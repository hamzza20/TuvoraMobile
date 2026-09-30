import XCTest

/// App-wide focus/navigation walkthrough, driven by the Siri Remote. Each check comes from
/// TVOS-FOCUS-CHECKLIST.md (researched HIG / WWDC / forum pitfalls); a failure is a finding.
final class WalkthroughTests: XCTestCase {
    private let remote = XCUIRemote.shared
    private var app: XCUIApplication!

    override func setUp() {
        continueAfterFailure = true   // a walkthrough reports every finding, not just the first
        app = XCUIApplication()
        app.launchArguments = ["-smokePickProfile", "1", "-smokeTab", "home"]
        app.launch()
    }

    private var focused: XCUIElement {
        app.descendants(matching: .any).element(matching: NSPredicate(format: "hasFocus == true"))
    }
    private func focusedId() -> String { focused.exists ? (focused.identifier.isEmpty ? "#" + focused.label : focused.identifier) : "<none>" }
    private func press(_ b: XCUIRemote.Button, settle: TimeInterval = 0.8) { remote.press(b); Thread.sleep(forTimeInterval: settle) }
    private func waitFocus(_ timeout: TimeInterval = 20, _ match: (String) -> Bool) -> Bool {
        let end = Date().addingTimeInterval(timeout)
        while Date() < end { if match(focusedId()) { return true }; Thread.sleep(forTimeInterval: 0.4) }
        return false
    }
    private func inContent(_ id: String) -> Bool { id != "<none>" && !id.hasPrefix("sidebar.") }

    private func openTab(_ tab: String) {
        if !focusedId().hasPrefix("sidebar.") { press(.menu) }
        XCTAssertTrue(waitFocus(5) { $0.hasPrefix("sidebar.") }, "Menu did not open the sidebar (on \(focusedId()))")
        let order = ["home", "search", "library", "iptv", "sports", "settings"]
        guard let from = order.firstIndex(where: { "sidebar.\($0)" == focusedId() }), let to = order.firstIndex(of: tab) else { return }
        for _ in 0..<abs(to - from) { press(to > from ? .down : .up, settle: 0.4) }
        XCTAssertEqual(focusedId(), "sidebar.\(tab)")
        press(.select, settle: 2)
    }

    /// Checklist B/C: picking a tab from the sidebar hands focus to that tab's content, and LEFT from
    /// its leftmost column gets back to the sidebar.
    func testEveryTabTakesFocusAndReachesSidebar() {
        XCTAssertTrue(waitFocus { inContent($0) }, "Home: nothing focused after launch")
        for tab in ["search", "library", "iptv", "sports", "settings", "home"] {
            openTab(tab)
            let ok = waitFocus(15) { inContent($0) }
            XCTAssertTrue(ok, "\(tab): after choosing it in the sidebar, focus is \(focusedId()) - nothing in the tab took focus")
            guard ok else { continue }
            var trail: [String] = []
            for _ in 0..<8 where !focusedId().hasPrefix("sidebar.") { press(.left, settle: 0.6); trail.append(focusedId()) }
            XCTAssertTrue(focusedId().hasPrefix("sidebar.") || tab == "search",
                          "\(tab): LEFT never reached the sidebar; trail \(trail)")
        }
    }

    /// Checklist A: Menu at the root leaves the app (tvOS Home screen); it must never be swallowed.
    func testMenuFromSidebarExitsTheApp() {
        XCTAssertTrue(waitFocus { inContent($0) })
        press(.menu)
        XCTAssertTrue(waitFocus(5) { $0.hasPrefix("sidebar.") }, "Menu in content did not open the sidebar")
        press(.menu, settle: 2)
        XCTAssertTrue(app.wait(for: .runningBackground, timeout: 5) || app.state == .runningBackgroundSuspended,
                      "Menu from the sidebar did not exit to the Apple TV Home screen (state \(app.state.rawValue))")
    }

    /// Checklist A/E: details opens from a card, Menu closes it, and focus returns to the card.
    func testDetailsMenuReturnsFocusToOpener() {
        XCTAssertTrue(waitFocus { inContent($0) }, "Home: nothing focused")
        press(.down, settle: 1.5)   // first catalog row (posters open details)
        let opener = focusedId()
        press(.select, settle: 4)
        let play = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Play' OR label BEGINSWITH 'Resume' OR label BEGINSWITH 'Watch'")).firstMatch
        XCTAssertTrue(play.waitForExistence(timeout: 15), "details did not open from \(opener)")
        XCTAssertTrue(waitFocus(8) { $0 != "<none>" }, "details: nothing focused")
        let detailsFocus = focusedId()
        for _ in 0..<6 { press(.down, settle: 0.6) }
        let afterDown = focusedId()
        XCTAssertNotEqual(afterDown, "<none>", "details: DOWN lost focus")
        press(.menu, settle: 2)
        XCTAssertFalse(play.exists, "Menu did not close details")
        XCTAssertTrue(waitFocus(5) { inContent($0) }, "after closing details focus is \(focusedId()) (opened from \(opener))")
        XCTAssertEqual(focusedId(), opener, "focus did not return to the card that opened details (details focus \(detailsFocus), after DOWN \(afterDown))")
    }

    /// Checklist A: Search's keyboard and results: Menu steps back, never strands focus.
    func testSearchMenuSteps() {
        XCTAssertTrue(waitFocus { inContent($0) })
        openTab("search")
        XCTAssertTrue(waitFocus(10) { inContent($0) }, "search: nothing focused")
        press(.menu, settle: 1.5)
        XCTAssertTrue(waitFocus(5) { $0 != "<none>" }, "search: Menu left nothing focused")
    }
}
