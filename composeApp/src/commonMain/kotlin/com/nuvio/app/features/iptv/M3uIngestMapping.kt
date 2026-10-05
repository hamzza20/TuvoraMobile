package com.nuvio.app.features.iptv

import com.nuvio.app.features.iptv.content.IptvContentKind
import com.nuvio.app.features.iptv.content.IptvEpisodeRow
import com.nuvio.app.features.iptv.content.IptvSeriesRow
import com.nuvio.app.features.iptv.content.IptvStreamRow
import com.nuvio.app.features.iptv.identity.M3uIdentity

/** What one parsed M3U entry becomes in the content DB (pure — see [M3uIngestMapping]). */
internal sealed interface M3uIngestRow {
    val kind: IptvContentKind
    val categoryId: String

    data class Channel(val row: IptvStreamRow, override val categoryId: String) : M3uIngestRow {
        override val kind get() = IptvContentKind.LIVE
    }
    data class Movie(val row: IptvStreamRow, override val categoryId: String) : M3uIngestRow {
        override val kind get() = IptvContentKind.VOD
    }
    data class Episode(val series: IptvSeriesRow, val row: IptvEpisodeRow, override val categoryId: String) : M3uIngestRow {
        override val kind get() = IptvContentKind.SERIES
    }
}

/**
 * The pure decision "which row, under which id, does this M3U entry become" — every id a synced
 * content id is built from (channel / movie sid, series sid, episode id, category id) is decided here,
 * so it is unit-tested on both runners without a database.
 */
internal object M3uIngestMapping {

    /**
     * [login] is the playlist's login ([M3uIdentity.loginOf] its URL); [episodeOrdinal] is the
     * fallback episode number for an episode whose name carries none.
     */
    fun map(entry: M3UParser.Entry, login: M3uIdentity.Login?, episodeOrdinal: Int): M3uIngestRow {
        val catId = M3UClient.categoryId(entry.group)
        return when (entry.kind) {
            M3UKind.LIVE -> M3uIngestRow.Channel(
                IptvStreamRow(M3UClient.sidOf(entry.url), entry.name, entry.logo, entry.tvgId, catId, entry.url, entry.ext),
                catId,
            )
            M3UKind.MOVIE -> M3uIngestRow.Movie(
                IptvStreamRow(M3UClient.sidOf(entry.url), entry.name, entry.logo, null, catId, entry.url, entry.ext),
                catId,
            )
            M3UKind.SERIES -> {
                val key = entry.seriesKey ?: entry.name
                val seriesSid = M3UClient.sidOf("series:$key")
                M3uIngestRow.Episode(
                    series = IptvSeriesRow(seriesSid, M3UClient.seriesTitle(key), entry.logo, catId),
                    row = IptvEpisodeRow(
                        seriesSid = seriesSid,
                        episodeId = M3UClient.episodeIdOf(entry.url),
                        name = entry.name,
                        season = entry.season ?: 1,
                        episode = entry.episode ?: (episodeOrdinal % 10_000),
                        logo = entry.logo,
                        url = entry.url,
                        ext = entry.ext,
                    ),
                    categoryId = catId,
                )
            }
        }
    }
}
