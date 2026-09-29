import SwiftUI
import TuvoraCore

/// Tuvora's Marigold identity on Apple TV (the phone's default theme; brand-neutral accents).
enum Theme {
    static let background = Color(red: 0.051, green: 0.051, blue: 0.051)
    static let surface = Color.white.opacity(0.07)
    static let surfaceFocused = Color.white.opacity(0.18)
    static let accent = Color(red: 0.961, green: 0.702, blue: 0.004)       // Genda / marigold #F5B301
    static let live = Color(red: 0.639, green: 0.071, blue: 0.227)         // Kumkum #A3123A, the LIVE chip
    static let secondaryText = Color.white.opacity(0.62)
}

/// The one full-screen player. Any screen asks it to play; it presents over the whole app.
@MainActor
final class PlaybackCoordinator: ObservableObject {
    @Published var session: TvPlayerSessionBox?
    @Published var message: String?

    func play(_ session: TuvoraCore.TvPlayerSession) { self.session = TvPlayerSessionBox(session: session) }
    func stop() { session = nil }

    /// Shows a short notice (e.g. "This channel isn't available right now").
    func notify(_ text: String) {
        message = text
        Task { try? await Task.sleep(nanoseconds: 3_500_000_000); if message == text { message = nil } }
    }
}

struct TvPlayerSessionBox: Identifiable {
    let id = UUID()
    let session: TuvoraCore.TvPlayerSession
}
