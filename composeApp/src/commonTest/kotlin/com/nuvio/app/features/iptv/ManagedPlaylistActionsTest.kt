package com.nuvio.app.features.iptv

import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Detach: the server call, then the managed map refreshed and ONE playlist pull — in that order. */
class ManagedPlaylistActionsTest {
    private val log = mutableListOf<String>()
    private var detachResult: () -> Boolean = { true }
    private val events = mutableListOf<String>()

    private val api = object : ProviderSetupApi {
        override suspend fun preview(code: String): SetupCodeOutcome = error("not used")
        override suspend fun redeem(code: String, profileIndex: Int): RedeemResult = error("not used")
        override suspend fun managedPlaylists(profileId: Int): List<ManagedInfo> = error("not used")
        override suspend fun detach(profileId: Int, playlistKey: String): Boolean {
            log += "detach($profileId,$playlistKey)"
            return detachResult()
        }
    }

    private val actions = ManagedPlaylistActions(
        api = { api }, activeProfile = { 2 },
        pull = { log += "pull($it)" }, refresh = { log += "refresh($it)"; true },
        telemetry = ProviderSetupTelemetry,
    )

    @BeforeTest
    fun setUp() {
        ProviderSetupTelemetry.capture = { name, _ -> events += name }
    }

    @AfterTest
    fun tearDown() {
        ProviderSetupTelemetry.capture = com.nuvio.app.core.analytics.AnalyticsSink::capture
    }

    @Test
    fun `detach asks the server then refreshes the map then pulls once`() = runBlocking {
        assertTrue(actions.detach("key-1"))
        assertEquals(listOf("detach(2,key-1)", "refresh(2)", "pull(2)"), log)
        assertEquals(listOf("playlist_detached"), events)
    }

    @Test
    fun `a playlist that was already detached elsewhere still ends managed`() = runBlocking {
        detachResult = { false }
        assertTrue(actions.detach("key-1"))
        assertEquals(listOf("detach(2,key-1)", "refresh(2)", "pull(2)"), log)
    }

    @Test
    fun `a failed detach changes nothing and reports it`() = runBlocking {
        detachResult = { throw RuntimeException("offline") }
        assertFalse(actions.detach("key-1"))
        assertEquals(listOf("detach(2,key-1)"), log, "no refresh, no pull, nothing logged for the provider")
        assertEquals(emptyList(), events)
    }
}
