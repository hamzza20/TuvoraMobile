import SwiftUI
import TuvoraCore

// NuvioTV "Who's watching?" (ui/screens/profile/ProfileSelectionScreen.kt), selection mode: the
// avatar-tinted background, wordmark, heading, profile cards with the focus ring + scale, and the PIN
// unlock overlay (ProfilePinOverlay / ProfilePinBoxes). All sizes are NuvioTV dp ×2.
//
// Not ported: profile management (create / edit / delete / PIN set) — NuvioTV reaches it by
// long-pressing a card; Apple TV has no profile editor yet, so the "Hold to manage profile" hint is
// left out rather than promising something that does nothing.

/// ProfileSelectionSpacing (ProfileSelectionScreen.kt:125-161).
private enum PickerMetrics {
    static let screenPaddingH = dp(56), screenPaddingV = dp(48)
    static let logoHeight = dp(44), logoToHeading = dp(28), headingToSub = dp(12)
    static let gridGap = dp(28), compactGridGap = dp(12)
    static let cardWidth = dp(152), compactCardWidth = dp(128)
    static let cardPadH = dp(10), cardPadV = dp(8)
    static let avatarContainer = dp(126), compactAvatarContainer = dp(104)
    static let avatarToName = dp(12), compactAvatarToName = dp(10)
    static let nameToMeta = dp(8), metaSlot = dp(16)
    static let pinHeadingToBoxes = dp(42), pinBoxesToSupport = dp(26)
    static let pinBox = dp(118), pinBoxGap = dp(14), pinSupportMaxWidth = dp(720)
    static let primaryGold = Color(argb: 0xFFFFB300)
}

struct ProfilePickerView: View {
    @State private var profiles: [NuvioProfile] = []
    @State private var avatars: [AvatarCatalogItem] = []
    @State private var loaded = false
    @State private var focusedIndex: Int32?
    @State private var pinProfile: NuvioProfile?
    @FocusState private var focus: Int32?

    var body: some View {
        ZStack {
            ProfileSelectionBackground(avatarHex: (pinProfile ?? focusedProfile)?.avatarColorHex)
            if let pinProfile {
                ProfilePinOverlay(profile: pinProfile) { self.pinProfile = nil; restoreFocus(pinProfile.profileIndex) }
                    .transition(.opacity.combined(with: .offset(x: dp(40))))
            } else {
                main.transition(.opacity.combined(with: .offset(x: -dp(40))))
            }
        }
        .animation(.timingCurve(0.2, 0, 0, 1, duration: 0.32), value: pinProfile?.profileIndex)
        .ignoresSafeArea()
        .task { for await items in AvatarRepository.shared.avatars { avatars = items } }
        .task {
            for await state in ProfileRepository.shared.state {
                profiles = state.profiles
                loaded = loaded || state.isLoaded || !state.profiles.isEmpty
                if focusedIndex == nil, let first = state.activeProfile ?? state.profiles.first {
                    focusedIndex = first.profileIndex
                }
                NSLog("SMOKE profiles=%@", state.profiles.map { "\($0.profileIndex):\($0.pinEnabled ? "pin" : "open")" }.joined(separator: ","))
                runSmokeHooks(state.profiles)
            }
        }
    }

    private var focusedProfile: NuvioProfile? { profiles.first { $0.profileIndex == focusedIndex } ?? profiles.first }

