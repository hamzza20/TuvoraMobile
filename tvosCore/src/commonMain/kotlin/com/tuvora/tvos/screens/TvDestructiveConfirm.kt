package com.tuvora.tvos.screens

import com.nuvio.app.features.iptv.DestructiveAction
import com.nuvio.app.features.iptv.DestructiveConfirmPolicy
import com.nuvio.app.features.iptv.HoldToConfirmPolicy

/**
 * Detach and Remove playlist on Apple TV (contract section 7): typing a word with a remote costs about
 * fifteen presses, so the destructive button instead needs OK HELD for two seconds, with a ring that
 * fills while it is held. Releasing early, or a quick press, does nothing. The numbers live in the shared
 * [HoldToConfirmPolicy]; this is Swift's door to it (Swift cannot reach a Kotlin companion constant).
 */
object TvHoldToConfirm {
    private val policy = HoldToConfirmPolicy()

    /** How long OK must be held. */
    val HOLD_MS: Long get() = policy.holdMs

    /** The ring's fill (0..1) after OK has been held [heldMs]. */
    fun progress(heldMs: Long): Float = policy.progress(heldMs)

    /** True once OK has been held long enough to confirm. */
    fun isConfirmed(heldMs: Long): Boolean = policy.isConfirmed(heldMs)
}

/** The sentences of a confirmation dialog; [extra] is the second paragraph when there is one. */
data class TvConfirmCopy(val title: String, val message: String, val extra: String?)

/** The shared [DestructiveConfirmPolicy] copy, as plain values for the SwiftUI dialog. */
object TvDestructiveCopy {
    fun detach(playlistName: String, providerName: String?): TvConfirmCopy =
        DestructiveConfirmPolicy.copy(DestructiveAction.DETACH, playlistName, providerName).let { TvConfirmCopy(it.title, it.message, it.extra) }

    fun remove(playlistName: String, providerName: String?, managed: Boolean): TvConfirmCopy =
        DestructiveConfirmPolicy.copy(DestructiveAction.REMOVE, playlistName, providerName, managed).let { TvConfirmCopy(it.title, it.message, it.extra) }
}
