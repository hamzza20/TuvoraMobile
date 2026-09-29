package com.tuvora.tvos.screens

import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.library.LibrarySortOption
import com.nuvio.app.features.library.LibrarySourceMode
import com.nuvio.app.features.library.LibraryUiState
import com.nuvio.app.features.library.availableLibrarySortOptions
import com.nuvio.app.features.library.buildLibraryVerticalProjection
import com.nuvio.app.features.library.effectiveLibrarySortOption

/** NuvioTV's Watched picker (LibraryViewModel.LibraryWatchedFilter). */
enum class TvLibraryWatchedFilter { ALL, WATCHED, UNWATCHED }

/** What the viewer picked on the Library screen. Null means "All" / the first list. */
data class TvLibraryFilters(
    val listKey: String? = null,
    val type: String? = null,
    val genre: String? = null,
    val year: String? = null,
    val watched: TvLibraryWatchedFilter = TvLibraryWatchedFilter.ALL,
)

/** One dropdown option; [count] is the faceted count NuvioTV shows beside the label ("Drama (4)"). */
data class TvLibraryOption(val key: String, val label: String, val count: Int)

/** Everything the Library screen draws: the pickers, their selections and the grid. */
data class TvLibraryView(
    val sourceMode: LibrarySourceMode,
    val isLoaded: Boolean,
    val isLoading: Boolean,
    val errorMessage: String?,
    val lists: List<TvLibraryOption>,
    val selectedListKey: String?,
    /** Content types present in the selected list, NuvioTV order (movie, series, tv, show, anime, then A–Z). */
    val types: List<TvLibraryOption>,
    /** Items matching every filter but the type, i.e. the count beside "All". */
    val allTypesCount: Int,
    val selectedType: String?,
    val sortOptions: List<LibrarySortOption>,
    val selectedSort: LibrarySortOption,
    val genres: List<TvLibraryOption>,
    val selectedGenre: String?,
    val years: List<TvLibraryOption>,
    val selectedYear: String?,
    val watched: TvLibraryWatchedFilter,
    val items: List<LibraryItem>,
)

/**
 * NuvioTV's library grid (LibraryViewModel.withVisibleItems), over the phone's shared library: the
 * list and sort come from the shared vertical projection (dedupe, provider order, Trakt rank), then
 * NuvioTV's type → genre → year → watched filters with faceted counts — each filter counts the items
 * that match all the OTHER active filters. Pure, so it is tested without repositories.
 */
object TvLibraryProjection {
    private val yearRegex = Regex("""\b(19|20)\d{2}\b""")
    private val typeOrder = listOf("movie", "series", "tv", "show", "anime")

    fun project(
        state: LibraryUiState,
        filters: TvLibraryFilters,
        sort: LibrarySortOption,
        providerOrders: Map<String, Map<String, Int>> = emptyMap(),
        providerOrderFailed: Boolean = false,
        isWatched: (LibraryItem) -> Boolean = { false },
    ): TvLibraryView {
        val effectiveSort = effectiveLibrarySortOption(sort, state.sourceMode)
        val visibleSort = if (providerOrderFailed) LibrarySortOption.DEFAULT else effectiveSort
        val listed = buildLibraryVerticalProjection(
            sections = state.sections,
            sourceMode = state.sourceMode,
            selectedSectionKey = filters.listKey,
            selectedType = null,
            sortOption = visibleSort,
            providerOrders = providerOrders,
        )
        val listItems = listed.entries.map { it.item }

        val genreFilter = filters.genre
        val yearFilter = filters.year
        fun matchesGenre(item: LibraryItem) = genreFilter == null || item.genres.any { it.equals(genreFilter, ignoreCase = true) }
        fun matchesYear(item: LibraryItem) = yearFilter == null || yearOf(item) == yearFilter

        // Types: from the list, counted after genre + year.
        val typeKeys = listItems.map(::typeKeyOf).distinct().sortedWith(compareBy<String>({ typeRank(it) }, { it }))
        val forTypeCounts = listItems.filter { matchesGenre(it) && matchesYear(it) }
        val types = typeKeys.map { key -> TvLibraryOption(key, key, forTypeCounts.count { typeKeyOf(it) == key }) }
        val selectedType = filters.type?.lowercase()?.takeIf { it in typeKeys }
        val typed = listItems.filter { selectedType == null || typeKeyOf(it) == selectedType }

        // Genres counted after type + year; years after type + genre.
        val genreCounts = linkedMapOf<String, Int>()
        typed.filter(::matchesYear).forEach { item ->
            item.genres.map { it.trim() }.filter { it.isNotBlank() }.distinct().forEach { genreCounts[it] = (genreCounts[it] ?: 0) + 1 }
        }
        val genres = genreCounts.entries.sortedBy { it.key.lowercase() }.map { TvLibraryOption(it.key, it.key, it.value) }
        val yearCounts = linkedMapOf<String, Int>()
        typed.filter(::matchesGenre).forEach { item -> yearOf(item)?.let { yearCounts[it] = (yearCounts[it] ?: 0) + 1 } }
        val years = yearCounts.entries.sortedByDescending { it.key }.map { TvLibraryOption(it.key, it.key, it.value) }

        val validGenre = genreFilter?.let { g -> genres.firstOrNull { it.key.equals(g, ignoreCase = true) }?.key }
        val validYear = yearFilter?.takeIf { y -> years.any { it.key == y } }
        val filtered = typed.filter { item ->
            (validGenre == null || item.genres.any { it.equals(validGenre, ignoreCase = true) }) &&
                (validYear == null || yearOf(item) == validYear) &&
                when (filters.watched) {
                    TvLibraryWatchedFilter.ALL -> true
                    TvLibraryWatchedFilter.WATCHED -> isWatched(item)
                    TvLibraryWatchedFilter.UNWATCHED -> !isWatched(item)
                }
        }

        return TvLibraryView(
            sourceMode = state.sourceMode,
            isLoaded = state.isLoaded,
            isLoading = state.isLoading,
            errorMessage = state.errorMessage,
            lists = listed.availableSections.map { TvLibraryOption(it.type, it.displayTitle, it.items.size) },
            selectedListKey = listed.selectedSectionKey,
            types = types,
            allTypesCount = forTypeCounts.size,
            selectedType = selectedType,
            sortOptions = availableLibrarySortOptions(state.sourceMode),
            selectedSort = visibleSort,
            genres = genres,
            selectedGenre = validGenre,
            years = years,
            selectedYear = validYear,
            watched = filters.watched,
            items = filtered,
        )
    }

    /** NuvioTV's type key: the provider's media category (e.g. anime) wins over movie/series. */
    fun typeKeyOf(item: LibraryItem): String = (item.mediaCategory ?: item.type).trim().ifBlank { "unknown" }.lowercase()

    fun yearOf(item: LibraryItem): String? = item.releaseInfo?.let { yearRegex.find(it)?.value }

    private fun typeRank(key: String): Int = typeOrder.indexOf(key).let { if (it >= 0) it else typeOrder.size }
}
