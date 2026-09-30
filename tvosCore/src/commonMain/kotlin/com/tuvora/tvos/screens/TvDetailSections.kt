package com.tuvora.tvos.screens

import com.nuvio.app.features.details.MetaPerson
import com.nuvio.app.features.details.MetaScreenSectionKey
import com.nuvio.app.features.details.MetaTrailer
import com.nuvio.app.features.home.MetaPreview

/** The tabbed people row under the hero (NuvioTV MetaDetailsScreen PeopleSectionTab), in NuvioTV's order. */
enum class TvDetailTab { CAST, RATINGS, MORE_LIKE_THIS, TRAILER, COLLECTION }

/** Full-width rows below the tabbed row, in NuvioTV's order. */
enum class TvDetailRow { COLLECTION, COMMENTS, NETWORKS, PRODUCTION }

/** What a title has to show below its hero and episodes. Counts, so the policy never needs the network. */
data class TvDetailSectionInputs(
    val isTvShow: Boolean,
    val castCount: Int,
    val showEpisodeRatings: Boolean,
    val moreLikeThisCount: Int,
    val playableTrailerCount: Int,
    val collectionCount: Int,
    val showComments: Boolean,
    val networkCount: Int,
    val productionCount: Int,
    /** Sections the profile switched off in Settings → Meta screen (synced across devices). */
    val disabled: Set<MetaScreenSectionKey> = emptySet(),
)

data class TvDetailSectionsLayout(val tabs: List<TvDetailTab>, val rows: List<TvDetailRow>) {
    /** NuvioTV draws the "A | B | C" tab strip only when there is more than one tab; one tab gets a plain title. */
    val showsTabStrip: Boolean get() = tabs.size > 1
    val isEmpty: Boolean get() = tabs.isEmpty() && rows.isEmpty()
}

/** Creators/directors/writers first (NuvioTV "leading cast"), then everyone else. */
data class TvCastSplit(val leading: List<MetaPerson>, val cast: List<MetaPerson>) {
    val isEmpty: Boolean get() = leading.isEmpty() && cast.isEmpty()
}

/**
 * NuvioTV's details-page section decisions (MetaDetailsScreen.kt: peopleTabItems, shouldSplitCollection,
 * the network/production order, castMembers split; EpisodeRatingsSection.kt ratingColor), pure so the
 * Apple TV screen and its tests share one answer.
 */
object TvDetailSectionsPolicy {
    /** Rows longer than this are cut: a tvOS row this long is never scrolled to the end, and every card is a focus target. */
    const val CAST_LIMIT = 30

    fun layout(inputs: TvDetailSectionInputs): TvDetailSectionsLayout {
        val on = { key: MetaScreenSectionKey -> key !in inputs.disabled }
        val tabs = buildList {
            if (inputs.castCount > 0 && on(MetaScreenSectionKey.CAST)) add(TvDetailTab.CAST)
            if (inputs.isTvShow && inputs.showEpisodeRatings) add(TvDetailTab.RATINGS)
            if (inputs.moreLikeThisCount > 0 && on(MetaScreenSectionKey.MORE_LIKE_THIS)) add(TvDetailTab.MORE_LIKE_THIS)
            if (inputs.playableTrailerCount > 0 && on(MetaScreenSectionKey.TRAILERS)) add(TvDetailTab.TRAILER)
            if (inputs.collectionCount > 0 && on(MetaScreenSectionKey.COLLECTION)) add(TvDetailTab.COLLECTION)
        }
        // NuvioTV: with more than three tabs the collection leaves the strip and becomes its own row.
        val splitCollection = tabs.size > 3 && TvDetailTab.COLLECTION in tabs
        val rows = buildList {
            if (splitCollection) add(TvDetailRow.COLLECTION)
            if (inputs.showComments && on(MetaScreenSectionKey.COMMENTS)) add(TvDetailRow.COMMENTS)
            if (on(MetaScreenSectionKey.PRODUCTION)) {
                val companies = listOfNotNull(
                    TvDetailRow.NETWORKS.takeIf { inputs.networkCount > 0 },
                    TvDetailRow.PRODUCTION.takeIf { inputs.productionCount > 0 },
                )
                // Series lead with the network; movies with the studio.
                addAll(if (inputs.isTvShow) companies else companies.reversed())
            }
        }
        return TvDetailSectionsLayout(tabs = if (splitCollection) tabs - TvDetailTab.COLLECTION else tabs, rows = rows)
    }

