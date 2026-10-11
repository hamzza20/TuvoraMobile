package com.tuvora.tvos.screens

import com.nuvio.app.features.servers.ServerFailure
import kotlin.test.Test
import kotlin.test.assertEquals

class TvServerPoliciesTest {
    @Test
    fun `status badge precedence`() {
        assertEquals(TvServerStatus.DISABLED, TvServerStatusPolicy.of(false, true, null), "off wins")
        assertEquals(TvServerStatus.NEEDS_SIGN_IN, TvServerStatusPolicy.of(true, false, ServerFailure.AUTH_REQUIRED), "no token here")
        assertEquals(TvServerStatus.SIGN_IN_AGAIN, TvServerStatusPolicy.of(true, true, ServerFailure.AUTH_REQUIRED), "refused token")
        assertEquals(TvServerStatus.SIGN_IN_AGAIN, TvServerStatusPolicy.of(true, true, ServerFailure.CERTIFICATE_CHANGED), "changed certificate")
        assertEquals(TvServerStatus.OFFLINE, TvServerStatusPolicy.of(true, true, ServerFailure.UNREACHABLE), "unreachable")
        assertEquals(TvServerStatus.SIGNED_IN, TvServerStatusPolicy.of(true, true, null), "healthy")
    }

    @Test
    fun `subtitle lists product user and host`() {
        assertEquals("Jellyfin · viewer · nas.local:8096", TvServerStatusPolicy.subtitle("Jellyfin", "viewer", "http://nas.local:8096/jf"), "full")
        assertEquals("Emby · nas.local", TvServerStatusPolicy.subtitle("Emby", "", "https://nas.local"), "no user")
    }

    @Test
    fun `add flow errors`() {
        assertEquals(TvAddError.INVALID_ADDRESS, TvServerAddErrorPolicy.of(ServerFailure.NOT_FOUND, duringSignIn = false), "bad address")
        assertEquals(TvAddError.NOT_A_MEDIA_SERVER, TvServerAddErrorPolicy.of(ServerFailure.UNSUPPORTED, duringSignIn = false), "wrong product")
        assertEquals(TvAddError.UNREACHABLE, TvServerAddErrorPolicy.of(ServerFailure.UNREACHABLE, duringSignIn = true), "down")
        assertEquals(TvAddError.WRONG_CREDENTIALS, TvServerAddErrorPolicy.of(ServerFailure.AUTH_REQUIRED, duringSignIn = true), "wrong password")
        assertEquals(TvAddError.SIGN_IN_FAILED, TvServerAddErrorPolicy.of(ServerFailure.AUTH_REQUIRED, duringSignIn = false), "auth outside sign-in")
    }
}
