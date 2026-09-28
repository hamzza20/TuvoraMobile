package com.tuvora.tvoscore

import kotlinx.coroutines.test.runTest
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.addons_modal_success_message
import nuvio.composeapp.generated.resources.auth_sign_up_failed
import nuvio.composeapp.generated.resources.compose_auth_link_open_failed
import nuvio.composeapp.generated.resources.compose_settings_page_content_discovery
import nuvio.composeapp.generated.resources.cw_airs_in_days
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals

// The tvOS build's stand-in for Compose resources must return the same text the phone shows.
// In tests the bundle has no Tuvora.strings table, so the English default (from values/) is used.
class TvosStringResourcesTest {

    @Test
    fun `plain strings come from the shared English XML`() = runTest {
        assertEquals("Sign-up failed", getString(Res.string.auth_sign_up_failed))
    }

    @Test
    fun `positional arguments are filled in`() = runTest {
        assertEquals(
            "Torrentio was validated and added successfully.",
            getString(Res.string.addons_modal_success_message, "Torrentio"),
        )
    }

    @Test
    fun `xml entities are decoded`() = runTest {
        assertEquals("Content & Discovery", getString(Res.string.compose_settings_page_content_discovery))
    }

    @Test
    fun `plurals pick one or other`() = runTest {
        assertEquals("Airs in 1 Day", getPluralString(Res.plurals.cw_airs_in_days, 1, 1))
        assertEquals("Airs in 3 Days", getPluralString(Res.plurals.cw_airs_in_days, 3, 3))
    }

    @Test
    fun `apostrophes survive`() = runTest {
        assertEquals("Couldn't open the link.", getString(Res.string.compose_auth_link_open_failed))
    }
}
