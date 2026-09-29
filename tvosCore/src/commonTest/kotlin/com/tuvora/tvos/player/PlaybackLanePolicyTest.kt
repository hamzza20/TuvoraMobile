package com.tuvora.tvos.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackLanePolicyTest {

    private fun input(
        url: String,
        live: Boolean = false,
        setting: EngineSetting = EngineSetting.Auto,
        remembered: PlaybackLane? = null,
        externalSubtitles: Boolean = false,
        mimeType: String? = null,
    ) = LaneInput(url, live, setting, remembered, externalSubtitles, mimeType)

    @Test
    fun `live tv always plays on libmpv`() {
        assertEquals(PlaybackLane.Libmpv, PlaybackLanePolicy.initial(input("http://p/live/u/p/1.m3u8", live = true)))
        assertEquals(PlaybackLane.Libmpv, PlaybackLanePolicy.initial(input("http://p/live/u/p/1.ts", live = true, setting = EngineSetting.AvPlayer)))
    }

    @Test
    fun `vod in a container avplayer plays goes to avplayer`() {
        for (url in listOf("https://cdn/x/movie.mp4", "https://cdn/x/Movie.M4V?token=1", "https://cdn/x/a.mov", "https://cdn/hls/master.m3u8#t")) {
            assertEquals(PlaybackLane.AvPlayer, PlaybackLanePolicy.initial(input(url)), url)
        }
    }

    @Test
    fun `vod avplayer cannot play goes to libmpv`() {
        for (url in listOf("https://cdn/x/movie.mkv", "http://p/movie/u/p/9.avi", "http://p/series/u/p/9.ts", "https://cdn/a.webm")) {
            assertEquals(PlaybackLane.Libmpv, PlaybackLanePolicy.initial(input(url)), url)
        }
    }

    @Test
    fun `an unknown container goes to libmpv so a single-use link is never opened twice`() {
        assertEquals(PlaybackLane.Libmpv, PlaybackLanePolicy.initial(input("https://debrid.example/dl/ABC123")))
    }

    @Test
    fun `a known mime type counts like an extension`() {
        assertEquals(PlaybackLane.AvPlayer, PlaybackLanePolicy.initial(input("https://debrid.example/dl/ABC", mimeType = "video/mp4")))
        assertEquals(PlaybackLane.Libmpv, PlaybackLanePolicy.initial(input("https://debrid.example/dl/ABC", mimeType = "video/x-matroska")))
    }

    @Test
    fun `external subtitle files need libmpv`() {
        assertEquals(PlaybackLane.Libmpv, PlaybackLanePolicy.initial(input("https://cdn/x/movie.mp4", externalSubtitles = true)))
    }

    @Test
    fun `the user's engine setting wins for vod`() {
        assertEquals(PlaybackLane.Libmpv, PlaybackLanePolicy.initial(input("https://cdn/x/movie.mp4", setting = EngineSetting.Libmpv)))
        assertEquals(PlaybackLane.AvPlayer, PlaybackLanePolicy.initial(input("https://cdn/x/movie.mkv", setting = EngineSetting.AvPlayer)))
    }

    @Test
    fun `the lane that worked last time for this title is reused`() {
        assertEquals(PlaybackLane.Libmpv, PlaybackLanePolicy.initial(input("https://cdn/x/movie.mp4", remembered = PlaybackLane.Libmpv)))
    }

    @Test
    fun `an avplayer failure escalates to libmpv once`() {
        assertEquals(PlaybackLane.Libmpv, PlaybackLanePolicy.escalation(PlaybackLane.AvPlayer, alreadyEscalated = false))
        assertNull(PlaybackLanePolicy.escalation(PlaybackLane.AvPlayer, alreadyEscalated = true))
    }

    @Test
    fun `a libmpv failure has nowhere to escalate`() {
        assertNull(PlaybackLanePolicy.escalation(PlaybackLane.Libmpv, alreadyEscalated = false))
    }
}
