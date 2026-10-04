import Foundation
import TuvoraCore

/// Test-hook breadcrumbs ("SMOKE …") read by the smoke launches and XCUITest runs. The hooks
/// themselves are Debug-only (`AppArguments` is empty in Release), so the lines are too — a Release
/// build must not write player state or error text to the device log. Every line is redacted (B116):
/// player errors quote stream URLs, which carry provider credentials.
func smokeLog(_ format: String, _ args: CVarArg...) {
    #if DEBUG
    let line = String(format: format, arguments: args)
    NSLog("%@", LogRedaction.shared.text(message: line))
    #endif
}
