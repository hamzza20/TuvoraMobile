package com.nuvio.app.features.player

/**
 * The resume position handed to mpv AT LOAD TIME, as its per-file `start=` option
 * (`loadfile <url> replace -1 start=<seconds>`).
 *
 * mpv rejects a `seek` issued before playback is initialised (`cmd_seek` fails while
 * `!playback_initialized`), so the post-load seek the runtime used to fire could be dropped and the
 * resumed title started at 0:00 (B59b; reproduced on the iOS simulator 2026-09-27). `start=` is the
 * documented way to open a file mid-way — TV mpv and Desktop already load this way.
 */
internal object MpvStartPosition {

    /** The `start=` load option for [initialPositionMs], or null to play from the beginning. */
    fun loadOption(initialPositionMs: Long?, isLiveStream: Boolean): String? {
        if (isLiveStream) return null
        val ms = initialPositionMs?.takeIf { it > 0L } ?: return null
        return "start=${ms / 1000}.${(ms % 1000).toString().padStart(3, '0')}"
    }
}
