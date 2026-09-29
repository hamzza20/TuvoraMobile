package com.tuvora.tvos.screens

import com.nuvio.app.features.iptv.TileEpgQueue
import com.nuvio.app.features.iptv.XtreamProgram
import com.nuvio.app.features.livetv.LiveGuideChannel
import com.nuvio.app.features.livetv.LiveTvData
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/** One row's programmes for one window, as the guide should show them. */
data class TvGuideRowEpg(val contentId: String, val windowStartMs: Long, val programmes: List<XtreamProgram>)

/**
 * The guide's EPG loader: [TvGuideEpgPrefetch] decides which rows and which source; this runs the
 * asks through the shared [TileEpgQueue] (two workers, newest-first, capped backlog with eviction —
 * the hub tiles' queue), stamps each (row, window) once, and publishes results on [results].
 */
object TvGuideEpg {
    private val lock = SynchronizedObject()
    private val asked = HashSet<String>()
    private val historyShown = HashSet<String>()
    private val _results = MutableSharedFlow<TvGuideRowEpg>(extraBufferCapacity = 128)
    val results: SharedFlow<TvGuideRowEpg> = _results.asSharedFlow()

    /** A fresh guide: forget what earlier screens asked for (their results went nowhere). */
    fun resetSession() = synchronized(lock) { asked.clear(); historyShown.clear() }

    /** The number of asks made this session — the regression hook for the fan-out bound. */
    val askedCount: Int get() = synchronized(lock) { asked.size }

    /**
     * Asks for the rows around [anchor] in [channels] (the list the guide shows), for the window at
     * [windowStartMs]. Call only once focus has settled ([TvGuideEpgPrefetch.SETTLE_MS]).
     */
    fun request(channels: List<LiveGuideChannel>, anchor: Int, windowStartMs: Long, travelling: Boolean, catchUpSupported: Boolean) {
        val rows = TvGuideEpgPrefetch.rows(anchor, channels.size).map { channels[it] }
        val byKey = rows.associateBy { TvGuideEpgPrefetch.key(it.contentId, windowStartMs) }
        val keys = synchronized(lock) {
            TvGuideEpgPrefetch.pending(rows.map { it.contentId }, windowStartMs, asked).also { asked.addAll(it) }
        }
        // Newest-first queue: enqueue reversed so the focused row runs first.
        for (key in keys.asReversed()) {
            val channel = byKey.getValue(key)
            val fetch = TvGuideEpgPrefetch.fetchFor(travelling, channel.hasArchive, catchUpSupported)
            if (fetch == TvRowFetch.Skip) {
                _results.tryEmit(TvGuideRowEpg(channel.contentId, windowStartMs, emptyList()))
                continue
            }
            TileEpgQueue.enqueue(
                key = "tvguide:$key",
                onEvicted = { synchronized(lock) { asked.remove(key) } },
            ) { load(channel, windowStartMs, travelling, fetch, key) }
        }
    }

    private suspend fun load(channel: LiveGuideChannel, windowStartMs: Long, travelling: Boolean, fetch: TvRowFetch, key: String): Boolean {
        if (fetch == TvRowFetch.History) LiveTvData.ensureHistory(channel.contentId)
        val shown = synchronized(lock) { key in historyShown }
        val window = TvCatchUp.windowProgrammes(
            channel.contentId, windowStartMs, windowStartMs + TvGuideTimeline.WINDOW_MS, travelling, shown,
        )
        if (window.fromHistory) synchronized(lock) { historyShown.add(key) }
        _results.emit(TvGuideRowEpg(channel.contentId, windowStartMs, window.programmes))
        return window.programmes.isNotEmpty()
    }
}
