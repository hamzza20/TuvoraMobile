package com.nuvio.app.features.servers

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.Uri
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.toUri
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URL
import java.nio.file.Files
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Artwork and playback of a PINNED self-signed media server against a REAL local HTTPS server (JVM): the image fetcher and
 * the loopback relay read through the pinned client; without the pin (or after the certificate changes) they fail; hosts
 * that were never pinned keep the normal path (the fetcher steps aside).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PinnedMediaE2ETest {
    private class Disk { var json: String? = null }

    private val video = ByteArray(300_000) { (it % 253).toByte() }
    private val jpeg = ByteArray(2_048) { (it % 199).toByte() }
    private var server: HttpsServer? = null
    private var fingerprint = ""
    private var port = 0

    @After
    fun tearDown() {
        server?.stop(0)
    }

    private fun start(portHint: Int = ServerSocket(0).use { it.localPort }): Int {
        val dir = Files.createTempDirectory("tuvora-media").toFile()
        val keystore = File(dir, "k.p12")
        val keytool = File(System.getProperty("java.home"), "bin/keytool").absolutePath
        val p = ProcessBuilder(
            keytool, "-genkeypair", "-alias", "s", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
            "-dname", "CN=media.invalid", "-ext", "san=dns:media.invalid", "-keystore", keystore.absolutePath,
            "-storetype", "PKCS12", "-storepass", "changeit", "-keypass", "changeit",
        ).redirectErrorStream(true).start()
        check(p.waitFor() == 0) { p.inputStream.readBytes().decodeToString() }
        val ks = KeyStore.getInstance("PKCS12").apply { keystore.inputStream().use { load(it, "changeit".toCharArray()) } }
        val cert = ks.getCertificate("s") as X509Certificate
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, "changeit".toCharArray()) }
        val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        val https = HttpsServer.create(InetSocketAddress("127.0.0.1", portHint), 0)
        https.httpsConfigurator = HttpsConfigurator(ctx)
        https.createContext("/video.mp4") { serve(it, video, "video/mp4") }
        https.createContext("/img.jpg") { serve(it, jpeg, "image/jpeg") }
        https.start()
        dir.deleteRecursively()
        server = https
        fingerprint = MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02X".format(it) }
        return portHint
    }

    private fun serve(ex: HttpExchange, data: ByteArray, type: String) {
        val range = ex.requestHeaders.getFirst("Range")?.removePrefix("bytes=")
        ex.responseHeaders.add("Content-Type", type)
        ex.responseHeaders.add("Accept-Ranges", "bytes")
        if (range != null) {
            val (a, b) = range.split("-")
            val from = a.toInt()
            val to = if (b.isEmpty()) data.size - 1 else b.toInt()
            val slice = data.copyOfRange(from, to + 1)
            ex.responseHeaders.add("Content-Range", "bytes $from-$to/${data.size}")
            ex.sendResponseHeaders(206, slice.size.toLong())
            ex.responseBody.use { it.write(slice) }
        } else {
            ex.sendResponseHeaders(200, data.size.toLong())
            if (ex.requestMethod != "HEAD") ex.responseBody.use { it.write(data) }
        }
    }

    private fun trust() = Disk().let { d -> ServerTrust(ServerPinStore({ d.json }, { d.json = it }, { d.json = null })) }

    private fun fetchViaRelay(session: RelaySession, url: String, range: String? = null): Pair<Int, ByteArray> {
        val conn = URL(session.rewrite(url)!!).openConnection() as HttpURLConnection
        range?.let { conn.setRequestProperty("Range", it) }
        conn.connectTimeout = 10_000
        conn.readTimeout = 20_000
        val status = conn.responseCode
        val body = (if (status < 400) conn.inputStream else conn.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
        return status to body
    }

    @Test
    fun `artwork of a pinned server is fetched through the pin and unpinned hosts step aside`() = runBlocking {
        port = start()
        val authority = "127.0.0.1:$port"
        val trust = trust()
        val factory = PinnedServerImageFetcherFactory(TrustedServerHttp(trust)) { trust.pinnedFingerprint(it) }
        val context = ApplicationProvider.getApplicationContext<Application>()
        val loader = ImageLoader.Builder(context).build()
        val url = "https://$authority/img.jpg"

        assertNull(factory.create(url.toUri(), Options(context), loader), "no pin: the normal fetcher keeps this URL")

        trust.pin(authority, fingerprint)
        val fetcher = assertNotNull(factory.create(url.toUri(), Options(context), loader), "pinned: handled by the pinned client")
        val result = withContext(Dispatchers.IO) { fetcher.fetch() } as SourceFetchResult
        assertEquals("image/jpeg", result.mimeType, "mime")
        assertContentEquals(jpeg, result.source.source().readByteArray(), "the artwork bytes arrived over the pinned TLS")

        assertNull(factory.create("https://elsewhere.example/img.jpg".toUri(), Options(context), loader), "other hosts keep the normal path")
    }

    @Test
    fun `video of a pinned server plays through the relay with range and is refused without or after a changed pin`() = runBlocking {
        port = start()
        val authority = "127.0.0.1:$port"
        val trust = trust()
        trust.pin(authority, fingerprint)
        val session = PinnedMediaRelay(TrustedServerHttp(trust)).open(authority)
        try {
            val url = "https://$authority/video.mp4"
            val (full, fullBody) = fetchViaRelay(session, url)
            assertEquals(200, full, "full read")
            assertContentEquals(video, fullBody, "all bytes, verified pinned TLS underneath")

            val (partial, part) = fetchViaRelay(session, url, "bytes=1000-1999")
            assertEquals(206, partial, "range read (seek)")
            assertContentEquals(video.copyOfRange(1000, 2000), part, "range bytes")

            // The server now presents a different certificate than the pinned one: the relay must not serve anything.
            server?.stop(0)
            start(port)
            val (changed, changedBody) = fetchViaRelay(session, url)
            assertTrue(changed >= 400, "refused after the certificate changed (was $changed)")
            assertTrue(changedBody.isEmpty(), "no media bytes")
        } finally {
            session.dispose()
        }
    }

    @Test
    fun `without a pin the relay cannot read the server`() = runBlocking {
        port = start()
        val authority = "127.0.0.1:$port"
        val session = PinnedMediaRelay(TrustedServerHttp(trust())).open(authority)
        try {
            val (status, body) = fetchViaRelay(session, "https://$authority/video.mp4")
            assertTrue(status >= 400, "a self-signed server nobody pinned is refused (was $status)")
            assertTrue(body.isEmpty(), "no bytes")
        } finally {
            session.dispose()
        }
    }
}
