package com.nuvio.app.features.player

import com.nuvio.app.features.streams.PlaybackTlsClass
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The permissive fallback against a REAL self-signed HTTPS server: only a host classified as an IPTV panel is retried without
 * verification after the strict handshake fails; media-server, add-on and unknown hosts keep the strict failure.
 */
class PanelFallbackCallFactoryTest {
    private var server: HttpsServer? = null
    private var port = 0

    @After
    fun tearDown() {
        server?.stop(0)
    }

    private fun startSelfSigned(): Int {
        val port = ServerSocket(0).use { it.localPort }
        val dir = Files.createTempDirectory("tuvora-panel").toFile()
        val keystore = File(dir, "k.p12")
        val keytool = File(System.getProperty("java.home"), "bin/keytool").absolutePath
        val p = ProcessBuilder(
            keytool, "-genkeypair", "-alias", "s", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
            "-dname", "CN=panel.invalid", "-ext", "san=dns:panel.invalid", "-keystore", keystore.absolutePath,
            "-storetype", "PKCS12", "-storepass", "changeit", "-keypass", "changeit",
        ).redirectErrorStream(true).start()
        check(p.waitFor() == 0) { p.inputStream.readBytes().decodeToString() }
        val ks = KeyStore.getInstance("PKCS12").apply { keystore.inputStream().use { load(it, "changeit".toCharArray()) } }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, "changeit".toCharArray()) }
        val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        val https = HttpsServer.create(InetSocketAddress("127.0.0.1", port), 0)
        https.httpsConfigurator = HttpsConfigurator(ctx)
        https.createContext("/") { ex ->
            val body = "stream".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        https.start()
        dir.deleteRecursively()
        server = https
        return port
    }

    private val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private fun factory(classify: (String) -> PlaybackTlsClass): PanelFallbackCallFactory {
        val strict = OkHttpClient.Builder().build()
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trustAll), SecureRandom()) }
        val permissive = strict.newBuilder().sslSocketFactory(ssl.socketFactory, trustAll).hostnameVerifier(HostnameVerifier { _, _ -> true }).build()
        return PanelFallbackCallFactory(strict, permissive, classify)
    }

    private fun get(factory: PanelFallbackCallFactory): String =
        factory.newCall(Request.Builder().url("https://127.0.0.1:$port/live/1.ts").build()).execute().use { it.body.string() }

    @Test
    fun `an iptv panel host with a self signed certificate still plays`() {
        port = startSelfSigned()
        assertEquals("stream", get(factory { PlaybackTlsClass.PermissivePanel }), "panel fixture: strict failed, permissive retry served it")
    }

    @Test
    fun `a media server or unknown host with the same certificate is refused`() {
        port = startSelfSigned()
        assertFailsWith<SSLException>("media server / addon / unknown fixture") { get(factory { PlaybackTlsClass.Strict }) }
    }

    @Test
    fun `the classification is asked per request host`() {
        port = startSelfSigned()
        val seen = mutableListOf<String>()
        val panelOnly = factory { host -> seen += host; if (host == "127.0.0.1") PlaybackTlsClass.PermissivePanel else PlaybackTlsClass.Strict }
        assertEquals("stream", get(panelOnly), "the panel host")
        assertEquals(listOf("127.0.0.1"), seen, "the host that was requested")
    }

    @Test
    fun `async calls fall back the same way`() {
        port = startSelfSigned()
        val result = java.util.concurrent.CompletableFuture<String>()
        factory { PlaybackTlsClass.PermissivePanel }.newCall(Request.Builder().url("https://127.0.0.1:$port/x").build()).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) { result.completeExceptionally(e) }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) { result.complete(response.use { it.body.string() }) }
        })
        assertEquals("stream", result.get(20, java.util.concurrent.TimeUnit.SECONDS), "enqueue fallback")
        val strictResult = java.util.concurrent.CompletableFuture<String>()
        factory { PlaybackTlsClass.Strict }.newCall(Request.Builder().url("https://127.0.0.1:$port/x").build()).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) { strictResult.complete(e::class.java.simpleName) }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) { strictResult.complete("served") }
        })
        assertEquals(true, strictResult.get(20, java.util.concurrent.TimeUnit.SECONDS).contains("SSL"), "strict enqueue fails with an SSL error")
    }
}
