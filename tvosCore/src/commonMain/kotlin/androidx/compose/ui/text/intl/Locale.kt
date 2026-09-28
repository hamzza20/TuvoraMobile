// tvOS build only: shared logic reads `Locale.current.toLanguageTag()` (catalog sync keys, date
// formatting). Compose UI has no tvOS artifact; this supplies that one call from the system locale.
package androidx.compose.ui.text.intl

class Locale internal constructor(private val tag: String) {
    fun toLanguageTag(): String = tag

    companion object {
        val current: Locale get() = Locale(systemLanguageTag())
    }
}

internal expect fun systemLanguageTag(): String
