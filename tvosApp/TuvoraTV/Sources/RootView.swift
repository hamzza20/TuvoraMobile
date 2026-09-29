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
        } else {
            gate
        }
    }

    private static func smokeSessionFromArguments() -> TvPlayerSession? {
        let args = ProcessInfo.processInfo.arguments
        guard let i = args.firstIndex(of: "-smokePlay"), i + 1 < args.count else { return nil }
        let launch = TvPlayerLaunches.shared.direct(url: args[i + 1], title: "Smoke test", isLive: args.contains("-smokeLive"), startPositionMs: 0)
        return TvPlayerSession(launch: launch)
    }

    private var gate: some View {
        Group {
            switch screen {
            case .loading: ProgressView("Loading")
            case .signIn: SignInView()
            case .profilePicker: ProfilePickerView()
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

/// Minimal "Who's watching?" so the gate can be driven end to end. Phase 3 replaces it with the full
/// picker (avatars, PIN entry, add profile); PIN-locked profiles are shown but not selectable yet.
struct ProfilePickerView: View {
    @State private var profiles: [NuvioProfile] = []

    var body: some View {
        VStack(spacing: 40) {
            Text("Who's watching?").font(.largeTitle)
            if profiles.isEmpty {
                ProgressView("Loading profiles")
            } else {
                HStack(spacing: 32) {
                    ForEach(profiles, id: \.profileIndex) { profile in
                        Button {
                            NSLog("SMOKE pick profile=%d", profile.profileIndex)
                            TvAppLifecycle.shared.pickProfile(profileIndex: profile.profileIndex)
                        } label: {
                            Text(profile.pinEnabled ? "\(profile.name) 🔒" : profile.name).font(.title2)
                        }
                        .disabled(profile.pinEnabled)
                    }
                }
            }
        }
        .task {
            for await state in ProfileRepository.shared.state {
                profiles = state.profiles
                // Simulator smoke hook: `-smokePickProfile <index>` picks without a remote.
                let args = ProcessInfo.processInfo.arguments
                if let i = args.firstIndex(of: "-smokePickProfile"), i + 1 < args.count, let index = Int32(args[i + 1]),
                   state.profiles.contains(where: { $0.profileIndex == index }) {
                    TvAppLifecycle.shared.pickProfile(profileIndex: index)
                }
                NSLog("SMOKE profiles=%@", state.profiles.map { "\($0.profileIndex):\($0.pinEnabled ? "pin" : "open")" }.joined(separator: ","))
            }
        }
    }
}
