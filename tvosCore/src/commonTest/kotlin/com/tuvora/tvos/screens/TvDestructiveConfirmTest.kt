package com.tuvora.tvos.screens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Hold OK for two seconds to detach or remove (contract section 7); the golden table is the shared one. */
class TvDestructiveConfirmTest {

    @Test
    fun `the ring fills over two seconds and only a full hold confirms`() {
        assertEquals(2_000L, TvHoldToConfirm.HOLD_MS)
        assertEquals(0.0f, TvHoldToConfirm.progress(0))
        assertEquals(0.5f, TvHoldToConfirm.progress(1_000))
        assertTrue(TvHoldToConfirm.progress(1_999) in 0.999f..0.9999f)
        assertEquals(1.0f, TvHoldToConfirm.progress(2_000))
        assertEquals(1.0f, TvHoldToConfirm.progress(3_000))
        assertFalse(TvHoldToConfirm.isConfirmed(0))
        assertFalse(TvHoldToConfirm.isConfirmed(1_000))
        assertFalse(TvHoldToConfirm.isConfirmed(1_999))
        assertTrue(TvHoldToConfirm.isConfirmed(2_000))
        assertTrue(TvHoldToConfirm.isConfirmed(3_000))
    }

    @Test
    fun `a quick press holds for a moment and never confirms`() {
        assertFalse(TvHoldToConfirm.isConfirmed(120))
        assertFalse(TvHoldToConfirm.isConfirmed(600))
    }

    @Test
    fun `detach copy names the provider and keeps the playlist`() {
        val copy = TvDestructiveCopy.detach("Acme Live", "Starshare")
        assertEquals("Detach from Starshare?", copy.title)
        assertEquals("You keep this playlist, but Starshare can't update it any more. If their server changes you would need to update it yourself.", copy.message)
        assertNull(copy.extra)
    }

    @Test
    fun `remove copy warns a managed playlist may need a new code`() {
        val plain = TvDestructiveCopy.remove("My list", null, managed = false)
        assertEquals("Remove My list?", plain.title)
        assertEquals("This deletes the playlist from this profile on all your devices.", plain.message)
        assertNull(plain.extra)
        assertEquals("You may need a new code from Starshare to get it back.", TvDestructiveCopy.remove("Acme Live", "Starshare", managed = true).extra)
    }
}
