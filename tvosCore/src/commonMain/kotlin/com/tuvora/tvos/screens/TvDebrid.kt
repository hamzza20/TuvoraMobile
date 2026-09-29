package com.tuvora.tvos.screens

import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.debrid.DebridStreamFormatterDefaults
import com.nuvio.app.features.debrid.DebridStreamPreferences

/** A result-size window in GB; 0 on either side means "no bound". */
data class TvSizeRange(val minGb: Int, val maxGb: Int)

/**
 * Settings → Connected Services' result limits and name templates (NuvioTV DebridSettingsScreen:
 * DebridMaxResultsDialog, DebridSizeRangeDialog, sizeRangeLabel, formatter reset). The option lists
 * and labels are pure; the setters write through the shared DebridSettingsRepository (Swift cannot
 * build a DebridStreamPreferences copy with its thirty fields, so the copy happens here).
 */
object TvDebrid {
    /** NuvioTV DebridMaxResultsDialog: 0 = no limit. Shared by max results and the per-group limits. */
    val limitOptions: List<Int> = listOf(0, 5, 10, 20, 50)

    /** NuvioTV DebridSizeRangeDialog. */
    val sizeRangeOptions: List<TvSizeRange> = listOf(
        TvSizeRange(0, 0), TvSizeRange(0, 5), TvSizeRange(0, 10),
        TvSizeRange(5, 20), TvSizeRange(10, 50), TvSizeRange(20, 100),
    )

    /** "All streams" / "%d streams" (debrid_stream_max_results_*). */
    fun limitLabel(value: Int): String = if (value <= 0) "All streams" else "$value streams"

    /** sizeRangeLabel: Any / Up to 10GB / 5GB+ / 5-20GB. */
    fun sizeRangeLabel(minGb: Int, maxGb: Int): String = when {
        minGb <= 0 && maxGb <= 0 -> "Any"
        minGb <= 0 -> "Up to ${maxGb}GB"
        maxGb <= 0 -> "${minGb}GB+"
        else -> "$minGb-${maxGb}GB"
    }

    fun withMaxPerResolution(prefs: DebridStreamPreferences, value: Int): DebridStreamPreferences =
        prefs.copy(maxPerResolution = value.coerceAtLeast(0))

    fun withMaxPerQuality(prefs: DebridStreamPreferences, value: Int): DebridStreamPreferences =
        prefs.copy(maxPerQuality = value.coerceAtLeast(0))

    fun withSizeRange(prefs: DebridStreamPreferences, range: TvSizeRange): DebridStreamPreferences =
        prefs.copy(sizeMinGb = range.minGb.coerceAtLeast(0), sizeMaxGb = range.maxGb.coerceAtLeast(0))

    /** Whether a saved template is the stock one (blank also means "use the default"). */
    fun isDefaultNameTemplate(template: String): Boolean =
        template.isBlank() || template.trim() == DebridStreamFormatterDefaults.NAME_TEMPLATE

    fun isDefaultDescriptionTemplate(template: String): Boolean =
        template.isBlank() || template.trim() == DebridStreamFormatterDefaults.DESCRIPTION_TEMPLATE

    val defaultNameTemplate: String get() = DebridStreamFormatterDefaults.NAME_TEMPLATE

    // --- writes -------------------------------------------------------------------------------

    private fun prefs() = DebridSettingsRepository.snapshot().streamPreferences

    fun setMaxPerResolution(value: Int) = DebridSettingsRepository.setStreamPreferences(withMaxPerResolution(prefs(), value))
    fun setMaxPerQuality(value: Int) = DebridSettingsRepository.setStreamPreferences(withMaxPerQuality(prefs(), value))
    fun setSizeRange(minGb: Int, maxGb: Int) = DebridSettingsRepository.setStreamPreferences(withSizeRange(prefs(), TvSizeRange(minGb, maxGb)))
    fun setNameTemplate(value: String) = DebridSettingsRepository.setStreamNameTemplate(value)
    fun setDescriptionTemplate(value: String) = DebridSettingsRepository.setStreamDescriptionTemplate(value)
    fun resetTemplates() = DebridSettingsRepository.resetStreamTemplates()
}
