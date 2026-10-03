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
}
