package com.tuvora.tvos.screens

import com.nuvio.app.features.iptv.PlaylistAddress

/** A row of "Hidden channels & groups": what it is, and the id the facade unhides it by. */
data class TvHiddenItem(val id: String, val name: String, val kindLabel: String)

/** A guide region as the picker lists it. */
data class TvEpgRegion(val name: String, val flag: String, val channelCount: Int)

/**
 * NuvioTV XtreamSettingsScreen's IPTV settings copy and summaries (Hidden channels & groups, Guide
 * regions), pure so the wording rules are tested without the overlay or the EPG mirror.
 */
object TvIptvSettingsPolicy {
    /**
     * P4 — a playlist's name as a settings row, hub chip or picker shows it. Older builds named a
     * nameless synced M3U row after its full link (login included), and that name syncs.
     */
    fun playlistName(name: String): String = PlaylistAddress.displayName(name)

    /** P4 — a playlist or backup address on a READ-ONLY row: login masked (B116), shape kept. Editors keep the raw value. */
    fun maskedAddress(url: String): String = PlaylistAddress.masked(url)

    /** hiddenItemKindLabel: "Channel", or the group's content type. */
    fun kindLabel(isChannel: Boolean, contentType: String): String = when {
        isChannel -> "Channel"
        contentType == "movies" -> "Movie group"
        contentType == "series" -> "Series group"
        else -> "Channel group"
    }

    fun hiddenSubtitle(loading: Boolean, count: Int): String = when {
        loading -> "Loading…"
        count == 0 -> "Nothing is hidden in this playlist. In the Live TV guide, hold OK on a channel or a group to hide it."
        else -> "Select one to bring it back."
    }

    /** epgRegionSummaryValue: flags when there are some, else a count; "All (n)" for no preference. */
    fun regionSummary(selected: Set<String>, available: List<TvEpgRegion>): String = when {
        available.isEmpty() -> "—"
        selected.isEmpty() -> "All (${available.size})"
        else -> {
            val flags = available.filter { it.name in selected }.mapNotNull { it.flag.ifEmpty { null } }
            if (flags.isNotEmpty()) flags.take(4).joinToString(" ") + if (selected.size > 4) " +${selected.size - 4}" else ""
            else "${selected.size} selected"
        }
    }

    fun regionDialogSubtitle(selectedCount: Int, total: Int): String =
        if (selectedCount == 0) "Using every region — pick the ones you watch to store less guide data on this device"
        else "$selectedCount of $total selected"
}

/** How a content-type row reads (NuvioTV XtreamContentTypesDialog countText), localized by the UI. */
enum class TvContentCountKind { Hidden, All, Fraction, Selected }

data class TvContentCount(val kind: TvContentCountKind, val selected: Int = 0, val total: Int = 0)

/** A provider category for the checklist. */
data class TvCategoryItem(val id: String, val name: String)

/**
 * NuvioTV's Content & Categories and the catch-up / guide-offset pickers (XtreamSettingsScreen.kt,
 * XtreamSettingsViewModel), pure. A selection of null means "all, including categories added later";
 * an empty list means none.
 */
object TvIptvContentPolicy {
    /** content key → NuvioTV label, in NuvioTV's order. */
    val contentTypes: List<Pair<String, String>> = listOf("live" to "Live TV", "movies" to "Movies", "series" to "Series")

    fun count(enabled: Boolean, selection: List<String>?, total: Int?): TvContentCount = when {
        !enabled -> TvContentCount(TvContentCountKind.Hidden)
        selection == null -> TvContentCount(TvContentCountKind.All)
        total != null -> TvContentCount(TvContentCountKind.Fraction, selection.size, total)
        else -> TvContentCount(TvContentCountKind.Selected, selection.size)
    }

    fun isChecked(selection: List<String>?, categoryId: String): Boolean = selection == null || categoryId in selection

    /**
     * One toggle as an OPERATION against the latest selection (NuvioTV toggleCategory): from "all"
     * the full list is materialized first, so a toggle racing Deselect All yields exactly the
     * toggled id, not a resurrected list.
     */
    fun toggle(current: List<String>?, allIds: List<String>, categoryId: String, checked: Boolean): List<String> {
        val base = current ?: allIds
        return if (checked) (base - categoryId) + categoryId else base - categoryId
    }

    /** "3/12 selected" style subtitle for the checklist (NuvioTV: `${selection?.size ?: total}/$total`). */
    fun selectedOfTotal(selection: List<String>?, total: Int): Pair<Int, Int> = (selection?.size ?: total) to total

    /** −12 h … +14 h in 30-minute steps (CATCHUP_CORRECTION_OPTIONS). */
    fun correctionOptions(): List<Int> = generateSequence(-12 * 60) { it + 30 }.takeWhile { it <= 14 * 60 }.toList()

    /** "+2h", "-1h 30m"; zero is the caller's word ("None (UTC)" for catch-up, "Auto" for the guide). */
    fun offsetText(minutes: Int): String {
        val sign = if (minutes < 0) "-" else "+"
        val abs = kotlin.math.abs(minutes)
        return if (abs % 60 == 0) "$sign${abs / 60}h" else "$sign${abs / 60}h ${abs % 60}m"
    }
}
