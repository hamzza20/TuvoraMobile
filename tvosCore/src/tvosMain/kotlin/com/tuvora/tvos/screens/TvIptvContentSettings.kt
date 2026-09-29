package com.tuvora.tvos.screens

import com.nuvio.app.features.iptv.CATCH_UP_CORRECTION_MAX_MINUTES
import com.nuvio.app.features.iptv.CATCH_UP_CORRECTION_MIN_MINUTES
import com.nuvio.app.features.iptv.CatchUpEpgRepository
import com.nuvio.app.features.iptv.EpgSourceLadder
import com.nuvio.app.features.iptv.XtreamRepository
import com.nuvio.app.features.iptv.content.IptvContentDb
import com.nuvio.app.features.iptv.overlay.IptvHiddenItems

/**
 * Per-playlist Content & Categories and the catch-up / guide options, over the same shared
 * [XtreamRepository.updateOptions] the phone's XtreamContentSettingsPage uses — so every edit is
 * persisted and recorded as a synced option edit (B04), never a local-only change.
 */
object TvIptvContentSettings {
    private fun account(id: String) = XtreamRepository.uiState.value.accounts.firstOrNull { it.id == id }

    suspend fun categories(accountId: String, type: String): List<TvCategoryItem> {
        val account = account(accountId) ?: return emptyList()
        return IptvHiddenItems.categoryNames(account, type).map { TvCategoryItem(it.id, it.name.ifBlank { "Other" }) }
    }

    fun setTypeEnabled(accountId: String, type: String, enabled: Boolean) =
        XtreamRepository.updateOptions(accountId) { acc ->
            acc.copy(contentTypes = if (enabled) acc.contentTypes + type else acc.contentTypes - type)
        }

    /** Select All (null = all, including later ones) / Deselect All (empty). */
    fun setSelection(accountId: String, type: String, selection: List<String>?) =
        XtreamRepository.updateOptions(accountId) { acc ->
            acc.copy(categorySelections = acc.categorySelections.withType(type, selection))
        }

    /** One toggle, composed inside the store transform against the LATEST selection. */
    fun toggleCategory(accountId: String, type: String, allIds: List<String>, categoryId: String, checked: Boolean) =
        XtreamRepository.updateOptions(accountId) { acc ->
            val next = TvIptvContentPolicy.toggle(acc.categorySelections.forType(type), allIds, categoryId, checked)
            acc.copy(categorySelections = acc.categorySelections.withType(type, next))
        }

    fun setPreferM3u8(accountId: String, prefer: Boolean) =
        XtreamRepository.updateOptions(accountId) { it.copy(catchUpPreferM3u8 = prefer) }

    fun setCatchUpCorrection(accountId: String, minutes: Int) {
        XtreamRepository.updateOptions(accountId) {
            it.copy(catchUpTimeCorrectionMinutes = minutes.coerceIn(CATCH_UP_CORRECTION_MIN_MINUTES, CATCH_UP_CORRECTION_MAX_MINUTES))
        }
        // The measured panel offset is cached per session and the correction rides on top of it.
        CatchUpEpgRepository.forget(accountId)
    }

    suspend fun setGuideOffset(accountId: String, minutes: Int) {
        XtreamRepository.updateOptions(accountId) {
            it.copy(guideEpgCorrectionMinutes = minutes.coerceIn(CATCH_UP_CORRECTION_MIN_MINUTES, CATCH_UP_CORRECTION_MAX_MINUTES))
        }
        // Stored rows were corrected under the old offset and the fetch gate would keep them for
        // hours: open the stamps so the next look refetches, and forget sources measured under it.
        runCatching { IptvContentDb.resetEpgFetchStamps(accountId) }
        EpgSourceLadder.sessionMemory.forgetAccount(accountId)
    }
}
