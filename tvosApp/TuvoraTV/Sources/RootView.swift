import SwiftUI
import TuvoraCore

/// Follows TvAppLifecycle's gate screen. The screens are placeholders until the Phase 3 UI; the
/// sign-in screen already runs the real device sign-in against the backend.
struct RootView: View {
    @State private var screen: TvGateScreen = .loading
    /// Simulator smoke hook: `-smokePlay <url> [-smokeLive]` opens the player straight away.
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
        guard let i = args.firstIndex(of: "-smokePlay"), i + 1 < args.count else { return nil }
        let launch = TvPlayerLaunches.shared.direct(url: args[i + 1], title: "Smoke test", isLive: args.contains("-smokeLive"), startPositionMs: 0)
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
                if let message { Text(message).font(NuvioType.bodyMedium).foregroundStyle(NuvioPalette.marigold.textSecondary) }
            }
        }
        .onAppear { pulse = true }
    }
}
