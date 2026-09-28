package com.nuvio.app.features.iptv

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UniformTypeIdentifiers.UTType
import platform.UniformTypeIdentifiers.UTTypePlainText
import platform.darwin.NSObject

// The M3U document picker, split from M3UFilePlatform.ios.kt so the file IO there compiles for tvOS,
// which has no document picker (the Apple TV build supplies its own pickM3UFile).

@OptIn(ExperimentalForeignApi::class)
actual fun pickM3UFile(onPicked: (PickedM3UFile?) -> Unit) {
    val root = UIApplication.sharedApplication.keyWindow?.rootViewController
    if (root == null) {
        onPicked(null)
        return
    }
    // Accept M3U UTIs when available (declared by many players) plus plain text as the safe fallback.
    val types = buildList {
        UTType.typeWithFilenameExtension("m3u")?.let { add(it) }
        UTType.typeWithFilenameExtension("m3u8")?.let { add(it) }
        add(UTTypePlainText)
    }
    val picker = UIDocumentPickerViewController(forOpeningContentTypes = types, asCopy = true)
    val delegate = M3UPickerDelegate(onPicked)
    // Retain the delegate for the picker's lifetime (the picker holds only a weak ref).
    retainedDelegate = delegate
    picker.delegate = delegate
    root.presentViewController(picker, animated = true, completion = null)
}

// Strong ref so the delegate outlives the suspend/callback; cleared when the pick resolves.
private var retainedDelegate: M3UPickerDelegate? = null

private class M3UPickerDelegate(
    private val onPicked: (PickedM3UFile?) -> Unit,
) : NSObject(), UIDocumentPickerDelegateProtocol {

    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        retainedDelegate = null
        val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL
        if (url == null) {
            onPicked(null)
            return
        }
        val name = url.lastPathComponent ?: "playlist.m3u"
        val path = url.path
        onPicked(PickedM3UFile(fileName = name, readBytes = {
            // asCopy = true delivers a temp copy in our sandbox, so a plain read works (no security scope).
            withContext(Dispatchers.Default) { readFileBytes(path) }
        }))
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        retainedDelegate = null
        onPicked(null)
    }
}
