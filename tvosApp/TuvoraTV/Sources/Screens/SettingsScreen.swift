import SwiftUI
import TuvoraCore

/// Minimal v1 settings: who is signed in, switch profile, sign out.
struct SettingsScreen: View {
    @State private var auth: AuthState?
    @State private var confirmSignOut = false

    var body: some View {
        List {
            Section("Account") {
                Text(accountLine).foregroundStyle(.secondary)
                Button("Switch profile") { TvAppLifecycle.shared.openProfilePicker() }
                if let auth, case .authenticated = onEnum(of: auth) {
                    Button("Sign out", role: .destructive) { confirmSignOut = true }
                }
            }
        }
        .frame(maxWidth: 1100)
        .task { for await next in AuthRepository.shared.state { auth = next } }
        .alert("Sign out of Tuvora on this Apple TV?", isPresented: $confirmSignOut) {
            Button("Sign out", role: .destructive) { Task { try? await AuthRepository.shared.signOut() } }
            Button("Cancel", role: .cancel) {}
        }
    }

    private var accountLine: String {
        guard let auth else { return "…" }
        switch onEnum(of: auth) {
        case .authenticated(let signedIn): return signedIn.isAnonymous ? "Not signed in" : "Signed in as \(signedIn.email ?? "your account")"
        case .unauthenticated: return "Not signed in"
        case .loading: return "Checking your account…"
        }
    }
}
