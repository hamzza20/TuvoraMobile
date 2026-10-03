package com.tuvora.tvos.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveDisplayCriteriaPolicyTest {

    private val ntscFilm = 24000.0 / 1001
    private val ntsc = 30000.0 / 1001
    private val ntscDouble = 60000.0 / 1001

    private fun format(
        containerFps: Double = 25.0,
        estimatedFps: Double = 0.0,
        width: Int = 1920,
        height: Int = 1080,
        codec: String = "h264",
        gamma: String = "bt.1886",
    ) = TvVideoFormat(containerFps, estimatedFps, width, height, codec, gamma)

    private fun refresh(containerFps: Double, estimatedFps: Double = 0.0): Double? =
        LiveDisplayCriteriaPolicy.criteria(format(containerFps = containerFps, estimatedFps = estimatedFps))?.refreshRate

    private fun assertRate(expected: Double, actual: Double?, message: String) {
        assertTrue(actual != null && kotlin.math.abs(expected - actual) < 1e-6, "$message: expected $expected got $actual")
    }

    // --- target refresh rate: the highest exact multiple tvOS outputs (Apple: 25/30 double to 50/60) ---

    @Test
    fun `PAL live doubles to 50 Hz`() {
        assertRate(50.0, refresh(25.0), "25p")
        assertRate(50.0, refresh(50.0), "50p")
    }

    @Test
    fun `NTSC live doubles to the matching fractional or integer 60`() {
        assertRate(ntscDouble, refresh(ntsc), "29.97")
        assertRate(ntscDouble, refresh(ntscDouble), "59.94")
        assertRate(ntscDouble, refresh(29.97), "29.97 written as a decimal")
        assertRate(60.0, refresh(30.0), "30")
        assertRate(60.0, refresh(60.0), "60")
    }

    @Test
    fun `film rates play at their own rate`() {
        assertRate(ntscFilm, refresh(ntscFilm), "23.976")
        assertRate(24.0, refresh(24.0), "24")
    }

    @Test
    fun `an unknown or non standard rate asks for nothing`() {
        assertNull(refresh(0.0), "no rate yet")
        assertNull(refresh(Double.NaN), "NaN")
        assertNull(refresh(12.5), "12.5")
        assertNull(refresh(90000.0), "a timebase reported as a rate")
        assertNull(refresh(48.0), "48 has no Apple TV output mode")
    }

    @Test
    fun `the container rate wins over the measured estimate`() {
        assertRate(50.0, refresh(containerFps = 25.0, estimatedFps = 29.6), "container 25")
    }

    @Test
    fun `a measured estimate is snapped when the container declares no rate`() {
        assertRate(50.0, refresh(containerFps = 0.0, estimatedFps = 24.93), "measured PAL")
        assertRate(50.0, refresh(containerFps = 0.0, estimatedFps = 50.12), "measured 50")
        // A measurement cannot tell 29.97 from 30: broadcast is fractional, so prefer it.
        assertRate(ntscDouble, refresh(containerFps = 0.0, estimatedFps = 29.99), "measured NTSC")
        assertRate(ntscFilm, refresh(containerFps = 0.0, estimatedFps = 23.99), "measured film")
        assertNull(refresh(containerFps = 0.0, estimatedFps = 27.5), "between families")
    }

    @Test
    fun `a nonsense container rate falls back to the measured estimate`() {
        assertRate(50.0, refresh(containerFps = 1000.0, estimatedFps = 25.02), "container 1000")
    }

    @Test
    fun `criteria need the picture size`() {
        assertNull(LiveDisplayCriteriaPolicy.criteria(format(width = 0)), "no width")
        assertNull(LiveDisplayCriteriaPolicy.criteria(format(height = 0)), "no height")
        assertNull(LiveDisplayCriteriaPolicy.criteria(null), "no format")
    }

    @Test
    fun `the dynamic range follows the transfer function`() {
        assertEquals(DisplayDynamicRange.Sdr, LiveDisplayCriteriaPolicy.criteria(format(gamma = "bt.1886"))?.dynamicRange)
        assertEquals(DisplayDynamicRange.Sdr, LiveDisplayCriteriaPolicy.criteria(format(gamma = ""))?.dynamicRange)
        assertEquals(DisplayDynamicRange.Pq, LiveDisplayCriteriaPolicy.criteria(format(gamma = "pq"))?.dynamicRange)
        assertEquals(DisplayDynamicRange.Pq, LiveDisplayCriteriaPolicy.criteria(format(gamma = "SMPTE ST 2084"))?.dynamicRange)
        assertEquals(DisplayDynamicRange.Hlg, LiveDisplayCriteriaPolicy.criteria(format(gamma = "hlg"))?.dynamicRange)
        assertEquals(DisplayDynamicRange.Hlg, LiveDisplayCriteriaPolicy.criteria(format(gamma = "arib-std-b67"))?.dynamicRange)
    }

    // --- the tracker: switch only when the mode changes; keep it across zaps and the guide ---

    private fun criteria(fps: Double, gamma: String = "bt.1886", width: Int = 1920) =
        LiveDisplayCriteriaPolicy.criteria(format(containerFps = fps, gamma = gamma, width = width))

    @Test
    fun `the first live format sets the criteria`() {
        val tracker = LiveDisplayCriteriaTracker()
        tracker.enter()
        val set = tracker.offer(criteria(25.0), matchingEnabled = true)
        assertRate(50.0, set?.refreshRate, "first offer")
        assertRate(50.0, tracker.applied?.refreshRate, "remembered")
    }

    @Test
    fun `nothing is set while the user has Match Content off`() {
        val tracker = LiveDisplayCriteriaTracker()
        tracker.enter()
        assertNull(tracker.offer(criteria(25.0), matchingEnabled = false), "matching off")
        assertNull(tracker.applied, "nothing remembered")
        // Turned on later: the next poll sets it.
        assertRate(50.0, tracker.offer(criteria(25.0), matchingEnabled = true)?.refreshRate, "matching on")
    }

    @Test
    fun `a zap within the same rate family keeps the display mode`() {
        val tracker = LiveDisplayCriteriaTracker()
        tracker.enter()
        tracker.offer(criteria(25.0), matchingEnabled = true)
        assertNull(tracker.offer(criteria(25.0), matchingEnabled = true), "same channel polled again")
        assertNull(tracker.offer(criteria(50.0), matchingEnabled = true), "25 to 50 is the same 50 Hz mode")
        assertNull(tracker.offer(criteria(25.0, width = 1280), matchingEnabled = true), "a different size is not a mode change")
        assertNull(tracker.offer(null, matchingEnabled = true), "a channel whose rate is not known yet")
        assertRate(50.0, tracker.applied?.refreshRate, "still 50")
    }

    @Test
    fun `a zap to another rate family switches once`() {
        val tracker = LiveDisplayCriteriaTracker()
        tracker.enter()
        tracker.offer(criteria(25.0), matchingEnabled = true)
        assertRate(ntscDouble, tracker.offer(criteria(ntsc), matchingEnabled = true)?.refreshRate, "PAL to NTSC")
        assertNull(tracker.offer(criteria(ntscDouble), matchingEnabled = true), "29.97 to 59.94 stays")
        assertRate(60.0, tracker.offer(criteria(30.0), matchingEnabled = true)?.refreshRate, "fractional to integer 60")
    }

    @Test
    fun `a dynamic range change switches even at the same rate`() {
        val tracker = LiveDisplayCriteriaTracker()
        tracker.enter()
        tracker.offer(criteria(50.0), matchingEnabled = true)
        assertEquals(DisplayDynamicRange.Hlg, tracker.offer(criteria(50.0, gamma = "hlg"), matchingEnabled = true)?.dynamicRange)
    }

    @Test
    fun `an offer after Live TV was left is ignored`() {
        val tracker = LiveDisplayCriteriaTracker()
        assertNull(tracker.offer(criteria(25.0), matchingEnabled = true), "no live screen on show")
        tracker.enter()
        tracker.exit()
        assertNull(tracker.offer(criteria(25.0), matchingEnabled = true), "a late poll after leaving")
    }

    @Test
    fun `leaving Live TV clears after the grace period`() {
        val tracker = LiveDisplayCriteriaTracker()
        tracker.enter()
        tracker.offer(criteria(25.0), matchingEnabled = true)
        assertTrue(tracker.exit(), "last screen left with criteria set: schedule the clear")
        assertTrue(tracker.graceElapsed(), "clear now")
        assertNull(tracker.applied, "forgotten")
        assertFalse(tracker.graceElapsed(), "a second timer does nothing")
    }

    @Test
    fun `fullscreen to guide and back keeps the mode whatever order the screens report`() {
        // Player closes before the guide reappears.
        val a = LiveDisplayCriteriaTracker()
        a.enter() // guide
        a.enter() // fullscreen
        a.offer(criteria(25.0), matchingEnabled = true)
        assertFalse(a.exit(), "guide still holds")
        assertFalse(a.graceElapsed(), "nothing to clear")
        assertRate(50.0, a.applied?.refreshRate, "kept on the guide")

        // The guide reported gone while fullscreen covered it, then the player closed, then the guide came back.
        val b = LiveDisplayCriteriaTracker()
        b.enter() // guide
        b.enter() // fullscreen
        b.offer(criteria(25.0), matchingEnabled = true)
        b.exit() // guide hidden under fullscreen
        assertTrue(b.exit(), "player closed: nothing on show for a moment")
        b.enter() // guide back within the grace period
        assertFalse(b.graceElapsed(), "the timer finds a live screen again")
        assertRate(50.0, b.applied?.refreshRate, "no HDMI re-switch")
    }

    @Test
    fun `leaving without ever matching schedules nothing`() {
        val tracker = LiveDisplayCriteriaTracker()
        tracker.enter()
        assertFalse(tracker.exit(), "nothing was set")
        assertFalse(tracker.exit(), "an unbalanced exit is harmless")
    }

    @Test
    fun `backgrounding clears and the next poll re-applies`() {
        val tracker = LiveDisplayCriteriaTracker()
        tracker.enter()
        tracker.offer(criteria(25.0), matchingEnabled = true)
        assertTrue(tracker.backgrounded(), "clear on background")
        assertFalse(tracker.backgrounded(), "already clear")
        assertRate(50.0, tracker.offer(criteria(25.0), matchingEnabled = true)?.refreshRate, "back in front, same channel")
    }
}
