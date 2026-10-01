package com.tuvora.tvos.screens

import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.collection.Collection
import com.nuvio.app.features.collection.CollectionFolder
import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.home.HomeCatalogSettingsItem
import com.nuvio.app.features.home.MetaPreview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Beta report 2026-10-01 (TestFlight build 4): "none of the groups I created appear on the screen".
// Apple TV Home drew only the add-on catalog rows; collections never reached it. These pin the phone's
// placement rule (HomeScreen.kt keyedEnabledHomeItems) that Apple TV now follows.
class TvHomeCollectionsPolicyTest {
    private fun section(key: String, items: Int = 1) = HomeCatalogSection(
        key = key, title = key, subtitle = "", addonName = "Add-on",
        target = CatalogTarget.Addon(manifestUrl = "https://addon.test/manifest.json", contentType = "movie", catalogId = key),
        items = List(items) { MetaPreview(id = "$key-$it", type = "movie", name = "Title $it") },
    )

    private fun collection(id: String, folders: Int = 1, pinned: Boolean = false) = Collection(
        id = id, title = "Collection $id", pinToTop = pinned,
        folders = List(folders) { CollectionFolder(id = "$id-f$it", title = "Folder $it") },
    )

    private fun item(key: String, order: Int, enabled: Boolean = true) = HomeCatalogSettingsItem(
        key = key, defaultTitle = key, addonName = "", enabled = enabled, order = order,
        isCollection = key.startsWith("collection_"), collectionId = key.removePrefix("collection_").takeIf { key.startsWith("collection_") },
    )

    private fun keys(entries: List<TvHomeEntry>) = entries.map { it.key }

    @Test
    fun `collections show on Home in the profile catalog order`() {
        val entries = TvHomeCollectionsPolicy.entries(
            settingsItems = listOf(item("a", 0), item("collection_netflix", 1), item("b", 2), item("collection_prime", 3)),
            sections = listOf(section("a"), section("b")),
            collections = listOf(collection("prime"), collection("netflix")),
        )
        assertEquals(listOf("a", "collection_netflix", "b", "collection_prime"), keys(entries))
        assertEquals("netflix", entries[1].collection?.id)
        assertNull(entries[1].section)
    }

    @Test
    fun `a collection hidden in home catalog settings does not show`() {
        val entries = TvHomeCollectionsPolicy.entries(
            settingsItems = listOf(item("a", 0), item("collection_netflix", 1, enabled = false)),
            sections = listOf(section("a")),
            collections = listOf(collection("netflix")),
        )
        assertEquals(listOf("a"), keys(entries))
    }

    @Test
    fun `a collection with no folders does not show`() {
        val entries = TvHomeCollectionsPolicy.entries(
            settingsItems = listOf(item("collection_empty", 0), item("a", 1)),
            sections = listOf(section("a")),
            collections = listOf(collection("empty", folders = 0)),
        )
        assertEquals(listOf("a"), keys(entries))
    }

    @Test
    fun `a collection the settings have not synced yet shows last or first when pinned`() {
        val entries = TvHomeCollectionsPolicy.entries(
            settingsItems = listOf(item("a", 0), item("b", 1)),
            sections = listOf(section("a"), section("b")),
            collections = listOf(collection("late"), collection("pinned", pinned = true)),
        )
        assertEquals(listOf("collection_pinned", "a", "b", "collection_late"), keys(entries))
    }

    @Test
    fun `catalog rows keep showing when settings are not loaded`() {
        val entries = TvHomeCollectionsPolicy.entries(
            settingsItems = emptyList(),
            sections = listOf(section("a"), section("b")),
            collections = emptyList(),
        )
        assertEquals(listOf("a", "b"), keys(entries))
    }

    @Test
    fun `empty catalog rows and duplicate collections are dropped`() {
        val entries = TvHomeCollectionsPolicy.entries(
            settingsItems = listOf(item("a", 0), item("collection_x", 1)),
            sections = listOf(section("a", items = 0)),
            collections = listOf(collection("x"), collection("x")),
        )
        assertEquals(listOf("collection_x"), keys(entries))
    }

    @Test
    fun `folder cards use the cover image and never the focus gif`() {
        val folder = CollectionFolder(id = "f", title = "netflix", coverImageUrl = "  ", focusGifUrl = "https://x.test/a.gif", coverEmoji = null)
        assertNull(TvHomeCollectionsPolicy.coverImage(folder))
        assertEquals("NE", TvHomeCollectionsPolicy.placeholder(folder))
        assertEquals("🍿", TvHomeCollectionsPolicy.placeholder(folder.copy(coverEmoji = "🍿")))
        assertEquals("https://x.test/c.png", TvHomeCollectionsPolicy.coverImage(folder.copy(coverImageUrl = " https://x.test/c.png ")))
    }

    @Test
    fun `folder tiles keep their own shape`() {
        val folder = CollectionFolder(id = "f", title = "F")
        assertEquals(2.0 / 3.0, TvHomeCollectionsPolicy.aspectRatio(folder.copy(tileShape = "poster")))
        assertEquals(16.0 / 9.0, TvHomeCollectionsPolicy.aspectRatio(folder.copy(tileShape = "wide")))
        assertEquals(1.0, TvHomeCollectionsPolicy.aspectRatio(folder.copy(tileShape = "square")))
    }

    @Test
    fun `the modern hero prefers the folder backdrop then its cover then the collection`() {
        val c = Collection(id = "c", title = "C", backdropImageUrl = "https://x.test/coll.jpg")
        val f = CollectionFolder(id = "f", title = "Prime", coverEmoji = "📺")
        assertEquals("https://x.test/coll.jpg", TvHomeCollectionsPolicy.heroBackdrop(c, f))
        assertEquals("https://x.test/cover.jpg", TvHomeCollectionsPolicy.heroBackdrop(c, f.copy(coverImageUrl = "https://x.test/cover.jpg")))
        assertEquals("https://x.test/hero.jpg", TvHomeCollectionsPolicy.heroBackdrop(c, f.copy(coverImageUrl = "https://x.test/cover.jpg", heroBackdropUrl = "https://x.test/hero.jpg")))
        assertEquals("📺  Prime", TvHomeCollectionsPolicy.heroTitle(f))
        assertEquals("", TvHomeCollectionsPolicy.heroTitle(f.copy(hideTitle = true)))
    }
}
