package com.tuvora.tvos.screens

import kotlin.test.Test
import kotlin.test.assertEquals

class TvIptvSettingsPolicyTest {
    private val p = TvIptvSettingsPolicy
    private val regions = listOf(
        TvEpgRegion("United Kingdom", "🇬🇧", 900), TvEpgRegion("France", "🇫🇷", 400),
        TvEpgRegion("Other", "", 50),
    )

    @Test
    fun `hidden item labels name the kind`() {
        assertEquals("Channel", p.kindLabel(isChannel = true, contentType = "live"))
        assertEquals("Channel group", p.kindLabel(false, "live"))
        assertEquals("Movie group", p.kindLabel(false, "movies"))
        assertEquals("Series group", p.kindLabel(false, "series"))
    }

    @Test
    fun `region summary uses flags then counts`() {
        assertEquals("—", p.regionSummary(emptySet(), emptyList()))
        assertEquals("All (3)", p.regionSummary(emptySet(), regions))
        assertEquals("🇬🇧 🇫🇷", p.regionSummary(setOf("United Kingdom", "France"), regions))
        assertEquals("1 selected", p.regionSummary(setOf("Other"), regions))
        assertEquals("2 of 3 selected", p.regionDialogSubtitle(2, 3))
    }

    @Test
    fun `hidden list subtitle`() {
        assertEquals("Loading…", p.hiddenSubtitle(loading = true, count = 0))
        assertEquals("Select one to bring it back.", p.hiddenSubtitle(false, 2))
    }
}
