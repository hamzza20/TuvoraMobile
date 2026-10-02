import Foundation

/// The launch arguments the app reads (every `-smoke*` simulator/UI-test hook and `-traceArtwork`). They exist only
/// in a DEBUG build: a release build (TestFlight, App Store) sees none, whatever it was launched with, so no hook
/// can be reached there.
enum AppArguments {
    static let list: [String] = {
        #if DEBUG
        return ProcessInfo.processInfo.arguments
        #else
        return []
        #endif
    }()
}
