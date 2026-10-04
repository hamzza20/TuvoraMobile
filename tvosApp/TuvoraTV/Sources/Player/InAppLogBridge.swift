import Foundation
import TuvoraCore

/// Minimal logging shim for the ported Picture-in-Picture code.
///
/// The PiP implementation was ported from the GPL-3.0 fork `luqmanfadlli/NuvioMobile-iOS`, which
/// carries its own in-app log viewer behind this API. We do not have that viewer, but the ported
/// files are ~1,500 lines and we will want to re-sync them against that fork when it moves, so the
/// call sites are kept byte-identical and adapted here instead of edited in place.
///
/// Maps onto the `print("[Tag] …")` convention the rest of this bridge already uses. Every line goes
/// through the shared Kotlin `LogRedaction` policy first (B116 — URLs carry provider credentials).
final class InAppLogBridge {
    static let shared = InAppLogBridge()

    private init() {}

    func debug(tag: String, message: String) {
        #if DEBUG
        print("[\(tag)] \(LogRedaction.shared.text(message: message))")
        #endif
    }

    func info(tag: String, message: String) {
        print("[\(tag)] \(LogRedaction.shared.text(message: message))")
    }

    func warn(tag: String, message: String) {
        print("[\(tag)] WARN \(LogRedaction.shared.text(message: message))")
    }

    func error(tag: String, message: String) {
        print("[\(tag)] ERROR \(LogRedaction.shared.text(message: message))")
    }

    /// mpv's own log callback, which already carries its level and module prefix.
    func mpv(platform: String, prefix: String, level: String, message: String) {
        print("[MPV/\(platform)][\(prefix)] \(level): \(LogRedaction.shared.text(message: message))", terminator: "")
    }
}
