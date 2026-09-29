package com.tuvora.tvos.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.nuvio.app.features.player.SubtitleStyleState

/** NuvioTV's Audio Delay (PlayerRuntimeControllerPlaybackEvents.kt): 25 ms steps within ±3 s, "+0.125s". */
object TvAudioDelay {
    const val STEP_MS = 25
    const val MIN_MS = -3000
    const val MAX_MS = 3000

    fun clamp(ms: Int): Int = ms.coerceIn(MIN_MS, MAX_MS)
    fun step(currentMs: Int, up: Boolean): Int = clamp(currentMs + if (up) STEP_MS else -STEP_MS)
    fun canStep(currentMs: Int, up: Boolean): Boolean = if (up) currentMs < MAX_MS else currentMs > MIN_MS

    /** AudioSelectionOverlay.formatAudioDelay: "0.000s", "+0.125s", "-1.500s". */
    fun label(ms: Int): String {
        if (ms == 0) return "0.000s"
        val abs = kotlin.math.abs(ms)
        val text = "${abs / 1000}.${(abs % 1000).toString().padStart(3, '0')}s"
        return if (ms > 0) "+$text" else "-$text"
    }

    /** "Range: -3.00s to 3.00s" (audio_delay_range). */
    fun rangeLabel(): String = "Range: -3.00s to 3.00s"
}

/** What the in-player Style panel shows. Colours are ARGB so Swift can draw the swatches. */
data class TvSubtitleStyleView(
    val fontSizeSp: Int,
    val bottomOffset: Int,
    val bold: Boolean,
    val outline: Boolean,
    val textColorIndex: Int,
    val backgroundIndex: Int,
    val textColors: List<Long>,
    val backgrounds: List<Long>,
)

/**
 * The in-player subtitle style editor (NuvioTV SubtitleStyleSidePanel + the phone's SubtitleStylePanel),
 * as pure edits of the shared [SubtitleStyleState]: size ±2 sp, bottom offset ±5 (0–200), NuvioTV's text
 * colour palette (keeping the text opacity), a background box (off / dim / solid), bold and outline.
 */
object TvSubtitleStyleEditor {
    /** SubtitleStyleSidePanel PANEL_TEXT_COLORS. */
    val TEXT_COLORS: List<Long> = listOf(0xFFFFFFFF, 0xFFD9D9D9, 0xFFFFD700, 0xFF00E5FF, 0xFFFF5C5C, 0xFF00FF88)
    /** Off, dim box, solid box. */
    val BACKGROUNDS: List<Long> = listOf(0x00000000, 0x80000000, 0xE6000000)

    const val SIZE_STEP = 2
    /** libmpv's usable range after TvSubtitleStyle's ×3 (18–96 px): 6–32 sp. */
    val SIZE_RANGE = 6..32
    const val OFFSET_STEP = 5
    val OFFSET_RANGE = 0..200

    fun size(style: SubtitleStyleState, up: Boolean) =
        style.copy(fontSizeSp = (style.fontSizeSp + if (up) SIZE_STEP else -SIZE_STEP).coerceIn(SIZE_RANGE))

    fun offset(style: SubtitleStyleState, up: Boolean) =
        style.copy(bottomOffset = (style.bottomOffset + if (up) OFFSET_STEP else -OFFSET_STEP).coerceIn(OFFSET_RANGE))

    fun textColor(style: SubtitleStyleState, index: Int): SubtitleStyleState {
        val argb = TEXT_COLORS.getOrNull(index) ?: return style
        return style.copy(textColor = Color(argb).copy(alpha = style.textColor.alpha))
    }

    fun background(style: SubtitleStyleState, index: Int): SubtitleStyleState {
        val argb = BACKGROUNDS.getOrNull(index) ?: return style
        return style.copy(backgroundColor = Color(argb))
    }

    fun bold(style: SubtitleStyleState) = style.copy(bold = !style.bold)
    fun outline(style: SubtitleStyleState) = style.copy(outlineEnabled = !style.outlineEnabled)
    fun reset(style: SubtitleStyleState) = SubtitleStyleState.DEFAULT.copy(
        stripSdh = style.stripSdh, useForcedSubtitles = style.useForcedSubtitles,
        showOnlyPreferredLanguages = style.showOnlyPreferredLanguages,
    )

    fun view(style: SubtitleStyleState): TvSubtitleStyleView {
        val rgb = style.textColor.toArgb().toLong() and 0x00FFFFFF
        return TvSubtitleStyleView(
            fontSizeSp = style.fontSizeSp,
            bottomOffset = style.bottomOffset,
            bold = style.bold,
            outline = style.outlineEnabled,
            textColorIndex = TEXT_COLORS.indexOfFirst { (it and 0x00FFFFFF) == rgb },
            backgroundIndex = BACKGROUNDS.indexOfFirst { it.toInt() == style.backgroundColor.toArgb() },
            textColors = TEXT_COLORS,
            backgrounds = BACKGROUNDS,
        )
    }
}

/**
 * The playback-issue report as an analytics event. NuvioTV uploads a report to its own endpoint
 * (PLAYBACK_REPORTS_BASE_URL, not configured for Tuvora); Apple TV sends it through AnalyticsSink.
 * Privacy: never the stream address, provider host or credentials — URLs in an error are redacted.
 */
object TvPlaybackIssueReport {
    const val EVENT = "playback_issue_reported"
    private val url = Regex("""[a-zA-Z][a-zA-Z0-9+.-]*://\S+""")

    fun redact(text: String): String = url.replace(text, "[redacted-url]")

    fun properties(
        trigger: String,
        lane: String,
        isLive: Boolean,
        contentType: String,
        positionMs: Long,
        durationMs: Long,
        isLoading: Boolean,
        isPlaying: Boolean,
        errorMessage: String?,
        videoCodec: String?,
        resolution: String?,
        audioCodec: String?,
        speed: Float,
        audioDelayMs: Int,
        subtitleDelayMs: Int,
    ): Map<String, Any> = buildMap {
        put("trigger", trigger)
        put("platform", "tvos")
        put("engine", lane)
        put("is_live", isLive)
        put("content_type", contentType)
        put("position_s", positionMs / 1000)
        put("duration_s", durationMs / 1000)
        put("is_loading", isLoading)
        put("is_playing", isPlaying)
        errorMessage?.takeIf { it.isNotBlank() }?.let { put("error", redact(it).take(300)) }
        videoCodec?.let { put("video_codec", it) }
        resolution?.let { put("resolution", it) }
        audioCodec?.let { put("audio_codec", it) }
        put("speed", speed.toDouble())
        put("audio_delay_ms", audioDelayMs)
        put("subtitle_delay_ms", subtitleDelayMs)
    }
}
