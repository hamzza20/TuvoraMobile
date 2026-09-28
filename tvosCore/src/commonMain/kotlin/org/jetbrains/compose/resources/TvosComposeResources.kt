// The slice of Compose Multiplatform resources that shared LOGIC calls, for the tvOS build only.
// Compose resources has no tvOS artifact; phone, desktop and Android TV keep using the real library.
// Accessors (`Res.string.*`) are generated from the same XML by :tvosCore:generateTvosResources.
package org.jetbrains.compose.resources

import androidx.compose.runtime.Composable

class StringResource(val key: String, val defaultText: String)
class PluralStringResource(val key: String, val defaultOne: String, val defaultOther: String)
class DrawableResource(val key: String)
class FontResource(val key: String)

/** Localised text for [key] from the app bundle's generated tables, or [default] when absent. */
internal expect fun localizedText(key: String, default: String): String

/** Android/Compose format placeholders: `%1$s`, `%2$d`, then plain `%s` / `%d` in order. */
internal fun formatResource(template: String, args: Array<out Any>): String {
    var out = Regex("%(\\d+)\\$[sdf]").replace(template) { m ->
        args.getOrNull(m.groupValues[1].toInt() - 1)?.toString() ?: m.value
    }
    var next = 0
    out = Regex("%[sdf]").replace(out) { m -> args.getOrNull(next++)?.toString() ?: m.value }
    return out.replace("%%", "%")
}

suspend fun getString(resource: StringResource): String = localizedText(resource.key, resource.defaultText)

suspend fun getString(resource: StringResource, vararg formatArgs: Any): String =
    formatResource(localizedText(resource.key, resource.defaultText), formatArgs)

suspend fun getPluralString(resource: PluralStringResource, quantity: Int, vararg formatArgs: Any): String =
    formatResource(if (quantity == 1) resource.defaultOne else resource.defaultOther, formatArgs)

@Composable
fun stringResource(resource: StringResource): String = localizedText(resource.key, resource.defaultText)

@Composable
fun stringResource(resource: StringResource, vararg formatArgs: Any): String =
    formatResource(localizedText(resource.key, resource.defaultText), formatArgs)

@Composable
fun pluralStringResource(resource: PluralStringResource, quantity: Int, vararg formatArgs: Any): String =
    formatResource(if (quantity == 1) resource.defaultOne else resource.defaultOther, formatArgs)
