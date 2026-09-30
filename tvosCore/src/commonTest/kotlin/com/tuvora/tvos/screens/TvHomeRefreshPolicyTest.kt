package com.tuvora.tvos.screens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

// Bug 2026-09-29: after a profile switch the home catalogs vanished. The switch clears HomeRepository,
// and Apple TV refreshed only when the add-on list changed - same add-ons, no reload. The phone keys
// the refresh on its app-content generation plus the add-on signature (MainAppContent.kt:419).
class TvHomeRefreshPolicyTest {
    @Test
    fun `a new profile session reloads the catalogs even with the same add-ons`() {
        assertNotEquals(TvHomeRefreshPolicy.key(generation = 1, addons = emptyList()),
                        TvHomeRefreshPolicy.key(generation = 2, addons = emptyList()))
    }

    @Test
    fun `the same session and add-ons do not reload`() {
        assertEquals(TvHomeRefreshPolicy.key(generation = 3, addons = emptyList()),
                     TvHomeRefreshPolicy.key(generation = 3, addons = emptyList()))
    }
}
