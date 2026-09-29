import SwiftUI
import TuvoraCore

/// NuvioTV's Settings, CLASSIC style (ui/screens/settings/SettingsScreen.kt + SettingsDesignSystem.kt):
/// a BackgroundElevated workspace (radius 28, 1dp Border, padding 20) holding a 220dp category rail and
/// the detail pane (≤ 880dp). On Apple TV a rail item selects when focus rests on it (NuvioTV Horizon's
/// 140 ms focus-select), so the detail follows the remote without an extra click.
///
/// Categories follow NuvioTV's order. Left out, with no Apple TV meaning or not ported yet: Tracking
/// (Trakt/Simkl), Advanced, Debug, and Integrations' Debrid/TMDB/MDBList/Anime-Skip.
struct SettingsScreen: View {
    enum Category: String, CaseIterable, Identifiable {
        case account, profiles, appearance, layout, discovery, integrations, playback, about
        var id: String { rawValue }
        var title: String {
            switch self {
            case .account: return "Account"
            case .profiles: return "Profiles"
            case .appearance: return "Appearance"
            case .layout: return "Layout"
            case .discovery: return "Content & Discovery"
            case .integrations: return "Integrations"
            case .playback: return "Playback"
            case .about: return "About"
            }
        }
        var icon: String {
            switch self {
            case .account: return "md_person"
            case .profiles: return "md_people"
            case .appearance: return "md_palette"
            case .layout: return "md_grid_view"
            case .discovery: return "md_explore"
            case .integrations: return "md_link"
            case .playback: return "md_play_arrow"
            case .about: return "md_info"
            }
        }
    }

    @Environment(\.nuvio) private var colors
    @StateObject private var model = SettingsModel()
    @StateObject private var dialogs = SettingsDialogs()
    /// Simulator smoke hook: `-smokeSettings <category>` opens a category, `-smokeSettingsDialog <addPlaylist|signOut|engine|removePlaylist>` a dialog.
    @State private var selected: Category = {
        let args = ProcessInfo.processInfo.arguments
        if let i = args.firstIndex(of: "-smokeSettings"), i + 1 < args.count, let c = Category(rawValue: args[i + 1]) { return c }
        return .account
    }()
    @FocusState private var railFocus: Category?

    var body: some View {
        ZStack {
            workspace
                .disabled(!dialogs.stack.isEmpty)
            if !dialogs.stack.isEmpty {
                Color.black.opacity(0.6).ignoresSafeArea()
                ZStack {
                    ForEach(dialogs.stack) { entry in
                        let top = entry.id == dialogs.stack.last?.id
                        SettingsDialogView(entry: entry, model: model, dialogs: dialogs)
                            .opacity(top ? 1 : 0)
                            .disabled(!top)
                    }
                }
                .padding(.vertical, 60)
                .focusSection()
                .onExitCommand { dialogs.pop() }
            }
        }
        .environmentObject(dialogs)
        .task { await model.observe() }
        .task {
            // Land on the selected category's rail item (defaultFocus alone loses to the first item
            // when the screen appears under the shell).
            railFocus = selected
            smokeDialog()
        }
    }

    private var workspace: some View {
        HStack(alignment: .top, spacing: dp(16)) {
            // NuvioTV's rail is a LazyColumn: it scrolls when the categories outgrow the workspace.
            ScrollView(.vertical, showsIndicators: false) {
                VStack(spacing: dp(10)) {
                    ForEach(Category.allCases) { category in
                        SettingsRailButton(title: category.title, icon: category.icon, selected: selected == category,
                                           focused: railFocus == category) { select(category) }
                            .focused($railFocus, equals: category)
                    }
                }
                .padding(.vertical, dp(4)).padding(.horizontal, dp(3))
            }
            .frame(width: dp(220))
            .frame(maxHeight: .infinity)
            .focusSection()
            .defaultFocus($railFocus, selected)

            ScrollView(.vertical, showsIndicators: false) {
                detail
                    .id(selected)
                    .transition(.asymmetric(insertion: .opacity.combined(with: .offset(x: dp(40))), removal: .opacity))
                    .frame(maxWidth: dp(880), alignment: .topLeading)
                    .padding(.vertical, dp(4))
                    .padding(.horizontal, dp(6))
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            .focusSection()
            // Menu in the detail goes back to its rail item (the detail's parent), as NuvioTV's Back.
            .onExitCommand { railFocus = selected }
        }
        .padding(dp(20))
        .background(RoundedRectangle(cornerRadius: dp(28), style: .continuous).fill(colors.backgroundElevated))
        .overlay(RoundedRectangle(cornerRadius: dp(28), style: .continuous).stroke(colors.border, lineWidth: NuvioTokens.Stroke.hairline))
        .padding(.top, 60).padding(.bottom, 60).padding(.leading, dp(16)).padding(.trailing, 80)
        .onChange(of: railFocus) { _, focused in
            guard let focused else { return }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.14) {
                if railFocus == focused { select(focused) }
            }
        }
    }

    private func select(_ category: Category) {
        guard selected != category else { return }
        withAnimation(.timingCurve(0.4, 0, 0.2, 1, duration: 0.2)) { selected = category }
    }

    @ViewBuilder
    private var detail: some View {
        switch selected {
        case .account: AccountSettingsDetail(model: model)
        case .profiles: ProfilesSettingsDetail()
        case .appearance: AppearanceSettingsDetail(model: model)
        case .layout: LayoutSettingsDetail()
        case .discovery: AddonsSettingsDetail(model: model)
        case .integrations: IptvSettingsDetail(model: model)
        case .playback: PlaybackSettingsDetail(model: model)
        case .about: AboutSettingsDetail()
        }
    }

    private func smokeDialog() {
        let args = ProcessInfo.processInfo.arguments
        guard let i = args.firstIndex(of: "-smokeSettingsDialog"), i + 1 < args.count else { return }
        switch args[i + 1] {
        case "addPlaylist": dialogs.push(.playlistForm(PlaylistFormModel(editing: nil)))
        case "stalker":
            let form = PlaylistFormModel(editing: nil); form.sourceType = "stalker"; dialogs.push(.playlistForm(form))
        case "signOut": dialogs.push(.signOut)
        case "engine":
            dialogs.push(.picker(PlaybackSettingsDetail.enginePicker(current: UserDefaults.standard.string(forKey: "tvos.player.engine") ?? "auto")))
        case "removePlaylist":
            Task {
                try? await Task.sleep(nanoseconds: 1_500_000_000)
                if let account = model.xtream?.accounts.first { dialogs.push(.removePlaylist(account)) }
            }
        default: break
        }
    }
}

// MARK: - State

