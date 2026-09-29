package com.tuvora.tvos.screens

import com.nuvio.app.features.library.LibrarySourceMode
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.WatchProgressSource
import com.nuvio.app.features.tracking.effectiveLibrarySourceMode
import com.nuvio.app.features.tracking.effectiveWatchProgressSource
import com.nuvio.app.features.trakt.MoreLikeThisSourcePreference

/** One step of a device-code sign-in poll (Trakt `/oauth/device/token`, Simkl `/oauth/pin/{code}`). */
sealed class TvDevicePoll {
    data object Approved : TvDevicePoll()
    data object Pending : TvDevicePoll()
    data class SlowDown(val intervalSeconds: Int) : TvDevicePoll()
    data object Expired : TvDevicePoll()
    data object Denied : TvDevicePoll()
    data object AlreadyUsed : TvDevicePoll()
    data object Invalid : TvDevicePoll()
    data class Failed(val status: Int) : TvDevicePoll()

    /** Whether the poll loop keeps going after this step. */
    val keepsPolling: Boolean get() = this is Pending || this is SlowDown
}

/**
 * Settings → Tracking's decisions, as NuvioTV makes them (TraktAuthService, SimklAuthRepository,
 * TrackingSettingsScreen/ViewModel). Pure, so the device flows and the source pickers test without
 * the network. Apple TV has no browser, so Trakt and Simkl connect by device code, like NuvioTV —
 * not by the phone apps' OAuth redirect.
 */
object TvTrackingPolicy {
    const val TRAKT_ACTIVATION_URL = "https://trakt.tv/activate"
    const val DEFAULT_POLL_INTERVAL_SECONDS = 5
    private const val MAX_POLL_INTERVAL_SECONDS = 60
    private val SIMKL_PIN = Regex("[A-Za-z0-9]{4,12}")

    /** The QR opens the activation page with the code filled in (NuvioTV TraktAccountDialog). */
    fun traktQrUrl(userCode: String?, verificationUrl: String?): String =
        userCode?.takeIf(String::isNotBlank)?.let { "$TRAKT_ACTIVATION_URL/$it" }
            ?: verificationUrl?.takeIf(String::isNotBlank)
            ?: TRAKT_ACTIVATION_URL

    /** Trakt's device-token status codes (TraktAuthService.pollDeviceToken). */
    fun traktPoll(status: Int, currentIntervalSeconds: Int): TvDevicePoll = when (status) {
        200 -> TvDevicePoll.Approved
        400 -> TvDevicePoll.Pending
        404 -> TvDevicePoll.Invalid
        409 -> TvDevicePoll.AlreadyUsed
        410 -> TvDevicePoll.Expired
        418 -> TvDevicePoll.Denied
        429 -> TvDevicePoll.SlowDown((currentIntervalSeconds.coerceAtLeast(1) + 5).coerceAtMost(MAX_POLL_INTERVAL_SECONDS))
        else -> TvDevicePoll.Failed(status)
    }

    /** Simkl's PIN poll body: OK + token = approved, KO = still waiting, a device_code = invalidated. */
    fun simklPoll(result: String?, accessToken: String?, deviceCode: String?): TvDevicePoll = when {
        !deviceCode.isNullOrBlank() -> TvDevicePoll.Invalid
        result == "OK" && !accessToken.isNullOrBlank() -> TvDevicePoll.Approved
        result == "KO" -> TvDevicePoll.Pending
        else -> TvDevicePoll.Failed(0)
    }

    fun isValidSimklPin(code: String?): Boolean = code != null && SIMKL_PIN.matches(code)

    /** NuvioTV formatTrackingDuration: "1d 2h", "2h 5m", "4m 9s", "7s". */
    fun formatDuration(valueMs: Long): String {
        val total = (valueMs / 1000L).coerceAtLeast(0L)
        val days = total / 86_400
        val hours = (total / 3_600) % 24
        val minutes = (total / 60) % 60
        val seconds = total % 60
        return when {
            days > 0 -> "${days}d ${hours}h"
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }

    /** Library sources offered: Tuvora's own library plus every connected service. */
    fun librarySources(trakt: Boolean, simkl: Boolean, mdblist: Boolean): List<LibrarySourceMode> = buildList {
        add(LibrarySourceMode.LOCAL)
        if (trakt) add(LibrarySourceMode.TRAKT)
        if (simkl) add(LibrarySourceMode.SIMKL)
        if (mdblist) add(LibrarySourceMode.MDBLIST)
    }

    /** Watch-progress sources offered: Tuvora Sync plus every connected service. */
    fun watchProgressSources(trakt: Boolean, simkl: Boolean, mdblist: Boolean): List<WatchProgressSource> = buildList {
        add(WatchProgressSource.NUVIO_SYNC)
        if (trakt) add(WatchProgressSource.TRAKT)
        if (simkl) add(WatchProgressSource.SIMKL)
        if (mdblist) add(WatchProgressSource.MDBLIST)
    }

    fun moreLikeThisSources(trakt: Boolean, simkl: Boolean): List<MoreLikeThisSourcePreference> = buildList {
        add(MoreLikeThisSourcePreference.TMDB)
        if (trakt) add(MoreLikeThisSourcePreference.TRAKT)
        if (simkl) add(MoreLikeThisSourcePreference.SIMKL)
    }

    private fun connected(trakt: Boolean, simkl: Boolean, mdblist: Boolean): (TrackingProviderId) -> Boolean = { id ->
        when (id) {
            TrackingProviderId.TRAKT -> trakt
            TrackingProviderId.SIMKL -> simkl
            TrackingProviderId.MDBLIST -> mdblist
        }
    }

    /** The shared fallback rules (a source whose service is disconnected reads as Tuvora's own). */
    fun effectiveLibrary(saved: LibrarySourceMode, trakt: Boolean, simkl: Boolean, mdblist: Boolean): LibrarySourceMode =
        effectiveLibrarySourceMode(saved, connected(trakt, simkl, mdblist))

    fun effectiveProgress(saved: WatchProgressSource, trakt: Boolean, simkl: Boolean, mdblist: Boolean): WatchProgressSource =
        effectiveWatchProgressSource(saved, connected(trakt, simkl, mdblist))

    /** What the row shows: a saved choice whose service is gone reads as its fallback. */
    fun effectiveMoreLikeThis(saved: MoreLikeThisSourcePreference, trakt: Boolean, simkl: Boolean): MoreLikeThisSourcePreference =
        saved.takeIf { it in moreLikeThisSources(trakt, simkl) } ?: MoreLikeThisSourcePreference.TMDB

    /** The line the device dialog shows for a poll that ended the flow (NuvioTV strings). */
    fun traktMessage(poll: TvDevicePoll): String? = when (poll) {
        TvDevicePoll.Expired -> "Device code expired. Start again."
        TvDevicePoll.AlreadyUsed -> "Device code already used. Start again."
        TvDevicePoll.Denied -> "Authorization denied on Trakt."
        TvDevicePoll.Invalid -> "Invalid device code"
        is TvDevicePoll.Failed -> "Token polling failed (${poll.status})"
        else -> null
    }

    fun simklMessage(poll: TvDevicePoll): String? = when (poll) {
        TvDevicePoll.Expired, TvDevicePoll.Invalid -> "Simkl code expired. Start again."
        is TvDevicePoll.Failed -> "Unable to reach Simkl. Try again."
        else -> null
    }

    /** NuvioTV's Continue Watching window choices; 0 = all history. */
    val continueWatchingWindows: List<Int> = listOf(14, 30, 60, 90, 180, 365, 0)
}
