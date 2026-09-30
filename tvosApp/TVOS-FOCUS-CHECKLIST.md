# tvOS Focus & Navigation Audit Checklist (SwiftUI, tvOS 17–26)

Run this against every screen: sidebar, Home, Settings, EPG guide, every cover/sheet, player.
Each item reads: **Rule**: why. *Verify:* how to check. [source]
Researched 2026-09-29 from live Apple docs, the HIG, and developer forums. Items marked *(dev report)* come from third-party reports, not Apple docs, so confirm them on a device.

Key sources:
- HIG-Focus: https://developer.apple.com/design/human-interface-guidelines/focus-and-selection
- HIG-Remotes: https://developer.apple.com/design/human-interface-guidelines/remotes
- HIG-Layout: https://developer.apple.com/design/human-interface-guidelines/layout
- HIG-TabBars: https://developer.apple.com/design/human-interface-guidelines/tab-bars
- Catalog: Apple sample "Creating a tvOS media catalog app in SwiftUI", https://developer.apple.com/documentation/swiftui/creating-a-tvos-media-catalog-app-in-swiftui

---

## A. Back / Menu contract

- [ ] **Back opens the parent screen, and at the app's root it exits to the Apple TV Home Screen.** The HIG says the parent at the top level is the Home Screen, and "exits to Apple TV Home Screen" is the expected behavior of Back. *Verify:* cold launch, press Back once, and you should land on the tvOS Home Screen. Then, from each screen, count Back presses to the root. [HIG-Remotes]
- [ ] **Don't attach `onExitCommand` at the root for the "already at root" state.** A root handler consumes every press, so users have to press and hold Back to leave the app. Pass `nil` (`.onExitCommand(perform: atRoot ? nil : handler)`) so the system handles the press. *Verify:* with focus in the sidebar on Home, one Back press exits. https://github.com/Drvolks/StreamClient/pull/162
- [ ] **Order Back actions by priority: dismiss overlay, then move focus from content to the sidebar or top, then exit.** The Apple TV app returns focus to the tab bar before it exits. "focus always returns to the tab bar at the top of the page when people press Menu." *Verify:* from deep in a Home row, Back goes sidebar → Home Screen. [HIG-TabBars]
- [ ] **Never call `exit(0)` to fake Back.** It looks like a crash, with no fade animation, and it is a likely rejection. https://developer.apple.com/forums/thread/22202
- [ ] **App Review rejects apps where tapping Back at launch does not exit, or holding Back does not reach Home** (cited under Guideline 4.0 Design). *Verify:* tap at launch, and hold from a deep screen. https://github.com/godotengine/godot/issues/46565 , https://developer.apple.com/forums/thread/24109
- [ ] **Every `fullScreenCover`, `sheet` and dialog closes on one Back press, and focus returns to the control that opened it.** Otherwise the user gets "no way back out". *Verify:* open each cover, press Back, and confirm the opener is focused (not the first item or nothing). Developers report that Back dismisses a `fullScreenCover` before your `onExitCommand` can intercept it, so don't rely on intercepting it in the player *(dev report)*. [HIG-Remotes]
- [ ] **Player: the first Back hides the controls or overlay, and the next Back leaves the player.** Play/Pause must always toggle playback. *Verify:* in the player, press Back with controls visible, then with them hidden, then press Play/Pause. [HIG-Remotes]
- [ ] **Known OS bug: on tvOS 18+, Back after scrolling a tab page can exit the app instead of refocusing the tab bar** (FB14835294, still reported on 26.0b1). *Verify:* on the Home tab, scroll down and press Back. https://developer.apple.com/forums/thread/762214

## B. Initial and default focus

- [ ] **Every screen has a focused element as soon as it appears.** tvOS users reach everything through focus, so with nothing focused a remote press does nothing. *Verify:* cold launch and each push or cover; the first swipe or click should do something visible. [HIG-Focus]
- [ ] **Say which element gets default focus (for example Play, not Back).** Use `.defaultFocus($state, value)` (tvOS 16+) or `.prefersDefaultFocus(in:)` together with `.focusScope(ns)`. https://developer.apple.com/documentation/swiftui/view/defaultfocus(_:_:priority:) , https://blakecrosley.com/blog/tvos-focus-engine-swiftui
- [ ] **Don't set `@FocusState` in `onAppear` before the data has loaded.** The target isn't focusable yet, so the assignment does nothing and nothing is focused. Set it after content renders, or retry. *Verify:* throttle the network and launch; something must still be focused. https://github.com/CreatureSurvive/TVFocusKit
- [ ] **`prefersDefaultFocus` inside a `ScrollView` has been unreliable** (tvOS 14–16), so prefer `@FocusState`/`defaultFocus`. https://developer.apple.com/forums/thread/706321
- [ ] **Reserve `.defaultFocus(..., priority: .userInitiated)` for sections that should always land on one item.** By default, default focus applies only on first appearance and programmatic moves, not on user swipes. [defaultFocus doc above]
- [ ] **Use `resetFocus(in: ns)` after a state reset** (profile switch, sign-out) instead of leaving focus pointing at a view that has been removed. https://developer.apple.com/documentation/swiftui/view/prefersdefaultfocus(_:in:)

