package com.nuvio.app.features.home

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/** UX38: on iPad the floating tab-bar pill (drawn over the top) covered the first Home card's title. */
class HomeListTopPaddingTest {
    private val listDefault = 64.dp // status bar 24 + screenTop 40

    @Test
    fun theHeroRunsUnderTheStatusBar() {
        assertEquals(0.dp, homeListTopPadding(showHeroSlot = true, defaultTopInset = listDefault, topNavOverlay = 90.dp))
    }

    @Test
    fun phoneWithABottomTabBarKeepsTheDefault() {
        assertEquals(listDefault, homeListTopPadding(showHeroSlot = false, defaultTopInset = listDefault, topNavOverlay = 0.dp))
    }

    @Test
    fun tabletTopPillPushesTheFirstCardBelowThePillPlusAGap() {
        // pill box = status bar 24 + 10 top + 48 pill + 8 bottom = 90
        assertEquals(98.dp, homeListTopPadding(showHeroSlot = false, defaultTopInset = listDefault, topNavOverlay = 90.dp))
    }

    @Test
    fun aShortPillNeverPullsTheListAboveTheDefault() {
        assertEquals(listDefault, homeListTopPadding(showHeroSlot = false, defaultTopInset = listDefault, topNavOverlay = 20.dp))
    }
}
