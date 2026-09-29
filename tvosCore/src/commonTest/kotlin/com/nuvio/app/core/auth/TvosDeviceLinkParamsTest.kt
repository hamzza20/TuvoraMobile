package com.nuvio.app.core.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.jsonPrimitive

// Live sign-in test 2026-09-28: the Apple TV's tv_login_sessions row was stored as device_type "mobile".
class TvosDeviceLinkParamsTest {

    @Test
    fun `apple tv sign-in identifies as tvos`() {
        val params = deviceLinkStartParams("nonce", "https://tuvora.co/link", "Apple TV")
        assertEquals("tvos", params.getValue("p_device_type").jsonPrimitive.content)
    }
}
