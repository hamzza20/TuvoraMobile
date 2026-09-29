package com.tuvora.tvos.player

import com.nuvio.app.features.player.skip.AutoSkipSegmentType
import com.nuvio.app.features.player.skip.SkipInterval
import com.nuvio.app.features.player.skip.internalSkipAction
import com.nuvio.app.features.player.skip.shouldAutoSkip

/** The segment the viewer is in and where "Skip" goes (NuvioTV SkipIntroButton). */
data class TvSkipSegment(
    val label: String,
    val targetMs: Long,
    val startMs: Long,
)

/** What one playback tick decided: the segment to offer, and whether to jump now (auto-skip). */
data class TvSkipDecision(val segment: TvSkipSegment?, val autoSkipToMs: Long?, val interval: SkipInterval?)

/**
 * The phone runtime's skip-segment rules (PlayerScreenRuntimeEffects.kt), pure: the active interval is
 * the one covering the position that has a skip action; it auto-skips once when its type is in the
 * profile's auto-skip set and playback is running. Labels are NuvioTV's (SkipIntroButton.getSkipLabel).
 */
object TvSkipPolicy {
    fun decide(
        positionMs: Long,
        durationMs: Long,
        isPlaying: Boolean,
        intervals: List<SkipInterval>,
        autoSkipTypes: Set<AutoSkipSegmentType>,
        alreadyAutoSkipped: Set<SkipInterval>,
    ): TvSkipDecision {
        if (intervals.isEmpty()) return TvSkipDecision(null, null, null)
        val positionSec = positionMs / 1000.0
        val current = intervals.firstOrNull { interval ->
            positionSec >= interval.startTime && positionSec < interval.endTime &&
                interval.internalSkipAction(intervals, durationMs) != null
        } ?: return TvSkipDecision(null, null, null)
        val action = current.internalSkipAction(intervals, durationMs) ?: return TvSkipDecision(null, null, null)
        val target = if (durationMs > 0L) action.targetMs.coerceAtMost(durationMs - 1) else action.targetMs
        val segment = TvSkipSegment(label(current.type, action.skipsToPostCredits), target, (current.startTime * 1000).toLong())
        val auto = isPlaying && current.shouldAutoSkip(autoSkipTypes) && current !in alreadyAutoSkipped
        return TvSkipDecision(segment, if (auto) target else null, current)
    }

    fun label(type: String, skipsToPostCredits: Boolean = false): String {
        if (skipsToPostCredits) return "Skip to Post-Credits"
        return when (type.trim().lowercase()) {
            "op", "opening", "mixed-op", "intro" -> "Skip Intro"
            "ed", "ending", "mixed-ed", "outro", "credits" -> "Skip Ending"
            "recap" -> "Skip Recap"
            "movie-credits" -> "Skip Credits"
            else -> "Skip"
        }
    }
}

/** NuvioTV's speed dialog values (PlayerUiState.PLAYBACK_SPEEDS) and label ("Normal" / "1.5x"). */
object TvPlaybackSpeeds {
    val values: List<Float> = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
    fun label(speed: Float): String = if (speed == 1f) "Normal" else "${speed}x"
    /** The listed speed nearest to [current] (an engine may report 1.0000001). */
    fun nearest(current: Float): Float = values.minBy { kotlin.math.abs(it - current) }
}

/** NuvioTV's subtitle delay control: ±100 ms steps within ±60 s ("+0.3s" / "-1.2s"). */
object TvSubtitleDelay {
    const val STEP_MS = 100
    const val MIN_MS = -60_000
    const val MAX_MS = 60_000
    fun step(currentMs: Int, up: Boolean): Int = (currentMs + if (up) STEP_MS else -STEP_MS).coerceIn(MIN_MS, MAX_MS)
    fun label(ms: Int): String {
        val tenths = kotlin.math.abs(ms) / 100
        val text = "${tenths / 10}.${tenths % 10}s"
        return when { ms > 0 -> "+$text"; ms < 0 -> "-$text"; else -> "0.0s" }
    }
}

/**
 * When the profile's subtitle language may pick the track: once, when the first subtitle track shows
 * up, and never after the viewer chose one themselves — an add-on subtitle lands as a new track, and
 * applying the preference then switched the viewer's pick straight off.
 */
object TvSubtitleSelectionPolicy {
    fun appliesProfileLanguage(userPicked: Boolean, alreadyApplied: Boolean, trackCount: Int): Boolean =
        !userPicked && !alreadyApplied && trackCount > 0
}
