package com.nuvio.app.features.player

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every libmpv bridge must verify peer certificates. mpv's `tls-verify` default is "no" when it is built without libcurl and
 * with libavformat < 63 (MPVKit's build), so a bridge that sets nothing plays self-signed and intercepted https streams
 * unchecked (proven on the iOS and tvOS simulator slices). On iOS/tvOS GnuTLS also cannot see the system trust store, so
 * `tls-verify=yes` needs a bundled CA list (`tls-ca-file`). This guard reads the bridge sources: a new mpv bridge that does not
 * set both fails the build.
 */
class MpvTlsOptionsGuardTest {
    private val root: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    private fun read(path: String): String = File(root, path).also { assertTrue(it.isFile, "missing $path") }.readText()

    @Test
    fun `the android bridge verifies and names a ca file`() {
        val text = read("composeApp/src/androidMain/kotlin/com/nuvio/app/features/player/PlayerEngine.android.kt")
        assertTrue(text.contains("""setOptionString("tls-verify", "yes")"""), "android tls-verify=yes")
        assertTrue(text.contains("""setOptionString("tls-ca-file""""), "android tls-ca-file")
    }

    @Test
    fun `the apple bridges verify and use the bundled ca list`() {
        listOf(
            "iosApp/iosApp/Player/MPVPlayerBridge.swift",
            "tvosApp/TuvoraTV/Sources/Player/MPVPlayerBridge.swift",
        ).forEach { path ->
            val text = read(path)
            assertTrue(text.contains("""mpv_set_option_string(mpv, "tls-verify", "yes")"""), "$path sets tls-verify=yes")
            assertTrue(text.contains(""""tls-ca-file""""), "$path sets tls-ca-file")
            assertTrue(text.contains("""forResource: "cacert", ofType: "pem""""), "$path reads the bundled cacert.pem")
        }
    }

    @Test
    fun `no swift file creates an mpv core without the verify option`() {
        val swiftFiles = listOf("iosApp", "tvosApp").flatMap { dir ->
            File(root, dir).walkTopDown().filter { it.isFile && it.extension == "swift" && "/build/" !in it.path }.toList()
        }
        val creators = swiftFiles.filter { it.readText().contains("mpv_create(") }
        assertTrue(creators.isNotEmpty(), "found the mpv bridges")
        val offenders = creators.filterNot { it.readText().contains("""mpv_set_option_string(mpv, "tls-verify", "yes")""") }
        assertTrue(offenders.isEmpty(), "mpv core without tls-verify=yes: ${offenders.map { it.relativeTo(root).path }}")
    }

    @Test
    fun `the ca list is bundled by both apple apps`() {
        val pem = File(root, "iosApp/iosApp/Resources/cacert.pem")
        assertTrue(pem.isFile, "iosApp Resources/cacert.pem (the iOS target syncs that folder)")
        assertTrue(pem.readText().split("BEGIN CERTIFICATE").size - 1 >= 100, "a real CA list")
        assertTrue(read("tvosApp/project.yml").contains("iosApp/iosApp/Resources/cacert.pem"), "tvosApp bundles the same file")
    }
}