/// Everything the settings detail panes read, collected from the shared repositories.
@MainActor
final class SettingsModel: ObservableObject {
    @Published var auth: AuthState?
    @Published var xtream: XtreamUiState?
    @Published var addons: [ManagedAddon] = []
    @Published var player: PlayerSettingsUiState?
    @Published var theme: AppTheme = .marigold
    @Published var amoled = false

    func observe() async {
        TvPlaylists.shared.ensureLoaded()
        PlayerSettingsRepository.shared.ensureLoaded()
        ThemeSettingsRepository.shared.ensureLoaded()
        await withTaskGroup(of: Void.self) { group in
            group.addTask { @MainActor in for await s in AuthRepository.shared.state { self.auth = s } }
            group.addTask { @MainActor in for await s in TvPlaylists.shared.state { self.xtream = s } }
            group.addTask { @MainActor in for await s in AddonRepository.shared.uiState { self.addons = s.addons } }
            group.addTask { @MainActor in for await s in PlayerSettingsRepository.shared.uiState { self.player = s } }
            group.addTask { @MainActor in for await t in ThemeSettingsRepository.shared.selectedTheme { self.theme = t } }
            group.addTask { @MainActor in for await on in ThemeSettingsRepository.shared.amoledEnabled { self.amoled = on.boolValue } }
        }
    }
}

enum SettingsDialogKind {
    case signOut
    case picker(PickerSpec)
    case playlistActions(XtreamAccount)
    case removePlaylist(XtreamAccount)
    case playlistForm(PlaylistFormModel)
    case addonActions(ManagedAddon)
    case removeAddon(ManagedAddon)
    case qr(url: String, instruction: String)
    case licences
}

struct PickerSpec {
    let title: String
    var subtitle: String? = nil
    let options: [SettingsPickerOption]
    let selectedId: String
    var width: CGFloat = dp(420)
    let onSelect: (String) -> Void
}

struct SettingsDialogEntry: Identifiable {
    let id = UUID()
    let kind: SettingsDialogKind
}

/// Dialogs stack (a picker can open over the playlist form, as NuvioTV's auto-refresh picker does).
@MainActor
final class SettingsDialogs: ObservableObject {
    @Published private(set) var stack: [SettingsDialogEntry] = []
    func push(_ kind: SettingsDialogKind) { stack.append(SettingsDialogEntry(kind: kind)) }
    func pop() { _ = stack.popLast() }
}

private struct SettingsDialogView: View {
    let entry: SettingsDialogEntry
    @ObservedObject var model: SettingsModel
    @ObservedObject var dialogs: SettingsDialogs

    var body: some View {
        switch entry.kind {
        case .signOut:
            // AccountSignOutConfirmationDialog
            NuvioDialog(title: "Sign out?",
                        subtitle: "You will need to sign in again to sync library, watch progress, addons, and plugins on this device.") {
                HStack(spacing: dp(8)) {
                    Spacer()
                    SettingsDialogButton(title: "Cancel") { dialogs.pop() }
                    SettingsDialogButton(title: "Sign Out", destructive: true) {
                        dialogs.pop()
                        Task { try? await AuthRepository.shared.signOut() }
                    }
                }
            }
        case .picker(let spec):
            SettingsSingleChoiceDialog(title: spec.title, subtitle: spec.subtitle, options: spec.options,
                                       selectedId: spec.selectedId, width: spec.width) { id in
                dialogs.pop()
                spec.onSelect(id)
            }
        case .playlistActions(let account):
            PlaylistActionsDialog(account: account, dialogs: dialogs)
        case .removePlaylist(let account):
            RemovePlaylistDialog(account: account, dialogs: dialogs)
        case .playlistForm(let form):
            PlaylistFormDialog(form: form, xtream: model.xtream, dialogs: dialogs)
        case .addonActions(let addon):
            NuvioDialog(title: addon.displayTitle, subtitle: addon.manifestUrl) {
                SettingsActionRow(title: addon.enabled ? "Disable" : "Enable", showChevron: false) {
                    AddonRepository.shared.setAddonEnabled(manifestUrl: addon.manifestUrl, enabled: !addon.enabled)
                    dialogs.pop()
                }
                SettingsActionRow(title: "Remove", showChevron: false) {
                    dialogs.pop()
                    dialogs.push(.removeAddon(addon))
                }
            }
        case .removeAddon(let addon):
            NuvioDialog(title: "Delete addon?", subtitle: "Are you sure you want to delete it?") {
                HStack(spacing: dp(8)) {
                    Spacer()
                    SettingsDialogButton(title: "No") { dialogs.pop() }
                    SettingsDialogButton(title: "Yes", destructive: true) {
                        AddonRepository.shared.removeAddon(manifestUrl: addon.manifestUrl)
                        dialogs.pop()
                    }
                }
            }
        case .qr(let url, let instruction):
            QrHandOffDialog(url: url, instruction: instruction) { dialogs.pop() }
        case .licences:
            LicencesDialog { dialogs.pop() }
        }
    }
}

// MARK: - Account

/// AccountSettingsInline + account/AccountSettingsContent.kt.
private struct AccountSettingsDetail: View {
    @ObservedObject var model: SettingsModel
    @EnvironmentObject private var dialogs: SettingsDialogs
    @Environment(\.nuvio) private var colors
    @State private var syncing = false

    var body: some View {
        VStack(alignment: .leading, spacing: dp(12)) {
            SettingsDetailHeader(title: "Account", subtitle: "Account and sync status")
            SettingsGroupCard {
                VStack(alignment: .leading, spacing: dp(6)) {
                    switch model.auth.map({ onEnum(of: $0) }) {
                    case .authenticated(let signedIn) where !signedIn.isAnonymous:
                        statusCard(email: signedIn.email ?? "")
                        AccountActionButton(icon: "md_sync", title: "Sync now",
                                            subtitle: "Get the latest from your other devices and send anything that hasn't synced yet") {
                            TvSettings.shared.syncNow()
                        }
                        signOutButton.padding(.top, dp(8))
                    case .authenticated, .unauthenticated:
                        Text("Sync your library, watch progress, addons, and plugins across devices.")
                            .font(NuvioType.bodySmall).foregroundStyle(colors.textSecondary)
                    case .loading, .none:
                        Text("Loading…").font(NuvioType.bodyMedium).foregroundStyle(colors.textSecondary)
                    }
                }
            }
        }
    }

    /// StatusCard: Secondary 10% on radius 8, check icon, "Signed in" label + email.
    private func statusCard(email: String) -> some View {
        HStack(spacing: dp(8)) {
            Image("md_check_circle").renderingMode(.template).resizable().frame(width: dp(16), height: dp(16)).foregroundStyle(colors.secondary)
            Text("Signed in").font(NuvioType.labelSmall).foregroundStyle(colors.textTertiary)
            Text(email).font(NuvioType.inter(12, .medium)).foregroundStyle(colors.textPrimary).lineLimit(1)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, dp(12)).padding(.vertical, dp(8))
        .background(RoundedRectangle(cornerRadius: dp(8)).fill(colors.secondary.opacity(0.1)))
    }

