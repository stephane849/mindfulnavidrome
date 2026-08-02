package com.stephane.naviplayer

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionCommand
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

        const val CAT_QUEUE = "cat/queue"
        const val CAT_ARTISTS = "cat/artists"
        const val CAT_ALBUMS = "cat/albums"
        const val CAT_PLAYLISTS = "cat/playlists"
        const val CAT_PODCASTS = "cat/podcasts"
        const val CAT_RADIO = "cat/radio"

        const val PREFIX_ARTIST = "artist/"
        const val PREFIX_ALBUM = "album/"
        const val PREFIX_PLAYLIST = "playlist/"
        const val PREFIX_PODCAST = "podcast/"
        const val PREFIX_TRACK = "track/"

        /** A station is addressed directly: it has no container and, being
         *  endless, no resume point or track list to belong to. */
        const val PREFIX_RADIO = "radio/"

        /** Queue rows address a position, not an identity: the same episode
         *  may legitimately appear in the queue more than once. */
        const val PREFIX_QUEUE = "queue/"

        /** Whole lists cross a Binder transaction, which caps out around 1MB. */
        private const val MAX_ITEMS = 300

        /** How old a cached feed may get before it is refreshed behind you. */
        private const val FEED_REFRESH_MS = 30 * 60 * 1000L

        /** Custom session command, so the sleep timer survives the app closing. */
        const val COMMAND_SLEEP_TIMER = "com.stephane.naviplayer.SLEEP_TIMER"
        const val ARG_SLEEP_MINUTES = "minutes"

        /** Carried in MediaMetadata extras to the browsing client. */
        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_PLAYED = "played"

        /**
         * What a radio stream says it is playing right now, sent to controllers
         * as session extras.
         *
         * It cannot ride on the media metadata: ExoPlayer merges in-stream
         * metadata underneath the MediaItem's own, so the station name we set
         * would overwrite the track title every time.
         */
        const val EXTRA_STREAM_TITLE = "stream_title"
    }

    private lateinit var player: ExoPlayer
    private lateinit var session: MediaLibrarySession
    private lateinit var api: NavidromeApi
    private lateinit var resume: ResumeStore
    private lateinit var podcasts: PodcastStore
    private lateinit var radio: RadioStore
    private lateinit var queueStore: QueueStore

    /**
     * The queue as the browse tree sees it. Kept in a field because childrenOf
     * runs on a background executor and the player may only be read from the
     * application thread.
     */
    @Volatile
    private var queueSnapshot: List<QueueEntry> = emptyList()

    private val handler = Handler(Looper.getMainLooper())
    private val browseExecutor: ListeningExecutorService =
        MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())

    /** Last container fetched, so tapping a track does not refetch its list. */
    private var songCache: Pair<String, List<Song>>? = null

    private val playerListener = PlayerListener()

    /** Set in onDestroy, so late callbacks never touch a released session. */
    private var released = false

    private var retries = 0

    /** Wall-clock time the sleep timer fires, or 0 when it is off. */
    private var sleepAtMs = 0L

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
        podcasts = PodcastStore(this)
        radio = RadioStore(this)
        queueStore = QueueStore(this)

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

        player.addListener(playerListener)

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

        // Restoring saved state runs while the service is being created, so
        // anything thrown here is a crash before the app can open - and no
        // saved queue is worth that. Drop it and carry on.
        try {
            restoreQueue()
        } catch (e: Exception) {
            Log.e(TAG, "could not restore the saved queue, dropping it", e)
            queueStore.clear()
            queueSnapshot = emptyList()
        }
    }

    /**
     * Puts a saved queue back into the player without preparing or playing it,
     * so reopening the app never starts audio by itself. The first play
     * prepares whatever is sitting there.
     */
    private fun restoreQueue() {
        if (player.mediaItemCount > 0) return
        val saved = queueStore.load() ?: return

        // These go straight onto the player rather than arriving from a
        // controller, so onAddMediaItems never sees them and nothing else will
        // attach a URL. setMediaItems builds its media sources there and then,
        // and an item with no URI takes the service down as it is created -
        // which meant one saved queue made the app unopenable.
        val entries = mutableListOf<QueueEntry>()
        val items = mutableListOf<MediaItem>()
        for (entry in saved.entries) {
            val uri = streamUriFor(entry.mediaId) ?: continue
            entries.add(entry)
            items.add(
                MediaItem.Builder()
                    .setMediaId(entry.mediaId)
                    .setUri(uri)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(entry.title)
                            .setSubtitle(entry.subtitle)
                            .setIsBrowsable(false)
                            .setIsPlayable(true)
                            .build()
                    )
                    .build()
            )
        }

        if (items.isEmpty()) {
            queueStore.clear()
            return
        }

        queueSnapshot = entries
        // Re-clamped, because anything that would not resolve has been dropped
        player.setMediaItems(
            items,
            saved.index.coerceIn(0, items.lastIndex),
            saved.positionMs,
        )
    }

    /** Snapshots the queue for the browse tree and writes it to disk. */
    private fun captureQueue() {
        val entries = (0 until player.mediaItemCount).map { i ->
            val item = player.getMediaItemAt(i)
            QueueEntry(
                mediaId = item.mediaId,
                title = item.mediaMetadata.title?.toString() ?: "",
                subtitle = item.mediaMetadata.subtitle?.toString() ?: "",
            )
        }
        queueSnapshot = entries
        queueStore.save(
            entries,
            player.currentMediaItemIndex,
            player.currentPosition.coerceAtLeast(0L),
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
        captureQueue()
        handler.removeCallbacks(positionSaver)
        // Releasing the player delivers a last round of callbacks, and anything
        // that touches the session from one of those would hit a released
        // session. Stop listening before tearing either down.
        player.removeListener(playerListener)
        released = true
        session.release()
        player.release()
        browseExecutor.shutdown()
        super.onDestroy()
    }

    // ---------- Playback listener ----------

    private inner class PlayerListener : Player.Listener {

        /**
         * Shoutcast streams announce the current track in band. Only radio has
         * this, and only some stations send it at all.
         */
        override fun onMetadata(metadata: Metadata) {
            for (i in 0 until metadata.length()) {
                val entry = metadata.get(i)
                if (entry is IcyInfo) {
                    publishStreamTitle(entry.title.orEmpty())
                    return
                }
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            retries = 0
            pendingResumeMediaId = mediaItem?.mediaId
            // Whatever the last station was announcing does not describe this
            publishStreamTitle("")
            mediaItem?.let {
                resume.saveLast(it.mediaId, it.mediaMetadata.title?.toString() ?: "")
            }
            // Auto-advancing between tracks leaves the player in STATE_READY
            // throughout, so onPlaybackStateChanged never fires and the resume
            // point for the new track would otherwise be ignored.
            if (player.playbackState == Player.STATE_READY) applyPendingResume()
            captureQueue()
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            captureQueue()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            // A track that ran to its end is finished, not paused partway
            if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                oldPosition.mediaItem?.mediaId?.let {
                    val songId = songIdOf(it)
                    resume.clear(songId)
                    resume.markPlayed(songId)
                }
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

        // Radio has nowhere to resume to. Some live streams do report a
        // seekable window, so this is an explicit rule rather than something
        // left to the duration check below.
        if (isRadio(mediaId)) return

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
        if (isRadio(mediaId)) return
        val total = player.duration
        resume.save(
            songIdOf(mediaId),
            player.currentPosition,
            if (total == C.TIME_UNSET) 0L else total,
        )
    }

    /** track/<container>/<songId> - the song id is everything after the last slash. */
    private fun songIdOf(mediaId: String): String = mediaId.substringAfterLast('/')

    private fun isRadio(mediaId: String): Boolean = mediaId.startsWith(PREFIX_RADIO)

    private var streamTitle = ""

    private fun publishStreamTitle(title: String) {
        // The player has listeners attached before the session exists, and
        // restoring a saved queue can transition an item on the way through
        if (!::session.isInitialized || released) return
        if (title == streamTitle) return
        streamTitle = title
        session.setSessionExtras(Bundle().apply { putString(EXTRA_STREAM_TITLE, title) })
    }

    /**
     * Podcast episodes are hosted by whoever publishes the feed and radio
     * stations by whoever runs them, so only Navidrome tracks go through the
     * Subsonic stream endpoint.
     */
    private fun streamUriFor(mediaId: String): String? {
        // Deliberately not transcoded: a station's URL is the stream, and
        // pushing it through stream.view would ask Navidrome to re-encode
        // something it does not hold.
        if (isRadio(mediaId)) {
            return radio.station(mediaId.removePrefix(PREFIX_RADIO))
                ?.streamUrl
                ?.takeIf { it.isNotEmpty() }
        }

        if (!mediaId.startsWith(PREFIX_TRACK)) return null
        val rest = mediaId.removePrefix(PREFIX_TRACK)
        val slash = rest.lastIndexOf('/')
        if (slash <= 0) return null

        val container = rest.substring(0, slash)
        val itemId = rest.substring(slash + 1)

        return if (container.startsWith(PREFIX_PODCAST)) {
            podcasts.feed(container.removePrefix(PREFIX_PODCAST))
                ?.episodes
                ?.firstOrNull { it.id == itemId }
                ?.url
        } else {
            api.streamUrl(itemId)
        }
    }

    // ---------- Browse tree ----------

    // ---------- Sleep timer ----------

    /**
     * Lives in the service rather than the activity so it keeps running with
     * the screen off and the app closed, which is the only time it is useful.
     */
    private val sleepRunnable = Runnable {
        player.pause()
        sleepAtMs = 0L
    }

    private fun setSleepTimer(minutes: Int) {
        handler.removeCallbacks(sleepRunnable)
        if (minutes <= 0) {
            sleepAtMs = 0L
            return
        }
        val delay = minutes * 60_000L
        sleepAtMs = System.currentTimeMillis() + delay
        handler.postDelayed(sleepRunnable, delay)
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                .buildUpon()
                .add(SessionCommand(COMMAND_SLEEP_TIMER, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == COMMAND_SLEEP_TIMER) {
                setSleepTimer(args.getInt(ARG_SLEEP_MINUTES, 0))
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }

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
            val resolved = mutableListOf<MediaItem>()
            for (item in mediaItems) {
                if (item.localConfiguration != null) {
                    resolved.add(item)
                    continue
                }
                val uri = streamUriFor(item.mediaId)
                if (uri == null) {
                    // Handing back an item with no URI crashes ExoPlayer when it
                    // prepares: it requires a localConfiguration. A station
                    // deleted on the server, or a feed whose cache was cleared,
                    // is a normal thing to hit - drop it instead.
                    Log.w(TAG, "no stream for ${item.mediaId}, skipping")
                    continue
                }
                resolved.add(item.buildUpon().setUri(uri).build())
            }
            return Futures.immediateFuture(resolved)
        }
    }

    private fun childrenOf(parentId: String): List<MediaItem> {
        // Podcasts come from their own hosts and the queue is local, so neither
        // needs a server
        val needsServer = parentId != MEDIA_ID_ROOT &&
            parentId != CAT_QUEUE &&
            parentId != CAT_PODCASTS &&
            !parentId.startsWith(PREFIX_PODCAST)
        if (needsServer && !api.isConfigured()) {
            return noticeList("Not connected", "Enter your server details")
        }

        return try {
            val items = loadChildren(parentId)
            when {
                items.isEmpty() -> emptyNotice(parentId)
                items.size > MAX_ITEMS ->
                    items.take(MAX_ITEMS) +
                        noticeItem("Showing first $MAX_ITEMS", "${items.size} items in total")
                else -> items
            }
        } catch (e: Exception) {
            // Surfaced as a row rather than swallowed: an empty list told us
            // nothing at all when this failed on a real device.
            Log.e(TAG, "could not load children of $parentId", e)
            noticeList(
                "Couldn't load this list",
                "${e.javaClass.simpleName}: ${e.message ?: "no detail"}",
            )
        }
    }

    private fun loadChildren(parentId: String): List<MediaItem> =
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

                // Read from the snapshot, never the player: this runs on the
                // browse executor and the player is main-thread only.
                parentId == CAT_QUEUE -> queueSnapshot.mapIndexed { index, entry ->
                    playableItem(
                        PREFIX_QUEUE + index,
                        entry.title.ifEmpty { "Untitled" },
                        entry.subtitle,
                    )
                }

                parentId == CAT_PODCASTS -> podcasts.feeds().map { feed ->
                    browsableItem(
                        PREFIX_PODCAST + feed.id,
                        feed.title,
                        plural(feed.episodes.size, "episode", "episodes"),
                    )
                }

                // Cached episodes are served straight away and the feed is
                // refreshed behind them. Fetching and re-parsing a whole RSS
                // document before showing anything made opening a large feed
                // feel broken.
                parentId.startsWith(PREFIX_PODCAST) -> {
                    val feedId = parentId.removePrefix(PREFIX_PODCAST)
                    val cached = podcasts.feed(feedId)
                    val feed = if (cached == null || cached.episodes.isEmpty()) {
                        refreshFeed(cached, feedId)
                    } else {
                        if (isStale(cached)) refreshInBackground(cached)
                        cached
                    }
                    feed?.episodes.orEmpty().map { episode ->
                        playableItem(
                            trackId(parentId, episode.id),
                            episode.title,
                            episodeSubtitle(episode),
                            progressOf(episode.id, episode.durationSec),
                            resume.isPlayed(episode.id),
                        )
                    }
                }

                // Stations are mirrored on the way past, so playback can
                // resolve a stream URL later without touching the network on
                // the application thread.
                parentId == CAT_RADIO -> api.getRadioStations()
                    .also { radio.replaceAll(it) }
                    .map { station ->
                        playableItem(
                            PREFIX_RADIO + station.id,
                            station.name,
                            stationSubtitle(station),
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
                            progressOf(song.id, song.duration),
                            resume.isPlayed(song.id),
                        )
                    }
                }

            else -> emptyList()
        }

    /**
     * An empty list is a normal state for some categories and a symptom for
     * others, so say which it is rather than reporting a media id at the reader.
     */
    private fun emptyNotice(parentId: String): List<MediaItem> = when (parentId) {
        CAT_RADIO -> noticeList("No stations", "Add one with Add, or in Navidrome")
        CAT_PODCASTS -> noticeList("No subscriptions", "Find a podcast under Search")
        CAT_QUEUE -> noticeList("Queue empty", "Long-press anything playable to add it")
        else -> noticeList("Empty list", "The server returned no items for $parentId")
    }

    private fun noticeList(title: String, detail: String) = listOf(noticeItem(title, detail))

    /**
     * A row that exists only to say something to the reader. Neither browsable
     * nor playable, so tapping it does nothing.
     */
    private fun noticeItem(title: String, detail: String) =
        MediaItem.Builder()
            .setMediaId("notice/${title.hashCode()}")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(detail)
                    .setIsBrowsable(false)
                    .setIsPlayable(false)
                    .build()
            )
            .build()

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

        val queued = queueSnapshot
        if (queued.isNotEmpty()) {
            items.add(
                browsableItem(
                    CAT_QUEUE,
                    "Queue",
                    plural(queued.size, "item", "items"),
                )
            )
        }

        items.add(browsableItem(CAT_ARTISTS, "Artists", "Browse by artist"))
        items.add(browsableItem(CAT_ALBUMS, "Albums", "Every album"))
        items.add(browsableItem(CAT_PLAYLISTS, "Playlists", "Your Navidrome playlists"))
        items.add(browsableItem(CAT_RADIO, "Radio", "Live stations"))
        items.add(
            browsableItem(
                CAT_PODCASTS,
                "Podcasts",
                plural(podcasts.feeds().size, "subscription", "subscriptions"),
            )
        )
        return items
    }

    private fun isStale(feed: Feed): Boolean =
        System.currentTimeMillis() - feed.refreshedAt > FEED_REFRESH_MS

    /** Blocking refresh, used only when there is nothing cached to show. */
    private fun refreshFeed(cached: Feed?, feedId: String): Feed? {
        val url = cached?.url?.takeIf { it.isNotEmpty() } ?: return cached
        return try {
            PodcastFeed.fetch(url).also { podcasts.save(it) }
        } catch (e: Exception) {
            Log.w(TAG, "refresh failed for $feedId, using cache", e)
            cached
        }
    }

    private fun refreshInBackground(feed: Feed) {
        if (feed.url.isEmpty()) return
        browseExecutor.execute {
            try {
                podcasts.save(PodcastFeed.fetch(feed.url))
                // Nudge any attached browser so the list picks up new episodes.
                // Session methods must run on the application thread.
                handler.post {
                    session.notifyChildrenChanged(PREFIX_PODCAST + feed.id, Int.MAX_VALUE, null)
                }
            } catch (e: Exception) {
                Log.w(TAG, "background refresh failed for ${feed.id}", e)
            }
        }
    }

    private fun episodeSubtitle(episode: Episode): String {
        val total = formatClock(episode.durationSec)
        val saved = resume.position(episode.id)
        val time = when {
            saved > 0L && total.isNotEmpty() -> "${formatClockMs(saved)} of $total"
            saved > 0L -> formatClockMs(saved)
            else -> total
        }
        val date = episode.published.take(16)
        return listOf(date, time).filter { it.isNotEmpty() }.joinToString(" - ")
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

    /**
     * Progress and played state travel in the metadata extras so the list can
     * draw a progress bar and filter episodes without asking the service again.
     */
    private fun playableItem(
        id: String,
        title: String,
        subtitle: String,
        progress: Float = 0f,
        played: Boolean = false,
    ) = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setExtras(
                    Bundle().apply {
                        putFloat(EXTRA_PROGRESS, progress)
                        putBoolean(EXTRA_PLAYED, played)
                    }
                )
                .build()
        )
        .build()

    /** Fraction of a track already heard, 0 when it has not been started. */
    private fun progressOf(songId: String, durationSec: Int): Float {
        if (durationSec <= 0) return 0f
        val saved = resume.position(songId)
        if (saved <= 0L) return 0f
        return (saved.toFloat() / (durationSec * 1000f)).coerceIn(0f, 1f)
    }

    // ---------- Small helpers ----------

    private fun plural(n: Int, one: String, many: String) =
        if (n == 1) "1 $one" else "$n $many"

    /** A station has no duration to show, so name where it comes from instead. */
    private fun stationSubtitle(station: Station): String {
        val host = try {
            java.net.URI(station.streamUrl).host.orEmpty().removePrefix("www.")
        } catch (e: Exception) {
            ""
        }
        return if (host.isEmpty()) "Live" else "Live - $host"
    }

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
