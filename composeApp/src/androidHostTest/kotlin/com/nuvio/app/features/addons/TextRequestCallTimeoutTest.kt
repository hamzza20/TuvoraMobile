package com.nuvio.app.features.addons

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression: a provider that trickles its answer — a byte now and then, so the 60 s read timeout (which
 * only measures silence) never fires — held a text request, and the IPTV page waiting on it, forever.
 * Text requests now carry a whole-call limit, as iOS's requestTimeoutMillis already did.
 */
class TextRequestCallTimeoutTest {
    private val server = MockWebServer()

    @AfterTest
    fun tearDown() {
        textRequestCallTimeoutMs = 60_000L
        server.shutdown()
    }

    @Test
    fun `a provider that trickles bytes cannot hold a text request past the call limit`() = runBlocking {
        server.enqueue(MockResponse().setBody("[" + "x".repeat(1_000) + "]").throttleBody(1, 300, TimeUnit.MILLISECONDS))
        server.start()
        textRequestCallTimeoutMs = 1_500L
        val started = System.currentTimeMillis()

        val result = runCatching { httpGetText(server.url("/player_api.php").toString(), null) }
        val waited = System.currentTimeMillis() - started

        assertTrue(result.isFailure, "the trickled request must fail, not return")
        assertTrue(waited < 6_000, "the call limit must end it (waited ${waited}ms)")
    }
}
