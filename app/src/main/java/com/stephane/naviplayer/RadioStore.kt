package com.stephane.naviplayer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Station(
    val id: String,
    val name: String,
    val streamUrl: String,
    val homepageUrl: String = "",
)

/**
 * The station list, mirrored from Navidrome to a JSON file.
 *
 * Stations live on the server - Navidrome implements the Subsonic internet
 * radio endpoints properly, unlike its podcast ones - so this is a cache, not
 * the source of truth. It exists because resolving a stream URL happens on the
 * application thread just before playback, where a network call would throw.
 * Holding the list in memory for the process, the way [PodcastStore] does, also
 * means starting a station from Continue listening works before the radio list
 * has been opened.
 */
class RadioStore(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)

    companion object {
        private const val FILE_NAME = "radio.json"

        /** Shared across the service and the activity, which are one process. */
        @Volatile
        private var cache: List<Station>? = null

        @Synchronized
        private fun put(stations: List<Station>) {
            cache = stations
        }
    }

    fun stations(): List<Station> {
        cache?.let { return it }
        val loaded = readFromDisk()
        put(loaded)
        return loaded
    }

    fun station(id: String): Station? = stations().firstOrNull { it.id == id }

    /** The server owns the list, so a fetch replaces it wholesale. */
    @Synchronized
    fun replaceAll(stations: List<Station>) {
        put(stations)
        writeAll(stations)
    }

    private fun readFromDisk(): List<Station> {
        if (!file.exists()) return emptyList()
        return try {
            val root = JSONArray(file.readText())
            (0 until root.length()).map { i ->
                val s = root.getJSONObject(i)
                Station(
                    id = s.getString("id"),
                    name = s.optString("name", "Untitled station"),
                    streamUrl = s.optString("streamUrl", ""),
                    homepageUrl = s.optString("homepageUrl", ""),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun writeAll(stations: List<Station>) {
        val root = JSONArray()
        for (station in stations) {
            root.put(
                JSONObject()
                    .put("id", station.id)
                    .put("name", station.name)
                    .put("streamUrl", station.streamUrl)
                    .put("homepageUrl", station.homepageUrl)
            )
        }
        try {
            file.writeText(root.toString())
        } catch (e: Exception) {
            // An unwritable cache is not worth taking the app down for
        }
    }
}
