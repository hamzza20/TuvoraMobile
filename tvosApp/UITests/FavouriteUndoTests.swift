import XCTest

/// F03 on Apple TV: a favourite toggle in the live guide says "Hold OK to undo", and holding OK again
/// within the window offers Undo. Toggles the first channel of the selected playlist and undoes it, so
/// the profile ends as it started. Needs a playlist with at least one live channel on profile 1.
final class FavouriteUndoTests: XCTestCase {
    private let remote = XCUIRemote.shared
    private var app: XCUIApplication!
    private static let menuLabels: Set<String> = ["Undo", "Add to Favorites", "Remove from Favorites", "Move up", "Move down", "Hide channel"]

    override func setUp() {
        continueAfterFailure = false
        app = XCUIApplication()
    }

    private var focused: XCUIElement {
        app.descendants(matching: .any).element(matching: NSPredicate(format: "hasFocus == true"))
    }
    private func focusedId() -> String { focused.exists ? (focused.identifier.isEmpty ? "#" + focused.label : focused.identifier) : "<none>" }
    private func press(_ b: XCUIRemote.Button, settle: TimeInterval = 0.8) { remote.press(b); Thread.sleep(forTimeInterval: settle) }

    /// The hold-OK menu's items, top to bottom (the focused cell has no label of its own).
    private func holdMenu() -> [String] {
        remote.press(.select, forDuration: 1.2)
        Thread.sleep(forTimeInterval: 1.5)
        return app.descendants(matching: .other).allElementsBoundByIndex
            .filter { Self.menuLabels.contains($0.label) && $0.frame.width > 100 }
            .sorted { $0.frame.minY < $1.frame.minY }
            .map(\.label)
    }
    private func pick(_ item: String, in menu: [String]) {
        guard let index = menu.firstIndex(of: item) else { XCTFail("no \(item) in \(menu)"); return }
        for _ in 0..<index { press(.down, settle: 0.5) }
        press(.select, settle: 1.5)
    }

    func testFavouriteToggleSaysHoldOkToUndo() throws {
        app.launchArguments = ["-smokePickProfile", "1", "-smokeTab", "iptv"]
        app.launch()
        guard app.buttons["hubchip.Live TV"].waitForExistence(timeout: 40) else {
            throw XCTSkip("needs a playlist on profile 1 (the IPTV hub has no Live TV chip)")
        }
        Thread.sleep(forTimeInterval: 4)
        press(.down, settle: 1.5)                       // into the guide
        // The hub remembers its category; from the category column go to All channels, then RIGHT.
        let categories = ["#All favorites", "#Favorites", "#Recent", "#All channels"]
        if categories.contains(focusedId()) {
            for _ in 0..<6 where focusedId() != "#All channels" { press(.down, settle: 0.5) }
            press(.select, settle: 1.5)
            press(.right, settle: 1.5)
        }
        let channel = focusedId()
        let first = holdMenu()
        let toggle = first.first { $0 == "Add to Favorites" || $0 == "Remove from Favorites" }
        guard let toggle else { throw XCTSkip("no channel row took focus (focus \(channel), menu \(first))") }
        pick(toggle, in: first)

        // Red before the fix: the menu closing hands focus back to the row, whose focus handler wiped
        // the notice at once.
        let notice = app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", "Hold OK to undo")).firstMatch
        XCTAssertTrue(notice.waitForExistence(timeout: 3), "no 'Hold OK to undo' notice after \(toggle) on \(channel)")

        let second = holdMenu()
        XCTAssertEqual(second.first, "Undo", "holding OK again did not offer Undo first: \(second)")
        pick("Undo", in: second)
        let third = holdMenu()
        XCTAssertTrue(third.contains(toggle), "Undo did not restore the favourite state (menu \(third))")
        press(.menu, settle: 1)
    }
}
