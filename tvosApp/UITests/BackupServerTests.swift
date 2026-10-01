import XCTest

/// Step 0.3 on Apple TV: the playlist form's "Backup servers" section, driven by the Siri Remote.
/// These open the Add Playlist form through launch hooks and never press Add, so they change nothing
/// on the profile (safe on the signed-in simulator too).
final class BackupServerTests: XCTestCase {
    private let remote = XCUIRemote.shared
    private var app: XCUIApplication!

    override func setUp() {
        continueAfterFailure = false
        app = XCUIApplication()
    }

    private func openForm(source: String, rows: [String] = []) {
        var args = ["-smokePickProfile", "1", "-smokeTab", "settings", "-smokeSettings", "integrations",
                    "-smokeSettingsDialog", "addPlaylist", "-smokeFormSource", source]
        if !rows.isEmpty { args += ["-smokeBackupRows", rows.joined(separator: ",")] }
        app.launchArguments = args
        app.launch()
        XCTAssertTrue(waitFocus(30) { $0 != "<none>" && !$0.hasPrefix("sidebar.") }, "the Add Playlist form never took focus")
    }

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
    /// DOWN until [id] has focus (the form is one column, so DOWN walks it top to bottom).
    private func downTo(_ id: String, max: Int = 20) -> Bool {
        for _ in 0..<max {
            if focusedId() == id { return true }
            press(.down, settle: 0.5)
        }
        return focusedId() == id
    }
    private func label(_ id: String) -> String { app.buttons[id].exists ? app.buttons[id].label : "" }

    /// Add backup server → the row editor opens on the address; typing on the native keyboard fills
    /// the row; Done returns focus to that row (not to the top of the form).
    func testAddBackupServerTypesAnAddress() {
        openForm(source: "m3u_url")
        XCTAssertTrue(downTo("backup.add"), "never reached Add backup server; focus on \(focusedId())")
        press(.select, settle: 1.5)
        XCTAssertTrue(waitFocus { $0 == "backup.address" }, "the row editor did not open on the address; focus \(focusedId())")
        XCTAssertFalse(app.buttons["backup.moveUp"].exists, "a single row offered Move up")
        XCTAssertFalse(app.buttons["backup.moveDown"].exists, "a single row offered Move down")

        press(.select, settle: 2)            // opens the tvOS keyboard
        app.typeText("https://tuvora.co/demo/reviewer-playlist.m3u")
        press(.menu, settle: 1.5)            // back from the keyboard, keeping the text
        XCTAssertTrue(waitFocus { $0 == "backup.address" }, "focus did not come back to the address; on \(focusedId())")

        XCTAssertTrue(downTo("backup.done", max: 4), "never reached Done; focus on \(focusedId())")
        press(.select, settle: 2.5)
        // Focus is back on the row (not the top of the form): DOWN from it is Add backup server. (A row
        // focused right after a dialog closes is not reported by XCUI's hasFocus, so check by moving.)
        press(.down)
        XCTAssertEqual(focusedId(), "backup.add", "closing the editor did not return focus to the row")
        XCTAssertTrue(label("backup.row.0").contains("tuvora.co/demo/reviewer-playlist.m3u"),
                      "the typed address is not on the row: \(label("backup.row.0"))")
    }

