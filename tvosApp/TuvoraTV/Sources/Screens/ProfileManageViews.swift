import SwiftUI
import TuvoraCore

// NuvioTV profile management (ui/screens/profile/ProfileSelectionScreen.kt): AddProfileCard, the
// Profile Options dialog, CreateProfileOverlay / EditProfileOverlay with the avatar picker
// (components/AvatarPickerGrid.kt), and the delete confirmation. Saves go through the shared
// ProfileRepository exactly as the phone's ProfileEditScreen does (createProfile / updateProfile, which
// apply locally for a signed-out or anonymous session instead of dropping the edit), and a failed save
// keeps the editor open with the phone's message rather than closing as if it had worked.
//
// Not ported: "Copy settings from another profile" — NuvioTV copies through the `sync_copy_profile_setup`
// RPC, which exists only on Nuvio's cloud (not in nuvio-backend or NuvioMedia/self-host) and has no
// shared-code path; and the member-only profile background tab (the inert membership subsystem).
// Colour: like NuvioTV and the phone, a profile's colour comes from the
// chosen avatar's background colour; neither offers a separate colour picker.

/// AddProfileCard (ProfileSelectionScreen.kt:1253-1381): a ringed disc with a drawn plus, "Add Profile".
struct AddProfileCard: View {
    let compact: Bool
    let focused: Bool
    let action: () -> Void
    @Environment(\.nuvio) private var colors

    var body: some View {
        let ring = compact ? (focused ? dp(104) : dp(98)) : (focused ? dp(122) : dp(114))
        let plus = compact ? dp(22) : dp(26)
        Button(action: action) {
            VStack(spacing: 0) {
                ZStack {
                    Circle().fill(Color.white.opacity(focused ? 0.12 : 0.06))
                    Circle().stroke(colors.border.opacity(0.5), lineWidth: dp(1))
                    Circle().stroke(colors.focusRing.opacity(focused ? 1 : 0), lineWidth: focused ? dp(3) : dp(1))
                    Capsule().fill(focused ? Color.white : colors.textTertiary).frame(width: plus, height: dp(3))
                    Capsule().fill(focused ? Color.white : colors.textTertiary).frame(width: dp(3), height: plus)
                }
                .frame(width: ring, height: ring)
                .frame(width: compact ? PickerMetrics.compactAvatarContainer : PickerMetrics.avatarContainer,
                       height: compact ? PickerMetrics.compactAvatarContainer : PickerMetrics.avatarContainer)
                Spacer().frame(height: compact ? PickerMetrics.compactAvatarToName : PickerMetrics.avatarToName)
                Text("Add Profile")
                    .font(NuvioType.inter(compact ? 15 : 17, focused ? .semibold : .medium))
                    .foregroundStyle(focused ? colors.textPrimary : colors.textTertiary)
                    .lineLimit(1)
                Spacer().frame(height: PickerMetrics.nameToMeta + PickerMetrics.metaSlot * 1.6)
            }
            .padding(.horizontal, PickerMetrics.cardPadH)
            .padding(.vertical, PickerMetrics.cardPadV)
            .frame(width: compact ? PickerMetrics.compactCardWidth : PickerMetrics.cardWidth)
            .scaleEffect(focused ? 1.04 : 1)
            .animation(.timingCurve(0.2, 0, 0, 1, duration: 0.21), value: focused)
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .reportsFocus(focused)
    }
}

/// The dimmed layer every management overlay sits on (NuvioTV: black 85%).
private struct OverlayScrim<Content: View>: View {
    var opacity = 0.85
    let onDismiss: () -> Void
    @ViewBuilder var content: Content

    var body: some View {
        ZStack {
            Color.black.opacity(opacity).ignoresSafeArea()
            content
        }
        .onExitCommand(perform: onDismiss)
    }
}

/// "Profile Options" (NuvioDialog 360dp): Edit, Set / Change PIN, Remove PIN, Delete.
struct ProfileOptionsDialog: View {
    let profile: NuvioProfile
    let onChoose: (TvProfileOption) -> Void
    let onDismiss: () -> Void

    var body: some View {
        OverlayScrim(opacity: 0.6, onDismiss: onDismiss) {
            NuvioDialog(title: "Profile Options", subtitle: profile.name, width: dp(360)) {
                ForEach(TvProfileManagePolicy.shared.options(profileIndex: profile.profileIndex, pinEnabled: profile.pinEnabled), id: \.self) { option in
                    SettingsDialogButton(title: TvProfileManagePolicy.shared.optionTitle(option: option),
                                         destructive: option == .delete, fullWidth: true) { onChoose(option) }
                }
            }
            .focusSection()
        }
    }
}

/// Delete confirmation (NuvioDialog 420dp) with NuvioTV's copy.
struct ProfileDeleteDialog: View {
    let profile: NuvioProfile
    let onDismiss: () -> Void
    let onDelete: () -> Void

