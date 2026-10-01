package com.tuvora.tvos.screens

import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watching.application.WatchingState
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.continueWatchingEntries
import com.nuvio.app.features.watchprogress.isMalformedNextUpSeedContentId
import com.nuvio.app.features.watchprogress.isSeriesTypeForContinueWatching
import com.nuvio.app.features.watchprogress.shouldUseAsCompletedSeedForContinueWatching

/** A finished episode whose series may have a Next Up card (the phone's CompletedSeriesCandidate). */
data class TvNextUpSeed(
    val contentId: String,
    val contentType: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val markedAtEpochMs: Long,
)

/**
 * Which watch history feeds Apple TV's Continue Watching row. Mirrors the phone's HomeScreen.kt rules
 * (upstream Compose, excluded from :tvosCore) so the row is the same on both. Pure; TvHome feeds it.
 *
 * A tracking provider's progress carries only paused playbacks (Simkl: `playback` sessions); the
 * episodes the viewer finished arrive as watched history (SimklWatchedSyncAdapter → WatchedRepository).
 * Next Up therefore seeds from both, unless the provider already folds completions into its progress
 * (ownsCompletedHistoryProjection) — buildHomeNextUpSeedCandidates.
 */
object TvContinueWatchingSources {
    /** NuvioTV CW_MAX_NEXT_UP_LOOKUPS / the phone's HomeNextUpInitialResolutionLimit. */
    const val NEXT_UP_LOOKUPS = 32

    /** The phone's HomeContinueWatchingMaxRecentProgressItems. */
    private const val MAX_RECENT_PROGRESS = 300

    /** In-progress entries the row may show: hidden and dropped titles out, the provider's window applied. */
    fun inProgress(
        entries: List<WatchProgressEntry>,
        hiddenContentIds: Set<String>,
        isDropped: (String) -> Boolean = { false },
        cutoffEpochMs: Long? = null,
    ): List<WatchProgressEntry> =
        entries
            .filterNot { it.parentMetaId in hiddenContentIds || isDropped(it.parentMetaId) }
            .filter { entry -> cutoffEpochMs == null || entry.lastUpdatedEpochMs >= cutoffEpochMs }
            .continueWatchingEntries(limit = MAX_RECENT_PROGRESS)

    /** Series whose latest finished episode seeds a Next Up card, newest first, capped at [limit]. */
    fun nextUpSeeds(
        progressEntries: List<WatchProgressEntry>,
        watchedItems: List<WatchedItem>,
        providerOwnsCompletedHistory: Boolean,
        preferFurthestEpisode: Boolean,
        hiddenContentIds: Set<String>,
        isDropped: (String) -> Boolean = { false },
        shouldUseProgressSeed: (WatchProgressEntry) -> Boolean = { it.shouldUseAsCompletedSeedForContinueWatching() },
        cutoffEpochMs: Long? = null,
        limit: Int = NEXT_UP_LOOKUPS,
    ): List<TvNextUpSeed> {
        val isHidden = { id: String -> id in hiddenContentIds || isDropped(id) }
        val progressSeeds = progressEntries.filter { entry ->
            !isHidden(entry.parentMetaId) &&
                entry.parentMetaType.isSeriesTypeForContinueWatching() &&
                entry.seasonNumber != null && entry.episodeNumber != null && entry.seasonNumber != 0 &&
                !isMalformedNextUpSeedContentId(entry.parentMetaId) &&
                shouldUseProgressSeed(entry)
        }
        val watchedSeeds = if (providerOwnsCompletedHistory) emptyList() else watchedItems.filter { item ->
            !isHidden(item.id) &&
                item.type.isSeriesTypeForContinueWatching() &&
                item.season != null && item.episode != null && item.season != 0 &&
                !isMalformedNextUpSeedContentId(item.id)
        }
        return WatchingState.latestCompletedBySeries(
            progressEntries = progressSeeds,
            watchedItems = watchedSeeds,
            preferFurthestEpisode = preferFurthestEpisode,
        ).mapNotNull { (content, completed) ->
            if (!content.type.isSeriesTypeForContinueWatching() || completed.seasonNumber == 0) return@mapNotNull null
            if (isMalformedNextUpSeedContentId(content.id)) return@mapNotNull null
            TvNextUpSeed(content.id, content.type, completed.seasonNumber, completed.episodeNumber, completed.markedAtEpochMs)
        }
            .filter { seed -> cutoffEpochMs == null || seed.markedAtEpochMs >= cutoffEpochMs }
            .sortedWith(
                compareByDescending<TvNextUpSeed> { it.markedAtEpochMs }
                    .thenByDescending { it.seasonNumber }
                    .thenByDescending { it.episodeNumber },
            )
            .take(limit)
    }
}
