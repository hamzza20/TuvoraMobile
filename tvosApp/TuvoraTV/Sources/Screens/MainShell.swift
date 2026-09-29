import SwiftUI
import TuvoraCore

/// NuvioTV's legacy sidebar shell (MainActivity.kt:1414-1812), translated: a 72dp icon rail that opens
/// into a 196dp labelled drawer when it takes focus (LEFT from content), dimming the content; RIGHT or
/// a selection closes it. Content sits 54dp in from the left edge.
struct MainShell: View {
    enum Destination: String, CaseIterable, Identifiable {
        case home, search, library, iptv, sports, settings
        var id: String { rawValue }
        var title: String {
            switch self {
            case .home: return "Home"
            case .search: return "Search"
            case .library: return "Library"
            case .iptv: return "IPTV"
            case .sports: return "Sports"
            case .settings: return "Settings"
            }
        }
        /// Same icons as NuvioTV: Material Home/LiveTv/SportsSoccer, its own sidebar SVGs for the rest.
        var icon: String {
            switch self {
            case .home: return "md_home"
            case .search: return "sidebar_search"
            case .library: return "sidebar_library"
            case .iptv: return "md_live_tv"
            case .sports: return "md_sports_soccer"
            case .settings: return "sidebar_settings"
            }
        }
    }

    @StateObject private var playback = PlaybackCoordinator()
    @StateObject private var theme = NuvioThemeModel()
    /// Simulator smoke hook: `-smokeTab <home|search|library|iptv|sports|settings>`.
    @State private var destination: Destination = {
        let args = ProcessInfo.processInfo.arguments
        if let i = args.firstIndex(of: "-smokeTab"), i + 1 < args.count, let d = Destination(rawValue: args[i + 1]) { return d }
        return .home
    }()
    @FocusState private var railFocus: Destination?
    @State private var profile: NuvioProfile?

    /// The drawer is open only when the viewer brought focus there (LEFT at the edge, or Menu). Collapsed
    /// items cannot take focus, exactly as NuvioTV's `canFocus = expanded`, so launch focus lands in content.
    @State private var railEngaged = false
    private var expanded: Bool { railEngaged && railFocus != nil }

    private func openRail() {
        railEngaged = true
        DispatchQueue.main.async { railFocus = destination }
    }

    var body: some View {
        let colors = theme.colors
        ZStack(alignment: .leading) {
            colors.background.ignoresSafeArea()

            content
                .padding(.leading, dp(80))
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                .overlay(Color.black.opacity(expanded ? 0.55 : 0).allowsHitTesting(false).ignoresSafeArea())
                .animation(NuvioTokens.Motion.medium, value: expanded)
                .onExitCommand { openRail() }
                .onMoveCommand { direction in
                    guard direction == .left, !railEngaged else { return }
                    let pressed = Date()
                    // Focus didn't move within a beat: this LEFT hit the content's left edge.
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) {
                        if ContentFocusActivity.lastChange < pressed { openRail() }
                    }
                }

            Sidebar(destination: $destination, focus: $railFocus, expanded: expanded, engaged: railEngaged, profile: profile)
                .onChange(of: railFocus) { _, f in if f == nil { railEngaged = false } }
        }
        .environment(\.nuvio, colors)
        .environmentObject(playback)
        .ignoresSafeArea()
        .overlay(alignment: .bottom) { toast(colors) }
        .fullScreenCover(item: $playback.session) { box in
            TvPlayerScreen(session: box.session) { playback.stop() }.environment(\.nuvio, colors)
        }
        .task { await theme.observe() }
        .task { for await state in ProfileRepository.shared.state { profile = state.activeProfile } }
    }

    @ViewBuilder
    private var content: some View {
        switch destination {
        case .home: HomeScreen()
        case .search: SearchScreen()
        case .library: LibraryScreen()
        case .iptv: IptvHubScreen()
        case .sports: SportsScreen()
        case .settings: SettingsScreen()
        }
    }

    @ViewBuilder
    private func toast(_ colors: NuvioPalette) -> some View {
        if let message = playback.message {
            Text(message)
                .font(NuvioType.bodyMedium)
                .foregroundStyle(colors.textPrimary)
                .padding(.horizontal, dp(20)).padding(.vertical, dp(10))
                .background(colors.backgroundElevated, in: RoundedRectangle(cornerRadius: NuvioTokens.Radius.dialog))
                .overlay(RoundedRectangle(cornerRadius: NuvioTokens.Radius.dialog).stroke(colors.border, lineWidth: NuvioTokens.Stroke.hairline))
                .padding(.bottom, dp(32))
                .transition(.opacity)
        }
    }
}

private struct Sidebar: View {
    @Binding var destination: MainShell.Destination
    var focus: FocusState<MainShell.Destination?>.Binding
    let expanded: Bool
    let engaged: Bool
    let profile: NuvioProfile?
    @Environment(\.nuvio) private var colors