    var body: some View {
        OverlayScrim(opacity: 0.6, onDismiss: onDismiss) {
            NuvioDialog(title: "Delete Profile?",
                        subtitle: "This will permanently delete this profile and all its data including library, watch history, and addon settings. This cannot be undone.",
                        width: dp(420)) {
                SettingsDialogButton(title: "Delete Profile", destructive: true, fullWidth: true, action: onDelete)
                // tvOS never rests focus on an irreversible action: Cancel is the default.
                SettingsDialogButton(title: "Cancel", fullWidth: true, initialFocus: true, action: onDismiss)
            }
            .focusSection()
        }
    }
}

/// CreateProfileOverlay / EditProfileOverlay: panel (BackgroundElevated, radius 20, ≤980dp) with the
/// title and Create / Save on top, the preview + name field on the left, and the avatar picker right.
struct ProfileEditorOverlay: View {
    /// nil creates a new profile.
    let profile: NuvioProfile?
    let avatars: [AvatarCatalogItem]
    let onDismiss: () -> Void
    let onSaved: () -> Void
    @Environment(\.nuvio) private var colors
    @State private var name: String
    @State private var colorHex: String
    @State private var avatarId: String?
    @State private var saving = false
    @State private var failure: String?
    @State private var focusedAvatarName: String?
    @State private var category = "all"
    @FocusState private var nameFocused: Bool

    private var policy: TvProfileManagePolicy { TvProfileManagePolicy.shared }

    init(profile: NuvioProfile?, avatars: [AvatarCatalogItem], onDismiss: @escaping () -> Void, onSaved: @escaping () -> Void) {
        self.profile = profile
        self.avatars = avatars
        self.onDismiss = onDismiss
        self.onSaved = onSaved
        _name = State(initialValue: profile?.name ?? "")
        _colorHex = State(initialValue: profile?.avatarColorHex ?? TvProfileManagePolicy.shared.DEFAULT_COLOR_HEX)
        _avatarId = State(initialValue: profile?.avatarId)
    }

    private var isNew: Bool { profile == nil }
    private var avatarChanged: Bool { avatarId != profile?.avatarId }

    /// NuvioTV previewAvatarImageUrl: the picked avatar, else (unchanged) the profile's own image.
    private var previewURL: String? {
        if let avatarId, let item = avatars.first(where: { $0.id == avatarId }) { return ProfileModelsKt.avatarImageUrl(avatar: item) }
        if !avatarChanged, let profile { return ProfileModelsKt.profileAvatarImageUrl(profile: profile, avatar: nil) }
        return nil
    }

    var body: some View {
        OverlayScrim(onDismiss: onDismiss) {
            VStack(spacing: dp(20)) {
                header
                HStack(alignment: .top, spacing: dp(18)) {
                    preview.frame(width: dp(280))
                    Rectangle().fill(colors.border).frame(width: dp(1), height: dp(300))
                    avatarPane.frame(maxWidth: .infinity)
                }
            }
            .padding(dp(28))
            .frame(maxWidth: dp(980))
            .background(RoundedRectangle(cornerRadius: dp(20), style: .continuous).fill(colors.backgroundElevated))
            .overlay(RoundedRectangle(cornerRadius: dp(20), style: .continuous).stroke(colors.border, lineWidth: dp(1)))
            .padding(.horizontal, dp(40))
        }
        .onAppear { if isNew { DispatchQueue.main.async { nameFocused = true } } }
    }

    private var header: some View {
        HStack(alignment: .center) {
            if isNew {
                Text("Create Profile").font(NuvioType.inter(26, .bold)).foregroundStyle(.white)
            } else {
                VStack(alignment: .leading, spacing: dp(2)) {
                    Text("Edit Profile").font(NuvioType.inter(16, .semibold)).foregroundStyle(colors.textSecondary)
                    Text(profile?.name ?? "").font(NuvioType.inter(30, .black)).foregroundStyle(.white).lineLimit(1)
                }
            }
            Spacer()
            if let failure {
                Text(failure).font(NuvioType.bodySmall).foregroundStyle(Color(argb: 0xFFFF8E8E))
                    .multilineTextAlignment(.trailing).frame(maxWidth: dp(300), alignment: .trailing)
            }
            HStack(spacing: dp(12)) {
                OverlayButton(title: "Cancel", primary: false, enabled: !saving, action: onDismiss)
                OverlayButton(title: saving ? (isNew ? "Creating…" : "Saving…") : (isNew ? "Create" : "Save"),
                              primary: true, enabled: policy.canSave(name: name, isSaving: saving), action: save)
            }
        }
        .padding(.leading, dp(24))
        .focusSection()
    }

