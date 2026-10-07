import XCTest

/// Jellyfin / Emby as sources on Apple TV (Wave 3, J5), driven by the Siri Remote against a REAL local Jellyfin.
///
/// Needs a Jellyfin on http://localhost:18096 with the user `tester` / `tvtest123`, Quick Connect on, and a movie
/// library holding "Tuvora Test (2020)" longer than two minutes (the lane's docker recipe: see the J5 report). The
/// tests skip when it is not running. They sign in through the real screens; the server side (approving the
/// Quick Connect code, reading the saved position) goes through the server's own REST API.
/// The simulator must be signed in to a Tuvora account (like the other UI suites) and have no media servers yet.
final class MediaServerTests: XCTestCase {
    private let remote = XCUIRemote.shared
    private var app: XCUIApplication!
    private let server = "http://localhost:18096"
    private let serverName = "Tuvora Test Jelly"

    override func setUp() {
        continueAfterFailure = false
        app = XCUIApplication()
    }

    // MARK: Server REST helpers (the test plays the part of "another device")

    private var auth = "MediaBrowser Client=\"j5test\", Device=\"uitest\", DeviceId=\"j5test-device\", Version=\"1\""

    private func request(_ method: String, _ path: String, token: String? = nil, body: Data? = nil) -> (Int, Data) {
        var req = URLRequest(url: URL(string: server + path)!)
        req.httpMethod = method
        req.setValue(token.map { "\(auth), Token=\"\($0)\"" } ?? auth, forHTTPHeaderField: "Authorization")
        if let body { req.httpBody = body; req.setValue("application/json", forHTTPHeaderField: "Content-Type") }
        req.timeoutInterval = 10
        var out: (Int, Data) = (-1, Data())
        let done = expectation(description: path)
        URLSession.shared.dataTask(with: req) { data, response, _ in
            out = ((response as? HTTPURLResponse)?.statusCode ?? -1, data ?? Data()); done.fulfill()
        }.resume()
        wait(for: [done], timeout: 15)
        return out
    }

