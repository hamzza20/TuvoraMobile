package com.tuvora.tvos.screens

import com.nuvio.app.features.tracking.TrackingMembershipRemovalImpact
import kotlin.test.Test
import kotlin.test.assertEquals

class TvTitleActionsPolicyTest {
    @Test
    fun `history only reads like the TV app`() {
        val c = TvTitleActionsPolicy.removalConfirmation("Alien", listOf("Trakt"), setOf(TrackingMembershipRemovalImpact.WATCHED_HISTORY))
        assertEquals("Remove from Trakt?", c.title)
        assertEquals("Removing “Alien” from Trakt will also clear its watched history there. This can’t be undone.", c.message)
    }

    @Test
    fun `several providers and both impacts are joined once`() {
        val c = TvTitleActionsPolicy.removalConfirmation(
            "Dark", listOf("Trakt", "Simkl", "Trakt"),
            setOf(TrackingMembershipRemovalImpact.WATCHED_HISTORY, TrackingMembershipRemovalImpact.RATING),
        )
        assertEquals("Remove from Trakt, Simkl?", c.title)
        assertEquals("Removing “Dark” from Trakt, Simkl will also clear its watched history and rating there. This can’t be undone.", c.message)
    }

    @Test
    fun `rating only`() {
        val c = TvTitleActionsPolicy.removalConfirmation("X", listOf("Simkl"), setOf(TrackingMembershipRemovalImpact.RATING))
        assertEquals("Removing “X” from Simkl will also clear its rating there. This can’t be undone.", c.message)
    }
}

class TvListOrderTest {
    @Test
    fun `moving swaps with the neighbour`() {
        assertEquals(listOf("b", "a", "c"), TvListOrder.move(listOf("a", "b", "c"), "b", up = true))
        assertEquals(listOf("a", "c", "b"), TvListOrder.move(listOf("a", "b", "c"), "b", up = false))
    }

    @Test
    fun `the edges and unknown keys do not move`() {
        assertEquals(null, TvListOrder.move(listOf("a", "b"), "a", up = true))
        assertEquals(null, TvListOrder.move(listOf("a", "b"), "b", up = false))
        assertEquals(null, TvListOrder.move(listOf("a", "b"), "z", up = true))
    }
}