    private var signOutButton: some View {
        SignOutButton { dialogs.push(.signOut) }
    }
}

/// SettingsActionButton (AccountSettingsContent.kt): BackgroundCard, FocusBackground + 2dp FocusRing
/// focused, radius 8, 22dp icon (Primary focused), bodyMedium Medium title, 11sp subtitle.
private struct AccountActionButton: View {
    let icon: String
    let title: String
    let subtitle: String
    let action: () -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        Button(action: action) {
            HStack(spacing: dp(12)) {
                Image(icon).renderingMode(.template).resizable().frame(width: dp(22), height: dp(22))
                    .foregroundStyle(focused ? colors.primary : colors.textSecondary)
                VStack(alignment: .leading, spacing: 0) {
                    Text(title).font(NuvioType.inter(14, .medium)).foregroundStyle(colors.textPrimary)
                    Text(subtitle).font(NuvioType.inter(11, .regular)).foregroundStyle(colors.textSecondary)
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, dp(14)).padding(.vertical, dp(10))
            .background(RoundedRectangle(cornerRadius: dp(8)).fill(focused ? colors.focusBackground : colors.backgroundCard))
            .overlay(RoundedRectangle(cornerRadius: dp(8)).stroke(focused ? colors.focusRing : .clear, lineWidth: NuvioTokens.Stroke.focus))
            .scaleEffect(focused ? 1.02 : 1)
            .animation(NuvioTokens.Motion.fast, value: focused)
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused($focused)
        .reportsFocus(focused)
    }
}

/// SignOutSettingsButton: #C62828 at 12% (25% focused), #F44336 icon/text, 50% ring when focused.
private struct SignOutButton: View {
    let action: () -> Void
    @FocusState private var focused: Bool

    var body: some View {
        let red = Color(argb: 0xFFF44336)
        Button(action: action) {
            HStack(spacing: dp(10)) {
                Image("md_logout").renderingMode(.template).resizable().frame(width: dp(18), height: dp(18))
                Text("Sign Out").font(NuvioType.inter(14, .medium))
                Spacer(minLength: 0)
            }
            .foregroundStyle(red)
            .padding(.horizontal, dp(14)).padding(.vertical, dp(8))
            .background(RoundedRectangle(cornerRadius: dp(8)).fill(Color(argb: 0xFFC62828).opacity(focused ? 0.25 : 0.12)))
            .overlay(RoundedRectangle(cornerRadius: dp(8)).stroke(focused ? red.opacity(0.5) : .clear, lineWidth: NuvioTokens.Stroke.focus))
            .scaleEffect(focused ? 1.02 : 1)
            .animation(NuvioTokens.Motion.fast, value: focused)
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused($focused)
        .reportsFocus(focused)
    }
}

// MARK: - Profiles

/// ProfileSettingsContent.kt. Manage Profiles opens the picker in manage mode (edit, PIN, delete, add).
private struct ProfilesSettingsDetail: View {
    var body: some View {
        VStack(alignment: .leading, spacing: dp(12)) {
            SettingsDetailHeader(title: "Profiles", subtitle: "Manage user profiles for this account")
            SettingsGroupCard {
                SettingsActionRow(title: "Manage Profiles") {
                    ProfilePickerLaunch.manageRequested = true
                    TvAppLifecycle.shared.openProfilePicker()
                }
            }
        }
    }
}

// MARK: - Appearance

/// ThemeSettingsContent (ThemeSettingsScreen.kt): colour-theme swatches + AMOLED. The synced
/// ThemeSettingsRepository drives the whole app live. Settings style, launcher artwork, font and app
/// language have no Apple TV equivalent here; "Pure Black Surfaces" is not in the shared theme settings.
private struct AppearanceSettingsDetail: View {
    @ObservedObject var model: SettingsModel
    /// The free themes (supporter themes are the inert membership subsystem and resolve to Marigold).
    private let themes: [(AppTheme, String)] = [(.marigold, "Marigold"), (.crimson, "Crimson"), (.ocean, "Ocean"),
                                                 (.violet, "Violet"), (.emerald, "Emerald"), (.amber, "Amber"),
                                                 (.rose, "Rose"), (.white, "White")]

    var body: some View {
        VStack(alignment: .leading, spacing: dp(14)) {
            SettingsDetailHeader(title: "Appearance", subtitle: "Choose your color theme, font and language")
            SettingsGroupCard(title: "Color Theme", subtitle: "Pick the accent color used across the app") {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: dp(10)) {
                        ForEach(themes, id: \.1) { theme, name in
                            ThemeSwatchChip(name: name, palette: NuvioPalette.of(theme), selected: model.theme == theme) {
                                ThemeSettingsRepository.shared.setTheme(theme: theme)
                            }
                        }
                    }
                    .padding(dp(4))
                }
                .scrollClipDisabled()
                .focusSection()
                SettingsToggleRow(title: "AMOLED Mode", subtitle: "Use pure black for app backgrounds", isOn: model.amoled) {
                    ThemeSettingsRepository.shared.setAmoled(enabled: !model.amoled)
                }
            }
        }
    }
}

/// ThemeSwatchChip: 96dp card, radius 18, 40dp accent circle with a check when selected, labelMedium name.
private struct ThemeSwatchChip: View {
    let name: String
    let palette: NuvioPalette
    let selected: Bool
    let action: () -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        Button(action: action) {
            VStack(spacing: dp(8)) {
                Circle().fill(palette.secondary).frame(width: dp(40), height: dp(40))
                    .overlay {
                        if selected {
                            Image("md_check").renderingMode(.template).resizable().frame(width: dp(20), height: dp(20))
                                .foregroundStyle(palette.onSecondary)
                        }
                    }
                Text(name).font(NuvioType.labelMedium).lineLimit(1)
                    .foregroundStyle(focused || selected ? colors.textPrimary : colors.textSecondary)
            }
            .padding(.horizontal, dp(8)).padding(.vertical, dp(12))
            .frame(width: dp(96))
            .background(RoundedRectangle(cornerRadius: dp(18), style: .continuous).fill(colors.background))
            .overlay(RoundedRectangle(cornerRadius: dp(18), style: .continuous).stroke(focused ? colors.focusRing : .clear, lineWidth: NuvioTokens.Stroke.focus))
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused($focused)
        .reportsFocus(focused)
    }
}

// MARK: - Layout

