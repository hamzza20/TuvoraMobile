import SwiftUI
import TuvoraCore
import PostHog

/// Apple TV entry point. Order matters (TvAppGraph.kt): analytics and the memory-pressure source
/// first, then the Kotlin bootstrap, before any screen reads shared state.
@main
struct TuvoraTVApp: App {
    init() {
        // Before anything reads storage: route the shared code's defaults to Apple TV's tiers.
        TieredUserDefaults.install()
        // PostHog with crash autocapture, the iPhone app's scrubbing and its consent switch.
        TuvoraTelemetry.start()
        AnalyticsSink.shared.register { event, properties in
            PostHogSDK.shared.capture(event, properties: properties)
        }
        MemoryPressureObserver.shared.start()
        TvAppGraph.shared.start()
        PlayerEngines.register()
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
