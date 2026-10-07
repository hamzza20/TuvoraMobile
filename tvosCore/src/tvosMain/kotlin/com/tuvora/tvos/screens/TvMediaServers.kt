package com.tuvora.tvos.screens

import com.nuvio.app.features.mediaserver.api.MediaServerEntry
import com.nuvio.app.features.mediaserver.api.MediaServerHomeRow
import com.nuvio.app.features.mediaserver.api.MediaServerType
import com.nuvio.app.features.mediaserver.internal.MediaServerAccounts
import com.nuvio.app.features.mediaserver.internal.MediaServerRuntime
import com.nuvio.app.features.mediaserver.internal.client.HealthStatus
import com.nuvio.app.features.mediaserver.internal.client.MediaServerException
import com.nuvio.app.features.mediaserver.internal.client.MediaServerTrust
import com.nuvio.app.features.mediaserver.internal.flow.AddError
import com.nuvio.app.features.mediaserver.internal.flow.AddServerController
import com.nuvio.app.features.mediaserver.internal.flow.AddStage
import com.nuvio.app.features.mediaserver.internal.flow.MediaServerListController
import com.nuvio.app.features.mediaserver.internal.flow.MediaServerManagement
import com.nuvio.app.features.mediaserver.internal.flow.MediaServerStatusPolicy
import com.nuvio.app.features.mediaserver.internal.flow.ServerStatus
import com.nuvio.app.features.mediaserver.internal.policy.QuickConnectPolicy
import com.nuvio.app.features.mediaserver.internal.source.MediaServerLibraries
import com.nuvio.app.features.mediaserver.internal.ui.MediaServerHomeSurfaces
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Settings -> Integrations -> Media servers for Apple TV (design 5.9: shared Kotlin, SwiftUI screens). The
 * flows are the SAME controllers the phone's Compose pages drive ([AddServerController],
 * [MediaServerListController], [MediaServerManagement]); this file only reshapes their internal state
 * into public value types Swift can read. No I/O decisions live in the views.
 */
enum class TvServerStatus { SIGNED_IN, SIGN_IN_AGAIN, NEEDS_SIGN_IN, OFFLINE, DISABLED }

data class TvServerRow(
    val key: String,
    val name: String,
    /** "Jellyfin - kid - nas.local:8096". */
    val subtitle: String,
    val status: TvServerStatus,
    val checking: Boolean,
)

private fun ServerStatus.toTv() = when (this) {
    ServerStatus.SIGNED_IN -> TvServerStatus.SIGNED_IN
    ServerStatus.SIGN_IN_AGAIN -> TvServerStatus.SIGN_IN_AGAIN
    ServerStatus.NEEDS_SIGN_IN -> TvServerStatus.NEEDS_SIGN_IN
    ServerStatus.OFFLINE -> TvServerStatus.OFFLINE
    ServerStatus.DISABLED -> TvServerStatus.DISABLED
}

private fun subtitleOf(entry: MediaServerEntry): String {
    val host = entry.address?.substringAfter("://")?.substringBefore('/')?.takeIf { it.isNotBlank() }
    return listOfNotNull(entry.type.productName, entry.userName?.takeIf { it.isNotBlank() }, host).joinToString(" · ")
}

object TvMediaServers {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val runtime get() = MediaServerRuntime.production
    private val listController by lazy { MediaServerListController(runtime.services, scope) }

    /** The saved servers with their status badge; re-emitted when entries, tokens, health checks or expiries change. */
    val rows: StateFlow<List<TvServerRow>> by lazy {
        val r = runtime
        r.entryStore.ensureLoaded()
        combine(
            r.entryStore.entries, r.services.expiredSessions, r.services.credentialVersion,
            listController.healthState, listController.checkingState,
        ) { entries, expired, _, health, checking ->
            listController.rows(entries, expired, health, checking).map {
                TvServerRow(it.entry.key, it.entry.name, subtitleOf(it.entry), it.status.toTv(), it.checking)
            }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())
    }

    fun ensureLoaded() = runtime.entryStore.ensureLoaded()

