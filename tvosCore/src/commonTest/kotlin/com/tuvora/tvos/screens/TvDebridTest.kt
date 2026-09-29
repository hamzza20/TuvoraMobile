package com.tuvora.tvos.screens

import com.nuvio.app.features.debrid.DebridStreamFormatterDefaults
import com.nuvio.app.features.debrid.DebridStreamPreferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvDebridTest {
    @Test
    fun `size range labels match nuviotv`() {
        assertEquals("Any", TvDebrid.sizeRangeLabel(0, 0))
        assertEquals("Up to 10GB", TvDebrid.sizeRangeLabel(0, 10))
        assertEquals("5GB+", TvDebrid.sizeRangeLabel(5, 0))
        assertEquals("5-20GB", TvDebrid.sizeRangeLabel(5, 20))
    }

    @Test
    fun `limit labels treat zero as no limit`() {
        assertEquals("All streams", TvDebrid.limitLabel(0))
        assertEquals("10 streams", TvDebrid.limitLabel(10))
        assertEquals(listOf(0, 5, 10, 20, 50), TvDebrid.limitOptions)
    }

    @Test
    fun `size range options start with any`() {
        assertEquals(TvSizeRange(0, 0), TvDebrid.sizeRangeOptions.first())
        assertEquals(6, TvDebrid.sizeRangeOptions.size)
    }

    @Test
    fun `limit and range edits touch only their own fields`() {
        val base = DebridStreamPreferences(maxResults = 20, maxPerQuality = 3)
        val perRes = TvDebrid.withMaxPerResolution(base, 5)
        assertEquals(5, perRes.maxPerResolution)
        assertEquals(20, perRes.maxResults)
        assertEquals(3, perRes.maxPerQuality)
        val ranged = TvDebrid.withSizeRange(perRes, TvSizeRange(5, 20))
        assertEquals(5, ranged.sizeMinGb)
        assertEquals(20, ranged.sizeMaxGb)
        assertEquals(base.sortCriteria, ranged.sortCriteria)
        assertEquals(0, TvDebrid.withMaxPerQuality(base, -4).maxPerQuality)
    }

    @Test
    fun `blank or stock templates read as default`() {
        assertTrue(TvDebrid.isDefaultNameTemplate(""))
        assertTrue(TvDebrid.isDefaultNameTemplate(DebridStreamFormatterDefaults.NAME_TEMPLATE))
        assertFalse(TvDebrid.isDefaultNameTemplate("{stream.resolution}"))
        assertTrue(TvDebrid.isDefaultDescriptionTemplate("  "))
    }
}
