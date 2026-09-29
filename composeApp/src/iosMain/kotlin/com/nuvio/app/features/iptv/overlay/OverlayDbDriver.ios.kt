package com.nuvio.app.features.iptv.overlay

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.nuvio.app.core.storage.AppleDataDirectory
import kotlinx.cinterop.ExperimentalForeignApi

internal actual object OverlayDbDriver {
    @OptIn(ExperimentalForeignApi::class)
    actual fun openConnection(): SQLiteConnection {
        val path = "${AppleDataDirectory.databases()}/iptv_overlay.db"
        return BundledSQLiteDriver().open(path)
    }
}
