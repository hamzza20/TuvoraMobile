package com.tuvora.tvos.screens

import com.nuvio.app.features.iptv.XtreamItemRegistry
import com.nuvio.app.features.iptv.XtreamLiveRecent
import com.nuvio.app.features.iptv.XtreamLiveRecents
import com.nuvio.app.features.iptv.XtreamProgram
import com.nuvio.app.features.livetv.LiveGuideChannel
import com.nuvio.app.features.livetv.LiveTvData
import kotlinx.coroutines.flow.StateFlow

/**
 * The live guide's data, per playlist: every channel (with category, pin and catch-up flags and the
 * personalization overlay applied, via LiveTvData.guideChannels) and each row's schedule, fetched only
 * for rows on screen — one request per composed row, as on the other clients.
 */
object TvLiveGuide {
    suspend fun channels(accountId: String): List<LiveGuideChannel> =
        // guideChannels is keyed by a channel id only to find its playlist; any live id of it works.
        LiveTvData.guideChannels(XtreamItemRegistry.liveId(accountId, 0))

    suspend fun programmes(contentId: String): List<XtreamProgram> = LiveTvData.programmes(contentId, limit = 8)

    val recents: StateFlow<List<XtreamLiveRecent>>
        get() {
            XtreamLiveRecents.ensureLoaded()
            return XtreamLiveRecents.recents
        }

    fun nowMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
}
