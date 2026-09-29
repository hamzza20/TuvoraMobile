package com.tuvora.tvos.screens

/** A row of "Hidden channels & groups": what it is, and the id the facade unhides it by. */
data class TvHiddenItem(val id: String, val name: String, val kindLabel: String)

/** A guide region as the picker lists it. */
data class TvEpgRegion(val name: String, val flag: String, val channelCount: Int)

/**
 * NuvioTV XtreamSettingsScreen's IPTV settings copy and summaries (Hidden channels & groups, Guide
 * regions), pure so the wording rules are tested without the overlay or the EPG mirror.
 */
object TvIptvSettingsPolicy {
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
