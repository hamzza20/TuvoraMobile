package org.jetbrains.compose.resources

import platform.Foundation.NSBundle

// The Apple TV app bundles <lang>.lproj/Tuvora.strings generated from composeResources, so the
// system picks the user's language the same way it does for every other tvOS app.
internal actual fun localizedText(key: String, default: String): String =
    NSBundle.mainBundle.localizedStringForKey(key, value = default, table = "Tuvora")
