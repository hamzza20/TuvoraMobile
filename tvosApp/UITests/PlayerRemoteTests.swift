import XCTest

/// B112 (Apple TV beta, 2026-10-01): "player controls not functioning properly", "forward/rewind hard
/// with the Siri Remote". The player follows AVPlayerViewController: Play/Pause always toggles, the
/// clickpad edges skip 10 s over bare video, arrows over the controls only move focus.
/// Needs the VOD range server (research/tvos-spike):  cd media && python3 ../range_server.py 8093
final class PlayerRemoteTests: XCTestCase {
    private let mkv = "http://127.0.0.1:8093/vod_h264_ac3.mkv"
    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        try XCTSkipUnless(reachable(mkv), "start the range server (see the class comment)")
        app = XCUIApplication()
        app.launchArguments = ["-smokePlay", mkv, "-smokePresent"]
        app.launch()
        XCTAssertTrue(now.waitForExistence(timeout: 30), "the player never appeared")
        XCTAssertTrue(wait(now, toRead: "Smoke test|playing", timeout: 45), "never played: \(labelOf(now))")
    }

    private var now: XCUIElement { app.descendants(matching: .any)["player.now"] }
    private var pos: XCUIElement { app.descendants(matching: .any)["player.pos"] }

    /// Was swallowed while the controls showed: it only re-armed their hide timer.
    func testPlayPauseTogglesWhileTheControlsShow() {
        XCUIRemote.shared.press(.up)                       // controls up
        Thread.sleep(forTimeInterval: 1)
        XCUIRemote.shared.press(.playPause)
        XCTAssertTrue(wait(now, toRead: "Smoke test|waiting", timeout: 5), "Play/Pause over the controls did not pause: \(labelOf(now)) trail \(trail())")
        XCUIRemote.shared.press(.playPause)
        XCTAssertTrue(wait(now, toRead: "Smoke test|playing", timeout: 8), "Play/Pause over the controls did not resume: \(labelOf(now))")
    }

    func testPlayPauseTogglesOverBareVideo() {
        Thread.sleep(forTimeInterval: 6)                   // controls auto-hide
        XCUIRemote.shared.press(.playPause)
        XCTAssertTrue(wait(now, toRead: "Smoke test|waiting", timeout: 5), "Play/Pause did not pause: \(labelOf(now)) trail \(trail())")
        XCUIRemote.shared.press(.playPause)
        XCTAssertTrue(wait(now, toRead: "Smoke test|playing", timeout: 8), "Play/Pause did not resume: \(labelOf(now))")
    }

    /// One click on the clickpad's right edge = one 10 s skip (not zero, not two), and no scrub left behind.
    func testRightEdgeSkipsTenSecondsOnce() {
        Thread.sleep(forTimeInterval: 6)                   // controls auto-hide
        let before = seconds()
        XCUIRemote.shared.press(.right)
        Thread.sleep(forTimeInterval: 2.5)
        let after = seconds()
        XCTAssertGreaterThanOrEqual(after - before, 10, "RIGHT did not skip forward (\(before) -> \(after)) trail \(trail())")
        // One skip, not two (libmpv lands on a keyframe, so the position alone can't tell).
        XCTAssertEqual(trail().components(separatedBy: "right>skipForward").count - 1, 1, "RIGHT did not skip exactly once: \(trail())")
        XCTAssertTrue(labelOf(pos).hasSuffix("|play"), "a skip left a scrub preview up: \(labelOf(pos))")
        XCTAssertTrue(wait(now, toRead: "Smoke test|playing", timeout: 8), "playback did not continue after the skip")
    }

    func testLeftEdgeSkipsBackTenSeconds() {
        Thread.sleep(forTimeInterval: 14)                  // far enough in to go back 10 s
        let before = seconds()
        XCUIRemote.shared.press(.left)
        Thread.sleep(forTimeInterval: 2.5)
        let after = seconds()
        XCTAssertLessThanOrEqual(after, before - 6, "LEFT did not skip back (\(before) -> \(after)) trail \(trail())")
        XCTAssertGreaterThanOrEqual(after, before - 13, "LEFT skipped back more than once (\(before) -> \(after))")
    }

    /// With the controls up the arrows move focus between the buttons; they never seek.
    func testArrowsOverTheControlsDoNotSeek() {
        XCUIRemote.shared.press(.up)
        Thread.sleep(forTimeInterval: 1)
        XCUIRemote.shared.press(.down)                     // onto the button row
        Thread.sleep(forTimeInterval: 0.5)
        let before = seconds()
        XCUIRemote.shared.press(.right)
        XCUIRemote.shared.press(.right)
        Thread.sleep(forTimeInterval: 1.5)
        let after = seconds()
        XCTAssertLessThan(after - before, 6, "arrows over the control buttons seeked (\(before) -> \(after))")
        XCTAssertTrue(wait(now, toRead: "Smoke test|playing", timeout: 5), "moving between buttons paused playback: \(labelOf(now))")
    }

    private func seconds() -> Int {
        Int(labelOf(pos).split(separator: "|").first ?? "") ?? -1
    }

    private func labelOf(_ element: XCUIElement) -> String { element.exists ? element.label : "<missing>" }

    /// The app's debug trail of how each press was handled (RemoteTrail).
    private func trail() -> String { labelOf(app.descendants(matching: .any)["player.remote"]) }

    private func wait(_ element: XCUIElement, toRead expected: String, timeout: TimeInterval) -> Bool {
        let end = Date().addingTimeInterval(timeout)
        while Date() < end {
            if element.exists && element.label == expected { return true }
            Thread.sleep(forTimeInterval: 0.5)
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
