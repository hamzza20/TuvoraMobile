package androidx.compose.ui.text.intl

import platform.Foundation.NSLocale
import platform.Foundation.currentLocale
import platform.Foundation.localeIdentifier

/** BCP-47 form of the device locale, e.g. "en-GB" (NSLocale uses "en_GB"). */
internal actual fun systemLanguageTag(): String = NSLocale.currentLocale.localeIdentifier.replace('_', '-')
