package com.nuvio.app.features.iptv

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** One panel question per playlist per freshness window; never for a playlist with no panel. */
class PlaylistAccountInfoStoreTest {
    private var now = 1_000_000L
    private var calls = 0
    private val info = XtreamAccountInfo(status = "Active", isTrial = false, expiresAtEpochSec = 99, maxConnections = 1, activeConnections = 0)
    private var answer: XtreamAccountInfo? = info
    private val store = PlaylistAccountInfoStore(fetch = { calls++; answer }, clock = { now })
    private val xtream = XtreamAccount(id = "k1", name = "A", baseUrl = "http://a.example.com", username = "u", password = "p")

    @Test
    fun `a fresh answer is reused without asking again`() = runBlocking {
        assertEquals(info, store.infoFor(xtream))
        assertEquals(info, store.infoFor(xtream))
        assertEquals(1, calls)
        now += AccountInfoFreshnessPolicy.TTL_MS - 1
        store.infoFor(xtream)
        assertEquals(1, calls)
    }

    @Test
    fun `a stale answer is asked for again`() = runBlocking {
        store.infoFor(xtream)
        now += AccountInfoFreshnessPolicy.TTL_MS
        store.infoFor(xtream)
        assertEquals(2, calls)
    }

    @Test
    fun `an M3U playlist has no panel to ask`() = runBlocking {
        assertNull(store.infoFor(xtream.copy(sourceType = SOURCE_TYPE_M3U_URL)))
        assertEquals(0, calls)
    }

    @Test
    fun `an unreachable panel keeps the last known answer`() = runBlocking {
        store.infoFor(xtream)
        now += AccountInfoFreshnessPolicy.TTL_MS
        answer = null
        assertEquals(info, store.infoFor(xtream), "still shows what the panel last said")
        assertEquals(info, store.known.value["k1"])
    }

    @Test
    fun `freshness table`() {
        assertTrue(AccountInfoFreshnessPolicy.isFresh(0, 1))
        assertFalse(AccountInfoFreshnessPolicy.isFresh(0, AccountInfoFreshnessPolicy.TTL_MS))
        assertFalse(AccountInfoFreshnessPolicy.isFresh(10, 5), "a clock that went backwards is not fresh")
    }
}