    /// Move down on the first of two rows: the editor follows the moved server (now last, so only Move
    /// up is offered), and the form lists the two in the new priority order.
    func testMoveDownReordersAndHidesDeadMoves() {
        openForm(source: "xtream", rows: ["http://a.example:8080", "http://b.example:8080"])
        XCTAssertTrue(downTo("backup.row.0"), "never reached the first backup row; focus on \(focusedId())")
        press(.select, settle: 1.5)
        XCTAssertTrue(waitFocus { $0 == "backup.address" }, "the row editor did not open; focus \(focusedId())")
        XCTAssertFalse(app.buttons["backup.moveUp"].exists, "the first row offered a Move up that can't act")
        XCTAssertTrue(downTo("backup.moveDown", max: 3), "no Move down on the first row; focus on \(focusedId())")
        press(.select, settle: 1.5)
        XCTAssertFalse(app.buttons["backup.moveDown"].exists, "the moved (now last) row still offers Move down")
        XCTAssertEqual(focusedId(), "backup.moveUp", "focus did not stay on the moves after moving")

        press(.menu, settle: 1.5)
        XCTAssertTrue(label("backup.row.0").contains("b.example"), "row 1 is not the moved-up server: \(label("backup.row.0"))")
        XCTAssertTrue(label("backup.row.1").contains("a.example"), "row 2 is not the moved-down server: \(label("backup.row.1"))")
    }

    /// Remove asks first with Cancel focused (UX100); Cancel keeps the row and goes back to the editor.
    func testRemoveAsksAndCancelKeepsTheRow() {
        openForm(source: "stalker", rows: ["http://portal-b.example:88"])
        XCTAssertTrue(downTo("backup.row.0"), "never reached the backup row; focus on \(focusedId())")
        press(.select, settle: 1.5)
        XCTAssertTrue(downTo("backup.remove", max: 4), "no Remove in the editor; focus on \(focusedId())")
        press(.select, settle: 1.5)
        XCTAssertTrue(waitFocus { $0 == "backup.remove.cancel" }, "the confirm did not start on Cancel; focus \(focusedId())")
        press(.select, settle: 2.5)
        // Back on Remove in the editor (XCUI does not report focus inside a dialog that was just
        // re-enabled, so prove it by acting): OK asks again, i.e. focus was on Remove.
        press(.select, settle: 1.5)
        XCTAssertTrue(waitFocus { $0 == "backup.remove.cancel" }, "Cancel did not return focus to Remove; focus \(focusedId())")
        press(.select, settle: 2.5)
        press(.menu, settle: 1.5)
        XCTAssertTrue(app.buttons["backup.row.0"].waitForExistence(timeout: 3), "Cancel removed the backup server")
        XCTAssertTrue(label("backup.row.0").contains("portal-b.example"))
    }

    /// Confirming Remove drops the row and closes the editor; focus lands on what is left.
    func testRemoveConfirmDropsTheRow() {
        openForm(source: "xtream", rows: ["http://a.example:8080"])
        XCTAssertTrue(downTo("backup.row.0"), "never reached the backup row; focus on \(focusedId())")
        press(.select, settle: 1.5)
        XCTAssertTrue(downTo("backup.remove", max: 4), "no Remove in the editor; focus on \(focusedId())")
        press(.select, settle: 1.5)
        XCTAssertTrue(waitFocus { $0 == "backup.remove.cancel" }, "the confirm did not start on Cancel; focus \(focusedId())")
        press(.up)
        XCTAssertEqual(focusedId(), "backup.remove.confirm")
        press(.select, settle: 2)
        XCTAssertFalse(app.buttons["backup.row.0"].exists, "the confirmed removal kept the row")
        XCTAssertTrue(waitFocus { $0 == "backup.add" }, "focus did not land on Add backup server; on \(focusedId())")
    }

    /// The shared validation's message shows on the row, and a duplicate of the main server blocks Add.
    func testSameAsMainServerIsFlagged() {
        app.launchArguments = ["-smokePickProfile", "1", "-smokeTab", "settings", "-smokeSettings", "integrations",
                               "-smokeSettingsDialog", "addPlaylist", "-smokeFormSource", "m3u_url",
                               "-smokeFormM3u", "https://dead.invalid/reviewer-playlist.m3u",
                               "-smokeBackupRows", "https://DEAD.invalid/reviewer-playlist.m3u"]
        app.launch()
        XCTAssertTrue(app.buttons["backup.row.0"].waitForExistence(timeout: 30))
        XCTAssertTrue(label("backup.row.0").contains("Same as the main server"), "no shared message on the row: \(label("backup.row.0"))")
    }

