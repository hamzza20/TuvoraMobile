package com.nuvio.app.features.profiles

// Apple TV has no photo library picker. Named gap: custom avatars are uploaded from phone, desktop or
// tuvora.co and arrive by sync; the preset avatar catalog still works on Apple TV.
actual fun pickAvatarImage(onPicked: (PickedAvatarImage?) -> Unit) = onPicked(null)
