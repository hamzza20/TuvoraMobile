package com.tuvora.tvos.screens

import com.nuvio.app.features.details.MetaPerson
import com.nuvio.app.features.details.MetaScreenSectionKey
import com.nuvio.app.features.details.MetaTrailer
import com.nuvio.app.features.home.MetaPreview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvDetailSectionsPolicyTest {
    private fun inputs(
        isTvShow: Boolean = false,
        cast: Int = 0,
        ratings: Boolean = false,
        more: Int = 0,
        trailers: Int = 0,
        collection: Int = 0,
        comments: Boolean = false,
        networks: Int = 0,
        production: Int = 0,
        disabled: Set<MetaScreenSectionKey> = emptySet(),
    ) = TvDetailSectionInputs(isTvShow, cast, ratings, more, trailers, collection, comments, networks, production, disabled)

    @Test
    fun `a movie with cast and recommendations gets both tabs in TV order`() {
        val layout = TvDetailSectionsPolicy.layout(inputs(cast = 5, more = 8))
        assertEquals(listOf(TvDetailTab.CAST, TvDetailTab.MORE_LIKE_THIS), layout.tabs)
        assertTrue(layout.showsTabStrip)
    }

    @Test
    fun `a single tab has no strip`() {
        val layout = TvDetailSectionsPolicy.layout(inputs(cast = 5))
        assertEquals(listOf(TvDetailTab.CAST), layout.tabs)
        assertFalse(layout.showsTabStrip)
    }

    @Test
    fun `ratings are a series only tab`() {
        assertEquals(emptyList(), TvDetailSectionsPolicy.layout(inputs(ratings = true)).tabs)
        assertEquals(listOf(TvDetailTab.RATINGS), TvDetailSectionsPolicy.layout(inputs(isTvShow = true, ratings = true)).tabs)
    }

    @Test
    fun `collection stays a tab when three or fewer tabs exist`() {
        val layout = TvDetailSectionsPolicy.layout(inputs(cast = 1, more = 1, collection = 3))
        assertEquals(listOf(TvDetailTab.CAST, TvDetailTab.MORE_LIKE_THIS, TvDetailTab.COLLECTION), layout.tabs)
        assertEquals(emptyList(), layout.rows)
    }

    @Test
    fun `collection splits into its own row when more than three tabs exist`() {
        val layout = TvDetailSectionsPolicy.layout(inputs(cast = 1, more = 1, trailers = 1, collection = 3))
        assertEquals(listOf(TvDetailTab.CAST, TvDetailTab.MORE_LIKE_THIS, TvDetailTab.TRAILER), layout.tabs)
        assertEquals(TvDetailRow.COLLECTION, layout.rows.first())
    }

    @Test
    fun `movies list production before network and series the reverse`() {
        val movie = TvDetailSectionsPolicy.layout(inputs(networks = 1, production = 2, comments = true))
        assertEquals(listOf(TvDetailRow.COMMENTS, TvDetailRow.PRODUCTION, TvDetailRow.NETWORKS), movie.rows)
        val series = TvDetailSectionsPolicy.layout(inputs(isTvShow = true, networks = 1, production = 2))
        assertEquals(listOf(TvDetailRow.NETWORKS, TvDetailRow.PRODUCTION), series.rows)
    }

    @Test
    fun `sections switched off in settings are hidden`() {
        val layout = TvDetailSectionsPolicy.layout(
            inputs(cast = 3, more = 3, production = 1, comments = true,
                disabled = setOf(MetaScreenSectionKey.CAST, MetaScreenSectionKey.PRODUCTION, MetaScreenSectionKey.COMMENTS)),
        )
        assertEquals(listOf(TvDetailTab.MORE_LIKE_THIS), layout.tabs)
        assertEquals(emptyList(), layout.rows)
    }

    @Test
    fun `an empty title has nothing below the hero`() {
        assertTrue(TvDetailSectionsPolicy.layout(inputs()).isEmpty)
    }

    @Test
    fun `directors lead a movie and are not repeated in the cast`() {
        val people = listOf(
            MetaPerson("Ridley Scott", "Director", tmdbId = 1),
            MetaPerson("Dan O'Bannon", "Writer", tmdbId = 2),
            MetaPerson("Sigourney Weaver", "Ripley", tmdbId = 3),
        )
        val split = TvDetailSectionsPolicy.splitCast(people)
        assertEquals(listOf("Ridley Scott"), split.leading.map { it.name })
        assertEquals(listOf("Dan O'Bannon", "Sigourney Weaver"), split.cast.map { it.name })
    }

    @Test
    fun `creators win over directors`() {
        val people = listOf(MetaPerson("A", "Director"), MetaPerson("B", "Creator"), MetaPerson("C", "Hero"))
        assertEquals(listOf("B"), TvDetailSectionsPolicy.splitCast(people).leading.map { it.name })
    }

    @Test
    fun `localized role labels are recognised`() {
        val roles = TvDetailSectionsPolicy.LeadRoles().with(creator = "Créateur", director = "Réalisateur", writer = "Scénariste")
        val split = TvDetailSectionsPolicy.splitCast(listOf(MetaPerson("X", "Réalisateur"), MetaPerson("Y", "Rôle")), roles)
        assertEquals(listOf("X"), split.leading.map { it.name })
        assertEquals(listOf("Y"), split.cast.map { it.name })
    }

    @Test
    fun `the cast row is capped`() {
        val people = (1..80).map { MetaPerson("P$it", "Role $it", tmdbId = it) } + MetaPerson("D", "Director")
        val split = TvDetailSectionsPolicy.splitCast(people)
        assertEquals(TvDetailSectionsPolicy.CAST_LIMIT, split.leading.size + split.cast.size)
        assertEquals("D", split.leading.single().name)
    }

    @Test
    fun `only YouTube trailers are playable and duplicates collapse`() {
        val trailers = listOf(
            MetaTrailer(id = "1", key = "abc", name = "Trailer", site = "YouTube"),
            MetaTrailer(id = "2", key = "abc", name = "Trailer again", site = "YouTube"),
            MetaTrailer(id = "3", key = "v1", name = "Vimeo", site = "Vimeo"),
            MetaTrailer(id = "4", key = "", name = "Blank", site = "YouTube"),
        )
        assertEquals(listOf("abc"), TvDetailSectionsPolicy.playableTrailers(trailers).map { it.key })
        assertEquals("youtube://watch?v=abc", TvDetailSectionsPolicy.youtubeAppUrl("abc"))
    }

    @Test
    fun `rating bands match the TV chips`() {
        assertEquals(0xFF186A3B, TvDetailSectionsPolicy.ratingColor(9.1))
        assertEquals(0xFF28B463, TvDetailSectionsPolicy.ratingColor(8.0))
        assertEquals(0xFFF4D03F, TvDetailSectionsPolicy.ratingColor(7.6))
        assertEquals(0xFFF39C12, TvDetailSectionsPolicy.ratingColor(7.0))
        assertEquals(0xFFE74C3C, TvDetailSectionsPolicy.ratingColor(6.0))
        assertEquals(0xFF633974, TvDetailSectionsPolicy.ratingColor(3.2))
        assertTrue(TvDetailSectionsPolicy.ratingOnDarkText(7.5))
        assertFalse(TvDetailSectionsPolicy.ratingOnDarkText(8.0))
    }

    @Test
    fun `filmography merges credits newest first without duplicates`() {
        val movies = listOf(MetaPreview("a", "movie", "Alien", releaseInfo = "1979"), MetaPreview("b", "movie", "Blade Runner", releaseInfo = "1982"))
        val tv = listOf(MetaPreview("c", "series", "Raised by Wolves", releaseInfo = "2020"), MetaPreview("a", "movie", "Alien", releaseInfo = "1979"))
        assertEquals(listOf("c", "b", "a"), TvDetailSectionsPolicy.filmography(movies, tv).map { it.id })
    }
}
