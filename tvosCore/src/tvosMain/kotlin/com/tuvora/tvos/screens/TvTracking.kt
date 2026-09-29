package com.tuvora.tvos.screens

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.library.LibrarySourceMode
import com.nuvio.app.features.mdblist.MdbListTracker
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.simkl.SimklApi
import com.nuvio.app.features.simkl.SimklApiRequest
import com.nuvio.app.features.simkl.SimklAnimeIdPreference
import com.nuvio.app.features.simkl.SimklAuthRepository
import com.nuvio.app.features.simkl.SimklAuthStorage
import com.nuvio.app.features.simkl.SimklHttpMethod
import com.nuvio.app.features.simkl.SimklRefreshOrigin
import com.nuvio.app.features.simkl.SimklRetryPolicy
import com.nuvio.app.features.simkl.SimklSyncRepository
import com.nuvio.app.features.tracking.TrackingRefreshIntent
import com.nuvio.app.features.tracking.TrackingSettingsRepository
import com.nuvio.app.features.tracking.WatchProgressSource
import com.nuvio.app.features.trakt.MoreLikeThisSourcePreference
import com.nuvio.app.features.trakt.TraktAuthRepository
import com.nuvio.app.features.trakt.TraktAuthState
import com.nuvio.app.features.trakt.TraktAuthStorage
import com.nuvio.app.features.trakt.TraktCommentsSettings
import com.nuvio.app.features.trakt.TraktConfig
import com.nuvio.app.features.trakt.TraktPlatformClock
import com.nuvio.app.features.watchprogress.WatchProgressSourceCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A device-code sign-in in progress, as NuvioTV's TrackingDeviceAuthContent shows it. */
data class TvDeviceAuth(
    val isLoading: Boolean = false,
    val userCode: String? = null,
    val displayUrl: String? = null,
    val qrUrl: String? = null,
    /** 0 when there is no code yet. */
    val expiresAtEpochMs: Long = 0L,
    val isPolling: Boolean = false,
    val credentialsConfigured: Boolean = true,
    val error: String? = null,
)

/**
 * Settings → Tracking for Apple TV. Trakt connects with its device-code flow and Simkl with its PIN
 * flow (NuvioTV's TV flows — the phone apps' OAuth redirect needs a browser Apple TV doesn't have);
 * the tokens land in the shared auth stores, so everything downstream (sync, scrobbling, library)
 * is the shared code's. MDBList uses the shared device flow as-is. Polls run only while the viewer
 * has a connect dialog open: Settings calls [cancel] when it closes.
 */
object TvTracking {
    private val log = Logger.withTag("TvTracking")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private const val TRAKT_API = "https://api.trakt.tv"

    val trakt get() = TraktAuthRepository.uiState
    val simkl get() = SimklAuthRepository.uiState
    val mdbList get() = MdbListTracker.auth.state
    val mdbListStatus get() = MdbListTracker.account.status
    val settings get() = TrackingSettingsRepository.uiState
    val commentsEnabled: StateFlow<Boolean> get() = TraktCommentsSettings.enabled

    private val _traktDevice = MutableStateFlow(TvDeviceAuth())
    val traktDevice: StateFlow<TvDeviceAuth> = _traktDevice.asStateFlow()
    private val _simklDevice = MutableStateFlow(TvDeviceAuth())
    val simklDevice: StateFlow<TvDeviceAuth> = _simklDevice.asStateFlow()

    private var flowJob: Job? = null

    fun ensureLoaded() {
        TraktAuthRepository.ensureLoaded()
        SimklAuthRepository.ensureLoaded()
        MdbListTracker.ensureLoaded()
        TrackingSettingsRepository.ensureLoaded()
    }

    fun traktConfigured(): Boolean = TraktAuthRepository.hasRequiredCredentials()
    fun simklConfigured(): Boolean = SimklAuthRepository.hasRequiredCredentials()
    fun mdbListConfigured(): Boolean = MdbListTracker.auth.hasRequiredCredentials()

    // --- Trakt device code -------------------------------------------------------------------

