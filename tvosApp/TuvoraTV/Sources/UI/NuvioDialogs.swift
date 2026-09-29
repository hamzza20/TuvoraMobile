import SwiftUI

/// NuvioDialog (components/NuvioDialog.kt): 520dp wide, BackgroundElevated, radius 16, 1dp Border,
/// padding 24, 16dp spacing, titleLarge title.
struct NuvioDialog<Content: View>: View {
    let title: String
    var subtitle: String? = nil
    /// NuvioDialog's `width` parameter (default 520dp).
    var width: CGFloat = dp(520)
    @ViewBuilder var content: Content
    @Environment(\.nuvio) private var colors

    var body: some View {
        VStack(alignment: .leading, spacing: dp(16)) {
            Text(title).font(NuvioType.titleLarge).foregroundStyle(colors.textPrimary)
            if let subtitle { Text(subtitle).font(NuvioType.bodyMedium).foregroundStyle(colors.textSecondary) }
            ScrollView { VStack(spacing: dp(8)) { content } }
        }
        .padding(dp(24))
        .frame(width: width)
        .background(RoundedRectangle(cornerRadius: NuvioTokens.Radius.dialog).fill(colors.backgroundElevated))
        .overlay(RoundedRectangle(cornerRadius: NuvioTokens.Radius.dialog).stroke(colors.border, lineWidth: NuvioTokens.Stroke.hairline))
    }
}

/// SettingsActionRow (SettingsDesignSystem.kt:780-910, CLASSIC): pill on Background, min height 62,
/// padding 18×12, bodyLarge title, bodySmall subtitle, labelLarge value, 18dp chevron; 2dp FocusRing.
struct SettingsActionRow: View {
    let title: String
    var subtitle: String? = nil
    var value: String? = nil
    var showChevron = true
    let action: () -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        Button(action: action) {
            HStack(spacing: dp(12)) {
                VStack(alignment: .leading, spacing: dp(2)) {
                    Text(title).font(NuvioType.bodyLarge).foregroundStyle(colors.textPrimary).lineLimit(1)
                    if let subtitle { Text(subtitle).font(NuvioType.bodySmall).foregroundStyle(colors.textSecondary).lineLimit(2) }
                }
                Spacer()
                if let value { Text(value).font(NuvioType.labelLarge).foregroundStyle(colors.textSecondary) }
                if showChevron {
                    Image("md_chevron_right").renderingMode(.template).resizable().frame(width: dp(18), height: dp(18)).foregroundStyle(colors.textTertiary)
                }
            }
            .padding(.horizontal, dp(18)).padding(.vertical, dp(12))
            .frame(minHeight: dp(62))
            .background(Capsule().fill(colors.background))
            .overlay(Capsule().stroke(focused ? colors.focusRing : .clear, lineWidth: NuvioTokens.Stroke.focus))
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused($focused)
        .reportsFocus(focused)
    }
}
