import Foundation
import UIKit

/// Apple TV half of MPVPlayerViewController+PictureInPicture.swift (iOS). tvOS has no
/// AVSampleBufferDisplayLayer PiP path for a custom renderer, so Picture-in-Picture is a named gap
/// here: the bridge reports it unsupported. What the iOS file does besides PiP — pausing and
/// releasing the video output when the app leaves the screen, and rejoining the live edge on
/// return — is kept, taken from its non-PiP branches.
extension MPVPlayerViewController {

    func setupNotifications() {
        NotificationCenter.default.addObserver(self, selector: #selector(enterBackground),
                                               name: UIApplication.didEnterBackgroundNotification, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(enterForeground),
                                               name: UIApplication.willEnterForegroundNotification, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(displaySwitchWillBegin),
                                               name: LiveDisplayCriteriaController.switchWillBegin, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(displaySwitchDidSettle),
                                               name: LiveDisplayCriteriaController.switchDidSettle, object: nil)
    }

    /// The TV is about to change display mode (live frame-rate matching): stop presenting video until
    /// it settles — MoltenVK crashes presenting into the CAMetalLayer mid-switch (NuvioTVOS, device).
    /// Audio plays on; `vid=auto` brings the picture back at the next keyframe.
    @objc func displaySwitchWillBegin() {
        guard mpv != nil, !videoIdledForDisplaySwitch else { return }
        videoIdledForDisplaySwitch = true
        metalLayer.releasePendingDrawable()
        setStringProperty("vid", "no")
        InAppLogBridge.shared.info(tag: "MPV/tvOS", message: "Video idled for display mode switch")
    }

    @objc func displaySwitchDidSettle() {
        guard videoIdledForDisplaySwitch else { return }
        videoIdledForDisplaySwitch = false
        // Backgrounded meanwhile: enterForeground restores the video.
        guard mpv != nil, UIApplication.shared.applicationState != .background else { return }
        setStringProperty("vid", "auto")
        InAppLogBridge.shared.info(tag: "MPV/tvOS", message: "Video restored after display mode switch")
    }

    @objc func enterBackground() {
        metalLayer.releasePendingDrawable()
        guard mpv != nil else { return }
        pausePlayback()
        detachVideoLayerForVoTeardown(reason: "background")
        setStringProperty("vid", "no")
    }

    @objc func enterForeground() {
        videoIdledForDisplaySwitch = false
        retryDeviceLossRecoveryNow()
        if !isAwaitingDeviceLossRecovery {
            metalLayer.setRenderingSuspended(false, reason: "enter-foreground")
        }
        guard mpv != nil else { return }
        reattachVideoLayerAfterVoTeardown(reason: "foreground")
        setStringProperty("vid", "auto")
        // A live stream paused in the background goes stale: reload to rejoin the live edge.
        if isLiveStream, let path = getString("path") {
            clearPlaybackError()
            applyRequestHeaders(activeRequestHeaders)
            command("loadfile", args: [path, "replace"])
        }
        playPlayback()
    }

    // Picture-in-Picture: unsupported on Apple TV (named gap, see the type comment).
    func setExperimentalSinglePrimaryPictureInPictureEnabled(_ enabled: Bool) {}
    func isPictureInPictureSupported() -> Bool { false }
    func startPictureInPicture() {}
    func stopPictureInPicture(source: String) {}
    func isPictureInPictureActive() -> Bool { false }
    func installExperimentalPictureInPictureCaptureIfNeeded() {}
    func layoutExperimentalPictureInPictureSurfaces(in bounds: CGRect) {}
    func prewarmAutomaticPictureInPictureSource(reason: String) {}
}
