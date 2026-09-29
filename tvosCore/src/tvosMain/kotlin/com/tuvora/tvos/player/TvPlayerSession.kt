package com.tuvora.tvos.player

import co.touchlab.kermit.Logger
import com.nuvio.app.features.player.LivePlaybackRejoinPolicy
import com.nuvio.app.features.player.MpvStartPosition
import com.nuvio.app.features.player.NuvioPlayerBridge
import com.nuvio.app.features.player.NuvioPlayerBridgeCreator
import com.nuvio.app.features.player.PlayerLaunch
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.externalPlaybackSession
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import platform.UIKit.UIViewController
import kotlin.time.TimeSource

/** The two Apple TV engines, registered by the Swift app at launch (libmpv bridge, AVPlayer bridge). */
object TvPlayerEngines {
    private val creators = mutableMapOf<PlaybackLane, NuvioPlayerBridgeCreator>()

    fun register(lane: PlaybackLane, creator: NuvioPlayerBridgeCreator) {
        creators[lane] = creator
    }

    internal fun create(lane: PlaybackLane): NuvioPlayerBridge? = creators[lane]?.createBridge()
}

/** The lane that played each title last, so a title AVPlayer failed on opens straight on libmpv next time. */
internal object TvLaneMemory {
    private val lanes = mutableMapOf<String, PlaybackLane>()
    fun get(key: String?): PlaybackLane? = key?.let(lanes::get)
    fun put(key: String?, lane: PlaybackLane) { if (key != null) lanes[key] = lane }
}

data class TvPlayerState(
    val lane: PlaybackLane,
    /** Bumps whenever the engine (and so its view controller) changes; Swift re-embeds on change. */
    val engineGeneration: Int,
    val isLive: Boolean,
    val isLoading: Boolean = true,
    val isPlaying: Boolean = false,
    val isEnded: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedMs: Long = 0L,
    /** Non-null when playback failed and nothing is left to try. */
    val errorMessage: String? = null,
    /** Series only: the next episode, and whether its card is due (PlayerNextEpisodeRules thresholds). */
    val nextEpisode: com.nuvio.app.features.details.MetaVideo? = null,
    val showNextEpisode: Boolean = false,
)

/**
 * One Apple TV playback: Apple TV's counterpart of the phone's PlayerScreenRuntime (upstream,
 * Compose-bound, excluded from :tvosCore). It reuses the phone's decisions — [PlaybackLanePolicy],
 * the live freeze/recovery policies through [TvLiveMonitor], the completed-save gate, and the same
 * save cadence (every 60 s while playing, 1 s after a seek, a flush on close).
 *
 * Swift owns the lifecycle: [attach] when the player screen appears, [detach] when it hides, [close]
 * when the viewer leaves. State is polled only while attached, so nothing runs off screen.
 */
