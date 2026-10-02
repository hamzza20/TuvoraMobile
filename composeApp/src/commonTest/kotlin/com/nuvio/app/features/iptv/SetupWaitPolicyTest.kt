package com.nuvio.app.features.iptv

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Contract section 9 (network rule: lifecycle-bound, delta-shaped, tested): while the code screen is
 * visible the TV asks `get_managed_playlists` (first after 3 s, then every 6 s), one request at a time,
 * for at most 300 s / 50 calls, backing off to 30 s after 3 failures in a row, and finishes when a
 * playlist key appears that was not in the snapshot taken when the screen opened. All tests run on
 * virtual time: nothing here touches a network or a real clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SetupWaitPolicyTest {

    private val known = setOf("k1", "k2")

    // --- the pure policy -------------------------------------------------------------------------

    @Test
    fun `nothing is asked before the first delay and the first poll is due at 3 s`() {
        val policy = SetupWaitPolicy(known, startedAtMs = 0)
        assertEquals(SetupWaitPolicy.Step.Wait(3_000), policy.step(0))
        assertEquals(SetupWaitPolicy.Step.Wait(3_000), policy.step(2_999))
        assertEquals(SetupWaitPolicy.Step.Poll, policy.step(3_000))
    }

    @Test
    fun `after an answer the next poll is six seconds later`() {
        val policy = SetupWaitPolicy(known, 0)
        policy.requestStarted(3_000)
        assertEquals(emptyList(), policy.requestSucceeded(3_400, known))
        assertEquals(SetupWaitPolicy.Step.Wait(9_400), policy.step(3_400))
        assertEquals(SetupWaitPolicy.Step.Poll, policy.step(9_400))
    }

    @Test
    fun `no second request while one is in flight`() {
        val policy = SetupWaitPolicy(known, 0)
        policy.requestStarted(3_000)
        assertEquals(SetupWaitPolicy.Step.Busy, policy.step(3_001))
        assertEquals(SetupWaitPolicy.Step.Busy, policy.step(60_000))
    }

    @Test
    fun `three failures in a row slow the interval to 30 s and one success restores it`() {
        val policy = SetupWaitPolicy(known, 0)
        var now = 3_000L
        repeat(2) {
            policy.requestStarted(now)
            policy.requestFailed(now)
            assertEquals(SetupWaitPolicy.Step.Wait(now + 6_000), policy.step(now))
            now += 6_000
        }
        policy.requestStarted(now)
        policy.requestFailed(now)
        assertEquals(SetupWaitPolicy.Step.Wait(now + 30_000), policy.step(now))
        now += 30_000
        policy.requestStarted(now)
        policy.requestSucceeded(now, known)
        assertEquals(SetupWaitPolicy.Step.Wait(now + 6_000), policy.step(now))
    }

    @Test
    fun `a failure then a success then two failures does not back off`() {
        val policy = SetupWaitPolicy(known, 0)
        var now = 3_000L
        fun call(ok: Boolean) {
            policy.requestStarted(now)
            if (ok) policy.requestSucceeded(now, known) else policy.requestFailed(now)
        }
        call(false); call(true); call(false); call(false)
        assertEquals(SetupWaitPolicy.Step.Wait(now + 6_000), policy.step(now))
    }

    @Test
    fun `a key that was not in the snapshot is success and stops the wait`() {
        val policy = SetupWaitPolicy(known, 0)
        policy.requestStarted(3_000)
        assertEquals(listOf("k3"), policy.requestSucceeded(3_500, known + "k3"))
        assertEquals(SetupWaitPolicy.Step.Stop(SetupWaitPolicy.StopReason.SUCCESS), policy.step(3_500))
    }

    @Test
    fun `keys already in the snapshot and removed keys are not success`() {
        val policy = SetupWaitPolicy(known, 0)
        policy.requestStarted(3_000)
        assertEquals(emptyList(), policy.requestSucceeded(3_100, setOf("k1")))
        assertTrue(policy.step(9_100) !is SetupWaitPolicy.Step.Stop)
    }

    @Test
    fun `an empty snapshot treats any returned key as new`() {
        val policy = SetupWaitPolicy(emptySet(), 0)
        policy.requestStarted(3_000)
        assertEquals(listOf("a", "b"), policy.requestSucceeded(3_100, setOf("b", "a")))
    }

    @Test
    fun `the wait ends at 300 s`() {
        val policy = SetupWaitPolicy(known, 0)
        assertEquals(SetupWaitPolicy.Step.Stop(SetupWaitPolicy.StopReason.TIMEOUT), policy.step(300_000))
        assertEquals(SetupWaitPolicy.Step.Stop(SetupWaitPolicy.StopReason.TIMEOUT), policy.step(900_000))
    }

    @Test
    fun `a poll that would be due after the cap is never made`() {
        val policy = SetupWaitPolicy(known, 0)
        policy.requestStarted(297_000)
        policy.requestSucceeded(297_200, known)
        // next would be 303.2 s: past the cap, so stop now instead of waiting for it.
        assertEquals(SetupWaitPolicy.Step.Stop(SetupWaitPolicy.StopReason.TIMEOUT), policy.step(297_200))
    }

    @Test
    fun `the call cap stops the wait even inside the time cap`() {
        val policy = SetupWaitPolicy(known, 0, firstDelayMs = 1_000, intervalMs = 1_000, maxCalls = 3)
        var now = 1_000L
        repeat(3) {
            assertEquals(SetupWaitPolicy.Step.Poll, policy.step(now))
            policy.requestStarted(now)
            policy.requestSucceeded(now, known)
            now += 1_000
        }
        assertEquals(SetupWaitPolicy.Step.Stop(SetupWaitPolicy.StopReason.CALL_CAP), policy.step(now))
    }

    @Test
    fun `leaving the screen stops the wait for good`() {
        val policy = SetupWaitPolicy(known, 0)
        policy.leave()
        assertEquals(SetupWaitPolicy.Step.Stop(SetupWaitPolicy.StopReason.LEFT), policy.step(3_000))
    }

    @Test
    fun `typing the code on the TV stops the wait`() {
        val policy = SetupWaitPolicy(known, 0)
        policy.codeTypedHere()
        assertEquals(SetupWaitPolicy.Step.Stop(SetupWaitPolicy.StopReason.TYPED_HERE), policy.step(3_000))
    }

    @Test
    fun `the first reason wins`() {
        val policy = SetupWaitPolicy(known, 0)
        policy.leave()
        policy.codeTypedHere()
        assertEquals(SetupWaitPolicy.Step.Stop(SetupWaitPolicy.StopReason.LEFT), policy.step(0))
    }

    // --- the loop that drives it, on virtual time -------------------------------------------------

    private class Probe(private val answer: (Int) -> Set<String>) {
        val callTimes = mutableListOf<Long>()
        var inFlight = 0
        var maxInFlight = 0
        suspend fun call(scope: TestScope, latencyMs: Long = 0): Set<String> {
            callTimes += scope.currentTime
            inFlight++
            maxInFlight = maxOf(maxInFlight, inFlight)
            try {
                if (latencyMs > 0) delay(latencyMs)
                return answer(callTimes.size)
            } finally {
                inFlight--
            }
        }
    }

    @Test
    fun `an idle slate polls at 3 s then every 6 s`() = runTest {
        val probe = Probe { emptySet() }
        val job = async { runSetupWait(SetupWaitPolicy(known, 0), { currentTime }) { probe.call(this@runTest) } }
        advanceTimeBy(21_000); runCurrent()
        assertEquals(listOf(3_000L, 9_000L, 15_000L, 21_000L), probe.callTimes)
        job.cancel()
    }

    @Test
    fun `no request after the screen is left`() = runTest {
        val probe = Probe { emptySet() }
        val job = async { runSetupWait(SetupWaitPolicy(known, 0), { currentTime }) { probe.call(this@runTest) } }
        advanceTimeBy(10_000); runCurrent()
        val before = probe.callTimes.size
        job.cancel()
        advanceTimeBy(600_000); runCurrent()
        assertEquals(before, probe.callTimes.size, "a cancelled wait must not ask again")
    }

    @Test
    fun `requests never overlap even when an answer is slow`() = runTest {
        val probe = Probe { emptySet() }
        val job = async { runSetupWait(SetupWaitPolicy(known, 0), { currentTime }) { probe.call(this@runTest, latencyMs = 20_000) } }
        advanceTimeBy(120_000); runCurrent()
        assertEquals(1, probe.maxInFlight)
        // 3 s start, 20 s latency, then 6 s gap: 3, 29, 55, 81, 107
        assertEquals(listOf(3_000L, 29_000L, 55_000L, 81_000L, 107_000L), probe.callTimes)
        job.cancel()
    }

    @Test
    fun `stops by itself at 300 s with at most 50 calls`() = runTest {
        val probe = Probe { emptySet() }
        val result = async { runSetupWait(SetupWaitPolicy(known, 0), { currentTime }) { probe.call(this@runTest) } }
        advanceTimeBy(600_000); runCurrent()
        val done = result.await()
        assertEquals(SetupWaitResult.Stopped(SetupWaitPolicy.StopReason.TIMEOUT), done)
        assertTrue(probe.callTimes.size <= 50, "calls=${probe.callTimes.size}")
        assertTrue(probe.callTimes.all { it <= 300_000 })
    }

    @Test
    fun `finds the new playlist and stops asking`() = runTest {
        val probe = Probe { n -> if (n >= 3) known + "fresh" else known }
        val result = async { runSetupWait(SetupWaitPolicy(known, 0), { currentTime }) { probe.call(this@runTest) } }
        advanceTimeBy(100_000); runCurrent()
        assertEquals(SetupWaitResult.Found(listOf("fresh")), result.await())
        assertEquals(3, probe.callTimes.size)
        assertEquals(listOf(3_000L, 9_000L, 15_000L), probe.callTimes)
    }

    @Test
    fun `failures back off to 30 s and the loop keeps going`() = runTest {
        var n = 0
        val times = mutableListOf<Long>()
        val job = async {
            runSetupWait(SetupWaitPolicy(known, 0), { currentTime }) {
                times += currentTime
                n++
                throw IllegalStateException("offline")
            }
        }
        advanceTimeBy(80_000); runCurrent()
        // 3, 9, 15 (three failures), then 30 s gaps: 45, 75
        assertEquals(listOf(3_000L, 9_000L, 15_000L, 45_000L, 75_000L), times)
        job.cancel()
    }

    @Test
    fun `a stopped wait result carries why`() = runTest {
        val policy = SetupWaitPolicy(known, 0)
        policy.codeTypedHere()
        val result = runSetupWait(policy, { currentTime }) { error("must not be called") }
        assertIs<SetupWaitResult.Stopped>(result)
        assertEquals(SetupWaitPolicy.StopReason.TYPED_HERE, result.reason)
    }
}
