import SwiftUI
import TuvoraCore

/// Follows TvAppLifecycle's gate screen. The screens are placeholders until the Phase 3 UI; the
/// sign-in screen already runs the real device sign-in against the backend.
struct RootView: View {
    @State private var screen: TvGateScreen = .loading
    /// Simulator smoke hook: `-smokePlay <url> [-smokeLive]` opens the player straight away. With
    /// `-smokeAs <type>:<videoId>` (e.g. `series:tt0944947:1:1`) it waits for the signed-in main screen
    /// and plays the URL as that title, so its skip segments and add-on subtitles load;
    /// `-smokeStartMs <ms>` resumes from there.
    @State private var smokeSession: TvPlayerSession? = RootView.smokeSessionFromArguments()

    var body: some View {
        Group {
            if let session = smokeSession {
                TvPlayerScreen(session: session) { smokeSession = nil }.environmentObject(PlaybackCoordinator())
            } else if ProcessInfo.processInfo.arguments.contains("-smokeSignIn") {
                // Simulator smoke hook: shows the sign-in screen without signing the simulator out.
                SignInView()
            } else {
                gate
            }
        }
        // Top Shelf items open here (tuvora://title?…); MainShell opens them once the gate reaches Main.
        .onOpenURL { url in if let link = DeepLink(url: url) { DeepLinkCenter.shared.pending = link } }
    }

    private static func smokeSessionFromArguments() -> TvPlayerSession? {
        let args = ProcessInfo.processInfo.arguments
        guard let i = args.firstIndex(of: "-smokePlay"), i + 1 < args.count, !args.contains("-smokeAs") else { return nil }
        let launch = TvPlayerLaunches.shared.direct(url: args[i + 1], title: "Smoke test", isLive: args.contains("-smokeLive"), startPositionMs: 0)
        return TvPlayerSession(launch: launch, liveReresolve: nil)
    }

    /// `-smokeAs`: the smoke session as a catalog title, built once the profile is loaded.
    private static func smokeCatalogSession() -> TvPlayerSession? {
        let args = ProcessInfo.processInfo.arguments
        guard let i = args.firstIndex(of: "-smokePlay"), i + 1 < args.count,
              let j = args.firstIndex(of: "-smokeAs"), j + 1 < args.count else { return nil }
        let spec = args[j + 1]
        guard let colon = spec.firstIndex(of: ":") else { return nil }
        let type = String(spec[..<colon]), videoId = String(spec[spec.index(after: colon)...])
        let start = args.firstIndex(of: "-smokeStartMs").flatMap { k in k + 1 < args.count ? Int64(args[k + 1]) : nil } ?? 0
        let launch = TvPlayerLaunches.shared.directAs(url: args[i + 1], title: "Smoke test", type: type, videoId: videoId, startPositionMs: start)
        return TvPlayerSession(launch: launch, liveReresolve: nil)
    }

    private var gate: some View {
        Group {
            switch screen {
            case .loading: SplashView(message: nil)
            case .signIn: SignInView()
            case .profilePicker: ProfilePickerView()
            case .switching: SplashView(message: nil)
            case .main: MainShell()
            }
        }
        .task {
            for await next in TvAppLifecycle.shared.screen {
                NSLog("SMOKE gate screen=%@", "\(next)")
                if next == .main, smokeSession == nil, let session = Self.smokeCatalogSession() {
                    try? await Task.sleep(nanoseconds: 2_000_000_000)   // let the add-ons load first
                    smokeSession = session
                }
                screen = next
                TvAppGraph.shared.screenChanged(name: "tv_gate_\(next)")
            }
        }
    }
}



/// NuvioTV's startup splash (startup_splash_enabled): the Tuvora wordmark centred on the app background,
/// used while the session restores and while a profile opens.
struct SplashView: View {
    let message: String?
    @State private var pulse = false

    var body: some View {
        ZStack {
            NuvioPalette.marigold.background.ignoresSafeArea()
            VStack(spacing: dp(24)) {
                Image("app_logo_wordmark").resizable().scaledToFit().frame(height: dp(60))
                    .opacity(pulse ? 1 : 0.7)
                    .animation(.easeInOut(duration: 0.9).repeatForever(autoreverses: true), value: pulse)
                ProgressView().tint(NuvioPalette.marigold.secondary)
                if let message { Text(ui: message).font(NuvioType.bodyMedium).foregroundStyle(NuvioPalette.marigold.textSecondary) }
            }
        }
        .onAppear { pulse = true }
    }
}
