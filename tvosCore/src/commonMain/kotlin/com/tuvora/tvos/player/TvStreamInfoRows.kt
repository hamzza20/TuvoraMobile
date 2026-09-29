package com.tuvora.tvos.player

import com.nuvio.app.features.player.PlayerStreamInfo
import com.nuvio.app.features.player.StreamInfoFormat

/** One labelled value in the stream info panel. */
data class TvInfoRow(val label: String, val value: String)

/** A titled block of rows (NuvioTV StreamInfoOverlay: SOURCE / VIDEO / AUDIO / SUBTITLE). */
data class TvInfoSection(val title: String, val rows: List<TvInfoRow>)

/**
 * NuvioTV's stream info panel as data: rows only for facts the engine reported (live MPEG-TS
 * declares little, so short sections are normal), formatted with the phone's StreamInfoFormat.
 */
object TvStreamInfoRows {
    fun sections(info: PlayerStreamInfo, sourceName: String?, streamTitle: String?, subtitle: String?): List<TvInfoSection> = buildList {
        val source = listOfNotNull(
            sourceName?.takeIf { it.isNotBlank() }?.let { TvInfoRow("Source", it) },
            streamTitle?.takeIf { it.isNotBlank() }?.let { TvInfoRow("Name", it) },
            info.playerEngine?.let { TvInfoRow("Player Engine", it) },
        )
        if (source.isNotEmpty()) add(TvInfoSection("SOURCE", source))
        val video = listOfNotNull(
            info.videoCodec?.let { TvInfoRow("Codec", it) },
            StreamInfoFormat.resolution(info.videoWidth, info.videoHeight)?.let { TvInfoRow("Resolution", it) },
            StreamInfoFormat.frameRate(info.videoFrameRate)?.let { TvInfoRow("Frame Rate", "$it fps") },
            bitrate(info.videoBitrate)?.let { TvInfoRow("Bitrate", it) },
        )
        if (video.isNotEmpty()) add(TvInfoSection("VIDEO", video))
        val audio = listOfNotNull(
            info.audioCodec?.let { TvInfoRow("Codec", it) },
            info.audioChannelCount?.takeIf { it > 0 }?.let { TvInfoRow("Channels", channels(it)) },
            StreamInfoFormat.sampleRate(info.audioSampleRate)?.let { TvInfoRow("Sample Rate", "$it kHz") },
            bitrate(info.audioBitrate)?.let { TvInfoRow("Bitrate", it) },
        )
        if (audio.isNotEmpty()) add(TvInfoSection("AUDIO", audio))
        subtitle?.takeIf { it.isNotBlank() }?.let { add(TvInfoSection("SUBTITLE", listOf(TvInfoRow("Name", it)))) }
    }

    private fun bitrate(bps: Int?): String? = StreamInfoFormat.bitrate(bps)?.let { if (it.isMegabits) "${it.value} Mbps" else "${it.value} kbps" }

    private fun channels(count: Int): String = StreamInfoFormat.channelLayout(count) ?: when (count) {
        1 -> "Mono"
        2 -> "Stereo"
        else -> "$count ch"
    }
}
