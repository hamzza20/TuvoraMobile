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

    // B107 follow-up (wave-1 device pass 2026-10-03): Live TV docked -> fullscreen -> exit fullscreen
    // -> Back left the IPTV hub sideways with the phone flat. Exiting fullscreen is an unlock (rotate
    // to portrait, asynchronous) immediately followed by the portrait lock, which read the interface
    // while it was still landscape and remembered LANDSCAPE as "before the player".
    @Test
    fun lock_right_after_a_release_remembers_where_the_release_is_rotating_to() {
        val exitFullscreen = OrientationReleasePolicy.target(device = UNKNOWN, interfaceNow = LANDSCAPE, interfaceBeforeLock = PORTRAIT)
        assertEquals(OrientationReleaseTarget.PORTRAIT, exitFullscreen, "leaving fullscreen goes back to portrait")
        val settling = OrientationReleasePolicy.settledPosture(exitFullscreen, interfaceNow = LANDSCAPE)

        val remembered = OrientationReleasePolicy.preLockPosture(interfaceNow = LANDSCAPE, settlingTo = settling)
        assertEquals(PORTRAIT, remembered, "the in-flight rotation's target, not the stale landscape, is the pre-lock posture")

        assertEquals(
            OrientationReleaseTarget.KEEP,
            OrientationReleasePolicy.target(device = UNKNOWN, interfaceNow = PORTRAIT, interfaceBeforeLock = remembered),
            "Back from Live TV must leave the hub in portrait",
        )
    }

    @Test
    fun lock_with_no_release_in_flight_remembers_the_interface_as_it_is() {
        assertEquals(LANDSCAPE, OrientationReleasePolicy.preLockPosture(interfaceNow = LANDSCAPE, settlingTo = null))
    }

    @Test
    fun keep_release_settles_where_the_interface_already_is() {
        assertEquals(LANDSCAPE, OrientationReleasePolicy.settledPosture(OrientationReleaseTarget.KEEP, interfaceNow = LANDSCAPE))
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