    private func login() -> (token: String, userId: String)? {
        let (code, data) = request("POST", "/Users/AuthenticateByName", body: #"{"Username":"tester","Pw":"tvtest123"}"#.data(using: .utf8))
        guard code == 200, let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let token = json["AccessToken"] as? String, let user = json["User"] as? [String: Any], let id = user["Id"] as? String
        else { return nil }
        return (token, id)
    }

    private func movieId(_ session: (token: String, userId: String)) -> String? {
        let (_, data) = request("GET", "/Items?Recursive=true&IncludeItemTypes=Movie&userId=\(session.userId)", token: session.token)
        let items = (try? JSONSerialization.jsonObject(with: data) as? [String: Any])?["Items"] as? [[String: Any]]
        return items?.first?["Id"] as? String
    }

    private func savedPositionTicks(_ session: (token: String, userId: String), itemId: String) -> Int64 {
        let (_, data) = request("GET", "/Items/\(itemId)?userId=\(session.userId)", token: session.token)
        let user = (try? JSONSerialization.jsonObject(with: data) as? [String: Any])?["UserData"] as? [String: Any]
        return (user?["PlaybackPositionTicks"] as? NSNumber)?.int64Value ?? 0
    }

    private func requireServer() throws -> (token: String, userId: String) {
        guard let s = login() else { throw XCTSkip("start the local Jellyfin (see the class comment)") }
        return s
    }

    // MARK: Remote helpers

    private var focused: XCUIElement {
        app.descendants(matching: .any).element(matching: NSPredicate(format: "hasFocus == true"))
    }
    private func focusedId() -> String { focused.exists ? (focused.identifier.isEmpty ? "#" + focused.label : focused.identifier) : "<none>" }
    private func press(_ b: XCUIRemote.Button, settle: TimeInterval = 0.8) { remote.press(b); Thread.sleep(forTimeInterval: settle) }
    private func waitFocus(_ timeout: TimeInterval = 10, _ match: (String) -> Bool) -> Bool {
        let end = Date().addingTimeInterval(timeout)
        while Date() < end { if match(focusedId()) { return true }; Thread.sleep(forTimeInterval: 0.4) }
        return false
    }
    private func downTo(_ id: String, max: Int = 14) -> Bool {
        for _ in 0..<max { if focusedId() == id { return true }; press(.down, settle: 0.5) }
        return focusedId() == id
    }
    private func visibleIds() -> String {
        app.descendants(matching: .any).allElementsBoundByIndex.prefix(80).map { $0.identifier.isEmpty ? "#" + $0.label : $0.identifier }.joined(separator: ",")
    }
    private func el(_ id: String) -> XCUIElement { app.descendants(matching: .any)[id] }

    private func launchToMediaServers(cleaned: Bool = false) {
        app.launchArguments = ["-smokePickProfile", "1", "-smokeTab", "settings", "-smokeSettings", "integrations",
                               "-smokeIntegration", "mediaservers"]
        app.launch()
        XCTAssertTrue(waitFocus(40) { $0.hasPrefix("settings.rail") }, "Settings never took focus; on \(focusedId())")
        press(.right, settle: 1.2)                      // into the pane
        XCTAssertTrue(waitFocus(15) { $0.hasPrefix("mediaServers.") }, "Media servers never took focus; on \(focusedId())")
        // A server left over from an earlier run is removed first, so every test starts from an empty list.
        if !cleaned && el("mediaServers.row.\(serverName)").exists {
            try? removeServerFromDetails()
            app.terminate()
            return launchToMediaServers(cleaned: true)
        }
        for _ in 0..<4 where focusedId() != "mediaServers.add" { press(.up, settle: 0.5) }
        XCTAssertEqual(focusedId(), "mediaServers.add", "could not reach Add a server")
    }

    /// Opens Add a server and types the address, then Connect: lands on the sign-in choice.
    private func enterAddressAndConnect() {
        press(.select, settle: 1.5)
        XCTAssertTrue(waitFocus { $0 == "mediaServers.address" }, "the address field did not take focus; on \(focusedId())")
        snap("1-address")
        press(.select, settle: 2)                       // opens the tvOS keyboard
        app.typeText("localhost:18096")
        press(.menu, settle: 1.5)                       // back from the keyboard, keeping the text
        press(.down, settle: 0.6)                       // the button row: Cancel first, Connect beside it
        if focusedId() != "mediaServers.connect" { press(.right, settle: 0.6) }
        XCTAssertEqual(focusedId(), "mediaServers.connect", "never reached Connect")
        press(.select, settle: 1)
    }

    // MARK: Tests

    /// Add by Quick Connect: the code is on screen, "another device" approves it, the server joins the list signed in,
    /// and the "Recently added on Home?" question appears and is answered yes.
    func testAddServerByQuickConnectThenShowRecentlyAddedOnHome() throws {
        let session = try requireServer()
        launchToMediaServers()
        enterAddressAndConnect()
        XCTAssertTrue(waitFocus(20) { $0 == "mediaServers.quickConnect" }, "no Quick Connect row after connecting; on \(focusedId())")
        snap("2-choose")
        press(.select, settle: 1)
        let code = el("mediaServers.qcCode")
        XCTAssertTrue(code.waitForExistence(timeout: 20), "the Quick Connect code never showed")
        snap("3-quick-connect-code")
        let digits = code.label.filter(\.isNumber)
        XCTAssertEqual(digits.count, 6, "the code is not 6 digits: \(code.label)")
        let (status, _) = request("POST", "/QuickConnect/Authorize?code=\(digits)", token: session.token)
        XCTAssertTrue((200..<300).contains(status), "the server refused to approve the code (\(status))")
        // The poll finishes the sign-in by itself; then the one question about Home.
        XCTAssertTrue(el("mediaServers.showOnHome").waitForExistence(timeout: 40), "sign-in did not finish after the approval")
        XCTAssertTrue(waitFocus(10) { $0 == "mediaServers.showOnHome" }, "the Home question did not take focus; on \(focusedId())")
        snap("4-home-offer")
        press(.select, settle: 2)
        // Back on the list: the server is there with its own name and the Signed in badge.
        let row = el("mediaServers.row.\(serverName)")
        XCTAssertTrue(row.waitForExistence(timeout: 15), "the new server is not on the list")
        XCTAssertTrue(row.label.contains("Signed in") || row.label.contains("Checking"), "no signed-in badge: \(row.label)")
        snap("5-list")
        try removeServerFromDetails()
    }

    /// Username + password sign-in, a wrong password first (the error is named), then the details page and Remove.
    func testPasswordSignInWrongThenRightAndRemove() throws {
        _ = try requireServer()
        launchToMediaServers()
        enterAddressAndConnect()
        XCTAssertTrue(waitFocus(20) { $0.hasPrefix("mediaServers.") }, "no sign-in choice; on \(focusedId())")
        XCTAssertTrue(downTo("mediaServers.usePassword", max: 3), "no password row; on \(focusedId())")
        press(.select, settle: 1)
        XCTAssertTrue(waitFocus { $0 == "mediaServers.username" || $0.hasPrefix("#") }, "the password step did not appear; on \(focusedId())")
        typeInto("mediaServers.username", "tester")
        typeInto("mediaServers.password", "wrong-password")
        XCTAssertTrue(reachSignIn(), "never reached Sign in; on \(focusedId())")
        press(.select, settle: 2.5)
        XCTAssertTrue(el("mediaServers.error").waitForExistence(timeout: 15), "a wrong password was not reported")
        // The password field was cleared by the submit: type the right one.
        typeInto("mediaServers.password", "tvtest123")
        XCTAssertTrue(reachSignIn(), "never reached Sign in; on \(focusedId())")
        press(.select, settle: 2.5)
        XCTAssertTrue(el("mediaServers.showOnHome").waitForExistence(timeout: 30), "password sign-in did not finish")
        press(.left, settle: 0.6); press(.select, settle: 2)   // "Not now"
        XCTAssertTrue(el("mediaServers.row.\(serverName)").waitForExistence(timeout: 15), "the server is not on the list")
        try removeServerFromDetails()
    }

    /// Plays a server movie from its Home row, then checks the server holds a resume position from the player's
    /// start/progress/stop reports (the session reporter, fed by TvPlayerSession).
    func testPlayingFromTheServerReportsProgressToIt() throws {
        let session = try requireServer()
        guard let item = movieId(session) else { throw XCTSkip("the test library is empty") }
        _ = request("DELETE", "/UserPlayedItems/\(item)?userId=\(session.userId)", token: session.token)   // unplayed again
        XCTAssertEqual(savedPositionTicks(session, itemId: item), 0, "the test movie already has a saved position; reset it first")
        launchToMediaServers()
        enterAddressAndConnect()
        XCTAssertTrue(waitFocus(20) { $0.hasPrefix("mediaServers.") })
        XCTAssertTrue(downTo("mediaServers.usePassword", max: 3))
        press(.select, settle: 1)
        typeInto("mediaServers.username", "tester")
        typeInto("mediaServers.password", "tvtest123")
        XCTAssertTrue(reachSignIn(), "never reached Sign in; on \(focusedId())")
        press(.select, settle: 2.5)
        XCTAssertTrue(el("mediaServers.showOnHome").waitForExistence(timeout: 30))
        press(.select, settle: 2)                       // yes: Recently added joins Home
        // Home: the server's row shows its movie.
        app.terminate()
        app.launchArguments = ["-smokePickProfile", "1", "-smokeTab", "home"]
        app.launch()
        XCTAssertTrue(waitFocus(60) { !$0.hasPrefix("sidebar.") && $0 != "<none>" }, "Home never took focus; on \(focusedId())")
        // The server's row sits after the add-on rows: walk DOWN until a card of its movie has focus.
        var found = false
        for _ in 0..<30 {
            if focused.exists && focused.label.contains("Tuvora Test") { found = true; break }
            press(.down, settle: 1.0)
        }
        XCTAssertTrue(found, "the server's Recently added row never showed its movie on Home; on \(focusedId())")
        // Card -> title page (or, from Continue Watching, straight to the sources) -> Play -> the one source.
        press(.select, settle: 4)
        snap("titlepage")
        for _ in 0..<4 where !el("player.now").exists { press(.select, settle: 4); snap("afterselect") }
        let now = el("player.now")
        XCTAssertTrue(now.waitForExistence(timeout: 90), "the player never opened")
        let end = Date().addingTimeInterval(60)
        while Date() < end && !now.label.hasSuffix("|playing") { Thread.sleep(forTimeInterval: 1) }
        XCTAssertTrue(now.label.hasSuffix("|playing"), "never played: \(now.label)")
        Thread.sleep(forTimeInterval: 25)               // a progress report is due every 15 s while playing
        press(.menu, settle: 2); press(.menu, settle: 3)  // hide controls if any, then leave: the stop report
        let saved = (0..<10).map { _ -> Int64 in Thread.sleep(forTimeInterval: 1); return savedPositionTicks(session, itemId: item) }.max() ?? 0
        XCTAssertGreaterThan(saved, 10 * 10_000_000, "the server holds no resume position after playing 25+ s (ticks=\(saved))")
        try? removeServerFromDetailsIfPresent()
    }

    // MARK: Shared steps

    /// The password step's button row is Back | Sign in: DOWN from the password field, then RIGHT.
    private func reachSignIn() -> Bool {
        for _ in 0..<3 where focusedId() != "mediaServers.signIn" { press(.down, settle: 0.5); if focusedId() == "#Back" { press(.right, settle: 0.5) } }
        return focusedId() == "mediaServers.signIn"
    }

    private func snap(_ name: String) {
        let a = XCTAttachment(screenshot: XCUIScreen.main.screenshot()); a.name = name; a.lifetime = .keepAlways; add(a)
    }

    private func typeInto(_ id: String, _ text: String) {
        var reached = focusedId() == id
        for _ in 0..<6 where !reached { press(.up, settle: 0.5); reached = focusedId() == id }
        if !reached { reached = downTo(id, max: 8) }
        XCTAssertTrue(reached, "never reached \(id); on \(focusedId())")
        press(.select, settle: 2)
        app.typeText(text)
        press(.menu, settle: 1.5)
    }

    /// List -> the server's details page -> Remove (Cancel is focused first; Remove confirms).
    private func removeServerFromDetails() throws {
        let row = el("mediaServers.row.\(serverName)")
        XCTAssertTrue(row.waitForExistence(timeout: 10))
        XCTAssertTrue(downTo("mediaServers.row.\(serverName)", max: 4), "never reached the server row; on \(focusedId())")
        press(.select, settle: 2)
        snap("6-details")
        XCTAssertTrue(el("mediaServers.remove").waitForExistence(timeout: 10), "the details page did not open; focus \(focusedId()); ids: \(visibleIds())")
        XCTAssertTrue(downTo("mediaServers.remove", max: 14), "never reached Remove; on \(focusedId())")
        press(.select, settle: 1.5)
        press(.right, settle: 0.6)
        XCTAssertTrue(waitFocus { $0 == "mediaServers.removeConfirm" }, "Remove did not reach the confirm button; on \(focusedId())")
        press(.select, settle: 3)
        XCTAssertFalse(el("mediaServers.row.\(serverName)").waitForExistence(timeout: 5), "the server is still listed after Remove")
    }

    private func removeServerFromDetailsIfPresent() throws {
        app.terminate()
        launchToMediaServers()
        if el("mediaServers.row.\(serverName)").waitForExistence(timeout: 8) { try removeServerFromDetails() }
    }
}
