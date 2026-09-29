// Ported from the GPL-3.0 fork luqmanfadlli/NuvioMobile-iOS (branch `enhanced`, 2026-08-18), which
// forks the same NuvioMedia/NuvioMobile upstream this repo does — so the licence is compatible.
//
// Why this approach: AVPictureInPictureController only accepts an AVPlayerLayer or an
// AVSampleBufferDisplayLayer, and we render libmpv -> libplacebo -> MoltenVK into a CAMetalLayer.
// Rather than change the renderer (which would cost gpu-next's HDR tone mapping, EDR, deband and
// interpolation), this taps the RENDERER'S OUTPUT: MetalLayer overrides nextDrawable(), the
// presented drawable's MTLTexture is GPU-blitted into a CVPixelBuffer, wrapped in a CMSampleBuffer
// and enqueued into an AVSampleBufferDisplayLayer that PiP is bound to. mpv is untouched.
//
// KEEP THESE FILES CLOSE TO THE UPSTREAM FORK so they can be re-synced with a clean diff. Local
// adaptations are confined to InAppLogBridge.swift (our logging shim) and the bridge wiring.

import Foundation
import UIKit
import QuartzCore
// LOCAL: addPresentedHandler is declared on MTLDrawable, not CAMetalLayer's QuartzCore surface.
// The upstream fork gets it transitively; we do not, so import it explicitly.
import Metal

class MetalLayer: CAMetalLayer {
    var onDrawablePresented: ((CAMetalDrawable) -> Void)?

    var onRenderingSuspensionChanged: ((Bool, String) -> Void)?

    var isDrawableCaptureArmed: Bool {
        get {
            captureLock.lock()
            defer { captureLock.unlock() }
            return storedIsDrawableCaptureArmed
        }
        set {
            captureLock.lock()
            storedIsDrawableCaptureArmed = newValue
            captureLock.unlock()
        }
    }

    var capturesWithoutPresentation: Bool {
        get {
            captureLock.lock()
            defer { captureLock.unlock() }
            return storedCapturesWithoutPresentation
        }
        set {
            captureLock.lock()
            storedCapturesWithoutPresentation = newValue
            captureLock.unlock()
        }
    }

    private(set) var capturedDrawableCount: UInt64 = 0

    private(set) var nextDrawableCallCount: UInt64 = 0

    private var storedIsDrawableCaptureArmed = false
    private var storedCapturesWithoutPresentation = false
    private var storedHasAcquiredDrawable = false
    private var pendingDrawable: CAMetalDrawable?
    // A drawable size handed over from the MAIN thread (layout during a fullscreen/orientation
    // resize) to be applied on the render thread inside nextDrawable(). See setPendingDrawableSize.
    private var pendingDrawableSize: CGSize?
    private var didLogRenderThread = false
    private let captureLock = NSLock()

    private static let failureThresholdBeforeSuspension = 2
    private static let suspendedRetryInterval: CFTimeInterval = 1.0
    private static let suspendedIdleSleep: TimeInterval = 0.03

    private var isRenderingSuspended = false
    private var isSuspensionLatched = false
    private var consecutiveAcquisitionFailures = 0
    private var lastSuspendedProbeTime: CFTimeInterval = 0

    var isSuspended: Bool {
        captureLock.lock()
        defer { captureLock.unlock() }
        return isRenderingSuspended
    }

    /// True once mpv's render thread has successfully acquired at least one drawable — i.e. the
    /// swapchain exists and rendering is live. Before this flips, the drawable pool is not being
    /// churned by a render thread, so the layer's drawableSize can be established directly from
    /// Main; after it, Main must hand resizes to nextDrawable() (setPendingDrawableSize) to avoid
    /// the iOS-26 drawable-pool heap race (EXC_BREAKPOINT _xzm_corruption_detected).
    var hasAcquiredDrawable: Bool {
        captureLock.lock()
        defer { captureLock.unlock() }
        return storedHasAcquiredDrawable
    }

    override var drawableSize: CGSize {
        get { return super.drawableSize }
        set {
            if Int(newValue.width) > 1 && Int(newValue.height) > 1 {
                super.drawableSize = newValue
            }
        }
    }

#if os(iOS)
    override var wantsExtendedDynamicRangeContent: Bool {
        get { return super.wantsExtendedDynamicRangeContent }
        set {
            if Thread.isMainThread {
                super.wantsExtendedDynamicRangeContent = newValue
            } else {
                DispatchQueue.main.async {
                    super.wantsExtendedDynamicRangeContent = newValue
                }
            }
        }
    }
#endif

    func setRenderingSuspended(_ suspended: Bool, reason: String) {
        captureLock.lock()
        let changed = isRenderingSuspended != suspended
        isRenderingSuspended = suspended
        isSuspensionLatched = suspended
        consecutiveAcquisitionFailures = 0
        lastSuspendedProbeTime = suspended ? CACurrentMediaTime() : 0
        let stale = pendingDrawable
        pendingDrawable = nil
        captureLock.unlock()

        withExtendedLifetime(stale) {}

        if changed { onRenderingSuspensionChanged?(suspended, reason) }
    }

