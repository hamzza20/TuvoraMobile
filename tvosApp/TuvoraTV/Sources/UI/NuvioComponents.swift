import SwiftUI
import TuvoraCore

// NuvioTV building blocks translated to SwiftUI. Every size is NuvioTV's dp ×2 (see NuvioTheme.swift).

/// Last time focus moved inside content. The shell uses it to tell "LEFT moved focus to a card on the
/// left" from "LEFT at the content's left edge", which opens the sidebar (NuvioTV's drawer behaviour).
@MainActor
enum ContentFocusActivity {
    private(set) static var lastChange = Date.distantPast
    static func touched() { lastChange = Date() }
}

extension View {
    /// Every Nuvio focusable reports focus changes, so the shell can detect the left edge.
    func reportsFocus(_ focused: Bool) -> some View {
        onChange(of: focused) { _, _ in ContentFocusActivity.touched() }
    }
}

/// HubChip (XtreamHubScreen.kt:760-821, HubChipVisualPolicy.kt): radius 12, padding 16×8, labelLarge,
/// focus scale 1.02, no border. Focused = grey Primary fill; selected = FocusBackground; idle = clear.
struct HubChip: View {
    let title: String
    var trailingIcon: String? = nil
    let selected: Bool
    let action: () -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        Button(action: action) {
            HStack(spacing: dp(4)) {
                Text(title).font(NuvioType.labelLarge).lineLimit(1)
                if let trailingIcon {
                    Image(trailingIcon).renderingMode(.template).resizable().frame(width: dp(16), height: dp(16))
                }
            }
            .foregroundStyle(focused || selected ? colors.textPrimary : colors.textSecondary)
            .padding(.horizontal, dp(16)).padding(.vertical, dp(8))
            .background(RoundedRectangle(cornerRadius: dp(12), style: .continuous)
                .fill(focused ? colors.primary : (selected ? colors.focusBackground : .clear)))
            .scaleEffect(focused ? NuvioTokens.Motion.focusScale : 1)
            .animation(NuvioTokens.Motion.fast, value: focused)
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused($focused)
        .reportsFocus(focused)
    }
}

/// ContentCard (components/ContentCard.kt): artwork with the poster radius, 2dp FocusRing and scale
/// 1.02 on focus, title (titleMedium) + release info (labelMedium) below; artwork-less fallback shows the
/// name centred in TextSecondary with a hairline Border.
struct NuvioPosterCard: View {
    let title: String
    let subtitle: String?
    let imageURL: String?
    let width: CGFloat
    let height: CGFloat
    var showLabel = NuvioLayoutPrefs.posterLabels
    var onFocus: (() -> Void)? = nil
    let action: () -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: dp(8)) {
                ZStack {
                    colors.backgroundCard
                    CachedPosterArtwork(urlString: imageURL, width: width, height: height, maximumWidth: width * 2) {
                        Text(title).font(NuvioType.titleMedium).foregroundStyle(colors.textSecondary)
                            .multilineTextAlignment(.center).lineLimit(3).padding(.horizontal, dp(12))
                            .frame(width: width, height: height)
                            .overlay(RoundedRectangle(cornerRadius: NuvioTokens.Radius.posterCard).stroke(colors.border, lineWidth: NuvioTokens.Stroke.hairline))
                    }
                }
                .frame(width: width, height: height)
                .clipShape(RoundedRectangle(cornerRadius: NuvioTokens.Radius.posterCard, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: NuvioTokens.Radius.posterCard, style: .continuous)
                    .stroke(focused ? colors.focusRing : .clear, lineWidth: NuvioTokens.Stroke.focus))
                .hoverEffect(.highlight)   // tvOS-native focus: lift, parallax and shadow under the Tuvora ring
                if showLabel {
                    VStack(alignment: .leading, spacing: dp(2)) {
                        Text(title).font(NuvioType.titleMedium).foregroundStyle(colors.textPrimary).lineLimit(1)
                        if let subtitle { Text(subtitle).font(NuvioType.labelMedium).foregroundStyle(colors.textSecondary).lineLimit(1) }
                    }
                    .frame(width: width, alignment: .leading)
                }
            }
            .animation(NuvioTokens.Motion.fast, value: focused)
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused($focused)
        .reportsFocus(focused)
        .onChange(of: focused) { _, isFocused in if isFocused { onFocus?() } }
    }
}

/// PlaceholderShimmer.kt: white 7% / 13% / 7% sweep, 1600 ms linear, in the card's shape.
struct NuvioShimmer: View {
    var cornerRadius: CGFloat = NuvioTokens.Radius.posterCard
    @State private var phase: CGFloat = -1

