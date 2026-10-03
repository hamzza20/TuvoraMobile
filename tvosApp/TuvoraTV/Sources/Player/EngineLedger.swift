import Foundation
import SwiftUI

/// Which playback engines exist right now: one is added when an engine is created and removed when it
/// is destroyed. An engine that outlives every player screen is what keeps sound playing after the
/// viewer left (B112). Debug builds show the count as the `engines.live` UI-test marker.
@MainActor
final class EngineLedger: ObservableObject {
    static let shared = EngineLedger()
    @Published private(set) var live = 0
    private var ids = Set<ObjectIdentifier>()

    nonisolated static func opened(_ engine: AnyObject) {
        let id = ObjectIdentifier(engine)
        Task { @MainActor in shared.ids.insert(id); shared.live = shared.ids.count }
    }

    nonisolated static func released(_ engine: AnyObject) {
        let id = ObjectIdentifier(engine)
        Task { @MainActor in shared.ids.remove(id); shared.live = shared.ids.count }
    }
}

#if DEBUG
/// UI-test marker: "engines=<n>" — how many playback engines are alive.
struct EngineLedgerMarker: View {
    @ObservedObject private var ledger = EngineLedger.shared
    var body: some View {
        Text("engines=\(ledger.live)")
            .font(.system(size: 1))
            .opacity(0.01)
            .allowsHitTesting(false)
            .accessibilityIdentifier("engines.live")
    }
}
#endif

/// Debug trail of the player's remote handling (which path a press took), for UI tests.
@MainActor
final class RemoteTrail: ObservableObject {
    static let shared = RemoteTrail()
    @Published private(set) var text = ""
    nonisolated static func add(_ event: String) {
        #if DEBUG
        Task { @MainActor in
            let parts = (shared.text.isEmpty ? [] : shared.text.components(separatedBy: " ")) + [event]
            shared.text = parts.suffix(12).joined(separator: " ")
        }
        #endif
    }
}

#if DEBUG
struct RemoteTrailMarker: View {
    @ObservedObject private var trail = RemoteTrail.shared
    var body: some View {
        Text(trail.text.isEmpty ? "-" : trail.text)
            .font(.system(size: 1))
            .opacity(0.01)
            .allowsHitTesting(false)
            .accessibilityIdentifier("player.remote")
    }
}
#endif
