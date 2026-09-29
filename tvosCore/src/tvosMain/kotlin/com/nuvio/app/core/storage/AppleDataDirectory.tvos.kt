package com.nuvio.app.core.storage

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

/**
 * Apple TV's version of AppleDataDirectory.ios.kt. tvOS lets an app write only to Caches (Application
 * Support and Documents fail on hardware; the simulator doesn't enforce it), and may purge Caches
 * while the app isn't running. Everything here is therefore rebuildable: databases re-ingest, queues
 * restart. What must survive lives in the durable core (Keychain + budgeted user defaults).
 */
internal object AppleDataDirectory {
    fun databases(): String = directory("TuvoraData")
    fun documents(): String = directory("TuvoraData/Documents")

    @OptIn(ExperimentalForeignApi::class)
    private fun directory(child: String): String {
        val caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).firstOrNull() as? String ?: "."
        val path = "$caches/$child"
        NSFileManager.defaultManager.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = null, error = null)
        return path
    }
}
