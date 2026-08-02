package com.stephane.naviplayer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Episode(
    val id: String,
    val title: String,
    val url: String,
    val published: String,
    val durationSec: Int,
)

data class Feed(
    val id: String,
    val url: String,
    val title: String,
    val episodes: List<Episode>,
    val refreshedAt: Long = 0L,
)

/**
 * Subscriptions and their cached episodes, as a JSON file.
 *
 * Navidrome answers every Subsonic podcast endpoint with HTTP 501, so podcasts
 * cannot come from the server the way music does - the app subscribes to feeds
 * itself and streams episodes straight from whoever hosts them.
 *
 * Parsed once and held in memory for the process. That is not an optimisation:
 * resolving a playback URL happens per queue item on the main thread, so
 * re-reading this file each time meant one tap on an episode could trigger
 * hundreds of full-file parses and hang the app.
 */
class PodcastStore(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)

    companion object {
        private const val FILE_NAME = "podcasts.json"

        /** Some feeds carry thousands of episodes; keeping all of them in
         *  memory and re-serialising them is what makes a big feed painful. */
        private const val MAX_EPISODES = 200

        /** Feed and episode ids must survive being embedded in a media id, so
         *  no slashes: a podcast guid is often a URL. */
        fun idOf(key: String): String = Integer.toHexString(key.hashCode())

        /** Shared across the service and the activity, which are one process. */
        @Volatile
        private var cache: List<Feed>? = null

        @Synchronized
        private fun put(feeds: List<Feed>) {
            cache = feeds
        }
    }

    fun feeds(): List<Feed> {
        cache?.let { return it }
        val loaded = readFromDisk()
        put(loaded)
        return loaded
    }

    fun feed(id: String): Feed? = feeds().firstOrNull { it.id == id }

    /** Adds the feed, or replaces its episodes if it is already subscribed. */
    @Synchronized
    fun save(feed: Feed) {
        val trimmed = feed.copy(
            episodes = feed.episodes.take(MAX_EPISODES),
            refreshedAt = System.currentTimeMillis(),
        )
        val merged = feeds().filter { it.id != trimmed.id } + trimmed
        put(merged)
        writeAll(merged)
    }

    @Synchronized
    fun remove(id: String) {
        val remaining = feeds().filter { it.id != id }
        put(remaining)
        writeAll(remaining)
    }

    private fun readFromDisk(): List<Feed> {
        if (!file.exists()) return emptyList()
        return try {
            val root = JSONArray(file.readText())
            (0 until root.length()).map { readFeed(root.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun writeAll(feeds: List<Feed>) {
        val root = JSONArray()
        for (feed in feeds) {
            val episodes = JSONArray()
            for (episode in feed.episodes) {
                episodes.put(
                    JSONObject()
                        .put("id", episode.id)
                        .put("title", episode.title)
                        .put("url", episode.url)
                        .put("published", episode.published)
                        .put("duration", episode.durationSec)
                )
            }
            root.put(
                JSONObject()
                    .put("id", feed.id)
                    .put("url", feed.url)
                    .put("title", feed.title)
                    .put("refreshedAt", feed.refreshedAt)
                    .put("episodes", episodes)
            )
        }
        try {
            file.writeText(root.toString())
        } catch (e: Exception) {
            // An unwritable cache is not worth taking the app down for
        }
    }

    private fun readFeed(node: JSONObject): Feed {
        val episodesNode = node.optJSONArray("episodes") ?: JSONArray()
        val episodes = (0 until episodesNode.length()).map { i ->
            val e = episodesNode.getJSONObject(i)
            Episode(
                id = e.getString("id"),
                title = e.optString("title", "Untitled"),
                url = e.optString("url", ""),
                published = e.optString("published", ""),
                durationSec = e.optInt("duration", 0),
            )
        }
        return Feed(
            id = node.getString("id"),
            url = node.optString("url", ""),
            title = node.optString("title", "Untitled feed"),
            episodes = episodes,
            refreshedAt = node.optLong("refreshedAt", 0L),
        )
    }
}