## C. Sections, sidebar and directional movement

- [ ] **Wrap each region (sidebar, hero, each row, settings rail, detail pane, EPG channel column and grid) in `.focusSection()`.** Without it, the engine searches in a straight line and misses targets that don't line up, or jumps too far. *Verify:* from the far-right item of every row, press Up and Down; focus lands in the adjacent section. https://developer.apple.com/documentation/swiftui/view/focussection()
- [ ] **A non-focusable hero still needs to be a focus target**, or Up from a row jumps to the sidebar or top bar. Use `.focusSection()` on the header stack (Apple's catalog sample does this). *Verify:* press Up from the rightmost card of the first row. [Catalog]
- [ ] **Left inside content moves within content. Only Left from a row's first item (or Back) opens the sidebar.** The sidebar opens when it is geometrically the nearest target to the left, so a short row, a hero or a gap lets it "steal" focus. Give content a leading section or boundary. *Verify:* press Left from every column-0 item and from mid-row items next to gaps. https://github.com/Nziranziza/home-tv/pull/42
- [ ] **DOWN from the last sidebar item stays in the sidebar.** Make the sidebar a `.focusSection()` whose frame fills the full height, and don't let content sit below the last item's column. *Verify:* hold Down on the last sidebar item. [focusSection doc]
- [ ] **Every focus region has a documented exit path, and none of them traps the user.** *Verify:* from each region try all four directions plus Back. https://blakecrosley.com/blog/tvos-focus-engine-swiftui
- [ ] **`TabView` with `.sidebarAdaptable`: a `TabSection` breaks revealing the sidebar via Back or swipe** (tvOS 18, unresolved). *Verify:* if you use `TabSection`, test sidebar reveal. https://developer.apple.com/forums/thread/760888
- [ ] **Built-in `NavigationSplitView` sidebar interaction is unreliable on tvOS.** Prefer a custom sidebar with `focusSection`. https://developer.apple.com/forums/thread/742670

## D. Move commands, press handling and disabled views

- [ ] **Treat `onMoveCommand` as a side channel, not a focus replacement.** It fires for the focused view on a directional press, and the focus engine may also move. Use it for paging and EPG time scrolling, not for rebuilding navigation. https://developer.apple.com/documentation/swiftui/view/onmovecommand(perform:)
- [ ] **tvOS 18+ on the 2nd/3rd-gen Siri Remote: `onMoveCommand` (and tap/Play-Pause) can miss the first click after load, focus change or a change of direction** (FB15272007). Don't build EPG paging that needs every click. *Verify:* in the EPG, click right 3 times and count the moves. https://developer.apple.com/forums/thread/764582
- [ ] **`.disabled(true)` removes a control from focus, and a disabled ancestor overrides its children.** If the focused control becomes disabled (for example a "Loading" button), focus jumps. Keep it enabled and ignore the action instead, or move focus deliberately. https://developer.apple.com/documentation/swiftui/view/disabled(_:)
- [ ] **Custom cards use `Button` (with `.borderless` or `.card` style) or `.focusable()`, never a bare `onTapGesture`.** A view the engine can't see can't be reached. [Catalog] , https://blakecrosley.com/blog/tvos-focus-engine-swiftui
- [ ] **Don't bind one `@FocusState` value to two views.** SwiftUI picks the first one and warns at runtime. https://developer.apple.com/documentation/swiftui/focusstate

## E. Data reloads and restoring focus

- [ ] **Keep row and item identities stable across refreshes.** A replaced `id` destroys the focused view, and focus drops or jumps. The HIG allows moving focus to a neighbour only when the focused item disappears. *Verify:* trigger a background refresh (CW, EPG tick) while an item is focused; focus should stay put. [HIG-Focus]
- [ ] **Returning to a row or section restores the last-focused item, not the geometrically nearest one.** Stock SwiftUI lands on the nearest item. Store the last `id` per section and restore it with `defaultFocus`/`@FocusState`. *Verify:* scroll row 2 to item 8, go Up, come back Down, and you should be on item 8. https://github.com/CreatureSurvive/TVFocusKit
- [ ] **Returning from details or the player refocuses the card that was opened.** *Verify:* open a card, press Back, and the same card should be focused. [HIG-Focus]
- [ ] **Never change focus without user input** (a timer, an async load). The only exception is when the focused item disappears. [HIG-Focus]

## F. Scrolling and long pages (details, settings pane)

- [ ] **A `ScrollView` scrolls only by moving focus, so content below the buttons needs focusable stops.** A long `Text` isn't focusable, and even `Text.focusable()` may not scroll. Break the synopsis, cast and episodes into focusable rows or cards, or use a `UITextView` wrapper for long text. *Verify:* on details, press Down until the bottom is reached. https://developer.apple.com/forums/thread/748689 , https://medium.com/@aliyasirali/scrolling-issues-in-tvos-a-workaround-for-long-text-befe3ed6884b
- [ ] **For above/below-the-fold landing and details pages, use `ScrollTargetBehavior` with a `focusSection` header.** [Catalog]
- [ ] **Apply `.scrollClipDisabled()` on shelves** so the enlarged focused card isn't clipped. [Catalog]
- [ ] **Leave room for focus growth.** Use about 40 pt between grid items so a focused item doesn't overlap its neighbours. [HIG-Layout]

## G. Layout and safe area

- [ ] **Inset primary content 60 pt top and bottom and 80 pt at the sides** to allow for overscan. *Verify:* check screenshots at 1920×1080. [HIG-Layout]
- [ ] **In the full-screen player, gestures act on the content, not on focus, and no pointer is shown.** [HIG-Focus]

## H. Text fields and keyboard

- [ ] **Set `@FocusState` to `nil` to dismiss the keyboard. Don't dismiss it any other way.** https://developer.apple.com/documentation/swiftui/focusstate
- [ ] **tvOS 18.3+: a `TextField` in a modal can become unclickable after the user presses Back from the keyboard.** *Verify:* edit the playlist or search field inside the sheet, press Back, then try to edit again. https://developer.apple.com/forums/thread/791328

## I. Automated tests (XCUITest)

- [ ] **Drive with `XCUIRemote.shared.press(.up/.down/.left/.right/.select/.menu/.playPause)`, and assert with `element.hasFocus`** (for example `XCTAssertTrue(app.buttons["Play"].hasFocus)`). Use `press(_:forDuration:)` to test a held Back. https://developer.apple.com/documentation/xcuiautomation/xcuiremote , https://alexilyenko.github.io/apple-tv-automated-tests/
- [ ] **Wait with `expectation(for: NSPredicate(format: "hasFocus == true"), evaluatedWith:)`, not `sleep`.** Focus animates asynchronously. https://useyourloaf.com/blog/ui-testing-quick-guide/
- [ ] **Limitations:** tvOS has no `tap()`, so every test has to navigate to its target. There is no element equality or path-finding, so you write search loops for rows and grids. Exit-to-Home is observable only as `app.state != .runningForeground`. https://developer.apple.com/forums/thread/741731 , https://alexilyenko.github.io/apple-tv-automated-tests/
- [ ] **Minimum per-screen tests:**
  - (1) something has focus on appear;
  - (2) edge presses in all four directions from each section;
  - (3) Back returns to the opener, and at the root the app leaves the foreground;
  - (4) Down reaches the bottom of long pages.

  Put accessibility identifiers on every focusable element.

---

## Top 10 most likely to bite Tuvora

1. A root `onExitCommand` swallows Back, so the app never exits (App Review, section A).
2. Nothing is focused at launch because `@FocusState` was set before the data arrived (B).
3. Left from a short row, a gap or the hero lands in the sidebar because it's the nearest target (C).
4. The sidebar isn't a full-height `focusSection`, so Down from the last item escapes into content (C).
5. The non-focusable hero has no `focusSection`, so Up skips it or jumps to the sidebar (C).
6. The details page has static text below the buttons that can't be scrolled (F).
7. After a cover or player is dismissed, focus doesn't return to its opener (A/E).
8. A background refresh changes `id`s and focus drops or jumps (E).
9. `.disabled` on the focused button during loading throws focus elsewhere (D).
10. The tvOS 18+ `onMoveCommand` first-click drop makes EPG paging feel broken (D).
