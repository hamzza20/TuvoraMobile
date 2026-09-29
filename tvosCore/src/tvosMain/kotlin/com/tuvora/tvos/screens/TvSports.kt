package com.tuvora.tvos.screens

import com.nuvio.app.features.iptv.XtreamRepository
import com.nuvio.app.features.radar.RadarChannelMatcher
import com.nuvio.app.features.radar.RadarFixture
import com.nuvio.app.features.radar.RadarLeague
import com.nuvio.app.features.radar.RadarRepository
import com.nuvio.app.features.radar.RadarTime
import com.nuvio.app.features.radar.RadarUiState
import com.nuvio.app.features.radar.radarWhenLabel
import com.tuvora.tvos.player.TvPlayerSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Apple TV's Sports hub over the shared RadarRepository (the phone's and NuvioTV's Sports Centre):
 * followed leagues and teams, live & upcoming fixtures, and the channels that show a match.
 * Layout decisions live in [TvSportsHubPolicy]; this object only reaches the internals Swift can't.
 */
object TvSports {
    val state: StateFlow<RadarUiState> get() = RadarRepository.uiState

    fun ensureLoaded() = RadarRepository.ensureLoaded()

    fun nowMs(): Long = RadarTime.nowMs()

    fun hub(state: RadarUiState, nowMs: Long): TvSportsHub = TvSportsHubPolicy.build(state, nowMs)

    fun isLive(state: RadarUiState, fixture: RadarFixture, nowMs: Long): Boolean = state.isLive(fixture, nowMs)

    fun status(state: RadarUiState, fixture: RadarFixture, nowMs: Long): TvMatchStatus {
        val live = state.isLive(fixture, nowMs)
        val liveScore = fixture.id?.let { state.liveScores[it] }
        return TvSportsHubPolicy.status(fixture, live, liveScore, fixture.startEpochMs?.let(RadarTime::dayLabel))
    }

    fun homeScore(state: RadarUiState, fixture: RadarFixture): String? =
        TvSportsHubPolicy.score(fixture.id?.let { state.liveScores[it] }?.homeScore, fixture.homeScore)

    fun awayScore(state: RadarUiState, fixture: RadarFixture): String? =
        TvSportsHubPolicy.score(fixture.id?.let { state.liveScores[it] }?.awayScore, fixture.awayScore)

    /** "Today 2:00 PM" (device-local), or "Time TBC" without a kick-off; null while live. */
    fun whenLabel(state: RadarUiState, fixture: RadarFixture, nowMs: Long): String? {
        if (state.isLive(fixture, nowMs)) return null
        return fixture.startEpochMs?.let(::radarWhenLabel) ?: "Time TBC"
    }

    /** Kick-off today or tomorrow: the when-label is drawn in the accent colour. */
    fun isSoon(state: RadarUiState, fixture: RadarFixture, nowMs: Long): Boolean {
        if (state.isLive(fixture, nowMs)) return false
        val day = fixture.startEpochMs?.let(RadarTime::dayLabel) ?: return false
        return day == "Today" || day == "Tomorrow"
    }

    fun isFollowed(state: RadarUiState, league: RadarLeague): Boolean = league.id in state.followedLeagueIds

    fun toggleFollow(league: RadarLeague) = RadarRepository.toggleFollow(league)

    /** Browsing a league must not require following it: fetch its fixtures on demand. */
    fun ensureLeagueLoaded(league: RadarLeague) = RadarRepository.ensureLeagueLoaded(league.id)

    fun leaguePage(state: RadarUiState, league: RadarLeague, nowMs: Long): TvLeaguePage =
        TvSportsHubPolicy.leaguePage(state, league, nowMs)

    /**
     * Refreshes fixtures while the Sports screen is on screen, per [TvSportsRefreshCadence]. The
     * caller runs this in a SwiftUI `.task` tied to the screen being visible and the scene active, so
     * cancellation (screen gone, app backgrounded) ends the loop — never a free-running poll.
     */
    suspend fun refreshWhileVisible() {
        var sinceFullMs = 0L
        while (true) {
            delay(TvSportsRefreshCadence.LIVE_INTERVAL_MS)
            val tick = TvSportsRefreshCadence.next(sinceFullMs)
            sinceFullMs = tick.sinceFullMs
            if (tick.full) RadarRepository.refreshFixtures(force = true) else RadarRepository.refreshLiveFixtures()
        }
    }

    fun hasPlaylists(): Boolean {
        XtreamRepository.ensureLoaded()
        return XtreamRepository.uiState.value.accounts.any { it.enabled }
    }

    /**
     * The viewer's channels showing [fixture], best evidence first (the match sheet's lookup:
     * broadcaster listings bounded to 4 s, then name + EPG matching). Empty when nothing matches.
     */
    suspend fun matchChannels(fixture: RadarFixture): List<RadarChannelMatcher.ChannelMatch> {
        XtreamRepository.ensureLoaded()
        val stations = try {
            withTimeoutOrNull(4_000) { RadarRepository.tvStations(fixture.id) } ?: emptyList()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            emptyList()
        }
        val league = fixture.leagueId?.let { RadarRepository.uiState.value.leagueById(it) }
        return RadarChannelMatcher.match(fixture, league, stations)
    }

    fun groupMatches(fixture: RadarFixture, matches: List<RadarChannelMatcher.ChannelMatch>): List<TvMatchGroup> =
        TvSportsHubPolicy.groupMatches(matches, fixture.league)

    /** The match sheet's subtitle: round or league · kick-off · venue. */
    fun sheetSubtitle(fixture: RadarFixture): String = listOfNotNull(
        fixture.roundLabel ?: fixture.league,
        fixture.startEpochMs?.let(::radarWhenLabel),
        fixture.venue?.takeIf { it.isNotBlank() },
    ).joinToString(" · ")

    /** A channel row's second line: the matching programme and its times, the listing, or the playlist. */
    fun matchDetail(match: RadarChannelMatcher.ChannelMatch): String {
        val programme = match.programme
        return when {
            programme != null -> listOfNotNull(
                match.language,
                "${programme.title} · ${RadarTime.formatTime(programme.startMs)} – ${RadarTime.formatTime(programme.endMs)}",
            ).joinToString(" · ")
            match.via == RadarChannelMatcher.MatchVia.LISTING -> listOfNotNull(match.language, "TV listing", match.channel.playlistName).joinToString(" · ")
            else -> match.channel.playlistName
        }
    }

    /** Opens a matched channel in the player, or null when it has no playable address right now. */
    suspend fun playMatch(match: RadarChannelMatcher.ChannelMatch): TvPlayerSession? {
        RadarChannelMatcher.ensurePlayable(match)
        return TvIptvBrowse.playChannel(match.channel.contentId, match.channel.name, match.channel.logo)
    }
}
