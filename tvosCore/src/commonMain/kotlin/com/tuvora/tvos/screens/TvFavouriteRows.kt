package com.tuvora.tvos.screens

import com.nuvio.app.features.iptv.XtreamItemRegistry

/**
 * F03 on Apple TV — two decisions of the live guide's favourites rows (pure; the SwiftUI hub renders).
 *
 * P5 (W2 device pass): removing a favourite while a favourites row is on screen dropped the row at
 * once, so the "Hold OK to undo" the notice promised had no row left to hold OK on. The removed row
 * now stays where it was, marked as removed, for as long as Undo is offered ([UNDO_WINDOW_MS]), and
 * Undo puts the favourite back in its old place (its old saved-at stamp) rather than at the top.
 *
 * T4: zapping from All favorites walked into other playlists' channels; the zap list is the row
 * narrowed to the playing channel's own playlist.
 */
object TvFavouriteRows {
    /** How long the notice and the hold-OK Undo are offered after a favourite toggle. */
    const val UNDO_WINDOW_MS: Long = 6_000

    /** A favourite removed from a favourites row on screen: where it was, and its old order stamp. */
    data class PendingRemoval(
        val contentId: String,
        val index: Int,
        val savedAtEpochMs: Long,
        val removedAtMs: Long,
    )

    fun pendingRemoval(rowIds: List<String>, contentId: String, savedAtEpochMs: Long, nowMs: Long): PendingRemoval? {
        val index = rowIds.indexOf(contentId)
        if (index < 0) return null
        return PendingRemoval(contentId, index, savedAtEpochMs, nowMs)
    }

    fun isOffered(pending: PendingRemoval?, nowMs: Long): Boolean =
        pending != null && nowMs - pending.removedAtMs < UNDO_WINDOW_MS

    /**
     * The row's ids with a removed favourite kept at its old place while Undo is offered. Once the
     * window has closed — or the favourite is back (Undo) — the row is exactly [rowIds].
     */
    fun visibleIds(rowIds: List<String>, pending: PendingRemoval?, nowMs: Long): List<String> {
        if (pending == null || !isOffered(pending, nowMs) || pending.contentId in rowIds) return rowIds
        return rowIds.toMutableList().apply { add(pending.index.coerceIn(0, size), pending.contentId) }
    }

    /**
     * The row while a removed favourite is HELD past its window — Undo is saving it back, or the row
     * still has focus. A focused row that vanishes throws focus to the sidebar on tvOS, and the hold-OK
     * menu (opened inside the window) can still be open when the window closes.
     */
    fun heldIds(rowIds: List<String>, pending: PendingRemoval?): List<String> {
        if (pending == null || pending.contentId in rowIds) return rowIds
        return rowIds.toMutableList().apply { add(pending.index.coerceIn(0, size), pending.contentId) }
    }

    /** When the window closes: drop the removed row now, unless it is the focused row (held until focus leaves). */
    fun dropsAtWindowClose(pending: PendingRemoval?, focusedId: String?): Boolean =
        pending != null && pending.contentId != focusedId

    /** T4: the channels zapping walks from [playingId] — only those of its own playlist, in row order. */
    fun zapIds(rowIds: List<String>, playingId: String): List<String> {
        val account = XtreamItemRegistry.parseId(playingId)?.accountId ?: return rowIds
        return rowIds.filter { XtreamItemRegistry.parseId(it)?.accountId == account }
    }
}