/// LayoutSettingsContent (LayoutSettingsScreen.kt), the options Apple TV screens honour. Device-local,
/// like NuvioTV's LayoutPreferenceDataStore.
private struct LayoutSettingsDetail: View {
    @AppStorage(NuvioLayoutPrefs.collapseSidebarKey) private var collapseSidebar = false
    @AppStorage(NuvioLayoutPrefs.showHeroKey) private var showHero = true
    @AppStorage(NuvioLayoutPrefs.posterLabelsKey) private var posterLabels = true
    @AppStorage(NuvioLayoutPrefs.posterWidthKey) private var posterWidth: Double = 126
    @AppStorage(NuvioLayoutPrefs.cwEnabledKey) private var cwEnabled = true
    @AppStorage(NuvioLayoutPrefs.cwStyleKey) private var cwStyle = "card"
    @AppStorage(NuvioLayoutPrefs.homeLayoutKey) private var homeLayout = "modern"
    @Environment(\.nuvio) private var colors

    private let widths: [(String, Double)] = [("Compact", 104), ("Dense", 112), ("Standard", 120),
                                              ("Balanced", 126), ("Comfort", 134), ("Large", 140)]

    var body: some View {
        VStack(alignment: .leading, spacing: dp(14)) {
            SettingsDetailHeader(title: "Layout Settings", subtitle: "Adjust home layout, content visibility, and poster behavior")
            SettingsGroupCard(title: "Home Layout", subtitle: "Choose structure and hero source.") {
                HStack(spacing: dp(12)) {
                    ForEach([("modern", "Modern View"), ("grid", "Grid View"), ("classic", "Classic View")], id: \.0) { key, label in
                        HomeLayoutCard(layout: key, title: label, selected: homeLayout == key) { homeLayout = key }
                    }
                }
                .focusSection()
            }
            SettingsGroupCard(title: "Home Content", subtitle: "Control what appears on home and search.") {
                SettingsToggleRow(title: "Collapse Sidebar", subtitle: "Hide sidebar by default; show when focused.", isOn: collapseSidebar) { collapseSidebar.toggle() }
                SettingsToggleRow(title: "Show Hero Section", subtitle: "Display hero carousel at top of home.", isOn: showHero) { showHero.toggle() }
                SettingsToggleRow(title: "Show Poster Labels", subtitle: "Show titles under posters in rows, grid, and see-all.", isOn: posterLabels) { posterLabels.toggle() }
            }
            SettingsGroupCard(title: "Poster Card", subtitle: "Tune card width and corner radius.") {
                optionRow(title: "Width", selected: widths.first { $0.1 == posterWidth }?.0 ?? "Custom") {
                    ForEach(widths, id: \.1) { label, value in
                        SettingsChoiceChip(label: label, selected: posterWidth == value) { posterWidth = value }
                    }
                }
                SettingsDialogButton(title: "Reset to Default") { posterWidth = 126 }
            }
            SettingsGroupCard(title: "Continue Watching", subtitle: "Settings for the Continue Watching section.") {
                SettingsToggleRow(title: "Show Continue Watching", subtitle: "Show the Continue Watching row on the home screen.", isOn: cwEnabled) { cwEnabled.toggle() }
                if cwEnabled {
                    HStack(spacing: dp(8)) {
                        ForEach([("card", "Card"), ("wide", "Wide"), ("poster", "Poster")], id: \.0) { key, label in
                            SettingsChoiceChip(label: label, selected: cwStyle == key) { cwStyle = key }
                        }
                    }
                    .focusSection()
                }
            }
        }
    }

    private func optionRow<Chips: View>(title: String, selected: String, @ViewBuilder chips: () -> Chips) -> some View {
        VStack(alignment: .leading, spacing: dp(8)) {
            HStack {
                Text(title).font(NuvioType.bodyLarge).foregroundStyle(colors.textPrimary)
                Spacer()
                Text(selected).font(NuvioType.labelLarge).foregroundStyle(colors.textSecondary)
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: dp(8)) { chips() }.padding(dp(4))
            }
            .scrollClipDisabled()
            .focusSection()
        }
    }
}

/// LayoutSettingsScreen.kt LayoutCard: a Background card (radius 12) with a 112dp sketch of the layout
/// and its name (labelLarge) under it, a check when selected; selected 1dp FocusRing, focused 2dp. The
/// sketches are static (NuvioTV animates them).
private struct HomeLayoutCard: View {
    let layout: String
    let title: String
    let selected: Bool
    let action: () -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        Button(action: action) {
            VStack(spacing: dp(6)) {
                sketch.frame(maxWidth: .infinity).frame(height: dp(112))
                HStack(spacing: dp(6)) {
                    if selected {
                        Image("md_check_circle").renderingMode(.template).resizable().frame(width: dp(14), height: dp(14)).foregroundStyle(colors.focusRing)
                    }
                    Text(title).font(NuvioType.labelLarge).foregroundStyle(selected || focused ? colors.textPrimary : colors.textSecondary)
                }
            }
            .padding(dp(10))
            .frame(maxWidth: .infinity)
            .background(RoundedRectangle(cornerRadius: dp(12), style: .continuous).fill(colors.background))
            .overlay(RoundedRectangle(cornerRadius: dp(12), style: .continuous)
                .stroke(focused || selected ? colors.focusRing : .clear, lineWidth: focused ? NuvioTokens.Stroke.focus : NuvioTokens.Stroke.hairline))
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused($focused)
        .reportsFocus(focused)
    }

    private var block: Color { colors.textTertiary.opacity(0.45) }

    @ViewBuilder
    private var sketch: some View {
        switch layout {
        case "classic":
            VStack(alignment: .leading, spacing: dp(5)) {
                RoundedRectangle(cornerRadius: dp(4)).fill(block.opacity(0.6)).frame(height: dp(44))
                ForEach(0..<2, id: \.self) { _ in
                    HStack(spacing: dp(4)) { ForEach(0..<6, id: \.self) { _ in RoundedRectangle(cornerRadius: dp(3)).fill(block) } }
                        .frame(height: dp(26))
                }
            }
        case "grid":
            VStack(alignment: .leading, spacing: dp(4)) {
                RoundedRectangle(cornerRadius: dp(4)).fill(block.opacity(0.6)).frame(height: dp(30))
                ForEach(0..<3, id: \.self) { _ in
                    HStack(spacing: dp(4)) { ForEach(0..<7, id: \.self) { _ in RoundedRectangle(cornerRadius: dp(3)).fill(block) } }
                        .frame(height: dp(22))
                }
            }
        default:
            VStack(alignment: .leading, spacing: dp(5)) {
                HStack(spacing: dp(6)) {
                    VStack(alignment: .leading, spacing: dp(4)) {
                        RoundedRectangle(cornerRadius: dp(2)).fill(block).frame(width: dp(50), height: dp(8))
                        RoundedRectangle(cornerRadius: dp(2)).fill(block.opacity(0.7)).frame(width: dp(70), height: dp(5))
                        RoundedRectangle(cornerRadius: dp(2)).fill(block.opacity(0.7)).frame(width: dp(60), height: dp(5))
                    }
                    RoundedRectangle(cornerRadius: dp(4)).fill(block.opacity(0.6))
                }
                .frame(height: dp(56))
                HStack(spacing: dp(4)) { ForEach(0..<6, id: \.self) { _ in RoundedRectangle(cornerRadius: dp(3)).fill(block) } }
                    .frame(height: dp(40))
            }
        }
    }
}

