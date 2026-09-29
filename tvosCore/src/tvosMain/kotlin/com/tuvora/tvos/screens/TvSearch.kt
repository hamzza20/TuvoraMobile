package com.tuvora.tvos.screens

import com.nuvio.app.core.i18n.localizedMediaTypeLabel
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.home.buildAddonCatalogRefreshSignature
import com.nuvio.app.features.search.DiscoverUiState
import com.nuvio.app.features.search.SearchHistoryRepository
import com.nuvio.app.features.search.SearchRepository
import com.nuvio.app.features.search.SearchUiState
import com.nuvio.app.features.watched.WatchedRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Apple TV's Search and Discover, over the phone's shared SearchRepository (add-on catalogs plus the
 * IPTV lane) and SearchHistoryRepository. The screen decides what to show with [TvSearchPolicy].
 */
object TvSearch {
    val results: StateFlow<SearchUiState> get() = SearchRepository.uiState
    val discover: StateFlow<DiscoverUiState> get() = SearchRepository.discoverUiState
    val recent: StateFlow<List<String>> get() = SearchHistoryRepository.uiState

    /** Changes when the installed add-ons' catalogs change — the phone re-runs search and Discover then. */
    val addonSignature: Flow<String>
        get() = AddonRepository.uiState
            .map { buildAddonCatalogRefreshSignature(it.addons).joinToString("|") + "#" + it.addons.size }
            .distinctUntilChanged()

    fun start() {
        AddonRepository.initialize()
        WatchedRepository.ensureLoaded()
        SearchHistoryRepository.ensureLoaded()
    }

    /** Runs a search for an already-debounced query ([TvSearchPolicy.submittedQuery]); blank clears. */
    fun search(query: String, force: Boolean = false) {
        val submitted = TvSearchPolicy.submittedQuery(query)
        if (submitted.isEmpty()) SearchRepository.clear()
        else SearchRepository.search(submitted, AddonRepository.uiState.value.addons, forceRefresh = force)
    }

    fun record(query: String) = SearchHistoryRepository.recordSearch(query)
    fun removeRecent(query: String) = SearchHistoryRepository.removeSearch(query)

    /** NuvioTV's "Clear history" (the shared repository removes one at a time). */
    fun clearRecent() = SearchHistoryRepository.uiState.value.forEach(SearchHistoryRepository::removeSearch)

    fun refreshDiscover() = SearchRepository.refreshDiscover(AddonRepository.uiState.value.addons)
    fun selectDiscoverType(type: String) = SearchRepository.selectDiscoverType(type)
    fun selectDiscoverCatalog(key: String) = SearchRepository.selectDiscoverCatalog(key)
    fun selectDiscoverGenre(genre: String?) = SearchRepository.selectDiscoverGenre(genre)
    fun loadMoreDiscover() = SearchRepository.loadMoreDiscover()

    /** The shared, localized type label ("Movies", "Series", …). */
    fun typeLabel(type: String): String = localizedMediaTypeLabel(type)
}
