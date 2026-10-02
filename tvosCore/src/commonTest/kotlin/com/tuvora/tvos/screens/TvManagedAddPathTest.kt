package com.tuvora.tvos.screens

import com.nuvio.app.features.iptv.ManagedEditPolicy
import com.nuvio.app.features.iptv.ManagedInfo
import com.nuvio.app.features.iptv.ManagedInfoRepository
import com.nuvio.app.features.iptv.InMemoryManagedInfoStore
import com.nuvio.app.features.iptv.XtreamAccount
import com.nuvio.app.features.iptv.XtreamRepository
import com.nuvio.app.features.iptv.xtreamAccountFromForm
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Apple TV Add Playlist door (TvPlaylists.add -> TvPlaylistFormPolicy.toInput -> XtreamRepository.addFromForm) on
 * a server + login the provider already installed: the managed row must stay byte-identical (security review M3 /
 * code review H1; the shared guard is Mobile's c9380887f, proven here through Apple TV's own form).
 */
class TvManagedAddPathTest {

    private val managedRow = XtreamAccount(
        id = "x", name = "Acme", baseUrl = "http://Panel.Example.COM:80/", username = "u1", password = "p1",
        epgUrl = "http://EPG.Example.com:80/guide.xml/", userAgent = "Acme/1.0",
        backupUrls = listOf("HTTP://Backup.Example.com:80/"),
    )

    @BeforeTest
    fun setUp() {
        XtreamRepository.persistWriteForTest = { _, _ -> }
        ManagedInfoRepository.store = InMemoryManagedInfoStore()
    }

    @AfterTest
    fun reset() {
        XtreamRepository.verifyForTest = null
        XtreamRepository.persistWriteForTest = null
        XtreamRepository.clearLocalState()
        ManagedInfoRepository.resetForTest()
    }

    private fun add(form: TvPlaylistForm): Boolean = runBlocking {
        val done = CompletableDeferred<Boolean>()
        XtreamRepository.addFromForm(TvPlaylistFormPolicy.toInput(form)!!) { done.complete(it) }
        withTimeout(10_000) { done.await() }
    }

    @Test
    fun `typing the installed server and login into the Apple TV add form leaves the managed playlist untouched`() {
        XtreamRepository.verifyForTest = { Result.success(Unit) }
        val form = TvPlaylistFormPolicy.empty().copy(server = "HTTP://PANEL.example.com", username = "u1", password = "p1", name = "Typed by hand")
        val key = xtreamAccountFromForm(TvPlaylistFormPolicy.toInput(form)!!)!!.id
        val pulled = managedRow.copy(id = key)
        XtreamRepository.installAccountsForTest(listOf(pulled))
        ManagedInfoRepository.installForTest(1, mapOf(key to ManagedInfo(playlistKey = key, providerName = "Acme TV")))

        assertTrue(add(form))
        val after = XtreamRepository.uiState.value.accounts.single()
        assertEquals(pulled.baseUrl, after.baseUrl)
        assertEquals(pulled.epgUrl, after.epgUrl)
        assertEquals(pulled.userAgent, after.userAgent)
        assertEquals(pulled.backupUrls, after.backupUrls)
        assertFalse(ManagedEditPolicy.changesProviderFields(pulled, after), "the server would not detach it")
    }
}
