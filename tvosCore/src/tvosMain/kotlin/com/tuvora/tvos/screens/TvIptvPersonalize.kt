package com.tuvora.tvos.screens

import com.nuvio.app.features.epg.EpgMirrorRepository
import com.nuvio.app.features.iptv.XtreamHubRepository
import com.nuvio.app.features.iptv.XtreamItemRegistry
import com.nuvio.app.features.iptv.XtreamRepository
import com.nuvio.app.features.iptv.overlay.IptvHiddenItems
import com.nuvio.app.features.iptv.overlay.IptvHiddenItemsPolicy
import com.nuvio.app.features.iptv.overlay.IptvOverlayRepository
import com.nuvio.app.features.livetv.LiveGuideChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The IPTV personalization overlay (hide channels / groups, synced to tuvora.co and every device)
 * and the guide-region choice, for Swift: [IptvOverlayRepository], [IptvHiddenItems] and
 * [EpgMirrorRepository] are internal to the shared code.
 */
object TvIptvPersonalize {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val hiddenById = mutableMapOf<String, Pair<String, IptvHiddenItemsPolicy.HiddenItem>>()

    /** Bumps whenever an overlay edit lands (here, on the web or another device): the guide re-reads its channels. */
    val overlayRevision: Flow<Int>
        get() {
            IptvOverlayRepository.ensureLoaded()
            return IptvOverlayRepository.uiState.map { it.hashCode() }.distinctUntilChanged()
        }

    /** NuvioTV MENU on a channel: hide it from the guide (the overlay's channel toggle). */
    fun hideChannel(channel: LiveGuideChannel) {
        val playlistId = XtreamItemRegistry.parseId(channel.contentId)?.accountId
        IptvOverlayRepository.setChannelHidden(channel.entityId, playlistId, hidden = true)
    }

    /** NuvioTV MENU on a provider category → "Hide group": the hub's category of the current section. */
    fun hideCategory(categoryId: String): String? = XtreamHubRepository.hideCategory(categoryId)

    suspend fun hiddenItems(accountId: String): List<TvHiddenItem> {
        val account = XtreamRepository.uiState.value.accounts.firstOrNull { it.id == accountId } ?: return emptyList()
        return IptvHiddenItems.load(account).map { item ->
            val id = "${item.kind}|${item.contentType}|${item.key}"
            hiddenById[id] = accountId to item
            TvHiddenItem(
                id = id,
                name = item.name,
                kindLabel = TvIptvSettingsPolicy.kindLabel(item.kind == IptvHiddenItemsPolicy.HiddenKind.CHANNEL, item.contentType),
            )
        }
    }

    fun unhide(id: String) {
        val (accountId, item) = hiddenById.remove(id) ?: return
        val account = XtreamRepository.uiState.value.accounts.firstOrNull { it.id == accountId } ?: return
        IptvHiddenItems.unhide(account, item)
    }

    suspend fun regions(): List<TvEpgRegion> =
        EpgMirrorRepository.availableRegions().map { TvEpgRegion(it.name, it.flag, it.channelCount) }

    suspend fun selectedRegions(): Set<String> = EpgMirrorRepository.selectedRegions()

    /** Applies the selection; the mirror rebuilds against it on its own scope (like the phone's picker). */
    fun setRegions(regions: Set<String>) {
        scope.launch { EpgMirrorRepository.setSelectedRegions(regions) }
    }
}
