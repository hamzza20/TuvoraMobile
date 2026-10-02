package com.nuvio.app.features.iptv

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * "Finishes by itself" on a TV code screen (contract section 9): the viewer scans the QR, redeems on a
 * phone, and the TV notices. The notice is one small read repeated on a slow clock, so the network rule
 * applies (delta-shaped, lifecycle-bound, decision extracted and tested):
 *
 * - while the screen is visible and signed in, ask `get_managed_playlists(profile)`: the first time after
 *   [firstDelayMs], then every [intervalMs]; never two requests at once;
 * - stop after [maxDurationMs] (<= [maxCalls] requests), when the screen is left, or when the code was
 *   typed on the TV itself (nothing left to wait for);
 * - [failuresBeforeBackoff] failures in a row slow the clock to [slowIntervalMs]; one answer restores it;
 * - success is a playlist key that was not in the [snapshot] taken when the screen opened.
 *
 * Pure: time is passed in, nothing here reads a clock or touches the network. [runSetupWait] is the
 * loop that drives it (also pure of I/O: the request is injected), so the whole behaviour tests on
 * virtual time. Byte-identical on every platform that has a code screen (TV, Apple TV).
 */
class SetupWaitPolicy(
    snapshot: Set<String>,
    private val startedAtMs: Long,
    private val firstDelayMs: Long = FIRST_DELAY_MS,
    private val intervalMs: Long = INTERVAL_MS,
    private val slowIntervalMs: Long = SLOW_INTERVAL_MS,
    private val maxDurationMs: Long = MAX_DURATION_MS,
    private val maxCalls: Int = MAX_CALLS,
    private val failuresBeforeBackoff: Int = FAILURES_BEFORE_BACKOFF,
) {
    private val known: Set<String> = snapshot.toSet()

    enum class StopReason { SUCCESS, TIMEOUT, CALL_CAP, LEFT, TYPED_HERE }

    sealed interface Step {
        /** Nothing to ask before [untilMs] (the policy's clock; the caller sleeps until then). */
        data class Wait(val untilMs: Long) : Step
        /** Make one request now, bracketed by [requestStarted] and [requestSucceeded] / [requestFailed]. */
        data object Poll : Step
        /** A request is already in flight: ask nothing until it is answered. */
        data object Busy : Step
        data class Stop(val reason: StopReason) : Step
    }

    private var stopped: StopReason? = null
    private var inFlight = false
    private var calls = 0
    private var consecutiveFailures = 0
    private var nextAtMs: Long = startedAtMs + firstDelayMs

    fun step(nowMs: Long): Step {
        stopped?.let { return Step.Stop(it) }
        if (inFlight) return Step.Busy
        // Time first: a poll that could only happen after the cap is never made, and the reason is the cap.
        if (nowMs - startedAtMs >= maxDurationMs || nextAtMs - startedAtMs > maxDurationMs) return stop(StopReason.TIMEOUT)
        if (calls >= maxCalls) return stop(StopReason.CALL_CAP)
        return if (nowMs >= nextAtMs) Step.Poll else Step.Wait(nextAtMs)
    }

    fun requestStarted(nowMs: Long) {
        inFlight = true
        calls++
    }

    /** The read was answered with [keys]: returns the keys that were not in the snapshot (success when not empty). */
    fun requestSucceeded(nowMs: Long, keys: Collection<String>): List<String> {
        inFlight = false
        // An answer that lands after the screen was left (or the wait ended some other way) reports nothing.
        if (stopped != null) return emptyList()
        consecutiveFailures = 0
        nextAtMs = nowMs + intervalMs
        val fresh = keys.filter { it !in known }.distinct().sorted()
        if (fresh.isNotEmpty()) stopped = StopReason.SUCCESS
        return fresh
    }

    fun requestFailed(nowMs: Long) {
        inFlight = false
        consecutiveFailures++
        nextAtMs = nowMs + if (consecutiveFailures >= failuresBeforeBackoff) slowIntervalMs else intervalMs
    }

    /** The screen was left (or went to the background): nothing more is asked, ever. */
    fun leave() {
        if (stopped == null) stopped = StopReason.LEFT
    }

    /** The viewer started typing the code on the TV: there is nothing left to wait for. */
    fun codeTypedHere() {
        if (stopped == null) stopped = StopReason.TYPED_HERE
    }

    private fun stop(reason: StopReason): Step {
        if (stopped == null) stopped = reason
        return Step.Stop(stopped!!)
    }

    companion object {
        const val FIRST_DELAY_MS = 3_000L
        const val INTERVAL_MS = 6_000L
        const val SLOW_INTERVAL_MS = 30_000L
        const val MAX_DURATION_MS = 300_000L
        const val MAX_CALLS = 50
        const val FAILURES_BEFORE_BACKOFF = 3
    }
}

/** How a [runSetupWait] ended. */
sealed interface SetupWaitResult {
    /** A playlist appeared that was not there when the screen opened. */
    data class Found(val newKeys: List<String>) : SetupWaitResult
    data class Stopped(val reason: SetupWaitPolicy.StopReason) : SetupWaitResult
}

/**
 * Drives [policy]: sleeps until a poll is due, makes ONE [fetchKeys] request at a time (a throw is a
 * failure: back-off, never a crash), and returns when the policy stops. Cancelling the caller's coroutine
 * (leaving the screen) ends it at once and no request is made afterwards.
 */
suspend fun runSetupWait(
    policy: SetupWaitPolicy,
    nowMs: () -> Long,
    fetchKeys: suspend () -> Set<String>,
): SetupWaitResult {
    try {
        while (true) {
            when (val step = policy.step(nowMs())) {
                is SetupWaitPolicy.Step.Stop -> return SetupWaitResult.Stopped(step.reason)
                SetupWaitPolicy.Step.Busy -> delay(100)
                is SetupWaitPolicy.Step.Wait -> delay((step.untilMs - nowMs()).coerceAtLeast(1))
                SetupWaitPolicy.Step.Poll -> {
                    policy.requestStarted(nowMs())
                    val keys = try {
                        fetchKeys()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        policy.requestFailed(nowMs())
                        continue
                    }
                    val fresh = policy.requestSucceeded(nowMs(), keys)
                    if (fresh.isNotEmpty()) return SetupWaitResult.Found(fresh)
                }
            }
        }
    } catch (e: CancellationException) {
        policy.leave()
        throw e
    }
}
