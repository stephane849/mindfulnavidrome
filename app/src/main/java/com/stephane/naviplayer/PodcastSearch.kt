package com.stephane.naviplayer

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class PodcastResult(
    val name: String,
    val author: String,
    val feedUrl: String,
    val episodeCount: Int,
)

/**
 * Podcast directory lookup through Apple's iTunes Search API, which is public,
 * needs no key or account, and returns the RSS feed URL directly - which is the
 * only field that actually matters here, since subscribing is just handing that
 * URL to [PodcastFeed].
 */
object PodcastSearch {

    private const val ENDPOINT = "https://itunes.apple.com/search"
    private const val LIMIT = 25
    private const val TIMEOUT_MS = 15_000

    fun search(term: String): List<PodcastResult> {
        val query = URLEncoder.encode(term.trim(), "UTF-8")
        val connection = URL("$ENDPOINT?media=podcast&limit=$LIMIT&term=$query")
            .openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "NaviPlayer")

        if (connection.responseCode !in 200..299) {
            throw Exception("HTTP ${connection.responseCode}")
        }

        val body = connection.inputStream.bufferedReader().use { it.readText() }
        val results = JSONObject(body).optJSONArray("results") ?: return emptyList()

        val out = mutableListOf<PodcastResult>()
        for (i in 0 until results.length()) {
            val node = results.getJSONObject(i)
            val feedUrl = node.optString("feedUrl", "")
            // Entries without a feed cannot be subscribed to, so drop them
            if (feedUrl.isEmpty()) continue
            out.add(
                PodcastResult(
                    name = node.optString("collectionName", "Untitled"),
                    author = node.optString("artistName", ""),
                    feedUrl = feedUrl,
                    episodeCount = node.optInt("trackCount", 0),
                )
            )
        }
        return out
    }
}
