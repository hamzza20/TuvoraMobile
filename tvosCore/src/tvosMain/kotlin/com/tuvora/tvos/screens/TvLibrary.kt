package com.tuvora.tvos.screens

import com.nuvio.app.features.library.LibraryDisplaySettingsRepository
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.library.LibrarySortOption
import com.nuvio.app.features.library.LibrarySourceMode
import com.nuvio.app.features.library.effectiveLibrarySortOption
import com.nuvio.app.features.library.librarySorter
import com.nuvio.app.features.library.observeLibraryProviderOrders
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.tracking.TrackingRefreshIntent
import com.nuvio.app.features.watched.WatchedRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Apple TV's Library: the phone's shared LibraryRepository (Tuvora library, or Trakt / Simkl / MDBList
 * when that is the profile's library source), sorted by the shared display setting and provider order,
 * filtered the NuvioTV way by [TvLibraryProjection]. [views] is cold: it runs only while the screen
 * collects it.
 */
object TvLibrary {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val filters = MutableStateFlow(TvLibraryFilters())

    @OptIn(ExperimentalCoroutinesApi::class)
    fun views(): Flow<TvLibraryView> {
        LibraryRepository.ensureLoaded()
        WatchedRepository.ensureLoaded()
        LibraryDisplaySettingsRepository.ensureLoaded()
        val sort = LibraryDisplaySettingsRepository.uiState.map { it.sortOption }.distinctUntilChanged()
        val orders = combine(LibraryRepository.uiState, filters, sort) { state, f, option ->
            val remote = state.sourceMode != LibrarySourceMode.LOCAL
            val listKey = state.sections.firstOrNull { it.type == f.listKey }?.type ?: state.sections.firstOrNull()?.type
            Triple(state.sourceMode, listOfNotNull(listKey.takeIf { remote }), effectiveLibrarySortOption(option, state.sourceMode))
        }.distinctUntilChanged().flatMapLatest { (mode, keys, option) ->
            observeLibraryProviderOrders(mode.librarySorter(), keys, option)
        }
        val watchedTick = combine(WatchedRepository.uiState, WatchedRepository.fullyWatchedSeriesKeys) { a, b -> a to b }
        return combine(LibraryRepository.uiState, filters, sort, orders, watchedTick) { state, f, option, providerOrders, _ ->
            TvLibraryProjection.project(
                state = state,
                filters = f,
                sort = option,
                providerOrders = providerOrders.ranks,
                providerOrderFailed = providerOrders.failed,
                isWatched = ::isWatched,
            )
        }
    }

    fun selectList(key: String) = filters.update { it.copy(listKey = key) }
    fun selectType(key: String?) = filters.update { it.copy(type = key) }
    fun selectGenre(genre: String?) = filters.update { it.copy(genre = genre) }
    fun selectYear(year: String?) = filters.update { it.copy(year = year) }
    fun selectWatched(filter: TvLibraryWatchedFilter) = filters.update { it.copy(watched = filter) }
    fun selectSort(option: LibrarySortOption) = LibraryDisplaySettingsRepository.setSortOption(option)

    /** NuvioTV's Sync: pull the library (and the tracking provider's lists) now. */
    fun refresh() {
        scope.launch {
            LibraryRepository.pullFromServer(ProfileRepository.activeProfileId, TrackingRefreshIntent.USER_INITIATED)
        }
    }

    private fun isWatched(item: LibraryItem): Boolean =
        if (item.type.equals("movie", ignoreCase = true)) WatchedRepository.isWatched(item.id, item.type)
        else WatchedRepository.isFullyWatchedSeries(item.id, item.type)
}
