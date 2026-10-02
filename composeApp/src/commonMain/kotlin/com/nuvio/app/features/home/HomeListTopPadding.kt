package com.nuvio.app.features.home

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Top padding of the Home list. With the hero the hero runs edge to edge under the status bar (0).
 * Without it the first row starts at [defaultTopInset] (status bar + screen top) — enough on phones,
 * whose tab bar is at the bottom, but tablets draw the floating tab-bar pill OVER the top of the
 * content ([topNavOverlay], status bar included; 0 when there is none), so the first card must
 * start below the pill plus [gap] (UX38: the pill covered the "Follow your sports" card's title).
 */
internal fun homeListTopPadding(
    showHeroSlot: Boolean,
    defaultTopInset: Dp,
    topNavOverlay: Dp,
    gap: Dp = 8.dp,
): Dp {
    if (showHeroSlot) return 0.dp
    if (topNavOverlay > 0.dp && topNavOverlay + gap > defaultTopInset) return topNavOverlay + gap
    return defaultTopInset
}
