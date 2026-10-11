package com.tuvora.tvos.screens

import com.nuvio.app.features.servers.QuickConnectDriver
import com.nuvio.app.features.servers.QuickConnectFailure
import com.nuvio.app.features.servers.QuickConnectPolicy
import com.nuvio.app.features.servers.QuickConnectState
import com.nuvio.app.features.servers.ServerCapability
import com.nuvio.app.features.servers.ServerCatalog
import com.nuvio.app.features.servers.ServerCertificateException
import com.nuvio.app.features.servers.ServerConnection
import com.nuvio.app.features.servers.ServerException
import com.nuvio.app.features.servers.ServerFailure
import com.nuvio.app.features.servers.ServerProvider
import com.nuvio.app.features.servers.ServerProviders
import com.nuvio.app.features.servers.ServerRepository
import com.nuvio.app.features.servers.ServerTrust
import com.nuvio.app.features.servers.supports
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * Settings -> Integrations -> Media servers for Apple TV, over the SAME shared stack the phones use (features/servers):
 * [ServerRepository] (connections, device-local tokens), the providers' discover / sign-in / Quick Connect, [ServerTrust]
 * (trust-on-first-use pins, device-local). This file only reshapes that state into public value types Swift can read;
 * no I/O decision lives in the views.
 */
data class TvServerRow(
    val key: String,
    val name: String,
    /** "Jellyfin - kid - nas.local:8096". */
    val subtitle: String,
    val status: TvServerStatus,
    val checking: Boolean,
)

private fun ServerConnection.subtitle(): String = TvServerStatusPolicy.subtitle(
    product = ServerRepository.provider(this)?.displayName ?: providerId,
    userName = userName,
    address = address,
)

private fun ServerConnection.status(failure: ServerFailure?): TvServerStatus =
    TvServerStatusPolicy.of(enabled, hasLocalToken = !ServerRepository.needsSignIn(id), failure = failure)

object TvMediaServers {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val checking = MutableStateFlow<Set<String>>(emptySet())

    /** The saved servers with their status badge; re-emitted when connections, failures or a health check change. */
    val rows: StateFlow<List<TvServerRow>> by lazy {
        ServerRepository.ensureLoaded()
        combine(ServerRepository.uiState, checking) { state, busy ->
            state.connections.map { c ->
                TvServerRow(c.id, c.name, c.subtitle(), c.status(state.failures[c.id]), checking = c.id in busy)
            }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())
    }

    fun ensureLoaded() = ServerRepository.ensureLoaded()

    /**
     * One authenticated check per visit (never a timer): the Settings pane calls this when it becomes visible. A revoked token
     * or an unreachable server shows on the row afterwards. CLAUDE.md recurring-network rule: one request per server, only
     * while visible, none for a server that is off or has no sign-in on this device.
     */
    fun checkOnce() {
        ensureLoaded()
        ServerRepository.uiState.value.connections
            .filter { it.enabled && !ServerRepository.needsSignIn(it.id) && it.id !in checking.value }
            .forEach { connection ->
                checking.update { it + connection.id }
                scope.launch {
                    try {
                        runCatching { ServerRepository.refreshLibraries(connection.id) }
                            .onFailure { if (it is CancellationException) throw it }
                    } finally {
                        checking.update { it - connection.id }
                    }
                }
            }
    }

    fun serverCount(): Int = ServerRepository.uiState.value.connections.size

    /** Ask Home to re-pull its rows once (a server was added, changed or removed). */
    fun refreshHomeRows() = TvHome.refresh()

    /** Smoke/UI-test hook: the id of the first server (for -smoke deep links). */
    fun firstKey(): String? { ensureLoaded(); return ServerRepository.uiState.value.connections.firstOrNull()?.id }
}

// ---------------------------------------------------------------- add / sign in

enum class TvAddStage { ADDRESS, CHOOSE_SIGN_IN, QUICK_CONNECT, PASSWORD, DONE }

data class TvAddState(
    val stage: TvAddStage,
    val address: String,
    /** "jellyfin" or "emby". */
    val selectedType: String,
    val busy: Boolean,
    val error: TvAddError?,
    /** Set while the certificate must be trusted (or re-trusted) before going on. */
    val certAuthority: String?,
    /** Colon-grouped hex. */
    val certFingerprint: String?,
    /** The device had pinned a DIFFERENT certificate for this server: show the stronger warning. */
    val certChanged: Boolean,
    val foundName: String?,
    /** "Jellyfin 10.9.0". */
    val foundProduct: String?,
    val quickConnectAvailable: Boolean,
    val quickConnectCode: String?,
    val quickConnectStartedAtMs: Long,
    /** Bumped every time a Quick Connect request starts: the screen re-runs its poll on it. */
    val quickConnectAttempt: Int,
    val signingInExisting: Boolean,
    /** The viewer's server types offered on the address step (Quick Connect only exists on Jellyfin). */
    val providerNames: List<String>,
)

