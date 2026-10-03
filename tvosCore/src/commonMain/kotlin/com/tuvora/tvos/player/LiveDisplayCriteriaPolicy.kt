package com.tuvora.tvos.player

import kotlin.math.abs

/**
 * Automatic display frame-rate matching for Live TV on Apple TV — the parity of Android TV's
 * live display-mode matching (NuvioTV 105207cb6): a 25p/50p channel on a 59.94 Hz output holds
 * frames for 2 or 3 refreshes and judders on every pan; at 50 Hz every frame is held exactly 2.
 *
 * Automatic for live (owner decision, no setting), but tvOS only honours it when the viewer turned
 * on Settings › Video and Audio › Match Content (`AVDisplayManager.isDisplayCriteriaMatchingEnabled`);
 * the app never works around that. The Swift side (LiveDisplayCriteriaController) builds
 * `AVDisplayCriteria(refreshRate:formatDescription:)` (public, tvOS 17) from [TvDisplayCriteria].
 *
 * Pure: the engine reports facts ([TvVideoFormat]); this decides what to ask the display for.
 */

/** What libmpv knows about the decoded picture (mpv `container-fps`, `estimated-vf-fps`, `video-params`). */
data class TvVideoFormat(
    /** The container/codec's declared rate; 0 when it declares none (common in live MPEG-TS). */
    val containerFps: Double,
    /** mpv's measured output rate; noisy, a fallback only. */
    val estimatedFps: Double,
    val width: Int,
    val height: Int,
    /** mpv codec name of the selected video track, e.g. "h264", "hevc". */
    val codec: String,
    /** mpv `video-params/gamma`, e.g. "bt.1886", "pq", "hlg". */
    val gamma: String,
)

enum class DisplayDynamicRange { Sdr, Hlg, Pq }

/** What to ask the display for. Only [refreshRate] and [dynamicRange] name a display mode. */
data class TvDisplayCriteria(
    val refreshRate: Double,
    val dynamicRange: DisplayDynamicRange,
    val width: Int,
    val height: Int,
    val codec: String,
) {
    /** Same HDMI mode: re-asking would cost a blackout for nothing (Apple tech talk 503: switch only as often as necessary). */
    fun sameModeAs(other: TvDisplayCriteria): Boolean =
        abs(refreshRate - other.refreshRate) < SAME_RATE_HZ && dynamicRange == other.dynamicRange

    private companion object {
        const val SAME_RATE_HZ = 0.001
    }
}

object LiveDisplayCriteriaPolicy {
    /**
     * How long Live TV may be off screen before the display goes back to its default: covers the
     * moment between the fullscreen player closing and the guide reappearing (either order).
     */
    const val GRACE_MS = 2_000L

    private const val FILM_NTSC = 24000.0 / 1001
    private const val NTSC = 30000.0 / 1001
    private const val NTSC_DOUBLE = 60000.0 / 1001

    /**
     * Content rate -> output refresh, the highest exact multiple an Apple TV outputs. Apple: "25fps and
     * 30fps content uses frame rate doubling to display at 50Hz and 60Hz", so 25p/50i -> 50,
     * 29.97 -> 59.94, 30 -> 60; film plays at its own rate. Same choice as Android TV's selector.
     * Ordered fractional-first: an ambiguous measurement picks the broadcast (fractional) rate.
     */
    private val standardRates: List<Pair<Double, Double>> = listOf(
        FILM_NTSC to FILM_NTSC,
        24.0 to 24.0,
        25.0 to 50.0,
        NTSC to NTSC_DOUBLE,
        30.0 to 60.0,
        50.0 to 50.0,
        NTSC_DOUBLE to NTSC_DOUBLE,
        60.0 to 60.0,
    )

    /** A declared rate is exact (25, 29.97002997, or "29.97" written as a decimal). */
    private const val DECLARED_TOLERANCE = 0.01

    /** A measured rate wanders; wide enough for noise, narrow enough not to cross families (24/25, 25/29.97). */
    private const val MEASURED_TOLERANCE = 0.3

    fun criteria(format: TvVideoFormat?): TvDisplayCriteria? {
        if (format == null || format.width <= 0 || format.height <= 0) return null
        val refresh = refreshRate(format.containerFps, format.estimatedFps) ?: return null
        return TvDisplayCriteria(
            refreshRate = refresh,
            dynamicRange = dynamicRange(format.gamma),
            width = format.width,
            height = format.height,
            codec = format.codec,
        )
    }

    /** The output refresh for this content, or null when the rate is unknown or not a broadcast/film rate. */
    fun refreshRate(containerFps: Double, estimatedFps: Double): Double? =
        snap(containerFps, DECLARED_TOLERANCE) ?: snap(estimatedFps, MEASURED_TOLERANCE)

    private fun snap(fps: Double, tolerance: Double): Double? {
        if (!fps.isFinite() || fps <= 0.0) return null
        return standardRates.firstOrNull { (content, _) -> abs(fps - content) <= tolerance }?.second
    }

    /** mpv/FFmpeg name transfer functions both concisely ("pq", "hlg") and by standard (ST 2084, ARIB STD-B67). */
    fun dynamicRange(gamma: String): DisplayDynamicRange {
        val g = gamma.lowercase()
        return when {
            "pq" in g || "2084" in g -> DisplayDynamicRange.Pq
            "hlg" in g || "b67" in g -> DisplayDynamicRange.Hlg
            else -> DisplayDynamicRange.Sdr
        }
    }
}

/**
 * The sticky part, one per app: which criteria are set and whether Live TV is on screen.
 *
 * The fullscreen live player and the live guide each [enter] while shown and [exit] when gone. Only
 * the fullscreen player [offer]s criteria (the guide's small preview never switches the TV, as on
 * Android); the guide only keeps a mode already matched, so fullscreen <-> guide and channel zaps
 * cost no HDMI blackout. When the last one leaves, Swift waits [LiveDisplayCriteriaPolicy.GRACE_MS]
 * and asks [graceElapsed]; backgrounding clears at once.
 */
class LiveDisplayCriteriaTracker {
    var applied: TvDisplayCriteria? = null
        private set

    private var holders = 0

    fun enter() {
        holders++
    }

    /** True when the last live screen left with criteria set: schedule [graceElapsed]. */
    fun exit(): Boolean {
        if (holders > 0) holders--
        return holders == 0 && applied != null
    }

    /** True = set the display back to default now (nothing live came back during the grace). */
    fun graceElapsed(): Boolean {
        if (holders > 0 || applied == null) return false
        applied = null
        return true
    }

    /** True = clear now. The live screens stay registered: back in front, the next [offer] re-applies. */
    fun backgrounded(): Boolean {
        if (applied == null) return false
        applied = null
        return true
    }

    /**
     * The fullscreen live player's current criteria (polled). Returns what to set now, or null to leave
     * the display alone: unknown rate, Match Content off, Live TV not on screen, or the same mode.
     */
    fun offer(criteria: TvDisplayCriteria?, matchingEnabled: Boolean): TvDisplayCriteria? {
        if (criteria == null || !matchingEnabled || holders == 0) return null
        if (applied?.sameModeAs(criteria) == true) return null
        applied = criteria
        return criteria
    }
}