    var body: some View {
        GeometryReader { geo in
            RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                .fill(Color.white.opacity(0.07))
                .overlay(
                    LinearGradient(colors: [.white.opacity(0.07), .white.opacity(0.13), .white.opacity(0.07)],
                                   startPoint: .leading, endPoint: .trailing)
                        .frame(width: geo.size.width)
                        .offset(x: phase * geo.size.width)
                        .clipShape(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
                )
        }
        .onAppear { withAnimation(.linear(duration: 1.6).repeatForever(autoreverses: false)) { phase = 1 } }
    }
}

/// Modern shelf header (ModernHomeRows.kt:534-547): titleMedium SemiBold, TextPrimary, 14dp below.
struct NuvioShelfHeader<Trailing: View>: View {
    let title: String
    @ViewBuilder var trailing: Trailing
    @Environment(\.nuvio) private var colors

    var body: some View {
        HStack(spacing: dp(12)) {
            Text(title).font(NuvioType.titleMediumSemi).foregroundStyle(colors.textPrimary).lineLimit(1)
            trailing
            Spacer()
        }
        .padding(.leading, NuvioTokens.Layout.gutter)
        .padding(.bottom, NuvioTokens.Layout.headerBottom)
    }
}

/// "See all" beside a hub shelf header: BackgroundCard / TextSecondary, radius 8, padding 12×4;
/// focused FocusBackground with Primary text.
struct SeeAllButton: View {
    let action: () -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        Button(action: action) {
            Text("See all").font(NuvioType.labelMedium)
                .foregroundStyle(focused ? colors.primary : colors.textSecondary)
                .padding(.horizontal, dp(12)).padding(.vertical, dp(4))
                .background(RoundedRectangle(cornerRadius: dp(8)).fill(focused ? colors.focusBackground : colors.backgroundCard))
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused($focused)
        .reportsFocus(focused)
    }
}

/// ErrorState / EmptyScreenState (shared TV components): centred title + message, optional retry.
struct NuvioStateMessage: View {
    let title: String
    let message: String
    var retry: (() -> Void)? = nil
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        VStack(spacing: dp(12)) {
            Text(title).font(NuvioType.titleLarge).foregroundStyle(colors.textPrimary)
            Text(message).font(NuvioType.bodyMedium).foregroundStyle(colors.textSecondary)
                .multilineTextAlignment(.center).frame(maxWidth: dp(420))
            if let retry {
                Button(action: retry) {
                    Text("Retry").font(NuvioType.labelLargeSemi)
                        .foregroundStyle(focused ? colors.onSecondary : colors.textPrimary)
                        .padding(.horizontal, dp(24)).padding(.vertical, dp(10))
                        .background(Capsule().fill(focused ? colors.secondary : colors.backgroundCard))
                }
                .buttonStyle(PlainNoChromeButtonStyle())
                .focused($focused)
        .reportsFocus(focused)
                .padding(.top, dp(8))
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

/// Apple TV layout preferences (Settings → Layout). Device-local like NuvioTV's LayoutPreferenceDataStore;
/// SwiftUI screens read them with `@AppStorage(key)`, plain code through these accessors.
enum NuvioLayoutPrefs {
    static let posterWidthKey = "tvos.layout.posterWidthDp"
    static let posterLabelsKey = "tvos.layout.posterLabels"
    static let showHeroKey = "tvos.layout.showHero"
    static let cwEnabledKey = "tvos.layout.cwEnabled"
    static let cwStyleKey = "tvos.layout.cwStyle"
    static let collapseSidebarKey = "tvos.layout.collapseSidebar"

    static var posterLabels: Bool { UserDefaults.standard.object(forKey: posterLabelsKey) as? Bool ?? true }
}

/// Poster size preference (LayoutPreferenceDataStore `poster_card_width_dp`, default 126; height ×1.5).
enum NuvioCardSize {
    static var posterWidthDp: CGFloat {
        let stored = UserDefaults.standard.double(forKey: NuvioLayoutPrefs.posterWidthKey)
        return stored > 0 ? CGFloat(stored) : 126
    }
    /// Hub portrait card: pref × 0.9072 (XtreamHubScreen.kt:824-829).
    static var hubPortrait: CGSize { CGSize(width: dp(posterWidthDp * 0.9072), height: dp(posterWidthDp * 0.9072 * 1.5)) }
    /// Hub live tile: 1.6616 × pref wide at 1.77:1.
    static var hubLandscape: CGSize { let w = dp(posterWidthDp * 1.6616); return CGSize(width: w, height: w / 1.77) }
}


/// The IMDb badge NuvioTV draws beside ratings: yellow (#F5C518) rounded tag with bold black "IMDb".
struct ImdbBadge: View {
    var body: some View {
        Text("IMDb").font(NuvioType.inter(10, .heavy)).foregroundStyle(.black)
            .padding(.horizontal, dp(4)).padding(.vertical, dp(1))
            .background(RoundedRectangle(cornerRadius: dp(3)).fill(NuvioPrimitives.imdb))
    }
}
