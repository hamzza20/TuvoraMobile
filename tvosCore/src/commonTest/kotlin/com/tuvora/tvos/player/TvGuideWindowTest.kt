package com.tuvora.tvos.player

import com.tuvora.tvos.screens.TvGuideWindow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TvGuideWindowTest {
    private val m = 60_000L
    private val now = 1_000L * 60 * 60 * 24 * 10 + 47 * m   // 00:47 on some day

    @Test
    fun `the window starts on the half hour at or before now`() {
        assertEquals(now - 17 * m, TvGuideWindow.start(now))
        assertEquals(listOf(0L, 30L, 60L, 90L).map { now - 17 * m + it * m }, TvGuideWindow.slots(now))
    }

    @Test
    fun `a programme spanning the window start is clipped to it`() {
        val start = TvGuideWindow.start(now)
        assertEquals(0.0 to 0.25, TvGuideWindow.span(now, start - 60 * m, start + 30 * m))
    }

    @Test
    fun `programmes outside the window are dropped`() {
        val start = TvGuideWindow.start(now)
        assertNull(TvGuideWindow.span(now, start - 60 * m, start))
        assertNull(TvGuideWindow.span(now, start + 120 * m, start + 150 * m))
    }

    @Test
    fun `the now line sits at the elapsed share of the window`() {
        assertEquals(17.0 / 120.0, TvGuideWindow.nowFraction(now), 1e-9)
    }
}