    private var main: some View {
        VStack(spacing: 0) {
            Image("app_logo_wordmark").resizable().scaledToFit().frame(height: PickerMetrics.logoHeight)
            Spacer().frame(height: PickerMetrics.logoToHeading)
            Text("Who's watching?")
                .font(NuvioType.inter(44, .bold)).tracking(-1)
                .foregroundStyle(NuvioPrimitives.white)
            Spacer().frame(height: PickerMetrics.headingToSub)
            Text("Select a profile to continue")
                .font(NuvioType.inter(18, .medium))
                .foregroundStyle(NuvioPrimitives.neutral400)
            Spacer(minLength: 0)
            grid
            Spacer(minLength: 0)
        }
        .padding(.horizontal, PickerMetrics.screenPaddingH)
        .padding(.vertical, PickerMetrics.screenPaddingV)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    @ViewBuilder
    private var grid: some View {
        if profiles.isEmpty {
            if loaded {
                Text("No profiles found").font(NuvioType.inter(18, .medium)).foregroundStyle(NuvioPrimitives.neutral400)
            } else {
                ProgressView()
            }
        } else {
            GeometryReader { geo in
                let layout = TvProfileGridLayout.shared.layout(
                    itemCount: Int32(profiles.count), maxWidth: Float(geo.size.width),
                    cardWidth: Float(PickerMetrics.cardWidth), compactCardWidth: Float(PickerMetrics.compactCardWidth),
                    gap: Float(PickerMetrics.gridGap), compactGap: Float(PickerMetrics.compactGridGap))
                let row = HStack(alignment: .top, spacing: CGFloat(layout.gap)) {
                    ForEach(profiles, id: \.profileIndex) { profile in
                        ProfileCard(profile: profile, avatarURL: avatarURL(profile), compact: layout.compact,
                                    focused: focus == profile.profileIndex) { select(profile) }
                            .focused($focus, equals: profile.profileIndex)
                    }
                }
                .padding(.vertical, dp(12))
                Group {
                    if layout.scrollable {
                        ScrollView(.horizontal, showsIndicators: false) { row }.scrollClipDisabled()
                    } else {
                        row.frame(maxWidth: .infinity)
                    }
                }
                .frame(maxHeight: .infinity)
            }
            .frame(height: PickerMetrics.avatarContainer + dp(120))
            .focusSection()
            .defaultFocus($focus, focusedIndex ?? profiles.first?.profileIndex)
            .onChange(of: focus) { _, new in if let new { focusedIndex = new } }
        }
    }

    private func avatarURL(_ profile: NuvioProfile) -> String? {
        let avatar = profile.avatarId.flatMap { id in avatars.first { $0.id == id } }
        return ProfileModelsKt.profileAvatarImageUrl(profile: profile, avatar: avatar)
    }

    private func select(_ profile: NuvioProfile) {
        if profile.pinEnabled {
            pinProfile = profile
        } else {
            NSLog("SMOKE pick profile=%d", profile.profileIndex)
            TvAppLifecycle.shared.pickProfile(profileIndex: profile.profileIndex)
        }
    }

    private func restoreFocus(_ index: Int32) {
        DispatchQueue.main.async { focus = index }
    }

    /// Simulator hooks: `-smokePickProfile <index>` picks without a remote; `-smokePin <index>` opens
    /// that profile's PIN overlay (for screenshots).
    @State private var smokeHandled = false
    private func runSmokeHooks(_ profiles: [NuvioProfile]) {
        guard !smokeHandled else { return }
        let args = ProcessInfo.processInfo.arguments
        func index(after flag: String) -> Int32? {
            guard let i = args.firstIndex(of: flag), i + 1 < args.count else { return nil }
            return Int32(args[i + 1])
        }
        if let index = index(after: "-smokePickProfile"), profiles.contains(where: { $0.profileIndex == index }) {
            smokeHandled = true
            TvAppLifecycle.shared.pickProfile(profileIndex: index)
        } else if let index = index(after: "-smokePin"), let profile = profiles.first(where: { $0.profileIndex == index }) {
            smokeHandled = true
            pinProfile = profile
        }
    }
}

// MARK: - Background

/// ProfileSelectionBackground (ProfileSelectionScreen.kt:810-883): fixed dark base tinted by the
/// focused avatar colour — vertical lerp(#1E1E1E, avatar, .30) → lerp(#121212, avatar, .14) @ .42 →
/// #121212, plus a left-side wash avatar 26% → 8% @ .45 → clear @ .72. Crossfades over 520 ms.
private struct ProfileSelectionBackground: View {
    let avatarHex: String?

    var body: some View {
        let avatar = RGB(hex: avatarHex ?? "#555555")
        ZStack {
            layer(avatar).id(avatarHex ?? "none").transition(.opacity)
        }
        .animation(.easeInOut(duration: 0.52), value: avatarHex)
        .ignoresSafeArea()
    }