    /// NuvioTV's Modern floating sidebar (ModernSidebarBlurPanel.kt), drawn in Liquid Glass: a rounded
    /// (30dp) panel inset from the edge, icons only until focus arrives, then labels.
    var body: some View {
        VStack(alignment: .leading, spacing: dp(10)) {
            if expanded {
                header.padding(.bottom, dp(12)).transition(.opacity)
            }
            ForEach(MainShell.Destination.allCases) { item in
                SidebarItem(item: item, selected: destination == item, expanded: expanded, focused: focus.wrappedValue == item) {
                    destination = item
                    focus.wrappedValue = nil
                }
                .focused(focus, equals: item)
                .disabled(!engaged)
            }
        }
        .padding(dp(10))
        .navigationGlass(in: RoundedRectangle(cornerRadius: dp(30), style: .continuous))
        .padding(.leading, dp(12))
        .frame(maxHeight: .infinity)
        .focusSection()
        .defaultFocus(focus, destination, priority: .userInitiated)
        .animation(NuvioTokens.Motion.fast, value: expanded)
        .onMoveCommand { direction in
            if direction == .right { focus.wrappedValue = nil }
        }
    }

    @ViewBuilder
    private var header: some View {
        if let profile {
            HStack(spacing: dp(10)) {
                ProfileAvatar(profile: profile, size: dp(30))
                Text(profile.name).font(NuvioType.titleMedium).foregroundStyle(colors.textPrimary).lineLimit(1)
            }
            .padding(.leading, dp(6))
        } else {
            Image("app_logo_wordmark").resizable().scaledToFit().frame(height: dp(36))
        }
    }
}

private struct SidebarItem: View {
    let item: MainShell.Destination
    let selected: Bool
    let expanded: Bool
    let focused: Bool
    let action: () -> Void
    @Environment(\.nuvio) private var colors

    var body: some View {
        Button(action: action) {
            HStack(spacing: dp(14)) {
                Image(item.icon).renderingMode(.template).resizable().scaledToFit()
                    .frame(width: dp(22), height: dp(22))
                    .foregroundStyle(selected ? colors.secondary : colors.textPrimary.opacity(focused ? 1 : 0.8))
                if expanded {
                    Text(item.title).font(NuvioType.titleMedium).foregroundStyle(colors.textPrimary).lineLimit(1)
                    Spacer(minLength: 0)
                }
            }
            .padding(.horizontal, dp(13))
            .frame(width: expanded ? dp(190) : dp(48), height: dp(48), alignment: .leading)
            .background(Capsule().fill(fill))
            .scaleEffect(focused ? 1.05 : 1)
            .animation(NuvioTokens.Motion.fast, value: focused)
        }
        .buttonStyle(PlainNoChromeButtonStyle())
    }

    /// Modern sidebar item fills: focused+selected accent 28%, focused white 12%, selected accent 15%.
    private var fill: Color {
        if focused && selected { return colors.secondary.opacity(0.28) }
        if focused { return Color.white.opacity(0.12) }
        if selected && expanded { return colors.secondary.opacity(0.15) }
        return .clear
    }
}

/// Buttons draw their own NuvioTV focus treatment; this removes tvOS's default lift/highlight.
struct PlainNoChromeButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.scaleEffect(configuration.isPressed ? 0.98 : 1)
    }
}

struct ProfileAvatar: View {
    let profile: NuvioProfile
    let size: CGFloat

    var body: some View {
        ZStack {
            Circle().fill(Color(hex: profile.avatarColorHex))
            if let url = profile.avatarUrl, !url.isEmpty {
                CachedPosterArtwork(urlString: url, width: size, height: size, maximumWidth: size * 2) { Color.clear }
                    .clipShape(Circle())
            } else {
                Text(String(profile.name.prefix(1)).uppercased())
                    .font(NuvioType.inter(size / 2 / 2, .semibold))
                    .foregroundStyle(.white)
            }
        }
        .frame(width: size, height: size)
    }
}

extension Color {
    /// "#RRGGBB" / "#AARRGGBB", falling back to NuvioTV's default avatar blue.
    init(hex: String) {
        let cleaned = hex.trimmingCharacters(in: CharacterSet(charactersIn: "#"))
        let value = UInt32(cleaned, radix: 16) ?? 0x1E88E5
        self.init(argb: cleaned.count == 6 ? 0xFF000000 | value : (cleaned.count == 8 ? value : 0xFF1E88E5))
    }
}

struct PlaceholderScreen: View {
    let title: String
    @Environment(\.nuvio) private var colors
    var body: some View {
        Text(title).font(NuvioType.headlineMedium).foregroundStyle(colors.textSecondary)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
