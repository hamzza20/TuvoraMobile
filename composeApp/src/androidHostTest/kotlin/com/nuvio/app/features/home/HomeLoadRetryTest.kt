package com.nuvio.app.features.home

import android.app.Application
import android.content.Context
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.AddonStorage
import com.nuvio.app.features.collection.CollectionMobileSettingsRepository
import com.nuvio.app.features.collection.CollectionMobileSettingsStorage
import com.nuvio.app.features.collection.CollectionRepository
import com.nuvio.app.features.collection.CollectionStorage
import com.nuvio.app.features.profiles.ProfileRepository
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Regression: on a first launch that hit a bad moment, Home showed only Continue Watching until the
 * app was restarted — every add-on manifest and every catalog row was fetched exactly once and a
 * failure was simply dropped. Now what failed is fetched again on its own ([com.nuvio.app.features.addons.AddonLoadRetryPolicy]).
 *
 * The server routes by path and counts (not a FIFO queue): background fetches would steal queued
 * responses (see the MockWebServer FIFO note).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HomeLoadRetryTest {
    private val manifestHits = AtomicInteger()
    private val catalogHits = AtomicInteger()
    private var failManifestTimes = 0
    private var failCatalogTimes = 0
    private lateinit var server: MockWebServer

    private val manifest = """
        {"id":"test","name":"Catalogs","description":"","version":"1","resources":["catalog"],
         "types":["movie"],"catalogs":[{"type":"movie","id":"popular"}]}
    """.trimIndent()

    @BeforeTest
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        listOf("nuvio_addons", "nuvio_collections", "nuvio_collection_mobile_settings", "nuvio_home_catalog_settings").forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
        AddonStorage.initialize(context)
        CollectionStorage.initialize(context)
        CollectionMobileSettingsStorage.initialize(context)
        HomeCatalogSettingsStorage.initialize(context)
        AddonRepository.clearLocalState()
        CollectionRepository.clearLocalState()
        CollectionMobileSettingsRepository.clearLocalState()
        HomeCatalogSettingsRepository.clearLocalState()
        HomeCatalogSettingsRepository.setHeroEnabled(false)
        HomeRepository.clear()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.endsWith("/manifest.json") ->
                        if (manifestHits.incrementAndGet() <= failManifestTimes) MockResponse().setResponseCode(503)
                        else MockResponse().setBody(manifest)
                    path.contains("/catalog/movie/popular") ->
                        if (catalogHits.incrementAndGet() <= failCatalogTimes) MockResponse().setResponseCode(503)
                        else MockResponse().setBody("""{"metas":[{"id":"tt1","type":"movie","name":"One"}]}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @AfterTest
    fun tearDown() {
        HomeRepository.clear()
        AddonRepository.clearLocalState()
        HomeCatalogSettingsRepository.clearLocalState()
        server.shutdown()
    }

    private fun install() {
        AddonStorage.saveInstalledAddonUrls(ProfileRepository.activeProfileId, listOf(server.url("/manifest.json").toString()))
        AddonRepository.initialize()
    }

    @Test
    fun `an add-on whose manifest failed on launch is fetched again on its own`() = runBlocking {
        failManifestTimes = 1
        install()

        val loaded = withTimeout(15_000) {
            AddonRepository.uiState.first { state -> state.addons.any { it.manifest != null } }
        }

        assertNotNull(loaded.addons.single().manifest)
        assertEquals(2, manifestHits.get(), "one failure, one automatic retry, nothing more")
    }

    @Test
    fun `a catalog row that failed is fetched again on its own and nothing that loaded is re-fetched`() = runBlocking {
        failCatalogTimes = 1
        install()
        withTimeout(5_000) { AddonRepository.awaitManifestsLoaded() }
        val addons = AddonRepository.uiState.value.addons
        HomeCatalogSettingsRepository.syncCatalogs(addons)
        HomeRepository.refresh(addons)

        val state = withTimeout(15_000) {
            HomeRepository.uiState.first { it.sections.any { section -> section.items.isNotEmpty() } }
        }

        assertEquals("tt1", state.sections.single().items.single().id)
        assertEquals(0, state.failedRowCount)
        assertEquals(2, catalogHits.get(), "the failed row is fetched once more, not polled")
        assertEquals(1, manifestHits.get())
    }

    @Test
    fun `a visit retries what failed and makes no request when nothing did`() = runBlocking {
        install()
        withTimeout(5_000) { AddonRepository.awaitManifestsLoaded() }
        val addons = AddonRepository.uiState.value.addons
        HomeCatalogSettingsRepository.syncCatalogs(addons)
        HomeRepository.refresh(addons)
        withTimeout(5_000) { HomeRepository.uiState.first { it.sections.isNotEmpty() && !it.isLoading } }
        val before = server.requestCount

        HomeRepository.retryFailed()
        AddonRepository.retryFailedManifests()
        Thread.sleep(300)

        assertEquals(before, server.requestCount, "an idle Home visit must not touch the network")
    }
}
