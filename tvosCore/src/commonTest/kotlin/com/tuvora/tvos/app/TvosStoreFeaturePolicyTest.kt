package com.tuvora.tvos.app

import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.features.debrid.DebridProviders
import com.nuvio.app.features.debrid.DebridSettingsRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The Apple TV app shares the App Store record with iOS (Universal Purchase on com.tuvora.media) and
// goes through the same App Review, so tvosCore compiles composeApp's iosAppStore policy. Debrid is a
// torrent-cache service (guideline 5.2.3): it must be compiled out here, never a remote flag (2.3.1).
// The Swift UI reads AppFeaturePolicy.shared.debridEnabled to hide Connected Services and Library › Cloud.
class TvosStoreFeaturePolicyTest {

    @Test
    fun `apple tv ships the store posture with debrid addons plugins and p2p off`() {
        assertFalse(AppFeaturePolicy.debridEnabled, "debrid must be off in the Apple TV (App Store) build")
        assertFalse(AppFeaturePolicy.addonsEnabled)
        assertFalse(AppFeaturePolicy.pluginsEnabled)
        assertFalse(AppFeaturePolicy.p2pEnabled)
    }

    @Test
    fun `published debrid settings never resolve links or open the cloud library on apple tv`() {
        val published = DebridSettingsRepository.snapshot()
        assertFalse(published.featureAvailable, "DebridSettingsRepository must publish featureAvailable = false")
        assertFalse(published.linkResolvingEnabled)
        assertFalse(published.canResolvePlayableLinks)
        assertFalse(published.cloudLibraryActive)
        assertFalse(published.canUseCloudLibrary)
    }

    @Test
    fun `a synced key stays saved but stays dark on apple tv`() {
        // What a store-build device sees when the user's keys arrive from another device or tuvora.co.
        val synced = DebridSettingsRepository.snapshot().copy(
            enabled = true,
            cloudLibraryEnabled = true,
            providerApiKeys = mapOf(DebridProviders.TORBOX_ID to "tb_key"),
        )
        assertEquals("tb_key", synced.torboxApiKey)
        assertTrue(synced.hasAnyApiKey)
        assertFalse(synced.canResolvePlayableLinks)
        assertFalse(synced.canUseCloudLibrary)
    }
}
