package com.nuvio.app.features.iptv

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The setup-code flow's decisions, on fakes: no network, no UI, no Compose. */
class SetupCodeControllerTest {

    private class FakeApi : ProviderSetupApi {
        var previewCalls = 0
        var redeemCalls = 0
        val redeemed = mutableListOf<Pair<String, Int>>()
        var previewResult: SetupCodeOutcome = SetupCodeOutcome.Ready(
            SetupPreview("Acme TV", ProviderSupport(telegram = "acme_tv"), "Gold", listOf(SetupPreviewPlaylist("Live", "xtream")), listOf("Cinemeta")),
        )
        var redeemResult: RedeemResult = RedeemResult.Redeemed(
            RedeemSummary(2, 1, 0, 0, listOf(RedeemedPlaylist("key-1", "Live", "added")), 1, 0),
        )
        override suspend fun preview(code: String): SetupCodeOutcome { previewCalls++; return previewResult }
        override suspend fun redeem(code: String, profileIndex: Int): RedeemResult {
            redeemCalls++; redeemed += code to profileIndex; return redeemResult
        }
        override suspend fun managedPlaylists(profileId: Int): List<ManagedInfo> = error("not used")
        override suspend fun detach(profileId: Int, playlistKey: String): Boolean = error("not used")
    }

    private val api = FakeApi()
    private var now = 0L
    private val holder = SetupCodeHolder(clock = { now })
    private var kind = AccountKind.REAL
    private var signOuts = 0
    private var activeProfile = 1
    private var signInRequests = 0
    private val pulls = mutableListOf<Int>()
    private val refreshes = mutableListOf<Int>()
    private val events = mutableListOf<Pair<String, Map<String, Any>>>()
    private var localKeys = setOf("key-1")

