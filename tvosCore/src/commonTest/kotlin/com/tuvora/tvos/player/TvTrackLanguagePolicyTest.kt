package com.tuvora.tvos.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TvTrackLanguagePolicyTest {
    private val tracks = listOf(TvTrack(3, "English", "en", false), TvTrack(4, "Español", "es", false), TvTrack(5, "Français", "fr-FR", false))

    @Test
    fun `the first preferred language that exists wins`() {
        assertEquals(4, TvTrackLanguagePolicy.subtitleChoice(tracks, listOf("de", "es", "en"), preferenceIsNone = false))
    }

    @Test
    fun `a regional track matches a base language`() {
        assertEquals(5, TvTrackLanguagePolicy.subtitleChoice(tracks, listOf("fr"), preferenceIsNone = false))
    }

    @Test
    fun `preference none turns subtitles off`() {
        assertEquals(TvTrackLanguagePolicy.OFF, TvTrackLanguagePolicy.subtitleChoice(tracks, emptyList(), preferenceIsNone = true))
    }

    @Test
    fun `no match leaves the engine's choice alone`() {
        assertNull(TvTrackLanguagePolicy.subtitleChoice(tracks, listOf("ja"), preferenceIsNone = false))
    }
}
