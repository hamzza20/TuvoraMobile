package com.tuvora.tvos.screens

import com.nuvio.app.features.iptv.BackupServerListEdits
import com.nuvio.app.features.iptv.BackupServerValidation
import com.nuvio.app.features.iptv.SOURCE_TYPE_M3U_URL
import com.nuvio.app.features.iptv.SOURCE_TYPE_STALKER
import com.nuvio.app.features.iptv.SOURCE_TYPE_XTREAM
import com.nuvio.app.features.iptv.parseXtreamAccount

/** What the per-row backup editor offers (NuvioTV `BackupServerListEdits.RowAction`, UX100). */
enum class TvBackupRowAction(val needsConfirmation: Boolean) { MOVE_UP(false), MOVE_DOWN(false), REMOVE(true) }

/** One backup row's validation verdict, with the shared English message (localized by the app). */
data class TvBackupRowProblem(val index: Int, val message: String)

/**
 * Step 0.3 — the Apple TV "Backup servers" section of the playlist form. Every rule is the shared
 * one ([BackupServerValidation], [BackupServerListEdits]); this only adapts them to the form's
 * fields so the SwiftUI screen re-implements nothing.
 */
object TvBackupServers {
    const val MAX_BACKUPS: Int = BackupServerValidation.MAX_BACKUPS

    /** Xtream, M3U link and Stalker take backups; an M3U file has no server (section hidden). */
    fun supports(sourceType: String): Boolean = BackupServerValidation.supportsBackups(sourceType)

    /** The main address a backup is compared against — the field the form's source type uses. */
    fun mainAddress(form: TvPlaylistForm): String = when (form.sourceType) {
        SOURCE_TYPE_M3U_URL -> form.m3uUrl
        SOURCE_TYPE_STALKER -> form.portalUrl
        SOURCE_TYPE_XTREAM ->
            if (form.pasteLink) parseXtreamAccount(form.playlistUrl.trim())?.baseUrl ?: form.playlistUrl else form.server
        else -> ""
    }

    /** The rows that are wrong right now (blank rows are ignored), in row order. */
    fun problems(form: TvPlaylistForm): List<TvBackupRowProblem> =
        BackupServerValidation.validate(form.sourceType, mainAddress(form), form.backupUrls).problems
            .map { TvBackupRowProblem(it.index, BackupServerListEdits.message(it.problem)) }

    fun problemAt(form: TvPlaylistForm, index: Int): String? = problems(form).firstOrNull { it.index == index }?.message

    /** Whether the backups let the form save (always true for a source without backups). */
    fun isValid(form: TvPlaylistForm): Boolean =
        BackupServerValidation.validate(form.sourceType, mainAddress(form), form.backupUrls).ok

    fun canAdd(rows: List<String>): Boolean = BackupServerListEdits.canAdd(rows)
    fun add(rows: List<String>): List<String> = BackupServerListEdits.add(rows)
    fun remove(rows: List<String>, index: Int): List<String> = BackupServerListEdits.remove(rows, index)
    fun update(rows: List<String>, index: Int, value: String): List<String> = BackupServerListEdits.update(rows, index, value)
    fun moveUp(rows: List<String>, index: Int): List<String> = BackupServerListEdits.moveUp(rows, index)
    fun moveDown(rows: List<String>, index: Int): List<String> = BackupServerListEdits.moveDown(rows, index)

    /**
     * The actions the editor shows for row [index] of [count], top to bottom — NuvioTV's
     * `rowActions` (UX100): a move that can't act is left out rather than disabled (focus skips a
     * disabled row, so DOWN-DOWN landed on Remove), and Remove confirms.
     */
    fun rowActions(index: Int, count: Int): List<TvBackupRowAction> = buildList {
        if (index > 0) add(TvBackupRowAction.MOVE_UP)
        if (index < count - 1) add(TvBackupRowAction.MOVE_DOWN)
        add(TvBackupRowAction.REMOVE)
    }

    /** NuvioTV `iptv_backup_server_hint_m3u` / `_hint_base`. */
    fun hint(sourceType: String): String =
        if (sourceType == SOURCE_TYPE_M3U_URL) "http://other-host/playlist.m3u" else "http://other-host:port"

    /**
     * The backup now answering for a playlist, for the details' "Using backup server N (address)"
     * (NuvioTV `iptv_using_backup_server_host`); null when [activeIndex] is the main server or no
     * longer names a saved backup (the row then says just "Using backup server N").
     */
    fun activeBackupAddress(backupUrls: List<String>, activeIndex: Int): String? =
        if (activeIndex <= 0) null else backupUrls.getOrNull(activeIndex - 1)
}