// MARK: - Content & Discovery (add-ons)

/// ContentDiscoverySettingsContent + the Addons screen (ui/screens/addon), inline: install by URL,
/// the installed list, enable/disable/remove, refresh.
private struct AddonsSettingsDetail: View {
    @ObservedObject var model: SettingsModel
    @EnvironmentObject private var dialogs: SettingsDialogs
    @Environment(\.nuvio) private var colors
    @State private var url = ""
    @State private var installing = false
    @State private var status: (text: String, error: Bool)?

    var body: some View {
        VStack(alignment: .leading, spacing: dp(12)) {
            SettingsDetailHeader(title: "Content & Discovery", subtitle: "Add-ons, plugins, catalogs, and discovery sources")
            SettingsGroupCard(title: "Install addon") {
                SettingsTextField(label: "Addon URL", hint: "https://example.com", text: $url) { install() }
                HStack(spacing: dp(12)) {
                    SettingsDialogButton(title: installing ? "Installing" : "Install", primary: true) { install() }
                    if let status {
                        Text(status.text).font(NuvioType.bodySmall).foregroundStyle(status.error ? colors.error : colors.textSecondary)
                    }
                }
            }
            SettingsGroupCard(title: "Installed") {
                if model.addons.isEmpty {
                    Text("No addons installed. Add one to get started.").font(NuvioType.bodyMedium).foregroundStyle(colors.textSecondary)
                }
                ForEach(model.addons, id: \.manifestUrl) { addon in
                    SettingsActionRow(title: addon.displayTitle,
                                      subtitle: addon.errorMessage ?? addon.manifest?.description_,
                                      value: addon.enabled ? "On" : "Off") {
                        dialogs.push(.addonActions(addon))
                    }
                }
                SettingsActionRow(title: "Refresh Addons", subtitle: "Pull latest addon changes for current profile") {
                    AddonRepository.shared.refreshAll()
                }
            }
        }
    }

    private func install() {
        let raw = url.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !installing else { return }
        guard !raw.isEmpty else { status = ("Enter a valid addon URL", true); return }
        if raw.contains("://"), !(raw.lowercased().hasPrefix("http://") || raw.lowercased().hasPrefix("https://")) {
            status = ("Addon URL must start with http or https", true); return
        }
        installing = true
        status = nil
        Task { @MainActor in
            defer { installing = false }
            do {
                let result = try await AddonRepository.shared.addAddon(rawUrl: raw)
                switch onEnum(of: result) {
                case .success(let ok): status = ("Installed \(ok.manifest.name)", false); url = ""
                case .error(let failure): status = (failure.message, true)
                }
            } catch {
                status = (error.localizedDescription, true)
            }
        }
    }
}

// MARK: - Integrations (IPTV)

/// XtreamSettingsContent (XtreamSettingsScreen.kt). NuvioTV reaches it from an Integrations hub; with
/// IPTV the only integration ported to Apple TV, the hub would be one extra click, so it opens here.
/// Not ported: "Add from phone" pairing, Guide regions, Content & Categories, Hidden channels, and the
/// catch-up / guide-offset pickers.
private struct IptvSettingsDetail: View {
    @ObservedObject var model: SettingsModel
    @EnvironmentObject private var dialogs: SettingsDialogs

    var body: some View {
        VStack(alignment: .leading, spacing: dp(12)) {
            SettingsDetailHeader(title: "IPTV (Xtream Codes)", subtitle: "Paste your IPTV portal or M3U URL to add a provider.")
            SettingsGroupCard {
                SettingsActionRow(title: "Add IPTV account", subtitle: "Paste a portal / M3U URL", leadingIcon: "md_add") {
                    TvPlaylists.shared.clearError()
                    dialogs.push(.playlistForm(PlaylistFormModel(editing: nil)))
                }
                ForEach(model.xtream?.accounts ?? [], id: \.id) { account in
                    SettingsActionRow(title: account.name,
                                      subtitle: [account.fileName ?? account.baseUrl, model.xtream?.saveWarnings[account.id]]
                                          .compactMap { $0 }.joined(separator: "\n"),
                                      value: account.enabled ? "On" : "Off") {
                        dialogs.push(.playlistActions(account))
                    }
                }
            }
        }
    }
}

private struct PlaylistActionsDialog: View {
    let account: XtreamAccount
    @ObservedObject var dialogs: SettingsDialogs

    var body: some View {
        NuvioDialog(title: account.name, subtitle: account.fileName ?? account.baseUrl) {
            if account.sourceType != "m3u_file" {
                SettingsActionRow(title: "Edit URL / credentials") {
                    dialogs.pop()
                    TvPlaylists.shared.clearError()
                    dialogs.push(.playlistForm(PlaylistFormModel(editing: account)))
                }
            }
            if TvPlaylists.shared.canRematch(account: account) {
                SettingsActionRow(title: "Re-match catalog", subtitle: "Re-check titles this playlist was thought not to have") {
                    TvPlaylists.shared.rematch(accountId: account.id)
                    dialogs.pop()
                }
            }
            SettingsActionRow(title: account.enabled ? "Disable" : "Enable", showChevron: false) {
                TvPlaylists.shared.setEnabled(accountId: account.id, enabled: !account.enabled)
                dialogs.pop()
            }
            SettingsActionRow(title: "Remove playlist", showChevron: false) {
                dialogs.pop()
                dialogs.push(.removePlaylist(account))
            }
        }
    }
}

/// B57's confirmation: names the playlist and what goes with it; Cancel takes focus first.
private struct RemovePlaylistDialog: View {
    let account: XtreamAccount
    @ObservedObject var dialogs: SettingsDialogs
    @FocusState private var cancelFocused: Bool

    var body: some View {
        NuvioDialog(title: "Remove \u{201C}\(account.name)\u{201D}?",
                    subtitle: "Its favourites, Continue Watching entries and watch progress go with it, on all your devices. This can't be undone.",
                    width: dp(460)) {
            SettingsDialogButton(title: "Remove playlist", destructive: true, fullWidth: true) {
                TvPlaylists.shared.remove(accountId: account.id)
                dialogs.pop()
            }
            SettingsDialogButton(title: "Cancel", fullWidth: true) { dialogs.pop() }
                .focused($cancelFocused)
        }
        .defaultFocus($cancelFocused, true)
        .onAppear { DispatchQueue.main.async { cancelFocused = true } }
    }
}

