package com.tuvora.tvos.screens

import com.nuvio.app.core.sync.SyncManager
import com.nuvio.app.features.iptv.ManagedInfoRepository
import com.nuvio.app.features.iptv.PlaylistActionsPolicy
import com.nuvio.app.features.iptv.PlaylistAction
import com.nuvio.app.features.iptv.XtreamAccount
import com.nuvio.app.features.iptv.XtreamRepository
import com.nuvio.app.features.iptv.XtreamUiState
import com.nuvio.app.features.iptv.match.XtreamMatchIndex
import com.nuvio.app.features.player.AvailableLanguageOptions
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Settings → IPTV for Apple TV: the saved playlists and NuvioTV's "Add Playlist" form. The full-form
 * path ([XtreamRepository.addFromForm]/editFromForm) is internal to the shared code, so this is its
 * public door; the field decisions live in [TvPlaylistFormPolicy].
 */
object TvPlaylists {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val state: StateFlow<XtreamUiState> get() = XtreamRepository.uiState

    fun ensureLoaded() = XtreamRepository.ensureLoaded()

    /**
     * Step 0.3: playlist id -> the backup server now answering (1-based), only for playlists that
     * are NOT on their main server — the "Using backup server N" note. Device-local failover state.
     */
    val activeServers: StateFlow<Map<String, Int>> get() = XtreamRepository.activeServers

    fun add(form: TvPlaylistForm, onResult: (Boolean) -> Unit) {
        val input = TvPlaylistFormPolicy.toInput(form)
        if (input == null) {
            // A pasted link without username/password: the repository names the problem.
            XtreamRepository.addFromUrl(form.playlistUrl, form.name.ifBlank { null }, onResult)
        } else {
            XtreamRepository.addFromForm(input, onResult)
        }
    }

    fun edit(accountId: String, form: TvPlaylistForm, onResult: (Boolean) -> Unit) {
        // A managed playlist (one a provider installed) is edited from the PULLED account so no provider-owned
        // field can change a byte and silently detach it (ManagedEditPolicy; contract section 6).
        val pulled = XtreamRepository.uiState.value.accounts.firstOrNull { it.id == accountId }
        val managed = ManagedInfoRepository.isManaged(ProfileRepository.activeProfileId, accountId)
        val input = TvPlaylistFormPolicy.toEditInput(form, pulled, managed)
        if (input == null) {
            XtreamRepository.editFromUrl(accountId, form.playlistUrl, onResult)
        } else {
            XtreamRepository.editFromForm(accountId, input, onResult)
        }
    }

    fun setEnabled(accountId: String, enabled: Boolean) = XtreamRepository.setEnabled(accountId, enabled)

    fun remove(accountId: String) = XtreamRepository.remove(accountId)

    fun clearError() = XtreamRepository.clearError()

    /** "Re-match catalog" is offered only where it means something (Xtream's TMDB match index). */
    fun canRematch(account: XtreamAccount): Boolean =
        PlaylistAction.REMATCH in PlaylistActionsPolicy.bodyActions(account)

    /** Drops stale "not on this provider" verdicts, as the phone's and NuvioTV's Re-match catalog. */
    fun rematch(accountId: String) {
        scope.launch { XtreamMatchIndex.distrustNegativeMappings(accountId) }
    }
}

/** Small Settings actions whose shared entry points are internal or need a profile id. */
object TvSettings {
    /** NuvioTV Account → "Sync now": pull the latest for this profile, skipping the recent-pull throttle. */
    fun syncNow() = SyncManager.requestForegroundPull(ProfileRepository.activeProfileId, force = true)

    /** ISO codes the language pickers offer (the phone's list; the UI names them in the viewer's locale). */
    fun languageCodes(): List<String> = AvailableLanguageOptions.map { it.code }

    fun setSubtitleFontSize(sp: Int) {
        val style = PlayerSettingsRepository.uiState.value.subtitleStyle
        PlayerSettingsRepository.setSubtitleStyle(style.copy(fontSizeSp = sp))
    }

    fun setSubtitleBold(bold: Boolean) {
        val style = PlayerSettingsRepository.uiState.value.subtitleStyle
        PlayerSettingsRepository.setSubtitleStyle(style.copy(bold = bold))
    }

    fun setSubtitleOutline(enabled: Boolean) {
        val style = PlayerSettingsRepository.uiState.value.subtitleStyle
        PlayerSettingsRepository.setSubtitleStyle(style.copy(outlineEnabled = enabled))
    }
}
