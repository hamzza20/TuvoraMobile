package com.tuvora.tvos.player

/**
 * Apple TV's player announces a playback to sources that keep their own watched state (media servers) from the
 * same moments the phone does: a start once it is playing, then a local tick - the reporter's own pure policy
 * (ProgressReportPolicy: playing every ~15 s, paused every ~60 s) decides whether anything is sent. Pure, so the
 * decision tests without the player.
 */
object TvSessionReportPolicy {
    enum class Action { NONE, START, TICK }

    const val TICK_MS = 5_000L
    private const val RESUME_NEAR_MS = 30_000L

    fun decide(
        owned: Boolean,
        live: Boolean,
        closed: Boolean,
        reported: Boolean,
        playing: Boolean,
        ended: Boolean,
        durationMs: Long,
        nowMs: Long,
        lastTickMs: Long,
    ): Action {
        if (!owned || live || closed || durationMs <= 0L) return Action.NONE
        if (!reported) return if (playing) Action.START else Action.NONE
        if (ended) return Action.NONE
        return if (nowMs - lastTickMs >= TICK_MS) Action.TICK else Action.NONE
    }

    /** The viewer is already within 30 s of the server's position: nothing to offer. */
    fun shouldOfferResume(currentMs: Long, offeredMs: Long): Boolean = currentMs < offeredMs - RESUME_NEAR_MS
}
