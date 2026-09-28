import SwiftUI
import ComposeApp
import PostHog

private let crashReportsEnabledKey = "sentry_enabled"
private let geoIpDisableProperty = "$geoip_disable"
private let sensitivePropertyNames: Set<String> = [
    "url", "uri", "href", "referrer", "code", "state", "token", "access_token",
    "refresh_token", "authorization", "password", "secret", "cookie", "api_key"
]

/// Mirrors `resolveDiagnosticsEnabled` in the shared Kotlin: absent means on, a stored value wins.
///
/// This drives PostHog's opt-out, so it governs every event the app sends, not only crashes. It
/// shipped defaulting to off in build 114, and the platform went dark — two weeks later only one
/// iOS user on a post-114 build was reporting at all, against 75 on builds that predated the gate.
/// Someone who has explicitly turned it off keeps that choice.
private func crashReportsEnabled() -> Bool {
    let defaults = UserDefaults.standard
    guard defaults.object(forKey: crashReportsEnabledKey) != nil else { return true }
    return defaults.bool(forKey: crashReportsEnabledKey)
}

private func isSensitiveProperty(_ key: String) -> Bool {
    let normalized = key.lowercased().replacingOccurrences(of: "-", with: "_")
    return sensitivePropertyNames.contains(normalized)
        || normalized.hasSuffix("_url")
        || normalized.hasSuffix("_uri")
        || normalized.contains("token")
        || normalized.contains("password")
        || normalized.contains("secret")
        || normalized.contains("authorization")
        || normalized.contains("cookie")
}

private func sanitizedPostHogString(_ value: String) -> String {
    let withoutURLs = value.replacingOccurrences(
        of: #"(?i)\b[a-z][a-z0-9+.-]*://\S+"#,
        with: "[REDACTED_URL]",
        options: .regularExpression
    )
    let withoutAuthorization = withoutURLs.replacingOccurrences(
        of: #"(?i)\b(?:bearer|basic)\s+[a-z0-9._~+/=-]+"#,
        with: "[REDACTED_AUTH]",
        options: .regularExpression
    )
    return withoutAuthorization.replacingOccurrences(
        of: #"(?i)\b(code|state|access_token|refresh_token|token|authorization|password|secret)=([^\s&]+)"#,
        with: "$1=[REDACTED]",
        options: .regularExpression
    )
}

private func sanitizedPostHogValue(_ value: Any) -> Any {
    if let string = value as? String {
        return sanitizedPostHogString(string)
    }
    if let dictionary = value as? [String: Any] {
        return sanitizedPostHogProperties(dictionary)
    }
    if let array = value as? [Any] {
        return array.map(sanitizedPostHogValue)
    }
    return value
}

private func sanitizedPostHogProperties(_ properties: [String: Any]) -> [String: Any] {
    var sanitized: [String: Any] = [:]
    for (key, value) in properties where !isSensitiveProperty(key) {
        sanitized[key] = sanitizedPostHogValue(value)
    }
    sanitized[geoIpDisableProperty] = true
    return sanitized
}

private final class PostHogConsentObserver {
    static let shared = PostHogConsentObserver()
    private var observer: NSObjectProtocol?

    func start() {
        guard observer == nil else { return }
        observer = NotificationCenter.default.addObserver(
            forName: UserDefaults.didChangeNotification,
            object: nil,
            queue: .main
        ) { _ in
            if crashReportsEnabled() {
                PostHogSDK.shared.optIn()
            } else {
                PostHogSDK.shared.optOut()
            }
        }
    }
}

/// Feeds the OS's real-time memory-pressure events into the shared Kotlin memory tier
/// (`AppMemory`): warning/critical escalate (and trim every registered cache), normal
/// relaxes. This is the dynamic half of the iOS probe — the static half is
/// ProcessInfo.physicalMemory read Kotlin-side. os_proc_available_memory() needs a
/// cinterop the shared framework doesn't have, so the event source lives here in Swift.
private final class MemoryPressureObserver {
    static let shared = MemoryPressureObserver()
    private var source: DispatchSourceMemoryPressure?

    func start() {
        guard source == nil else { return }
        let source = DispatchSource.makeMemoryPressureSource(
            eventMask: [.normal, .warning, .critical],
            queue: .main
        )
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

@main
struct iOSApp: App {
    @UIApplicationDelegateAdaptor(OrientationLockAppDelegate.self) private var appDelegate

    init() {
        #if DEBUG
        // stdout is block-buffered when attached to `devicectl --console` (not a TTY), so the last
        // few KB of logs — the ones that matter when the app hangs and gets SIGKILLed — were lost.
        setvbuf(stdout, nil, _IOLBF, 0)
        #endif
        // Public client-side key — safe to ship in the binary.
        let config = PostHogConfig(
            projectToken: "phc_o824qv3fcxKW9NvF4K6mYKX3rScK5CBQzrSx4RQ5b6ye",
            host: "https://us.i.posthog.com"
        )
        // Capture crashes (Mach exceptions, POSIX signals, NSExceptions) as $exception events.
        config.errorTrackingConfig.autoCapture = true
        config.optOut = !crashReportsEnabled()
        config.captureApplicationLifecycleEvents = false
        config.captureScreenViews = false
        config.sendFeatureFlagEvent = false
        config.preloadFeatureFlags = false
        config.surveys = false
        config.sessionReplay = false
        config.sessionReplayConfig.captureNetworkTelemetry = false
        config.sessionReplayConfig.captureLogs = false
        config.sessionReplayConfig.screenshotMode = false
        config.tracingHeaders = []
        config.logs.setBeforeSend { _ in nil }
        config.setBeforeSend { event in
            if event.event.caseInsensitiveCompare("Deep Link Opened") == .orderedSame {
                return nil
            }
            event.properties = sanitizedPostHogProperties(event.properties)
            return event
        }
        // Upload queued events quickly after launch: a crash queued by the previous
        // run must ship before the user navigates back into whatever crashed
        // (the default 30s starved uploads during crash-loops).
        config.flushIntervalSeconds = 10
        PostHogSDK.shared.setup(config)
        // Lets shared Kotlin code capture without linking a PostHog SDK into the framework.
        // Registered before any shared code can run, so no early event is dropped.
        AnalyticsSink.shared.register { event, properties in
            PostHogSDK.shared.capture(event, properties: properties)
        }
        PostHogSDK.shared.register([geoIpDisableProperty: true])
        if crashReportsEnabled() {
            PostHogSDK.shared.optIn()
        } else {
            PostHogSDK.shared.optOut()
        }
        PostHogConsentObserver.shared.start()
        // Registered next to the AnalyticsSink bridge, before shared code can allocate:
        // pressure events trim the Kotlin-side budget registry from the very first screen.
        MemoryPressureObserver.shared.start()

        if #available(iOS 14.0, *) {
            // MetricKit supplies delayed hangs and resource failures that exception
            // autocapture cannot observe. The singleton remains subscribed for app life.
            MetricKitReliabilityReporter.shared.start()
        }
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .preferredColorScheme(.dark)
                .onOpenURL { url in
                    AppUrlBridgeKt.handleAppUrl(url: url.absoluteString)
                }
        }
    }
}
