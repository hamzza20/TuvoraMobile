package com.nuvio.app.features.epg

import com.nuvio.app.features.iptv.epg.XmltvStreamingParser
import com.nuvio.app.features.iptv.epg.normalizeChannelId
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.test.Test

/**
 * B10 measurement harness — runs the REAL matcher (this module's code, not a python port) over a
 * real-shaped lineup + real XMLTV guides and reports the match rate four ways plus the top miss
 * reasons. No device, no panel credentials.
 *
 * Skipped (prints one line) unless `EPG_HARNESS_DIR` points at a data directory:
 *
 *     research/epg-matching/fetch_harness_data.sh <dir>     # public XMLTV + M3U + the panel names
 *     EPG_HARNESS_DIR=<dir> ./gradlew :composeApp:testAndroidHostTest \
 *         --tests 'com.nuvio.app.features.epg.EpgMatchHarnessTest' -i
 *
 * Data layout: `<dir>/guide/` any `*.xml` / `*.xml.gz` (merged into ONE guide, as a playlist's
 * single EPG source), `<dir>/panel_names.csv` (a real panel's 11,442 live channel names — ids
 * blank, as a Starshare-class panel leaves 94% of them) and `.m3u` files in `<dir>` (real M3U lineups with
 * real tvg-ids). The report also lands in `<dir>/report.txt`.
 *
 * Columns:
 *  - before   — today's store lane: exact `epg_channel_id`/`tvg-id` after case/space folding.
 *  - tivimate — TiviMate-style: id, then the channel name case-insensitively equal to a display-name.
 *  - after    — [GuideChannelMatcher] (id → cleaned name tiers; no fuzzy), what this branch ships.
 *  - +fuzzy   — the same with the review-tier similarity walk on (NOT shipped on the store lane).
 */
class EpgMatchHarnessTest {

    private data class Lineup(val label: String, val channels: List<GuideChannelMatcher.LineupChannel>)

    @Test
    fun matchRateReport() {
        val dir = System.getenv("EPG_HARNESS_DIR")?.let(::File)
        if (dir == null || !dir.isDirectory) {
            println("EpgMatchHarnessTest: EPG_HARNESS_DIR not set — skipped")
            return
        }
        val guide = loadGuide(File(dir, "guide"))
        val lineups = buildList {
            File(dir, "panel_names.csv").takeIf { it.isFile }?.let { add(Lineup("panel names (ids blank)", loadPanelCsv(it))) }
            dir.listFiles { f -> f.name.endsWith(".m3u") }.orEmpty().sortedBy { it.name }
                .forEach { add(Lineup("${it.name} (real tvg-ids)", loadM3u(it))) }
        }
        val out = StringBuilder()
        out.appendLine("EPG match harness — guide: ${guide.size} channels from ${File(dir, "guide").list()?.size ?: 0} file(s)")
        for (l in lineups) out.append(report(l, guide))
        println(out)
        File(dir, "report.txt").writeText(out.toString())
    }

    private fun report(l: Lineup, guide: List<GuideChannelMatcher.GuideChannel>): String {
        val sb = StringBuilder()
        val lineup = l.channels
        val eligible = lineup.filter { GuideChannelMatcher.isEligible(it.name) }
        val eligibleIds = eligible.map { it.streamId }.toHashSet()
        val guideIds = guide.map { normalizeChannelId(it.id) }.toHashSet()
        val namesCf = HashMap<String, String>()
        for (g in guide) for (n in g.names) namesCf.putIfAbsent(casefold(n), normalizeChannelId(g.id))

        val before = lineup.filter { ch -> ch.epgId?.let { normalizeChannelId(it) in guideIds } == true }.map { it.streamId }.toHashSet()
        val tivimate = lineup.filter { ch ->
            ch.epgId?.let { normalizeChannelId(it) in guideIds } == true || casefold(ch.name) in namesCf
        }.map { it.streamId }.toHashSet()
        val t0 = System.nanoTime()
        val after = GuideChannelMatcher.match(lineup, guide)
        val afterMs = (System.nanoTime() - t0) / 1_000_000
        val afterSet = after.assignments.map { it.streamId }.toHashSet()
        val fuzzy = GuideChannelMatcher.match(lineup, guide, allowFuzzy = true)
        val fuzzySet = fuzzy.assignments.map { it.streamId }.toHashSet()

        // The mirror path (EpgChannelIndex directly, fuzzy on) with and without the F10 cleaner.
        val index = EpgChannelIndex.build(guide.map { it.id to it.names })
        val mirrorLegacy = lineup.count { index.match(it.name, it.epgId, rules = null) != null }
        val mirrorF10 = lineup.count { index.match(it.name, it.epgId) != null }

        fun pct(set: Set<Int>) = "%5.1f%% (%d)".format(100.0 * set.count { it in eligibleIds } / maxOf(1, eligible.size), set.count { it in eligibleIds })
        sb.appendLine()
        sb.appendLine("== ${l.label}: ${lineup.size} channels, ${eligible.size} eligible ==")
        sb.appendLine("  eligible match rate   before ${pct(before)} | tivimate ${pct(tivimate)} | after ${pct(afterSet)} | +fuzzy ${pct(fuzzySet)}")
        sb.appendLine("  after tiers           ${after.census}  (match took ${afterMs} ms)")
        sb.appendLine("  mirror path (all ch.) legacy=$mirrorLegacy  with F10 cleaner=$mirrorF10  (${mirrorF10 - mirrorLegacy} more)")

        // Why `before` misses: the id story.
        val beforeMiss = eligible.filter { it.streamId !in before }
        val idAbsent = beforeMiss.count { it.epgId.isNullOrBlank() }
        sb.appendLine("  before misses         ${beforeMiss.size}: tvg-id absent=$idAbsent, tvg-id present but not in guide=${beforeMiss.size - idAbsent}")

        // What made the difference for channels we now match and TiviMate-style does not.
        val gained = after.assignments.filter { it.streamId !in tivimate && it.streamId in eligibleIds }
        val byId = guide.associateBy { normalizeChannelId(it.id) }
        val byStream = lineup.associateBy { it.streamId }
        val reasons = gained.groupingBy { a -> gainReason(byStream.getValue(a.streamId).name, byId[a.guideId]?.names.orEmpty()) }.eachCount()
        sb.appendLine("  after-only wins (${gained.size}) by reason: ${reasons.entries.sortedByDescending { it.value }.joinToString { "${it.key}=${it.value}" }}")
        gained.take(12).forEach { a ->
            sb.appendLine("      ${byStream.getValue(a.streamId).name}  ->  ${byId[a.guideId]?.names?.firstOrNull()} [${a.guideId}] ${a.tier.slug}")
        }

        // What we still miss (eligible) and what fuzzy would add — precision check by eye.
        val miss = eligible.filter { it.streamId !in afterSet }
        val timeshift = miss.count { EpgNorm.isTimeshift(EpgNorm.coreNorm(it.name)) }
        sb.appendLine("  after misses          ${miss.size} (timeshift +1 among them: $timeshift; rest = guide has no such channel or naming beyond the tiers)")
        miss.shuffled(kotlin.random.Random(7)).take(10).forEach { sb.appendLine("      miss: ${it.name}") }
        val fuzzyOnly = fuzzy.assignments.filter { it.streamId !in afterSet && it.streamId in eligibleIds }
        fuzzyOnly.take(8).forEach { a ->
            sb.appendLine("      fuzzy-only: ${byStream.getValue(a.streamId).name}  ->  ${byId[a.guideId]?.names?.firstOrNull()}")
        }
        return sb.toString()
    }

