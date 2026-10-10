// ---- LOCAL (not in the upstream fork) ----
// Shared verbatim with tvosApp/TuvoraTV/Sources/Player/RenderThreadExitGuard.swift — keep them identical.
//
// Covers the vo teardowns the app does not start itself. detachVideoLayerForVoTeardown takes the video
// layer out of the window before every teardown the BRIDGE asks for (destroy, background, PiP), but
// mpv also destroys its vo on its own (a load that fails after video was up, the video track going
// away). The vo thread has no run loop, so its pending implicit CATransaction — MoltenVK's layer
// writes, our pending drawableSize — commits from the thread's TSD cleanup. If the layer is still on
// screen then, that commit lays out and displays the window off the main thread and Compose aborts
// in SurfaceMetalRedrawer.draw ("must be called on main thread"; TestFlight 2026-09-24, crash stack
// _pthread_tsd_cleanup → CA::Transaction::release_thread). Rotation leaves Compose's view
// needing display, which is why the reports read "crashed when I rotated".
//
// The hook is a dynamic pthread key on the render thread. libpthread runs dynamic-key destructors
// before the static framework keys (pthread_tsd.c _pthread_tsd_cleanup_new), and QuartzCore keeps
// its per-thread transaction in a static key (72, __PTK_FRAMEWORK_QUARTZCORE_KEY2 — measured on this
// OS), so the hook runs while the transaction is still uncommitted and can detach the layer first.
//
// It never waits on Main unboundedly: Main is often the thread JOINING the vo thread (mpv_set_property
// "vid" → vo_destroy → pthread_join), so a sync hop would deadlock. Bridge-started teardowns already
// detached the layer, so the hook returns before touching Main; for anything else it waits at most
// [mainHandoffTimeout] and then lets the thread exit as it did before.

import Foundation
import QuartzCore

final class RenderThreadExitGuard {
    /// Main-thread action that takes the video layer out of the window. Set by the bridge.
    var onDetachNeeded: (() -> Void)? {
        get { lock.lock(); defer { lock.unlock() }; return storedOnDetachNeeded }
        set { lock.lock(); storedOnDetachNeeded = newValue; lock.unlock() }
    }

    /// Called ON the render thread the first time a new render thread acquires a drawable.
    var onRenderThreadStarted: (() -> Void)? {
        get { lock.lock(); defer { lock.unlock() }; return storedOnRenderThreadStarted }
        set { lock.lock(); storedOnRenderThreadStarted = newValue; lock.unlock() }
    }

    static let mainHandoffTimeout: DispatchTimeInterval = .milliseconds(250)

    private let lock = NSLock()
    private var storedOnDetachNeeded: (() -> Void)?
    private var storedOnRenderThreadStarted: (() -> Void)?

    private static let threadKey: pthread_key_t = {
        var key = pthread_key_t()
        let status = pthread_key_create(&key) { raw in
            Unmanaged<Registration>.fromOpaque(raw).takeRetainedValue().threadWillExit()
        }
        precondition(status == 0, "pthread_key_create failed: \(status)")
        return key
    }()

    /// Call from the render thread (nextDrawable). Registers the exit hook once per thread.
    func noteRenderThread(layer: CALayer) {
        guard !Thread.isMainThread else { return }
        let key = Self.threadKey
        guard pthread_getspecific(key) == nil else { return }
        let registration = Registration(owner: self, layer: layer)
        pthread_setspecific(key, Unmanaged.passRetained(registration).toOpaque())
        onRenderThreadStarted?()
    }

    fileprivate func renderThreadWillExit(layer: CALayer) {
        // Already out of the window (the bridge detached before its own teardown): nothing to do,
        // and Main may be blocked joining this very thread, so do not touch it.
        guard layer.superlayer != nil, let detach = onDetachNeeded else { return }

        let handoff = MainHandoff()
        let done = DispatchSemaphore(value: 0)
        DispatchQueue.main.async {
            if handoff.claim() { detach() }
            done.signal()
        }
        if done.wait(timeout: .now() + Self.mainHandoffTimeout) == .timedOut {
            if handoff.abandon() {
                InAppLogBridge.shared.warn(
                    tag: "MPV/iOS",
                    message: "Render thread exited with the video layer on screen; Main busy, detach abandoned"
                )
                return
            }
            // Main claimed it just as we timed out and is detaching now: let it finish first.
            done.wait()
        }
    }

    private final class Registration {
        weak var owner: RenderThreadExitGuard?
        weak var layer: CALayer?

        init(owner: RenderThreadExitGuard, layer: CALayer) {
            self.owner = owner
            self.layer = layer
        }

        func threadWillExit() {
            guard let owner, let layer else { return }
            owner.renderThreadWillExit(layer: layer)
        }
    }

    /// Exactly one of Main (claim) and the exiting thread (abandon) wins.
    private final class MainHandoff {
        private let lock = NSLock()
        private var settled = false

        func claim() -> Bool { settle() }
        func abandon() -> Bool { settle() }

        private func settle() -> Bool {
            lock.lock()
            defer { lock.unlock() }
            guard !settled else { return false }
            settled = true
            return true
        }
    }
}
