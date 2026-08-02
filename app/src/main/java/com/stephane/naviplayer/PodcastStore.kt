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
)

/**
 * Subscriptions and their cached episodes, as a JSON file.
 *
 * Navidrome answers every Subsonic podcast endpoint with HTTP 501, so podcasts
 * cannot come from the server the way music does - the app subscribes to feeds
 * itself and streams episodes straight from whoever hosts them.
 *
 * Deliberately a flat file rather than Room: this is a handful of feeds, and a
 * database would drag KSP into the build for no gain.
 */
class PodcastStore(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)

    companion object {
        private const val FILE_NAME = "podcasts.json"

        /** Feed and episode ids must survive being embedded in a media id, so
         *  no slashes: a podcast guid is often a URL. */
        fun idOf(key: String): String = Integer.toHexString(key.hashCode())
    }

    @Synchronized
    fun feeds(): List<Feed> {
        if (!file.exists()) return emptyList()
        return try {
            val root = JSONArray(file.readText())
            (0 until root.length()).map { readFeed(root.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun feed(id: String): Feed? = feeds().firstOrNull { it.id == id }

    /** Adds the feed, or replaces its episodes if it is already subscribed. */
    @Synchronized
    fun save(feed: Feed) {
        val existing = feeds().filter { it.id != feed.id }
        writeAll(existing + feed)
    }

    @Synchronized
    fun remove(id: String) {
        writeAll(feeds().filter { it.id != id })
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
                    .put("episodes", episodes)
            )
        }
        file.writeText(root.toString())
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
        )
    }
}