class TvPlayerSession(
    private val launch: PlayerLaunch,
    /**
     * Live only: fetches a fresh address for a reconnect. Stalker links are single-use (create_link),
     * so replaying the old URL fails; Xtream/M3U just return the same address. Null = replay.
     */
    private val liveReresolve: (suspend () -> TvResolvedSource?)? = null,
) {
    private val log = Logger.withTag("TvPlayerSession")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val isLive = LivePlaybackRejoinPolicy.rejoinsLiveEdge(launch.streamType, launch.liveReplay != null)
    private val liveChannel = launch.streamType == "live"
    private val progressKey = launch.videoId ?: launch.parentMetaId
    private val playbackSession = launch.externalPlaybackSession()
    private val gate = TvProgressGate()
    private val monitor = TvLiveMonitor()
    private val clock = TimeSource.Monotonic.markNow()

    private var bridge: NuvioPlayerBridge? = null
    private var escalated = false
    private var languagesApplied = false
    private var wantsToPlay = true
    private var pollJob: Job? = null
    private var seekSaveJob: Job? = null
    private var lastSaveAtMs = 0L
    private var snapshot = PlayerPlaybackSnapshot()

    private val _state = MutableStateFlow(
        TvPlayerState(
            lane = PlaybackLanePolicy.initial(
                LaneInput(
                    url = launch.sourceUrl,
                    isLive = liveChannel,
                    setting = EngineSetting.Auto,
                    remembered = TvLaneMemory.get(progressKey),
                    hasExternalSubtitles = launch.externalSubtitles.isNotEmpty(),
                    mimeType = launch.sourceResponseHeaders.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value,
                ),
            ),
            engineGeneration = 0,
            isLive = isLive,
        ),
    )
    val state: StateFlow<TvPlayerState> = _state.asStateFlow()

    val title: String get() = launch.title
    val subtitle: String? get() = launch.episodeTitle ?: launch.streamTitle.takeIf { it != launch.title }

    /** The current engine's view controller. Changes when the session escalates; see [TvPlayerState.engineGeneration]. */
    fun viewController(): UIViewController? = (bridge ?: open(_state.value.lane, launch.initialPositionMs))?.createPlayerViewController()

    /** The series this episode belongs to (loaded on attach), for the Episodes panel and Next Episode. */
    var seriesMeta: com.nuvio.app.features.details.MetaDetails? = null
        private set

    val currentSeason: Int? get() = launch.seasonNumber
    val currentEpisode: Int? get() = launch.episodeNumber
    val currentVideoId: String get() = launch.videoId ?: launch.parentMetaId

    private fun loadSeries() {
        if (launch.seasonNumber == null || launch.episodeNumber == null || seriesMeta != null) return
        scope.launch {
            val meta = runCatching {
                com.nuvio.app.features.details.MetaDetailsRepository.fetch(launch.parentMetaType, launch.parentMetaId)
            }.getOrNull() ?: return@launch
            seriesMeta = meta
            val next = com.nuvio.app.features.player.skip.PlayerNextEpisodeRules.resolveNextEpisode(
                videos = meta.videos, currentSeason = launch.seasonNumber, currentEpisode = launch.episodeNumber,
            )
            _state.value = _state.value.copy(nextEpisode = next)
        }
    }

    fun attach() {
        loadSeries()
        if (bridge == null) open(_state.value.lane, launch.initialPositionMs)
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                poll()
                delay(POLL_MS)
            }
        }
    }

    fun detach() {
        pollJob?.cancel()
        pollJob = null
        save(flush = true)
    }

    fun close() {
        detach()
        seekSaveJob?.cancel()
        bridge?.clearNowPlayingInfo()
        bridge?.destroy()
        bridge = null
        scope.cancel()
    }

    fun togglePlayPause() = if (_state.value.isPlaying) pause() else play()
    fun play() { wantsToPlay = true; bridge?.play() }
    fun pause() { wantsToPlay = false; bridge?.pause() }

    fun seekBy(offsetMs: Long) {
        if (isLive) return
        bridge?.seekBy(offsetMs)
        scheduleSeekSave()
    }

    fun seekTo(positionMs: Long) {
        if (isLive) return
        bridge?.seekTo(positionMs.coerceAtLeast(0L))
        scheduleSeekSave()
    }

    /** Audio tracks of the current engine (ids are the engine's own; pass them back to [selectAudio]). */
    fun audioTracks(): List<TvTrack> {
        val b = bridge ?: return emptyList()
        return (0 until b.getAudioTrackCount()).map { i ->
            TvTrack(b.getAudioTrackId(i).toIntOrNull() ?: i, b.getAudioTrackLabel(i), b.getAudioTrackLang(i), b.isAudioTrackSelected(i))
        }
    }

    fun subtitleTracks(): List<TvTrack> {
        val b = bridge ?: return emptyList()
        return (0 until b.getSubtitleTrackCount()).map { i ->
            TvTrack(b.getSubtitleTrackId(i).toIntOrNull() ?: i, b.getSubtitleTrackLabel(i), b.getSubtitleTrackLang(i), b.isSubtitleTrackSelected(i))
        }
    }

    fun selectAudio(trackId: Int) { bridge?.selectAudioTrack(trackId) }

    /** -1 turns subtitles off. */
    fun selectSubtitle(trackId: Int) { bridge?.selectSubtitleTrack(trackId) }

    /** 0 = fit, 1 = fill, 2 = zoom (NuvioPlayerBridge.setResizeMode). */
    fun setResizeMode(mode: Int) { bridge?.setResizeMode(mode) }

    fun setSpeed(speed: Float) { bridge?.setPlaybackSpeed(speed) }

    fun retry() {
        _state.value = _state.value.copy(errorMessage = null, isLoading = true)
        bridge?.retry()
    }

    private fun open(lane: PlaybackLane, startMs: Long): NuvioPlayerBridge? {
        val created = TvPlayerEngines.create(lane) ?: TvPlayerEngines.create(PlaybackLane.Libmpv) ?: run {
            log.e { "no player engine registered for $lane" }
            _state.value = _state.value.copy(errorMessage = "No player available")
            return null
        }
        bridge = created
        PlayerSettingsRepository.ensureLoaded()
        val settings = PlayerSettingsRepository.uiState.value
        created.configureAudioOutput(audioOutput = settings.iosAudioOutputMode.mpvValue)
        created.configureVideoOutput(
            hardwareDecoder = settings.iosHardwareDecoderMode.mpvValue,
            targetColorspaceHint = settings.iosTargetColorspaceHintEnabled,
            toneMapping = settings.iosToneMappingMode.mpvValue,
            hdrComputePeak = settings.iosHdrComputePeakEnabled,
            targetPrimaries = settings.iosTargetPrimaries.mpvValue,
            targetTransfer = settings.iosTargetTransfer.mpvValue,
            extendedDynamicRange = settings.iosExtendedDynamicRangeEnabled,
            deband = settings.iosDebandEnabled,
            interpolation = settings.iosInterpolationEnabled,
            brightness = settings.iosBrightness,
            contrast = settings.iosContrast,
            saturation = settings.iosSaturation,
            gamma = settings.iosGamma,
        )
        val subStyle = TvSubtitleStyle.forMpv(settings.subtitleStyle)
        created.applySubtitleStyle(
            textColor = subStyle.textColor, backgroundColor = subStyle.backgroundColor, outlineColor = subStyle.outlineColor,
            outlineSize = subStyle.outlineSize, bold = subStyle.bold, fontSize = subStyle.fontSize,
            subPos = subStyle.subPos, stripSdh = subStyle.stripSdh,
        )
        created.setIsLiveStream(isLive)
        val headers = TvPlaybackHeaders.sanitize(launch.sourceHeaders)
        created.loadFileWithAudio(
            videoUrl = launch.sourceUrl,
            audioUrl = launch.sourceAudioUrl,
            headersJson = headers.takeIf { it.isNotEmpty() }?.let { Json.encodeToString(it) },
            subtitlesJson = launch.externalSubtitles.takeIf { it.isNotEmpty() }?.let { Json.encodeToString(it) },
            // The resume rides the load (mpv `start=`), as on the phone (B59b).
            startOption = MpvStartPosition.loadOption(startMs, isLive),
        )
        created.updateNowPlayingMetadata(title = launch.title, subtitle = subtitle, artworkUrl = launch.poster ?: launch.background)
        if (!wantsToPlay) created.pause()
        return created
    }

    private fun poll() {
        val b = bridge ?: return
        val error = b.getErrorMessage().takeIf { it.isNotBlank() }
        snapshot = PlayerPlaybackSnapshot(
            isLoading = b.getIsLoading(),
            isPlaying = b.getIsPlaying(),
            isEnded = b.getIsEnded(),
            durationMs = b.getDurationMs(),
            positionMs = b.getPositionMs(),
            bufferedPositionMs = b.getBufferedMs(),
            playbackSpeed = b.getPlaybackSpeed(),
            videoProgressTicks = b.getVideoFrameTicks(),
            hasVideoTrack = true,
        )
        val lane = _state.value.lane
        if (error != null && handleFailure(lane, error)) return
        if (snapshot.isPlaying && snapshot.positionMs > 0L) TvLaneMemory.put(progressKey, lane)
        if (!languagesApplied && b.getAudioTrackCount() > 1) {
            languagesApplied = true
            val settings = PlayerSettingsRepository.uiState.value
            listOfNotNull(settings.preferredAudioLanguage, settings.secondaryPreferredAudioLanguage)
                .filter { it.isNotBlank() && it != com.nuvio.app.features.player.AudioLanguageOption.DEVICE }
                .takeIf { it.isNotEmpty() }
                ?.let(b::applyAudioLanguagePreferences)
        }
        val settings = PlayerSettingsRepository.uiState.value
        val showNext = _state.value.nextEpisode != null && snapshot.durationMs > 0 &&
            com.nuvio.app.features.player.skip.PlayerNextEpisodeRules.shouldShowNextEpisodeCard(
                positionMs = snapshot.positionMs,
                durationMs = snapshot.durationMs,
                skipIntervals = emptyList(),
                thresholdMode = settings.nextEpisodeThresholdMode,
                thresholdPercent = settings.nextEpisodeThresholdPercent,
                thresholdMinutesBeforeEnd = settings.nextEpisodeThresholdMinutesBeforeEnd,
            )
        _state.value = _state.value.copy(
            showNextEpisode = showNext,
            isLoading = snapshot.isLoading,
            isPlaying = snapshot.isPlaying,
            isEnded = snapshot.isEnded,
            positionMs = snapshot.positionMs,
            durationMs = snapshot.durationMs,
            bufferedMs = snapshot.bufferedPositionMs,
        )
        if (isLive) {
            when (monitor.sample(clock.elapsedNow().inWholeMilliseconds, snapshot, wantsToPlay)) {
                TvLiveAction.None -> Unit
                TvLiveAction.Reconnect -> { log.i { "live freeze: reconnecting" }; reconnectLive() }
                TvLiveAction.GiveUp -> _state.value = _state.value.copy(errorMessage = "The channel stopped responding")
            }
        } else {
            val now = clock.elapsedNow().inWholeMilliseconds
            if (snapshot.isPlaying && now - lastSaveAtMs >= SAVE_INTERVAL_MS) {
                lastSaveAtMs = now
                save(flush = false)
            }
        }
    }

    /** Handles a reported error; true means this poll is done (state already updated).*/
    private fun handleFailure(lane: PlaybackLane, error: String): Boolean {
        val next = PlaybackLanePolicy.escalation(lane, escalated)
        if (next == null) {
            if (isLive) {
                // A live error is a stream that ended: same bounded reconnect ladder as a freeze.
                val ended = snapshot.copy(isEnded = true, isPlaying = false, isLoading = false)
                when (monitor.sample(clock.elapsedNow().inWholeMilliseconds, ended, wantsToPlay = true)) {
                    TvLiveAction.Reconnect -> reconnectLive()
                    TvLiveAction.GiveUp -> _state.value = _state.value.copy(errorMessage = error, isLoading = false, isPlaying = false)
                    TvLiveAction.None -> _state.value = _state.value.copy(isLoading = true)
                }
                return true
            }
            _state.value = _state.value.copy(errorMessage = error, isLoading = false, isPlaying = false)
            return true
        }
        log.w { "$lane failed ($error); escalating to $next" }
        escalated = true
        val resumeAt = snapshot.positionMs.takeIf { it > 0L } ?: launch.initialPositionMs
        // One connection at a time: release the failed engine before the next one opens the stream.
        bridge?.destroy()
        bridge = null
        _state.value = _state.value.copy(lane = next, engineGeneration = _state.value.engineGeneration + 1, isLoading = true)
        open(next, resumeAt)
        TvLaneMemory.put(progressKey, next)
        return true
    }

    private fun reconnectLive() {
        val reresolve = liveReresolve ?: run { bridge?.retry(); return }
        scope.launch {
            val fresh = runCatching { reresolve() }.getOrNull()
            if (fresh == null) { bridge?.retry(); return@launch }
            val headers = TvPlaybackHeaders.sanitize(fresh.headers)
            bridge?.loadFileWithAudio(
                videoUrl = fresh.url,
                audioUrl = null,
                headersJson = headers.takeIf { it.isNotEmpty() }?.let { Json.encodeToString(it) },
                subtitlesJson = null,
                startOption = null,
            )
        }
    }

    private fun scheduleSeekSave() {
        seekSaveJob?.cancel()
        seekSaveJob = scope.launch {
            delay(SEEK_SAVE_DELAY_MS)
            poll()
            save(flush = false, syncRemote = true)
        }
    }

    private fun save(flush: Boolean, syncRemote: Boolean = flush) {
        if (liveChannel) return
        if (snapshot.durationMs <= 0L && !snapshot.isEnded) return
        if (!gate.admit(playbackSession.videoId, snapshot)) return
        runCatching {
            if (flush) {
                WatchProgressRepository.flushPlaybackProgress(playbackSession, snapshot, syncRemote)
            } else {
                WatchProgressRepository.upsertPlaybackProgress(playbackSession, snapshot, syncRemote)
            }
        }.onFailure { log.w(it) { "progress save failed" } }
    }

    private companion object {
        const val POLL_MS = 500L
        const val SAVE_INTERVAL_MS = 60_000L
        const val SEEK_SAVE_DELAY_MS = 1_000L
    }
}

/** One audio or subtitle track as the player overlay lists it. */
data class TvTrack(val id: Int, val label: String, val language: String, val selected: Boolean)

/** A playable address: URL plus the request headers the provider needs. */
data class TvResolvedSource(val url: String, val headers: Map<String, String>)

/** Swift-friendly launch builders (Kotlin default arguments don't cross into Swift). */
object TvPlayerLaunches {
    /** A stream with no catalog identity — the simulator smoke test and direct URLs. */
    fun direct(url: String, title: String, isLive: Boolean, startPositionMs: Long): PlayerLaunch = PlayerLaunch(
        profileId = com.nuvio.app.features.profiles.ProfileRepository.activeProfileId,
        title = title,
        sourceUrl = url,
        streamType = if (isLive) "live" else null,
        streamTitle = title,
        providerName = "Direct",
        parentMetaId = "direct:$url",
        parentMetaType = if (isLive) "tv" else "movie",
        initialPositionMs = startPositionMs,
    )
}
