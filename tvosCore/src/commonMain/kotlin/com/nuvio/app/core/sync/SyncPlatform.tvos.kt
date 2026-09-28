// Apple TV's copy of the upstream core/sync/SyncPlatform.kt (excluded from :tvosCore). The constant
// keeps its upstream name so the shared sync code compiles unchanged; on Apple TV its value is "tvos":
// the app keeps its own settings blob and is listed as Apple TV (nuvio-backend 20260928130000).
package com.nuvio.app.core.sync

internal const val MOBILE_SYNC_PLATFORM = "tvos"
internal const val HOME_CATALOG_SHARED_SYNC_PLATFORM = "home_catalog_shared"
