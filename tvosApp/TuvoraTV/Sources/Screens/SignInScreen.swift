import SwiftUI
import TuvoraCore

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

