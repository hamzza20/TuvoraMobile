package com.tuvora.tvos.screens

import com.nuvio.app.core.contracts.StreamSourceAccess
import com.nuvio.app.features.debrid.DirectDebridPlayableResult
import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.MetaDetailsUiState
import com.nuvio.app.features.details.MetaExternalRating
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.details.seriesPrimaryAction
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.library.toLibraryItem
import com.nuvio.app.features.mdblist.MdbListSettingsRepository
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watching.application.WatchingActions
import com.nuvio.app.features.player.PlayerLaunch
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.servers.PinnedMediaPolicy
import com.nuvio.app.features.servers.PinnedMediaRelay
import com.nuvio.app.features.servers.RelaySession
import com.nuvio.app.features.servers.ServerPlayback
import com.nuvio.app.features.servers.serverPlaybackMessage
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamsRepository
import com.nuvio.app.features.streams.StreamsUiState
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.tuvora.tvos.player.TvPlaybackHeaders
import com.tuvora.tvos.player.TvPlayerSession
import kotlinx.coroutines.flow.StateFlow

/** What opening a source led to: a player session, or a sentence to show the viewer. */
sealed class TvOpenResult {
    data class Play(val session: TvPlayerSession) : TvOpenResult()
    data class Message(val text: String) : TvOpenResult()
}

/**
 * A title's details, its sources, and opening one. Apple TV's port of the phone's
 * StreamDestination.openSelectedStream (upstream, Compose-bound, excluded from :tvosCore):
 * deferred Stalker links are minted at play time and debrid links resolved, exactly as there.
 * Torrent (P2P) and open-in-another-app sources are named gaps on Apple TV.
 */
object TvTitle {
    val details: StateFlow<MetaDetailsUiState> get() = MetaDetailsRepository.uiState
    val streams: StateFlow<StreamsUiState> get() = StreamsRepository.uiState

    fun load(type: String, id: String) = MetaDetailsRepository.load(type, id)

    /** MDBList ratings are on (toggle plus an API key or a connected account): the hero shows them. */
    fun mdbListActive(): Boolean = MdbListSettingsRepository.snapshot().isActive

    /** Simulator smoke hook (`-smokeMdbRatings`): sample MDBList scores, since the simulator has no API key. */
    fun smokeWithMdbRatings(meta: MetaDetails): MetaDetails = meta.copy(
        externalRatings = listOf(
            MetaExternalRating("imdb", 7.8), MetaExternalRating("tmdb", 78.0), MetaExternalRating("trakt", 80.0),
            MetaExternalRating("letterboxd", 3.9), MetaExternalRating("tomatoes", 91.0, isCertified = true),
            MetaExternalRating("audience", 55.0), MetaExternalRating("metacritic", 74.0),
        ),
    )

    /** [season]/[episode] of -1 mean "a movie" (Kotlin nullables don't cross cleanly into Swift). */
    fun loadStreams(type: String, videoId: String, parentMetaId: String, season: Int, episode: Int) {
        StreamsRepository.load(
            type = type,
            videoId = videoId,
            parentMetaId = parentMetaId,
            season = season.takeIf { it >= 0 },
            episode = episode.takeIf { it >= 0 },
            // Not a manual pick: the repository then nominates autoPlayStream per the profile's
            // Auto Stream Selection setting, as on the phone (Manual mode nominates nothing).
            manualSelection = false,
        )
    }

    fun cancelStreams() = StreamsRepository.cancelLoading()

    /** The auto-play pick has been acted on (StreamsRepository.consumeAutoPlay). */
    fun consumeAutoPlay() = StreamsRepository.consumeAutoPlay()

    /**
     * The episode the series Play button targets — the phone's own rule (seriesPrimaryAction: the
     * in-progress episode, else the next unwatched one). Null for movies or when nothing is decided.
     */
    fun primaryEpisode(meta: MetaDetails): MetaVideo? {
        val action = meta.seriesPrimaryAction(
            entries = WatchProgressRepository.uiState.value.entries,
            watchedItems = WatchedRepository.uiState.value.items,
            todayIsoDate = com.nuvio.app.features.watchprogress.CurrentDateProvider.todayIsoDate(),
        ) ?: return null
        if (action.seasonNumber != null && action.episodeNumber != null) {
            meta.videos.firstOrNull { it.season == action.seasonNumber && it.episode == action.episodeNumber }?.let { return it }
        }
        return meta.videos.firstOrNull { it.id == action.videoId }
    }

    // Library / Watched — the phone's own actions (LibraryRepository.toggleSaved, WatchingActions).
    val libraryChanges get() = LibraryRepository.uiState
    val watchedChanges get() = WatchedRepository.uiState

    fun isSaved(meta: MetaDetails): Boolean = LibraryRepository.isSaved(meta.id, meta.type)

    suspend fun toggleSaved(meta: MetaDetails) {
        LibraryRepository.toggleSaved(meta.toLibraryItem(savedAtEpochMs = kotlin.time.Clock.System.now().toEpochMilliseconds()))
    }

    fun isWatched(meta: MetaDetails): Boolean =
        WatchedRepository.isWatched(id = meta.id, type = meta.type) || WatchedRepository.isFullyWatchedSeries(id = meta.id, type = meta.type)

    suspend fun toggleWatched(meta: MetaDetails) {
        WatchingActions.togglePosterWatched(MetaPreview(id = meta.id, type = meta.type, name = meta.name, poster = meta.poster))
    }

    /** Where to resume [videoId], in ms; 0 when unwatched or finished. */
    fun resumePositionMs(videoId: String, parentMetaId: String, season: Int, episode: Int): Long {
        val entry = WatchProgressRepository.progressForVideo(
            videoId = videoId,
            parentMetaId = parentMetaId,
            seasonNumber = season.takeIf { it >= 0 },
            episodeNumber = episode.takeIf { it >= 0 },
        ) ?: return 0L
        return if (entry.isCompleted) 0L else entry.lastPositionMs.coerceAtLeast(0L)
    }

