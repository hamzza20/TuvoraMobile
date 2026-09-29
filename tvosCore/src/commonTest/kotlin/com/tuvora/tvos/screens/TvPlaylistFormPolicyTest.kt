package com.tuvora.tvos.screens

import com.nuvio.app.features.iptv.SOURCE_TYPE_M3U_FILE
import com.nuvio.app.features.iptv.SOURCE_TYPE_M3U_URL
import com.nuvio.app.features.iptv.SOURCE_TYPE_STALKER
import com.nuvio.app.features.iptv.SOURCE_TYPE_XTREAM
import com.nuvio.app.features.iptv.XtreamAccount
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TvPlaylistFormPolicyTest {
    private val blank = TvPlaylistFormPolicy.empty()

    @Test
    fun `xtream details need server username and password`() {
        assertFalse(TvPlaylistFormPolicy.canSubmit(blank.copy(server = "http://h:80", username = "u")))
        assertTrue(TvPlaylistFormPolicy.canSubmit(blank.copy(server = "http://h:80", username = "u", password = "p")))
    }

    @Test
    fun `xtream paste link needs only the link`() {
        assertFalse(TvPlaylistFormPolicy.canSubmit(blank.copy(pasteLink = true, server = "http://h", username = "u", password = "p")))
        assertTrue(TvPlaylistFormPolicy.canSubmit(blank.copy(pasteLink = true, playlistUrl = "http://h/get.php?username=u&password=p")))
    }

    @Test
    fun `m3u url stalker and file sources`() {
        assertTrue(TvPlaylistFormPolicy.canSubmit(TvPlaylistFormPolicy.empty(SOURCE_TYPE_M3U_URL).copy(m3uUrl = "http://h/list.m3u")))
        assertFalse(TvPlaylistFormPolicy.canSubmit(TvPlaylistFormPolicy.empty(SOURCE_TYPE_STALKER).copy(portalUrl = "http://p")))
        assertTrue(TvPlaylistFormPolicy.canSubmit(TvPlaylistFormPolicy.empty(SOURCE_TYPE_STALKER).copy(portalUrl = "http://p", macAddress = "00:1A:79:00:00:01")))
        // Apple TV has no document picker, so a file playlist is never submittable here.
        assertFalse(TvPlaylistFormPolicy.canSubmit(TvPlaylistFormPolicy.empty(SOURCE_TYPE_M3U_FILE).copy(name = "x")))
    }

    @Test
    fun `pasted xtream link is split into server and credentials`() {
        val input = assertNotNull(TvPlaylistFormPolicy.toInput(blank.copy(pasteLink = true, playlistUrl = " http://host:8080/get.php?username=alice&password=secret&type=m3u_plus ")))
        assertEquals("http://host:8080", input.serverUrl)
        assertEquals("alice", input.username)
        assertEquals("secret", input.password)
        assertEquals(SOURCE_TYPE_XTREAM, input.sourceType)
    }

    @Test
    fun `pasted link without credentials yields no input`() {
        assertNull(TvPlaylistFormPolicy.toInput(blank.copy(pasteLink = true, playlistUrl = "http://host:8080/")))
    }

    @Test
    fun `stalker input uses the portal as server and blanks xtream creds`() {
        val form = TvPlaylistFormPolicy.empty(SOURCE_TYPE_STALKER).copy(
            portalUrl = " http://portal:88 ", macAddress = "00:1A:79:AA:BB:CC", username = "ignored",
            stalkerUsername = "", serialNumber = "SN1", sendDeviceId = false,
        )
        val input = assertNotNull(TvPlaylistFormPolicy.toInput(form))
        assertEquals("http://portal:88", input.serverUrl)
        assertEquals("", input.username)
        assertEquals("00:1A:79:AA:BB:CC", input.macAddress)
        assertNull(input.stalkerUsername)
        assertEquals("SN1", input.serialNumber)
        assertFalse(input.sendDeviceId)
    }

    @Test
    fun `optional fields become null and dns stays system`() {
        val input = assertNotNull(TvPlaylistFormPolicy.toInput(blank.copy(server = "http://h", username = "u", password = "p", name = "  ", epgUrl = "", userAgent = " ")))
        assertNull(input.name)
        assertNull(input.epgUrl)
        assertNull(input.userAgent)
        assertEquals("system", input.dnsProvider)
        assertEquals(24, input.autoRefreshHours)
    }

    @Test
    fun `edit prefill follows the source type`() {
        val xtream = XtreamAccount(id = "x", name = "Home", baseUrl = "http://h:80", username = "u", password = "p", autoRefreshHours = 12)
        val form = TvPlaylistFormPolicy.fromAccount(xtream)
        assertEquals("http://h:80", form.server)
        assertEquals("u", form.username)
        assertEquals(12, form.autoRefreshHours)
        assertFalse(form.pasteLink)

        val m3u = XtreamAccount(id = "m", name = "List", baseUrl = "http://h/l.m3u", username = "", password = "", sourceType = SOURCE_TYPE_M3U_URL)
        assertEquals("http://h/l.m3u", TvPlaylistFormPolicy.fromAccount(m3u).m3uUrl)

        val stalker = XtreamAccount(id = "s", name = "Box", baseUrl = "http://p", username = "", password = "", sourceType = SOURCE_TYPE_STALKER, macAddress = "00:1A:79:01:02:03")
        val sf = TvPlaylistFormPolicy.fromAccount(stalker)
        assertEquals("http://p", sf.portalUrl)
        assertEquals("00:1A:79:01:02:03", sf.macAddress)
    }

    @Test
    fun `random mac stays in the infomir range`() {
        val mac = TvPlaylistFormPolicy.randomStbMac(Random(7))
        assertTrue(Regex("^00:1A:79:[0-9A-F]{2}:[0-9A-F]{2}:[0-9A-F]{2}$").matches(mac), mac)
    }

    @Test
    fun `auto refresh labels match nuviotv`() {
        assertEquals("Off", TvPlaylistFormPolicy.autoRefreshLabel(0))
        assertEquals("24h", TvPlaylistFormPolicy.autoRefreshLabel(24))
    }
}
