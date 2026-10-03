import XCTest

/// B112 (Apple TV beta, 2026-10-01): sound kept playing after leaving the player. Every way out of the
/// presented player must destroy its engine (`engines.live` marker, EngineLedger).
/// Needs the VOD range server (research/tvos-spike):  cd media && python3 ../range_server.py 8093
/// The tests skip when it is not running.
final class PlayerExitTests: XCTestCase {
    private let mkv = "http://127.0.0.1:8093/vod_h264_ac3.mkv"   // libmpv lane
    private let mp4 = "http://127.0.0.1:8093/vod_h264_aac.mp4"   // AVPlayer lane

    override func setUp() {
        continueAfterFailure = false
    }

    func testMenuLeavesTheLibmpvPlayerAndStopsItsEngine() throws {
        try leaveWithMenu(url: mkv)
    }

    func testMenuLeavesTheAVPlayerPlayerAndStopsItsEngine() throws {
        try leaveWithMenu(url: mp4)
    }

    /// Menu while the stream is still opening (an address that never answers): the player leaves and
    /// its engine goes, instead of starting to play with no screen once the stream finally arrives.
    func testMenuWhileLoadingLeavesAndStopsTheEngine() throws {
        let app = launch("http://10.255.255.1/never-answers.mkv")
        let now = app.descendants(matching: .any)["player.now"]
        XCTAssertTrue(now.waitForExistence(timeout: 30), "the player never appeared")
        Thread.sleep(forTimeInterval: 3)   // presented and still opening
        XCTAssertEqual(labelOf(now), "Smoke test|waiting", "the test stream was not still loading")
        XCUIRemote.shared.press(.menu)
        let engines = app.descendants(matching: .any)["engines.live"]
        XCTAssertTrue(waitGone(now, timeout: 15), "the player is still on screen after Menu; trail \(trail())")
        XCTAssertTrue(wait(engines, toRead: "engines=0", timeout: 15), "an engine outlived the player: \(labelOf(engines))")
        Thread.sleep(forTimeInterval: 5)
        XCTAssertEqual(labelOf(engines), "engines=0", "an engine came back after the viewer left")
        XCTAssertEqual(app.state, .runningForeground, "Menu left the app instead of the player")
    }

    /// Menu with the controls showing first hides them (tvOS convention), the next Menu leaves.
    func testFirstMenuHidesControlsThenSecondLeaves() throws {
        try XCTSkipUnless(reachable(mkv), "start the range server (see the class comment)")
        let app = launch(mkv)
        let now = app.descendants(matching: .any)["player.now"]
        XCTAssertTrue(now.waitForExistence(timeout: 30), "the player never appeared")
        XCTAssertTrue(wait(now, toRead: "Smoke test|playing", timeout: 45), "never played: \(now.label)")
        XCUIRemote.shared.press(.up)               // shows the controls
        Thread.sleep(forTimeInterval: 1)
        XCUIRemote.shared.press(.menu)             // hides them; the player stays
        Thread.sleep(forTimeInterval: 2)
        XCTAssertTrue(now.exists, "Menu over the controls left the player instead of hiding them")
        XCUIRemote.shared.press(.menu)
        let engines = app.descendants(matching: .any)["engines.live"]
        XCTAssertTrue(waitGone(now, timeout: 15), "the second Menu did not leave the player; trail \(trail())")
        XCTAssertTrue(wait(engines, toRead: "engines=0", timeout: 15), "an engine outlived the player: \(labelOf(engines))")
    }

    /// Menu inside the audio/subtitle panel closes only the panel (it used to be able to take the whole
    /// player with it); the player leaves only when nothing is left to close.
    func testMenuClosesThePanelBeforeLeaving() throws {
        try XCTSkipUnless(reachable(mkv), "start the range server (see the class comment)")
        let app = launch(mkv)
        let now = app.descendants(matching: .any)["player.now"]
        XCTAssertTrue(wait(now, toRead: "Smoke test|playing", timeout: 45), "never played: \(labelOf(now))")
        Thread.sleep(forTimeInterval: 6)           // controls auto-hide
        XCUIRemote.shared.press(.down)             // audio/subtitle panel
        Thread.sleep(forTimeInterval: 1.5)
        let afterDown = trail()
        XCUIRemote.shared.press(.menu)
        Thread.sleep(forTimeInterval: 1.5)
        XCTAssertTrue(now.exists, "Menu in the panel left the player; trail after DOWN \(afterDown)")
        XCTAssertTrue(trail().contains("down>openTracks"), "DOWN did not open the panel: \(trail())")
        XCTAssertTrue(trail().hasSuffix("back>closePanel"), "Menu in the panel did more than close it: \(trail())")
        for _ in 0..<3 where now.exists {
            XCUIRemote.shared.press(.menu)
            Thread.sleep(forTimeInterval: 1.5)
        }
        let engines = app.descendants(matching: .any)["engines.live"]
        XCTAssertTrue(waitGone(now, timeout: 10), "Menu never left the player; trail \(trail())")
        XCTAssertTrue(wait(engines, toRead: "engines=0", timeout: 15), "an engine outlived the player: \(labelOf(engines))")
    }

    private func leaveWithMenu(url: String) throws {
        try XCTSkipUnless(reachable(url), "start the range server (see the class comment)")
        let app = launch(url)
        let now = app.descendants(matching: .any)["player.now"]
        XCTAssertTrue(now.waitForExistence(timeout: 30), "the player never appeared")
        XCTAssertTrue(wait(now, toRead: "Smoke test|playing", timeout: 45), "never played: \(now.label)")
        // Menu until the player is gone (the first may only hide the controls), at most three presses.
        for _ in 0..<3 where now.exists {
            XCUIRemote.shared.press(.menu)
            Thread.sleep(forTimeInterval: 2)
        }
        let engines = app.descendants(matching: .any)["engines.live"]
        XCTAssertTrue(waitGone(now, timeout: 10), "Menu never left the player; trail \(trail())")
        XCTAssertTrue(wait(engines, toRead: "engines=0", timeout: 15), "an engine outlived the player (sound keeps playing): \(labelOf(engines))")
        XCTAssertEqual(app.state, .runningForeground, "Menu left the app instead of the player")
    }

    private func launch(_ url: String) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-smokePlay", url, "-smokePresent"]
        app.launch()
        return app
    }

    private func wait(_ element: XCUIElement, toRead expected: String, timeout: TimeInterval) -> Bool {
        let end = Date().addingTimeInterval(timeout)
        while Date() < end {
            if element.exists && element.label == expected { return true }
            Thread.sleep(forTimeInterval: 1)
        }
        return false
    }

    /// Gone for three reads in a row: a single read can miss an element while a snapshot fails.
    private func waitGone(_ element: XCUIElement, timeout: TimeInterval) -> Bool {
        let end = Date().addingTimeInterval(timeout)
        var absent = 0
        while Date() < end {
            absent = element.exists ? 0 : absent + 1
            if absent >= 3 { return true }
            Thread.sleep(forTimeInterval: 0.7)
        }
        return false
    }

    private func labelOf(_ element: XCUIElement) -> String { element.exists ? element.label : "<missing>" }

    /// The app's debug trail of how each press was handled (RemoteTrail).
    private func trail() -> String { labelOf(XCUIApplication().descendants(matching: .any)["player.remote"]) }

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
