package com.tuvora.tvos.player

import com.nuvio.app.features.player.SubtitleStyleState
import kotlin.test.Test
import kotlin.test.assertEquals

class TvSubtitleStyleTest {
    @Test
    fun `the outlined legacy style maps like the phone`() {
        val mpv = TvSubtitleStyle.forMpv(com.nuvio.app.features.player.SubtitleStyleDefaults.LEGACY)
        assertEquals("#FFFFFFFF", mpv.textColor)
        assertEquals("#00000000", mpv.backgroundColor)
        assertEquals("#FF000000", mpv.outlineColor)
        assertEquals(2f, mpv.outlineSize)
        assertEquals(54f, mpv.fontSize)
        assertEquals(90, mpv.subPos)
    }

    @Test
    fun `the F47 default box survives the bridge's opaque-box as a translucent padded box`() {
        // opaque-box paints the box in the OUTLINE colour with the outline size as margin.
        val mpv = TvSubtitleStyle.forMpv(SubtitleStyleState.DEFAULT)
        assertEquals("#8C000000", mpv.backgroundColor)
        assertEquals("#8C000000", mpv.outlineColor)
        assertEquals(com.nuvio.app.features.player.SubtitleStyleMpvMapping.BOX_PADDING.toFloat(), mpv.outlineSize)
    }

    @Test
    fun `sizes and positions are clamped`() {
        val mpv = TvSubtitleStyle.forMpv(
            SubtitleStyleState(
                fontSizeSp = 60,
                bottomOffset = 400,
                outlineEnabled = false,
                backgroundColor = androidx.compose.ui.graphics.Color.Transparent,
            ),
        )
        assertEquals(96f, mpv.fontSize)
        assertEquals(0, mpv.subPos)
        assertEquals(0f, mpv.outlineSize)
    }
}
