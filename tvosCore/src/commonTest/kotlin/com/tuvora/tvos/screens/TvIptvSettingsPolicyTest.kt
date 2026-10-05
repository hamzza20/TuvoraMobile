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

    /** B119: the Apple TV picker had the same "All draws unchecked, one OK narrows to one" trap. */
    @Test
    fun `region rows under All read checked and OK removes only that one`() {
        assertEquals(true, p.regionChecked(emptySet(), "France"), "All checks every row")
        assertEquals(setOf("United Kingdom", "Other"), p.toggleRegion(emptySet(), regions, "France"), "All minus France")
        assertEquals(emptySet(), p.toggleRegion(setOf("United Kingdom", "Other"), regions, "France"), "back to All")
    }

    @Test
    fun `hidden list subtitle`() {
        assertEquals("Loading…", p.hiddenSubtitle(loading = true, count = 0))
        assertEquals("Select one to bring it back.", p.hiddenSubtitle(false, 2))
    }
}

class TvIptvContentPolicyTest {
    private val p = TvIptvContentPolicy

    @Test
    fun `content type rows read like NuvioTV`() {
        assertEquals(TvContentCount(TvContentCountKind.Hidden), p.count(enabled = false, selection = null, total = 10))
        assertEquals(TvContentCount(TvContentCountKind.All), p.count(true, null, 10))
        assertEquals(TvContentCount(TvContentCountKind.Fraction, 3, 10), p.count(true, listOf("a", "b", "c"), 10))
        assertEquals(TvContentCount(TvContentCountKind.Selected, 1, 0), p.count(true, listOf("a"), null))
    }

    @Test
    fun `a toggle from all materializes the list and never resurrects after deselect all`() {
        val all = listOf("a", "b", "c")
        assertEquals(listOf("a", "c"), p.toggle(current = null, allIds = all, categoryId = "b", checked = false))
        assertEquals(listOf("b"), p.toggle(current = emptyList(), allIds = all, categoryId = "b", checked = true))
        assertEquals(listOf("a", "b"), p.toggle(listOf("a", "b"), all, "b", checked = true))
        kotlin.test.assertTrue(p.isChecked(null, "z"))
        kotlin.test.assertFalse(p.isChecked(emptyList(), "a"))
    }

    @Test
    fun `offset options and labels`() {
        val options = p.correctionOptions()
        assertEquals(-720, options.first())
        assertEquals(840, options.last())
        assertEquals(53, options.size)
        assertEquals("+2h", p.offsetText(120))
        assertEquals("-1h 30m", p.offsetText(-90))
    }
}