    suspend fun open(stream: StreamItem, meta: MetaDetails, video: MetaVideo?, resumeMs: Long): TvOpenResult {
        var playable = stream
        // A Stalker source is listed without a play link: mint it now, for this edition only.
        val access = StreamSourceAccess.current()
        if (access.isDeferredUrl(playable.playableDirectUrl)) {
            val minted = access.resolveDeferredUrl(playable.playableDirectUrl.orEmpty(), forceMint = false)
                ?: return TvOpenResult.Message("This source isn't available right now.")
            playable = playable.copy(url = minted)
        }
        // A media-server stream is prepared on the server first (direct play / transcode decision, play session), then played
        // from its session URL; progress goes back to the server through ServerPlayback.
        var serverPlaybackUrl: String? = null
        var resume = resumeMs
        if (playable.serverTarget != null) {
            playable = try {
                ServerPlayback.prepare(playable)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                return TvOpenResult.Message(error.serverPlaybackMessage())
            }
            serverPlaybackUrl = playable.playableDirectUrl
            val saved = WatchProgressRepository.progressForVideo(
                videoId = video?.id ?: meta.id,
                parentMetaId = meta.id,
                seasonNumber = video?.season,
                episodeNumber = video?.episode,
            )
            resume = ServerPlayback.newerResumePositionMs(serverPlaybackUrl, saved?.lastUpdatedEpochMs) ?: resumeMs
        }
        if (DirectDebridPlaybackResolver.shouldResolveToPlayableStream(playable)) {
            when (val resolved = DirectDebridPlaybackResolver.resolveToPlayableStream(playable, video?.season, video?.episode)) {
                is DirectDebridPlayableResult.Success -> playable = resolved.stream
                DirectDebridPlayableResult.MissingApiKey -> return TvOpenResult.Message("Add your debrid API key in Settings to play this source.")
                DirectDebridPlayableResult.NotCached -> return TvOpenResult.Message("This source isn't cached by your debrid service yet.")
                DirectDebridPlayableResult.Stale, DirectDebridPlayableResult.Error -> return TvOpenResult.Message("This source couldn't be opened. Try another one.")
            }
        }
        if (playable.needsLocalDebridResolve) return TvOpenResult.Message("Torrent sources aren't supported on Apple TV yet.")
        if (playable.shouldOpenExternally) return TvOpenResult.Message("This source opens in another app, which Apple TV doesn't support.")
        val url = playable.playableDirectUrl ?: return TvOpenResult.Message("This source has no playable link.")
        // A server this device pinned a (self-signed) certificate for is read through the verified loopback relay.
        var relay: RelaySession? = null
        var playUrl = url
        var subtitles = playable.externalSubtitles
        val pinned = PinnedMediaPolicy.route(url) as? PinnedMediaPolicy.Route.Pinned
        if (pinned != null) {
            val opened = try {
                PinnedMediaRelay.shared.open(pinned.authority)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                ServerPlayback.stop(serverPlaybackUrl)
                return TvOpenResult.Message("Could not open a secure connection to the server.")
            }
            relay = opened
            playUrl = opened.rewrite(url) ?: url
            subtitles = subtitles.map { it.copy(url = opened.rewrite(it.url) ?: it.url) }
        }

        val videoId = video?.id ?: meta.id
        val launch = PlayerLaunch(
            profileId = ProfileRepository.activeProfileId,
            title = meta.name,
            sourceUrl = playUrl,
            sourceHeaders = TvPlaybackHeaders.sanitize(playable.behaviorHints.proxyHeaders?.request),
            sourceResponseHeaders = TvPlaybackHeaders.sanitize(playable.behaviorHints.proxyHeaders?.response),
            externalSubtitles = subtitles,
            streamType = playable.streamType,
            logo = meta.logo,
            poster = meta.poster,
            background = meta.background,
            seasonNumber = video?.season,
            episodeNumber = video?.episode,
            episodeTitle = video?.title,
            episodeThumbnail = video?.thumbnail,
            streamTitle = playable.streamLabel,
            streamSubtitle = playable.streamSubtitle,
            bingeGroup = playable.behaviorHints.bingeGroup,
            providerName = playable.addonName,
            providerAddonId = playable.addonId,
            contentType = meta.type,
            videoId = videoId,
            parentMetaId = meta.id,
            parentMetaType = meta.type,
            initialPositionMs = resume,
        )
        StreamsRepository.cancelLoading()
        val retryResolver: (suspend () -> com.tuvora.tvos.player.TvResolvedSource?)? = when {
            access.isDeferredUrl(stream.playableDirectUrl) -> suspend {
                access.resolveDeferredUrl(stream.playableDirectUrl.orEmpty(), forceMint = true)?.let {
                    com.tuvora.tvos.player.TvResolvedSource(it, launch.sourceHeaders)
                }
            }
            access.isStalkerSource(videoId) -> suspend {
                if (com.nuvio.app.core.contracts.MetaSourceAccess.current().ensureStreamRegistered(videoId, true, true)) {
                    access.directStreamItem(videoId)?.let { fresh -> fresh.playableDirectUrl?.let {
                        com.tuvora.tvos.player.TvResolvedSource(it, TvPlaybackHeaders.sanitize(fresh.behaviorHints.proxyHeaders?.request))
                    } }
                } else null
            }
            else -> null
        }
        return TvOpenResult.Play(
            TvPlayerSession(launch, vodReresolve = retryResolver)
                .bindServer(serverPlaybackUrl) { relay?.dispose() },
        )
    }
}
