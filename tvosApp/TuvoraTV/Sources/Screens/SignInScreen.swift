import CoreImage.CIFilterBuiltins
import SwiftUI
import TuvoraCore

// NuvioTV QR sign-in (ui/screens/account/AuthQrSignInScreen.kt), onboarding mode: brand panel on the
// left, the QR login pane (460dp, white 2.2% with a 1dp white-7% left edge) on the right. The device
// starts a login session (DeviceLinkAuthRepository, the same flow as the phone and NuvioTV); the
// viewer approves it at tuvora.co/link, or continues without an account (a local anonymous session,
// the phone's AuthRepository.signInAnonymously).
//
// Not ported: "Sign in with email" — NuvioTV routes it to its own email screen, which Apple TV does
// not have; QR stays the only sign-in, which NuvioTV's own copy already says ("TV stays QR-only").

private enum AuthColors {
    static let textPrimary = Color(argb: 0xFFF5F7F8)
    static let textSecondary = Color(argb: 0xFF969CA3)
    static let paneBackground = Color.white.opacity(0.022)
    static let paneBorder = Color.white.opacity(0.07)
    static let buttonBackground = Color.white.opacity(0.05)
    static let buttonBorder = Color.white.opacity(0.09)
    static let errorBackground = Color(argb: 0x33C62828)
    static let errorText = Color(argb: 0xFFFF6E6E)
}

struct SignInView: View {
    @State private var state: DeviceLinkAuthState = DeviceLinkAuthStateIdle.shared
    @State private var showTermsQr = false
    @FocusState private var focus: Control?

    enum Control: Hashable { case refresh, continueWithout, terms }

    var body: some View {
        ZStack {
            AuthGradientBackground()
            HStack(spacing: 0) {
                brandPanel
                    .padding(.horizontal, dp(56))
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
                loginPane
                    .frame(width: dp(460))
                    .frame(maxHeight: .infinity)
                    .background(AuthColors.paneBackground)
                    .overlay(alignment: .leading) { Rectangle().fill(AuthColors.paneBorder).frame(width: dp(1)) }
            }
            if showTermsQr {
                TermsQrDialog { showTermsQr = false; focus = .terms }
            }
        }
        .ignoresSafeArea()
        .task {
            DeviceLinkAuthRepository.shared.start()
            for await next in DeviceLinkAuthRepository.shared.state {
                state = next
                switch onEnum(of: next) {
                case .waiting(let waiting):
                    #if DEBUG
                    NSLog("SMOKE device login code=%@ url=%@ completing=%d", waiting.code, waiting.verificationUrl, waiting.isCompleting)
                    #endif
                case .failed(let failed):
                    NSLog("SMOKE device login failed=%@", "\(failed.reason)")
                default: break
                }
            }
        }
        .onDisappear {
            // NuvioTV clears the QR session when the screen goes; never while the approved session is
            // being exchanged (that finishes on its own and signs the device in).
            if case .waiting(let waiting) = onEnum(of: state), waiting.isCompleting { return }
            DeviceLinkAuthRepository.shared.cancel()
        }
        .defaultFocus($focus, .continueWithout)
    }

    // MARK: Brand panel (AuthQrBrandPanel)

    private var brandPanel: some View {
        VStack(alignment: .leading, spacing: 0) {
            Image("app_logo_wordmark").resizable().scaledToFit().frame(height: dp(60))
            Spacer().frame(height: dp(32))
            Text("Watch your library, anywhere")
                .font(NuvioType.inter(40, .semibold)).lineSpacing(dp(5))
                .foregroundStyle(AuthColors.textPrimary)
                .frame(maxWidth: dp(440), alignment: .leading)
            Spacer().frame(height: dp(18))
            Text("Use your phone to sign in with email/password. TV stays QR-only for faster login.")
                .font(NuvioType.inter(17, .regular)).lineSpacing(dp(9))
                .foregroundStyle(AuthColors.textSecondary)
                .frame(maxWidth: dp(400), alignment: .leading)
        }
    }

    // MARK: Login pane (AuthQrLoginPane + AuthQrCodeBlock)

    private var waiting: DeviceLinkAuthStateWaiting? {
        if case .waiting(let waiting) = onEnum(of: state) { return waiting }
        return nil
    }

    private var isLoading: Bool {
        switch onEnum(of: state) {
        case .idle, .starting: return true
        default: return false
        }
    }

