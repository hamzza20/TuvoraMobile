package com.tuvora.tvos.screens

import com.nuvio.app.core.network.NetworkCondition
import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.features.iptv.ChannelEpg
import com.nuvio.app.features.iptv.XtreamHubRepository
import com.nuvio.app.features.iptv.XtreamHubSection
import com.nuvio.app.features.iptv.XtreamHubUiState
import com.nuvio.app.features.livetv.LiveTvData
import com.nuvio.app.features.player.PlayerLaunch
import com.nuvio.app.features.profiles.ProfileRepository
import com.tuvora.tvos.player.TvPlayerSession
import com.tuvora.tvos.player.TvResolvedSource
import kotlinx.coroutines.flow.StateFlow

/**
 * Apple TV's IPTV browse: playlists → categories → items, over the same XtreamHubRepository the
 * phone's hub uses. [section] picks Live, Movies or Series. Live channels play straight from here;
 * movies and series open Details like any catalog title.
 */
object TvIptvBrowse {
    val state: StateFlow<XtreamHubUiState> get() = XtreamHubRepository.uiState
    val epg: StateFlow<Map<String, ChannelEpg>> get() = XtreamHubRepository.epg

    fun open(section: XtreamHubSection) {
        XtreamHubRepository.ensureLoaded()
        XtreamHubRepository.selectSection(section)
    }

    fun selectPlaylist(accountId: String) = XtreamHubRepository.selectAccount(accountId)
    fun loadCategory(categoryId: String) = XtreamHubRepository.loadCategory(categoryId)
    fun loadMore(categoryId: String) = XtreamHubRepository.loadMore(categoryId)
    fun retry() = XtreamHubRepository.retryCategories()
    fun ensureEpg(contentId: String) = XtreamHubRepository.ensureEpg(contentId)

    val isOffline: Boolean
        get() = NetworkStatusRepository.uiState.value.condition == NetworkCondition.NoInternet

    /**
     * Resolves a live channel and opens a player session, or null when the channel has no playable
     * address (the phone's LiveChannelLaunchPolicy "unavailable" outcome) — the screen says so.
     */
    suspend fun playChannel(contentId: String, name: String, logo: String?): TvPlayerSession? {
        val source = LiveTvData.resolveSource(contentId, name, logo) ?: return null
        val launch = PlayerLaunch(
            profileId = ProfileRepository.activeProfileId,
            title = name,
            sourceUrl = source.url,
            sourceHeaders = source.headers,
            streamTitle = name,
            streamType = "live",
            providerName = com.nuvio.app.core.contracts.LivePlaybackAccess.current().accountNameFor(contentId) ?: "IPTV",
            providerAddonId = "xtream",
            logo = logo,
            contentType = "live",
            videoId = contentId,
            parentMetaId = contentId,
            parentMetaType = "tv",
        )
        return TvPlayerSession(launch) {
            LiveTvData.resolveSource(contentId, name, logo, forceMint = true)?.let { TvResolvedSource(it.url, it.headers) }
        }
    }
}
