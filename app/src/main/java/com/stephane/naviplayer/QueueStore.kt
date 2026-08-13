package com.stephane.naviplayer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class QueueEntry(
    val mediaId: String,
    val title: String,
    val subtitle: String,
    /** 0 when unknown, e.g. an entry saved before this was tracked. */
    val durationMs: Long = 0L,
)

data class SavedQueue(
    val entries: List<QueueEntry>,
    val index: Int,
    val positionMs: Long,
)

/**
 * The playback queue, on disk.
 *
 * Media3 keeps the queue inside the player, which survives the activity but not
 * the service being killed or the device rebooting. Lining up a run of episodes
 * and losing them to a restart is the same class of annoyance as losing a
 * resume position, so the queue is written out as it changes.
 *
 * Titles are stored alongside the ids because the Queue screen has to render
 * before the items have been resolved back into the player.
 */
class QueueStore(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)

    companion object {
        private const val FILE_NAME = "queue.json"

        /** Deep queues are not worth unbounded disk or restore time. */
        private const val MAX_ENTRIES = 300
    }

    @Synchronized
    fun save(entries: List<QueueEntry>, index: Int, positionMs: Long) {
        if (entries.isEmpty()) {
            clear()
            return
        }

        val array = JSONArray()
        for (entry in entries.take(MAX_ENTRIES)) {
            array.put(
                JSONObject()
                    .put("mediaId", entry.mediaId)
                    .put("title", entry.title)
                    .put("subtitle", entry.subtitle)
                    .put("durationMs", entry.durationMs)
            )
        }

        val root = JSONObject()
            .put("entries", array)
            .put("index", index.coerceAtLeast(0))
            .put("position", positionMs.coerceAtLeast(0L))

        try {
            file.writeText(root.toString())
        } catch (e: Exception) {
            // A queue that cannot be written is not worth crashing over
        }
    }

    @Synchronized
    fun load(): SavedQueue? {
        if (!file.exists()) return null
        return try {
            val root = JSONObject(file.readText())
            val array = root.optJSONArray("entries") ?: return null
            val entries = (0 until array.length()).mapNotNull { i ->
                val node = array.getJSONObject(i)
                val mediaId = node.optString("mediaId", "")
                if (mediaId.isEmpty()) {
                    null
                } else {
                    QueueEntry(
                        mediaId = mediaId,
                        title = node.optString("title", ""),
                        subtitle = node.optString("subtitle", ""),
                        durationMs = node.optLong("durationMs", 0L),
                    )
                }
            }
            if (entries.isEmpty()) {
                null
            } else {
                SavedQueue(
                    entries = entries,
                    index = root.optInt("index", 0).coerceIn(0, entries.size - 1),
                    positionMs = root.optLong("position", 0L),
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    @Synchronized
    fun clear() {
        try {
            if (file.exists()) file.delete()
        } catch (e: Exception) {
            // Nothing useful to do
        }
    }
}
