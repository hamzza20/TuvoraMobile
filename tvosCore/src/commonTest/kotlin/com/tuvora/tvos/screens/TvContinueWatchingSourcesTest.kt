package com.tuvora.tvos.screens

import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression for the Apple TV tester report (Discord 2026-10-01): with Simkl connected, Continue
 * Watching missed almost every show. Simkl's progress provider carries only paused playbacks; the
 * episodes a viewer finished arrive as watched history (SimklWatchedSyncAdapter → WatchedRepository).
 * The phone seeds Next Up from both; Apple TV seeded from progress entries only.
 */
class TvContinueWatchingSourcesTest {
    /** What a fake Simkl provider feeds: one paused movie, and finished episodes as watched history. */
    private val simklPlayback = listOf(progress("tt-movie", "movie", pos = 30 * 60_000L, dur = 120 * 60_000L, at = 9_000L))
    private val simklHistory = listOf(
        watched("tt-show-a", 1, 3, at = 8_000L),
        watched("tt-show-a", 1, 2, at = 7_000L),
        watched("tt-show-b", 2, 5, at = 6_000L),
        watched("tt-show-c", 1, 1, at = 5_000L),
    )

    @Test
    fun `shows finished only in Simkl watched history seed Next Up`() {
        val seeds = TvContinueWatchingSources.nextUpSeeds(
            progressEntries = simklPlayback,
            watchedItems = simklHistory,
            providerOwnsCompletedHistory = false,
            preferFurthestEpisode = true,
            hiddenContentIds = emptySet(),
        )
        assertEquals(listOf("tt-show-a", "tt-show-b", "tt-show-c"), seeds.map { it.contentId })
        assertEquals(TvNextUpSeed("tt-show-a", "series", 1, 3, 8_000L), seeds.first())
    }

    @Test
    fun `a provider that owns completed history seeds from progress only`() {
        val seeds = TvContinueWatchingSources.nextUpSeeds(
            progressEntries = listOf(progress("tt-show-d", "series", s = 1, e = 4, completed = true, at = 4_000L)),
            watchedItems = simklHistory,
            providerOwnsCompletedHistory = true,
            preferFurthestEpisode = true,
            hiddenContentIds = emptySet(),
        )
        assertEquals(listOf("tt-show-d"), seeds.map { it.contentId })
    }

    @Test
    fun `hidden and dropped shows and seeds older than the window are left out`() {
        val seeds = TvContinueWatchingSources.nextUpSeeds(
            progressEntries = emptyList(),
            watchedItems = simklHistory,
            providerOwnsCompletedHistory = false,
            preferFurthestEpisode = true,
            hiddenContentIds = setOf("tt-show-a"),
            isDropped = { it == "tt-show-b" },
            cutoffEpochMs = 5_500L,
        )
        assertTrue(seeds.isEmpty(), "got $seeds")
    }

    @Test
    fun `looks up as many series as NuvioTV`() {
        val history = (1..40).map { watched("tt-s$it", 1, 1, at = it * 1_000L) }
        val seeds = TvContinueWatchingSources.nextUpSeeds(
            progressEntries = emptyList(),
            watchedItems = history,
            providerOwnsCompletedHistory = false,
            preferFurthestEpisode = true,
            hiddenContentIds = emptySet(),
        )
        assertEquals(32, seeds.size)
        assertEquals("tt-s40", seeds.first().contentId)
    }

    @Test
    fun `in-progress drops hidden and dropped titles and applies the window`() {
        val entries = simklPlayback + listOf(
            progress("tt-old", "movie", pos = 10_000L, dur = 100_000L, at = 1_000L),
            progress("tt-dropped", "series", s = 1, e = 1, pos = 10_000L, dur = 100_000L, at = 9_500L),
        )
        val visible = TvContinueWatchingSources.inProgress(
            entries = entries,
            hiddenContentIds = emptySet(),
            isDropped = { it == "tt-dropped" },
            cutoffEpochMs = 2_000L,
        )
        assertEquals(listOf("tt-movie"), visible.map { it.parentMetaId })
    }

    private fun progress(
        id: String, type: String, s: Int? = null, e: Int? = null,
        pos: Long = 0L, dur: Long = 0L, completed: Boolean = false, at: Long,
    ) = WatchProgressEntry(
        contentType = type, parentMetaId = id, parentMetaType = type,
        videoId = if (s != null) "$id:$s:$e" else id, title = id,
        seasonNumber = s, episodeNumber = e, lastPositionMs = pos, durationMs = dur,
        lastUpdatedEpochMs = at, isCompleted = completed,
    )

    private fun watched(id: String, s: Int, e: Int, at: Long) =
        WatchedItem(id = id, type = "series", name = id, season = s, episode = e, markedAtEpochMs = at)
}
