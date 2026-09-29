import SwiftUI
import TuvoraCore

/// Follows TvAppLifecycle's gate screen. The screens are placeholders until the Phase 3 UI; the
/// sign-in screen already runs the real device sign-in against the backend.
struct RootView: View {
    @State private var screen: TvGateScreen = .loading

    var body: some View {
        Group {
            switch screen {
            case .loading: ProgressView("Loading")
            case .signIn: SignInView()
            case .profilePicker: Text("Who's watching?").font(.largeTitle)
            case .switching: ProgressView("Opening your profile")
            case .main: Text("Home").font(.largeTitle)
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

/// QR / code sign-in: the device starts a session and shows the code the person approves at tuvora.co/link.
struct SignInView: View {
    @State private var line = "Starting sign-in…"

    var body: some View {
        VStack(spacing: 24) {
            Text("Sign in to Tuvora").font(.largeTitle)
            Text(line).font(.title2).monospaced()
        }
        .task {
            DeviceLinkAuthRepository.shared.start()
            for await state in DeviceLinkAuthRepository.shared.state {
                switch onEnum(of: state) {
                case .idle: break
                case .starting: line = "Starting sign-in…"
                case .waiting(let waiting):
                    line = "Code \(waiting.code) at \(waiting.verificationUrl)"
                    NSLog("SMOKE device login code=%@ url=%@", waiting.code, waiting.verificationUrl)
                case .failed(let failed):
                    line = "Sign-in could not start (\(failed.reason))"
                    NSLog("SMOKE device login failed=%@", "\(failed.reason)")
                }
            }
        }
    }
}