    private func layer(_ avatar: RGB) -> some View {
        let base = RGB(0x12, 0x12, 0x12), elevated = RGB(0x1E, 0x1E, 0x1E)
        return ZStack {
            LinearGradient(stops: [
                .init(color: elevated.lerp(avatar, 0.3).color, location: 0),
                .init(color: base.lerp(avatar, 0.14).color, location: 0.42),
                .init(color: base.color, location: 1),
            ], startPoint: .top, endPoint: .bottom)
            LinearGradient(stops: [
                .init(color: avatar.color.opacity(0.26), location: 0),
                .init(color: avatar.color.opacity(0.08), location: 0.45),
                .init(color: .clear, location: 0.72),
                .init(color: .clear, location: 1),
            ], startPoint: .leading, endPoint: .trailing)
        }
    }
}

/// sRGB triple for Compose-style `lerp` between colours.
private struct RGB {
    var r: Double, g: Double, b: Double
    init(_ r: Int, _ g: Int, _ b: Int) { self.r = Double(r) / 255; self.g = Double(g) / 255; self.b = Double(b) / 255 }
    init(hex: String) {
        let cleaned = hex.trimmingCharacters(in: CharacterSet(charactersIn: "#"))
        let value = UInt32(cleaned.suffix(6), radix: 16) ?? 0x1E88E5
        self.init(Int((value >> 16) & 0xFF), Int((value >> 8) & 0xFF), Int(value & 0xFF))
    }
    func lerp(_ other: RGB, _ t: Double) -> RGB {
        var out = self
        out.r += (other.r - r) * t; out.g += (other.g - g) * t; out.b += (other.b - b) * t
        return out
    }
    var color: Color { Color(.sRGB, red: r, green: g, blue: b, opacity: 1) }
}

// MARK: - Profile card

/// ProfileCard (ProfileSelectionScreen.kt:1055-1246): avatar 96 → 102 inside a ring 114 → 122 whose
/// focus stroke grows 1 → 3dp; the card scales to 1.04 over 210 ms; name 17sp, secondary → primary.
/// The primary profile carries NuvioTV's gold star badge and "PRIMARY" caption.
private struct ProfileCard: View {
    let profile: NuvioProfile
    let avatarURL: String?
    let compact: Bool
    let focused: Bool
    let action: () -> Void
    @Environment(\.nuvio) private var colors

    private var isPrimary: Bool { profile.profileIndex == ProfileModelsKt.PRIMARY_PROFILE_INDEX }

