package com.tuvora.tvos.screens

import com.nuvio.app.features.library.LibraryListDialogMode
import com.nuvio.app.features.library.LibraryListDialogState
import com.nuvio.app.features.library.LibraryListManagementController
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.tracking.LibraryListPrivacy
import com.nuvio.app.features.tracking.TrackingLibraryTab
import com.nuvio.app.features.tracking.TrackingLibraryTabKind
import com.nuvio.app.features.tracking.TrackingProviderRegistry
import com.nuvio.app.features.tracking.providerId
import kotlinx.coroutines.flow.StateFlow

/** What Manage Lists can do for the current library source. */
data class TvListsInfo(
    val providerName: String,
    val lists: List<TrackingLibraryTab>,
    val privacyOptions: List<LibraryListPrivacy>,
    val supportsDescription: Boolean,
    val supportsReordering: Boolean,
)

/**
 * NuvioTV's Manage Lists (library/LibraryListDialogs.kt) over the phone's own
 * LibraryListManagementController: the tracking source's personal lists — create, edit, delete and,
 * where the provider allows it, move up / down. Null [info] means the source has no list management
 * (the Tuvora library, or a provider without it), and the screen hides the button.
 */
object TvLibraryLists {
    private val controller = LibraryListManagementController(LibraryRepository::listManagementContext, LibraryRepository::listManager)
    val dialog: StateFlow<LibraryListDialogState?> get() = controller.state

    fun info(): TvListsInfo? {
        val context = LibraryRepository.listManagementContext() ?: return null
        val provider = context.source.providerId?.let(TrackingProviderRegistry::libraryProvider) ?: return null
        val manager = provider.listManager ?: return null
        return TvListsInfo(
            providerName = provider.providerId.displayName,
            lists = provider.snapshot().tabs.filter { it.kind == TrackingLibraryTabKind.PERSONAL },
            privacyOptions = manager.capabilities.privacyOptions,
            supportsDescription = manager.capabilities.supportsDescription,
            supportsReordering = manager.capabilities.supportsReordering,
        )
    }

    fun open() = controller.open()
    fun dismiss() = controller.dismiss()
    fun create() = controller.create()
    fun edit(key: String) { info()?.lists?.firstOrNull { it.key == key }?.let(controller::edit) }
    fun requestDelete(key: String) { info()?.lists?.firstOrNull { it.key == key }?.let(controller::requestDelete) }
    fun setName(value: String) = controller.setName(value)
    fun setDescription(value: String) = controller.setDescription(value)
    fun setPrivacy(value: LibraryListPrivacy) = controller.setPrivacy(value)
    suspend fun submit() = controller.submit()

    /** NuvioTV "Move Up" / "Move Down"; false when the provider refused (the screen says so). */
    suspend fun move(key: String, up: Boolean): Boolean {
        val context = LibraryRepository.listManagementContext() ?: return false
        val keys = info()?.lists?.map { it.key } ?: return false
        val reordered = TvListOrder.move(keys, key, up) ?: return true
        return runCatching { LibraryRepository.listManager(context).reorderLists(reordered) }.isSuccess
    }

    /** The error line under the dialog (NuvioTV library_error_save_list_failed / delete_list_failed). */
    fun errorText(state: LibraryListDialogState): String? = state.error?.let {
        if (state.mode == LibraryListDialogMode.DELETE) "Failed to delete list" else "Failed to save list"
    }
}
