package com.tuvora.tvos.app

import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.build.AppBuildConfig
import com.nuvio.app.core.network.SupabaseConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Simulator verification only: signs a TEST account in on a LOCAL backend so a UI test can drive the real
 * screens without the QR sign-in (which needs a browser on another device). It does nothing unless this is
 * a debug build whose backend is on this machine (127.0.0.1 / localhost), so it can never sign anything in
 * against the hosted service, and a release build ignores it entirely.
 */
object TvLocalSmoke {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun backendIsLocal(): Boolean {
        val host = SupabaseConfig.URL.substringAfter("://").substringBefore('/').substringBefore(':')
        return host == "127.0.0.1" || host == "localhost"
    }

    fun signIn(email: String, password: String) {
        if (!AppBuildConfig.IS_DEBUG_BUILD || !backendIsLocal()) return
        scope.launch {
            // Wait for the session restore to settle, then sign in only if nobody is signed in.
            var waited = 0
            while (AuthRepository.state.value is AuthState.Loading && waited < 100) { kotlinx.coroutines.delay(100); waited++ }
            if (AuthRepository.state.value !is AuthState.Authenticated) AuthRepository.signInWithEmail(email, password)
        }
    }
}
