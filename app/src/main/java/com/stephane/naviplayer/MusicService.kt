package com.stephane.naviplayer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.AudioAttributesCompat
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver

/**
 * Owns all playback. The activity never touches MediaPlayer - it browses through
 * MediaBrowserCompat and issues commands through MediaControllerCompat, so audio
 * survives the activity being destroyed.
 */
class MusicService : MediaBrowserServiceCompat() {

    companion object {
        private const val TAG = "NaviService"
        private const val CHANNEL_ID = "navi_playback"
        private const val NOTIFICATION_ID = 1

        /** How far the -15/+15 controls move. */
        private const val SEEK_STEP_MS = 15_000L

        /** Position is written this often while playing, so a kill loses little. */
        private const val SAVE_INTERVAL_MS = 10_000L

        const val MEDIA_ID_ROOT = "root"

        const val CAT_ARTISTS = "cat/artists"
        const val CAT_ALBUMS = "cat/albums"
        const val CAT_PLAYLISTS = "cat/playlists"

        const val PREFIX_ARTIST = "artist/"
        const val PREFIX_ALBUM = "album/"
        const val PREFIX_PLAYLIST = "playlist/"
        const val PREFIX_TRACK = "track/"
    }

    private lateinit var session: MediaSessionCompat
    private lateinit var api: NavidromeApi
    private lateinit var resume: ResumeStore
    private lateinit var audioManager: AudioManager

    private val handler = Handler(Looper.getMainLooper())

    private var player: MediaPlayer? = null
    private var focusRequest: AudioFocusRequestCompat? = null
    private var noisyReceiverRegistered = false

    private var queue: List<Song> = emptyList()
    private var queueContainerId = ""
    private var index = -1
    private var isForeground = false

    /** Last container fetched, so tapping a track does not refetch its list. */
    private var songCache: Pair<String, List<Song>>? = null

    /** Writes the position periodically; only armed while something is playing. */
    private val positionSaver = object : Runnable {
        override fun run() {
            savePosition()
            handler.postDelayed(this, SAVE_INTERVAL_MS)
        }
    }

