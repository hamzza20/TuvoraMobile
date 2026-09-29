package com.tuvora.tvos.player

import com.nuvio.app.features.player.SubtitleStyleState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvAudioDelayTest {
    @Test
    fun `steps are 25 ms and clamp at three seconds`() {
        assertEquals(25, TvAudioDelay.step(0, up = true))
        assertEquals(-25, TvAudioDelay.step(0, up = false))
        assertEquals(3000, TvAudioDelay.step(2990, up = true))
        assertEquals(-3000, TvAudioDelay.clamp(-9000))
        assertFalse(TvAudioDelay.canStep(3000, up = true))
        assertTrue(TvAudioDelay.canStep(3000, up = false))
    }

    @Test
    fun `labels read like the TV app`() {
        assertEquals("0.000s", TvAudioDelay.label(0))
        assertEquals("+0.125s", TvAudioDelay.label(125))
        assertEquals("-1.500s", TvAudioDelay.label(-1500))
    }
}

class TvSubtitleStyleEditorTest {
    private val base = SubtitleStyleState.DEFAULT

    @Test
    fun `size and offset step within their ranges`() {
        assertEquals(20, TvSubtitleStyleEditor.size(base, up = true).fontSizeSp)
        assertEquals(32, TvSubtitleStyleEditor.size(base.copy(fontSizeSp = 32), up = true).fontSizeSp)
        assertEquals(6, TvSubtitleStyleEditor.size(base.copy(fontSizeSp = 6), up = false).fontSizeSp)
        assertEquals(25, TvSubtitleStyleEditor.offset(base, up = true).bottomOffset)
        assertEquals(0, TvSubtitleStyleEditor.offset(base.copy(bottomOffset = 2), up = false).bottomOffset)
    }

    @Test
    fun `text colour keeps the opacity and maps to the mpv colour`() {
        val translucent = base.copy(textColor = base.textColor.copy(alpha = 0.5f))
        val yellow = TvSubtitleStyleEditor.textColor(translucent, 2)
        assertEquals(2, TvSubtitleStyleEditor.view(yellow).textColorIndex)
        val mpv = TvSubtitleStyle.forMpv(yellow).textColor
        assertTrue(mpv.endsWith("FFD700"), mpv)
        assertTrue(mpv.startsWith("#7F") || mpv.startsWith("#80"), mpv)
    }

    @Test
    fun `background box maps through to mpv`() {
        val boxed = TvSubtitleStyleEditor.background(base, 1)
        assertEquals(1, TvSubtitleStyleEditor.view(boxed).backgroundIndex)
        assertEquals("#80000000", TvSubtitleStyle.forMpv(boxed).backgroundColor)
        assertEquals(0, TvSubtitleStyleEditor.view(base).backgroundIndex)
    }

    @Test
    fun `offset raises the mpv position and reset keeps the non-visual settings`() {
        val raised = TvSubtitleStyleEditor.offset(base, up = true)
        assertTrue(TvSubtitleStyle.forMpv(raised).subPos < TvSubtitleStyle.forMpv(base).subPos)
        val reset = TvSubtitleStyleEditor.reset(raised.copy(stripSdh = true, bold = true))
        assertEquals(base.bottomOffset, reset.bottomOffset)
        assertFalse(reset.bold)
        assertTrue(reset.stripSdh)
    }
}

class TvPlaybackIssueReportTest {
    @Test
    fun `the report never carries a stream address`() {
        val props = TvPlaybackIssueReport.properties(
            trigger = "error", lane = "libmpv", isLive = true, contentType = "tv", positionMs = 65_000, durationMs = 0,
            isLoading = false, isPlaying = false,
            errorMessage = "Failed to open http://user:pass@provider.example:8080/live/abc/123.ts (403)",
            videoCodec = "HEVC", resolution = "1080p", audioCodec = null, speed = 1f, audioDelayMs = 0, subtitleDelayMs = 0,
        )
        assertEquals("Failed to open [redacted-url] (403)", props["error"])
        assertEquals(65L, props["position_s"])
        assertFalse(props.values.any { it.toString().contains("provider.example") })
    }
}
