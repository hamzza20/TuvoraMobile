import SwiftUI
import TuvoraCore

/// Follows TvAppLifecycle's gate screen. The screens are placeholders until the Phase 3 UI; the
/// sign-in screen already runs the real device sign-in against the backend.
struct RootView: View {
    @State private var screen: TvGateScreen = .loading
    /// Simulator smoke hook: `-smokePlay <url> [-smokeLive]` opens the player straight away.
    @State private var smokeSession: TvPlayerSession? = RootView.smokeSessionFromArguments()

    var body: some View {
        if let session = smokeSession {
            TvPlayerScreen(session: session) { smokeSession = nil }
        } else if ProcessInfo.processInfo.arguments.contains("-smokeSignIn") {
            // Simulator smoke hook: shows the sign-in screen without signing the simulator out.
            SignInView()
        } else {
            gate
        }
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
            case .loading: ProgressView("Loading")
            case .signIn: SignInView()
            case .profilePicker: ProfilePickerView()
            case .switching: ProgressView("Opening your profile")
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

