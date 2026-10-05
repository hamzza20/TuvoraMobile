package com.tuvora.tvos.screens

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TvIptvPairingPolicyTest {
    private val p = TvIptvPairingPolicy
    private fun json(s: String): JsonElement = Json.parseToJsonElement(s)

    @Test
    fun `codes match the backend format`() {
        val code = p.codeFrom(ByteArray(16) { (it * 37).toByte() })
        assertTrue(p.CODE_REGEX.matches(code), code)
    }

    @Test
    fun `web url and display url`() {
        assertEquals("https://tuvora.co/iptv-pairing/?code=ABC123", p.webUrl(p.WEB_BASE_URL, "ABC123"))
        assertEquals("https://x.test/p?a=1&code=ABC123", p.webUrl("https://x.test/p?a=1", "ABC123"))
        assertEquals("tuvora.co/iptv-pairing", p.displayUrl(p.WEB_BASE_URL))
        assertEquals("Code expires in 04:05", p.expiresText(245_000))
    }

    @Test
    fun `xtream payload becomes the add form`() {
        val form = p.payloadToForm(json("""{"source_type":"xtream","base_url":"http://p.test:8080","username":"u","password":"pw","name":"Home","epg_url":"http://e.test","auto_refresh_hours":12}"""))!!
        assertEquals("xtream", form.sourceType)
        assertEquals("http://p.test:8080", form.server)
        assertEquals("u", form.username)
        assertEquals("Home", form.name)
        assertEquals(12, form.autoRefreshHours)
        assertTrue(TvPlaylistFormPolicy.canSubmit(form))
    }

    @Test
    fun `url and stalker payloads map their fields`() {
        val m3u = p.payloadToForm(json("""{"source_type":"url","base_url":"http://p.test/get.php","user_agent":"UA"}"""))!!
        assertEquals("m3u_url", m3u.sourceType)
        assertEquals("http://p.test/get.php", m3u.m3uUrl)
        assertEquals("UA", m3u.userAgent)
        val stalker = p.payloadToForm(json("""{"source_type":"stalker","portal_url":"portal.test/c","mac_address":"00:1A:79:00:00:01","send_device_id":false}"""))!!
        assertEquals("http://portal.test/c", stalker.portalUrl)
        assertEquals(false, stalker.sendDeviceId)
    }

    /** F46: "Add from phone" carries the four STB identity overrides (NuvioTV IptvPairingPayload keys). */
    @Test
    fun `stalker pairing carries device id 2 signature model and hw version`() {
        val f = p.payloadToForm(json("""{"source_type":"stalker","portal_url":"http://p","mac_address":"00:1A:79:00:00:01",
            "device_id2":" d2 ","signature":"sig","stb_model":"MAG254","hw_version":"2.6-IB-00"}"""))!!
        assertEquals(listOf("d2", "sig", "MAG254", "2.6-IB-00"), listOf(f.deviceId2, f.signature, f.stbModel, f.hwVersion))
    }

    @Test
    fun `unusable payloads are refused`() {
        assertNull(p.payloadToForm(null))
        assertNull(p.payloadToForm(json("""{"source_type":""}""")))
        assertNull(p.payloadToForm(json("""{"source_type":"xtream","base_url":"http://p.test"}""")))
        assertNull(p.payloadToForm(json("""["x"]""")))
    }
}
