package com.nuvio.app.features.player

/** Which way the phone (device) or the app's interface is facing, OS-neutral. */
enum class OrientationPosture { PORTRAIT, LANDSCAPE, UNKNOWN }

/** What the iOS orientation coordinator should rotate the interface to when a player lock ends. */
enum class OrientationReleaseTarget { PORTRAIT, LANDSCAPE, KEEP }

/**
 * B107: when the player's landscape (or Live TV's portrait) lock ends, iOS only widens the allowed
 * orientations — it does not rotate back by itself until the device physically moves. A phone held
 * upright therefore kept the stream list, detail page and tabs sideways until relaunch.
 *
 * Pure decision (no UIKit) so it is unit-testable; `OrientationLockCoordinator.swift` feeds it
 * the device posture and the interface posture now / before the lock, then requests the geometry.
 */
object OrientationReleasePolicy {
    fun target(
        device: OrientationPosture,
        interfaceNow: OrientationPosture,
        interfaceBeforeLock: OrientationPosture,
    ): OrientationReleaseTarget {
        // Follow how the phone is held; when it can't say (flat on a table, unknown, or the
        // user's rotation lock), go back to how the app looked before the player took over.
        val wanted = if (device != OrientationPosture.UNKNOWN) device else interfaceBeforeLock
        return when {
            wanted == OrientationPosture.UNKNOWN -> OrientationReleaseTarget.KEEP
            wanted == interfaceNow -> OrientationReleaseTarget.KEEP
            wanted == OrientationPosture.PORTRAIT -> OrientationReleaseTarget.PORTRAIT
            else -> OrientationReleaseTarget.LANDSCAPE
        }
    }

    /** Where the interface ends up once [target] has been applied (the rotation is asynchronous). */
    fun settledPosture(target: OrientationReleaseTarget, interfaceNow: OrientationPosture): OrientationPosture =
        when (target) {
            OrientationReleaseTarget.PORTRAIT -> OrientationPosture.PORTRAIT
            OrientationReleaseTarget.LANDSCAPE -> OrientationPosture.LANDSCAPE
            OrientationReleaseTarget.KEEP -> interfaceNow
        }

    /**
     * The posture a new player lock remembers as "how the app looked before". Live TV switches mode
     * as an unlock immediately followed by a lock (exit fullscreen = release + portrait lock); the
     * release's rotation is still in flight then, so the interface still reads the old posture.
     * [settlingTo] is where that release is rotating to (null when no release is in flight) and wins.
     */
    fun preLockPosture(interfaceNow: OrientationPosture, settlingTo: OrientationPosture?): OrientationPosture =
        settlingTo ?: interfaceNow
}
