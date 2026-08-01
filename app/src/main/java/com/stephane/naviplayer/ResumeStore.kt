package com.stephane.naviplayer

import android.content.Context
import java.util.Locale

/**
 * Remembers how far into a track the listener got. Classes and lectures are the
 * point of this: an hour-long file is worth resuming, a three-minute song is
 * not, so anything shorter than [MIN_DURATION_MS] is deliberately ignored.
 *
 * Kept in its own prefs file so clearing playback history never touches the
 * saved credentials.
 */
class ResumeStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS = "navi_resume"

        /** Shorter than this is a song, and songs should start at the beginning. */
        private const val MIN_DURATION_MS = 5 * 60 * 1000L

        /** Below this, restarting is friendlier than resuming. */
        private const val MIN_POSITION_MS = 30 * 1000L

        /** Within this of the end, the track counts as finished. */
        const val END_SLACK_MS = 30 * 1000L

        private const val KEY_LAST_ID = "last_media_id"
        private const val KEY_LAST_TITLE = "last_title"
    }

    /**
     * Stores the position, or clears it when the track is too short to be worth
     * resuming, barely started, or effectively finished.
     */
    fun save(songId: String, positionMs: Long, durationMs: Long) {
        val worthKeeping = durationMs >= MIN_DURATION_MS &&
            positionMs >= MIN_POSITION_MS &&
            positionMs < durationMs - END_SLACK_MS

        if (worthKeeping) {
            prefs.edit().putLong(songId, positionMs).apply()
        } else {
            clear(songId)
        }
    }

    fun position(songId: String): Long = prefs.getLong(songId, 0L)

    fun clear(songId: String) {
        prefs.edit().remove(songId).apply()
    }

    // ---------- Last played, for the "Continue listening" row ----------

    fun saveLast(mediaId: String, title: String) {
        prefs.edit()
            .putString(KEY_LAST_ID, mediaId)
            .putString(KEY_LAST_TITLE, title)
            .apply()
    }

    fun lastMediaId(): String = prefs.getString(KEY_LAST_ID, "") ?: ""

    fun lastTitle(): String = prefs.getString(KEY_LAST_TITLE, "") ?: ""
}

/** m:ss for songs, h:mm:ss once a lecture runs past the hour. */
fun formatClock(seconds: Int): String {
    if (seconds <= 0) return ""
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, secs)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, secs)
    }
}

fun formatClockMs(millis: Long): String = formatClock((millis / 1000).toInt())