/// The "Add Playlist" form's state, kept outside the view so a picker can open over it.
@MainActor
final class PlaylistFormModel: ObservableObject {
    let editing: XtreamAccount?
    @Published var sourceType = "xtream"
    @Published var pasteLink = false
    @Published var playlistUrl = ""
    @Published var server = ""
    @Published var username = ""
    @Published var password = ""
    @Published var name = ""
    @Published var userAgent = ""
    @Published var m3uUrl = ""
    @Published var portalUrl = ""
    @Published var macAddress = ""
    @Published var stalkerUsername = ""
    @Published var stalkerPassword = ""
    @Published var serialNumber = ""
    @Published var deviceId = ""
    @Published var sendDeviceId = true
    @Published var epgUrl = ""
    @Published var autoRefreshHours: Int32 = TvPlaylistFormPolicy.shared.DEFAULT_AUTO_REFRESH_HOURS

    init(editing: XtreamAccount?) {
        self.editing = editing
        guard let editing else { return }
        let f = TvPlaylistFormPolicy.shared.fromAccount(account: editing)
        sourceType = f.sourceType; server = f.server; username = f.username; password = f.password
        name = f.name; userAgent = f.userAgent; m3uUrl = f.m3uUrl; portalUrl = f.portalUrl
        macAddress = f.macAddress; stalkerUsername = f.stalkerUsername; stalkerPassword = f.stalkerPassword
        serialNumber = f.serialNumber; deviceId = f.deviceId; sendDeviceId = f.sendDeviceId
        epgUrl = f.epgUrl; autoRefreshHours = f.autoRefreshHours
    }

    var kotlin: TvPlaylistForm {
        TvPlaylistForm(sourceType: sourceType, pasteLink: pasteLink, playlistUrl: playlistUrl, server: server,
                       username: username, password: password, name: name, userAgent: userAgent, m3uUrl: m3uUrl,
                       portalUrl: portalUrl, macAddress: macAddress, stalkerUsername: stalkerUsername,
                       stalkerPassword: stalkerPassword, serialNumber: serialNumber, deviceId: deviceId,
                       sendDeviceId: sendDeviceId, epgUrl: epgUrl, autoRefreshHours: autoRefreshHours)
    }
}

/// XtreamAddDialog: 760dp NuvioDialog, source-type tiles, the source's fields, EPG URL, auto-refresh,
/// the no-content disclaimer, the Add button and the verify status. DNS provider is left out: Apple TV
/// (like iOS) can't resolve one playlist through its own DNS.
private struct PlaylistFormDialog: View {
    @ObservedObject var form: PlaylistFormModel
    let xtream: XtreamUiState?
    @ObservedObject var dialogs: SettingsDialogs
    @Environment(\.nuvio) private var colors
    @FocusState private var sourceFocus: String?

    private let sources = [("m3u_url", "URL"), ("m3u_file", "File"), ("xtream", "Xtream"), ("stalker", "Stalker")]

    var body: some View {
        let validating = xtream?.isValidating ?? false
        NuvioDialog(title: form.editing != nil ? "Edit Playlist" : "Add Playlist",
                    subtitle: form.editing != nil ? "Update this playlist's source and options" : "Add an IPTV source and choose its options",
                    width: dp(760)) {
            VStack(alignment: .leading, spacing: dp(16)) {
                label("Source Type")
                HStack(spacing: dp(8)) {
                    ForEach(sources, id: \.0) { id, title in
                        SettingsChoiceChip(label: title, selected: form.sourceType == id) { form.sourceType = id }
                            .focused($sourceFocus, equals: id)
                    }
                }
                .focusSection()

                sourceFields

                label("EPG URL (optional)")
                SettingsTextField(label: "EPG URL", hint: "http://host:port/xmltv.php?username=…&password=…", text: $form.epgUrl)

                label("Auto-Refresh")
                SettingsActionRow(title: "Auto-Refresh", value: TvPlaylistFormPolicy.shared.autoRefreshLabel(hours: form.autoRefreshHours)) {
                    dialogs.push(.picker(PickerSpec(
                        title: "Auto-Refresh",
                        options: TvPlaylistFormPolicy.shared.autoRefreshOptions.map {
                            SettingsPickerOption(id: "\($0.int32Value)", title: TvPlaylistFormPolicy.shared.autoRefreshLabel(hours: $0.int32Value))
                        },
                        selectedId: "\(form.autoRefreshHours)") { id in form.autoRefreshHours = Int32(id) ?? 24 }))
                }
                SettingsHelperText(text: "Periodically check this playlist for new movies and series.")
                SettingsHelperText(text: "Tuvora does not provide any channels, movies, or series. All content comes from the playlist you add here, and you must have a valid subscription with that provider.")

                AddPlaylistButton(label: validating ? "Verifying…" : (form.editing != nil ? "Save changes" : "Add Playlist"),
                                  enabled: TvPlaylistFormPolicy.shared.canSubmit(form: form.kotlin)) { submit() }

                if validating {
                    Text("Verifying…").font(NuvioType.bodySmall).foregroundStyle(colors.textSecondary)
                } else if let error = xtream?.error {
                    Text(error).font(NuvioType.bodySmall).foregroundStyle(colors.error)
                }
            }
        }
        .defaultFocus($sourceFocus, form.sourceType)
        .onAppear { let type = form.sourceType; DispatchQueue.main.async { sourceFocus = type } }
    }

