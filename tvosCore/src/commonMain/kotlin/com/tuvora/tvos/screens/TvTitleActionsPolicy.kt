package com.tuvora.tvos.screens

import com.nuvio.app.features.tracking.TrackingMembershipRemovalImpact

/** NuvioTV's tracking-removal confirmation (PosterOptionsDialog.kt TrackingRemovalDialog), as text. */
data class TvRemovalConfirmation(val title: String, val message: String)

/**
 * The wording NuvioTV shows before removing a title from a tracking provider's library would also clear
 * its watched history or rating there. Pure, so it is tested without providers.
 */
object TvTitleActionsPolicy {
    fun removalConfirmation(itemTitle: String, providerNames: List<String>, impacts: Set<TrackingMembershipRemovalImpact>): TvRemovalConfirmation {
        val providers = providerNames.distinct().joinToString()
        val impact = when (impacts) {
            setOf(TrackingMembershipRemovalImpact.WATCHED_HISTORY) -> "watched history"
            setOf(TrackingMembershipRemovalImpact.RATING) -> "rating"
            else -> "watched history and rating"
        }
        return TvRemovalConfirmation(
            title = "Remove from $providers?",
            message = "Removing “$itemTitle” from $providers will also clear its $impact there. This can’t be undone.",
        )
    }
}
