package com.nuvio.app.features.profiles

// The Siri Remote has no haptic engine; the tvOS focus engine gives its own motion feedback when a
// profile tile gains focus. Named gap: these calls intentionally do nothing on Apple TV.
internal actual object ProfileHoverHapticFeedback {
    actual fun prepare() = Unit
    actual fun perform() = Unit
    actual fun release() = Unit
}
