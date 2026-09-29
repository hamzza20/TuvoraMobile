package com.tuvora.tvos.screens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TvGuideEpgPrefetchTest {
    private val p = TvGuideEpgPrefetch

    @Test
    fun `asks are bounded around the focused row and resolve nearest first`() {
        assertEquals(listOf(10, 9, 11, 8, 12), p.rows(anchor = 10, size = 26_423, radius = 2))
        assertEquals(listOf(0, 1, 2), p.rows(anchor = 0, size = 26_423, radius = 2))
        assertEquals(listOf(4, 3, 2), p.rows(anchor = 99, size = 5, radius = 2))
        assertTrue(p.rows(anchor = 3, size = 0).isEmpty())
        // However large the lineup, one settle asks for at most 2 × RADIUS + 1 rows.
        assertEquals(2 * p.RADIUS + 1, p.rows(anchor = 13_000, size = 26_423).size)
    }

    @Test
    fun `a D-pad sweep through the lineup asks for each row at most once per window`() {
        val ids = (0 until 26_423).map { "c$it" }
        val asked = HashSet<String>()
        var requests = 0
        // Settle on every 3rd row while walking 300 rows down (a worst case: every step settles).
        for (focus in 0 until 300 step 3) {
            val keys = p.pending(p.rows(focus, ids.size).map { ids[it] }, windowStartMs = 1_000, alreadyAsked = asked)
            requests += keys.size
            asked.addAll(keys)
        }
        // Bounded by the rows actually near the path (300 walked + the radius past the end), not by 26k.
        assertEquals(300 + p.RADIUS - 2, requests)
        // A different window is a different ask.
        assertEquals(p.RADIUS + 1, p.pending(p.rows(0, ids.size).map { ids[it] }, windowStartMs = 2_000, alreadyAsked = asked).size)
    }

    @Test
    fun `a past window reads every row's stored guide - not only the focused one's`() {
        // Regression: travelling back showed programmes for the focused archive channel only.
        assertEquals(TvRowFetch.History, p.fetchFor(travelling = true, hasArchive = false, catchUpSupported = true))
        assertEquals(TvRowFetch.History, p.fetchFor(travelling = true, hasArchive = true, catchUpSupported = true))
        assertEquals(TvRowFetch.Skip, p.fetchFor(travelling = true, hasArchive = false, catchUpSupported = false))
    }

    @Test
    fun `the live window uses the table only for archive channels`() {
        assertEquals(TvRowFetch.History, p.fetchFor(travelling = false, hasArchive = true, catchUpSupported = true))
        assertEquals(TvRowFetch.NowNext, p.fetchFor(travelling = false, hasArchive = false, catchUpSupported = true))
        assertEquals(TvRowFetch.NowNext, p.fetchFor(travelling = false, hasArchive = true, catchUpSupported = false))
    }
}
