package com.tuvora.tvos.player

import com.nuvio.app.features.player.normalizeLanguageCode

/** One audio or subtitle track as the player overlay lists it. */
data class TvTrack(val id: Int, val label: String, val language: String, val selected: Boolean)

/**
 * Which subtitle track to turn on for the viewer's preferred languages (targets resolved by the shared
 * resolvePreferredSubtitleLanguageTargets). Pure, so tested without a player.
 */
object TvTrackLanguagePolicy {
    /** Track id to select, [OFF] to turn subtitles off, or null to leave the engine's choice alone. */
    fun subtitleChoice(tracks: List<TvTrack>, targets: List<String>, preferenceIsNone: Boolean): Int? {
        if (targets.isEmpty()) return if (preferenceIsNone) OFF else null
        for (target in targets) {
            tracks.firstOrNull { matches(it, target) }?.let { return it.id }
        }
        return if (preferenceIsNone) OFF else null
    }

    const val OFF = -1

    private fun matches(track: TvTrack, target: String): Boolean {
        val lang = normalizeLanguageCode(track.language) ?: normalizeLanguageCode(track.label) ?: return false
        return lang == target || lang.substringBefore('-') == target.substringBefore('-')
    }
}
