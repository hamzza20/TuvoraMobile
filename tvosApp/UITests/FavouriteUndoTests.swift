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
        // One snapshot of the tree (querying element by element races the menu's animation).
        for _ in 0..<3 {
            guard let root = try? app.snapshot() else { Thread.sleep(forTimeInterval: 0.5); continue }
            var found: [(String, CGFloat)] = []
            func walk(_ node: XCUIElementSnapshot) {
                if node.elementType == .other, Self.menuLabels.contains(node.label), node.frame.width > 100 {
                    found.append((node.label, node.frame.minY))
                }
                node.children.forEach(walk)
            }
            walk(root)
            if !found.isEmpty { return found.sorted { $0.1 < $1.1 }.map(\.0) }
            Thread.sleep(forTimeInterval: 0.5)
        }
        return []
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

    /// Every row of All favorites is a favourite — including those of OTHER playlists, whose hold-OK
    /// menu used to say "Add to Favorites" (the set behind the label only held the current playlist's
    /// channels). Read-only: each menu is dismissed with MENU. Skips when All favorites is empty.
    func testAllFavoritesRowsOfferRemove() throws {
        app.launchArguments = ["-smokePickProfile", "1", "-smokeTab", "iptv"]
        app.launch()
        guard app.buttons["hubchip.Live TV"].waitForExistence(timeout: 40) else {
            throw XCTSkip("needs a playlist on profile 1 (the IPTV hub has no Live TV chip)")
        }
        Thread.sleep(forTimeInterval: 4)
        press(.down, settle: 1.5)
        if !["#All favorites", "#Favorites", "#Recent", "#All channels"].contains(focusedId()) { press(.left, settle: 1) }
        for _ in 0..<10 where focusedId() != "#All favorites" { press(.up, settle: 0.5) }
        guard focusedId() == "#All favorites" else { throw XCTSkip("never reached All favorites (focus \(focusedId()))") }
        press(.select, settle: 2)
        press(.right, settle: 1.5)
        guard focusedId().range(of: #"^#1, "#, options: .regularExpression) != nil else {
            throw XCTSkip("All favorites is empty on this profile (focus \(focusedId()))")
        }
        var seen = Set<String>()
        for _ in 0..<6 {
            let row = focusedId()
            guard row.range(of: #"^#\d+, "#, options: .regularExpression) != nil, seen.insert(row).inserted else { break }
            let menu = holdMenu()
            XCTAssertTrue(menu.contains("Remove from Favorites") && !menu.contains("Add to Favorites"),
                          "All favorites row \(row) offers \(menu)")
            press(.menu, settle: 1)
            press(.down, settle: 0.8)
        }
        XCTAssertGreaterThan(seen.count, 0)
    }

    /// Into the guide's category column from the hub (the hub remembers its category and row).
    private func toCategories() -> Bool {
        let categories: Set<String> = ["#All favorites", "#Favorites", "#Recent", "#All channels"]
        press(.down, settle: 1.5)
        for _ in 0..<4 {
            let id = focusedId()
            if categories.contains(id) || id.hasPrefix("#") && !id.contains(", ") { return true }
            if id.range(of: #"^#\d+, "#, options: .regularExpression) != nil { press(.left, settle: 1) } else { press(.down, settle: 1) }
        }
        return categories.contains(focusedId())
    }
    private func openCategory(_ name: String) -> Bool {
        for _ in 0..<12 where focusedId() != "#\(name)" { press(.up, settle: 0.5) }
        for _ in 0..<20 where focusedId() != "#\(name)" { press(.down, settle: 0.5) }
        guard focusedId() == "#\(name)" else { return false }
        press(.select, settle: 2)
        press(.right, settle: 1.5)
        return true
    }

    /// P5 (W2 device pass): removing a favourite INSIDE a favourites row used to drop the row at once,
    /// so the "Hold OK to undo" the notice promised had no row to hold OK on. The row now stays (marked
    /// as removed) for the Undo window, and Undo puts the favourite back in the same place.
    /// Self-contained: favourites the first channel of All channels if Favorites is empty, and removes
    /// that favourite again at the end.
    func testRemovingInsideFavoritesKeepsTheRowForUndo() throws {
        app.launchArguments = ["-smokePickProfile", "1", "-smokeTab", "iptv"]
        app.launch()
        guard app.buttons["hubchip.Live TV"].waitForExistence(timeout: 40) else {
            throw XCTSkip("needs a playlist on profile 1 (the IPTV hub has no Live TV chip)")
        }
        Thread.sleep(forTimeInterval: 4)
        guard toCategories() else { throw XCTSkip("never reached the category column (focus \(focusedId()))") }
        // Set-up: make sure this playlist has a favourite.
        var added = false
        guard openCategory("Favorites") else { throw XCTSkip("no Favorites category (focus \(focusedId()))") }
        if focusedId().range(of: #"^#1, "#, options: .regularExpression) == nil {
            // An empty Favorites leaves focus on its category; LEFT from there would open the sidebar.
            if focusedId().range(of: #"^#\d+, "#, options: .regularExpression) != nil { press(.left, settle: 1) }
            guard openCategory("All channels") else { throw XCTSkip("no All channels category") }
            let menu = holdMenu()
            guard menu.contains("Add to Favorites") else { throw XCTSkip("first channel offers \(menu)") }
            pick("Add to Favorites", in: menu)
            added = true
            Thread.sleep(forTimeInterval: 7)           // let the add's Undo window close
            press(.left, settle: 1)
            guard openCategory("Favorites") else { throw XCTSkip("no Favorites category after the add") }
        }
        let row = focusedId()
        guard row.range(of: #"^#1, "#, options: .regularExpression) != nil else {
            throw XCTSkip("Favorites is still empty (focus \(row))")
        }
        let first = holdMenu()
        guard first.contains("Remove from Favorites") else { throw XCTSkip("first Favorites row offers \(first)") }
        pick("Remove from Favorites", in: first)

        // Red before the fix: the row left the list, focus fell elsewhere and Undo was unreachable.
        // Number + name (the ★ goes with the removal, so the rest of the label may change).
        func rowKey(_ id: String) -> String { id.split(separator: ",").prefix(2).joined(separator: ",") }
        XCTAssertEqual(rowKey(focusedId()), rowKey(row), "the removed favourite's row did not stay for Undo")
        let notice = app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", "Hold OK to undo")).firstMatch
        XCTAssertTrue(notice.waitForExistence(timeout: 3), "no 'Hold OK to undo' notice after removing \(row)")
        let second = holdMenu()
        XCTAssertEqual(second.first, "Undo", "holding OK on the removed row did not offer Undo first: \(second)")
        pick("Undo", in: second)
        Thread.sleep(forTimeInterval: 1.5)
        XCTAssertEqual(rowKey(focusedId()), rowKey(row), "Undo did not put the favourite back in its place")
        let third = holdMenu()
        XCTAssertTrue(third.contains("Remove from Favorites"), "Undo did not restore the favourite (menu \(third))")
        // Clean-up: leave the profile as it started.
        if added {
            pick("Remove from Favorites", in: third)
            Thread.sleep(forTimeInterval: 7)
        } else {
            press(.menu, settle: 1)
        }
    }
}