    /**
     * One reachability + token check per visit (never a timer): the Settings page calls this when it
     * becomes visible. CLAUDE.md recurring-network rule: delta, lifecycle-bound, decision in the controller.
     */
    fun checkOnce() {
        ensureLoaded()
        listController.checkOnce(runtime.entryStore.current())
    }

    /** Whether a Home server row exists for any server (drives nothing visual; here for tests / smoke). */
    fun serverCount(): Int = runtime.entryStore.current().size

    /** Ask Home to re-pull the contributed rows once (TTL-gated by the contributor; no timer). */
    fun refreshHomeRows() = com.nuvio.app.features.home.HomeRepository.refreshContributed()

    /** Smoke/UI-test hook: the sign-in key of the first server (for -smoke deep links). */
    fun firstKey(): String? { ensureLoaded(); return runtime.entryStore.current().firstOrNull()?.key }
}

// ---------------------------------------------------------------- add / sign in

enum class TvAddStage { ADDRESS, CHOOSE_SIGN_IN, QUICK_CONNECT, PASSWORD, OFFER_HOME_ROW, DONE }

enum class TvAddError {
    INVALID_ADDRESS, NOT_A_MEDIA_SERVER, UNREACHABLE, WRONG_CREDENTIALS, QUICK_CONNECT_FAILED,
    SIGN_IN_FAILED, DIFFERENT_SERVER, NOT_SAVED, UNUSABLE_SERVER,
}

data class TvAddState(
    val stage: TvAddStage,
    val address: String,
    /** "jellyfin" / "emby" / null = detect. */
    val selectedType: String?,
    val busy: Boolean,
    val error: TvAddError?,
    val certAuthority: String?,
    /** Colon-grouped hex when the controller gave plain hex. */
    val certFingerprint: String?,
    val foundName: String?,
    /** "Jellyfin 10.9.0". */
    val foundProduct: String?,
    val serverName: String,
    val typeCorrection: String?,
    val quickConnectAvailable: Boolean,
    val publicUsers: List<String>,
    val quickConnectCode: String?,
    val quickConnectStartedAtMs: Long,
    val signedInName: String?,
    val signingInExisting: Boolean,
)

/**
 * One add-server (or sign-in-existing) session. Swift creates it when the dialog opens and calls [close] when it
 * goes away. The Quick Connect poll is the suspend [runQuickConnect]: Swift runs it in a `.task(id:)` bound to the
 * code screen being visible and the scene active, and task cancellation cancels the coroutine (SKIE), so nothing
 * polls in the background (Swiftfin's QuickConnectView does the same with `.task`).
 */
