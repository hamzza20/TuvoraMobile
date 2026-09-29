package com.tuvora.tvos.screens

import co.touchlab.kermit.Logger
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.features.iptv.stalker.StalkerCrypto
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.resume
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Serializable
private data class PairingStart(
    val code: String,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("poll_interval_seconds") val pollIntervalSeconds: Int = 3,
)

@Serializable
private data class PairingPoll(
    val status: String,
    val payload: JsonElement? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("poll_interval_seconds") val pollIntervalSeconds: Int? = null,
)

/** Where "Add from phone" is: the screen renders it (NuvioTV IptvPairingStatus). */
data class TvPairingState(
    /** loading | waiting | saving | success | expired | error */
    val status: String,
    val code: String? = null,
    val webUrl: String? = null,
    val expiresAtMs: Long? = null,
    val message: String? = null,
)

/**
 * NuvioTV's IPTV pairing (core/iptv/IptvPairingManager.kt + IptvPairingViewModel.kt) over the same
 * anon-callable RPCs: `create_iptv_pairing` with the hash of a device secret that never leaves the TV,
 * then `poll_iptv_pairing` until the phone submits a playlist, which is saved through the settings
 * Add path. [run] is the whole session; the Swift dialog runs it in a `.task`, so closing the dialog
 * cancels the poll (lifecycle-bound; it also ends by itself at the code's expiry).
 */
object TvIptvPairing {
    private val log = Logger.withTag("TvIptvPairing")

    @OptIn(ExperimentalUuidApi::class)
    suspend fun run(onState: (TvPairingState) -> Unit) {
        onState(TvPairingState("loading"))
        val secret = Uuid.random().toHexString() + Uuid.random().toHexString()
        val hash = StalkerCrypto.sha256Hex(secret)
        val started = try {
            SupabaseProvider.client.postgrest.rpc("create_iptv_pairing", buildJsonObject {
                put("p_code", TvIptvPairingPolicy.codeFrom(Uuid.random().toByteArray()))
                put("p_code_hash", hash)
            }).decodeList<PairingStart>().firstOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log.w(e) { "create_iptv_pairing failed" }
            null
        } ?: return onState(TvPairingState("error", message = "Something went wrong. Try again."))

        val code = started.code
        val webUrl = TvIptvPairingPolicy.webUrl(TvIptvPairingPolicy.WEB_BASE_URL, code)
        var expiresAt = started.expiresAt?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() }
        var interval = started.pollIntervalSeconds.coerceAtLeast(2)
        onState(TvPairingState("waiting", code, webUrl, expiresAt))

        while (true) {
            delay(interval * 1000L)
            val poll = try {
                SupabaseProvider.client.postgrest.rpc("poll_iptv_pairing", buildJsonObject {
                    put("p_code", code)
                    put("p_code_hash", hash)
                }).decodeList<PairingPoll>().firstOrNull()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log.w(e) { "poll failed, will retry" }   // NuvioTV: a failed poll keeps the session
                null
            } ?: continue
            poll.pollIntervalSeconds?.let { interval = it.coerceAtLeast(2) }
            poll.expiresAt?.let { raw -> runCatching { Instant.parse(raw).toEpochMilliseconds() }.getOrNull()?.let { expiresAt = it } }
            when (poll.status.lowercase()) {
                "consumed" -> {
                    val form = TvIptvPairingPolicy.payloadToForm(poll.payload)
                        ?: return onState(TvPairingState("expired", code, webUrl, expiresAt))
                    onState(TvPairingState("saving", code, webUrl, expiresAt))
                    val saved = suspendCancellableCoroutine { cont -> TvPlaylists.add(form) { ok -> if (cont.isActive) cont.resume(ok) } }
                    val name = form.name.ifBlank { null }
                    return onState(
                        if (saved) TvPairingState("success", code, webUrl, expiresAt,
                            message = name?.let { "$it is now on your TV." } ?: "Your playlist is now on your TV.")
                        else TvPairingState("error", code, webUrl, expiresAt,
                            message = TvPlaylists.state.value.error ?: "Something went wrong. Try again."),
                    )
                }
                "expired" -> return onState(TvPairingState("expired", code, webUrl, expiresAt))
                else -> onState(TvPairingState("waiting", code, webUrl, expiresAt))
            }
        }
    }
}
