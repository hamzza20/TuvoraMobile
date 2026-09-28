package com.nuvio.app.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals

// Apple TV must identify as "tvos" to the backend: its own settings blob (design decision 2026-09-28)
// and its own row in the account's device list (nuvio-backend 20260928130000_report_device_tvos.sql).
class TvosSyncPlatformTest {

    @Test
    fun `apple tv syncs settings under its own platform key`() {
        assertEquals("tvos", MOBILE_SYNC_PLATFORM)
    }

    @Test
    fun `the shared home catalog key is unchanged`() {
        assertEquals("home_catalog_shared", HOME_CATALOG_SHARED_SYNC_PLATFORM)
    }
}
