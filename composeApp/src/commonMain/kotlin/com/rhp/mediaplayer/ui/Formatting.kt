package com.rhp.mediaplayer.ui

/**
 * Duration as a clock reading.
 *
 * Only an unknown duration gets the placeholder. Zero is a real position -- the
 * start of a track whose length is already known -- and rendering that as
 * "--:--" would make a freshly cued song look like it had failed to load.
 */
fun formatDuration(milliseconds: Long?): String {
    if (milliseconds == null || milliseconds < 0L) return "--:--"

    val totalSeconds = milliseconds / 1000
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600

    val paddedSeconds = seconds.toString().padStart(2, '0')
    return if (hours > 0) {
        "$hours:${minutes.toString().padStart(2, '0')}:$paddedSeconds"
    } else {
        "$minutes:$paddedSeconds"
    }
}

/** Count with a noun, e.g. "42 首". */
fun formatSongCount(count: Int): String = "$count 首"
