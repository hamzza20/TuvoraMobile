package com.tuvora.tvos.player

import com.nuvio.app.features.player.SubtitleStyleState
import kotlin.test.Test
import kotlin.test.assertEquals

class TvSubtitleStyleTest {
    @Test
    fun `the default style maps like the phone`() {
        val mpv = TvSubtitleStyle.forMpv(SubtitleStyleState.DEFAULT)
        assertEquals("#FFFFFFFF", mpv.textColor)
        assertEquals("#00000000", mpv.backgroundColor)
        assertEquals("#FF000000", mpv.outlineColor)
        assertEquals(2f, mpv.outlineSize)
        assertEquals(54f, mpv.fontSize)
        assertEquals(90, mpv.subPos)
    }

    @Test
    fun `sizes and positions are clamped`() {
        val mpv = TvSubtitleStyle.forMpv(SubtitleStyleState(fontSizeSp = 60, bottomOffset = 400, outlineEnabled = false))
        assertEquals(96f, mpv.fontSize)
        assertEquals(0, mpv.subPos)
        assertEquals(0f, mpv.outlineSize)
    }
}
