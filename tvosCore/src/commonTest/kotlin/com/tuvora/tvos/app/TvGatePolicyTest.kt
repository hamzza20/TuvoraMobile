package com.tuvora.tvos.app

import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.features.profiles.NuvioProfile
import kotlin.test.Test
import kotlin.test.assertEquals

// Apple TV's startup gate must make the same choices as the phone's AppGate.kt (upstream, Compose):
// which screen shows and which profile opens without asking. Cases mirror AppGate.kt:296-372.
class TvGatePolicyTest {
    private val alice = NuvioProfile(profileIndex = 1, name = "Alice")
    private val bob = NuvioProfile(profileIndex = 2, name = "Bob")
    private val kidsWithPin = NuvioProfile(profileIndex = 3, name = "Kids", pinEnabled = true)
    private val signedIn = AuthState.Authenticated(userId = "u1", email = "a@example.test", isAnonymous = false)

    private fun input(
        auth: AuthState = signedIn,
        profiles: List<NuvioProfile> = emptyList(),
        current: TvGateScreen = TvGateScreen.Loading,
        online: Boolean = true,
        rememberLast: Boolean = false,
        everSelected: Boolean = false,
        activeProfileIndex: Int = 1,
    ) = TvGateInput(auth, profiles, current, online, rememberLast, everSelected, activeProfileIndex)

    @Test
    fun `signed in with one unlocked profile opens it and syncs`() {
        assertEquals(TvGateDecision.SwitchTo(alice, sync = true), TvGatePolicy.decide(input(profiles = listOf(alice))))
    }

    @Test
    fun `a single profile with a PIN asks first`() {
        assertEquals(TvGateDecision.Show(TvGateScreen.ProfilePicker), TvGatePolicy.decide(input(profiles = listOf(kidsWithPin))))
    }

    @Test
    fun `several profiles show the picker`() {
        assertEquals(TvGateDecision.Show(TvGateScreen.ProfilePicker), TvGatePolicy.decide(input(profiles = listOf(alice, bob))))
    }

    @Test
    fun `remember last profile reopens it without asking`() {
        val decision = TvGatePolicy.decide(
            input(profiles = listOf(alice, bob), rememberLast = true, everSelected = true, activeProfileIndex = 2),
        )
        assertEquals(TvGateDecision.SwitchTo(bob, sync = true), decision)
    }

    @Test
    fun `remember last never skips a PIN`() {
        val decision = TvGatePolicy.decide(
            input(profiles = listOf(alice, kidsWithPin), rememberLast = true, everSelected = true, activeProfileIndex = 3),
        )
        assertEquals(TvGateDecision.Show(TvGateScreen.ProfilePicker), decision)
    }

    @Test
    fun `signed out with no cached profiles shows sign in`() {
        assertEquals(TvGateDecision.Show(TvGateScreen.SignIn), TvGatePolicy.decide(input(auth = AuthState.Unauthenticated)))
    }

    @Test
    fun `signed out and offline with cached profiles opens them without syncing`() {
        val decision = TvGatePolicy.decide(
            input(auth = AuthState.Unauthenticated, profiles = listOf(alice), current = TvGateScreen.SignIn, online = false),
        )
        assertEquals(TvGateDecision.SwitchTo(alice, sync = false), decision)
    }

    @Test
    fun `signed out and online on the sign in screen stays there`() {
        val decision = TvGatePolicy.decide(
            input(auth = AuthState.Unauthenticated, profiles = listOf(alice), current = TvGateScreen.SignIn, online = true),
        )
        assertEquals(TvGateDecision.Show(TvGateScreen.SignIn), decision)
    }

    @Test
    fun `still loading with cached profiles opens them without syncing`() {
        val decision = TvGatePolicy.decide(input(auth = AuthState.Loading, profiles = listOf(alice)))
        assertEquals(TvGateDecision.SwitchTo(alice, sync = false), decision)
    }

    @Test
    fun `still loading with nothing cached waits`() {
        assertEquals(TvGateDecision.Show(TvGateScreen.Loading), TvGatePolicy.decide(input(auth = AuthState.Loading)))
    }

    @Test
    fun `an authenticated update while already in the app changes nothing`() {
        assertEquals(TvGateDecision.Stay, TvGatePolicy.decide(input(profiles = listOf(alice), current = TvGateScreen.Main)))
    }

    @Test
    fun `a switch in progress is never interrupted`() {
        val decision = TvGatePolicy.decide(input(profiles = listOf(alice, bob), current = TvGateScreen.Switching))
        assertEquals(TvGateDecision.Stay, decision)
    }

    @Test
    fun `profiles arriving while the picker auto-skips open the only profile`() {
        val decision = TvGatePolicy.decide(input(profiles = listOf(alice), current = TvGateScreen.ProfilePicker))
        assertEquals(TvGateDecision.SwitchTo(alice, sync = true), decision)
    }

    @Test
    fun `a picker the user opened on purpose is not auto-skipped`() {
        val decision = TvGatePolicy.decide(
            input(profiles = listOf(alice), current = TvGateScreen.ProfilePicker).copy(userOpenedPicker = true),
        )
        assertEquals(TvGateDecision.Stay, decision)
    }
}