/**
 * One add-server (or sign-in-existing) session. Swift creates it when the dialog opens and calls [close] when it goes away.
 * The Quick Connect poll is the suspend [runQuickConnect]: Swift runs it in a `.task(id:)` bound to the code screen being
 * visible and the scene active, and task cancellation cancels the coroutine (SKIE), so nothing polls in the background.
 */
class TvAddServerSession(existingId: String? = null) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val existing: ServerConnection? = existingId?.let { ServerRepository.connection(it) }
    private val mutable = MutableStateFlow(
        TvAddState(
            stage = TvAddStage.ADDRESS,
            address = existing?.address.orEmpty(),
            selectedType = existing?.providerId ?: "jellyfin",
            busy = false, error = null, certAuthority = null, certFingerprint = null, certChanged = false,
            foundName = null, foundProduct = null, quickConnectAvailable = false, quickConnectCode = null,
            quickConnectStartedAtMs = 0L, quickConnectAttempt = 0, signingInExisting = existing != null,
            providerNames = ServerProviders.registered.map { it.id },
        ),
    )
    val state: StateFlow<TvAddState> = mutable.asStateFlow()

    private var driver: QuickConnectDriver? = null
    private var driverJob: kotlinx.coroutines.Job? = null
    private var work: kotlinx.coroutines.Job? = null
    private var resolvedAddress: String? = null

    init {
        // Signing in an existing server: go straight to the sign-in choice (its address and product are known).
        if (existing != null) connect()
    }

    private fun provider(): ServerProvider =
        ServerProviders.forId(mutable.value.selectedType) ?: ServerProviders.registered.first()

    fun setAddress(text: String) = mutable.update { it.copy(address = text, error = null, certAuthority = null) }

    fun selectType(wire: String) {
        if (existing != null) return
        mutable.update { it.copy(selectedType = wire, error = null, certAuthority = null) }
    }

    /** Reach the server and read its name (and certificate) before asking for credentials. */
    fun connect() {
        val current = mutable.value
        if (current.busy || current.address.isBlank()) return
        mutable.update { it.copy(busy = true, error = null, certAuthority = null, certFingerprint = null) }
        work?.cancel()
        work = scope.launch {
            val provider = provider()
            try {
                val found = provider.discover(mutable.value.address)
                resolvedAddress = found.address
                val quick = provider.supports(ServerCapability.QUICK_CONNECT) &&
                    runCatching { provider.quickConnectAvailable(found.address) }.getOrDefault(false)
                mutable.update {
                    it.copy(
                        stage = TvAddStage.CHOOSE_SIGN_IN, busy = false, foundName = found.serverName,
                        foundProduct = listOfNotNull(provider.displayName, found.version).joinToString(" "),
                        quickConnectAvailable = quick,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (untrusted: ServerCertificateException) {
                showCertificate(untrusted)
            } catch (error: ServerException) {
                mutable.update { it.copy(busy = false, error = TvServerAddErrorPolicy.of(error.failure, duringSignIn = false)) }
            } catch (_: Exception) {
                mutable.update { it.copy(busy = false, error = TvAddError.UNREACHABLE) }
            }
        }
    }

    private fun showCertificate(untrusted: ServerCertificateException) = mutable.update {
        it.copy(
            busy = false,
            certAuthority = untrusted.authority,
            certFingerprint = untrusted.fingerprint.chunked(2).joinToString(":"),
            certChanged = untrusted.changed,
        )
    }

    private var pendingCertificate: ServerCertificateException? = null

    /** The viewer accepted the certificate shown: pin it on this device (never synced) and continue. */
    fun trustCertificate() {
        val authority = mutable.value.certAuthority ?: return
        val fingerprint = mutable.value.certFingerprint?.replace(":", "") ?: return
        ServerTrust.shared.pin(authority, fingerprint)
        mutable.update { it.copy(certAuthority = null, certFingerprint = null, certChanged = false) }
        connect()
    }

    fun declineCertificate() = mutable.update { it.copy(certAuthority = null, certFingerprint = null, certChanged = false, busy = false) }

    fun startQuickConnect() {
        val address = resolvedAddress ?: return
        if (!provider().supports(ServerCapability.QUICK_CONNECT)) return
        driverJob?.cancel()
        val next = QuickConnectDriver(provider(), address)
        driver = next
        mutable.update { it.copy(stage = TvAddStage.QUICK_CONNECT, error = null, quickConnectCode = null, quickConnectAttempt = it.quickConnectAttempt + 1) }
        driverJob = scope.launch {
            next.start()
            publishQuickConnect(next)
        }
    }

    private fun publishQuickConnect(from: QuickConnectDriver) {
        when (val s = from.state.value) {
            is QuickConnectState.Waiting -> mutable.update {
                it.copy(quickConnectCode = QuickConnectPolicy.displayCode(s.code), quickConnectStartedAtMs = s.startedAtMs)
            }
            is QuickConnectState.Failed -> failQuickConnect(from, s.reason)
            else -> Unit
        }
    }

    private fun failQuickConnect(from: QuickConnectDriver, reason: QuickConnectFailure) {
        if (reason == QuickConnectFailure.CERTIFICATE) {
            from.certificate?.let { cert ->
                mutable.update { it.copy(stage = TvAddStage.ADDRESS) }
                showCertificate(cert)
                return
            }
        }
        mutable.update {
            it.copy(
                error = when (reason) {
                    QuickConnectFailure.DISABLED -> TvAddError.QUICK_CONNECT_OFF
                    else -> TvAddError.QUICK_CONNECT_FAILED
                },
            )
        }
    }

    /**
     * Polls until the request is approved, expires or fails; then signs in with the approved request. Cancelled with the
     * screen (task cancellation); safe to call again for the same request after a pause (it continues it).
     */
    suspend fun runQuickConnect() {
        val active = driver ?: return
        active.run()
        publishQuickConnect(active)
        when (active.state.value) {
            QuickConnectState.Authorised -> {
                mutable.update { it.copy(busy = true) }
                try {
                    val connection = active.complete()
                    finish(connection)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (untrusted: ServerCertificateException) {
                    mutable.update { it.copy(busy = false, stage = TvAddStage.ADDRESS) }
                    showCertificate(untrusted)
                } catch (error: ServerException) {
                    mutable.update { it.copy(busy = false, error = TvServerAddErrorPolicy.of(error.failure, duringSignIn = true)) }
                } catch (_: IllegalStateException) {
                    // The sign-in worked but this device could not keep it (the keychain refused the write).
                    mutable.update { it.copy(busy = false, error = TvAddError.NOT_SAVED) }
                } catch (_: Exception) {
                    mutable.update { it.copy(busy = false, error = TvAddError.SIGN_IN_FAILED) }
                }
            }
            QuickConnectState.Expired -> mutable.update { it.copy(error = TvAddError.QUICK_CONNECT_EXPIRED, quickConnectCode = null) }
            else -> Unit
        }
    }

    fun usePassword() {
        driverJob?.cancel()
        driver?.cancel()
        driver = null
        mutable.update { it.copy(stage = TvAddStage.PASSWORD, error = null) }
    }

    fun backToChoice() {
        driverJob?.cancel()
        driver?.cancel()
        driver = null
        mutable.update { it.copy(stage = TvAddStage.CHOOSE_SIGN_IN, error = null, quickConnectCode = null, busy = false) }
    }

    fun backToAddress() {
        mutable.update { it.copy(stage = TvAddStage.ADDRESS, error = null, busy = false, foundName = null, foundProduct = null) }
    }

    fun submitPassword(username: String, password: String) {
        val address = resolvedAddress ?: return
        if (mutable.value.busy || username.isBlank()) return
        mutable.update { it.copy(busy = true, error = null) }
        work?.cancel()
        work = scope.launch {
            try {
                finish(ServerRepository.connect(provider(), address, username.trim(), password))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (untrusted: ServerCertificateException) {
                mutable.update { it.copy(busy = false, stage = TvAddStage.ADDRESS) }
                showCertificate(untrusted)
            } catch (error: ServerException) {
                mutable.update { it.copy(busy = false, error = TvServerAddErrorPolicy.of(error.failure, duringSignIn = true)) }
            } catch (_: IllegalStateException) {
                mutable.update { it.copy(busy = false, error = TvAddError.NOT_SAVED) }
            } catch (_: Exception) {
                mutable.update { it.copy(busy = false, error = TvAddError.SIGN_IN_FAILED) }
            }
        }
    }

    /** The sign-in landed: refuse a different server than the one being signed in, otherwise close the flow. */
    private fun finish(connection: ServerConnection) {
        val wanted = existing
        if (wanted != null && connection.remoteServerId != wanted.remoteServerId) {
            ServerRepository.remove(connection.id)
            mutable.update { it.copy(busy = false, error = TvAddError.DIFFERENT_SERVER) }
            return
        }
        TvMediaServers.refreshHomeRows()
        mutable.update { it.copy(busy = false, stage = TvAddStage.DONE) }
    }

    /** "9:41" for the code screen's countdown. */
    fun countdownLabel(startedAtMs: Long, nowMs: Long): String =
        QuickConnectPolicy.countdownLabel(QuickConnectPolicy.remainingMs(startedAtMs, nowMs))

    fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

    fun close() {
        driverJob?.cancel()
        driver?.cancel()
        scope.cancel()
    }
}

// ---------------------------------------------------------------- one server

data class TvLibraryRow(val id: String, val name: String, val selected: Boolean)

data class TvServerDetails(
    val key: String,
    val name: String,
    val subtitle: String,
    val status: TvServerStatus,
    val signedIn: Boolean,
    val enabled: Boolean,
    val useCatalogMetadata: Boolean,
    val importWatchState: Boolean,
    val libraries: List<TvLibraryRow>,
    val providerName: String,
    /** Titles can open with the add-ons' details (needs the provider's external-id lookup). */
    val supportsCatalogMetadata: Boolean,
    val supportsWatchState: Boolean,
)

class TvServerDetailsSession(private val key: String) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private fun build(): TvServerDetails? {
        ServerRepository.ensureLoaded()
        val state = ServerRepository.uiState.value
        val c = state.connections.firstOrNull { it.id == key } ?: return null
        val status = c.status(state.failures[c.id])
        return TvServerDetails(
            key = c.id, name = c.name, subtitle = c.subtitle(), status = status,
            signedIn = status != TvServerStatus.NEEDS_SIGN_IN && status != TvServerStatus.SIGN_IN_AGAIN,
            enabled = c.enabled, useCatalogMetadata = c.useCatalogMetadata, importWatchState = c.importWatchState,
            libraries = c.libraries.map { TvLibraryRow(it.id, it.name, it.selected) },
            providerName = ServerRepository.provider(c)?.displayName ?: c.providerId,
            supportsCatalogMetadata = ServerRepository.provider(c)?.supports(ServerCapability.EXTERNAL_ID_LOOKUP) == true,
            supportsWatchState = ServerRepository.provider(c)?.supports(ServerCapability.USER_STATE_READ) == true,
        )
    }

    /** Null once the connection is gone (removed here or from another device). */
    val details: StateFlow<TvServerDetails?> = ServerRepository.uiState.map { build() }
        .stateIn(scope, SharingStarted.Eagerly, build())

    fun setEnabled(on: Boolean) = ServerRepository.setEnabled(key, on)
    fun setCatalogMetadata(on: Boolean) = ServerRepository.setCatalogMetadata(key, on)
    fun setImportWatchState(on: Boolean) = ServerRepository.setImportWatchState(key, on)
    fun setLibrarySelected(id: String, on: Boolean) = ServerRepository.setLibrarySelected(key, id, on)

    /** Re-reads the libraries from the server once when the page opens (a revoked token is noticed here too). */
    suspend fun refreshLibraries() {
        runCatching { ServerRepository.refreshLibraries(key) }.onFailure { if (it is CancellationException) throw it }
    }

    fun remove() {
        ServerRepository.remove(key)
        TvMediaServers.refreshHomeRows()
    }

    fun close() { scope.cancel() }
}

// ---------------------------------------------------------------- Library "Servers" shelves

data class TvServerShelf(val key: String, val title: String, val items: List<com.nuvio.app.features.home.MetaPreview>, val failed: Boolean)

/** The Library's "Servers" source: one shelf per selected server library (the phone's loadServerShelves). */
object TvServerLibrary {
    fun hasServers(): Boolean { ServerRepository.ensureLoaded(); return ServerRepository.uiState.value.enabledConnections.isNotEmpty() }

    suspend fun shelves(): List<TvServerShelf> = coroutineScope {
        ServerRepository.ensureLoaded()
        ServerCatalog.libraries().map { ref ->
            async {
                try {
                    val section = ServerCatalog.librarySection(ref, SHELF_LIMIT)
                    TvServerShelf("${ref.connection.id}:${ref.library.id}", ref.title, section.items, failed = false)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    TvServerShelf("${ref.connection.id}:${ref.library.id}", ref.title, emptyList(), failed = true)
                }
            }
        }.awaitAll()
    }

    private const val SHELF_LIMIT = 18
}