    /** First transformation under which the provider name equals one of the guide's names. */
    private fun gainReason(name: String, guideNames: List<String>): String {
        val targets = guideNames.map { casefold(it) }.toSet()
        fun hit(s: String) = casefold(s) in targets
        return when {
            hit(name) -> "case/diacritics"
            hit(ChannelNameCleaner.clean(name, ChannelNameCleaner.Rules(stripQuality = false, stripDecorations = false))) -> "country prefix"
            hit(ChannelNameCleaner.clean(name, ChannelNameCleaner.Rules(stripCountryPrefix = false, stripDecorations = false))) -> "quality suffix"
            hit(ChannelNameCleaner.clean(name, ChannelNameCleaner.Rules(stripDecorations = false))) -> "prefix+quality"
            hit(ChannelNameCleaner.clean(name)) -> "decorations/brackets"
            hit(EpgNorm.stripPanelNoise(name)) -> "panel noise (bouquet/packager/codec)"
            else -> "normalisation (tokens/squash/word-digits/'&')"
        }
    }

    private fun casefold(s: String) = EpgNorm.baseNorm(s)

    // --- loaders ---------------------------------------------------------------------------------

    private fun loadGuide(dir: File): List<GuideChannelMatcher.GuideChannel> {
        val seen = LinkedHashMap<String, GuideChannelMatcher.GuideChannel>()
        dir.listFiles { f -> f.name.endsWith(".xml") || f.name.endsWith(".xml.gz") }.orEmpty().sortedBy { it.name }.forEach { f ->
            val parser = XmltvStreamingParser(
                keepChannelIds = emptySet(),
                onProgramme = {},
                onChannelNames = { id, names -> seen.putIfAbsent(normalizeChannelId(id), GuideChannelMatcher.GuideChannel(id, names)) },
            )
            val input = if (f.name.endsWith(".gz")) GZIPInputStream(f.inputStream()) else f.inputStream()
            input.bufferedReader().use { r ->
                val buf = CharArray(64 * 1024)
                while (true) {
                    val n = r.read(buf)
                    if (n < 0) break
                    parser.feed(String(buf, 0, n))
                }
            }
            parser.finish()
        }
        return seen.values.toList()
    }

    private fun loadPanelCsv(f: File): List<GuideChannelMatcher.LineupChannel> =
        f.readLines().drop(1).mapIndexedNotNull { i, line ->
            val name = csvFirst(line).trim()
            if (name.isEmpty()) null else GuideChannelMatcher.LineupChannel(i, name, epgId = null)
        }

    private fun csvFirst(line: String): String {
        if (!line.startsWith("\"")) return line.substringBefore(',')
        val sb = StringBuilder()
        var i = 1
        while (i < line.length) {
            val c = line[i]
            if (c == '"') {
                if (i + 1 < line.length && line[i + 1] == '"') { sb.append('"'); i += 2; continue }
                break
            }
            sb.append(c); i++
        }
        return sb.toString()
    }

    private val tvgIdAttr = Regex("tvg-id=\"([^\"]*)\"")

    private fun loadM3u(f: File): List<GuideChannelMatcher.LineupChannel> {
        val out = ArrayList<GuideChannelMatcher.LineupChannel>()
        f.readLines().forEach { line ->
            if (!line.startsWith("#EXTINF")) return@forEach
            val id = tvgIdAttr.find(line)?.groupValues?.get(1)
            val name = line.substringAfterLast(",").trim()
            out.add(GuideChannelMatcher.LineupChannel(out.size, name, id))
        }
        return out
    }
}
