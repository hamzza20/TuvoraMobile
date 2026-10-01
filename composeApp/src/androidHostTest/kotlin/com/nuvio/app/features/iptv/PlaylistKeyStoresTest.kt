package com.nuvio.app.features.iptv

import android.app.Application
import android.content.Context
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.nuvio.app.features.epg.EpgMirrorDbDriver
import com.nuvio.app.features.iptv.content.IptvContentDbDriver
import com.nuvio.app.features.iptv.identity.IptvIdentity
import com.nuvio.app.features.iptv.match.MatchDbDriver
import com.nuvio.app.features.iptv.overlay.ChannelOverlay
import com.nuvio.app.features.iptv.overlay.IptvOverlayStore
import com.nuvio.app.features.iptv.overlay.OverlayDbDriver
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.library.LibraryStorage
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watched.WatchedStorage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Step 0 through the REAL stores (library, watched, live recents, overlay SQLite, the M3U file copy).
 *
 *  1. THE regression for the original bug: a provider moving domains — an address edit — keeps the
 *     playlist id, so a hidden channel (whose overlay key hashes the id) and every saved item survive.
 *  2. Adoption of a server key moves every prefix-keyed item and the file copy onto the key and
 *     drops nothing; the next pull re-keys nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlaylistKeyStoresTest {

    private lateinit var playlistsDir: File
    private val profile get() = ProfileRepository.activeProfileId

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        listOf("nuvio_library", "nuvio_watched", "nuvio_iptv").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        LibraryStorage.initialize(context)
        WatchedStorage.initialize(context)
        XtreamAccountStorage.initialize(context)
        LibraryRepository.clearLocalState()
        WatchedRepository.clearLocalState()
        XtreamLiveRecents.clearLocalState()
        playlistsDir = Files.createTempDirectory("playlists").toFile()
        m3uPlaylistsDirForTests = playlistsDir
        OverlayDbDriver.openForTests = { BundledSQLiteDriver().open(":memory:") }
        // The adoption's cache purge touches these stores too — keep them in memory.
        IptvContentDbDriver.openForTests = { BundledSQLiteDriver().open(":memory:") }
        MatchDbDriver.openForTests = { BundledSQLiteDriver().open(":memory:") }
        EpgMirrorDbDriver.openForTests = { BundledSQLiteDriver().open(":memory:") }
    }

    @After
    fun tearDown() {
        XtreamRepository.verifyForTest = null
        XtreamRepository.persistWriteForTest = null
        XtreamRepository.clearLocalState()
        LibraryRepository.clearLocalState()
        WatchedRepository.clearLocalState()
        XtreamLiveRecents.clearLocalState()
        m3uPlaylistsDirForTests = null
        playlistsDir.deleteRecursively()
    }

    private fun seedSaved(playlistId: String) {
        val prefix = XtreamItemRegistry.accountPrefix(playlistId)
        LibraryRepository.save(LibraryItem(id = "${prefix}movie:11", type = "movie", name = "Saved Movie", savedAtEpochMs = 1L))
        LibraryRepository.save(LibraryItem(id = "${prefix}live:7", type = "tv", name = "Fav Channel", savedAtEpochMs = 2L))
        WatchedRepository.markWatched(WatchedItem(id = "${prefix}movie:12", type = "movie", name = "Watched Movie", markedAtEpochMs = 3L))
        XtreamLiveRecents.record("${prefix}live:7", "Fav Channel", null)
    }

    private fun savedIds(playlistId: String): List<String> {
        val prefix = XtreamItemRegistry.accountPrefix(playlistId)
        return buildList {
            if (LibraryRepository.isLocalSaved("${prefix}movie:11", "movie")) add("library movie")
            if (LibraryRepository.isLocalSaved("${prefix}live:7", "tv")) add("live favourite")
            if (WatchedRepository.isWatched("${prefix}movie:12", "movie")) add("watched")
            if (XtreamLiveRecents.recents.value.any { it.contentId == "${prefix}live:7" }) add("recent")
        }
    }

    private val everything = listOf("library movie", "live favourite", "watched", "recent")

    @Test
    fun `hidden channels and saved items survive a provider domain change`() = runBlocking {
        val old = XtreamAccount(id = "http://old.example:8080|u", name = "P", baseUrl = "http://old.example:8080", username = "u", password = "p")
        XtreamRepository.installAccountsForTest(listOf(old))
        XtreamRepository.persistWriteForTest = { _, _ -> }
        XtreamRepository.verifyForTest = { Result.success(Unit) }
        val hiddenBefore = IptvIdentity.entityId(old.id, "BBC One HD", "bbc.uk")
        IptvOverlayStore.setChannel(profile, hiddenBefore, old.id, ChannelOverlay(hidden = true), 1L)
        seedSaved(old.id)
        assertEquals(everything, savedIds(old.id), "baseline")

        val done = CompletableDeferred<Boolean>()
        XtreamRepository.editFromForm(
            old.id,
            XtreamFormInput(
                serverUrl = "http://new-domain.example:8080", username = "u", password = "p", name = "P",
                epgUrl = null, dnsProvider = "system", autoRefreshHours = 24,
            ),
        ) { done.complete(it) }
        assertTrue(withTimeout(10_000) { done.await() })

        val edited = XtreamRepository.uiState.value.accounts.single()
        assertEquals("http://new-domain.example:8080", edited.baseUrl)
        // The guide computes a channel's overlay key from the CURRENT playlist id: it must still hit.
        val hiddenAfter = IptvIdentity.entityId(edited.id, "BBC One HD", "bbc.uk")
        assertEquals(hiddenBefore, hiddenAfter, "the channel's overlay key is unchanged")
        assertTrue(IptvOverlayStore.snapshot(profile).channels[hiddenAfter]?.hidden == true, "the hidden channel stays hidden")
        assertEquals(everything, savedIds(edited.id), "every saved item still resolves under the playlist id")
    }

    @Test
    fun `adopting a server key moves every saved item and the file copy and drops nothing`() = runBlocking {
        val local = XtreamAccount(
            id = "m3u_file|tv.m3u|1719000000000", name = "TV", baseUrl = "", username = "", password = "",
            sourceType = SOURCE_TYPE_M3U_FILE, fileName = "tv.m3u",
        )
        XtreamRepository.installAccountsForTest(listOf(local))
        seedSaved(local.id)
        val bytes = "#EXTM3U\n#EXTINF:-1,BBC\nhttp://x/1.ts\n"
        copyM3UFileToStorage(local.id, PickedM3UFile("tv.m3u") { bytes.encodeToByteArray() })

        val serverKey = "m3u_file|tv.m3u|synced"
        val pulled = listOf(PulledPlaylist(local.copy(id = serverKey), serverKeyed = true))
        val first = XtreamRepository.adoptFromPull(profile, pulled)

        assertEquals(listOf(PlaylistKeyAdoption.Rekey(local.id, serverKey)), first.rekeys)
        assertEquals(listOf(serverKey), XtreamRepository.uiState.value.accounts.map { it.id }, "the local playlist took the key")
        assertEquals(everything, savedIds(serverKey), "every saved item moved onto the key")
        assertEquals(emptyList(), savedIds(local.id), "nothing left behind under the old id")
        assertEquals(bytes, File(m3uFileStoragePath(serverKey)).readText(), "the file copy followed the id")
        assertFalse(File(m3uFileStoragePath(local.id)).exists(), "and was moved, not copied")

        val second = XtreamRepository.adoptFromPull(profile, pulled)
        assertEquals(emptyList(), second.rekeys, "the next pull re-keys nothing")
        assertEquals(everything, savedIds(serverKey))
    }
}