    /// An M3U file has no server, so no backups (NuvioTV hides the section).
    func testM3uFileHasNoBackupSection() {
        openForm(source: "m3u_file")
        Thread.sleep(forTimeInterval: 2)
        XCTAssertFalse(app.buttons["backup.add"].exists, "an M3U file offered backup servers")
    }
}

/// Playlist tests that ADD a playlist, so they must only run on a LOCAL test profile (a simulator with
/// a cached profile and no signed-in account — nothing syncs): run them with
/// `-only-testing:TuvoraTVUITests/LocalProfilePlaylistTests` on such a simulator, never on one
/// signed in to a real account.
final class LocalProfilePlaylistTests: XCTestCase {
    private var app: XCUIApplication!
    private let remote = XCUIRemote.shared
    static let demoMain = "https://dead.invalid/reviewer-playlist.m3u"
    static let demoBackup = "https://tuvora.co/demo/reviewer-playlist.m3u"

    override func setUp() {
        continueAfterFailure = false
        app = XCUIApplication()
    }

    private var focused: XCUIElement {
        app.descendants(matching: .any).element(matching: NSPredicate(format: "hasFocus == true"))
    }
    private func focusedId() -> String { focused.exists ? (focused.identifier.isEmpty ? "#" + focused.label : focused.identifier) : "<none>" }

    /// The IPTV tab follows the playlist store while it is open: a playlist that arrives (as a sync pull
    /// would) replaces "No playlists yet" without leaving the tab. Red before the fix (stayed empty).
    func test1IptvTabShowsAPlaylistAddedWhileItIsOpen() throws {
        app.launchArguments = ["-smokePickProfile", "1", "-smokeTab", "iptv", "-smokeAddM3uLater", "10", Self.demoBackup]
        app.launch()
        let empty = app.staticTexts["No playlists yet"]
        guard empty.waitForExistence(timeout: 30) else {
            throw XCTSkip("needs a local profile with no playlists (the hub did not show its empty state)")
        }
        XCTAssertTrue(app.buttons["hubchip.Live TV"].waitForExistence(timeout: 60),
                      "the playlist added while the IPTV tab was open never showed (still \"No playlists yet\")")
        XCTAssertFalse(empty.exists)
    }

    /// The demo playlist through the real form: main server dead, backup healthy → Add succeeds on the
    /// backup and the playlist row says "Using backup server 1". Prints how long it took.
    func test2DemoPlaylistFailsOverToItsBackup() {
        // On Settings → Integrations → IPTV, so the playlist row is behind the form when it closes.
        app.launchArguments = ["-smokePickProfile", "1", "-smokeTab", "settings", "-smokeSettings", "integrations",
                               "-smokeIntegration", "iptv",
                               "-smokeSettingsDialog", "addPlaylist", "-smokeFormSource", "m3u_url",
                               "-smokeFormM3u", Self.demoMain, "-smokeBackupRows", Self.demoBackup]
        app.launch()
        XCTAssertTrue(app.buttons["playlist.submit"].waitForExistence(timeout: 30))
        Thread.sleep(forTimeInterval: 2)
        for _ in 0..<25 where focusedId() != "playlist.submit" { remote.press(.down); Thread.sleep(forTimeInterval: 0.5) }
        XCTAssertEqual(focusedId(), "playlist.submit")
        let start = Date()
        remote.press(.select)
        let note = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "Using backup server 1")).firstMatch
        XCTAssertTrue(note.waitForExistence(timeout: 120), "the playlist was not added on its backup server")
        let seconds = Date().timeIntervalSince(start)
        print(String(format: "BACKUP_FAILOVER_ADD_SECONDS=%.1f", seconds))
        try? XCUIScreen.main.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: "/tmp/tuvora-backup-row.png"))
    }
}
