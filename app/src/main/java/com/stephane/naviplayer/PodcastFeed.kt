package com.stephane.naviplayer

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal RSS reader, using the platform pull parser so nothing is added to the
 * dependency list. Reads only what a player needs: the channel title, and each
 * item's title, enclosure URL, publication date and duration.
 */
object PodcastFeed {

    private const val TIMEOUT_MS = 15_000

    fun fetch(feedUrl: String): Feed {
        val connection = URL(feedUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "NaviPlayer")

        if (connection.responseCode !in 200..299) {
            throw Exception("HTTP ${connection.responseCode}")
        }
        return connection.inputStream.use { parse(feedUrl, it) }
    }

    private fun parse(feedUrl: String, input: InputStream): Feed {
        val parser = Xml.newPullParser()
        // Namespaces off, so itunes:duration arrives as a plain tag name. Also
        // leaves document declarations unprocessed, so a feed cannot pull in
        // external entities.
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        var channelTitle = ""
        val episodes = mutableListOf<Episode>()

        var inItem = false
        var title = ""
        var url = ""
        var guid = ""
        var published = ""
        var duration = 0

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name.lowercase()) {
                    "item", "entry" -> {
                        inItem = true
                        title = ""; url = ""; guid = ""; published = ""; duration = 0
                    }
                    "title" -> {
                        val text = safeText(parser)
                        if (inItem) title = text else if (channelTitle.isEmpty()) channelTitle = text
                    }
                    "enclosure" -> if (inItem && url.isEmpty()) {
                        url = parser.getAttributeValue(null, "url") ?: ""
                    }
                    // Atom feeds carry the audio on a link with a rel/type pair
                    "link" -> if (inItem && url.isEmpty()) {
                        val type = parser.getAttributeValue(null, "type") ?: ""
                        if (type.startsWith("audio/")) {
                            url = parser.getAttributeValue(null, "href") ?: ""
                        }
                    }
                    "guid", "id" -> if (inItem && guid.isEmpty()) guid = safeText(parser)
                    "pubdate", "published", "updated" ->
                        if (inItem && published.isEmpty()) published = safeText(parser)
                    "itunes:duration", "duration" ->
                        if (inItem && duration == 0) duration = parseDuration(safeText(parser))
                }
            } else if (event == XmlPullParser.END_TAG) {
                val name = parser.name.lowercase()
                if (name == "item" || name == "entry") {
                    inItem = false
                    if (url.isNotEmpty()) {
                        val key = guid.ifEmpty { url }
                        episodes.add(
                            Episode(
                                id = PodcastStore.idOf(key),
                                title = title.ifEmpty { "Untitled episode" },
                                url = url,
                                published = published,
                                durationSec = duration,
                            )
                        )
                    }
                }
            }
            event = parser.next()
        }

        return Feed(
            id = PodcastStore.idOf(feedUrl),
            url = feedUrl,
            title = channelTitle.ifEmpty { feedUrl },
            // Feeds are conventionally newest first; keep whatever order the
            // publisher chose rather than guessing at date formats.
            episodes = episodes,
        )
    }

    /** nextText throws on elements that turn out to have children; treat those as empty. */
    private fun safeText(parser: XmlPullParser): String =
        try {
            parser.nextText().trim()
        } catch (e: Exception) {
            ""
        }

    /** Accepts "3753", "62:33" and "1:02:33". */
    private fun parseDuration(raw: String): Int {
        if (raw.isEmpty()) return 0
        val parts = raw.split(':')
        return try {
            when (parts.size) {
                1 -> parts[0].trim().toDouble().toInt()
                2 -> parts[0].trim().toInt() * 60 + parts[1].trim().toInt()
                3 -> parts[0].trim().toInt() * 3600 +
                    parts[1].trim().toInt() * 60 +
                    parts[2].trim().toInt()
                else -> 0
            }
        } catch (e: Exception) {
            0
        }
    }
}
