package com.tuvora.tvos.screens

import com.nuvio.app.features.iptv.XtreamItemRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TvFavouriteRowsTest {
    private val a1 = XtreamItemRegistry.liveId("acctA", 1)
    private val a2 = XtreamItemRegistry.liveId("acctA", 2)
    private val a3 = XtreamItemRegistry.liveId("acctA", 3)
    private val b1 = XtreamItemRegistry.liveId("acctB", 1)
    private val b2 = XtreamItemRegistry.liveId("acctB", 2)

    /** P5: the removed row stays at its old place while Undo is offered, so hold-OK can reach it. */
    @Test
    fun removedFavouriteKeepsItsRowWhileUndoIsOffered() {
        val pending = assertNotNull(TvFavouriteRows.pendingRemoval(listOf(a1, a2, a3), a2, savedAtEpochMs = 50, nowMs = 1_000))
        assertEquals(listOf(a1, a2, a3), TvFavouriteRows.visibleIds(listOf(a1, a3), pending, nowMs = 1_000 + 5_999))
        assertTrue(TvFavouriteRows.isOffered(pending, nowMs = 6_999))
    }

    @Test
    fun removedFavouriteLeavesOnceTheWindowCloses() {
        val pending = TvFavouriteRows.pendingRemoval(listOf(a1, a2, a3), a2, savedAtEpochMs = 50, nowMs = 1_000)
        assertEquals(listOf(a1, a3), TvFavouriteRows.visibleIds(listOf(a1, a3), pending, nowMs = 7_000))
        assertFalse(TvFavouriteRows.isOffered(pending, nowMs = 7_000))
    }

    @Test
    fun undoneFavouriteIsNotListedTwice() {
        val pending = TvFavouriteRows.pendingRemoval(listOf(a1, a2, a3), a2, savedAtEpochMs = 50, nowMs = 1_000)
        assertEquals(listOf(a1, a2, a3), TvFavouriteRows.visibleIds(listOf(a1, a2, a3), pending, nowMs = 2_000))
    }

    @Test
    fun lastFavouriteOfARowStaysUntilTheWindowCloses() {
        val pending = TvFavouriteRows.pendingRemoval(listOf(a1), a1, savedAtEpochMs = 50, nowMs = 1_000)
        assertEquals(listOf(a1), TvFavouriteRows.visibleIds(emptyList(), pending, nowMs = 2_000))
    }

    @Test
    fun pendingRemovalRemembersTheOldOrderStamp() {
        val pending = assertNotNull(TvFavouriteRows.pendingRemoval(listOf(a1, a2), a2, savedAtEpochMs = 42, nowMs = 1_000))
        assertEquals(42L, pending.savedAtEpochMs)
        assertEquals(1, pending.index)
    }

    @Test
    fun heldRowStaysPastTheWindow() {
        val pending = TvFavouriteRows.pendingRemoval(listOf(a1, a2, a3), a2, savedAtEpochMs = 50, nowMs = 1_000)
        assertEquals(listOf(a1, a2, a3), TvFavouriteRows.heldIds(listOf(a1, a3), pending))
        assertEquals(listOf(a1, a2, a3), TvFavouriteRows.heldIds(listOf(a1, a2, a3), pending))
    }

    @Test
    fun aFocusedRemovedRowIsNotDroppedUnderFocus() {
        val pending = TvFavouriteRows.pendingRemoval(listOf(a1, a2), a2, savedAtEpochMs = 50, nowMs = 1_000)
        assertFalse(TvFavouriteRows.dropsAtWindowClose(pending, focusedId = a2))
        assertTrue(TvFavouriteRows.dropsAtWindowClose(pending, focusedId = a1))
        assertFalse(TvFavouriteRows.dropsAtWindowClose(null, focusedId = a1))
    }

    /** T4: zapping from All favorites stays inside the playing channel's own playlist. */
    @Test
    fun zapFromAllFavouritesStaysInThePlaylist() {
        val row = listOf(a1, b1, a2, b2, a3)
        assertEquals(listOf(a1, a2, a3), TvFavouriteRows.zapIds(row, playingId = a2))
        assertEquals(listOf(b1, b2), TvFavouriteRows.zapIds(row, playingId = b2))
    }
}
