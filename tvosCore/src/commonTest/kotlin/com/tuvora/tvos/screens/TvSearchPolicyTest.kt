package com.tuvora.tvos.screens

import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.search.SearchEmptyStateReason
import com.nuvio.app.features.search.SearchUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvSearchPolicyTest {
    private val rows = listOf(
        HomeCatalogSection("k", "Movies", "", "Cinemeta", CatalogTarget.Library("movie", "x"), listOf(MetaPreview("tt1", "movie", "Alien"))),
    )

    @Test
    fun `fewer than two characters is the start state`() {
        assertEquals("", TvSearchPolicy.submittedQuery(" a "))
        assertEquals("al", TvSearchPolicy.submittedQuery(" al "))
        assertEquals(TvSearchMode.START, TvSearchPolicy.mode("a", null, SearchUiState()))
    }

    @Test
    fun `a pending query with nothing on screen shows skeletons`() {
        assertEquals(TvSearchMode.LOADING, TvSearchPolicy.mode("alien", "alie", SearchUiState()))
        assertEquals(TvSearchMode.LOADING, TvSearchPolicy.mode("alien", "alien", SearchUiState(isLoading = true)))
    }

    @Test
    fun `results stay up while the next keystroke is pending`() {
        val state = SearchUiState(sections = rows)
        assertEquals(TvSearchMode.RESULTS, TvSearchPolicy.mode("aliens", "alien", state))
        assertTrue(TvSearchPolicy.showsLoadingMore("aliens", "alien", state))
        assertFalse(TvSearchPolicy.showsLoadingMore("alien", "alien", state))
    }

    @Test
    fun `empty answers map to the TV states`() {
        assertEquals(TvSearchMode.NO_RESULTS, TvSearchPolicy.mode("zz", "zz", SearchUiState(emptyStateReason = SearchEmptyStateReason.NoResults)))
        assertEquals(TvSearchMode.NO_CATALOGS, TvSearchPolicy.mode("zz", "zz", SearchUiState(emptyStateReason = SearchEmptyStateReason.NoActiveAddons)))
        assertEquals(TvSearchMode.ERROR, TvSearchPolicy.mode("zz", "zz", SearchUiState(emptyStateReason = SearchEmptyStateReason.RequestFailed, errorMessage = "x")))
    }

    @Test
    fun `history records only a finished search with results`() {
        assertTrue(TvSearchPolicy.shouldRecord("alien", "alien", SearchUiState(sections = rows)))
        assertFalse(TvSearchPolicy.shouldRecord("alien", "alien", SearchUiState(isLoading = true, sections = rows)))
        assertFalse(TvSearchPolicy.shouldRecord("alien", "alie", SearchUiState(sections = rows)))
        assertFalse(TvSearchPolicy.shouldRecord("alien", "alien", SearchUiState(emptyStateReason = SearchEmptyStateReason.NoResults)))
    }
}