    private fun controller() = SetupCodeController(
        api = { api }, holder = holder, accountKind = { kind }, activeProfileIndex = { activeProfile },
        profileExists = { true }, localAccountKeys = { localKeys },
        pullPlaylists = { pulls += it }, refreshManaged = { refreshes += it },
        requestSignIn = { signInRequests++ }, signOutToSignIn = { signOuts++ }, telemetry = ProviderSetupTelemetry,
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

    @BeforeTest
    fun setUp() {
        ProviderSetupTelemetry.capture = { name, props -> events += name to props }
    }

    @AfterTest
    fun tearDown() {
        ProviderSetupTelemetry.capture = com.nuvio.app.core.analytics.AnalyticsSink::capture
    }

    @Test
    fun `typing groups the code as it is entered`() {
        val c = controller()
        c.onTyped("abcdefg")
        assertEquals("TUV-ABCD-EFG", c.state.value.typed)
    }

    @Test
    fun `pasting a link or a message takes the code out of it`() {
        val c = controller()
        c.onPasted("Open https://tuvora.co/s/TUV-ABCD-EFGH-JKMN then sign in")
        assertEquals("TUV-ABCD-EFGH-JKMN", c.state.value.typed)
        c.onPasted("abcdefghjkmn")
        assertEquals("TUV-ABCD-EFGH-JKMN", c.state.value.typed)
    }

    @Test
    fun `continue with a bad code is rejected with its problem and holds nothing`() {
        val c = controller()
        c.onTyped("ABCD-0000")
        assertEquals(ContinueResult.REJECTED, c.onContinue())
        assertEquals(SetupCodeProblem.BAD_CHARACTERS, c.state.value.typedProblem)
        assertFalse(holder.hasCode())
        assertEquals(0, api.previewCalls)
    }

    @Test
    fun `continue with a good code holds it in memory and opens the preview`() {
        val c = controller()
        c.onTyped("TUV-ABCD-EFGH-JKMN")
        assertEquals(ContinueResult.OPEN_PREVIEW, c.onContinue())
        assertEquals("ABCDEFGHJKMN", holder.peek())
        assertEquals(0, signInRequests)
    }

    @Test
    fun `continue without a real account keeps the code and asks to sign in and resumes once after`() {
        kind = AccountKind.SIGNED_OUT
        val c = controller()
        c.onTyped("TUV-ABCD-EFGH-JKMN")
        assertEquals(ContinueResult.NEEDS_SIGN_IN, c.onContinue())
        assertEquals(1, signInRequests)
        assertEquals("ABCDEFGHJKMN", holder.peek(), "kept in memory for the way back")
        assertFalse(c.takeResumeAfterSignIn(), "not while still signed out")
        kind = AccountKind.REAL
        assertTrue(c.takeResumeAfterSignIn(), "back from sign-in: open the preview")
        assertFalse(c.takeResumeAfterSignIn(), "only once")
    }

    @Test
    fun `a guest is asked before being signed out and the code is kept`() {
        kind = AccountKind.GUEST
        val c = controller()
        c.onTyped("TUV-ABCD-EFGH-JKMN")
        assertEquals(ContinueResult.NEEDS_SIGN_IN, c.onContinue())
        assertTrue(c.state.value.guestPrompt, "asks first")
        assertEquals(0, signOuts, "nothing is signed out yet")
        assertEquals(0, signInRequests)
        c.confirmGuestSignIn()
        assertEquals(1, signOuts, "the guest agreed: sign out of guest mode to reach sign-in")
        assertFalse(c.state.value.guestPrompt)
        assertEquals("ABCDEFGHJKMN", holder.peek(), "the code survives the sign-out")
        kind = AccountKind.REAL
        assertTrue(c.takeResumeAfterSignIn())
    }

    @Test
    fun `a guest who says no stays a guest and nothing resumes`() {
        kind = AccountKind.GUEST
        val c = controller()
        c.onTyped("TUV-ABCD-EFGH-JKMN")
        c.onContinue()
        c.dismissGuestPrompt()
        assertEquals(0, signOuts)
        kind = AccountKind.REAL
        assertFalse(c.takeResumeAfterSignIn())
    }

    @Test
    fun `a code that expires while signing in is not resumed`() {
        kind = AccountKind.SIGNED_OUT
        val c = controller()
        c.onTyped("TUV-ABCD-EFGH-JKMN")
        c.onContinue()
        kind = AccountKind.REAL
        now += SetupCodeHolder.TTL_MS
        assertFalse(c.takeResumeAfterSignIn())
    }

    @Test
    fun `a linked code is held and and a link that is not a code is ignored`() {
        val c = controller()
        assertTrue(c.acceptLinkedCode("https://tuvora.co/s/TUV-ABCD-EFGH-JKMN"))
        assertEquals("ABCDEFGHJKMN", holder.peek())
        assertFalse(c.acceptLinkedCode("https://tuvora.co/s/nope"))
    }

    @Test
    fun `a linked code without a real account goes to sign-in first`() {
        kind = AccountKind.SIGNED_OUT
        val c = controller()
        assertTrue(c.acceptLinkedCode("TUV-ABCD-EFGH-JKMN"))
        assertEquals(1, signInRequests)
        kind = AccountKind.REAL
        assertTrue(c.takeResumeAfterSignIn())
    }

    @Test
    fun `the preview loads once for the held code and defaults to the active profile`() {
        activeProfile = 3
        val c = controller()
        holder.set("ABCDEFGHJKMN")
        c.loadPreview()
        c.loadPreview()
        assertEquals(1, api.previewCalls, "a Ready preview is not fetched again")
        assertEquals("Acme TV", c.state.value.preview?.providerName)
        assertEquals(3, c.state.value.selectedProfileIndex)
        assertEquals(listOf("setup_code_preview" to mapOf<String, Any>("outcome" to "ready")), events)
    }

    @Test
    fun `a dead code is forgotten`() {
        val c = controller()
        for (outcome in listOf(SetupCodeOutcome.Unusable, SetupCodeOutcome.Expired(ProviderSupport.NONE))) {
            holder.set("ABCDEFGHJKMN")
            api.previewResult = outcome
            c.loadPreview(force = true)
            assertEquals(outcome, c.state.value.previewOutcome)
            assertNull(holder.peek(), "$outcome")
        }
    }

    @Test
    fun `a network or rate limit problem keeps the code for a retry`() {
        val c = controller()
        holder.set("ABCDEFGHJKMN")
        api.previewResult = SetupCodeOutcome.Network
        c.loadPreview()
        assertEquals("ABCDEFGHJKMN", holder.peek())
        api.previewResult = api.previewResult.let { SetupCodeOutcome.Ready(SetupPreview("Acme TV", ProviderSupport.NONE, "", emptyList(), emptyList())) }
        c.loadPreview(force = true)
        assertTrue(c.state.value.preview != null)
    }

    @Test
    fun `no preview is asked for when nothing is held`() {
        val c = controller()
        c.loadPreview()
        assertEquals(0, api.previewCalls)
        assertEquals(SetupCodeOutcome.Problem(SetupCodeProblem.EMPTY), c.state.value.previewOutcome)
    }

    @Test
    fun `confirm redeems into the chosen profile then pulls once for it and clears the code`() {
        activeProfile = 2
        val c = controller()
        holder.set("ABCDEFGHJKMN")
        c.loadPreview()
        c.confirm()
        assertEquals(listOf("ABCDEFGHJKMN" to 2), api.redeemed)
        assertEquals(listOf(2), pulls, "exactly one pull, for the redeemed profile")
        assertEquals(emptyList(), refreshes)
        assertNull(holder.peek(), "cleared on success")
        val done = c.state.value.completed!!
        assertEquals("Acme TV", done.providerName)
        assertEquals("key-1", done.openPlaylistKey)
        assertEquals(listOf("setup_code_preview", "setup_code_redeemed"), events.map { it.first })
        assertEquals(mapOf<String, Any>("added" to 1, "updated" to 0, "outcome" to "redeemed"), events.last().second)
    }

    @Test
    fun `a redeem into another profile refreshes its managed map and does not pull`() {
        activeProfile = 1
        val c = controller()
        holder.set("ABCDEFGHJKMN")
        c.loadPreview()
        c.selectProfile(3)
        c.confirm()
        assertEquals(listOf("ABCDEFGHJKMN" to 3), api.redeemed)
        assertEquals(emptyList(), pulls)
        assertEquals(listOf(3), refreshes)
        assertNull(c.state.value.completed?.openPlaylistKey, "its playlists are not on this device yet")
    }

    @Test
    fun `no analytics event carries the code`() {
        val c = controller()
        holder.set("ABCDEFGHJKMN")
        c.loadPreview()
        c.confirm()
        for ((name, props) in events) {
            assertFalse(props.values.any { it.toString().contains("ABCD") }, "$name must not carry the code: $props")
        }
    }

    @Test
    fun `confirm without a real account asks to sign in and redeems nothing`() {
        val c = controller()
        holder.set("ABCDEFGHJKMN")
        c.loadPreview()
        kind = AccountKind.SIGNED_OUT
        c.confirm()
        assertEquals(0, api.redeemCalls)
        assertEquals(1, signInRequests)
        assertEquals("ABCDEFGHJKMN", holder.peek())
    }

    @Test
    fun `an expired redeem still offers the contacts the preview showed and forgets the code`() {
        val c = controller()
        holder.set("ABCDEFGHJKMN")
        c.loadPreview()
        api.redeemResult = RedeemResult.Refused(SetupCodeOutcome.Expired(ProviderSupport.NONE))
        c.confirm()
        val refusal = c.state.value.redeemRefusal as SetupCodeOutcome.Expired
        assertEquals(ProviderSupport(telegram = "acme_tv"), refusal.support)
        assertNull(holder.peek())
        assertFalse(c.state.value.redeeming)
    }

    @Test
    fun `a profile that vanished or a network problem keeps the code`() {
        val c = controller()
        holder.set("ABCDEFGHJKMN")
        c.loadPreview()
        api.redeemResult = RedeemResult.Refused(SetupCodeOutcome.ProfileGone)
        c.confirm()
        assertEquals(SetupCodeOutcome.ProfileGone, c.state.value.redeemRefusal)
        assertEquals("ABCDEFGHJKMN", holder.peek())
        api.redeemResult = RedeemResult.Refused(SetupCodeOutcome.Network)
        c.confirm()
        assertEquals("ABCDEFGHJKMN", holder.peek())
        assertEquals(0, pulls.size)
    }

    @Test
    fun `cancel forgets everything`() {
        val c = controller()
        c.onTyped("TUV-ABCD-EFGH-JKMN")
        c.onContinue()
        c.loadPreview()
        c.cancel()
        assertNull(holder.peek())
        assertEquals(SetupCodeUiState(), c.state.value)
    }

    private fun completion(added: Int = 0, updated: Int = 0, already: Boolean = false, skipped: List<String> = emptyList()) =
        SetupCompletion("Acme TV", 1, added, updated, already, null, skipped)

    @Test
    fun `a redeem that added nothing never says it added a playlist`() {
        assertEquals(CompletionKind.ADDED, completion(added = 1).outcome)
        assertEquals(CompletionKind.ADDED, completion(updated = 2, skipped = listOf("missing_login")).outcome)
        assertEquals(CompletionKind.ALREADY_IN_ACCOUNT, completion(already = true).outcome)
        assertEquals(CompletionKind.NOTHING_MISSING_LOGIN, completion(skipped = listOf("missing_login")).outcome)
        assertEquals(CompletionKind.NOTHING_INVALID_URL, completion(skipped = listOf("invalid_url")).outcome)
        assertEquals(CompletionKind.NOTHING_MISSING_LOGIN, completion(skipped = listOf("invalid_url", "missing_login")).outcome)
    }

    private fun done(profile: Int, key: String? = "key-1") = SetupCompletion("Acme TV", profile, 1, 0, false, key)

    @Test
    fun `every successful redeem ends on the new playlist's details when it is on this device`() {
        assertEquals(SetupCompletionPlan(pops = 2, openDetailsKey = "key-1"), SetupCompletionNavigation.plan(done(1), fromAddPage = true))
        assertEquals(SetupCompletionPlan(pops = 1, openDetailsKey = "key-1"), SetupCompletionNavigation.plan(done(1), fromAddPage = false))
    }

    @Test
    fun `a redeem into another profile ends on the IPTV list because its details cannot open here`() {
        val plan = SetupCompletionNavigation.plan(done(3, key = null), fromAddPage = true)
        assertEquals(SetupCompletionPlan(pops = 2, openDetailsKey = null), plan)
        assertEquals(1, SetupCompletionNavigation.plan(done(3, key = null), fromAddPage = false).pops)
    }

    @Test
    fun `the controller hands the plan a key only for the active profile`() {
        activeProfile = 1
        val c = controller()
        holder.set("ABCDEFGHJKMN"); c.loadPreview(); c.selectProfile(3); c.confirm()
        val other = c.state.value.completed!!
        assertNull(SetupCompletionNavigation.plan(other, fromAddPage = true).openDetailsKey)
        c.finish()
        holder.set("ABCDEFGHJKMN"); c.loadPreview(); c.selectProfile(1); c.confirm()
        assertEquals("key-1", SetupCompletionNavigation.plan(c.state.value.completed!!, fromAddPage = true).openDetailsKey)
    }
}
