package com.tuvora.tvos.screens

import com.nuvio.app.features.profiles.PinVerifyResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvProfilePinPolicyTest {
    @Test
    fun `digits stop at four and are ignored while verifying`() {
        var pin = ""
        "12345".forEach { pin = TvProfilePinPolicy.append(pin, it, isWorking = false) }
        assertEquals("1234", pin)
        assertTrue(TvProfilePinPolicy.isComplete(pin))
        assertEquals("12", TvProfilePinPolicy.append("12", '3', isWorking = true))
        assertEquals("12", TvProfilePinPolicy.append("12", 'x', isWorking = false))
    }

    @Test
    fun `backspace drops one digit unless a verify is in flight`() {
        assertEquals("12", TvProfilePinPolicy.backspace("123", isWorking = false))
        assertEquals("123", TvProfilePinPolicy.backspace("123", isWorking = true))
        assertEquals("", TvProfilePinPolicy.backspace("", isWorking = false))
        assertFalse(TvProfilePinPolicy.isComplete("123"))
    }

    @Test
    fun `verify results map to the TV app messages`() {
        assertEquals(TvPinOutcome(TvPinOutcomeKind.Unlocked), TvProfilePinPolicy.outcome(PinVerifyResult(unlocked = true)))
        assertEquals(TvPinOutcome(TvPinOutcomeKind.Incorrect), TvProfilePinPolicy.outcome(PinVerifyResult(unlocked = false)))
        assertEquals(TvPinOutcome(TvPinOutcomeKind.Locked, 30), TvProfilePinPolicy.outcome(PinVerifyResult(unlocked = false, retryAfterSeconds = 30)))
        assertEquals(TvPinOutcome(TvPinOutcomeKind.VerifyFailed), TvProfilePinPolicy.outcome(null))
    }

    @Test
    fun `the profile row goes compact then scrolls as profiles are added`() {
        // Apple TV content width 1760 pt; NuvioTV cards 152dp gap 28dp, compact 128dp gap 12dp (x2).
        fun layout(n: Int) = TvProfileGridLayout.layout(n, 1760f, 304f, 256f, 56f, 24f)
        assertEquals(TvProfileGridLayout.Layout(compact = false, gap = 56f, scrollable = false), layout(4))
        assertEquals(TvProfileGridLayout.Layout(compact = false, gap = 24f, scrollable = false), TvProfileGridLayout.layout(5, 1700f, 304f, 256f, 56f, 24f))
        assertEquals(TvProfileGridLayout.Layout(compact = true, gap = 24f, scrollable = false), layout(6))
        assertTrue(layout(8).scrollable)
    }
}
