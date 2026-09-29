package com.tuvora.tvos.screens

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.details.seriesPrimaryAction
import com.nuvio.app.features.home.HomeRepository
import com.nuvio.app.features.home.HomeUiState
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.buildPlaybackVideoId
import com.nuvio.app.features.watchprogress.continueWatchingEntries
import com.nuvio.app.features.watchprogress.shouldUseAsCompletedSeedForContinueWatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Apple TV's Home data: the catalog rows (HomeRepository, refreshed when the installed add-ons change,
 * as the phone's HomeScreen effect does) and Continue Watching — in-progress entries by the phone's own
 * rule (continueWatchingEntries) plus Next Up for recently finished series, picked with the phone's
 * seriesPrimaryAction. The phone builds this row inside HomeScreen.kt (upstream, Compose, excluded).
 */
object TvHome {
    private val log = Logger.withTag("TvHome")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var started = false
    private var nextUpJob: Job? = null

    val rows: StateFlow<HomeUiState> get() = HomeRepository.uiState

    private val _continueWatching = MutableStateFlow<List<TvCwItem>>(emptyList())
    val continueWatching: StateFlow<List<TvCwItem>> = _continueWatching.asStateFlow()

    private var inProgress: List<TvCwItem> = emptyList()
    private var nextUp: List<TvCwItem> = emptyList()

    fun start() {
        if (started) return
        started = true
        AddonRepository.initialize()
        WatchProgressRepository.ensureLoaded()
        WatchedRepository.ensureLoaded()
        scope.launch {
            AddonRepository.uiState.map { it.addons }.distinctUntilChanged().collect { addons ->
                HomeRepository.refresh(addons)
            }
        }
        scope.launch {
            WatchProgressRepository.uiState.collect { state ->
                val visible = state.entries.filter { it.parentMetaId !in state.hiddenContentIds }
                inProgress = visible.continueWatchingEntries(limit = 300).take(TvContinueWatching.MAX_ITEMS).map { it.toCw(isNextUp = false) }
                publish()
                refreshNextUp(visible)
            }
        }
    }

    fun refresh() = HomeRepository.refresh(AddonRepository.uiState.value.addons, force = true)

    private fun publish() {
        _continueWatching.value = TvContinueWatching.merge(inProgress, nextUp)
    }

    private fun refreshNextUp(entries: List<WatchProgressEntry>) {
        val seeds = entries
            .filter { it.shouldUseAsCompletedSeedForContinueWatching() && it.parentMetaType.isSeriesLike() }
            .groupBy { it.parentMetaId }
            .mapNotNull { (_, list) -> list.maxByOrNull { it.lastUpdatedEpochMs } }
            .sortedByDescending { it.lastUpdatedEpochMs }
            .take(NEXT_UP_SEEDS)
        nextUpJob?.cancel()
        nextUpJob = scope.launch {
            val today = CurrentDateProvider.todayIsoDate()
            val watched = WatchedRepository.uiState.value.items
            val resolved = seeds.mapNotNull { seed ->
                val meta = runCatching { MetaDetailsRepository.fetch(seed.parentMetaType, seed.parentMetaId) }.getOrNull() ?: return@mapNotNull null
                val progress = WatchProgressRepository.prepareNextUpProgressEntries(entries = entries, contentId = meta.id)
                val action = meta.seriesPrimaryAction(entries = progress, watchedItems = watched, todayIsoDate = today) ?: return@mapNotNull null
                if (action.resumePositionMs != null) return@mapNotNull null
                val video = meta.videoFor(action.seasonNumber, action.episodeNumber, action.videoId) ?: return@mapNotNull null
                seed.toNextUp(meta, video)
            }
            nextUp = resolved
            publish()
        }
    }

    /** Opens a card: the title's details and, for an episode, the video to play. */
    suspend fun resolve(item: TvCwItem): TvCwTarget? {
        val meta = runCatching { MetaDetailsRepository.fetch(item.parentMetaType, item.parentMetaId) }.getOrNull() ?: return null
        val video = if (item.seasonNumber != null) meta.videoFor(item.seasonNumber, item.episodeNumber, item.videoId) else null
        return TvCwTarget(meta, video)
    }

    private fun WatchProgressEntry.toCw(isNextUp: Boolean) = TvCwItem(
        parentMetaId = parentMetaId, parentMetaType = parentMetaType, videoId = videoId, title = title,
        episodeTitle = episodeTitle, seasonNumber = seasonNumber, episodeNumber = episodeNumber,
        artwork = episodeThumbnail ?: background ?: poster, logo = logo, background = background ?: poster,
        positionMs = lastPositionMs, durationMs = durationMs, lastUpdatedEpochMs = lastUpdatedEpochMs, isNextUp = isNextUp,
    )

    private fun WatchProgressEntry.toNextUp(meta: MetaDetails, video: MetaVideo) = TvCwItem(
        parentMetaId = meta.id, parentMetaType = meta.type, videoId = video.id, title = meta.name,
        episodeTitle = video.title, seasonNumber = video.season, episodeNumber = video.episode,
        artwork = video.thumbnail ?: meta.background ?: meta.poster, logo = meta.logo, background = meta.background ?: meta.poster,
        positionMs = 0, durationMs = 0, lastUpdatedEpochMs = lastUpdatedEpochMs, isNextUp = true,
    )

    private fun MetaDetails.videoFor(season: Int?, episode: Int?, videoId: String): MetaVideo? {
        if (season != null && episode != null) videos.firstOrNull { it.season == season && it.episode == episode }?.let { return it }
        return videos.firstOrNull {
            buildPlaybackVideoId(parentMetaId = id, seasonNumber = it.season, episodeNumber = it.episode, fallbackVideoId = it.id) == videoId || it.id == videoId
        }
    }

    private fun String.isSeriesLike() = trim().lowercase() in setOf("series", "show", "tv", "tvshow")

    private const val NEXT_UP_SEEDS = 12
}

data class TvCwTarget(val meta: MetaDetails, val video: MetaVideo?)
