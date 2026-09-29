package com.tuvora.tvos.screens

import com.nuvio.app.features.iptv.SOURCE_TYPE_M3U_FILE
import com.nuvio.app.features.iptv.SOURCE_TYPE_M3U_URL
import com.nuvio.app.features.iptv.SOURCE_TYPE_STALKER
import com.nuvio.app.features.iptv.SOURCE_TYPE_XTREAM
import com.nuvio.app.features.iptv.XtreamAccount
import com.nuvio.app.features.iptv.XtreamFormInput
import com.nuvio.app.features.iptv.parseXtreamAccount
import kotlin.random.Random

/**
 * What the Apple TV "Add Playlist" form holds — NuvioTV's XtreamAddDialog fields
 * (ui/screens/settings/XtreamSettingsScreen.kt). Every field is a plain value so Swift can build it;
 * blank means "not entered".
 */
data class TvPlaylistForm(
    val sourceType: String,
    /** Xtream only: true = "Paste link" (one playlist URL), false = "Enter details". */
    val pasteLink: Boolean,
    val playlistUrl: String,
    val server: String,
    val username: String,
    val password: String,
    val name: String,
    val userAgent: String,
    val m3uUrl: String,
    val portalUrl: String,
    val macAddress: String,
    val stalkerUsername: String,
    val stalkerPassword: String,
    val serialNumber: String,
    val deviceId: String,
    val sendDeviceId: Boolean,
    val epgUrl: String,
    val autoRefreshHours: Int,
)

/**
 * The form's decisions, kept out of the screen so they test without UI: when Add is enabled, how the
 * fields become the shared [XtreamFormInput], and how a saved playlist pre-fills Edit.
 */
object TvPlaylistFormPolicy {
    /** NuvioTV XtreamAccount.AUTO_REFRESH_OPTIONS; 0 = off, 24 = the product default. */
    val autoRefreshOptions: List<Int> = listOf(0, 6, 12, 24, 48, 72)
    const val DEFAULT_AUTO_REFRESH_HOURS = 24

    /** NuvioTV autoRefreshLabel. */
    fun autoRefreshLabel(hours: Int): String = if (hours == 0) "Off" else "${hours}h"

    fun empty(sourceType: String = SOURCE_TYPE_XTREAM): TvPlaylistForm = TvPlaylistForm(
        sourceType = sourceType, pasteLink = false, playlistUrl = "", server = "", username = "",
        password = "", name = "", userAgent = "", m3uUrl = "", portalUrl = "", macAddress = "",
        stalkerUsername = "", stalkerPassword = "", serialNumber = "", deviceId = "", sendDeviceId = true,
        epgUrl = "", autoRefreshHours = DEFAULT_AUTO_REFRESH_HOURS,
    )

    /**
     * Whether Add/Save can be pressed. Apple TV has no document picker, so an M3U *file* playlist can
     * never be submitted here (NuvioTV shows the same "no file picker" note when none resolves).
     */
    fun canSubmit(form: TvPlaylistForm): Boolean = when (form.sourceType) {
        SOURCE_TYPE_M3U_URL -> form.m3uUrl.isNotBlank()
        SOURCE_TYPE_M3U_FILE -> false
        SOURCE_TYPE_STALKER -> form.portalUrl.isNotBlank() && form.macAddress.isNotBlank()
        else -> if (form.pasteLink) {
            form.playlistUrl.isNotBlank()
        } else {
            form.server.isNotBlank() && form.username.isNotBlank() && form.password.isNotBlank()
        }
    }

    /**
     * The shared form input, or null when a pasted Xtream link carries no username/password (the
     * caller then lets the repository report its own "couldn't read" error). Apple TV cannot do
     * per-playlist DNS (neither can iOS), so the provider stays "system".
     */
    internal fun toInput(form: TvPlaylistForm): XtreamFormInput? {
        var server = form.server
        var username = form.username
        var password = form.password
        if (form.sourceType == SOURCE_TYPE_XTREAM && form.pasteLink) {
            val parsed = parseXtreamAccount(form.playlistUrl.trim()) ?: return null
            server = parsed.baseUrl
            username = parsed.username
            password = parsed.password
        }
        val stalker = form.sourceType == SOURCE_TYPE_STALKER
        return XtreamFormInput(
            serverUrl = (if (stalker) form.portalUrl else server).trim(),
            username = if (stalker) "" else username.trim(),
            password = if (stalker) "" else password.trim(),
            name = form.name.trim().ifEmpty { null },
            epgUrl = form.epgUrl.trim().ifEmpty { null },
            dnsProvider = "system",
            autoRefreshHours = form.autoRefreshHours,
            sourceType = form.sourceType,
            m3uUrl = form.m3uUrl.trim(),
            userAgent = form.userAgent.trim().ifEmpty { null },
            macAddress = form.macAddress.trim(),
            stalkerUsername = form.stalkerUsername.trim().ifEmpty { null },
            stalkerPassword = form.stalkerPassword.trim().ifEmpty { null },
            serialNumber = form.serialNumber.trim().ifEmpty { null },
            deviceId = form.deviceId.trim().ifEmpty { null },
            sendDeviceId = form.sendDeviceId,
        )
    }

    /** Edit pre-fill: the saved playlist's identity and options, in the fields its source type shows. */
    fun fromAccount(account: XtreamAccount): TvPlaylistForm {
        val base = empty(account.sourceType).copy(
            name = account.name,
            userAgent = account.userAgent.orEmpty(),
            epgUrl = account.epgUrl.orEmpty(),
            autoRefreshHours = account.autoRefreshHours,
        )
        return when (account.sourceType) {
            SOURCE_TYPE_M3U_URL -> base.copy(m3uUrl = account.baseUrl)
            SOURCE_TYPE_STALKER -> base.copy(
                portalUrl = account.baseUrl,
                macAddress = account.macAddress,
                stalkerUsername = account.stalkerUsername.orEmpty(),
                stalkerPassword = account.stalkerPassword.orEmpty(),
                serialNumber = account.serialNumber.orEmpty(),
                deviceId = account.deviceId.orEmpty(),
                sendDeviceId = account.sendDeviceId,
            )
            SOURCE_TYPE_M3U_FILE -> base
            else -> base.copy(server = account.baseUrl, username = account.username, password = account.password)
        }
    }

    /** A virtual STB MAC in Infomir's 00:1A:79 range (NuvioTV randomStbMac). */
    fun randomStbMac(): String = randomStbMac(Random.Default)

    internal fun randomStbMac(random: Random): String =
        "00:1A:79:" + (0 until 3).joinToString(":") { random.nextInt(0, 256).toString(16).uppercase().padStart(2, '0') }
}
