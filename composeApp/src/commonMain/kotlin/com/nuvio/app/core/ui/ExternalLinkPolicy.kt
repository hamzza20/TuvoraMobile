package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.external_link_open_failed_copied
import org.jetbrains.compose.resources.stringResource

/**
 * Decides what happens when the app is asked to open a web link.
 *
 * The platform opener can refuse: on Android, Compose's `LocalUriHandler.openUri` rethrows when no
 * activity handles ACTION_VIEW (no browser installed); on desktop, the JVM's `Desktop.browse`
 * throws when there is no default browser (common on Linux) or BROWSE is unsupported. We try
 * rather than pre-check, and turn any refusal into a copy-the-link fallback — never a crash.
 * Twin of TV's `ExternalLinkPolicy` (which falls back to a QR hand-off instead).
 */
internal object ExternalLinkPolicy {
    sealed interface Outcome {
        data object Opened : Outcome
        data object Ignored : Outcome
        data class CopyFallback(val url: String) : Outcome
    }

    private val emailShape = Regex("""^[A-Za-z0-9.!#$'*+/=_~-]+@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}$""")

    /** A plain address (no `?` headers, quotes or brackets): the only shape a `mailto:` link is built from. */
    fun isPlainEmailAddress(value: String): Boolean = value.length <= 254 && emailShape.matches(value)

    /**
     * `host` or `host:port` of a public-looking host: ASCII letters, digits and hyphens in at least two
     * dot-separated labels with an alphabetic top level, an optional numeric port (1-65535). IP literals (v4 and
     * v6), single-label hosts (localhost, intranet names), non-ASCII and punycode (`xn--`, IDN) hosts are refused.
     */
    fun isPublicLookingAuthority(authority: String): Boolean {
        val host = authority.substringBefore(':')
        if (':' in authority) {
            val port = authority.substringAfter(':')
            if (port.isEmpty() || port.length > 5 || !port.all { it in '0'..'9' } || port.toInt() !in 1..65535) return false
        }
        if (host.isEmpty() || host.length > 253) return false
        val labels = host.split('.')
        if (labels.size < 2) return false
        for (label in labels) {
            if (label.isEmpty() || label.length > 63) return false
            if (label.startsWith("-") || label.endsWith("-")) return false
            if (!label.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }) return false
            if (label.startsWith("xn--", ignoreCase = true)) return false
        }
        val tld = labels.last()
        return tld.length >= 2 && tld.all { it in 'a'..'z' || it in 'A'..'Z' }
    }

    /**
     * A provider-supplied contact link may be opened only when it is `https://` on a public-looking host or a
     * plain `mailto:` address (no `?` headers): the text is a provider's, so the opener is never handed
     * `javascript:`, `intent:`, `file:`, `tel:`, a custom scheme, plain http, an IP literal or an intranet name
     * (security L10). Other app links keep using [open], unchanged.
     */
    fun isSafeContactLink(url: String): Boolean {
        val s = url.trim()
        if (s.isEmpty() || s.any { it.code <= 0x20 || it.code in 0x7f..0x9f || it in "<>\"\\^`|" }) return false
        if (s.startsWith("mailto:", ignoreCase = true)) {
            val address = s.substring("mailto:".length)
            return address.isNotEmpty() && '?' !in address && ',' !in address && ';' !in address && '%' !in address &&
                address.count { it == '@' } == 1 && isPlainEmailAddress(address)
        }
        if (!s.startsWith("https://", ignoreCase = true)) return false
        val authority = s.substring("https://".length).takeWhile { it != '/' && it != '?' && it != '#' }
        return authority.isNotEmpty() && '@' !in authority && '%' !in authority && isPublicLookingAuthority(authority)
    }

    /** [open], but only for a link [isSafeContactLink] accepts; anything else is ignored (never launched). */
    fun openContact(url: String, launch: (String) -> Unit): Outcome =
        if (isSafeContactLink(url)) open(url, launch) else Outcome.Ignored

    fun open(url: String, launch: (String) -> Unit): Outcome {
        val target = url.trim()
        if (target.isEmpty()) return Outcome.Ignored
        return try {
            launch(target)
            Outcome.Opened
        } catch (_: Exception) {
            Outcome.CopyFallback(target)
        }
    }
}

/**
 * The app's one safe way to open an external link from UI. Tries the platform browser; if that
 * fails, copies the link to the clipboard and says so in the app toast. Use this instead of a bare
 * `LocalUriHandler.current.openUri(...)`, which crashes on devices without a browser.
 */
@Composable
fun rememberSafeUriOpener(): (String) -> Unit {
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    val failedTemplate = stringResource(Res.string.external_link_open_failed_copied)
    return remember(uriHandler, clipboard, failedTemplate) {
        { url ->
            when (val outcome = ExternalLinkPolicy.open(url) { uriHandler.openUri(it) }) {
                is ExternalLinkPolicy.Outcome.CopyFallback -> {
                    runCatching { clipboard.setText(AnnotatedString(outcome.url)) }
                    NuvioToastController.show(
                        message = failedTemplate.replace("%1\$s", outcome.url),
                        durationMillis = 5000L,
                    )
                }
                ExternalLinkPolicy.Outcome.Opened,
                ExternalLinkPolicy.Outcome.Ignored,
                -> Unit
            }
        }
    }
}
