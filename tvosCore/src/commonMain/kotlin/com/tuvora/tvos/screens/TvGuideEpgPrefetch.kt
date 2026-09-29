package com.tuvora.tvos.screens

/** What one guide row asks the panel for, for one window. */
enum class TvRowFetch {
    /** Nothing to ask: a past window on a playlist with no catch-up guide (M3U, Stalker). */
    Skip,
    /** The panel's now-and-next (get_short_epg): the live window's first paint. */
    NowNext,
    /** The channel's stored guide table (get_simple_data_table, 6-hour gated): the only source of PAST rows. */
    History,
}

/**
 * Which guide rows are asked for EPG, and how — the fan-out rule (tuvora-guide-epg-fanout; NuvioTV
 * GuideEpgPrefetchPolicy): never one request per COMPOSED row. The guide asks for a bounded window
 * around the row the D-pad is on, only after focus settles, through the two-worker newest-first
 * queue — and each (row, window) at most once. That makes the past-window guide fill EVERY visible
 * row (it used to fetch history for the focused channel alone) without letting a D-pad sweep through
 * a 26,000-channel lineup turn into a request per row.
 *
 * Pure, so the bound and the choice of source are pinned by tests: the failure mode is invisible in
 * a screenshot and shows up only as a provider edge block or a usage graph.
 */
object TvGuideEpgPrefetch {
    /** Rows either side of the focused one — NuvioTV's ±8, about a screen of guide rows each way. */
    const val RADIUS = 8

    /** Focus must rest this long before anything is asked (NuvioTV EPG_FOCUS_DEBOUNCE_MS). */
    const val SETTLE_MS = 250L

    /**
     * Row indices to ask for, in the order they should RESOLVE: the focused row, then outward,
     * nearest first. Empty for an empty list; the anchor is clamped into range.
     */
    fun rows(anchor: Int, size: Int, radius: Int = RADIUS): List<Int> {
        if (size <= 0) return emptyList()
        val a = anchor.coerceIn(0, size - 1)
        val out = ArrayList<Int>(radius * 2 + 1)
        out.add(a)
        for (d in 1..radius) {
            (a - d).takeIf { it >= 0 }?.let(out::add)
            (a + d).takeIf { it < size }?.let(out::add)
        }
        return out
    }

    /**
     * The source for a row: a travelled (past) window reads the stored table on any playlist that
     * keeps one — every channel's past has titles to show, archive or not — and the live window reads
     * the table for archive channels (their lookback slot is replayable) and now-and-next otherwise.
     */
    fun fetchFor(travelling: Boolean, hasArchive: Boolean, catchUpSupported: Boolean): TvRowFetch = when {
        travelling && catchUpSupported -> TvRowFetch.History
        travelling -> TvRowFetch.Skip
        hasArchive && catchUpSupported -> TvRowFetch.History
        else -> TvRowFetch.NowNext
    }

    /** The once-only stamp: one ask per (channel, window). */
    fun key(contentId: String, windowStartMs: Long): String = "$contentId@$windowStartMs"

    /**
     * The asks to make for a settled focus: [rows] minus those already asked for this window, as keys
     * in resolve order. A caller feeding a newest-first queue enqueues them REVERSED.
     */
    fun pending(rowIds: List<String>, windowStartMs: Long, alreadyAsked: Set<String>): List<String> =
        rowIds.map { key(it, windowStartMs) }.filter { it !in alreadyAsked }.distinct()
}
