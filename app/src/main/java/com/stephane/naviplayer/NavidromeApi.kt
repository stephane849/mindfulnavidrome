package com.stephane.naviplayer

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Artist(val id: String, val name: String, val albumCount: Int)
data class Album(
    val id: String,
    val name: String,
    val artist: String,
    val year: String,
    val songCount: Int
)
data class Playlist(val id: String, val name: String, val songCount: Int)
data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Int
)

/**
 * Thin Subsonic client. Credentials live in SharedPreferences so that the
 * activity and the service read the same values without passing them around.
 */
class NavidromeApi(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    companion object {
        const val PREFS = "navi_prefs"
        private const val API_VERSION = "1.16.1"
        private const val CLIENT_NAME = "NaviPlayer"

        /** Subsonic caps getAlbumList2 at 500 per call. */
        private const val PAGE_SIZE = 500
        private const val MAX_PAGES = 10
    }

    val server: String
        get() = (prefs.getString("server", "") ?: "").trim().trimEnd('/')

    private val username: String
        get() = prefs.getString("username", "") ?: ""

    private val password: String
        get() = prefs.getString("password", "") ?: ""

    fun isConfigured(): Boolean =
        server.isNotEmpty() && username.isNotEmpty() && password.isNotEmpty()

    private fun authParams(): String {
        val u = URLEncoder.encode(username, "UTF-8")
        val p = URLEncoder.encode(password, "UTF-8")
        val c = URLEncoder.encode(CLIENT_NAME, "UTF-8")
        return "u=$u&p=$p&v=$API_VERSION&c=$c&f=json"
    }

    fun streamUrl(songId: String): String {
        val id = URLEncoder.encode(songId, "UTF-8")
        return "$server/rest/stream.view?id=$id&${authParams()}"
    }

    private fun fetch(path: String, query: String): JSONObject {
        val sep = if (query.isEmpty()) "" else "$query&"
        val url = URL("$server/rest/$path?$sep${authParams()}")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.requestMethod = "GET"

        if (conn.responseCode != 200) throw Exception("HTTP ${conn.responseCode}")

        val body = conn.inputStream.bufferedReader().use { it.readText() }
        val root = JSONObject(body).getJSONObject("subsonic-response")

        if (root.getString("status") != "ok") {
            throw Exception(root.optJSONObject("error")?.optString("message") ?: "unknown error")
        }
        return root
    }

    // ---------- Parsers, shared by every endpoint that returns these shapes ----------

    private fun parseAlbum(a: JSONObject): Album {
        val year = a.optInt("year", 0)
        return Album(
            id = a.getString("id"),
            name = a.optString("name", a.optString("title", "Untitled album")),
            artist = a.optString("artist", ""),
            year = if (year > 0) year.toString() else "",
            songCount = a.optInt("songCount", 0)
        )
    }

    private fun parseSong(s: JSONObject, fallbackAlbum: String): Song = Song(
        id = s.getString("id"),
        title = s.optString("title", "Untitled"),
        artist = s.optString("artist", ""),
        album = s.optString("album", fallbackAlbum),
        duration = s.optInt("duration", 0)
    )

    // ---------- Artists ----------

    /** Artists arrive nested in A-Z index buckets; flatten them into one list. */
    fun getArtists(): List<Artist> {
        val root = fetch("getArtists.view", "")
        val out = mutableListOf<Artist>()
        val indexArray = root.optJSONObject("artists")?.optJSONArray("index") ?: return out

        for (i in 0 until indexArray.length()) {
            val artistArray = indexArray.getJSONObject(i).optJSONArray("artist") ?: continue
            for (j in 0 until artistArray.length()) {
                val a = artistArray.getJSONObject(j)
                out.add(
                    Artist(
                        id = a.getString("id"),
                        name = a.optString("name", "Unknown artist"),
                        albumCount = a.optInt("albumCount", 0)
                    )
                )
            }
        }
        return out
    }

    // ---------- Albums ----------

    fun getAlbums(artistId: String): List<Album> {
        val id = URLEncoder.encode(artistId, "UTF-8")
        val root = fetch("getArtist.view", "id=$id")
        val out = mutableListOf<Album>()
        val albumArray = root.optJSONObject("artist")?.optJSONArray("album") ?: return out

        for (i in 0 until albumArray.length()) {
            out.add(parseAlbum(albumArray.getJSONObject(i)))
        }
        return out
    }

    /** Every album, alphabetically. Paged, because getAlbumList2 caps each call. */
    fun getAllAlbums(): List<Album> {
        val out = mutableListOf<Album>()
        var offset = 0

        for (page in 0 until MAX_PAGES) {
            val root = fetch(
                "getAlbumList2.view",
                "type=alphabeticalByName&size=$PAGE_SIZE&offset=$offset"
            )
            val albumArray = root.optJSONObject("albumList2")?.optJSONArray("album") ?: break
            if (albumArray.length() == 0) break

            for (i in 0 until albumArray.length()) {
                out.add(parseAlbum(albumArray.getJSONObject(i)))
            }

            if (albumArray.length() < PAGE_SIZE) break
            offset += PAGE_SIZE
        }
        return out
    }

    // ---------- Playlists ----------

    fun getPlaylists(): List<Playlist> {
        val root = fetch("getPlaylists.view", "")
        val out = mutableListOf<Playlist>()
        val listArray = root.optJSONObject("playlists")?.optJSONArray("playlist") ?: return out

        for (i in 0 until listArray.length()) {
            val p = listArray.getJSONObject(i)
            out.add(
                Playlist(
                    id = p.getString("id"),
                    name = p.optString("name", "Untitled playlist"),
                    songCount = p.optInt("songCount", 0)
                )
            )
        }
        return out
    }

    fun getPlaylistSongs(playlistId: String): List<Song> {
        val id = URLEncoder.encode(playlistId, "UTF-8")
        val root = fetch("getPlaylist.view", "id=$id")
        val out = mutableListOf<Song>()
        val entryArray = root.optJSONObject("playlist")?.optJSONArray("entry") ?: return out

        for (i in 0 until entryArray.length()) {
            out.add(parseSong(entryArray.getJSONObject(i), ""))
        }
        return out
    }

    // ---------- Tracks ----------

    fun getSongs(albumId: String): List<Song> {
        val id = URLEncoder.encode(albumId, "UTF-8")
        val root = fetch("getAlbum.view", "id=$id")
        val out = mutableListOf<Song>()
        val albumNode = root.optJSONObject("album") ?: return out
        val albumName = albumNode.optString("name", "")
        val songArray = albumNode.optJSONArray("song") ?: return out

        for (i in 0 until songArray.length()) {
            out.add(parseSong(songArray.getJSONObject(i), albumName))
        }
        return out
    }
}
