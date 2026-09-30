package com.tuvora.tvos.screens

import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.home.buildAddonCatalogRefreshSignature

/**
 * When Home reloads its add-on catalogs: on every new profile session (a switch clears
 * HomeRepository) and whenever the add-on set changes - the phone's
 * `LaunchedEffect(appContentGeneration, homeCatalogRefreshKey)` (MainAppContent.kt:419).
 */
object TvHomeRefreshPolicy {
    data class Key(val generation: Int, val addonSignature: List<String>)

    fun key(generation: Int, addons: List<ManagedAddon>): Key =
        Key(generation, buildAddonCatalogRefreshSignature(addons))
}
