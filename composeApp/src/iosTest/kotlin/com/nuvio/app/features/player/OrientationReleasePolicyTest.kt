package com.nuvio.app.features.player

import com.nuvio.app.features.player.OrientationPosture.LANDSCAPE
import com.nuvio.app.features.player.OrientationPosture.PORTRAIT
import com.nuvio.app.features.player.OrientationPosture.UNKNOWN
import kotlin.test.Test
import kotlin.test.assertEquals

class OrientationReleasePolicyTest {

    // B107 regression: the player forced landscape, the phone stayed upright, Back left the
    // stream list / detail page sideways because releasing the lock never rotated back.
    @Test
    fun upright_phone_rotates_back_to_portrait_after_the_landscape_player() {
        assertEquals(
            OrientationReleaseTarget.PORTRAIT,
            OrientationReleasePolicy.target(device = PORTRAIT, interfaceNow = LANDSCAPE, interfaceBeforeLock = PORTRAIT),
            "phone held upright must get a portrait interface when the player closes",
        )
    }

    @Test
    fun phone_flat_on_a_table_restores_the_orientation_from_before_the_player() {
        assertEquals(
            OrientationReleaseTarget.PORTRAIT,
            OrientationReleasePolicy.target(device = UNKNOWN, interfaceNow = LANDSCAPE, interfaceBeforeLock = PORTRAIT),
            "no device reading falls back to the pre-lock interface orientation",
        )
    }

    @Test
    fun phone_still_held_sideways_keeps_landscape() {
        assertEquals(
            OrientationReleaseTarget.KEEP,
            OrientationReleasePolicy.target(device = LANDSCAPE, interfaceNow = LANDSCAPE, interfaceBeforeLock = PORTRAIT),
            "a viewer still holding the phone sideways must not be flipped to portrait",
        )
    }

    @Test
    fun live_tv_portrait_lock_released_while_held_sideways_goes_landscape() {
        assertEquals(
            OrientationReleaseTarget.LANDSCAPE,
            OrientationReleasePolicy.target(device = LANDSCAPE, interfaceNow = PORTRAIT, interfaceBeforeLock = PORTRAIT),
            "the forced-portrait lock has the same stuck-orientation problem the other way round",
        )
    }

    @Test
    fun nothing_known_changes_nothing() {
        assertEquals(
            OrientationReleaseTarget.KEEP,
            OrientationReleasePolicy.target(device = UNKNOWN, interfaceNow = LANDSCAPE, interfaceBeforeLock = UNKNOWN),
            "without any orientation fact the release must not guess",
        )
    }
}
