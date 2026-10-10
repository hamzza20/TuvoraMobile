import QuartzCore
import XCTest
@testable import TuvoraTV

/// RenderThreadExitGuard is shared verbatim with the iOS app (iosApp/iosApp/Player), which has no unit
/// test target — these cover both.
final class RenderThreadExitGuardTests: XCTestCase {
    func testDetachesOnMainBeforeARenderThreadExitsWithTheLayerOnScreen() {
        let parent = CALayer()
        let layer = CALayer()
        parent.addSublayer(layer)
        let exitGuard = RenderThreadExitGuard()
        let detached = expectation(description: "detached on main")
        var detachRanOnMain = false
        exitGuard.onDetachNeeded = {
            detachRanOnMain = Thread.isMainThread
            layer.removeFromSuperlayer()
            detached.fulfill()
        }

        let thread = Thread {
            exitGuard.noteRenderThread(layer: layer)
            layer.bounds = CGRect(x: 0, y: 0, width: 4, height: 4) // a pending implicit transaction
        }
        thread.start()

        wait(for: [detached], timeout: 2)
        XCTAssertTrue(detachRanOnMain)
        XCTAssertNil(layer.superlayer)
    }

    func testLeavesMainAloneWhenTheBridgeAlreadyDetachedTheLayer() {
        let layer = CALayer() // no superlayer: the bridge's own teardown detached it first
        let exitGuard = RenderThreadExitGuard()
        let detached = expectation(description: "detach must not run")
        detached.isInverted = true
        exitGuard.onDetachNeeded = { detached.fulfill() }

        let thread = joinableThread { exitGuard.noteRenderThread(layer: layer) }
        pthread_join(thread, nil)

        wait(for: [detached], timeout: 0.5)
    }

    func testMainBlockedJoiningTheRenderThreadIsNotDeadlocked() {
        // The real crash shape: Main sits in mpv_set_property("vid") → vo_destroy → pthread_join.
        let parent = CALayer()
        let layer = CALayer()
        parent.addSublayer(layer)
        let exitGuard = RenderThreadExitGuard()
        var detachCalls = 0
        exitGuard.onDetachNeeded = { detachCalls += 1 }

        let started = Date()
        let thread = joinableThread { exitGuard.noteRenderThread(layer: layer) }
        pthread_join(thread, nil) // blocks Main, like vo_destroy does
        let joinedAfter = Date().timeIntervalSince(started)

        XCTAssertLessThan(joinedAfter, 2, "the exit hook must give up instead of waiting for Main")
        RunLoop.main.run(until: Date().addingTimeInterval(0.2)) // let the late main block run
        XCTAssertEqual(detachCalls, 0, "an abandoned hand-off must not detach later")
        XCTAssertNotNil(layer.superlayer)
    }

    func testReportsEachNewRenderThreadOnce() {
        let layer = CALayer()
        let exitGuard = RenderThreadExitGuard()
        var starts = 0
        let lock = NSLock()
        exitGuard.onRenderThreadStarted = { lock.lock(); starts += 1; lock.unlock() }

        for _ in 0..<2 {
            let thread = joinableThread {
                exitGuard.noteRenderThread(layer: layer)
                exitGuard.noteRenderThread(layer: layer) // every frame calls it
            }
            pthread_join(thread, nil)
        }
        exitGuard.noteRenderThread(layer: layer) // Main is never a render thread

        lock.lock(); defer { lock.unlock() }
        XCTAssertEqual(starts, 2)
    }

    private final class Body {
        let run: () -> Void
        init(_ run: @escaping () -> Void) { self.run = run }
    }

    private func joinableThread(_ body: @escaping () -> Void) -> pthread_t {
        var thread: pthread_t?
        let context = Unmanaged.passRetained(Body(body)).toOpaque()
        let status = pthread_create(&thread, nil, { raw in
            Unmanaged<Body>.fromOpaque(raw).takeRetainedValue().run()
            return nil
        }, context)
        precondition(status == 0)
        return thread!
    }
}
