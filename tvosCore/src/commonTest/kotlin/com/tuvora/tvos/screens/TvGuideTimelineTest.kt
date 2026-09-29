package com.tuvora.tvos.screens

import com.nuvio.app.features.iptv.XtreamProgram
import com.nuvio.app.features.livetv.LiveGuideChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvGuideTimelineTest {
    private val t = TvGuideTimeline
    private val min = 60_000L
    private val hour = 60 * min
    private val day = 24 * hour
    // 2026-09-28T20:10:00Z
    private val now = 1_790_640_600_000L

    private fun prog(title: String, start: Long, end: Long, archive: Boolean? = null) =
        XtreamProgram(title, "", start, end, nowPlaying = false, hasArchive = archive)

    private fun channel(archive: Boolean, days: Int = 0) =
        LiveGuideChannel(contentId = "c", name = "Ch", logo = null, streamId = 1, categoryId = null, hasArchive = archive, catchUpDays = days)

    @Test
    fun `the live window opens one slot in the past on a slot boundary`() {
        val start = t.liveWindowStartMs(now)
        assertEquals(0L, start % t.SLOT_MS)
        assertTrue(start <= now - t.SLOT_MS && start > now - 2 * t.SLOT_MS)
        assertTrue(t.containsNow(start, now))
        assertTrue(t.isAtLiveEdge(start, now))
    }

    @Test
    fun `travel pages a full window and stops at the stored archive and the horizon`() {
        val live = t.liveWindowStartMs(now)
        assertEquals(live - t.WINDOW_MS, t.shift(live, -t.EDGE_TRAVEL_SLOTS, now, catchUpDays = 3))
        var start = live
        repeat(200) { start = t.shift(start, -t.EDGE_TRAVEL_SLOTS, now, catchUpDays = 3) }
        assertEquals(t.earliestWindowStartMs(now, 3), start)
        // The store keeps at least 8 days whatever the panel says.
        assertTrue(start <= now - 8 * day)
        repeat(200) { start = t.shift(start, t.EDGE_TRAVEL_SLOTS, now, catchUpDays = 3) }
        assertEquals(t.latestWindowStartMs(now), start)
    }

    @Test
    fun `a travelled window stays put while the live one follows the clock`() {
        val live = t.liveWindowStartMs(now)
        val later = now + 40 * min
        assertEquals(t.liveWindowStartMs(later), t.onClockTick(live, now, later))
        val back = live - t.WINDOW_MS
        assertEquals(back, t.onClockTick(back, now, later))
    }

    @Test
    fun `the OK rule - replay past then sheet for airing archive then live otherwise and nothing in the future`() {
        val ch = channel(archive = true, days = 7)
        val past = prog("Past", now - 2 * hour, now - hour)
        val airing = prog("Now", now - 10 * min, now + 20 * min)
        val future = prog("Next", now + 20 * min, now + hour)
        assertEquals(TvCellIntent.Replay, t.intent(t.action(past, ch.hasArchive, ch.catchUpDays, now, catchUpSupported = true)))
        assertEquals(TvCellIntent.OpenSheet, t.intent(t.action(airing, ch.hasArchive, ch.catchUpDays, now, catchUpSupported = true)))
        assertEquals(TvCellIntent.None, t.intent(t.action(future, ch.hasArchive, ch.catchUpDays, now, catchUpSupported = true)))
        val noArchive = channel(archive = false)
        assertEquals(TvCellIntent.PlayLive, t.intent(t.action(airing, noArchive.hasArchive, 0, now, true)))
        assertEquals(TvCellIntent.None, t.intent(t.action(past, noArchive.hasArchive, 0, now, true)))
    }

    @Test
    fun `a playlist that cannot build catch-up urls never offers a replay`() {
        val past = prog("Past", now - 2 * hour, now - hour)
        val airing = prog("Now", now - 10 * min, now + 20 * min)
        assertEquals(TvCellIntent.None, t.intent(t.action(past, true, 7, now, catchUpSupported = false)))
        assertEquals(TvCellIntent.PlayLive, t.intent(t.action(airing, true, 7, now, catchUpSupported = false)))
        assertFalse(t.showsReplayBadge(t.action(past, true, 7, now, catchUpSupported = false)))
    }

    @Test
    fun `cells fill the window with fillers for gaps and clip at the edges`() {
        val start = t.liveWindowStartMs(now)
        val programmes = listOf(
            prog("A", start - hour, start + 30 * min),            // clipped on the left
            prog("B", start + hour, start + 90 * min),             // after a 30-min gap
            prog("Z", start + 5 * hour, start + 6 * hour),         // outside
        )
        val cells = t.cells(programmes, channel(archive = true, days = 7), start, now, catchUpSupported = true)
        assertEquals(listOf("A", null, "B", null), cells.map { it.programme?.title })
        assertEquals(0.0, cells.first().from)
        assertEquals(1.0, cells.sumOf { it.width }, 1e-9)
        assertEquals(TvCellIntent.None, cells[1].intent)
    }

    @Test
    fun `an empty row is one filler`() {
        val cells = t.cells(emptyList(), channel(false), t.liveWindowStartMs(now), now, true)
        assertEquals(1, cells.size)
        assertEquals(1.0, cells.single().width)
    }

    @Test
    fun `replay verdicts follow the phone's rule`() {
        assertEquals(TvReplayVerdict.Proven, TvReplayPolicy.verdict(isPlaying = true, positionMs = 0, isEnded = false, errorMessage = null))
        assertEquals(TvReplayVerdict.Proven, TvReplayPolicy.verdict(false, 1_000, false, null))
        assertEquals(TvReplayVerdict.Failed, TvReplayPolicy.verdict(false, 0, isEnded = true, errorMessage = null))
        assertEquals(TvReplayVerdict.Failed, TvReplayPolicy.verdict(false, 0, false, "HTTP 404"))
        assertEquals(TvReplayVerdict.SessionLimit, TvReplayPolicy.verdict(false, 0, false, "{\"error\":\"limit\"}"))
        assertEquals(TvReplayVerdict.Pending, TvReplayPolicy.verdict(false, 0, false, null))
        assertEquals("BBC One · News", TvReplayPolicy.title("BBC One", "News"))
    }
}
