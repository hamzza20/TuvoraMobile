package com.tuvora.tvos.screens

/** One Continue Watching card, as NuvioTV's ContinueWatchingSection draws it. */
data class TvCwItem(
    val parentMetaId: String,
    val parentMetaType: String,
    val videoId: String,
    val title: String,
    val episodeTitle: String?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val artwork: String?,
    val logo: String?,
    val background: String?,
    val positionMs: Long,
    val durationMs: Long,
    val lastUpdatedEpochMs: Long,
    val isNextUp: Boolean,
) {
    val progress: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/**
 * Merges in-progress items and Next Up items into the Continue Watching row: newest first, Next Up
 * suppressed for any series already in progress, capped. Pure, so tested without repositories.
 */
object TvContinueWatching {
    const val MAX_ITEMS = 20

    fun merge(inProgress: List<TvCwItem>, nextUp: List<TvCwItem>): List<TvCwItem> {
        val inProgressSeries = inProgress.map { it.parentMetaId }.toSet()
        return (inProgress + nextUp.filter { it.parentMetaId !in inProgressSeries })
            .distinctBy { it.parentMetaId to it.videoId }
            .sortedByDescending { it.lastUpdatedEpochMs }
            .take(MAX_ITEMS)
    }

    /** The top-right badge: "Next Up", or time left like NuvioTV ("8m left", "1h 49m left"). */
    fun badge(item: TvCwItem): String? {
        if (item.isNextUp) return "Next Up"
        if (item.durationMs <= 0) return null
        val leftMin = ((item.durationMs - item.positionMs).coerceAtLeast(0) / 60_000).toInt()
        if (leftMin <= 0) return null
        val h = leftMin / 60
        val m = leftMin % 60
        return if (h > 0) "${h}h ${m}m left" else "${m}m left"
    }

    /** "S2 E1" for episodes, null for movies. */
    fun episodeLabel(item: TvCwItem): String? =
        if (item.seasonNumber != null && item.episodeNumber != null) "S${item.seasonNumber} E${item.episodeNumber}" else null
}
