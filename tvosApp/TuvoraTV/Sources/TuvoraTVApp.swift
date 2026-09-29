import SwiftUI
import TuvoraCore

/// Apple TV entry point. Order matters (TvAppGraph.kt): analytics and the memory-pressure source
/// first, then the Kotlin bootstrap, before any screen reads shared state.
@main
struct TuvoraTVApp: App {
    init() {
        AnalyticsSink.shared.register { event, properties in
            // PostHog is wired in a later Phase 2 step; until then events go to the device log.
            NSLog("[analytics] %@ %@", event, properties.description)
        }
        MemoryPressureObserver.shared.start()
        TvAppGraph.shared.start()
        PlayerEngines.register()
        NSLog("SMOKE strings lang=%@ auth_sign_up_failed=%@", Locale.preferredLanguages.first ?? "?",
              Bundle.main.localizedString(forKey: "auth_sign_up_failed", value: "MISSING", table: "Tuvora"))
    }

    var body: some Scene {
        WindowGroup { RootView() }
    }
}

/// Feeds AppMemory from the system's memory-pressure events, as the iPhone app does (iOSApp.swift).
final class MemoryPressureObserver {
    static let shared = MemoryPressureObserver()
    private var source: DispatchSourceMemoryPressure?

    func start() {
        guard source == nil else { return }
        let source = DispatchSource.makeMemoryPressureSource(eventMask: [.normal, .warning, .critical], queue: .main)
        source.setEventHandler {
            let event = source.data
            if !event.intersection([.warning, .critical]).isEmpty {
                AppMemory.shared.onPressure()
            } else if event.contains(.normal) {
                AppMemory.shared.onRelax()
            }
        }
        source.activate()
        self.source = source
    }
}
