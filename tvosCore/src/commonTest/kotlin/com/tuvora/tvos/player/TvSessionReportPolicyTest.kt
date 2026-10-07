package com.tuvora.tvos.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** When Apple TV's player tells a source that keeps its own watched state (a media server) about a playback. */
class TvSessionReportPolicyTest {
    private fun decide(
        owned: Boolean = true, live: Boolean = false, closed: Boolean = false,
        reported: Boolean = false, playing: Boolean = true, ended: Boolean = false,
        durationMs: Long = 120_000, nowMs: Long = 10_000, lastTickMs: Long = 0,
    ) = TvSessionReportPolicy.decide(owned, live, closed, reported, playing, ended, durationMs, nowMs, lastTickMs)

    @Test fun `starts once playing with a known duration`() =
        assertEquals(TvSessionReportPolicy.Action.START, decide())

    @Test fun `never reports a source nobody owns or live TV or a closed session`() {
        assertEquals(TvSessionReportPolicy.Action.NONE, decide(owned = false))
        assertEquals(TvSessionReportPolicy.Action.NONE, decide(live = true))
        assertEquals(TvSessionReportPolicy.Action.NONE, decide(closed = true))
    }

    @Test fun `waits for playback and a duration before the start`() {
        assertEquals(TvSessionReportPolicy.Action.NONE, decide(playing = false))
        assertEquals(TvSessionReportPolicy.Action.NONE, decide(durationMs = 0))
    }

    @Test fun `ticks every five seconds after the start whether paused or not`() {
        assertEquals(TvSessionReportPolicy.Action.NONE, decide(reported = true, nowMs = 4_900, lastTickMs = 0))
        assertEquals(TvSessionReportPolicy.Action.TICK, decide(reported = true, nowMs = 5_000, lastTickMs = 0))
        assertEquals(TvSessionReportPolicy.Action.TICK, decide(reported = true, playing = false, nowMs = 9_000, lastTickMs = 3_000))
    }

    @Test fun `an ended playback stops ticking`() =
        assertEquals(TvSessionReportPolicy.Action.NONE, decide(reported = true, ended = true, nowMs = 60_000))

    @Test fun `a resume offer is skipped when the viewer is already near the server position`() {
        assertFalse(TvSessionReportPolicy.shouldOfferResume(currentMs = 100_000, offeredMs = 120_000))
        assertTrue(TvSessionReportPolicy.shouldOfferResume(currentMs = 0, offeredMs = 120_000))
        assertTrue(TvSessionReportPolicy.shouldOfferResume(currentMs = 89_999, offeredMs = 120_000))
    }
}