class TvAddServerSession(existingKey: String? = null) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val runtime = MediaServerRuntime.production.also { it.entryStore.ensureLoaded() }
    private val controller = AddServerController(
        services = runtime.services,
        accounts = MediaServerAccounts(runtime.entryStore, runtime.services),
        trust = MediaServerTrust.shared,
        scope = scope,
        existing = existingKey?.let(runtime.entryStore::entryByKey),
        onSignedIn = { entry -> MediaServerHomeSurfaces.changed(runtime, entry) },
    )

    val state: StateFlow<TvAddState> = controller.state.map { s ->
        TvAddState(
            stage = when (s.stage) {
                AddStage.ADDRESS -> TvAddStage.ADDRESS
                AddStage.CHOOSE_SIGN_IN -> TvAddStage.CHOOSE_SIGN_IN
                AddStage.QUICK_CONNECT -> TvAddStage.QUICK_CONNECT
                AddStage.PASSWORD -> TvAddStage.PASSWORD
                AddStage.OFFER_HOME_ROW -> TvAddStage.OFFER_HOME_ROW
                AddStage.DONE -> TvAddStage.DONE
            },
            address = s.address,
            selectedType = s.selectedType?.wire,
            busy = s.busy,
            error = s.error?.let { e ->
                when (e) {
                    AddError.INVALID_ADDRESS -> TvAddError.INVALID_ADDRESS
                    AddError.NOT_A_MEDIA_SERVER -> TvAddError.NOT_A_MEDIA_SERVER
                    AddError.UNREACHABLE -> TvAddError.UNREACHABLE
                    AddError.WRONG_CREDENTIALS -> TvAddError.WRONG_CREDENTIALS
                    AddError.QUICK_CONNECT_FAILED -> TvAddError.QUICK_CONNECT_FAILED
                    AddError.SIGN_IN_FAILED -> TvAddError.SIGN_IN_FAILED
                    AddError.DIFFERENT_SERVER -> TvAddError.DIFFERENT_SERVER
                    AddError.NOT_SAVED -> TvAddError.NOT_SAVED
                    AddError.UNUSABLE_SERVER -> TvAddError.UNUSABLE_SERVER
                }
            },
            certAuthority = s.certPrompt?.authority,
            certFingerprint = s.certPrompt?.fingerprint?.let { f ->
                if (f.length % 2 == 0 && ':' !in f) f.chunked(2).joinToString(":") else f
            },
            foundName = s.found?.info?.name,
            foundProduct = s.found?.let { listOfNotNull(it.type.productName, it.info.version).joinToString(" ") },
            serverName = s.serverName,
            typeCorrection = s.typeCorrectedFrom?.let { "${it.productName}|${s.found?.type?.productName.orEmpty()}" },
            quickConnectAvailable = s.quickConnectAvailable,
            publicUsers = s.publicUsers.map { it.name },
            quickConnectCode = s.quickConnect?.displayCode,
            quickConnectStartedAtMs = s.quickConnect?.startedAtMs ?: 0L,
            signedInName = s.signedIn?.name,
            signingInExisting = s.signingInExisting,
        )
    }.stateIn(scope, SharingStarted.Eagerly, controller.state.value.let { initialState() })

    private fun initialState() = TvAddState(
        TvAddStage.ADDRESS, controller.state.value.address, controller.state.value.selectedType?.wire, false, null, null, null,
        null, null, "", null, false, emptyList(), null, 0L, null, controller.state.value.signingInExisting,
    )

    fun setAddress(text: String) = controller.setAddress(text)
    fun selectType(wire: String?) = controller.selectType(MediaServerType.fromWire(wire))
    fun connect() = controller.connect()
    fun trustCertificate() = controller.trustCertificate()
    fun declineCertificate() = controller.declineCertificate()
    fun setServerName(text: String) = controller.setServerName(text)
    fun startQuickConnect() = controller.startQuickConnect()
    suspend fun runQuickConnect() = controller.runQuickConnect()
    fun usePassword() = controller.usePassword()
    fun backToChoice() = controller.backToChoice()
    fun backToAddress() = controller.backToAddress()
    fun submitPassword(username: String, password: String) = controller.submitPassword(username, password)
    fun enableRecentlyAdded() = controller.enableRecentlyAdded()
    fun skipHomeRowOffer() = controller.skipHomeRowOffer()

    /** "9:41" for the code screen's countdown. */
    fun countdownLabel(startedAtMs: Long, nowMs: Long): String =
        QuickConnectPolicy.countdownLabel(QuickConnectPolicy.remainingMs(startedAtMs, nowMs))

    fun nowMs(): Long = runtime.nowMs()

    fun close() {
        controller.cancel()
        scope.cancel()
    }
}

// ---------------------------------------------------------------- one server

data class TvLibraryRow(val id: String, val name: String, val onHome: Boolean)

data class TvServerDetails(
    val key: String,
    val name: String,
    val subtitle: String,
    val status: TvServerStatus,
    val signedIn: Boolean,
    val enabled: Boolean,
    val syncAddress: Boolean,
    val homeContinueWatching: Boolean,
    val homeNextUp: Boolean,
    val homeRecentlyAdded: Boolean,
    /** Libraries can be listed (signed in, enabled, has an address). */
    val canListLibraries: Boolean,
)

enum class TvLibrariesState { LOADING, READY, EMPTY, FAILED }

data class TvLibraries(val state: TvLibrariesState, val libraries: List<TvLibraryRow>)