    fun startTrakt() {
        cancel()
        if (!traktConfigured()) {
            _traktDevice.value = TvDeviceAuth(credentialsConfigured = false)
            return
        }
        _traktDevice.value = TvDeviceAuth(isLoading = true)
        flowJob = scope.launch {
            try {
                val code = post("$TRAKT_API/oauth/device/code", json.encodeToString(TraktDeviceCodeRequest(TraktConfig.CLIENT_ID)))
                if (code.status !in 200..299) {
                    _traktDevice.value = TvDeviceAuth(error = "Failed to start Trakt auth (${code.status})")
                    return@launch
                }
                val device = json.decodeFromString<TraktDeviceCodeResponse>(code.body)
                val expiresAt = TraktPlatformClock.nowEpochMs() + device.expiresIn * 1_000L
                _traktDevice.value = TvDeviceAuth(
                    userCode = device.userCode,
                    displayUrl = device.verificationUrl,
                    qrUrl = TvTrackingPolicy.traktQrUrl(device.userCode, device.verificationUrl),
                    expiresAtEpochMs = expiresAt,
                    isPolling = true,
                )
                var interval = device.interval.coerceAtLeast(1)
                while (true) {
                    delay(interval * 1_000L)
                    if (TraktPlatformClock.nowEpochMs() >= expiresAt) {
                        endTrakt(TvDevicePoll.Expired); return@launch
                    }
                    val body = json.encodeToString(TraktDeviceTokenRequest(device.deviceCode, TraktConfig.CLIENT_ID, TraktConfig.CLIENT_SECRET))
                    val response = post("$TRAKT_API/oauth/device/token", body)
                    val poll = TvTrackingPolicy.traktPoll(response.status, interval)
                    when {
                        poll is TvDevicePoll.Approved -> {
                            installTrakt(json.decodeFromString(response.body))
                            _traktDevice.value = TvDeviceAuth()
                            return@launch
                        }
                        poll is TvDevicePoll.SlowDown -> interval = poll.intervalSeconds
                        poll.keepsPolling -> Unit
                        else -> { endTrakt(poll); return@launch }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.w { "Trakt device flow failed: ${error.message}" }
                _traktDevice.value = TvDeviceAuth(error = "Network error, please try again")
            }
        }
    }

    private fun endTrakt(poll: TvDevicePoll) {
        _traktDevice.value = TvDeviceAuth(error = TvTrackingPolicy.traktMessage(poll))
    }

    private suspend fun installTrakt(token: TraktTokenResponse) {
        val profileId = ProfileRepository.activeProfileId
        val state = TraktAuthState(
            accessToken = token.accessToken,
            refreshToken = token.refreshToken,
            tokenType = token.tokenType,
            createdAt = token.createdAt,
            expiresIn = token.expiresIn,
        )
        TraktAuthStorage.savePayload(profileId, json.encodeToString(state))
        TraktAuthRepository.onProfileChanged(profileId)
        runCatching { TraktAuthRepository.refreshUserSettings(profileId) }
    }

    fun disconnectTrakt() = TraktAuthRepository.onDisconnectRequested()

    // --- Simkl PIN -----------------------------------------------------------------------------

    fun startSimkl() {
        cancel()
        if (!simklConfigured()) {
            _simklDevice.value = TvDeviceAuth(credentialsConfigured = false)
            return
        }
        _simklDevice.value = TvDeviceAuth(isLoading = true)
        flowJob = scope.launch {
            try {
                val start = simklGet("/oauth/pin")
                val code = start.userCode?.trim()
                val url = (start.verificationUri ?: start.verificationUrl)?.trim()
                val expiresIn = start.expiresIn?.takeIf { it > 0 }
                if (start.result != "OK" || !TvTrackingPolicy.isValidSimklPin(code) || url.isNullOrBlank() || expiresIn == null) {
                    _simklDevice.value = TvDeviceAuth(error = "Unable to reach Simkl. Try again.")
                    return@launch
                }
                val expiresAt = TraktPlatformClock.nowEpochMs() + expiresIn * 1_000L
                _simklDevice.value = TvDeviceAuth(userCode = code, displayUrl = url, qrUrl = url,
                    expiresAtEpochMs = expiresAt, isPolling = true)
                val interval = (start.interval ?: TvTrackingPolicy.DEFAULT_POLL_INTERVAL_SECONDS).coerceAtLeast(1)
                while (true) {
                    delay(interval * 1_000L)
                    if (TraktPlatformClock.nowEpochMs() >= expiresAt) {
                        endSimkl(TvDevicePoll.Expired); return@launch
                    }
                    val payload = simklGet("/oauth/pin/$code")
                    val poll = TvTrackingPolicy.simklPoll(payload.result, payload.accessToken, payload.deviceCode)
                    when {
                        poll is TvDevicePoll.Approved -> {
                            installSimkl(payload.accessToken!!.trim())
                            _simklDevice.value = TvDeviceAuth()
                            return@launch
                        }
                        poll.keepsPolling -> Unit
                        else -> { endSimkl(poll); return@launch }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.w { "Simkl PIN flow failed: ${error.message}" }
                _simklDevice.value = TvDeviceAuth(error = "Unable to reach Simkl. Try again.")
            }
        }
    }

    private fun endSimkl(poll: TvDevicePoll) {
        _simklDevice.value = TvDeviceAuth(error = TvTrackingPolicy.simklMessage(poll))
    }

    private suspend fun installSimkl(token: String) {
        SimklAuthStorage.saveAccessToken(token)
        SimklAuthRepository.onProfileChanged()
        runCatching { SimklAuthRepository.refreshUserSettings() }
        SimklSyncRepository.refreshAsync(TrackingRefreshIntent.INVALIDATED, SimklRefreshOrigin.AUTHORIZATION)
    }

    fun disconnectSimkl() = SimklAuthRepository.onDisconnectRequested()

    // --- MDBList (shared device flow) ----------------------------------------------------------

    fun startMdbList() {
        cancel()
        if (!mdbListConfigured()) return
        scope.launch { runCatching { MdbListTracker.account.connect() } }
    }

    fun disconnectMdbList() {
        scope.launch { runCatching { MdbListTracker.account.disconnect() } }
    }

    /** Stops whichever sign-in is polling; the viewer closed its dialog. */
    fun cancel() {
        flowJob?.cancel()
        flowJob = null
        _traktDevice.value = TvDeviceAuth()
        _simklDevice.value = TvDeviceAuth()
        if (MdbListTracker.auth.state.value.session != null) runCatching { MdbListTracker.account.cancel() }
    }

    // --- Sources and options -------------------------------------------------------------------

    fun setLibrarySource(mode: LibrarySourceMode) = TrackingSettingsRepository.setLibrarySourceMode(mode)

    fun setWatchProgressSource(source: WatchProgressSource) {
        scope.launch {
            runCatching { WatchProgressSourceCoordinator.selectSource(ProfileRepository.activeProfileId, source) }
        }
    }

    fun setMoreLikeThis(source: MoreLikeThisSourcePreference) = TrackingSettingsRepository.setMoreLikeThisSource(source)
    fun setContinueWatchingWindow(days: Int) = TrackingSettingsRepository.setContinueWatchingDaysCap(days)
    fun setComments(enabled: Boolean) = TraktCommentsSettings.setEnabled(enabled)
    fun setAnimeId(preference: SimklAnimeIdPreference) = TrackingSettingsRepository.setSimklAnimeIdPreference(preference)

    // --- HTTP -----------------------------------------------------------------------------------

    private suspend fun post(url: String, body: String) = httpRequestRaw(
        method = "POST",
        url = url,
        headers = mapOf(
            "Content-Type" to "application/json",
            "trakt-api-version" to "2",
            "trakt-api-key" to TraktConfig.CLIENT_ID,
        ),
        body = body,
    )

    private suspend fun simklGet(path: String): SimklPinResponse {
        val response = SimklApi.client.execute(
            SimklApiRequest(
                method = SimklHttpMethod.GET,
                path = path,
                requiresAuthentication = false,
                retryPolicy = SimklRetryPolicy.NEVER,
            ),
        )
        return json.decodeFromString(response.body)
    }
}

@Serializable
private data class TraktDeviceCodeRequest(@SerialName("client_id") val clientId: String)

@Serializable
private data class TraktDeviceCodeResponse(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_url") val verificationUrl: String,
    @SerialName("expires_in") val expiresIn: Int,
    val interval: Int = TvTrackingPolicy.DEFAULT_POLL_INTERVAL_SECONDS,
)

@Serializable
private data class TraktDeviceTokenRequest(
    val code: String,
    @SerialName("client_id") val clientId: String,
    @SerialName("client_secret") val clientSecret: String,
)

@Serializable
private data class TraktTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("token_type") val tokenType: String,
    @SerialName("expires_in") val expiresIn: Int,
    @SerialName("created_at") val createdAt: Long,
)

@Serializable
private data class SimklPinResponse(
    val result: String? = null,
    @SerialName("device_code") val deviceCode: String? = null,
    @SerialName("user_code") val userCode: String? = null,
    @SerialName("verification_uri") val verificationUri: String? = null,
    @SerialName("verification_url") val verificationUrl: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    val interval: Int? = null,
    @SerialName("access_token") val accessToken: String? = null,
)