    var body: some View {
        let avatarSize = compact ? (focused ? dp(88) : dp(82)) : (focused ? dp(102) : dp(96))
        let ringSize = compact ? (focused ? dp(104) : dp(98)) : (focused ? dp(122) : dp(114))
        let container = compact ? PickerMetrics.compactAvatarContainer : PickerMetrics.avatarContainer
        Button(action: action) {
            VStack(spacing: 0) {
                ZStack {
                    ZStack {
                        Circle().stroke(colors.border.opacity(0.75), lineWidth: NuvioTokens.Stroke.hairline)
                        Circle().stroke(colors.focusRing.opacity(focused ? 1 : 0), lineWidth: focused ? dp(3) : dp(1))
                        ProfileAvatarCircle(name: profile.name, colorHex: profile.avatarColorHex, imageURL: avatarURL, size: avatarSize)
                    }
                    .frame(width: ringSize, height: ringSize)
                    if isPrimary {
                        Text("\u{2605}").font(NuvioType.inter(compact ? 12 : 14, .bold)).foregroundStyle(.white)
                            .frame(width: compact ? dp(22) : dp(26), height: compact ? dp(22) : dp(26))
                            .background(Circle().fill(PickerMetrics.primaryGold))
                            .overlay(Circle().stroke(colors.background, lineWidth: dp(2)))
                            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
                            .offset(x: dp(2), y: dp(1))
                    }
                }
                .frame(width: container, height: container)
                Spacer().frame(height: compact ? PickerMetrics.compactAvatarToName : PickerMetrics.avatarToName)
                Text(profile.name)
                    .font(NuvioType.inter(compact ? 15 : 17, focused ? .semibold : .medium))
                    .foregroundStyle(focused ? colors.textPrimary : colors.textSecondary)
                    .lineLimit(1).truncationMode(.tail)
                Spacer().frame(height: PickerMetrics.nameToMeta)
                Group {
                    if isPrimary {
                        Text("PRIMARY").font(NuvioType.inter(11, .semibold)).tracking(dp(0.8))
                            .foregroundStyle(PickerMetrics.primaryGold)
                    } else {
                        Color.clear
                    }
                }
                .frame(height: PickerMetrics.metaSlot * 1.6, alignment: .top)
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

/// ProfileAvatarCircle (components/ProfileAvatarCircle.kt): the avatar colour disc with the image
/// cropped over it, or the initial at 40% of the size when there is no image or it fails to load.
struct ProfileAvatarCircle: View {
    let name: String
    let colorHex: String
    let imageURL: String?
    let size: CGFloat

    var body: some View {
        ZStack {
            Circle().fill(Color(hex: colorHex))
            let initial = Text(name.first.map { String($0).uppercased() } ?? "?")
                .font(.custom("Inter", size: size * 0.4).weight(.bold)).foregroundStyle(.white)
            if let imageURL, !imageURL.isEmpty {
                CachedPosterArtwork(urlString: imageURL, width: size, height: size, maximumWidth: size * 2) { initial }
            } else {
                initial
            }
        }
        .frame(width: size, height: size)
        .clipShape(Circle())
    }
}

// MARK: - PIN overlay

/// ProfilePinOverlay (ProfileSelectionScreen.kt:2044-2360), unlock mode: heading, four PIN boxes,
/// support / error line, the forgot-PIN hint and the back hint. The fourth digit submits.
///
/// Input: a focusable 0–9 digit row under the boxes plus delete, the way tvOS's own passcode screens
/// (Restrictions, parental controls) take a PIN with the Siri Remote — the boxes stay visible and the
/// fourth digit submits, with no full-screen keyboard and no "Done" step. A hardware keyboard's digit
/// and delete keys also work (NuvioTV: "Use your remote or keyboard").
struct ProfilePinOverlay: View {
    let profile: NuvioProfile
    let onDismiss: () -> Void
    @Environment(\.nuvio) private var colors
    @State private var pin = ""
    @State private var working = false
    @State private var error: String?
    @State private var shake: CGFloat = 0
    @FocusState private var focusedKey: String?

    private static let keys = ["1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "⌫"]

    var body: some View {
        VStack(spacing: 0) {
            Text("Enter your PIN to access \(profile.name).")
                .font(NuvioType.inter(42, .bold)).lineSpacing(dp(6))
                .foregroundStyle(colors.textPrimary)
                .multilineTextAlignment(.center)
            Spacer().frame(height: PickerMetrics.pinHeadingToBoxes)
            PinBoxes(value: pin, working: working, error: error != nil)
                .offset(x: shake)
            Spacer().frame(height: PickerMetrics.pinBoxesToSupport)
            Text(supportText)
                .font(NuvioType.inter(18, .medium))
                .foregroundStyle(error != nil ? Color(argb: 0xFFFF8E8E) : colors.textSecondary)
                .multilineTextAlignment(.center)
                .frame(maxWidth: PickerMetrics.pinSupportMaxWidth)
            Spacer().frame(height: dp(10))
            Text("Forgot PIN? Reset it from your Tuvora account on tuvora website.")
                .font(NuvioType.inter(14, .medium)).foregroundStyle(colors.textTertiary)
                .multilineTextAlignment(.center)
                .frame(maxWidth: PickerMetrics.pinSupportMaxWidth)
            Spacer().frame(height: dp(14))
            Text("Press back to cancel").font(NuvioType.inter(14, .medium)).foregroundStyle(colors.textTertiary)
            Spacer().frame(height: dp(28))
            keypad
        }
        .padding(.horizontal, PickerMetrics.screenPaddingH)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .onExitCommand(perform: onDismiss)
        .onKeyPress(phases: .down) { press in
            if press.key == .delete { type("⌫"); return .handled }
            let chars = press.characters
            if chars.count == 1, chars.first?.isNumber == true { type(chars); return .handled }
            return .ignored
        }
        .onAppear { DispatchQueue.main.async { focusedKey = "1" } }
    }

    private var supportText: String {
        if let error { return error }
        if working { return "Verifying…" }
        return "Use your remote or keyboard to enter 4 digits."
    }

    private var keypad: some View {
        HStack(spacing: dp(8)) {
            ForEach(Self.keys, id: \.self) { key in
                PinKey(label: key, focused: focusedKey == key) { type(key) }
                    .focused($focusedKey, equals: key)
                    .disabled(working)
            }
        }
        .focusSection()
    }

    private func type(_ key: String) {
        let policy = TvProfilePinPolicy.shared
        if key == "⌫" {
            pin = policy.backspace(pin: pin, isWorking: working)
        } else if let digit = key.utf16.first {
            pin = policy.append(pin: pin, digit: digit, isWorking: working)
        }
        error = nil
        if policy.isComplete(pin: pin) { submit() }
    }

    private func submit() {
        let submitted = pin
        pin = ""
        working = true
        Task {
            let result = try? await ProfileRepository.shared.verifyPin(profileIndex: profile.profileIndex, pin: submitted)
            let outcome = TvProfilePinPolicy.shared.outcome(result: result)
            working = false
            switch outcome.kind {
            case .unlocked:
                NSLog("SMOKE pin unlocked profile=%d", profile.profileIndex)
                TvAppLifecycle.shared.pickProfile(profileIndex: profile.profileIndex)
                return
            case .locked: error = "Profile is locked. Try again in \(outcome.retryAfterSeconds)s."
            case .incorrect: error = "Current PIN is incorrect."
            default: error = "Could not verify PIN. Try again."
            }
            NSLog("SMOKE pin rejected profile=%d", profile.profileIndex)
            await playErrorShake()
        }
    }

    /// NuvioTV's shake: -22, 18, -14, 10, -6, 0 px at 42 ms a step.
    private func playErrorShake() async {
        for offset in [-22.0, 18, -14, 10, -6, 0] {
            withAnimation(.linear(duration: 0.042)) { shake = offset }
            try? await Task.sleep(nanoseconds: 42_000_000)
        }
    }
}

/// ProfilePinBoxes (ProfileSelectionScreen.kt:2362-2469): 118dp squares, radius 2, 14dp apart.
private struct PinBoxes: View {
    let value: String
    let working: Bool
    let error: Bool
    @Environment(\.nuvio) private var colors
    @State private var cursorOn = true

    var body: some View {
        HStack(spacing: PickerMetrics.pinBoxGap) {
            ForEach(0..<Int(TvProfilePinPolicy.shared.PIN_LENGTH), id: \.self) { index in
                box(index)
            }
        }
        .environment(\.layoutDirection, .leftToRight)
        .onAppear { withAnimation(.easeInOut(duration: 0.52).repeatForever(autoreverses: true)) { cursorOn = false } }
    }

    private func box(_ index: Int) -> some View {
        let filled = index < value.count
        let active = index == value.count && !working
        let shape = RoundedRectangle(cornerRadius: dp(2))
        let fill: Color = error ? Color(argb: 0xFF311818).opacity(0.76)
            : filled ? Color.white.opacity(0.07) : active ? Color.white.opacity(0.04) : .clear
        let stroke: Color = error ? Color(argb: 0xFFE35D5D)
            : active ? colors.focusRing : filled ? Color.white.opacity(0.92) : Color.white.opacity(0.72)
        return ZStack {
            shape.fill(fill)
            shape.stroke(stroke, lineWidth: (active || error) ? dp(2) : dp(1))
            if filled {
                Circle().fill(Color.white).frame(width: dp(16), height: dp(16))
            } else if active {
                Rectangle().fill(Color.white.opacity(cursorOn ? 1 : 0.25)).frame(width: dp(2), height: dp(40))
            }
        }
        .frame(width: PickerMetrics.pinBox, height: PickerMetrics.pinBox)
        .animation(.easeInOut(duration: 0.14), value: filled)
        .animation(.easeInOut(duration: 0.14), value: error)
    }
}

/// One key of the digit row, styled like NuvioTV's auth buttons: white 5% + 9% hairline, white fill
/// with black text on focus.
private struct PinKey: View {
    let label: String
    let focused: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(label)
                .font(NuvioType.inter(18, .semibold))
                .foregroundStyle(focused ? Color.black : Color(argb: 0xFFF5F7F8))
                .frame(width: dp(44), height: dp(44))
                .background(RoundedRectangle(cornerRadius: dp(10), style: .continuous).fill(focused ? Color.white : Color.white.opacity(0.05)))
                .overlay(RoundedRectangle(cornerRadius: dp(10), style: .continuous).stroke(Color.white.opacity(focused ? 0 : 0.09), lineWidth: dp(1)))
                .scaleEffect(focused ? 1.08 : 1)
                .animation(NuvioTokens.Motion.fast, value: focused)
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .reportsFocus(focused)
    }
}
