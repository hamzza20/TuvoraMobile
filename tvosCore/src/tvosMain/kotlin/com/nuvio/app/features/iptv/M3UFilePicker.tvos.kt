package com.nuvio.app.features.iptv

// tvOS has no document picker or Files app, so an M3U *file* playlist cannot be imported on Apple TV.
// Named gap: file playlists are added on phone or desktop; a synced file playlist whose local copy is
// absent takes the existing "file not on this device" path (fileExists == false).
actual fun pickM3UFile(onPicked: (PickedM3UFile?) -> Unit) = onPicked(null)
