package com.tuvora.tvos.screens

import com.nuvio.app.features.iptv.SOURCE_TYPE_M3U_FILE
import com.nuvio.app.features.iptv.SOURCE_TYPE_M3U_URL
import com.nuvio.app.features.iptv.SOURCE_TYPE_STALKER
import com.nuvio.app.features.iptv.SOURCE_TYPE_XTREAM
import com.nuvio.app.features.iptv.XtreamAccount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Step 0.3 on Apple TV: the form's "Backup servers" section uses the shared rules only. */
class TvBackupServersTest {
    private val xtream = TvPlaylistFormPolicy.empty(SOURCE_TYPE_XTREAM)
        .copy(server = "http://main.example:8080", username = "u", password = "p")
    private val m3u = TvPlaylistFormPolicy.empty(SOURCE_TYPE_M3U_URL).copy(m3uUrl = "https://dead.invalid/reviewer-playlist.m3u")
    private val stalker = TvPlaylistFormPolicy.empty(SOURCE_TYPE_STALKER)
        .copy(portalUrl = "http://portal.example:88", macAddress = "00:1A:79:00:00:01")

    // --- UX100: the per-row editor never shows a move that can't act, and Remove confirms ---

    @Test
    fun `first of three rows can only move down or be removed`() {
        assertEquals(listOf(TvBackupRowAction.MOVE_DOWN, TvBackupRowAction.REMOVE), TvBackupServers.rowActions(0, 3))
    }

    @Test
    fun `a middle row can move both ways`() {
        assertEquals(
            listOf(TvBackupRowAction.MOVE_UP, TvBackupRowAction.MOVE_DOWN, TvBackupRowAction.REMOVE),
            TvBackupServers.rowActions(1, 3),
        )
    }

    @Test
    fun `the last row can only move up or be removed`() {
        assertEquals(listOf(TvBackupRowAction.MOVE_UP, TvBackupRowAction.REMOVE), TvBackupServers.rowActions(2, 3))
    }

    @Test
    fun `a single row can only be removed`() {
        assertEquals(listOf(TvBackupRowAction.REMOVE), TvBackupServers.rowActions(0, 1))
    }

    @Test
    fun `remove confirms and moves do not`() {
        assertTrue(TvBackupRowAction.REMOVE.needsConfirmation)
        assertFalse(TvBackupRowAction.MOVE_UP.needsConfirmation)
        assertFalse(TvBackupRowAction.MOVE_DOWN.needsConfirmation)
    }

    // --- which sources take backups ---

    @Test
    fun `xtream m3u link and stalker take backups and an m3u file does not`() {
        assertTrue(TvBackupServers.supports(SOURCE_TYPE_XTREAM))
        assertTrue(TvBackupServers.supports(SOURCE_TYPE_M3U_URL))
        assertTrue(TvBackupServers.supports(SOURCE_TYPE_STALKER))
        assertFalse(TvBackupServers.supports(SOURCE_TYPE_M3U_FILE))
    }

    // --- validation: the shared messages, against the field the source type uses ---

    @Test
    fun `a backup equal to the xtream server is same as the main server`() {
        val form = xtream.copy(backupUrls = listOf("HTTP://Main.Example:8080/"))
        assertEquals(listOf(TvBackupRowProblem(0, "Same as the main server")), TvBackupServers.problems(form))
        assertFalse(TvPlaylistFormPolicy.canSubmit(form), "a bad backup row must block Add")
    }

    @Test
    fun `a pasted xtream link is the main server too`() {
        val form = TvPlaylistFormPolicy.empty(SOURCE_TYPE_XTREAM).copy(
            pasteLink = true,
            playlistUrl = "http://main.example:8080/get.php?username=u&password=p&type=m3u_plus",
            backupUrls = listOf("main.example:8080"),
        )
        assertEquals("Same as the main server", TvBackupServers.problemAt(form, 0))
    }

    @Test
    fun `m3u backups compare against the m3u url and keep their path`() {
        assertTrue(TvBackupServers.problems(m3u.copy(backupUrls = listOf("https://tuvora.co/demo/reviewer-playlist.m3u"))).isEmpty())
        assertEquals(
            "Same as the main server",
            TvBackupServers.problemAt(m3u.copy(backupUrls = listOf("https://dead.invalid/reviewer-playlist.m3u/")), 0),
        )
    }

    @Test
    fun `stalker backups compare against the portal`() {
        assertEquals("Same as the main server", TvBackupServers.problemAt(stalker.copy(backupUrls = listOf("http://PORTAL.example:88")), 0))
    }

