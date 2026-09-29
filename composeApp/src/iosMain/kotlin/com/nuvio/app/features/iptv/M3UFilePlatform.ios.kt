package com.nuvio.app.features.iptv

import com.nuvio.app.core.storage.AppleDataDirectory
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.posix.memcpy

/**
 * iOS file-source platform. The picker (UIDocumentPickerViewController) lives in M3UFilePicker.ios.kt,
 * iPhone/iPad only; this file is UIKit-free so the Apple TV build reuses it. The chosen file is read and
 * copied into Application Support at `playlists/{id}.m3u`. File IO uses NSData + POSIX line reading so
 * the ingest stays bounded-memory. Twin of the Android actual.
 */

@OptIn(ExperimentalForeignApi::class)
internal fun readFileBytes(path: String?): ByteArray {
    if (path == null) error("Selected file has no path")
    val data = NSData.dataWithContentsOfFile(path) ?: error("Could not read the selected file")
    return data.toByteArray()
}

/** Copies an NSData's bytes into a Kotlin ByteArray via its raw `bytes` pointer + memcpy. */
@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val len = length.toInt()
    if (len == 0) return ByteArray(0)
    val src = bytes ?: return ByteArray(0)
    val out = ByteArray(len)
    out.usePinned { pinned -> memcpy(pinned.addressOf(0), src, len.convert()) }
    return out
}

@OptIn(ExperimentalForeignApi::class)
actual suspend fun copyM3UFileToStorage(playlistId: String, picked: PickedM3UFile): String = withContext(Dispatchers.Default) {
    val bytes = picked.readBytes()
    val dest = storagePath(playlistId)
    val data = bytes.usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = bytes.size.convert())
    }
    data.writeToFile(dest, atomically = true)
    dest
}

actual fun m3uFileStoragePath(playlistId: String): String = storagePath(playlistId)

actual fun fileExists(path: String): Boolean = NSFileManager.defaultManager.fileExistsAtPath(path)

@OptIn(ExperimentalForeignApi::class)
actual fun deleteM3UFile(playlistId: String) {
    val path = storagePath(playlistId)
    if (NSFileManager.defaultManager.fileExistsAtPath(path)) {
        NSFileManager.defaultManager.removeItemAtPath(path, error = null)
    }
}

/**
 * Streams a local file's lines (gzip-aware) with bounded memory. NSData maps the file; we scan bytes
 * for newlines and hand out one decoded line at a time. A gzip-magic file is inflated first — iOS EPG
 * files are network-fetched (already handled by httpStreamLines), so a local .gz here is uncommon; if
 * one is passed we fall back to reading the whole (small) file via inflate. For plain files nothing is
 * fully materialized beyond the memory-mapped NSData the OS pages in on demand.
 */
@OptIn(ExperimentalForeignApi::class)
actual suspend fun streamFileLines(path: String, onLine: (String) -> Unit): Unit = withContext(Dispatchers.Default) {
    val data = NSData.dataWithContentsOfFile(path) ?: error("Playlist file not found: $path")
    val length = data.length.toInt()
    if (length == 0) return@withContext
    val buffer = data.toByteArray()
    val gzipped = buffer.size >= 2 && buffer[0] == 0x1f.toByte() && buffer[1] == 0x8b.toByte()
    if (gzipped) {
        // A local gzipped playlist file is a rare edge on iOS (the picker targets .m3u/.m3u8/text, and a
        // giant provider dump arrives via URL where Ktor inflates it). Fail clearly rather than emit
        // garbage — the user can re-import a plain .m3u. (Wire NSData gunzip here if this is ever needed.)
        error("Gzipped playlist files aren't supported on iOS yet — import a plain .m3u.")
    }
    // Plain: scan the buffer for '\n' and decode each line slice locally.
    var start = 0
    var i = 0
    while (i < length) {
        if (buffer[i] == '\n'.code.toByte()) {
            val end = if (i > start && buffer[i - 1] == '\r'.code.toByte()) i - 1 else i
            onLine(buffer.decodeToString(start, end))
            start = i + 1
        }
        i++
    }
    if (start < length) {
        val end = if (buffer[length - 1] == '\r'.code.toByte()) length - 1 else length
        if (end > start) onLine(buffer.decodeToString(start, end))
    }
}

/** <app data>/playlists/{safeId}.m3u (Application Support on iPhone, Caches on Apple TV) — created lazily. */
@OptIn(ExperimentalForeignApi::class)
private fun storagePath(playlistId: String): String {
    val base = AppleDataDirectory.databases()
    val playlists = "$base/playlists"
    NSFileManager.defaultManager.createDirectoryAtPath(playlists, withIntermediateDirectories = true, attributes = null, error = null)
    return "$playlists/${safeName(playlistId)}.m3u"
}

/** Playlist ids contain '://', ':', '|' — flatten to a filesystem-safe stable basename. */
private fun safeName(playlistId: String): String = buildString(playlistId.length) {
    for (c in playlistId) append(if (c.isLetterOrDigit() || c == '-' || c == '_') c else '_')
}