class TvServerDetailsSession(private val key: String) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val runtime = MediaServerRuntime.production.also { it.entryStore.ensureLoaded() }
    private val management = MediaServerManagement(
        accounts = MediaServerAccounts(runtime.entryStore, runtime.services),
        services = runtime.services,
        onChanged = { changed -> MediaServerHomeSurfaces.changed(runtime, changed) },
    )

    private fun build(entries: List<MediaServerEntry>, expired: Set<String>): TvServerDetails? {
        val e = entries.firstOrNull { it.key == key } ?: return null
        val signedIn = runtime.services.isSignedIn(e)
        return TvServerDetails(
            key = e.key, name = e.name, subtitle = subtitleOf(e),
            status = MediaServerStatusPolicy.of(e, signedIn, e.serverKey in expired, null).toTv(),
            signedIn = signedIn, enabled = e.enabled, syncAddress = e.syncAddress,
            homeContinueWatching = MediaServerHomeRow.CONTINUE_WATCHING in e.homeRows,
            homeNextUp = MediaServerHomeRow.NEXT_UP in e.homeRows,
            homeRecentlyAdded = MediaServerHomeRow.RECENTLY_ADDED in e.homeRows,
            canListLibraries = signedIn && e.enabled && !e.address.isNullOrBlank(),
        )
    }

    /** Null once the entry is gone (removed here or from another device); the FIRST value is already the entry, never a placeholder. */
    val details: StateFlow<TvServerDetails?> = combine(
        runtime.entryStore.entries, runtime.services.expiredSessions, runtime.services.credentialVersion,
    ) { entries, expired, _ -> build(entries, expired) }
        .stateIn(scope, SharingStarted.Eagerly, build(runtime.entryStore.current(), runtime.services.expiredSessions.value))

    private fun entry(): MediaServerEntry? = runtime.entryStore.entryByKey(key)

    fun rename(name: String) { entry()?.let { management.rename(it, name) } }
    fun setEnabled(on: Boolean) { entry()?.let { management.setEnabled(it, on) } }
    fun setSyncAddress(on: Boolean) { entry()?.let { management.setSyncAddress(it, on) } }

    /** [row]: "continue_watching" | "next_up" | "recently_added". */
    fun setHomeRow(row: String, on: Boolean) {
        val r = MediaServerHomeRow.entries.firstOrNull { it.name.lowercase() == row } ?: return
        entry()?.let { management.setHomeRow(it, r, on) }
    }

    fun setHomeLibrary(id: String, name: String, on: Boolean) { entry()?.let { management.setHomeLibrary(it, id, name, on) } }

    private val librariesFlow = MutableStateFlow(TvLibraries(TvLibrariesState.LOADING, emptyList()))
    val libraries: StateFlow<TvLibraries> = librariesFlow.asStateFlow()

    /** An authenticated call, made once when the page opens (a revoked token is noticed here too). */
    suspend fun loadLibraries() {
        val e = entry() ?: return
        val client = runtime.services.clientFor(e)
        if (client == null) { librariesFlow.value = TvLibraries(TvLibrariesState.EMPTY, emptyList()); return }
        try {
            val list = MediaServerLibraries.browsable(client.views())
            librariesFlow.value = TvLibraries(
                if (list.isEmpty()) TvLibrariesState.EMPTY else TvLibrariesState.READY,
                list.map { TvLibraryRow(it.id, it.name, it.id in e.homeLibraries) },
            )
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (ex: MediaServerException) {
            if ((ex as? MediaServerException.Http)?.isUnauthorized == true) runtime.services.onUnauthorized(e.serverKey)
            librariesFlow.value = TvLibraries(TvLibrariesState.FAILED, emptyList())
        }
    }

    /** Re-reads which libraries are on Home after a toggle (the list itself is not re-fetched). */
    fun refreshLibraryFlags() {
        val e = entry() ?: return
        librariesFlow.update { cur -> cur.copy(libraries = cur.libraries.map { it.copy(onHome = it.id in e.homeLibraries) }) }
    }

    suspend fun signOut() { entry()?.let { management.signOut(it) } }

    suspend fun remove(purgeSavedData: Boolean): Boolean = entry()?.let { management.remove(it, purgeSavedData) } ?: false

    fun close() { scope.cancel() }
}
