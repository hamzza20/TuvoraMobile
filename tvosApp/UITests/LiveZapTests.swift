import XCTest

/// Live channel zapping in the presented player (PlaybackCoordinator swaps the session in place).
/// Needs two local live relays (research/tvos-spike/live_relay.py):
///   python3 live_relay.py 8941 h264 &   python3 live_relay.py 8942 h264 &
/// The test skips when they are not running.
final class LiveZapTests: XCTestCase {
    private let first = "http://127.0.0.1:8941/live.ts"
    private let second = "http://127.0.0.1:8942/live.ts"

    override func setUp() {
        continueAfterFailure = false
    }

    /// Suspected 2026-10-03: the swapped-in screen kept the old screen's identity, so the new channel's
    /// session was never attached (no player opened) while the closed one stayed on screen.
    func testZappingStartsTheNextChannelsOwnSession() throws {
        try XCTSkipUnless(reachable(first) && reachable(second), "start the two live relays (see the class comment)")
        let app = XCUIApplication()
        app.launchArguments = ["-smokePlay", first, "-smokeZapTo", second]
        app.launch()

        let now = app.descendants(matching: .any)["player.now"]
        XCTAssertTrue(now.waitForExistence(timeout: 30), "the player never appeared")
        XCTAssertTrue(wait(now, toRead: "Smoke test|playing", timeout: 45), "first channel never played: \(now.label)")

        Thread.sleep(forTimeInterval: 8)   // let the controls auto-hide; UP zaps only over hidden controls
        XCUIRemote.shared.press(.up)

        XCTAssertTrue(
            wait(now, toRead: "Smoke zap -1|playing", timeout: 45),
            "the next channel's own session never played after the zap: \(now.label)"
        )
    }

    private func wait(_ element: XCUIElement, toRead expected: String, timeout: TimeInterval) -> Bool {
        let end = Date().addingTimeInterval(timeout)
        while Date() < end {
            if element.exists && element.label == expected { return true }
            Thread.sleep(forTimeInterval: 1)
        }
        return false
    }

    private func reachable(_ url: String) -> Bool {
        var request = URLRequest(url: URL(string: url)!, timeoutInterval: 3)
        request.httpMethod = "HEAD"
        let done = DispatchSemaphore(value: 0)
        var ok = false
        URLSession.shared.dataTask(with: request) { _, response, _ in
            ok = (response as? HTTPURLResponse)?.statusCode == 200
            done.signal()
        }.resume()
        _ = done.wait(timeout: .now() + 4)
        return ok
    }
}
