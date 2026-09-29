package com.tuvora.tvos.screens

import com.nuvio.app.features.iptv.SOURCE_TYPE_M3U_URL
import com.nuvio.app.features.iptv.SOURCE_TYPE_STALKER
import com.nuvio.app.features.iptv.SOURCE_TYPE_XTREAM
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * "Add from phone" (NuvioTV P5 IPTV pairing, core/iptv/IptvPairingPayload.kt), pure: the 6-char code,
 * the device secret that never leaves the TV, the QR's web URL, and the submitted payload → the
 * playlist form the settings "Add" path saves (so a paired playlist is verified and stored exactly
 * like a typed one).
 */
object TvIptvPairingPolicy {
    /** NuvioTV BuildConfig.IPTV_PAIRING_WEB_BASE_URL default. */
    const val WEB_BASE_URL = "https://tuvora.co/iptv-pairing/"
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    val CODE_REGEX = Regex("^[A-Z0-9]{6}$")

    /** The 6-char code from [bytes] (≥ 6 secure random bytes; the facade passes a random UUID's). */
    fun codeFrom(bytes: ByteArray): String = buildString { repeat(6) { append(ALPHABET[(bytes[it].toInt() and 0xFF) % ALPHABET.length]) } }

    fun webUrl(baseUrl: String, code: String): String {
        val trimmed = baseUrl.trim()
        return "$trimmed${if (trimmed.contains('?')) "&" else "?"}code=$code"
    }

    /** "tuvora.co/iptv-pairing" for the manual line under the QR. */
    fun displayUrl(baseUrl: String): String =
        baseUrl.trim().removePrefix("https://").removePrefix("http://").substringBefore('?').trimEnd('/')

    /** "Code expires in 04:59". */
    fun expiresText(remainingMs: Long): String {
        val s = (remainingMs / 1000).coerceAtLeast(0)
        return "Code expires in ${(s / 60).toString().padStart(2, '0')}:${(s % 60).toString().padStart(2, '0')}"
    }

    /**
     * The submitted payload (a `sync_push_iptv_playlists`-shaped row) as the add form, or null when it
     * is unusable. Xtream: base_url + username + password; URL (`url`/`m3u_url`): the playlist URL from
     * `url` or `base_url`; Stalker: portal_url/base_url + mac_address and the optional portal fields.
     */
    fun payloadToForm(payload: JsonElement?): TvPlaylistForm? {
        val obj = payload as? JsonObject ?: return null
        val sourceType = obj.string("source_type")?.takeIf { it.isNotBlank() } ?: return null
        val base = TvPlaylistFormPolicy.empty().copy(
            name = obj.string("name").orEmpty(),
            epgUrl = obj.string("epg_url").orEmpty(),
            autoRefreshHours = obj.int("auto_refresh_hours") ?: TvPlaylistFormPolicy.DEFAULT_AUTO_REFRESH_HOURS,
            userAgent = obj.string("user_agent").orEmpty(),
        )
        return when (sourceType) {
            SOURCE_TYPE_XTREAM -> {
                val server = obj.string("base_url")?.takeIf { it.isNotBlank() } ?: return null
                val username = obj.string("username")?.takeIf { it.isNotBlank() } ?: return null
                val password = obj.string("password") ?: return null
                base.copy(sourceType = SOURCE_TYPE_XTREAM, server = server, username = username, password = password)
            }
            "url", SOURCE_TYPE_M3U_URL -> {
                val url = (obj.string("url") ?: obj.string("base_url"))?.takeIf { it.isNotBlank() } ?: return null
                base.copy(sourceType = SOURCE_TYPE_M3U_URL, m3uUrl = url,
                    userAgent = base.userAgent.ifBlank { obj.string("username").orEmpty() })
            }
            SOURCE_TYPE_STALKER -> {
                val raw = (obj.string("portal_url") ?: obj.string("base_url"))?.takeIf { it.isNotBlank() } ?: return null
                val mac = obj.string("mac_address")?.takeIf { it.isNotBlank() } ?: return null
                base.copy(
                    sourceType = SOURCE_TYPE_STALKER,
                    portalUrl = if (raw.startsWith("http")) raw else "http://$raw",
                    macAddress = mac,
                    stalkerUsername = obj.string("stalker_username").orEmpty(),
                    stalkerPassword = obj.string("stalker_password").orEmpty(),
                    serialNumber = obj.string("serial_number").orEmpty(),
                    deviceId = obj.string("device_id").orEmpty(),
                    sendDeviceId = obj.bool("send_device_id") ?: true,
                )
            }
            else -> null
        }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
}