    @ViewBuilder
    private var sourceFields: some View {
        switch form.sourceType {
        case "m3u_url":
            SettingsTextField(label: "M3U URL", hint: "(http://host/get.php?…&type=m3u_plus  or  …/playlist.m3u)", text: $form.m3uUrl, onSubmit: submit)
            SettingsTextField(label: "User-Agent (optional)", text: $form.userAgent, onSubmit: submit)
            SettingsTextField(label: "Name (optional)", text: $form.name, onSubmit: submit)
            SettingsHelperText(text: "Paste the playlist URL. The whole list is downloaded and indexed once so it browses fast; large lists take a moment after saving.")
        case "m3u_file":
            SettingsHelperText(text: "No file picker on this device — add the file by URL or from your phone (it syncs).")
        case "stalker":
            SettingsTextField(label: "Portal URL", hint: "(http://host:port)", text: $form.portalUrl, onSubmit: submit)
            SettingsTextField(label: "MAC Address", hint: "(00:1A:79:xx:xx:xx)", text: $form.macAddress, onSubmit: submit)
            SettingsActionRow(title: "Randomize MAC", subtitle: "Generate a virtual STB MAC (Infomir range)") {
                form.macAddress = TvPlaylistFormPolicy.shared.randomStbMac()
            }
            SettingsTextField(label: "Username (optional)", text: $form.stalkerUsername, onSubmit: submit)
            SettingsTextField(label: "Password (optional)", text: $form.stalkerPassword, secure: true, onSubmit: submit)
            SettingsTextField(label: "Serial Number (optional)", text: $form.serialNumber, onSubmit: submit)
            SettingsTextField(label: "Device ID (optional)", text: $form.deviceId, onSubmit: submit)
            SettingsActionRow(title: "Send Device ID", subtitle: "Include device identifier in portal requests",
                              value: form.sendDeviceId ? "On" : "Off") { form.sendDeviceId.toggle() }
            SettingsHelperText(text: "Enter your portal URL and the MAC registered with the provider. Serial / Device ID override the values derived from the MAC.")
        default:
            HStack(spacing: dp(8)) {
                SettingsChoiceChip(label: "Enter details", selected: !form.pasteLink) { form.pasteLink = false }
                SettingsChoiceChip(label: "Paste link", selected: form.pasteLink) { form.pasteLink = true }
            }
            .focusSection()
            .padding(.top, dp(4))
            if form.pasteLink {
                SettingsTextField(label: "Playlist URL", hint: "http://host:port/get.php?username=…&password=…", text: $form.playlistUrl, onSubmit: submit)
            } else {
                SettingsTextField(label: "Server URL", hint: "(portal, e.g. http://host:port)", text: $form.server, onSubmit: submit)
                SettingsTextField(label: "Username", text: $form.username, onSubmit: submit)
                SettingsTextField(label: "Password", text: $form.password, secure: true, onSubmit: submit)
                SettingsTextField(label: "Name (optional)", text: $form.name, onSubmit: submit)
                SettingsTextField(label: "User-Agent (optional)", text: $form.userAgent, onSubmit: submit)
            }
        }
    }

    private func label(_ text: String) -> some View {
        Text(text).font(NuvioType.labelLarge).foregroundStyle(colors.textPrimary)
    }

    private func submit() {
        let kotlinForm = form.kotlin
        guard TvPlaylistFormPolicy.shared.canSubmit(form: kotlinForm), !(xtream?.isValidating ?? false) else { return }
        let done: (KotlinBoolean) -> Void = { ok in
            if ok.boolValue { DispatchQueue.main.async { dialogs.pop() } }
        }
        if let editing = form.editing {
            TvPlaylists.shared.edit(accountId: editing.id, form: kotlinForm, onResult: done)
        } else {
            TvPlaylists.shared.add(form: kotlinForm, onResult: done)
        }
    }
}

/// XtreamAddButton: full width, radius 10, BackgroundElevated + 1dp Border; Primary fill when focused.
private struct AddPlaylistButton: View {
    let label: String
    let enabled: Bool
    let action: () -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        Button(action: action) {
            Text(label).font(NuvioType.bodyMedium).foregroundStyle(colors.textPrimary.opacity(enabled ? 1 : 0.4))
                .frame(maxWidth: .infinity).padding(.vertical, dp(12))
                .background(RoundedRectangle(cornerRadius: dp(10)).fill(focused ? colors.primary : colors.backgroundElevated))
                .overlay(RoundedRectangle(cornerRadius: dp(10)).stroke(focused ? colors.primary : colors.border, lineWidth: NuvioTokens.Stroke.hairline))
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused($focused)
        .reportsFocus(focused)
        .padding(.top, dp(12))
    }
}

// MARK: - Playback

/// PlaybackSettingsContent (PlaybackSettingsSections.kt + PlaybackAudio/Subtitle/AutoPlaySettings.kt),
/// the options with meaning on Apple TV. Shared settings (synced) go through PlayerSettingsRepository;
/// the engine choice is Apple-TV-only and read by TvPlayerSession at open.
private struct PlaybackSettingsDetail: View {
    @ObservedObject var model: SettingsModel
    @EnvironmentObject private var dialogs: SettingsDialogs
    @AppStorage("tvos.player.engine") private var engine = "auto"

    var body: some View {
        let player = model.player
        VStack(alignment: .leading, spacing: dp(14)) {
            SettingsDetailHeader(title: "Playback Settings", subtitle: "Configure video playback and subtitle options")
            SettingsGroupCard(title: "Player & Stream Selection", subtitle: "Player preference, auto-play, and source filtering.") {
                SettingsActionRow(title: "Internal Engine", value: Self.engineLabel(engine)) {
                    dialogs.push(.picker(Self.enginePicker(current: engine) { engine = $0 }))
                }
                SettingsActionRow(title: "Auto Stream Selection", value: autoPlayLabel(player?.streamAutoPlayMode)) {
                    dialogs.push(.picker(PickerSpec(
                        title: "Auto Stream Selection",
                        options: [SettingsPickerOption(id: "manual", title: "Manual (choose stream)", description: "Always show source list and let me choose."),
                                  SettingsPickerOption(id: "first", title: "Auto-play first source", description: "Play the first available source automatically.")],
                        selectedId: player?.streamAutoPlayMode == .firstStream ? "first" : "manual", width: dp(520)) { id in
                            PlayerSettingsRepository.shared.setStreamAutoPlayMode(mode: id == "first" ? .firstStream : .manual)
                        }))
                }
                SettingsToggleRow(title: "Auto-play Next Episode", subtitle: "Start next episode automatically when prompt appears.",
                                  isOn: player?.streamAutoPlayNextEpisodeEnabled ?? true) {
                    PlayerSettingsRepository.shared.setStreamAutoPlayNextEpisodeEnabled(enabled: !(player?.streamAutoPlayNextEpisodeEnabled ?? true))
                }
            }
            SettingsGroupCard(title: "Audio & Video", subtitle: "Audio controls and video compatibility.") {
                SettingsActionRow(title: "Preferred Audio Language", value: Self.languageLabel(player?.preferredAudioLanguage ?? "device")) {
                    dialogs.push(.picker(PickerSpec(title: "Preferred Audio Language", options: Self.audioOptions(),
                                                    selectedId: player?.preferredAudioLanguage ?? "device") {
                        PlayerSettingsRepository.shared.setPreferredAudioLanguage(language: $0)
                    }))
                }
            }
            SettingsGroupCard(title: "Subtitles", subtitle: "Language, style, and render mode.") {
                SettingsActionRow(title: "Preferred Language", value: Self.languageLabel(player?.preferredSubtitleLanguage ?? "none")) {
                    dialogs.push(.picker(PickerSpec(title: "Preferred Language", options: Self.subtitleOptions(),
                                                    selectedId: player?.preferredSubtitleLanguage ?? "none") {
                        PlayerSettingsRepository.shared.setPreferredSubtitleLanguage(language: $0)
                    }))
                }
                let size = player?.subtitleStyle.fontSizeSp ?? 18
                SettingsActionRow(title: "Size", value: "\(size)") {
                    dialogs.push(.picker(PickerSpec(title: "Size", options: stride(from: 12, through: 40, by: 2).map { SettingsPickerOption(id: "\($0)", title: "\($0)") },
                                                    selectedId: "\(size)") { TvSettings.shared.setSubtitleFontSize(sp: Int32($0) ?? 18) }))
                }
                SettingsToggleRow(title: "Bold", subtitle: "Use bold font weight for subtitles", isOn: player?.subtitleStyle.bold ?? false) {
                    TvSettings.shared.setSubtitleBold(bold: !(player?.subtitleStyle.bold ?? false))
                }
                SettingsToggleRow(title: "Outline", subtitle: "Add outline around subtitle text for better visibility", isOn: player?.subtitleStyle.outlineEnabled ?? true) {
                    TvSettings.shared.setSubtitleOutline(enabled: !(player?.subtitleStyle.outlineEnabled ?? true))
                }
            }
        }
    }

