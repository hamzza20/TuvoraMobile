package com.tuvora.tvos.screens

/**
 * The live guide's visible time window, as NuvioTV's guide draws it (XtreamLiveGuideScreen.kt): two
 * hours in 30-minute slots, starting on the half hour at or before now. Pure, so the geometry is tested.
 */
object TvGuideWindow {
    const val SLOT_MS = 30 * 60 * 1000L
    const val SLOTS = 4
    const val SPAN_MS = SLOT_MS * SLOTS

    fun start(nowMs: Long): Long = nowMs - ((nowMs % SLOT_MS) + SLOT_MS) % SLOT_MS

    /** Slot start times for the header (labels are formatted in the viewer's time zone by the UI). */
    fun slots(nowMs: Long): List<Long> = (0 until SLOTS).map { start(nowMs) + it * SLOT_MS }

    /**
     * Where a programme sits in the window as fractions of its width: [first, second) clamped to 0..1,
     * or null when it lies entirely outside.
     */
    fun span(nowMs: Long, startMs: Long, endMs: Long): Pair<Double, Double>? {
        val windowStart = start(nowMs)
        val windowEnd = windowStart + SPAN_MS
        if (endMs <= windowStart || startMs >= windowEnd || endMs <= startMs) return null
        val from = (maxOf(startMs, windowStart) - windowStart).toDouble() / SPAN_MS
        val to = (minOf(endMs, windowEnd) - windowStart).toDouble() / SPAN_MS
        return from to to
    }

    /** The now line, as a fraction of the window width. */
    fun nowFraction(nowMs: Long): Double = (nowMs - start(nowMs)).toDouble() / SPAN_MS

    /** Swift-friendly [span]: [from, to] or an empty list. */
    fun spanList(nowMs: Long, startMs: Long, endMs: Long): List<Double> = span(nowMs, startMs, endMs)?.toList() ?: emptyList()
}
