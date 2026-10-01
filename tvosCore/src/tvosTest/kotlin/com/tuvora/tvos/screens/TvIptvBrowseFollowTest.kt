package com.tuvora.tvos.screens

import com.nuvio.app.features.iptv.XtreamAccount
import com.nuvio.app.features.iptv.XtreamHubRepository
import com.nuvio.app.features.iptv.XtreamRepository
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The Apple TV IPTV tab follows the playlist store while it is on screen (the phone's B-fix,
 * XtreamHubFollowAccountsTest, through the tvOS facade the SwiftUI hub runs in its `.task`).
 *
 * The SwiftUI hub used to call [TvIptvBrowse.open] once per appearance: a playlist that arrived by
 * sync while the tab was visible never showed (the empty state even tells the viewer to add one on
 * their phone, then never noticed it), and a removal/edit that reset the hub left a spinner forever.
 */
class TvIptvBrowseFollowTest {

    private fun account(tag: String) = XtreamAccount(
        id = "http://$tag.invalid:8080|u",
        name = tag,
        baseUrl = "http://$tag.invalid:8080",
        username = "u",
        password = "p",
        // Disabled-then-enabled is not the point here; keep it enabled so the hub lists it.
    )

    @AfterTest
    fun tearDown() {
        XtreamRepository.installAccountsForTest(emptyList())
        XtreamHubRepository.resetForProfile()
    }

    @Test
    fun aPlaylistThatArrivesWhileTheTabIsOpenShowsUp(): Unit = runBlocking {
        XtreamRepository.installAccountsForTest(emptyList())
        XtreamHubRepository.resetForProfile()
        val follower = launch(Dispatchers.Default) { TvIptvBrowse.followPlaylists() }
        try {
            assertNotNull(
                withTimeoutOrNull(5_000) { TvIptvBrowse.state.first { it.accountsLoaded && it.accounts.isEmpty() } },
                "the hub never loaded its (empty) playlist list",
            )
            // A sync pull / the add flow appends to the store's in-memory list.
            val added = account("added")
            XtreamRepository.stageEditForTest(listOf(added))
            assertNotNull(
                withTimeoutOrNull(5_000) { TvIptvBrowse.state.first { st -> st.accounts.any { it.id == added.id } } },
                "the new playlist never reached the open IPTV tab (stuck on \"No playlists yet\")",
            )
        } finally {
            follower.cancel()
        }
    }

    @Test
    fun aRemovalWhileTheTabIsOpenReloadsInsteadOfSpinning(): Unit = runBlocking {
        val keep = account("keep")
        val gone = account("gone")
        XtreamRepository.installAccountsForTest(listOf(keep, gone))
        XtreamHubRepository.resetForProfile()
        val follower = launch(Dispatchers.Default) { TvIptvBrowse.followPlaylists() }
        try {
            assertNotNull(
                withTimeoutOrNull(5_000) { TvIptvBrowse.state.first { it.accounts.size == 2 } },
                "the hub never loaded both playlists",
            )
            // XtreamRepository.remove / a pulled deletion: the list shrinks, then the hub state is wiped.
            XtreamRepository.stageEditForTest(listOf(keep))
            XtreamHubRepository.resetForProfile()
            assertNotNull(
                withTimeoutOrNull(5_000) {
                    TvIptvBrowse.state.first { st -> st.accountsLoaded && st.accounts.map { it.id } == listOf(keep.id) }
                },
                "after a removal the hub stayed wiped (endless spinner) or kept the removed playlist",
            )
        } finally {
            follower.cancel()
        }
    }
}
