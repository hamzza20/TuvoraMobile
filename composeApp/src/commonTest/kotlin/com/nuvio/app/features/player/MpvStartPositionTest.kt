package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MpvStartPositionTest {

    @Test
    fun a_resume_is_delivered_as_a_load_time_start_option() {
        // B59b: the resume must travel with loadfile, not as a later seek mpv may reject.
        assertEquals("start=99.240", MpvStartPosition.loadOption(99_240L, isLiveStream = false))
        assertEquals("start=0.005", MpvStartPosition.loadOption(5L, isLiveStream = false))
        assertEquals("start=754.000", MpvStartPosition.loadOption(754_000L, isLiveStream = false))
    }

    @Test
    fun no_position_means_play_from_the_start() {
        assertNull(MpvStartPosition.loadOption(null, isLiveStream = false))
        assertNull(MpvStartPosition.loadOption(0L, isLiveStream = false))
        assertNull(MpvStartPosition.loadOption(-1L, isLiveStream = false))
    }

    @Test
    fun live_streams_never_carry_a_start_offset() {
        assertNull(MpvStartPosition.loadOption(99_240L, isLiveStream = true))
    }
}
