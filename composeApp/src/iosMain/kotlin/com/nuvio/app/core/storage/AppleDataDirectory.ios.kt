package com.nuvio.app.core.storage

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

/**
 * Where this build keeps app-owned files. iPhone: databases and imported playlists in Application
 * Support, journals and queues in Documents. Apple TV compiles its own version of this file
 * (tvosCore) because tvOS only lets apps write to Caches.
 */
internal object AppleDataDirectory {
    fun databases(): String = directory(NSApplicationSupportDirectory)
    fun documents(): String = directory(NSDocumentDirectory)

    @OptIn(ExperimentalForeignApi::class)
    private fun directory(kind: ULong): String {
        val base = NSSearchPathForDirectoriesInDomains(kind, NSUserDomainMask, true).firstOrNull() as? String ?: "."
        NSFileManager.defaultManager.createDirectoryAtPath(base, withIntermediateDirectories = true, attributes = null, error = null)
        return base
    }
}
