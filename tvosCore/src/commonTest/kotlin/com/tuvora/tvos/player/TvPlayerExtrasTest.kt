package com.tuvora.tvos.player

import com.nuvio.app.features.player.PlayerStreamInfo
import com.nuvio.app.features.player.skip.AutoSkipSegmentType
import com.nuvio.app.features.player.skip.SkipInterval
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TvSkipPolicyTest {
    private val intro = SkipInterval(60.0, 150.0, "op", "aniskip")
    private val outro = SkipInterval(1300.0, 1400.0, "ed", "aniskip")

    @Test
    fun `inside the intro offers Skip Intro to its end`() {
        val d = TvSkipPolicy.decide(90_000, 1_440_000, isPlaying = true, listOf(intro, outro), emptySet(), emptySet())
        assertEquals("Skip Intro", d.segment?.label)
        assertEquals(150_000L, d.segment?.targetMs)
        assertNull(d.autoSkipToMs)
    }

    @Test
    fun `outside every segment offers nothing`() {
        assertNull(TvSkipPolicy.decide(200_000, 1_440_000, true, listOf(intro, outro), emptySet(), emptySet()).segment)
    }

    @Test
    fun `auto skip fires once and only while playing`() {
        val types = setOf(AutoSkipSegmentType.INTRO)
        assertEquals(150_000L, TvSkipPolicy.decide(61_000, 1_440_000, true, listOf(intro), types, emptySet()).autoSkipToMs)
        assertNull(TvSkipPolicy.decide(61_000, 1_440_000, false, listOf(intro), types, emptySet()).autoSkipToMs)
        assertNull(TvSkipPolicy.decide(61_000, 1_440_000, true, listOf(intro), types, setOf(intro)).autoSkipToMs)
    }

    @Test
    fun `a segment type not chosen for auto skip is only offered`() {
        // The ending runs to the file's end (tail under 5 s), so it is a plain "Skip Ending".
        val d = TvSkipPolicy.decide(1_310_000, 1_402_000, true, listOf(outro), setOf(AutoSkipSegmentType.INTRO), emptySet())
        assertEquals("Skip Ending", d.segment?.label)
        assertNull(d.autoSkipToMs)
    }

    @Test
    fun `an ending well before the file end leads to the post credits`() {
        val d = TvSkipPolicy.decide(1_310_000, 1_440_000, true, listOf(outro), emptySet(), emptySet())
        assertEquals("Skip to Post-Credits", d.segment?.label)
        assertEquals(1_400_000L, d.segment?.targetMs)
    }

    @Test
    fun `labels match the TV app`() {
        assertEquals("Skip Recap", TvSkipPolicy.label("recap"))
        assertEquals("Skip", TvSkipPolicy.label("mystery"))
        assertEquals("Skip to Post-Credits", TvSkipPolicy.label("ed", skipsToPostCredits = true))
    }
}

class TvPlayerControlsPolicyTest {
    @Test
    fun `speeds and labels are the TV list`() {
        assertEquals(8, TvPlaybackSpeeds.values.size)
        assertEquals("Normal", TvPlaybackSpeeds.label(1f))
        assertEquals("1.5x", TvPlaybackSpeeds.label(1.5f))
        assertEquals(1.25f, TvPlaybackSpeeds.nearest(1.2600001f))
    }

    @Test
    fun `subtitle delay steps a tenth and clamps`() {
        assertEquals(100, TvSubtitleDelay.step(0, up = true))
        assertEquals(-60_000, TvSubtitleDelay.step(-60_000, up = false))
        assertEquals("+0.3s", TvSubtitleDelay.label(300))
        assertEquals("-1.2s", TvSubtitleDelay.label(-1_200))
        assertEquals("0.0s", TvSubtitleDelay.label(0))
    }

    @Test
    fun `stream info shows only reported facts`() {
        val sections = TvStreamInfoRows.sections(
            PlayerStreamInfo(videoCodec = "HEVC", videoWidth = 1920, videoHeight = 1080, videoFrameRate = 23.976f,
                videoBitrate = 6_400_000, audioChannelCount = 6, playerEngine = "libmpv"),
            sourceName = "Torrentio", streamTitle = null, subtitle = null,
        )
        assertEquals(listOf("SOURCE", "VIDEO", "AUDIO"), sections.map { it.title })
        assertEquals(listOf("Codec" to "HEVC", "Resolution" to "1920 × 1080 (1080p)", "Frame Rate" to "23.976 fps", "Bitrate" to "6.4 Mbps"),
            sections[1].rows.map { it.label to it.value })
        assertEquals(listOf("Channels" to "5.1"), sections[2].rows.map { it.label to it.value })
    }
}

class TvSubtitleSelectionPolicyTest {
    @Test
    fun `the profile language applies once when the first track appears`() {
        assertEquals(true, TvSubtitleSelectionPolicy.appliesProfileLanguage(userPicked = false, alreadyApplied = false, trackCount = 1))
        assertEquals(false, TvSubtitleSelectionPolicy.appliesProfileLanguage(userPicked = false, alreadyApplied = false, trackCount = 0))
        assertEquals(false, TvSubtitleSelectionPolicy.appliesProfileLanguage(userPicked = false, alreadyApplied = true, trackCount = 2))
    }

    @Test
    fun `an add-on subtitle the viewer picked is never switched off by the preference`() {
        // Regression: the picked add-on subtitle became the first track and the NONE preference deselected it.
        assertEquals(false, TvSubtitleSelectionPolicy.appliesProfileLanguage(userPicked = true, alreadyApplied = false, trackCount = 1))
    }
}
