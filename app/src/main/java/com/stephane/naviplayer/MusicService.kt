package com.stephane.naviplayer

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Owns all playback. The activity browses and controls through a Media3
 * MediaBrowser, so audio survives the activity being destroyed.
 *
 * ExoPlayer rather than MediaPlayer, for reasons that matter specifically to
 * hour-long spoken-word recordings: constant-bitrate MP3 seeking so the +/-15s
 * controls land accurately on files with no Xing header, generous buffering,
 * and retrying a failed stream at its current position instead of abandoning
 * the track. Audio focus, becoming-noisy, the wake lock and the media
 * notification are all handled by the player and session rather than by hand.
 */
class MusicService : MediaLibraryService() {

    companion object {
        private const val TAG = "NaviService"

        /** How far the notification's seek controls move. */
        private const val SEEK_STEP_MS = 15_000L

        /** Position is written this often while playing, so a kill loses little. */
        private const val SAVE_INTERVAL_MS = 10_000L

        /** Transient network failures are common across a 90-minute stream. */
        private const val MAX_RETRIES = 5
        private const val RETRY_DELAY_MS = 2_000L

        /** Long-form audio benefits from buffering far ahead of a music player. */
        private const val MIN_BUFFER_MS = 60_000
        private const val MAX_BUFFER_MS = 300_000
        private const val BUFFER_FOR_PLAYBACK_MS = 5_000
        private const val BUFFER_FOR_REPLAY_MS = 10_000

        const val MEDIA_ID_ROOT = "root"

        const val CAT_ARTISTS = "cat/artists"
        const val CAT_ALBUMS = "cat/albums"
        const val CAT_PLAYLISTS = "cat/playlists"

        const val PREFIX_ARTIST = "artist/"
        const val PREFIX_ALBUM = "album/"
        const val PREFIX_PLAYLIST = "playlist/"
        const val PREFIX_TRACK = "track/"
    }

    private lateinit var player: ExoPlayer
    private lateinit var session: MediaLibrarySession
    private lateinit var api: NavidromeApi
    private lateinit var resume: ResumeStore