    private var preview: some View {
        VStack(spacing: dp(16)) {
            ProfileAvatarCircle(name: name.isEmpty ? "?" : name, colorHex: colorHex, imageURL: previewURL, size: dp(112))
            Text(name.isEmpty ? "Profile name" : name)
                .font(NuvioType.inter(22, .bold))
                .foregroundStyle(name.isEmpty ? colors.textSecondary : colors.textPrimary)
                .multilineTextAlignment(.center).lineLimit(2)
            // Native tvOS text entry (DESIGN-PARITY §2): the system keyboard, NuvioTV's placeholder.
            TextField("Profile name", text: $name)
                .font(NuvioType.bodyLarge)
                .autocorrectionDisabled()
                .focused($nameFocused)
                .reportsFocus(nameFocused)
                .onChange(of: name) { _, new in
                    let clamped = policy.clampName(name: new)
                    if clamped != new { name = clamped }
                }
        }
        .padding(.top, dp(12))
        .focusSection()
    }

    private var avatarPane: some View {
        VStack(spacing: dp(10)) {
            Text("Choose Avatar").font(NuvioType.inter(13, .medium)).foregroundStyle(colors.textSecondary)
            Text("Custom avatar URLs can be configured from the Tuvora web panel.")
                .font(NuvioType.inter(12, .medium)).foregroundStyle(colors.textTertiary)
            if avatars.isEmpty {
                Text("Choose Avatar").font(NuvioType.inter(15, .regular)).foregroundStyle(colors.textTertiary)
                    .frame(maxWidth: .infinity).frame(height: dp(160))
                    .background(RoundedRectangle(cornerRadius: dp(18)).fill(colors.backgroundCard))
                    .overlay(RoundedRectangle(cornerRadius: dp(18)).stroke(colors.border, lineWidth: dp(1)))
            } else {
                categoryTabs
                ScrollView(.vertical, showsIndicators: false) {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: dp(88)), spacing: dp(12))], spacing: dp(12)) {
                        ForEach(avatars.filter { policy.inCategory(avatarCategory: $0.category, selected: category) }, id: \.id) { item in
                            AvatarGridItem(item: item, selected: item.id == avatarId,
                                           onFocus: { focusedAvatarName = item.displayName }) { pick(item) }
                        }
                    }
                    // Room inside the clip for the 1.1× focus scale and the selection ring.
                    .padding(.horizontal, dp(8)).padding(.vertical, dp(8))
                }
                .frame(height: dp(200))
                .focusSection()
                Text(focusedAvatarName ?? "Focus an avatar to view its name")
                    .font(NuvioType.inter(18, .bold))
                    .foregroundStyle(focusedAvatarName != nil ? colors.textPrimary : colors.textTertiary)
                    .lineLimit(1)
            }
        }
    }

    private var categoryTabs: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: dp(8)) {
                ForEach(policy.avatarCategories(avatarCategories: avatars.map(\.category)), id: \.self) { key in
                    CategoryTab(label: policy.categoryLabel(category: key), selected: key == category) { category = key }
                }
            }
            .padding(.horizontal, dp(8)).padding(.vertical, dp(4))
        }
        .focusSection()
    }

    private func pick(_ item: AvatarCatalogItem) {
        let fallback = profile?.avatarColorHex ?? policy.DEFAULT_COLOR_HEX
        let result = policy.toggleAvatar(selectedId: avatarId, tappedId: item.id, tappedBgColor: item.bgColor,
                                         currentColorHex: colorHex, fallbackColorHex: fallback)
        avatarId = result.first as String?
        colorHex = (result.second as String?) ?? fallback
    }

    private func save() {
        guard policy.canSave(name: name, isSaving: saving) else { return }
        saving = true
        failure = nil
        Task {
            let saved: Bool
            if let profile {
                saved = (try? await ProfileRepository.shared.updateProfile(
                    profileIndex: profile.profileIndex, name: name, avatarColorHex: colorHex, avatarId: avatarId,
                    // A new catalog pick replaces a custom URL; otherwise the profile keeps its own.
                    avatarUrl: avatarChanged ? nil : profile.avatarUrl,
                    // Carry what this editor doesn't show, so saving never clears it.
                    profileBackgroundId: profile.profileBackgroundId, profileBackgroundUrl: profile.profileBackgroundUrl,
                    usesPrimaryAddons: profile.usesPrimaryAddons))?.boolValue ?? false
            } else {
                saved = (try? await ProfileRepository.shared.createProfile(
                    name: name, avatarColorHex: colorHex, avatarId: avatarId, avatarUrl: nil, usesPrimaryAddons: false))?.boolValue ?? false
            }
            saving = false
            NSLog("SMOKE profile save new=%d ok=%d", isNew, saved)
            if saved {
                onSaved()
            } else {
                failure = isNew ? "Could not create profile. Try again." : "Couldn't save your changes. Check your connection and try again."
            }
        }
    }
}