    private var loginPane: some View {
        VStack(spacing: 0) {
            Text("Scan the QR code or enter the short code in your browser.")
                .font(NuvioType.inter(15, .regular)).lineSpacing(dp(6))
                .foregroundStyle(AuthColors.textSecondary)
                .multilineTextAlignment(.center)
            Spacer().frame(height: dp(28))
            qrBlock
            Spacer().frame(height: dp(28))
            HStack(spacing: dp(12)) {
                AuthButton(title: isLoading ? "Please wait…" : "Refresh QR", focused: focus == .refresh) {
                    DeviceLinkAuthRepository.shared.cancel()
                    DeviceLinkAuthRepository.shared.start()
                }
                .focused($focus, equals: .refresh)
                .disabled(isLoading)
                AuthButton(title: "Continue without account", focused: focus == .continueWithout) {
                    NSLog("SMOKE sign-in continue without account")
                    DeviceLinkAuthRepository.shared.cancel()
                    AuthRepository.shared.signInAnonymously()
                }
                .focused($focus, equals: .continueWithout)
            }
            .focusSection()
        }
        .padding(.horizontal, dp(48))
    }

    @ViewBuilder
    private var qrBlock: some View {
        if let waiting, let qr = QrCode.image(for: waiting.verificationUrl) {
            Image(decorative: qr, scale: 1).interpolation(.none).resizable()
                .padding(dp(8))
                .frame(width: dp(206), height: dp(206))
                .background(RoundedRectangle(cornerRadius: dp(8)).fill(Color.white))
                .accessibilityLabel("QR login code")
        } else {
            Text(isLoading ? "Generating QR…" : "QR unavailable. Refresh to retry.")
                .font(NuvioType.bodyMedium).foregroundStyle(AuthColors.textSecondary)
                .multilineTextAlignment(.center).padding(dp(16))
                .frame(width: dp(206), height: dp(206))
                .background(RoundedRectangle(cornerRadius: dp(8)).fill(AuthColors.buttonBackground))
                .overlay(RoundedRectangle(cornerRadius: dp(8)).stroke(AuthColors.buttonBorder, lineWidth: dp(1)))
        }

        if let waiting {
            Spacer().frame(height: dp(18))
            Text("Or go to \(Self.displayUrl(waiting.verificationUrl)) and enter")
                .font(NuvioType.bodyMedium).foregroundStyle(AuthColors.textSecondary)
                .multilineTextAlignment(.center)
            Spacer().frame(height: dp(10))
            Text(waiting.code)
                .font(NuvioType.inter(24, .semibold)).tracking(dp(3))
                .foregroundStyle(AuthColors.textPrimary)
        } else if isLoading {
            Spacer().frame(height: dp(18))
            VStack(spacing: dp(10)) {
                NuvioShimmer(cornerRadius: dp(6)).frame(width: dp(238), height: dp(12))
                NuvioShimmer(cornerRadius: dp(8)).frame(width: dp(116), height: dp(22))
            }
        }

        Spacer().frame(height: dp(12))
        termsAcknowledgement

        if let status = statusLine {
            Spacer().frame(height: dp(14))
            if status.isError {
                Text(ui: status.text)
                    .font(NuvioType.bodySmall).foregroundStyle(AuthColors.errorText)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, dp(12)).padding(.vertical, dp(10))
                    .frame(maxWidth: .infinity)
                    .background(RoundedRectangle(cornerRadius: dp(12)).fill(AuthColors.errorBackground))
                    .overlay(RoundedRectangle(cornerRadius: dp(12)).stroke(NuvioPrimitives.neutral750.opacity(0.35), lineWidth: dp(1)))
            } else {
                Text(ui: status.text).font(NuvioType.bodySmall).foregroundStyle(AuthColors.textSecondary)
                    .multilineTextAlignment(.center)
            }
        }
    }

    /// AuthTermsAcknowledgement: the sentence, then the focusable "Terms" link directly under it
    /// (B72). Apple TV has no browser, so the link hands the page off as a QR, as NuvioTV does on
    /// browser-less TVs.
    private var termsAcknowledgement: some View {
        VStack(spacing: dp(2)) {
            Text("Account creation is only for adults 18+ and requires agreement to the")
                .font(NuvioType.inter(13, .regular)).foregroundStyle(AuthColors.textSecondary)
                .multilineTextAlignment(.center)
            Button { showTermsQr = true } label: {
                Text("Terms").font(NuvioType.inter(13, .medium))
                    .foregroundStyle(focus == .terms ? Color.black : AuthColors.textPrimary)
                    .underline(focus != .terms)
                    .padding(.horizontal, dp(8)).padding(.vertical, dp(2))
                    .background(Capsule().fill(focus == .terms ? Color.white : .clear))
            }
            .buttonStyle(PlainNoChromeButtonStyle())
            .focused($focus, equals: .terms)
            .reportsFocus(focus == .terms)
        }
    }

    private var statusLine: (text: String, isError: Bool)? {
        switch onEnum(of: state) {
        case .waiting(let waiting):
            return (waiting.isCompleting ? "Login approved. Finishing sign in…" : "Waiting for approval on your phone…", false)
        case .failed(let failed):
            switch failed.reason {
            case .expired: return ("QR login expired. Generate a new code.", true)
            case .complete: return ("Could not complete QR sign in", true)
            default: return ("Failed to start QR login", true)
            }
        default: return nil
        }
    }

    /// "https://tuvora.co/link?code=ABC123" → "tuvora.co/link" (the manual-entry line shows the page,
    /// the code is printed below it).
    static func displayUrl(_ url: String) -> String {
        var value = url
        if let cut = value.firstIndex(where: { $0 == "?" || $0 == "#" }) { value = String(value[..<cut]) }
        for prefix in ["https://", "http://"] where value.hasPrefix(prefix) { value.removeFirst(prefix.count) }
        while value.hasSuffix("/") { value.removeLast() }
        return value
    }
}

