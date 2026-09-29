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
    fun forMpv(style: SubtitleStyleState): TvMpvSubtitleStyle = TvMpvSubtitleStyle(
        textColor = mpvColor(style.textColor.toArgb()),
        backgroundColor = mpvColor(style.backgroundColor.toArgb()),
        outlineColor = mpvColor(style.outlineColor.toArgb()),
        outlineSize = if (style.outlineEnabled) style.outlineWidth.toFloat() else 0f,
        bold = style.bold,
        fontSize = (style.fontSizeSp * 3f).coerceIn(18f, 96f),
        subPos = (100 - (style.bottomOffset / 2)).coerceIn(0, 150),
        stripSdh = style.stripSdh,
    )

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
