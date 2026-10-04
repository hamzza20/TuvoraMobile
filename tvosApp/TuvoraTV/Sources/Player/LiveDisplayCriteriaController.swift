import AVFoundation
import AVKit
import CoreMedia
import TuvoraCore
import UIKit

/// Live TV display frame-rate matching on Apple TV: asks the TV for the channel's frame rate (25p/50i
/// -> 50 Hz, 29.97 -> 59.94, film at its own rate) through `AVDisplayManager.preferredDisplayCriteria`.
/// Parity of Android TV's live display-mode matching (NuvioTV 105207cb6).
///
/// - Decisions (target rate, when to switch, when to go back) are the shared, unit-tested
///   `LiveDisplayCriteriaPolicy` / `LiveDisplayCriteriaTracker`; this file only talks to the OS.
/// - Public API only: `AVDisplayCriteria(refreshRate:formatDescription:)` (tvOS 17; the app's minimum
///   is 17.5, so no availability gate). Never the private `init(refreshRate:videoDynamicRange:)`.
/// - tvOS honours the criteria only when the viewer enabled Settings › Video and Audio › Match Content;
///   with it off (the default) nothing is asked and nothing changes.
/// - The live player and the live guide hold Live TV while on screen ([enter] / [exit]); zaps and
///   guide <-> fullscreen keep the mode. Leaving Live TV (after a short grace) and backgrounding set
///   the criteria back to nil, the system default.
///
/// The HDMI mode switch rebuilds the display pipeline, and presenting libmpv's Vulkan frames into the
/// CAMetalLayer meanwhile crashes MoltenVK (device-observed in NuvioTVOS, the GPL-3 Apple TV fork of the
/// same upstream: MPVPlaybackController.swift "Display mode matching"). So every switch is bracketed by
/// [switchWillBegin] / [switchDidSettle]: libmpv idles its video output, and the player session pauses
/// its freeze watch, until `isDisplayModeSwitchInProgress` clears.
@MainActor
final class LiveDisplayCriteriaController {
    static let shared = LiveDisplayCriteriaController()

    /// Posted before criteria change: libmpv players stop presenting video until [switchDidSettle].
    static let switchWillBegin = Notification.Name("TuvoraDisplayModeSwitchWillBegin")
    /// Posted once the display has settled (or the wait timed out): video may present again.
    static let switchDidSettle = Notification.Name("TuvoraDisplayModeSwitchDidSettle")

    /// `UIWindow.avDisplayManager` is an Objective-C category in AVKit: calling it makes no link-time
    /// reference, so the linker can drop AVKit and the selector is missing at runtime (crashed on device
    /// in NuvioTVOS). Naming a real AVKit class keeps the framework linked.
    private static let avKitLinkAnchor: AnyClass = AVDisplayManager.self

    private let tracker = LiveDisplayCriteriaTracker()
    private var clearTask: Task<Void, Never>?
    private var settleTask: Task<Void, Never>?
    private var switching = false

    private init() {
        NotificationCenter.default.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { _ in
            MainActor.assumeIsolated { LiveDisplayCriteriaController.shared.backgrounded() }
        }
    }

    /// A live screen (fullscreen live player, live guide) is on show.
    func enter() {
        tracker.enter()
        clearTask?.cancel()
        clearTask = nil
    }

