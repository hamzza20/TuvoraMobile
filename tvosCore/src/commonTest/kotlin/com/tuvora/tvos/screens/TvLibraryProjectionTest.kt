package com.tuvora.tvos.screens

import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.library.LibrarySection
import com.nuvio.app.features.library.LibrarySortOption
import com.nuvio.app.features.library.LibrarySourceMode
import com.nuvio.app.features.library.LibraryUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TvLibraryProjectionTest {
    private fun item(id: String, type: String = "movie", name: String = id, year: String? = null, genres: List<String> = emptyList(), saved: Long = 0, category: String? = null) =
        LibraryItem(id = id, type = type, name = name, releaseInfo = year, genres = genres, savedAtEpochMs = saved, mediaCategory = category)

    private fun local(vararg items: LibraryItem) = LibraryUiState(
        sourceMode = LibrarySourceMode.LOCAL,
        items = items.toList(),
        sections = items.groupBy { it.type }.map { (type, list) -> LibrarySection(type, type, list) },
        isLoaded = true,
    )

    private val library = local(
        item("a", "movie", "Alien", "1979", listOf("Horror", "Sci-Fi"), saved = 1),
        item("b", "movie", "Brazil", "1985", listOf("Comedy"), saved = 3),
        item("c", "series", "Chernobyl", "2019", listOf("Drama"), saved = 2),
        item("d", "series", "Dark", "2017–2020", listOf("Drama", "Sci-Fi"), saved = 4, category = "anime"),
    )

    @Test
    fun `local library defaults to newest saved first`() {
        val view = TvLibraryProjection.project(library, TvLibraryFilters(), LibrarySortOption.DEFAULT)
        assertEquals(listOf("d", "b", "c", "a"), view.items.map { it.id })
        assertEquals(LibrarySortOption.ADDED_DESC, view.selectedSort)
        assertEquals(false, LibrarySortOption.DEFAULT in view.sortOptions)
    }

    @Test
    fun `types follow the TV order and the media category wins`() {
        val view = TvLibraryProjection.project(library, TvLibraryFilters(), LibrarySortOption.TITLE_ASC)
        assertEquals(listOf("movie" to 2, "series" to 1, "anime" to 1), view.types.map { it.key to it.count })
        assertEquals(4, view.allTypesCount)
    }

    @Test
    fun `genre counts ignore the genre filter but honour the year filter`() {
        val view = TvLibraryProjection.project(library, TvLibraryFilters(genre = "Drama", year = "2019"), LibrarySortOption.TITLE_ASC)
        assertEquals(listOf("c"), view.items.map { it.id })
        assertEquals(listOf("Drama" to 1), view.genres.map { it.key to it.count })
        assertEquals(listOf("2019" to 1, "2017" to 1), view.years.map { it.key to it.count })
    }

    @Test
    fun `a genre that no longer matches is dropped instead of emptying the grid`() {
        val view = TvLibraryProjection.project(library, TvLibraryFilters(type = "movie", genre = "Drama"), LibrarySortOption.TITLE_ASC)
        assertNull(view.selectedGenre)
        assertEquals(listOf("a", "b"), view.items.map { it.id })
    }

    @Test
    fun `years come from the release info newest first`() {
        val view = TvLibraryProjection.project(library, TvLibraryFilters(), LibrarySortOption.TITLE_ASC)
        assertEquals(listOf("2019", "2017", "1985", "1979"), view.years.map { it.key })
    }

    @Test
    fun `watched filter splits the grid`() {
        val watched = setOf("a", "c")
        val seen = TvLibraryProjection.project(library, TvLibraryFilters(watched = TvLibraryWatchedFilter.WATCHED), LibrarySortOption.TITLE_ASC) { it.id in watched }
        val unseen = TvLibraryProjection.project(library, TvLibraryFilters(watched = TvLibraryWatchedFilter.UNWATCHED), LibrarySortOption.TITLE_ASC) { it.id in watched }
        assertEquals(listOf("a", "c"), seen.items.map { it.id })
        assertEquals(listOf("b", "d"), unseen.items.map { it.id })
    }

    @Test
    fun `a tracking source shows its lists and filters to the selected one`() {
        val state = LibraryUiState(
            sourceMode = LibrarySourceMode.TRAKT,
            sections = listOf(
                LibrarySection("watchlist", "Watchlist", listOf(item("a"), item("b"))),
                LibrarySection("favs", "Favourites", listOf(item("c", "series"))),
            ),
            isLoaded = true,
        )
        val first = TvLibraryProjection.project(state, TvLibraryFilters(), LibrarySortOption.DEFAULT)
        assertEquals(listOf("watchlist", "favs"), first.lists.map { it.key })
        assertEquals("watchlist", first.selectedListKey)
        assertEquals(listOf("a", "b"), first.items.map { it.id })
        assertEquals(LibrarySortOption.DEFAULT, first.selectedSort)

        val favs = TvLibraryProjection.project(state, TvLibraryFilters(listKey = "favs"), LibrarySortOption.DEFAULT)
        assertEquals(listOf("c"), favs.items.map { it.id })
    }

    @Test
    fun `a failed provider order falls back to provider order`() {
        val state = LibraryUiState(sourceMode = LibrarySourceMode.TRAKT, sections = listOf(LibrarySection("w", "W", listOf(item("a")))), isLoaded = true)
        val view = TvLibraryProjection.project(state, TvLibraryFilters(), LibrarySortOption.ADDED_DESC, providerOrderFailed = true)
        assertEquals(LibrarySortOption.DEFAULT, view.selectedSort)
    }
}
