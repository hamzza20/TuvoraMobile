package com.tuvora.tvos.player

/**
 * Which engine plays a stream on Apple TV: libmpv, or Apple's AVPlayer.
 *
 * Same split as Android TV's dual-engine pipeline, with AVPlayer in ExoPlayer's role: libmpv plays
 * all live TV and anything AVPlayer can't open (MKV, AVI, raw .ts, external subtitle files);
 * AVPlayer plays VOD it is known to support, which is what gives Dolby Vision, Atmos and the
 * Apple TV's Match Content. AVPlayer is only chosen on positive evidence, because falling back
 * means opening the stream a second time, and some provider links work once.
 */
enum class PlaybackLane { Libmpv, AvPlayer }

/** Settings → Player engine. */
enum class EngineSetting { Auto, Libmpv, AvPlayer }

data class LaneInput(
    val url: String,
    val isLive: Boolean,
    val setting: EngineSetting,
    /** The lane that played this title last time, if any. */
    val remembered: PlaybackLane?,
    val hasExternalSubtitles: Boolean,
    val mimeType: String? = null,
)

object PlaybackLanePolicy {

    private val avPlayerExtensions = setOf("mp4", "m4v", "mov", "m3u8")
    private val avPlayerMimeTypes = setOf(
        "video/mp4", "video/quicktime", "video/x-m4v",
        "application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpegurl",
    )

    fun initial(input: LaneInput): PlaybackLane {
        if (input.isLive) return PlaybackLane.Libmpv
        when (input.setting) {
            EngineSetting.Libmpv -> return PlaybackLane.Libmpv
            EngineSetting.AvPlayer -> return PlaybackLane.AvPlayer
            EngineSetting.Auto -> Unit
        }
        input.remembered?.let { return it }
        if (input.hasExternalSubtitles) return PlaybackLane.Libmpv
        val mime = input.mimeType?.substringBefore(';')?.trim()?.lowercase()
        if (mime != null && mime.isNotEmpty()) {
            return if (mime in avPlayerMimeTypes) PlaybackLane.AvPlayer else PlaybackLane.Libmpv
        }
        return if (extensionOf(input.url) in avPlayerExtensions) PlaybackLane.AvPlayer else PlaybackLane.Libmpv
    }

    /** The lane to retry on after [failed] failed, or null when there is none left. Never ping-pongs. */
    fun escalation(failed: PlaybackLane, alreadyEscalated: Boolean): PlaybackLane? =
        if (failed == PlaybackLane.AvPlayer && !alreadyEscalated) PlaybackLane.Libmpv else null

    internal fun extensionOf(url: String): String? {
        val path = url.substringBefore('#').substringBefore('?')
        val last = path.substringAfterLast('/')
        if (!last.contains('.')) return null
        return last.substringAfterLast('.').lowercase()
    }
}