    private val handler = Handler(Looper.getMainLooper())
    private val browseExecutor: ListeningExecutorService =
        MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())

    /** Last container fetched, so tapping a track does not refetch its list. */
    private var songCache: Pair<String, List<Song>>? = null

    private var retries = 0

    /** Set when a new item starts, consumed once it is ready enough to seek. */
    private var pendingResumeMediaId: String? = null

    private val positionSaver = object : Runnable {
        override fun run() {
            savePosition()
            handler.postDelayed(this, SAVE_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        api = NavidromeApi(this)
        resume = ResumeStore(this)

        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("NaviPlayer")
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
            .setAllowCrossProtocolRedirects(true)

        // The fix that makes +/-15s usable on ripped lectures: MP3s without a
        // Xing/VBRI header have no seek table, and without this flag seeking
        // falls back to guesswork.
        val extractors = DefaultExtractorsFactory()
            .setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING)

        val sourceFactory = DefaultMediaSourceFactory(httpFactory, extractors)
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(MAX_RETRIES))

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                MIN_BUFFER_MS,
                MAX_BUFFER_MS,
                BUFFER_FOR_PLAYBACK_MS,
                BUFFER_FOR_REPLAY_MS,
            )
            .build()

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(sourceFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(SEEK_STEP_MS)
            .setSeekForwardIncrementMs(SEEK_STEP_MS)
            .build()

        player.addListener(PlayerListener())

        session = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
            .build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply {
                setSmallIcon(R.drawable.ic_note)
            }
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession =
        session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the task away should not kill a lecture that is still playing
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        savePosition()
        handler.removeCallbacks(positionSaver)
        session.release()
        player.release()
        browseExecutor.shutdown()
        super.onDestroy()
    }

    // ---------- Playback listener ----------

    private inner class PlayerListener : Player.Listener {

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            retries = 0
            pendingResumeMediaId = mediaItem?.mediaId
            mediaItem?.let {
                resume.saveLast(it.mediaId, it.mediaMetadata.title?.toString() ?: "")
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            // A track that ran to its end is finished, not paused partway
            if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                oldPosition.mediaItem?.mediaId?.let { resume.clear(songIdOf(it)) }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                retries = 0
                applyPendingResume()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                startSaving()
            } else {
                stopSaving()
                savePosition()
            }
        }

        /**
         * Retry where we left off. The old MediaPlayer path called skipNext on
         * any error, which meant one dropped packet 40 minutes into a class
         * silently moved on to the next one.
         */
        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "playback error (${error.errorCodeName})", error)
            if (retries >= MAX_RETRIES) {
                Log.e(TAG, "giving up after $retries retries; staying on this track")
                return
            }
            retries++
            val resumeAt = player.currentPosition
            handler.postDelayed({
                player.prepare()
                if (resumeAt > 0) player.seekTo(resumeAt)
            }, RETRY_DELAY_MS)
        }
    }

    private fun applyPendingResume() {
        val mediaId = pendingResumeMediaId ?: return
        pendingResumeMediaId = null

        // A start position given by the caller wins over the stored one
        if (player.currentPosition > 1_000L) return

        val saved = resume.position(songIdOf(mediaId))
        if (saved <= 0L) return

        val total = player.duration
        if (total == C.TIME_UNSET || saved < total - ResumeStore.END_SLACK_MS) {
            player.seekTo(saved)
        }
    }

    // ---------- Resume positions ----------

    private fun startSaving() {
        handler.removeCallbacks(positionSaver)
        handler.postDelayed(positionSaver, SAVE_INTERVAL_MS)
    }

    private fun stopSaving() {
        handler.removeCallbacks(positionSaver)
    }

    private fun savePosition() {
        val mediaId = player.currentMediaItem?.mediaId ?: return
        val total = player.duration
        resume.save(
            songIdOf(mediaId),
            player.currentPosition,
            if (total == C.TIME_UNSET) 0L else total,
        )
    }

    /** track/<container>/<songId> - the song id is everything after the last slash. */
    private fun songIdOf(mediaId: String): String = mediaId.substringAfterLast('/')

    // ---------- Browse tree ----------

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(
                LibraryResult.ofItem(browsableItem(MEDIA_ID_ROOT, "Library", ""), params)
            )

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(
                LibraryResult.ofItem(browsableItem(mediaId, "", ""), null)
            )

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            browseExecutor.submit(
                Callable {
                    LibraryResult.ofItemList(ImmutableList.copyOf(childrenOf(parentId)), params)
                }
            )

        /**
         * Items handed back by a controller carry only their media id, so the
         * streaming URL is attached here, just before playback.
         */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val resolved = mediaItems.mapTo(mutableListOf()) { item ->
                if (item.localConfiguration != null) {
                    item
                } else {
                    item.buildUpon()
                        .setUri(api.streamUrl(songIdOf(item.mediaId)))
                        .build()
                }
            }
            return Futures.immediateFuture(resolved)
        }
    }

    private fun childrenOf(parentId: String): List<MediaItem> {
        if (!api.isConfigured()) return emptyList()

        return try {
            when {
                parentId == MEDIA_ID_ROOT -> rootItems()

                parentId == CAT_ARTISTS -> api.getArtists().map { artist ->
                    browsableItem(
                        PREFIX_ARTIST + artist.id,
                        artist.name,
                        plural(artist.albumCount, "album", "albums"),
                    )
                }

                parentId == CAT_ALBUMS -> api.getAllAlbums().map { album ->
                    browsableItem(
                        PREFIX_ALBUM + album.id,
                        album.name,
                        albumSubtitleWithArtist(album),
                    )
                }

                parentId == CAT_PLAYLISTS -> api.getPlaylists().map { playlist ->
                    browsableItem(
                        PREFIX_PLAYLIST + playlist.id,
                        playlist.name,
                        plural(playlist.songCount, "track", "tracks"),
                    )
                }

                parentId.startsWith(PREFIX_ARTIST) ->
                    api.getAlbums(parentId.removePrefix(PREFIX_ARTIST)).map { album ->
                        browsableItem(PREFIX_ALBUM + album.id, album.name, albumSubtitle(album))
                    }

                parentId.startsWith(PREFIX_ALBUM) || parentId.startsWith(PREFIX_PLAYLIST) -> {
                    val showArtist = parentId.startsWith(PREFIX_PLAYLIST)
                    loadContainer(parentId).map { song ->
                        playableItem(
                            trackId(parentId, song.id),
                            song.title,
                            trackSubtitle(song, showArtist),
                        )
                    }
                }

                else -> emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "could not load children of $parentId", e)
            emptyList()
        }
    }

    /** The mode picker, plus a way straight back into whatever was last playing. */
    private fun rootItems(): List<MediaItem> {
        val items = mutableListOf<MediaItem>()

        val lastId = resume.lastMediaId()
        if (lastId.isNotEmpty()) {
            val savedPosition = resume.position(songIdOf(lastId))
            val title = resume.lastTitle()
            val subtitle = if (savedPosition > 0L) {
                "$title - ${formatClockMs(savedPosition)}"
            } else {
                title
            }
            items.add(playableItem(lastId, "Continue listening", subtitle))
        }

        items.add(browsableItem(CAT_ARTISTS, "Artists", "Browse by artist"))
        items.add(browsableItem(CAT_ALBUMS, "Albums", "Every album"))
        items.add(browsableItem(CAT_PLAYLISTS, "Playlists", "Your Navidrome playlists"))
        return items
    }

    /** Albums and playlists both resolve to a track list; the cache spans both. */
    private fun loadContainer(containerId: String): List<Song> {
        songCache?.takeIf { it.first == containerId }?.let { return it.second }

        val songs = when {
            containerId.startsWith(PREFIX_ALBUM) ->
                api.getSongs(containerId.removePrefix(PREFIX_ALBUM))
            containerId.startsWith(PREFIX_PLAYLIST) ->
                api.getPlaylistSongs(containerId.removePrefix(PREFIX_PLAYLIST))
            else -> emptyList()
        }
        songCache = containerId to songs
        return songs
    }

    private fun trackId(containerId: String, songId: String) =
        "$PREFIX_TRACK$containerId/$songId"

    private fun browsableItem(id: String, title: String, subtitle: String) =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .build()
            )
            .build()

    private fun playableItem(id: String, title: String, subtitle: String) =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build()
            )
            .build()

    // ---------- Small helpers ----------

    private fun plural(n: Int, one: String, many: String) =
        if (n == 1) "1 $one" else "$n $many"

    private fun albumSubtitle(album: Album): String {
        val count = plural(album.songCount, "track", "tracks")
        return if (album.year.isNotEmpty()) "${album.year} - $count" else count
    }

    /** The flat album list spans artists, so name the artist there. */
    private fun albumSubtitleWithArtist(album: Album): String {
        val parts = mutableListOf<String>()
        if (album.artist.isNotEmpty()) parts.add(album.artist)
        if (album.year.isNotEmpty()) parts.add(album.year)
        return if (parts.isEmpty()) {
            plural(album.songCount, "track", "tracks")
        } else {
            parts.joinToString(" - ")
        }
    }

    /** Shows the resume point when there is one, so a part-heard class is obvious. */
    private fun trackSubtitle(song: Song, showArtist: Boolean): String {
        val total = formatClock(song.duration)
        val saved = resume.position(song.id)
        val time = when {
            saved > 0L && total.isNotEmpty() -> "${formatClockMs(saved)} of $total"
            saved > 0L -> formatClockMs(saved)
            else -> total
        }
        val artist = if (showArtist && song.artist.isNotEmpty()) song.artist else ""
        return listOf(artist, time).filter { it.isNotEmpty() }.joinToString(" - ")
    }
}
