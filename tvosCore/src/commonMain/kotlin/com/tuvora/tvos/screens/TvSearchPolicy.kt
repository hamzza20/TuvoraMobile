package com.tuvora.tvos.screens

import com.nuvio.app.features.search.SearchEmptyStateReason
import com.nuvio.app.features.search.SearchUiState

/** What the Search screen shows under the field (NuvioTV SearchScreen.kt's `when`). */
enum class TvSearchMode {
    /** Fewer than 2 characters: recent searches, or "Start Searching". */
    START,
    /** Waiting on the debounce or the first answers with nothing on screen yet: skeleton rows. */
    LOADING,
    /** No enabled add-on can search (and no IPTV playlist carries it). */
    NO_CATALOGS,
    /** Every catalog failed. */
    ERROR,
    NO_RESULTS,
    RESULTS,
}

/**
 * NuvioTV's search decisions (SearchUiState.kt, SearchScreen.kt) over the phone's shared
 * SearchRepository. Pure, so it is tested without the network.
 */
object TvSearchPolicy {
    const val MIN_QUERY_LENGTH = 2
    /** The phone's keystroke debounce before a search goes out. */
    const val DEBOUNCE_MS = 350L

    /** NuvioTV `submittedSearchQuery`: the trimmed query once it has 2+ characters, else "". */
    fun submittedQuery(raw: String): String = raw.trim().takeIf { it.length >= MIN_QUERY_LENGTH }.orEmpty()

    /**
     * [requested] is the query the repository was last asked for; while the viewer is still typing
     * past it the query is pending, and existing results stay up instead of flickering to skeletons.
     */
    fun mode(query: String, requested: String?, state: SearchUiState): TvSearchMode {
        val submitted = submittedQuery(query)
        if (submitted.isEmpty()) return TvSearchMode.START
        val pending = submitted != requested
        val hasRows = state.sections.any { it.items.isNotEmpty() }
        return when {
            hasRows -> TvSearchMode.RESULTS
            pending || state.isLoading -> TvSearchMode.LOADING
            state.emptyStateReason == SearchEmptyStateReason.NoActiveAddons ||
                state.emptyStateReason == SearchEmptyStateReason.NoSearchCatalogs -> TvSearchMode.NO_CATALOGS
            state.emptyStateReason == SearchEmptyStateReason.RequestFailed || state.errorMessage != null -> TvSearchMode.ERROR
            else -> TvSearchMode.NO_RESULTS
        }
    }

    /** More catalogs are still answering under results already shown: NuvioTV adds one skeleton row. */
    fun showsLoadingMore(query: String, requested: String?, state: SearchUiState): Boolean =
        mode(query, requested, state) == TvSearchMode.RESULTS && (state.isLoading || submittedQuery(query) != requested)

    /** The phone records a search once the answer for exactly that query has finished with results. */
    fun shouldRecord(query: String, requested: String?, state: SearchUiState): Boolean {
        val submitted = submittedQuery(query)
        return submitted.isNotEmpty() && submitted == requested && !state.isLoading && state.sections.any { it.items.isNotEmpty() }
    }
}
