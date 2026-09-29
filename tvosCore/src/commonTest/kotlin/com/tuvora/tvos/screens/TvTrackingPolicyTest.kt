package com.tuvora.tvos.screens

import com.nuvio.app.features.library.LibrarySourceMode
import com.nuvio.app.features.tracking.WatchProgressSource
import com.nuvio.app.features.trakt.MoreLikeThisSourcePreference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TvTrackingPolicyTest {
    @Test
    fun `trakt device token statuses map as nuviotv does`() {
        assertEquals(TvDevicePoll.Approved, TvTrackingPolicy.traktPoll(200, 5))
        assertEquals(TvDevicePoll.Pending, TvTrackingPolicy.traktPoll(400, 5))
        assertEquals(TvDevicePoll.Invalid, TvTrackingPolicy.traktPoll(404, 5))
        assertEquals(TvDevicePoll.AlreadyUsed, TvTrackingPolicy.traktPoll(409, 5))
        assertEquals(TvDevicePoll.Expired, TvTrackingPolicy.traktPoll(410, 5))
        assertEquals(TvDevicePoll.Denied, TvTrackingPolicy.traktPoll(418, 5))
        assertEquals(TvDevicePoll.Failed(500), TvTrackingPolicy.traktPoll(500, 5))
    }

    @Test
    fun `slow down adds five seconds and caps at a minute`() {
        assertEquals(TvDevicePoll.SlowDown(10), TvTrackingPolicy.traktPoll(429, 5))
        assertEquals(TvDevicePoll.SlowDown(60), TvTrackingPolicy.traktPoll(429, 58))
    }

    @Test
    fun `only pending and slow down keep the poll loop alive`() {
        assertTrue(TvDevicePoll.Pending.keepsPolling)
        assertTrue(TvDevicePoll.SlowDown(10).keepsPolling)
        for (end in listOf(TvDevicePoll.Approved, TvDevicePoll.Expired, TvDevicePoll.Denied, TvDevicePoll.AlreadyUsed, TvDevicePoll.Invalid, TvDevicePoll.Failed(0))) {
            assertFalse(end.keepsPolling, end.toString())
        }
    }

    @Test
    fun `simkl pin poll results`() {
        assertEquals(TvDevicePoll.Approved, TvTrackingPolicy.simklPoll("OK", "tok", null))
        assertEquals(TvDevicePoll.Pending, TvTrackingPolicy.simklPoll("KO", null, null))
        assertEquals(TvDevicePoll.Invalid, TvTrackingPolicy.simklPoll("OK", "tok", "dev"))
        assertEquals(TvDevicePoll.Failed(0), TvTrackingPolicy.simklPoll("OK", " ", null))
        assertTrue(TvTrackingPolicy.isValidSimklPin("AB12C"))
        assertFalse(TvTrackingPolicy.isValidSimklPin("../x"))
        assertFalse(TvTrackingPolicy.isValidSimklPin(null))
    }

    @Test
    fun `trakt qr carries the code`() {
        assertEquals("https://trakt.tv/activate/ABCD1234", TvTrackingPolicy.traktQrUrl("ABCD1234", "https://trakt.tv/activate"))
        assertEquals("https://trakt.tv/activate", TvTrackingPolicy.traktQrUrl(null, null))
    }

    @Test
    fun `durations format like nuviotv`() {
        assertEquals("7s", TvTrackingPolicy.formatDuration(7_000))
        assertEquals("4m 9s", TvTrackingPolicy.formatDuration(249_000))
        assertEquals("2h 5m", TvTrackingPolicy.formatDuration((2 * 3600 + 5 * 60) * 1000L))
        assertEquals("1d 2h", TvTrackingPolicy.formatDuration((26 * 3600) * 1000L))
        assertEquals("0s", TvTrackingPolicy.formatDuration(-5))
    }

    @Test
    fun `sources offer only connected services`() {
        assertEquals(listOf(LibrarySourceMode.LOCAL), TvTrackingPolicy.librarySources(false, false, false))
        assertEquals(listOf(LibrarySourceMode.LOCAL, LibrarySourceMode.SIMKL), TvTrackingPolicy.librarySources(false, true, false))
        assertEquals(
            listOf(WatchProgressSource.NUVIO_SYNC, WatchProgressSource.TRAKT, WatchProgressSource.MDBLIST),
            TvTrackingPolicy.watchProgressSources(true, false, true),
        )
        assertEquals(listOf(MoreLikeThisSourcePreference.TMDB, MoreLikeThisSourcePreference.TRAKT), TvTrackingPolicy.moreLikeThisSources(true, false))
    }

    @Test
    fun `more like this falls back to tmdb when its service is gone`() {
        assertEquals(MoreLikeThisSourcePreference.TMDB, TvTrackingPolicy.effectiveMoreLikeThis(MoreLikeThisSourcePreference.TRAKT, false, true))
        assertEquals(MoreLikeThisSourcePreference.SIMKL, TvTrackingPolicy.effectiveMoreLikeThis(MoreLikeThisSourcePreference.SIMKL, false, true))
    }

    @Test
    fun `end of flow messages use nuviotv wording`() {
        assertEquals("Authorization denied on Trakt.", TvTrackingPolicy.traktMessage(TvDevicePoll.Denied))
        assertEquals("Token polling failed (503)", TvTrackingPolicy.traktMessage(TvDevicePoll.Failed(503)))
        assertNull(TvTrackingPolicy.traktMessage(TvDevicePoll.Pending))
        assertEquals("Simkl code expired. Start again.", TvTrackingPolicy.simklMessage(TvDevicePoll.Expired))
    }

    @Test
    fun `saved sources fall back when their service is disconnected`() {
        assertEquals(LibrarySourceMode.LOCAL, TvTrackingPolicy.effectiveLibrary(LibrarySourceMode.TRAKT, false, true, false))
        assertEquals(LibrarySourceMode.SIMKL, TvTrackingPolicy.effectiveLibrary(LibrarySourceMode.SIMKL, false, true, false))
        assertEquals(WatchProgressSource.NUVIO_SYNC, TvTrackingPolicy.effectiveProgress(WatchProgressSource.MDBLIST, true, true, false))
        assertEquals(WatchProgressSource.TRAKT, TvTrackingPolicy.effectiveProgress(WatchProgressSource.TRAKT, true, false, false))
    }
}