    func releasePendingDrawable() {
        captureLock.lock()
        let stale = pendingDrawable
        pendingDrawable = nil
        captureLock.unlock()
        withExtendedLifetime(stale) {}
    }

    /// Hand over a new drawable size from the MAIN thread (layout during a fullscreen/orientation
    /// resize). We deliberately do NOT write `super.drawableSize` here: mpv's MoltenVK context
    /// (single Vulkan queue — vulkan-queue-count 1, async off) reads drawableSize and recreates its
    /// swapchain on its OWN render thread, and mutating the CAMetalLayer's drawable pool from Main
    /// concurrently corrupts the Metal heap (iOS 26 traps it as EXC_BREAKPOINT
    /// _xzm_corruption_detected). The pending size is applied inside nextDrawable(), on that same
    /// render thread, so the write and mpv's read are serialized onto one thread.
    func setPendingDrawableSize(_ size: CGSize) {
        captureLock.lock()
        pendingDrawableSize = size
        captureLock.unlock()
    }

    /// Applies any pending drawable size. MUST be called with `captureLock` held, from nextDrawable()
    /// (mpv's render thread). Writes `super.drawableSize` directly to bypass the clamping override.
    private func applyPendingDrawableSizeLocked() {
        guard let size = pendingDrawableSize else { return }
        pendingDrawableSize = nil
        if Int(size.width) > 1 && Int(size.height) > 1 {
            super.drawableSize = size
        }
    }

    override func nextDrawable() -> CAMetalDrawable? {
        captureLock.lock()
        nextDrawableCallCount &+= 1
        // Apply a resize handed over from Main on THIS (render) thread, before acquiring a drawable,
        // so the swapchain is only ever resized here. Both suspended and normal paths fall through
        // this point while the lock is held.
        applyPendingDrawableSizeLocked()
        if !didLogRenderThread {
            didLogRenderThread = true
            InAppLogBridge.shared.info(
                tag: "Player/iOS",
                message: "MetalLayer.nextDrawable render thread=\(Thread.current)"
            )
        }
        if isRenderingSuspended {
            let now = CACurrentMediaTime()
            let shouldProbe = now - lastSuspendedProbeTime >= Self.suspendedRetryInterval
            if shouldProbe {
                lastSuspendedProbeTime = now
            }
            let stale = pendingDrawable
            pendingDrawable = nil
            captureLock.unlock()
            withExtendedLifetime(stale) {}

            guard shouldProbe else {
                Thread.sleep(forTimeInterval: Self.suspendedIdleSleep)
                return nil
            }
        } else {
            captureLock.unlock()
        }

        let drawable = super.nextDrawable()
        let acquisitionFailed = drawable == nil

        captureLock.lock()
        var didSuspend = false
        var didResume = false
        if acquisitionFailed {
            consecutiveAcquisitionFailures += 1
            if !isRenderingSuspended &&
                consecutiveAcquisitionFailures >= Self.failureThresholdBeforeSuspension {
                isRenderingSuspended = true
                lastSuspendedProbeTime = CACurrentMediaTime()
                didSuspend = true
            }
        } else {
            consecutiveAcquisitionFailures = 0
            storedHasAcquiredDrawable = true
            if isRenderingSuspended && !isSuspensionLatched {
                isRenderingSuspended = false
                lastSuspendedProbeTime = 0
                didResume = true
            }
        }

        let suspendedNow = isRenderingSuspended
        let armed = storedIsDrawableCaptureArmed
        let handler = onDrawablePresented
        // LOCAL: the simulator's Metal has no MTLDrawable.addPresentedHandler (verified absent from
        // iPhoneSimulator SDK MTLDrawable.h, present in iPhoneOS). The deferred path never calls it
        // — it hands over the PREVIOUS drawable on the next acquisition — and is the same path the
        // fork already uses while backgrounded, i.e. while PiP is on screen. Forcing it here keeps
        // PiP testable in the simulator instead of compiling the feature out.
        #if targetEnvironment(simulator)
        let deferred = !suspendedNow
        #else
        let deferred = storedCapturesWithoutPresentation && !suspendedNow
        #endif
        let previous = pendingDrawable
        pendingDrawable = deferred ? drawable : nil
        captureLock.unlock()

        if didSuspend { onRenderingSuspensionChanged?(true, "drawable-acquisition-stalled") }
        if didResume { onRenderingSuspensionChanged?(false, "drawable-acquired") }

        guard !suspendedNow else { return drawable }

        guard armed, let drawable, let handler else { return drawable }

        if deferred {
            if let previous {
                captureLock.lock()
                capturedDrawableCount &+= 1
                captureLock.unlock()
                handler(previous)
            }
            return drawable
        }

        // Unreachable on the simulator: `deferred` is forced true above and returns before here.
        // Still compiled out, because the symbol does not exist in the simulator SDK.
        #if !targetEnvironment(simulator)
        drawable.addPresentedHandler { [weak self] _ in
            guard let self else { return }
            self.captureLock.lock()
            self.capturedDrawableCount &+= 1
            self.captureLock.unlock()
            handler(drawable)
        }

        return drawable
        #else
        return drawable
        #endif
    }
}
