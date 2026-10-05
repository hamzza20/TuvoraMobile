package com.tuvora.tvos.player

import androidx.compose.ui.graphics.toArgb
import com.nuvio.app.features.player.SubtitleStyleState

/** libmpv subtitle arguments for a profile's style — the phone's conversion (PlayerEngine.ios.kt, excluded here). */
data class TvMpvSubtitleStyle(
    val textColor: String,
    val backgroundColor: String,
    val outlineColor: String,
    val outlineSize: Float,
    val bold: Boolean,
    val fontSize: Float,
    val subPos: Int,
    val stripSdh: Boolean,
)

object TvSubtitleStyle {
    /**
     * The Apple TV MPV bridge (tvosApp, lane E) still selects `opaque-box` for any background, and
     * libass paints that box in the OUTLINE colour, padded by the outline size. Until the bridge adopts
     * NuvioPlayerPropertyBridge (which then applies `background-box` via SubtitleStyleMpvMapping), a
     * box WITHOUT an outline — the F47 default — is passed as outline colour = background colour and
     * outline size = the shared box padding: the same soft, translucent, padded box.
     */
    fun forMpv(style: SubtitleStyleState): TvMpvSubtitleStyle {
        val boxWithoutOutline = style.backgroundColor.alpha > 0f && !style.outlineEnabled
        return TvMpvSubtitleStyle(
        textColor = mpvColor(style.textColor.toArgb()),
        backgroundColor = mpvColor(style.backgroundColor.toArgb()),
        outlineColor = mpvColor((if (boxWithoutOutline) style.backgroundColor else style.outlineColor).toArgb()),
        outlineSize = when {
            boxWithoutOutline -> com.nuvio.app.features.player.SubtitleStyleMpvMapping.BOX_PADDING.toFloat()
            style.outlineEnabled -> style.outlineWidth.toFloat()
            else -> 0f
        },
        bold = style.bold,
        fontSize = (style.fontSizeSp * 3f).coerceIn(18f, 96f),
        subPos = (100 - (style.bottomOffset / 2)).coerceIn(0, 150),
        stripSdh = style.stripSdh,
        )
    }

    /** "#AARRGGBB", as mpv's sub-color options take it. */
    fun mpvColor(argb: Int): String {
        val digits = "0123456789ABCDEF"
        return buildString {
            append('#')
            for (shift in intArrayOf(24, 16, 8, 0)) {
                val v = (argb ushr shift) and 0xFF
                append(digits[v / 16]); append(digits[v % 16])
            }
        }
    }
}