    @Test
    fun `every shared problem message reaches the form`() {
        val form = xtream.copy(backupUrls = listOf("ftp://x.example", "http://b.example", "http://b.example/", "http://"))
        assertEquals(
            listOf(
                TvBackupRowProblem(0, "Only http:// and https:// addresses work"),
                TvBackupRowProblem(2, "Already in the list"),
                TvBackupRowProblem(3, "Not a valid address"),
            ),
            TvBackupServers.problems(form),
        )
        assertNull(TvBackupServers.problemAt(form, 1))
    }

    @Test
    fun `blank rows are ignored and do not block saving`() {
        val form = xtream.copy(backupUrls = listOf("", "  "))
        assertTrue(TvBackupServers.problems(form).isEmpty())
        assertTrue(TvPlaylistFormPolicy.canSubmit(form))
    }

    // --- list edits are the shared ones ---

    @Test
    fun `add stops at the shared maximum`() {
        val five = List(TvBackupServers.MAX_BACKUPS) { "http://b$it.example" }
        assertFalse(TvBackupServers.canAdd(five))
        assertEquals(five, TvBackupServers.add(five))
        assertEquals(listOf("a", ""), TvBackupServers.add(listOf("a")))
    }

    @Test
    fun `move and remove reorder by priority`() {
        val rows = listOf("a", "b", "c")
        assertEquals(listOf("b", "a", "c"), TvBackupServers.moveUp(rows, 1))
        assertEquals(listOf("a", "c", "b"), TvBackupServers.moveDown(rows, 1))
        assertEquals(rows, TvBackupServers.moveUp(rows, 0))
        assertEquals(listOf("a", "c"), TvBackupServers.remove(rows, 1))
        assertEquals(listOf("a", "x", "c"), TvBackupServers.update(rows, 1, "x"))
    }

    // --- the rows reach the shared save path, and Edit pre-fills them ---

    @Test
    fun `the typed rows reach the shared form input`() {
        val rows = listOf("http://b1.example:8080", "b2.example")
        assertEquals(rows, assertNotNull(TvPlaylistFormPolicy.toInput(xtream.copy(backupUrls = rows))).backupUrls)
        assertEquals(rows, assertNotNull(TvPlaylistFormPolicy.toInput(stalker.copy(backupUrls = rows))).backupUrls)
        val m3uRows = listOf("https://tuvora.co/demo/reviewer-playlist.m3u")
        assertEquals(m3uRows, assertNotNull(TvPlaylistFormPolicy.toInput(m3u.copy(backupUrls = m3uRows))).backupUrls)
    }

    @Test
    fun `an m3u file never sends backups`() {
        val form = TvPlaylistFormPolicy.empty(SOURCE_TYPE_M3U_FILE).copy(backupUrls = listOf("http://b.example"))
        assertEquals(emptyList(), assertNotNull(TvPlaylistFormPolicy.toInput(form)).backupUrls)
    }

    @Test
    fun `edit pre-fills the saved backup list in order`() {
        val account = XtreamAccount(
            id = "m3u|https://dead.invalid/reviewer-playlist.m3u", name = "Demo",
            baseUrl = "https://dead.invalid/reviewer-playlist.m3u", username = "", password = "",
            sourceType = SOURCE_TYPE_M3U_URL,
            backupUrls = listOf("https://tuvora.co/demo/reviewer-playlist.m3u", "https://b2.example/list.m3u"),
        )
        assertEquals(account.backupUrls, TvPlaylistFormPolicy.fromAccount(account).backupUrls)
    }

    @Test
    fun `the hint matches the source type`() {
        assertEquals("http://other-host/playlist.m3u", TvBackupServers.hint(SOURCE_TYPE_M3U_URL))
        assertEquals("http://other-host:port", TvBackupServers.hint(SOURCE_TYPE_XTREAM))
        assertEquals("http://other-host:port", TvBackupServers.hint(SOURCE_TYPE_STALKER))
    }

    @Test
    fun `the active backup address names the server that answers`() {
        val backups = listOf("https://tuvora.co/demo/reviewer-playlist.m3u", "https://b2.example/list.m3u")
        assertEquals("https://tuvora.co/demo/reviewer-playlist.m3u", TvBackupServers.activeBackupAddress(backups, 1))
        assertEquals("https://b2.example/list.m3u", TvBackupServers.activeBackupAddress(backups, 2))
        assertNull(TvBackupServers.activeBackupAddress(backups, 0), "the main server is not a backup")
        assertNull(TvBackupServers.activeBackupAddress(backups, 3), "a stale index names nothing")
    }
}
