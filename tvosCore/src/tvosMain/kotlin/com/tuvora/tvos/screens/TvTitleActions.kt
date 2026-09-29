package com.tuvora.tvos.screens

import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.library.toLibraryItem
import com.nuvio.app.features.tracking.TrackingProviderRegistry
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watching.application.WatchingActions

/** What a library toggle did: done, or it needs the viewer to confirm a tracking-provider removal. */
data class TvSaveResult(val confirmation: TvRemovalConfirmation?)

/**
 * NuvioTV's title actions menu (posteroptions/PosterOptionsDialog.kt: Go to details, Add to / Remove
 * from library, Mark as watched / unwatched) for any poster, over the phone's own actions —
 * LibraryRepository.toggleSaved (routed to Trakt / Simkl / MDBList when that is the library source) and
 * WatchingActions.togglePosterWatched.
 */
object TvTitleActions {
    fun isSaved(preview: MetaPreview): Boolean = LibraryRepository.isSaved(preview.id, preview.type)

    fun isWatched(preview: MetaPreview): Boolean =
        WatchedRepository.isWatched(id = preview.id, type = preview.type) ||
            WatchedRepository.isFullyWatchedSeries(id = preview.id, type = preview.type)

    /** Movies and series can be marked watched; live channels and other types cannot. */
    fun canMarkWatched(preview: MetaPreview): Boolean = preview.type.lowercase() in setOf("movie", "series", "show", "tv_series", "anime")

    /**
     * Toggles the title in the library. When removing it from a tracking provider would also clear
     * its history or rating there, nothing changes and the confirmation comes back; call again with
     * [confirmed] = true once the viewer agrees (NuvioTV "Remove anyway").
     */
    suspend fun toggleSaved(preview: MetaPreview, confirmed: Boolean): TvSaveResult {
        val item = LibraryRepository.savedItem(preview.id)?.takeIf { it.type == preview.type } ?: preview.toItem()
        return toggle(item, preview.name, confirmed)
    }

    suspend fun toggleSavedItem(item: LibraryItem, confirmed: Boolean): TvSaveResult = toggle(item, item.name, confirmed)

    suspend fun toggleWatched(preview: MetaPreview) = WatchingActions.togglePosterWatched(preview)

    private suspend fun toggle(item: LibraryItem, title: String, confirmed: Boolean): TvSaveResult {
        val confirmedProviders = if (confirmed) TrackingProviderRegistry.connectedLibraryProviders().map { it.providerId }.toSet() else emptySet()
        val result = LibraryRepository.toggleSaved(item, confirmedRemovalProviders = confirmedProviders)
        if (!result.requiresRemovalConfirmation) return TvSaveResult(null)
        val names = result.requiredRemovalConfirmations.map { c ->
            TrackingProviderRegistry.authProvider(c.providerId)?.descriptor?.displayName ?: c.providerId.displayName
        }
        val impacts = result.requiredRemovalConfirmations.flatMapTo(linkedSetOf()) { it.impacts }
        return TvSaveResult(TvTitleActionsPolicy.removalConfirmation(title, names, impacts))
    }

    private fun MetaPreview.toItem(): LibraryItem = toLibraryItem(savedAtEpochMs = kotlin.time.Clock.System.now().toEpochMilliseconds())
}
