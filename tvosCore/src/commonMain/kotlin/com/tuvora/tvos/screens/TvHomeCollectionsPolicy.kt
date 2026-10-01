package com.tuvora.tvos.screens

import com.nuvio.app.features.collection.Collection
import com.nuvio.app.features.collection.CollectionFolder
import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.home.HomeCatalogSettingsItem
import com.nuvio.app.features.home.PosterShape

/** One Home row on Apple TV: an add-on catalog or a user's collection (exactly one is set). */
data class TvHomeEntry(
    val key: String,
    val section: HomeCatalogSection? = null,
    val collection: Collection? = null,
)

/**
 * Where a profile's collections sit among the Home catalog rows, and whether they show - the phone's
 * HomeScreen.kt rule (keyedEnabledHomeItems: one list of catalogs and "collection_<id>" entries, in the
 * per-profile Home catalog settings order, disabled ones skipped, collections with no folders hidden),
 * which NuvioTV's home pipeline follows too.
 *
 * Apple TV differences, both for rows the settings have not caught up with yet (the phone syncs them on
 * the next frame, so they never show there for long):
 * - a catalog row with no settings entry keeps its HomeRepository position, after the ordered rows
 *   (the rows Apple TV has always shown);
 * - a collection with no settings entry shows with the defaults syncCollections would give it: enabled,
 *   after everything, or first when it is pinned to the top.
 */
object TvHomeCollectionsPolicy {
    fun collectionKey(collection: Collection): String = "collection_${collection.id}"

    fun entries(
        settingsItems: List<HomeCatalogSettingsItem>,
        sections: List<HomeCatalogSection>,
        collections: List<Collection>,
    ): List<TvHomeEntry> {
        val visibleCollections = collections.filter { it.folders.isNotEmpty() }.distinctBy { it.id }
        val collectionsByKey = visibleCollections.associateBy(::collectionKey)
        val sectionsByKey = sections.associateBy { it.key }
        val knownKeys = settingsItems.mapTo(HashSet()) { it.key }
        val newCollections = visibleCollections.filter { collectionKey(it) !in knownKeys }

        val result = ArrayList<TvHomeEntry>()
        val emitted = HashSet<String>()
        fun emit(entry: TvHomeEntry) { if (emitted.add(entry.key)) result += entry }

        newCollections.filter { it.pinToTop }.forEach { emit(TvHomeEntry(collectionKey(it), collection = it)) }
        settingsItems.sortedBy { it.order }.filter { it.enabled }.forEach { item ->
            if (item.isCollection || item.key.startsWith("collection_")) {
                collectionsByKey[item.key]?.let { emit(TvHomeEntry(item.key, collection = it)) }
            } else {
                sectionsByKey[item.key]?.takeIf { it.items.isNotEmpty() }?.let { emit(TvHomeEntry(item.key, section = it)) }
            }
        }
        sections.filter { it.key !in knownKeys && it.items.isNotEmpty() }.forEach { emit(TvHomeEntry(it.key, section = it)) }
        newCollections.filterNot { it.pinToTop }.forEach { emit(TvHomeEntry(collectionKey(it), collection = it)) }
        return result
    }

    /** NuvioTV collectionFolderCardImageUrl: the cover only; a focus GIF is never a static poster. */
    fun coverImage(folder: CollectionFolder): String? = folder.coverImageUrl?.trim()?.takeIf { it.isNotEmpty() }

    /** What a folder card shows when it has no cover: its emoji, else the first two letters (NuvioTV FolderCard). */
    fun placeholder(folder: CollectionFolder): String =
        folder.coverEmoji?.trim()?.takeIf { it.isNotEmpty() } ?: folder.title.take(2).uppercase()

    /** Width / height of a folder tile (NuvioTV FolderCard: poster 2:3, landscape 16:9, square 1:1). */
    fun aspectRatio(folder: CollectionFolder): Double = when (folder.posterShape) {
        PosterShape.Poster -> 2.0 / 3.0
        PosterShape.Landscape -> 16.0 / 9.0
        PosterShape.Square -> 1.0
    }

    /** Modern hero for a focused folder (ModernHomeModels.buildCollectionFolderItem). */
    fun heroBackdrop(collection: Collection, folder: CollectionFolder): String? =
        listOf(folder.heroBackdropUrl, folder.coverImageUrl, collection.backdropImageUrl)
            .firstOrNull { !it.isNullOrBlank() }?.trim()

    fun heroTitle(folder: CollectionFolder): String = when {
        folder.hideTitle -> ""
        !folder.coverEmoji.isNullOrBlank() -> "${folder.coverEmoji}  ${folder.title}"
        else -> folder.title
    }
}
