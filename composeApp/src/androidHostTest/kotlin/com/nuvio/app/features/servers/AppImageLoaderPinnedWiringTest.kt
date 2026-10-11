package com.nuvio.app.features.servers

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import coil3.fetch.Fetcher
import com.nuvio.app.buildAppImageLoader
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/**
 * The factory itself is covered by [PinnedMediaE2ETest]; this pins that the loader the APP builds actually contains it.
 * Android's platform hook installs its own component registry, and a registry set twice replaces the first: on the real
 * emulator every pinned server's artwork failed with "Trust anchor not found" while the factory's own test stayed green.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppImageLoaderPinnedWiringTest {
    @Test
    fun `the app image loader fetches pinned server artwork before the normal network fetcher`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val loader = buildAppImageLoader(context)
        val factories: List<Fetcher.Factory<*>> = loader.components.fetcherFactories.map { it.first }
        val pinned = factories.indexOfFirst { it is PinnedServerImageFetcherFactory }
        assertTrue(pinned >= 0, "the pinned-artwork factory is missing from the app's image loader; factories=${factories.map { it::class.simpleName }}")
        val firstNetwork = factories.indexOfFirst { it.javaClass.name.contains("NetworkFetcher") }
        assertTrue(firstNetwork < 0 || pinned < firstNetwork, "the pinned factory must come before the normal network fetcher")
    }
}