/// The Material TV Button NuvioTV's auth pane uses: radius 16, white 5% with a white-9% hairline;
/// focused white with black text.
private struct AuthButton: View {
    let title: String
    let focused: Bool
    let action: () -> Void
    @Environment(\.isEnabled) private var isEnabled

    var body: some View {
        Button(action: action) {
            Text(ui: title).font(NuvioType.labelLarge).lineLimit(1)
                .foregroundStyle(focused ? Color.black : AuthColors.textPrimary)
                .padding(.horizontal, dp(16)).padding(.vertical, dp(10))
                .background(RoundedRectangle(cornerRadius: dp(16), style: .continuous)
                    .fill(focused ? Color.white : AuthColors.buttonBackground.opacity(isEnabled ? 1 : 0.45)))
                .overlay(RoundedRectangle(cornerRadius: dp(16), style: .continuous)
                    .stroke(AuthColors.buttonBorder, lineWidth: focused ? 0 : dp(1)))
                .scaleEffect(focused ? 1.05 : 1)
                .animation(NuvioTokens.Motion.fast, value: focused)
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .reportsFocus(focused)
    }
}

/// QrHandOffDialog: the page as a QR for a TV without a browser.
private struct TermsQrDialog: View {
    let onClose: () -> Void
    @FocusState private var closeFocused: Bool

    var body: some View {
        ZStack {
            Color.black.opacity(0.7).ignoresSafeArea()
            VStack(spacing: dp(16)) {
                if let qr = QrCode.image(for: "https://tuvora.co/terms") {
                    Image(decorative: qr, scale: 1).interpolation(.none).resizable()
                        .padding(dp(8)).frame(width: dp(206), height: dp(206))
                        .background(RoundedRectangle(cornerRadius: dp(8)).fill(Color.white))
                }
                Text("No web browser on this TV. Scan with your phone to open this page")
                    .font(NuvioType.bodyMedium).foregroundStyle(AuthColors.textSecondary)
                    .multilineTextAlignment(.center)
                Text("tuvora.co/terms").font(NuvioType.labelLarge).foregroundStyle(AuthColors.textPrimary)
                AuthButton(title: "Close", focused: closeFocused, action: onClose).focused($closeFocused)
            }
            .padding(dp(24))
            .frame(width: dp(520))
            .navigationGlass(in: RoundedRectangle(cornerRadius: NuvioTokens.Radius.dialog))
        }
        .onExitCommand(perform: onClose)
        .onAppear { DispatchQueue.main.async { closeFocused = true } }
    }
}

/// authGradientBackground: a 122° linear gradient from deep violet to black over a black base.
private struct AuthGradientBackground: View {
    var body: some View {
        GeometryReader { geo in
            let angle = 122.0 * .pi / 180
            let dx = sin(angle), dy = -cos(angle)
            let w = geo.size.width, h = geo.size.height
            let half = (abs(w * dx) + abs(h * dy)) / 2
            let start = UnitPoint(x: (w / 2 - dx * half) / w, y: (h / 2 - dy * half) / h)
            let end = UnitPoint(x: (w / 2 + dx * half) / w, y: (h / 2 + dy * half) / h)
            LinearGradient(stops: [
                .init(color: Color(argb: 0xFF21113B), location: 0),
                .init(color: Color(argb: 0xFF21113B), location: 0.14),
                .init(color: Color(argb: 0xFF1A0E2F), location: 0.26),
                .init(color: Color(argb: 0xFF130A23), location: 0.36),
                .init(color: Color(argb: 0xFF0A060F), location: 0.48),
                .init(color: Color(argb: 0xFF050408), location: 0.60),
                .init(color: .black, location: 0.70),
                .init(color: .black, location: 1),
            ], startPoint: start, endPoint: end)
        }
        .background(Color.black)
        .ignoresSafeArea()
    }
}

/// QR codes drawn on device with CoreImage's CIQRCodeGenerator (no third-party library).
enum QrCode {
    private static let context = CIContext()

    static func image(for text: String) -> CGImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage else { return nil }
        // Integer upscale keeps the modules crisp; `.interpolation(.none)` does the rest.
        let scaled = output.transformed(by: CGAffineTransform(scaleX: 12, y: 12))
        return context.createCGImage(scaled, from: scaled.extent)
    }
}
