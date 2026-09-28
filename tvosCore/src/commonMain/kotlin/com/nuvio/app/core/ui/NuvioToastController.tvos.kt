// Apple TV implementation of the toast port. On phone and desktop this object lives inside the Compose
// UI file core/ui/Components.kt (upstream-owned), which tvOS cannot compile; logic calls
// NuvioToastController.show(...) either way, and the SwiftUI app renders `currentToast`.
package com.nuvio.app.core.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NuvioToastMessage(
    val id: Long,
    val message: String,
    val durationMillis: Long,
)

object NuvioToastController {
    private val _currentToast = MutableStateFlow<NuvioToastMessage?>(null)
    val currentToast = _currentToast.asStateFlow()
    private var nextToastId = 0L

    fun show(message: String, durationMillis: Long = 2500L) {
        nextToastId += 1L
        _currentToast.value = NuvioToastMessage(id = nextToastId, message = message, durationMillis = durationMillis)
    }

    fun dismiss(id: Long? = null) {
        val activeToast = _currentToast.value ?: return
        if (id == null || activeToast.id == id) _currentToast.value = null
    }
}