    // Headphone unplugged, or Bluetooth disconnected: pause rather than blare
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                pausePlayback()
            }
        }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> stopPlayback()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pausePlayback()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                player?.setVolume(0.2f, 0.2f)
            AudioManager.AUDIOFOCUS_GAIN -> {
                player?.setVolume(1.0f, 1.0f)
                if (player != null && player?.isPlaying == false) resumePlayback()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        api = NavidromeApi(this)
        resume = ResumeStore(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        session = MediaSessionCompat(this, "NaviPlayerSession").apply {
            setCallback(SessionCallback())
            setSessionActivity(
                PendingIntent.getActivity(
                    this@MusicService, 0,
                    Intent(this@MusicService, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            isActive = true
        }
        sessionToken = session.sessionToken

        createNotificationChannel()
        updatePlaybackState(PlaybackStateCompat.STATE_NONE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Routes hardware media-button presses (headset, Bluetooth) into the session
        MediaButtonReceiver.handleIntent(session, intent)
        return START_STICKY
    }

    override fun onDestroy() {
        stopPlayback()
        session.isActive = false
        session.release()
        super.onDestroy()
    }

    // ---------- Browse tree ----------

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot = BrowserRoot(MEDIA_ID_ROOT, null)

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        result.detach()

        if (!api.isConfigured()) {
            result.sendResult(null)
            return
        }

        Thread {
            try {
                val items = mutableListOf<MediaBrowserCompat.MediaItem>()

                when {
                    parentId == MEDIA_ID_ROOT -> items.addAll(rootItems())

                    parentId == CAT_ARTISTS -> {
                        for (artist in api.getArtists()) {
                            items.add(
                                browsableItem(
                                    PREFIX_ARTIST + artist.id,
                                    artist.name,
                                    plural(artist.albumCount, "album", "albums")
                                )
                            )
                        }
                    }

                    parentId == CAT_ALBUMS -> {
                        for (album in api.getAllAlbums()) {
                            items.add(
                                browsableItem(
                                    PREFIX_ALBUM + album.id,
                                    album.name,
                                    albumSubtitleWithArtist(album)
                                )
                            )
                        }
                    }

                    parentId == CAT_PLAYLISTS -> {
                        for (playlist in api.getPlaylists()) {
                            items.add(
                                browsableItem(
                                    PREFIX_PLAYLIST + playlist.id,
                                    playlist.name,
                                    plural(playlist.songCount, "track", "tracks")
                                )
                            )
                        }
                    }

                    parentId.startsWith(PREFIX_ARTIST) -> {
                        val artistId = parentId.removePrefix(PREFIX_ARTIST)
                        for (album in api.getAlbums(artistId)) {
                            items.add(
                                browsableItem(
                                    PREFIX_ALBUM + album.id,
                                    album.name,
                                    albumSubtitle(album)
                                )
                            )
                        }
                    }

                    parentId.startsWith(PREFIX_ALBUM) ||
                        parentId.startsWith(PREFIX_PLAYLIST) -> {
                        val showArtist = parentId.startsWith(PREFIX_PLAYLIST)
                        for (song in loadContainer(parentId)) {
                            items.add(
                                playableItem(
                                    trackId(parentId, song.id),
                                    song.title,
                                    trackSubtitle(song, showArtist)
                                )
                            )
                        }
                    }
                }

                result.sendResult(items)
            } catch (e: Exception) {
                Log.e(TAG, "onLoadChildren failed for $parentId", e)
                result.sendResult(null)
            }
        }.start()
    }

    /** The mode picker, plus a way straight back into whatever was last playing. */
    private fun rootItems(): List<MediaBrowserCompat.MediaItem> {
        val items = mutableListOf<MediaBrowserCompat.MediaItem>()

        val lastId = resume.lastMediaId()
        if (lastId.isNotEmpty()) {
            val savedPosition = resume.position(lastId.substringAfterLast('/'))
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
        MediaBrowserCompat.MediaItem(
            MediaDescriptionCompat.Builder()
                .setMediaId(id).setTitle(title).setSubtitle(subtitle).build(),
            MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
        )

    private fun playableItem(id: String, title: String, subtitle: String) =
        MediaBrowserCompat.MediaItem(
            MediaDescriptionCompat.Builder()
                .setMediaId(id).setTitle(title).setSubtitle(subtitle).build(),
            MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
        )

    // ---------- Session callbacks ----------

    private inner class SessionCallback : MediaSessionCompat.Callback() {

        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            if (mediaId == null || !mediaId.startsWith(PREFIX_TRACK)) return

            // track/<container>/<songId>, where container is album/<id> or playlist/<id>
            val rest = mediaId.removePrefix(PREFIX_TRACK)
            val slash = rest.lastIndexOf('/')
            if (slash <= 0) return
            val containerId = rest.substring(0, slash)
            val songId = rest.substring(slash + 1)

            Thread {
                try {
                    val songs = loadContainer(containerId)
                    val target = songs.indexOfFirst { it.id == songId }
                    if (target < 0) return@Thread

                    savePosition()
                    queue = songs
                    queueContainerId = containerId
                    index = target
                    startCurrent()
                } catch (e: Exception) {
                    Log.e(TAG, "onPlayFromMediaId failed", e)
                }
            }.start()
        }

        override fun onPlay() = resumePlayback()
        override fun onPause() = pausePlayback()
        override fun onStop() = stopPlayback()
        override fun onSkipToNext() = skipNext()
        override fun onSkipToPrevious() = skipPrevious()
        override fun onRewind() = seekBy(-SEEK_STEP_MS)
        override fun onFastForward() = seekBy(SEEK_STEP_MS)
        override fun onSeekTo(pos: Long) {
            player?.seekTo(pos.toInt())
            savePosition()
            updatePlaybackState(currentState())
        }
    }

    // ---------- Playback ----------

    private fun startCurrent() {
        if (index < 0 || index >= queue.size) return
        val song = queue[index]

        if (!requestFocus()) return

        resume.saveLast(trackId(queueContainerId, song.id), song.title)

        player?.release()
        player = MediaPlayer().apply {
            setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            setWakeMode(applicationContext, android.os.PowerManager.PARTIAL_WAKE_LOCK)
            setDataSource(api.streamUrl(song.id))
            setOnPreparedListener {
                // Pick up where this track was left off, unless it was all but finished
                val saved = resume.position(song.id)
                val total = it.duration.toLong()
                if (saved > 0L && (total <= 0L || saved < total - ResumeStore.END_SLACK_MS)) {
                    it.seekTo(saved.toInt())
                }
                it.start()
                setMetadata(song)
                updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                goForeground()
                registerNoisy()
                startSaving()
            }
            setOnCompletionListener {
                queue.getOrNull(index)?.let { finished -> resume.clear(finished.id) }
                skipNext()
            }
            setOnErrorListener { _, what, extra ->
                Log.e(TAG, "MediaPlayer error what=$what extra=$extra")
                skipNext()
                true
            }
            prepareAsync()
        }

        setMetadata(song)
        updatePlaybackState(PlaybackStateCompat.STATE_BUFFERING)
    }

    private fun resumePlayback() {
        val mp = player ?: run {
            if (queue.isNotEmpty()) { index = 0; startCurrent() }
            return
        }
        if (!requestFocus()) return
        mp.start()
        updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
        goForeground()
        registerNoisy()
        startSaving()
    }

    private fun pausePlayback() {
        savePosition()
        stopSaving()
        player?.takeIf { it.isPlaying }?.pause()
        updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
        // Keep the notification but let the system reclaim the service if needed
        if (isForeground) {
            ServiceCompatShim.stopForeground(this, keepNotification = true)
            isForeground = false
        }
        unregisterNoisy()
    }

    private fun stopPlayback() {
        // Position first: releasing the player throws the position away
        savePosition()
        stopSaving()
        player?.release()
        player = null
        index = -1
        abandonFocus()
        unregisterNoisy()
        updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
        ServiceCompatShim.stopForeground(this, keepNotification = false)
        isForeground = false
        stopSelf()
    }

    /** Stops at the end of the album rather than wrapping into unrelated music. */
    private fun skipNext() {
        if (queue.isEmpty()) return
        savePosition()
        if (index + 1 >= queue.size) { stopPlayback(); return }
        index += 1
        startCurrent()
    }

    /** Restarts the track if more than 3s in, otherwise steps back. */
    private fun skipPrevious() {
        val mp = player
        if (mp != null && mp.currentPosition > 3000) {
            mp.seekTo(0)
            savePosition()
            updatePlaybackState(currentState())
            return
        }
        savePosition()
        if (index - 1 < 0) {
            mp?.seekTo(0)
            return
        }
        index -= 1
        startCurrent()
    }

    /** The -15/+15 jump, clamped to the track. */
    private fun seekBy(deltaMs: Long) {
        val mp = player ?: return
        try {
            val total = mp.duration.toLong()
            var target = mp.currentPosition + deltaMs
            if (target < 0L) target = 0L
            if (total > 0L && target > total) target = total
            mp.seekTo(target.toInt())
            savePosition()
            updatePlaybackState(currentState())
        } catch (e: IllegalStateException) {
            Log.w(TAG, "seek failed", e)
        }
    }

    private fun currentState(): Int = when {
        player == null -> PlaybackStateCompat.STATE_STOPPED
        player?.isPlaying == true -> PlaybackStateCompat.STATE_PLAYING
        else -> PlaybackStateCompat.STATE_PAUSED
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
        val mp = player ?: return
        val song = queue.getOrNull(index) ?: return
        try {
            resume.save(song.id, mp.currentPosition.toLong(), mp.duration.toLong())
        } catch (e: IllegalStateException) {
            Log.w(TAG, "could not read position", e)
        }
    }

    // ---------- Audio focus ----------

    private fun requestFocus(): Boolean {
        val attrs = AudioAttributesCompat.Builder()
            .setContentType(AudioAttributesCompat.CONTENT_TYPE_MUSIC)
            .setUsage(AudioAttributesCompat.USAGE_MEDIA)
            .build()

        val request = AudioFocusRequestCompat
            .Builder(AudioManagerCompat.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener(focusListener)
            .build()

        focusRequest = request
        return AudioManagerCompat.requestAudioFocus(audioManager, request) ==
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        focusRequest?.let { AudioManagerCompat.abandonAudioFocusRequest(audioManager, it) }
        focusRequest = null
    }

    private fun registerNoisy() {
        if (!noisyReceiverRegistered) {
            // API 34 requires an explicit export flag on runtime-registered receivers
            ContextCompat.registerReceiver(
                this,
                noisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            noisyReceiverRegistered = true
        }
    }

    private fun unregisterNoisy() {
        if (noisyReceiverRegistered) {
            try { unregisterReceiver(noisyReceiver) } catch (_: Exception) { }
            noisyReceiverRegistered = false
        }
    }

    // ---------- Session state ----------

    private fun setMetadata(song: Song) {
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(
                    MediaMetadataCompat.METADATA_KEY_MEDIA_ID,
                    trackId(queueContainerId, song.id)
                )
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, song.artist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, song.album)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, song.duration * 1000L)
                .build()
        )
    }

    private fun updatePlaybackState(state: Int) {
        val position = try { player?.currentPosition?.toLong() ?: 0L } catch (_: Exception) { 0L }

        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_STOP or
                    PlaybackStateCompat.ACTION_SEEK_TO or
                    PlaybackStateCompat.ACTION_REWIND or
                    PlaybackStateCompat.ACTION_FAST_FORWARD or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
                )
                .setState(state, position, 1.0f)
                .build()
        )
        updateNotification()
    }

    // ---------- Notification ----------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): android.app.Notification {
        val controller = session.controller
        val metadata = controller?.metadata
        val playing = currentState() == PlaybackStateCompat.STATE_PLAYING

        val title = metadata?.getString(MediaMetadataCompat.METADATA_KEY_TITLE) ?: "Navi player"
        val artist = metadata?.getString(MediaMetadataCompat.METADATA_KEY_ARTIST) ?: ""

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_note)
            .setContentTitle(title)
            .setContentText(artist)
            .setContentIntent(controller?.sessionActivity)
            .setDeleteIntent(
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                    this, PlaybackStateCompat.ACTION_STOP
                )
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)

        // Order matters: the compact view below picks actions 1, 2, 3 out of these
        builder.addAction(
            NotificationCompat.Action(
                android.R.drawable.ic_media_previous, "Previous",
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                    this, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                )
            )
        )
        builder.addAction(
            NotificationCompat.Action(
                android.R.drawable.ic_media_rew, "Back 15s",
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                    this, PlaybackStateCompat.ACTION_REWIND
                )
            )
        )
        builder.addAction(
            NotificationCompat.Action(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "Pause" else "Play",
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                    this, PlaybackStateCompat.ACTION_PLAY_PAUSE
                )
            )
        )
        builder.addAction(
            NotificationCompat.Action(
                android.R.drawable.ic_media_ff, "Forward 15s",
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                    this, PlaybackStateCompat.ACTION_FAST_FORWARD
                )
            )
        )
        builder.addAction(
            NotificationCompat.Action(
                android.R.drawable.ic_media_next, "Next",
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                    this, PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                )
            )
        )

        builder.setStyle(
            MediaStyle()
                .setMediaSession(session.sessionToken)
                // Collapsed shows -15 / play / +15: the controls long audio needs
                .setShowActionsInCompactView(1, 2, 3)
                .setShowCancelButton(true)
                .setCancelButtonIntent(
                    MediaButtonReceiver.buildMediaButtonPendingIntent(
                        this, PlaybackStateCompat.ACTION_STOP
                    )
                )
        )

        return builder.build()
    }

    private fun goForeground() {
        if (!isForeground) {
            ContextCompat.startForegroundService(this, Intent(this, MusicService::class.java))
            startForeground(NOTIFICATION_ID, buildNotification())
            isForeground = true
        } else {
            updateNotification()
        }
    }

    private fun updateNotification() {
        if (currentState() == PlaybackStateCompat.STATE_NONE) return
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            Log.e(TAG, "notify failed", e)
        }
    }

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

/** stopForeground took a boolean before API 24 and an int flag from API 24 on. */
object ServiceCompatShim {
    fun stopForeground(service: android.app.Service, keepNotification: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            service.stopForeground(
                if (keepNotification) android.app.Service.STOP_FOREGROUND_DETACH
                else android.app.Service.STOP_FOREGROUND_REMOVE
            )
        } else {
            @Suppress("DEPRECATION")
            service.stopForeground(!keepNotification)
        }
    }
}