    /**
     * NuvioTV's leading credits: creators if any, else directors, else writers; they are removed from the
     * main cast so nobody appears twice. [leadRoles] are the role labels the shared TMDB mapper writes
     * (localized "Creator"/"Director"/"Writer"); English is always recognised too.
     */
    fun splitCast(people: List<MetaPerson>, leadRoles: LeadRoles = LeadRoles()): TvCastSplit {
        fun isRole(p: MetaPerson, labels: Set<String>) = p.role?.trim()?.lowercase()?.let { it in labels } == true
        val creators = people.filter { isRole(it, leadRoles.creator) }
        val directors = people.filter { isRole(it, leadRoles.director) }
        val writers = people.filter { isRole(it, leadRoles.writer) }
        val leading = when {
            creators.isNotEmpty() -> creators
            directors.isNotEmpty() -> directors
            else -> writers
        }
        val allLead = leadRoles.creator + leadRoles.director + leadRoles.writer
        val leadingKeys = leading.map(::key).toSet()
        val rest = people.filterNot { isRole(it, allLead) && key(it) in leadingKeys }
        val cappedLeading = leading.take(CAST_LIMIT)
        return TvCastSplit(cappedLeading, rest.take((CAST_LIMIT - cappedLeading.size).coerceAtLeast(0)))
    }

    data class LeadRoles(
        val creator: Set<String> = setOf("creator"),
        val director: Set<String> = setOf("director"),
        val writer: Set<String> = setOf("writer"),
    ) {
        fun with(creator: String, director: String, writer: String) = LeadRoles(
            this.creator + creator.trim().lowercase(), this.director + director.trim().lowercase(), this.writer + writer.trim().lowercase(),
        )
    }

    private fun key(p: MetaPerson) = "${p.tmdbId ?: ""}|${p.name.trim().lowercase()}|${p.role.orEmpty().trim().lowercase()}"

    /** NuvioTV lists only trailers it can hand to YouTube (ytId present). */
    fun playableTrailers(trailers: List<MetaTrailer>): List<MetaTrailer> =
        trailers.filter { it.key.isNotBlank() && it.site.equals("YouTube", ignoreCase = true) }
            .distinctBy { it.key }

    /** YouTube video id → the YouTube tvOS app's deep link, and the thumbnail NuvioTV shows. */
    fun youtubeAppUrl(key: String): String = "youtube://watch?v=$key"
    fun youtubeThumbnail(key: String): String = "https://img.youtube.com/vi/$key/hqdefault.jpg"

    /** CastDetailScreen's filmography: movie + TV credits, one card per title, newest year first. */
    fun filmography(movieCredits: List<MetaPreview>, tvCredits: List<MetaPreview>): List<MetaPreview> =
        (movieCredits + tvCredits).distinctBy { it.id }
            .sortedByDescending { it.releaseInfo?.trim()?.take(4)?.toIntOrNull() ?: 0 }

    /** EpisodeRatingsSection.ratingColor bands, as ARGB: 9+ deep green … under 6 purple. */
    fun ratingColor(value: Double): Long = when {
        value >= 9.0 -> 0xFF186A3B
        value >= 8.0 -> 0xFF28B463
        value >= 7.5 -> 0xFFF4D03F
        value >= 7.0 -> 0xFFF39C12
        value >= 6.0 -> 0xFFE74C3C
        else -> 0xFF633974
    }

    /** Dark text on the two yellow bands, white elsewhere (EpisodeRatingsSection.ratingTextColor). */
    fun ratingOnDarkText(value: Double): Boolean = value >= 7.0 && value < 8.0
}
