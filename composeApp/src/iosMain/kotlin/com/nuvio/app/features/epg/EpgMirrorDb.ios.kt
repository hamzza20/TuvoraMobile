package com.nuvio.app.features.epg

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.nuvio.app.core.storage.AppleDataDirectory
import kotlinx.cinterop.ExperimentalForeignApi

internal actual object EpgMirrorDbDriver {
    @OptIn(ExperimentalForeignApi::class)
    actual fun openConnection(): SQLiteConnection {
        val path = "${AppleDataDirectory.databases()}/epg_mirror.db"
        // BundledSQLiteDriver compiles SQLite in — no system libsqlite3 dependency.
        return BundledSQLiteDriver().open(path)
    }
}
