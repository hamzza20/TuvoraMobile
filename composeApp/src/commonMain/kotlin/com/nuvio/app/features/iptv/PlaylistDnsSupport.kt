package com.nuvio.app.features.iptv

import com.nuvio.app.isIos

/**
 * Whether this app applies a playlist's per-playlist DNS-over-HTTPS choice. Only the Android
 * transport has a per-app DNS hook (OkHttp); Ktor Darwin / URLSession has none. The form explains
 * the limitation only where it applies (UX83: Android users were shown "iOS ignores this setting").
 */
internal val perPlaylistDnsSupported: Boolean get() = !isIos