    static func engineLabel(_ key: String) -> String {
        switch key { case "libmpv": return "libmpv"; case "avplayer": return "AVPlayer"; default: return "Auto (Best for Content)" }
    }

    static func enginePicker(current: String, onSelect: @escaping (String) -> Void = { UserDefaults.standard.set($0, forKey: "tvos.player.engine") }) -> PickerSpec {
        PickerSpec(title: "Internal Engine",
                   options: [SettingsPickerOption(id: "auto", title: engineLabel("auto")),
                             SettingsPickerOption(id: "libmpv", title: engineLabel("libmpv")),
                             SettingsPickerOption(id: "avplayer", title: engineLabel("avplayer"))],
                   selectedId: current, onSelect: onSelect)
    }

    private func autoPlayLabel(_ mode: StreamAutoPlayMode?) -> String {
        switch mode {
        case .firstStream: return "Auto-play first source"
        case .regexMatch: return "Auto-play regex match"
        default: return "Manual (choose stream)"
        }
    }

    static func languageLabel(_ code: String) -> String {
        switch code.lowercased() {
        case "device": return "Device language"
        case "default": return "Default (media file)"
        case "original": return "Original language"
        case "none", "": return "None"
        default: return Locale.current.localizedString(forLanguageCode: code)?.capitalized ?? code
        }
    }

    private static func languages() -> [SettingsPickerOption] {
        TvSettings.shared.languageCodes()
            .map { SettingsPickerOption(id: $0, title: languageLabel($0)) }
            .sorted { $0.title.localizedCompare($1.title) == .orderedAscending }
    }

    static func audioOptions() -> [SettingsPickerOption] {
        [SettingsPickerOption(id: "device", title: "Device language"),
         SettingsPickerOption(id: "default", title: "Default (media file)"),
         SettingsPickerOption(id: "original", title: "Original language", description: "Requires TMDB enrichment to be enabled")] + languages()
    }

    static func subtitleOptions() -> [SettingsPickerOption] {
        [SettingsPickerOption(id: "none", title: "None"), SettingsPickerOption(id: "device", title: "Device language")] + languages()
    }
}

// MARK: - About

/// AboutSettingsContent (AboutScreen.kt): wordmark, version, Discord and privacy links (QR hand-off —
/// Apple TV has no browser), licences. In-app updates are the App Store's job on Apple TV.
private struct AboutSettingsDetail: View {
    @EnvironmentObject private var dialogs: SettingsDialogs
    @Environment(\.nuvio) private var colors
    private var version: String { Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "–" }

    var body: some View {
        VStack(alignment: .leading, spacing: dp(14)) {
            SettingsDetailHeader(title: "About", subtitle: "App information, updates, and legal links")
            SettingsGroupCard {
                VStack(spacing: dp(10)) {
                    Image("app_logo_wordmark").resizable().scaledToFit().frame(width: dp(180), height: dp(40))
                    Text("Version \(version)").font(NuvioType.labelSmall).foregroundStyle(colors.textSecondary)
                }
                .frame(maxWidth: .infinity)
                .padding(.top, dp(4))
                SettingsActionRow(title: "Join the Discord", subtitle: "Setup help, feature ideas and release news", trailingIcon: "md_open_in_new") {
                    dialogs.push(.qr(url: "https://discord.gg/wFu9T2nS8X", instruction: "Scan with your phone to join the Tuvora Discord"))
                }
                SettingsActionRow(title: "Privacy Policy", subtitle: "View our privacy policy", trailingIcon: "md_open_in_new") {
                    dialogs.push(.qr(url: "https://tuvora.co/privacy", instruction: "No web browser on this TV. Scan with your phone to open this page"))
                }
                SettingsActionRow(title: "Licenses & Attribution", subtitle: "Data sources, acknowledgements, and licenses") {
                    dialogs.push(.licences)
                }
            }
        }
    }
}

/// LicensesAttributionsScreen, as a dialog: the app licence and the platform-neutral data credits.
private struct LicencesDialog: View {
    let close: () -> Void
    @Environment(\.nuvio) private var colors

    private let entries: [(String, String)] = [
        ("Tuvora", "Source code and license terms are available in the project repository. Licensed under the GNU General Public License v3.0."),
        ("The Movie Database (TMDB)", "Tuvora uses the TMDB API for movie and TV metadata, artwork, trailers, cast, production details, collections, and recommendations. This product uses the TMDB API but is not endorsed or certified by TMDB."),
        ("IMDb Non-Commercial Datasets", "Tuvora uses IMDb Non-Commercial Datasets, including title.ratings.tsv.gz, for IMDb ratings and vote counts. Information courtesy of IMDb (https://www.imdb.com). Used with permission. IMDb data is for personal and non-commercial use under IMDb terms."),
        ("libmpv / MPVKit", "Used as a playback engine. mpv/libmpv and bundled native components retain their upstream license terms."),
    ]

    var body: some View {
        NuvioDialog(title: "Licenses & Attribution", width: dp(640)) {
            ForEach(entries, id: \.0) { title, text in
                VStack(alignment: .leading, spacing: dp(4)) {
                    Text(title).font(NuvioType.titleSmall).foregroundStyle(colors.textPrimary)
                    Text(text).font(NuvioType.bodySmall).foregroundStyle(colors.textSecondary).fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.bottom, dp(6))
            }
            SettingsDialogButton(title: "Close", primary: true, action: close)
        }
        .frame(maxHeight: dp(460))
    }
}