    /// That live screen is gone. When nothing live remains, the display goes back to default after
    /// the grace (time for the guide to reappear behind a closing player).
    func exit() {
        guard tracker.exit() else { return }
        clearTask?.cancel()
        clearTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(LiveDisplayCriteriaPolicy.shared.GRACE_MS) * 1_000_000)
            guard let self, !Task.isCancelled, self.tracker.graceElapsed() else { return }
            smokeLog("SMOKE afr clear (left Live TV)")
            self.apply(nil)
        }
    }

    /// The fullscreen live player's current criteria (every state update; cheap when unchanged).
    func offer(_ criteria: TvDisplayCriteria?) {
        guard let criteria else { return }
        _ = Self.avKitLinkAnchor
        guard let manager = Self.displayManager() else { return }
        let enabled = manager.isDisplayCriteriaMatchingEnabled
        guard let next = tracker.offer(criteria: criteria, matchingEnabled: enabled) else {
            if !enabled { logMatchingOffOnce(criteria) }
            return
        }
        smokeLog("SMOKE afr set %.3f Hz %@ %dx%d %@", next.refreshRate, "\(next.dynamicRange)", next.width, next.height, next.codec)
        apply(next)
    }

    private func backgrounded() {
        clearTask?.cancel()
        clearTask = nil
        guard tracker.backgrounded() else { return }
        smokeLog("SMOKE afr clear (background)")
        // The app is leaving the screen and libmpv has already released its video: no idle dance.
        Self.displayManager()?.preferredDisplayCriteria = nil
        finishSwitch()
    }

    private func apply(_ criteria: TvDisplayCriteria?) {
        guard let manager = Self.displayManager() else { return }
        let next: AVDisplayCriteria?
        if let criteria {
            guard let format = Self.formatDescription(for: criteria) else {
                smokeLog("SMOKE afr skipped: no format description for %@", criteria.codec)
                return
            }
            next = AVDisplayCriteria(refreshRate: Float(criteria.refreshRate), formatDescription: format)
        } else {
            next = nil
        }
        beginSwitch()
        manager.preferredDisplayCriteria = next
        settleTask?.cancel()
        settleTask = Task { [weak self] in
            // Give the switch a beat to start, then wait for it to end (at most ~4 s): NuvioTVOS timing.
            try? await Task.sleep(nanoseconds: 500_000_000)
            var attempts = 16
            while !Task.isCancelled, manager.isDisplayModeSwitchInProgress, attempts > 0 {
                attempts -= 1
                try? await Task.sleep(nanoseconds: 250_000_000)
            }
            guard let self, !Task.isCancelled else { return }
            self.finishSwitch()
        }
    }

    private func beginSwitch() {
        guard !switching else { return }
        switching = true
        NotificationCenter.default.post(name: Self.switchWillBegin, object: nil)
    }

    private func finishSwitch() {
        settleTask?.cancel()
        settleTask = nil
        guard switching else { return }
        switching = false
        NotificationCenter.default.post(name: Self.switchDidSettle, object: nil)
    }

    private var loggedMatchingOff = false
    private func logMatchingOffOnce(_ wanted: TvDisplayCriteria) {
        guard !loggedMatchingOff else { return }
        loggedMatchingOff = true
        smokeLog("SMOKE afr off: Match Content is disabled in Apple TV Settings (wanted %.3f Hz %@ %dx%d %@)",
              wanted.refreshRate, "\(wanted.dynamicRange)", wanted.width, wanted.height, wanted.codec)
    }

    private static func displayManager() -> AVDisplayManager? {
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        let window = scene?.windows.first(where: \.isKeyWindow) ?? scene?.windows.first
        return window?.avDisplayManager
    }

    /// A format description carrying the codec, size and colour tags, the same shape VLC
    /// (VLCSampleBufferDisplay.m) and NuvioTVOS build for a non-AVPlayer renderer. It is only a hint for
    /// the display mode; nothing is decoded through it.
    private static func formatDescription(for criteria: TvDisplayCriteria) -> CMVideoFormatDescription? {
        let codec: CMVideoCodecType
        switch criteria.codec.lowercased() {
        case "hevc", "h265": codec = kCMVideoCodecType_HEVC
        case "av1": codec = kCMVideoCodecType_AV1
        case "mpeg2video": codec = kCMVideoCodecType_MPEG2Video
        default: codec = kCMVideoCodecType_H264
        }
        let extensions: [CFString: Any]
        switch criteria.dynamicRange {
        case .pq, .hlg:
            extensions = [
                kCMFormatDescriptionExtension_ColorPrimaries: kCMFormatDescriptionColorPrimaries_ITU_R_2020,
                kCMFormatDescriptionExtension_TransferFunction: criteria.dynamicRange == .hlg
                    ? kCMFormatDescriptionTransferFunction_ITU_R_2100_HLG
                    : kCMFormatDescriptionTransferFunction_SMPTE_ST_2084_PQ,
                kCMFormatDescriptionExtension_YCbCrMatrix: kCMFormatDescriptionYCbCrMatrix_ITU_R_2020,
            ]
        default:
            extensions = [
                kCMFormatDescriptionExtension_ColorPrimaries: kCMFormatDescriptionColorPrimaries_ITU_R_709_2,
                kCMFormatDescriptionExtension_TransferFunction: kCMFormatDescriptionTransferFunction_ITU_R_709_2,
                kCMFormatDescriptionExtension_YCbCrMatrix: kCMFormatDescriptionYCbCrMatrix_ITU_R_709_2,
            ]
        }
        var description: CMVideoFormatDescription?
        let status = CMVideoFormatDescriptionCreate(
            allocator: kCFAllocatorDefault,
            codecType: codec,
            width: criteria.width,
            height: criteria.height,
            extensions: extensions as CFDictionary,
            formatDescriptionOut: &description
        )
        return status == noErr ? description : nil
    }
}
