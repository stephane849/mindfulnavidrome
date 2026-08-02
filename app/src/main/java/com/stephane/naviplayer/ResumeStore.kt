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
        private const val MIN_DURATION_MS = 2 * 60 * 1000L

        /** Below this, restarting is friendlier than resuming. */
        private const val MIN_POSITION_MS = 15 * 1000L

        /** Within this of the end, the track counts as finished. */
        const val END_SLACK_MS = 30 * 1000L

        private const val KEY_LAST_ID = "last_media_id"
        private const val KEY_LAST_TITLE = "last_title"
        private const val KEY_STACK = "browse_stack"

        private const val UNIT_SEPARATOR = "\u0001"
        private const val RECORD_SEPARATOR = "\u0002"
    }

    /**
     * Stores the position, clears it once the track has effectively finished,
     * and otherwise leaves whatever is already stored alone.
     *
     * The distinction matters: an unknown duration used to fall through to
     * clear(), so a save issued before the player knew how long the file was
     * would wipe a perfectly good position rather than skip the write.
     */
    fun save(songId: String, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0L) return

        val finished = positionMs >= durationMs - END_SLACK_MS
        val tooShort = durationMs < MIN_DURATION_MS
        val barelyStarted = positionMs < MIN_POSITION_MS

        when {
            finished -> clear(songId)
            tooShort || barelyStarted -> return
            else -> prefs.edit().putLong(songId, positionMs).apply()
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

    // ---------- Where you were browsing ----------

    /** Keeps the breadcrumb so reopening lands on the screen you left, not the root. */
    fun saveStack(entries: List<Pair<String, String>>) {
        val encoded = entries.joinToString(RECORD_SEPARATOR) { (id, title) ->
            id + UNIT_SEPARATOR + title
        }
        prefs.edit().putString(KEY_STACK, encoded).apply()
    }

    fun stack(): List<Pair<String, String>> {
        val encoded = prefs.getString(KEY_STACK, "") ?: ""
        if (encoded.isEmpty()) return emptyList()
        return encoded.split(RECORD_SEPARATOR).mapNotNull { record ->
            val parts = record.split(UNIT_SEPARATOR)
            if (parts.size == 2 && parts[0].isNotEmpty()) parts[0] to parts[1] else null
        }
    }
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