/// OverlayButton (ProfileSelectionScreen.kt:2547-2634): radius 12, min height 48, 15sp SemiBold;
/// primary = Secondary (focused SecondaryVariant), secondary = white 6% (focused FocusBackground);
/// focused 2dp FocusRing.
private struct OverlayButton: View {
    let title: String
    let primary: Bool
    var enabled = true
    let action: () -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: dp(12), style: .continuous)
        Button(action: action) {
            Text(ui: title).font(NuvioType.inter(15, .semibold)).lineLimit(1)
                .foregroundStyle(textColor)
                .padding(.horizontal, dp(28)).padding(.vertical, dp(12))
                .frame(minHeight: dp(48))
                .background(shape.fill(fill))
                .overlay(shape.stroke(stroke, lineWidth: focused ? dp(2) : dp(1)))
                .animation(.easeInOut(duration: 0.12), value: focused)
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused($focused)
        .disabled(!enabled)
        .reportsFocus(focused)
    }

    private var fill: Color {
        if !enabled { return Color.white.opacity(0.04) }
        if primary { return focused ? colors.secondaryVariant : colors.secondary }
        return focused ? colors.focusBackground : Color.white.opacity(0.06)
    }
    private var stroke: Color {
        if !enabled { return colors.border }
        if focused { return colors.focusRing }
        return primary ? colors.secondary : colors.border
    }
    private var textColor: Color {
        if !enabled { return colors.textSecondary }
        if primary { return colors.onSecondary }
        return colors.textPrimary
    }
}

/// AvatarPickerGrid CategoryTab: radius 20 pill, 13sp; selected Secondary 22% + Secondary hairline.
private struct CategoryTab: View {
    let label: String
    let selected: Bool
    let action: () -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: dp(20), style: .continuous)
        Button(action: action) {
            Text(ui: label).font(NuvioType.inter(13, selected ? .semibold : .medium))
                .foregroundStyle(selected || focused ? Color.white : colors.textSecondary)
                .padding(.horizontal, dp(18)).padding(.vertical, dp(8))
                .background(shape.fill(focused ? colors.focusBackground : selected ? colors.secondary.opacity(0.22) : Color.white.opacity(0.06)))
                .overlay(shape.stroke(focused ? colors.focusRing : selected ? colors.secondary : colors.border, lineWidth: focused ? dp(2) : dp(1)))
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused($focused)
        .reportsFocus(focused)
    }
}

/// AvatarGridItem: an 80dp disc on the avatar's colour, scale 1.1 on focus, 3dp ring when selected.
private struct AvatarGridItem: View {
    let item: AvatarCatalogItem
    let selected: Bool
    let onFocus: () -> Void
    let action: () -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        Button(action: action) {
            ProfileAvatarCircle(name: item.displayName, colorHex: item.bgColor ?? TvProfileManagePolicy.shared.DEFAULT_COLOR_HEX,
                                imageURL: ProfileModelsKt.avatarImageUrl(avatar: item), size: dp(80))
                .overlay(Circle().stroke(selected || focused ? colors.focusRing : .clear, lineWidth: selected ? dp(3) : dp(2)))
                .scaleEffect(focused ? 1.1 : 1)
                .animation(.easeInOut(duration: 0.15), value: focused)
                .frame(width: dp(88), height: dp(88))
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused($focused)
        .reportsFocus(focused)
        .onChange(of: focused) { _, f in if f { onFocus() } }
    }
}

/// The action toast (ProfileSelectionScreen.kt:604-633): black 78% pill, white 14% hairline, 15sp.
struct ProfileToast: View {
    let text: String
    var body: some View {
        Text(ui: text).font(NuvioType.inter(15, .medium)).foregroundStyle(.white)
            .padding(.horizontal, dp(24)).padding(.vertical, dp(14))
            .background(Capsule().fill(Color.black.opacity(0.78)))
            .overlay(Capsule().stroke(Color.white.opacity(0.14), lineWidth: dp(1)))
            .padding(.bottom, dp(34))
    }
}
