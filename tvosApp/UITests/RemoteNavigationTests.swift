import XCTest

/// Drives the real Siri Remote through the focus engine (XCUIRemote), so focus bugs that unit tests
/// can't see get a regression test. Needs the simulator's signed-in test account (IPTV playlists).
/// Each test is one tester report from 2026-09-29.
final class RemoteNavigationTests: XCTestCase {
    private let remote = XCUIRemote.shared
    private var app: XCUIApplication!

    override func setUp() {
        continueAfterFailure = false
        app = XCUIApplication()
    }

    private func launch(_ args: [String]) {
        app.launchArguments = ["-smokePickProfile", "1"] + args
        app.launch()
    }

    private var focused: XCUIElement {
        app.descendants(matching: .any).element(matching: NSPredicate(format: "hasFocus == true"))
    }

    private func focusedId() -> String { focused.exists ? focused.identifier : "<none>" }

    private func press(_ button: XCUIRemote.Button, settle: TimeInterval = 0.8) {
        remote.press(button)
        Thread.sleep(forTimeInterval: settle)
    }

    private func waitForFocus(where match: (String) -> Bool, timeout: TimeInterval = 30) -> Bool {
        let end = Date().addingTimeInterval(timeout)
        while Date() < end {
            if match(focusedId()) { return true }
            Thread.sleep(forTimeInterval: 0.5)
        }
        return false
    }

    // MARK: - IPTV hub

    /// "In the IPTV tab, moving Series -> Movies -> Live TV goes to the nav bar."
    func testHubChipsMoveLeftWithoutOpeningSidebar() {
        launch(["-smokeTab", "iptv"])
        XCTAssertTrue(app.buttons["hubchip.Live TV"].waitForExistence(timeout: 30), "IPTV hub header never appeared")
        Thread.sleep(forTimeInterval: 3)

        for _ in 0..<8 where !focusedId().hasPrefix("hubchip.") { press(.up) }
        XCTAssertTrue(focusedId().hasPrefix("hubchip."), "could not reach the hub chips; focus on \(focusedId())")

        for _ in 0..<3 where focusedId() != "hubchip.Series" { press(.right) }
        XCTAssertEqual(focusedId(), "hubchip.Series")
        press(.select, settle: 3)
        XCTAssertEqual(focusedId(), "hubchip.Series", "focus left the chip after opening Series")

        press(.left)
        XCTAssertEqual(focusedId(), "hubchip.Movies", "LEFT from Series went to \(focusedId())")
        press(.select, settle: 3)
        XCTAssertEqual(focusedId(), "hubchip.Movies", "focus left the chip after opening Movies")
        press(.left)
        XCTAssertEqual(focusedId(), "hubchip.Live TV", "LEFT from Movies went to \(focusedId())")
        press(.select, settle: 3)
        XCTAssertFalse(focusedId().hasPrefix("sidebar."), "opening Live TV put focus in the sidebar")

        // At the true left edge LEFT still opens the drawer (NuvioTV's behaviour).
        for _ in 0..<4 where focusedId() != "hubchip.Live TV" { press(.up) }
        if focusedId() == "hubchip.Live TV" {
            press(.left)
            XCTAssertTrue(focusedId().hasPrefix("sidebar."), "LEFT at the left edge should open the sidebar, got \(focusedId())")
        }
    }

    // MARK: - Sidebar

    /// "When scrolling down on the nav bar from Settings it goes out of the nav bar."
    func testSidebarKeepsFocusAtBothEnds() {
        launch(["-smokeTab", "home"])
        XCTAssertTrue(waitForFocus(where: { $0 != "<none>" }), "nothing focused on Home")
        press(.menu)   // Menu in content opens the drawer
        XCTAssertTrue(waitForFocus(where: { $0.hasPrefix("sidebar.") }, timeout: 5), "Menu did not open the sidebar; focus \(focusedId())")

        var trail: [String] = []
        for _ in 0..<8 { press(.down, settle: 0.5); trail.append(focusedId()) }
        XCTAssertEqual(focusedId(), "sidebar.settings", "DOWN past Settings left the nav bar; trail \(trail)")
        press(.down)
        XCTAssertEqual(focusedId(), "sidebar.settings", "a second DOWN past Settings left the nav bar")

        for _ in 0..<8 { press(.up, settle: 0.5) }
        XCTAssertEqual(focusedId(), "sidebar.home", "UP past Home left the nav bar")
    }

    // MARK: - Home

    /// "On the big poster at app start, going to the nav bar needs a scroll down to the list first."
    func testLeftFromHomeAtLaunchOpensSidebar() {
        launch(["-smokeTab", "home"])
        XCTAssertTrue(waitForFocus(where: { $0 != "<none>" }), "nothing is focused on Home after launch")
        for _ in 0..<6 where !focusedId().hasPrefix("sidebar.") { press(.left) }
        XCTAssertTrue(focusedId().hasPrefix("sidebar."), "LEFT from Home's first row never reached the sidebar; focus \(focusedId())")
    }

    // MARK: - Settings

    /// "In Settings, from a sub-setting like Integrations, LEFT opens the nav bar."
    func testLeftFromSettingsDetailReturnsToCategoryRail() {
        launch(["-smokeTab", "settings", "-smokeSettings", "integrations"])
        XCTAssertTrue(waitForFocus(where: { $0.hasPrefix("settings.rail.") }), "settings rail never took focus; \(focusedId())")
        press(.right)
        let inDetail = focusedId()
        XCTAssertFalse(inDetail.hasPrefix("settings.rail.") || inDetail.hasPrefix("sidebar."), "RIGHT did not enter the detail pane; \(inDetail)")
        press(.left)
        XCTAssertTrue(focusedId().hasPrefix("settings.rail."), "LEFT from the detail pane went to \(focusedId()), not the category rail")
        press(.left)
        XCTAssertTrue(focusedId().hasPrefix("sidebar."), "LEFT from the category rail should open the sidebar; \(focusedId())")
    }

    // MARK: - Profiles

    /// "Manage Profiles ... there is no way to come out / cannot go back to the home screen."
    func testManageProfilesHasAWayOut() {
        launch(["-smokeTab", "settings", "-smokeSettings", "profiles"])
        XCTAssertTrue(waitForFocus(where: { $0.hasPrefix("settings.rail.") }), "settings rail never took focus")
        press(.right)
        let row = focusedId() + " / " + focused.label
        press(.select, settle: 3)   // Manage Profiles
        let rail = app.buttons["settings.rail.profiles"]
        let gone = NSPredicate(format: "exists == false")
        XCTAssertEqual(XCTWaiter.wait(for: [expectation(for: gone, evaluatedWith: rail)], timeout: 10), .completed,
                       "Manage Profiles did not open; select pressed on \(row); texts: \(app.staticTexts.allElementsBoundByIndex.prefix(12).map { $0.label })")
        XCTAssertTrue(app.buttons["profiles.done"].waitForExistence(timeout: 5),
                      "Manage Profiles has no visible Done; buttons: \(app.buttons.allElementsBoundByIndex.map { $0.identifier + "|" + $0.label })")
        press(.menu, settle: 3)
        XCTAssertTrue(app.buttons["sidebar.home"].waitForExistence(timeout: 10), "Menu on Manage Profiles did not return to the app")
        XCTAssertFalse(app.buttons["profiles.done"].exists, "still on Manage Profiles after Menu")
    }
}
