import SwiftUI

/// NuvioDialog (components/NuvioDialog.kt): 520dp wide, BackgroundElevated, radius 16, 1dp Border,
/// padding 24, 16dp spacing, titleLarge title.
struct NuvioDialog<Content: View>: View {
    let title: String
    var subtitle: String? = nil
    var width: CGFloat = dp(520)
    @ViewBuilder var content: Content
    @Environment(\.nuvio) private var colors

    var body: some View {
        VStack(alignment: .leading, spacing: dp(16)) {
            Text(title).font(NuvioType.titleLarge).foregroundStyle(colors.textPrimary)
            if let subtitle { Text(subtitle).font(NuvioType.bodyMedium).foregroundStyle(colors.textSecondary) }
            // Hug short content; scroll only when it outgrows the space the dialog is given.
            ViewThatFits(in: .vertical) {
                VStack(spacing: dp(8)) { content }
                ScrollView { VStack(spacing: dp(8)) { content }.padding(.vertical, dp(4)) }
            }
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
    /** Material icon asset drawn 24dp before the title (NuvioTV `leadingIcon`). */
    var leadingIcon: String? = nil
    /** NuvioTV `trailingIcon` (chevron by default, open-in-new for links). */
    var trailingIcon = "md_chevron_right"
    /** Disabled rows dim to 40% and can't take focus, as NuvioTV's `enabled = false`. */
    var enabled = true
    let action: () -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        Button(action: action) {
            HStack(spacing: dp(12)) {
                if let leadingIcon {
                    Image(leadingIcon).renderingMode(.template).resizable().scaledToFit()
                        .frame(width: dp(24), height: dp(24)).foregroundStyle(colors.textPrimary)
                        .padding(.trailing, dp(4))
                }
                VStack(alignment: .leading, spacing: dp(2)) {
                    Text(title).font(NuvioType.bodyLarge).foregroundStyle(colors.textPrimary).lineLimit(1)
                    if let subtitle, !subtitle.isEmpty { Text(subtitle).font(NuvioType.bodySmall).foregroundStyle(colors.textSecondary).lineLimit(3) }
                }
                Spacer()
                if let value { Text(value).font(NuvioType.labelLarge).foregroundStyle(colors.textSecondary) }
                if showChevron {
                    Image(trailingIcon).renderingMode(.template).resizable().frame(width: dp(18), height: dp(18)).foregroundStyle(colors.textTertiary)
                }
            }
            .padding(.horizontal, dp(18)).padding(.vertical, dp(12))
            .frame(minHeight: dp(62))
            .background(Capsule().fill(colors.background))
            .overlay(Capsule().stroke(focused ? colors.focusRing : .clear, lineWidth: NuvioTokens.Stroke.focus))
            .opacity(enabled ? 1 : 0.4)
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .disabled(!enabled)
        .focused($focused)
        .reportsFocus(focused)
    }
}
