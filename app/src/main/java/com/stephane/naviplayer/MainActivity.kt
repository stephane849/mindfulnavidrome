package com.stephane.naviplayer

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.mudita.mmd.ThemeMMD
import com.mudita.mmd.black
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.bottom_sheet.ModalBottomSheetMMD
import com.mudita.mmd.components.bottom_sheet.rememberModalBottomSheetMMDState
import com.mudita.mmd.components.nav_bar.NavigationBarMMD
import com.mudita.mmd.components.progress_indicator.LinearProgressIndicatorMMD
import com.mudita.mmd.components.snackbar.SnackbarMMD
import com.mudita.mmd.components.slider.SliderMMD
import com.mudita.mmd.components.tabs.PrimaryTabRowMMD
import com.mudita.mmd.components.tabs.TabMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.text_field.TextFieldMMD
import com.mudita.mmd.white

/** Top-level destinations, each with its own back stack. */
private enum class Section { LIBRARY, PODCASTS, QUEUE, SEARCH }

/**
 * Browses and controls playback through a Media3 MediaBrowser, which is also a
 * Player - so transport state, position and the current item all come from the
 * one object.
 *
 * Structured the way apps in this category are: top-level destinations one tap
 * apart in a bottom bar, a compact mini player that only exists when something
 * is loaded, and a full Now Playing screen behind it. Presented with Mudita
 * Mindful Design throughout - [ThemeMMD] for the pure black and white E-Ink
 * scheme and Lato type, [LazyColumnMMD] for stepped scrolling.
 */
class MainActivity : ComponentActivity() {

    companion object {
        /**
         * The clock moves once a second. It used to tick every fifteen, to
         * spare the panel, but a timer that sits still and then jumps reads as
         * broken rather than as restful - and this is one line of text being
         * redrawn, not an animation.
         */
        private const val TICK_MS = 1_000L

        /** Never re-arm tighter than this, whatever the arithmetic says. */
        private const val MIN_TICK_MS = 200L

        /** Bounded so a large library cannot overflow the Binder transaction. */
        private const val PAGE_SIZE = 400

        /** Both title-bar side slots, equal so the title centres on the screen. */
        private val BAR_SLOT = 76.dp

        private val SPEEDS = listOf(0.8f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
        private val SLEEP_MINUTES = listOf(0, 15, 30, 45, 60)
    }

    private var browserFuture: ListenableFuture<MediaBrowser>? = null
    private var browser: MediaBrowser? = null
    private lateinit var api: NavidromeApi
    private lateinit var resume: ResumeStore

    /** One breadcrumb per section, so switching destinations keeps your place. */
    private val stacks = Section.entries.associateWith {
        mutableListOf<Pair<String, String>>()
    }

    /**
     * Lists already fetched, keyed by media id. Rendering these immediately is
     * the difference between navigation feeling instant and every step costing
     * a network round trip.
     */
    private val browseCache = mutableStateMapOf<String, List<MediaItem>>()

    // ---------- Everything the UI reads ----------

    private var section by mutableStateOf(Section.LIBRARY)
    private var libraryTab by mutableStateOf(0)
    private var rows by mutableStateOf<List<MediaItem>>(emptyList())
    private var loading by mutableStateOf(false)
    private var showNowPlaying by mutableStateOf(false)

    private var playingMediaId by mutableStateOf<String?>(null)
    private var isPlaying by mutableStateOf(false)
    private var isBuffering by mutableStateOf(false)
    private var hasMedia by mutableStateOf(false)
    private var nowPlayingTitle by mutableStateOf<String?>(null)
    private var nowPlayingSubtitle by mutableStateOf<String?>(null)
    private var positionMs by mutableStateOf(0L)
    private var durationMs by mutableStateOf(0L)
    private var queueCount by mutableStateOf(0)

    /** Position in the queue, for the "n of m" line on Now Playing. */
    private var queueIndex by mutableStateOf(0)
    private var speed by mutableStateOf(1.0f)
    private var sleepMinutes by mutableStateOf(0)

    /** The stack trace of the last crash, shown once and then cleared. */
    private var crashReport by mutableStateOf("")

    private var showLogin by mutableStateOf(true)
    private var loginStatus by mutableStateOf("")
    /** Transient, shown as a snackbar and cleared on a timer. */
    private var statusMessage by mutableStateOf("")

    /** Persistent, for failures that need to stay on screen. */
    private var errorMessage by mutableStateOf("")

    /** 0 all, 1 unplayed, 2 in progress. */
    private var episodeFilter by mutableStateOf(0)

    private var scrubbing by mutableStateOf(false)
    private var scrubFraction by mutableStateOf(0f)

    private var podcastResults by mutableStateOf<List<PodcastResult>>(emptyList())

    private var searchField by mutableStateOf("")
    private var searchSongs by mutableStateOf<List<Song>>(emptyList())

    private var actionTarget by mutableStateOf<MediaItem?>(null)

    /** True while a live stream is loaded: no duration, nothing to seek. */
    private var isRadio by mutableStateOf(false)

    /** What the station says it is playing, pushed over as session extras. */
    private var streamTitle by mutableStateOf("")

    private var showAddStation by mutableStateOf(false)
    private var stationNameField by mutableStateOf("")
    private var stationUrlField by mutableStateOf("")

    /** Deleting a station removes it from the server, so it takes two taps. */
    private var deleteArmed by mutableStateOf(false)

    private var serverField by mutableStateOf("")
    private var usernameField by mutableStateOf("")
    private var passwordField by mutableStateOf("")
    private var bitrateField by mutableStateOf(NavidromeApi.DEFAULT_BITRATE.toString())

    private val tickHandler = Handler(Looper.getMainLooper())
    private val statusClear = Runnable { statusMessage = "" }
    private val positionTick = object : Runnable {
        override fun run() = refreshPosition()
    }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            syncFromPlayer(player)
        }
    }

    private val sessionListener = object : MediaBrowser.Listener {
        override fun onExtrasChanged(controller: MediaController, extras: Bundle) {
            streamTitle = extras.getString(MusicService.EXTRA_STREAM_TITLE).orEmpty()
            browser?.let { syncFromPlayer(it) }
        }
    }

    private val currentStack: MutableList<Pair<String, String>>
        get() = stacks.getValue(section)

    /** Where a section starts when nothing has been drilled into. */
    private fun rootOf(target: Section): String = when (target) {
        Section.LIBRARY -> when (libraryTab) {
            1 -> MusicService.CAT_ALBUMS
            2 -> MusicService.CAT_PLAYLISTS
            3 -> MusicService.CAT_RADIO
            else -> MusicService.CAT_ARTISTS
        }
        Section.PODCASTS -> MusicService.CAT_PODCASTS
        Section.QUEUE -> MusicService.CAT_QUEUE
        Section.SEARCH -> ""
    }

    private val currentMediaId: String
        get() = currentStack.lastOrNull()?.first ?: rootOf(section)

    private val isRadioTab: Boolean
        get() = section == Section.LIBRARY && libraryTab == 3 && currentStack.isEmpty()

    /** Episode filters apply only inside a podcast feed. */
    private val visibleRows: List<MediaItem>
        get() {
            if (section != Section.PODCASTS || currentStack.isEmpty() || episodeFilter == 0) {
                return rows
            }
            return rows.filter { item ->
                val extras = item.mediaMetadata.extras
                val progress = extras?.getFloat(MusicService.EXTRA_PROGRESS) ?: 0f
                val played = extras?.getBoolean(MusicService.EXTRA_PLAYED) ?: false
                when (episodeFilter) {
                    1 -> !played && progress <= 0f
                    2 -> progress > 0f
                    else -> true
                }
            }
        }

    private val currentTitle: String
        get() = currentStack.lastOrNull()?.second ?: when (section) {
            Section.LIBRARY -> "Library"
            Section.PODCASTS -> "Podcasts"
            Section.QUEUE -> "Queue"
            Section.SEARCH -> "Search"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = AudioManager.STREAM_MUSIC

        api = NavidromeApi(this)
        resume = ResumeStore(this)
        requestNotificationPermissionIfNeeded()

        if (api.isConfigured()) {
            val prefs = getSharedPreferences(NavidromeApi.PREFS, MODE_PRIVATE)
            serverField = api.server
            usernameField = prefs.getString("username", "") ?: ""
            passwordField = prefs.getString("password", "") ?: ""
            bitrateField = api.maxBitRate.toString()
            showLogin = false
        }

        crashReport = CrashLog.read(this)

        setContent {
            ThemeMMD {
                BackHandler(enabled = !showLogin && (showNowPlaying || currentStack.isNotEmpty())) {
                    onBack()
                }
                when {
                    crashReport.isNotEmpty() -> CrashScreen()
                    showLogin -> LoginScreen()
                    showNowPlaying -> NowPlayingScreen()
                    else -> MainShell()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // A pending report means the last run died. Connecting starts the
        // playback service, which is where a launch crash is most likely to
        // come from - so hold off until the report has been read, or the app
        // would crash again before it could be shown.
        if (crashReport.isNotEmpty()) return
        connectBrowser()
    }

    override fun onStop() {
        super.onStop()
        savePlaybackState()
        tickHandler.removeCallbacks(positionTick)
        browser?.removeListener(playerListener)
        browserFuture?.let { MediaBrowser.releaseFuture(it) }
        browserFuture = null
        browser = null
    }

    private fun savePlaybackState() {
        val browser = this.browser ?: return
        val item = browser.currentMediaItem ?: return
        val duration = browser.duration

        resume.save(
            item.mediaId.substringAfterLast('/'),
            browser.currentPosition,
            if (duration == C.TIME_UNSET) 0L else duration,
        )
        resume.saveLast(item.mediaId, item.mediaMetadata.title?.toString() ?: "")
    }

    // ---------- Service connection ----------

    private fun connectBrowser() {
        val token = SessionToken(this, ComponentName(this, MusicService::class.java))
        val future = MediaBrowser.Builder(this, token)
            .setListener(sessionListener)
            .buildAsync()
        browserFuture = future
        future.addListener(
            {
                val connected = try {
                    future.get()
                } catch (e: Exception) {
                    loginStatus = "Playback service unavailable"
                    return@addListener
                }
                browser = connected
                connected.addListener(playerListener)
                // Reconnecting mid-stream should not lose the current track
                streamTitle = connected.sessionExtras
                    .getString(MusicService.EXTRA_STREAM_TITLE)
                    .orEmpty()
                syncFromPlayer(connected)
                loadCurrent()
            },
            MoreExecutors.directExecutor(),
        )
    }

    private fun syncFromPlayer(player: Player) {
        isPlaying = player.isPlaying
        isBuffering = player.playbackState == Player.STATE_BUFFERING
        hasMedia = player.mediaItemCount > 0
        playingMediaId = player.currentMediaItem?.mediaId
        isRadio = playingMediaId?.startsWith(MusicService.PREFIX_RADIO) == true

        val itemTitle = player.currentMediaItem?.mediaMetadata?.title?.toString()
        val itemSubtitle = player.currentMediaItem?.mediaMetadata?.subtitle?.toString()
        nowPlayingTitle = itemTitle

        // The station stays the title - it is the thing you chose - and
        // whatever it says it is playing becomes the subtitle.
        nowPlayingSubtitle = if (isRadio) {
            streamTitle.takeIf { it.isNotBlank() && it != itemTitle } ?: itemSubtitle
        } else {
            itemSubtitle
        }
        durationMs = player.duration.let { if (it == C.TIME_UNSET) 0L else it }
        queueCount = player.mediaItemCount
        queueIndex = player.currentMediaItemIndex.coerceAtLeast(0)
        speed = player.playbackParameters.speed
        refreshPosition()
    }

    // ---------- Browsing ----------

    /**
     * Renders whatever is cached straight away and refreshes behind it. The
     * list is never blanked first: clearing then repainting costs two E-Ink
     * refreshes and shows an empty screen in between.
     */
    private fun loadCurrent() {
        val browser = this.browser ?: return
        if (section == Section.SEARCH) return

        val mediaId = currentMediaId
        val cached = browseCache[mediaId]
        rows = cached ?: emptyList()
        loading = cached == null
        errorMessage = ""

        val future = browser.getChildren(mediaId, 0, PAGE_SIZE, null)
        future.addListener(
            {
                val result = try {
                    future.get()
                } catch (e: Exception) {
                    loading = false
                    errorMessage = "Request failed: ${e.javaClass.simpleName}: ${e.message}"
                    return@addListener
                }
                if (currentMediaId != mediaId) return@addListener
                loading = false

                val children = result.value
                when {
                    children == null ->
                        errorMessage = "Browse error, result code ${result.resultCode}"
                    else -> {
                        browseCache[mediaId] = children
                        rows = children
                        errorMessage = ""
                    }
                }
            },
            MoreExecutors.directExecutor(),
        )
    }

    private fun switchSection(target: Section) {
        if (section == target) {
            // Tapping the current destination again returns to its root
            if (currentStack.isNotEmpty()) {
                currentStack.clear()
                loadCurrent()
            }
            return
        }
        section = target
        errorMessage = ""
        loadCurrent()
    }

    private fun selectLibraryTab(index: Int) {
        if (libraryTab == index) return
        libraryTab = index
        currentStack.clear()
        loadCurrent()
    }

    private fun onBack() {
        when {
            showNowPlaying -> showNowPlaying = false
            currentStack.isNotEmpty() -> {
                currentStack.removeAt(currentStack.size - 1)
                loadCurrent()
            }
        }
    }

    private fun onRowTapped(item: MediaItem) {
        val mediaId = item.mediaId
        if (mediaId.isEmpty()) return
        val meta = item.mediaMetadata

        queueIndexOf(mediaId)?.let { index ->
            sendQueueEdit(MusicService.OP_QUEUE_PLAY, index)
            return
        }

        when {
            meta.isBrowsable == true -> {
                currentStack.add(mediaId to (meta.title?.toString() ?: ""))
                loadCurrent()
            }
            meta.isPlayable == true -> play(item)
            else -> Unit
        }
    }

    private fun play(item: MediaItem) {
        val browser = this.browser ?: return

        // A station plays alone. Starting one the way a track starts would load
        // every other station behind it, and since a live stream never ends,
        // nothing would ever reach them.
        if (item.mediaId.startsWith(MusicService.PREFIX_RADIO)) {
            browser.setMediaItem(item)
            browser.prepare()
            browser.play()
            return
        }

        val playables = visibleRows.filter { it.mediaMetadata.isPlayable == true }
        val startIndex = playables.indexOfFirst { it.mediaId == item.mediaId }

        if (startIndex < 0) browser.setMediaItem(item)
        else browser.setMediaItems(playables, startIndex, C.TIME_UNSET)

        browser.prepare()
        browser.play()
    }

    // ---------- Queue ----------

    /**
     * Queueing adds and nothing else. It used to start playing when the queue
     * was empty, which meant lining up a few episodes before a walk began the
     * first one over whatever you were already doing. An empty queue is not a
     * reason to assume you meant play - that is what tapping the row is for.
     *
     * The session prepares an idle player when play is finally pressed, so
     * leaving it unprepared here costs nothing.
     */
    private fun addToQueue(item: MediaItem) {
        sendQueueEdit(MusicService.OP_QUEUE_ADD, 0, item = item)
        showStatus("Queued ${item.mediaMetadata.title}")
    }

    private fun playNext(item: MediaItem) {
        sendQueueEdit(MusicService.OP_QUEUE_ADD_NEXT, 0, item = item)
        showStatus("Playing next: ${item.mediaMetadata.title}")
    }

    private fun clearQueue() {
        sendQueueEdit(MusicService.OP_QUEUE_CLEAR, 0)
        showStatus("Queue cleared")
    }

    private fun removeFromQueue(index: Int) {
        sendQueueEdit(MusicService.OP_QUEUE_REMOVE, index)
    }

    /**
     * Reorders by one place; the sheet offers it as Move up and Move down.
     * Bounds are the service's to check - while a station is playing the
     * player's item count is 1 and says nothing about the queue's length.
     */
    private fun moveInQueue(index: Int, to: Int) {
        sendQueueEdit(MusicService.OP_QUEUE_MOVE, index, to)
    }

    /**
     * Every queue edit goes to the service rather than to the player directly.
     * While a station is playing the queue is not in the player at all, and only
     * the service knows that - so it decides whether an edit lands on the
     * timeline or on the stored copy.
     */
    private fun sendQueueEdit(
        op: String,
        index: Int,
        to: Int = -1,
        item: MediaItem? = null,
    ) {
        val browser = this.browser ?: return
        actionTarget = null

        val future = browser.sendCustomCommand(
            SessionCommand(MusicService.COMMAND_QUEUE_EDIT, Bundle.EMPTY),
            Bundle().apply {
                putString(MusicService.ARG_QUEUE_OP, op)
                putInt(MusicService.ARG_QUEUE_INDEX, index)
                putInt(MusicService.ARG_QUEUE_TO, to)
                item?.let {
                    putString(MusicService.ARG_QUEUE_MEDIA_ID, it.mediaId)
                    putString(
                        MusicService.ARG_QUEUE_TITLE,
                        it.mediaMetadata.title?.toString() ?: "",
                    )
                    putString(
                        MusicService.ARG_QUEUE_SUBTITLE,
                        it.mediaMetadata.subtitle?.toString() ?: "",
                    )
                }
            },
        )
        // Reload once the edit has actually been applied, or the list would be
        // rebuilt from the state it had a moment ago
        future.addListener(
            {
                // loadCurrent talks to the browser, which is application-thread
                // only, and the future need not complete on it
                runOnUiThread {
                    browseCache.remove(MusicService.CAT_QUEUE)
                    loadCurrent()
                }
            },
            MoreExecutors.directExecutor(),
        )
    }

    private fun queueIndexOf(mediaId: String): Int? =
        if (mediaId.startsWith(MusicService.PREFIX_QUEUE)) {
            mediaId.removePrefix(MusicService.PREFIX_QUEUE).toIntOrNull()
        } else {
            null
        }

    // ---------- Search ----------

    private fun runSearch() {
        val term = searchField.trim()
        if (term.isEmpty()) return

        // A pasted feed URL is a subscription, not a search
        if (term.startsWith("http://", true) || term.startsWith("https://", true)) {
            addFeed(term)
            searchField = ""
            return
        }

        showStatus("Searching…")
        searchSongs = emptyList()
        podcastResults = emptyList()

        Thread {
            val songs = try {
                if (api.isConfigured()) api.search(term) else emptyList()
            } catch (e: Exception) {
                emptyList()
            }
            val casts = try {
                PodcastSearch.search(term)
            } catch (e: Exception) {
                emptyList()
            }
            runOnUiThread {
                searchSongs = songs
                podcastResults = casts
                if (songs.isEmpty() && casts.isEmpty()) showStatus("Nothing found for \"$term\"")
            }
        }.start()
    }

    private fun playSearchResult(song: Song) {
        val browser = this.browser ?: return
        if (song.albumId.isEmpty()) {
            showStatus("That track has no album to play from")
            return
        }
        val mediaId = "${MusicService.PREFIX_TRACK}${MusicService.PREFIX_ALBUM}" +
            "${song.albumId}/${song.id}"
        browser.setMediaItem(
            MediaItem.Builder()
                .setMediaId(mediaId)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(song.title)
                        .setSubtitle(song.artist)
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .build()
                )
                .build()
        )
        browser.prepare()
        browser.play()
    }

    // ---------- Feeds ----------

    private fun addFeed(url: String) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return
        showStatus("Fetching feed…")
        podcastResults = emptyList()

        Thread {
            val message = try {
                val feed = PodcastFeed.fetch(trimmed)
                PodcastStore(this).save(feed)
                "Subscribed to ${feed.title}"
            } catch (e: Exception) {
                "Couldn't add feed: ${e.javaClass.simpleName}: ${e.message}"
            }
            runOnUiThread {
                showStatus(message)
                browseCache.remove(MusicService.CAT_PODCASTS)
                loadCurrent()
            }
        }.start()
    }

    // ---------- Radio ----------

    /**
     * Writes to Navidrome rather than keeping a local list, so a station added
     * on the phone is there in the web UI too. Navidrome may refuse if the
     * account is not an admin, in which case its own message is what shows.
     */
    private fun addStation() {
        val name = stationNameField.trim()
        val url = stationUrlField.trim()
        if (name.isEmpty() || url.isEmpty()) {
            showStatus("A station needs both a name and a URL")
            return
        }
        showAddStation = false
        showStatus("Adding station…")

        Thread {
            val message = try {
                api.createRadioStation(name, url)
                "Added $name"
            } catch (e: Exception) {
                "Couldn't add station: ${e.message ?: e.javaClass.simpleName}"
            }
            runOnUiThread {
                showStatus(message)
                stationNameField = ""
                stationUrlField = ""
                refreshRadio()
            }
        }.start()
    }

    private fun deleteStation(item: MediaItem) {
        val id = item.mediaId.removePrefix(MusicService.PREFIX_RADIO)
        val name = item.mediaMetadata.title?.toString() ?: "station"
        actionTarget = null
        deleteArmed = false

        Thread {
            val message = try {
                api.deleteRadioStation(id)
                "Deleted $name"
            } catch (e: Exception) {
                "Couldn't delete: ${e.message ?: e.javaClass.simpleName}"
            }
            runOnUiThread {
                showStatus(message)
                refreshRadio()
            }
        }.start()
    }

    private fun refreshRadio() {
        browseCache.remove(MusicService.CAT_RADIO)
        loadCurrent()
    }

    // ---------- Feeds ----------

    /** A subscription row in the Podcasts list, as opposed to an episode. */
    private fun isFeedRow(item: MediaItem): Boolean =
        item.mediaId.startsWith(MusicService.PREFIX_PODCAST) &&
            item.mediaMetadata.isBrowsable == true

    /**
     * Unsubscribing is local - the feed belongs to nobody but this phone - but
     * it throws away the episode cache and every resume point becomes
     * unreachable, so it takes a second tap like deleting a station does.
     */
    private fun unsubscribe(item: MediaItem) {
        val feedId = item.mediaId.removePrefix(MusicService.PREFIX_PODCAST)
        val title = item.mediaMetadata.title?.toString() ?: "podcast"
        actionTarget = null
        deleteArmed = false

        PodcastStore(this).remove(feedId)
        showStatus("Unsubscribed from $title")
        browseCache.remove(MusicService.CAT_PODCASTS)
        loadCurrent()
    }

    // ---------- Transport ----------

    private fun togglePlayPause() {
        val browser = this.browser ?: return
        if (browser.isPlaying) browser.pause() else browser.play()
    }

    private fun cycleSpeed() {
        val browser = this.browser ?: return
        val next = SPEEDS[(SPEEDS.indexOfFirst { it == speed }.takeIf { it >= 0 }
            ?.plus(1) ?: 1) % SPEEDS.size]
        browser.setPlaybackSpeed(next)
        speed = next
    }

    private fun cycleSleepTimer() {
        val browser = this.browser ?: return
        val next = SLEEP_MINUTES[
            (SLEEP_MINUTES.indexOf(sleepMinutes).takeIf { it >= 0 }?.plus(1) ?: 1) %
                SLEEP_MINUTES.size
        ]
        sleepMinutes = next
        browser.sendCustomCommand(
            SessionCommand(MusicService.COMMAND_SLEEP_TIMER, Bundle.EMPTY),
            Bundle().apply { putInt(MusicService.ARG_SLEEP_MINUTES, next) },
        )
        showStatus(if (next == 0) "Sleep timer off" else "Sleeping in $next min")
    }

    /** Transient feedback: shown as a snackbar and cleared on its own. */
    private fun showStatus(message: String) {
        statusMessage = message
        tickHandler.removeCallbacks(statusClear)
        tickHandler.postDelayed(statusClear, 4_000L)
    }

    private fun refreshPosition() {
        tickHandler.removeCallbacks(positionTick)
        val browser = this.browser ?: return
        positionMs = browser.currentPosition.coerceAtLeast(0L)
        if (!browser.isPlaying) return

        // Aim at the next whole second rather than a second from now. Every
        // player event refreshes the position, and each one cancels and re-arms
        // this timer - so a fixed delay let an event mid-second push the clock
        // past a tick, which is what made it look stopped rather than coarse.
        // Divided by speed because at 1.5x a second of audio takes two thirds
        // of a second to play.
        val speed = browser.playbackParameters.speed.coerceAtLeast(0.1f)
        val untilNextSecond = TICK_MS - (positionMs % TICK_MS)
        val delay = (untilNextSecond / speed).toLong().coerceIn(MIN_TICK_MS, TICK_MS)
        tickHandler.postDelayed(positionTick, delay)
    }

    // ---------- Shell ----------

    /**
     * A title bar that names the screen in the middle of it.
     *
     * Hand-built rather than TopAppBarMMD, which wraps Material 3's start-aligned
     * TopAppBar: its title slot begins after the navigation icon, so centring
     * inside that slot centres on the space left over rather than on the screen,
     * and drifts as the two edges change width. Equal fixed side slots make the
     * title land in the middle whatever sits beside it.
     */
    @Composable
    private fun ScreenBar(
        title: String,
        start: @Composable () -> Unit = {},
        end: @Composable () -> Unit = {},
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(white)
                .defaultMinSize(minHeight = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.width(BAR_SLOT),
                contentAlignment = Alignment.CenterStart,
                content = { start() },
            )
            TextMMD(
                text = title,
                style = MaterialTheme.typography.titleMedium.heavy(),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier.width(BAR_SLOT),
                contentAlignment = Alignment.CenterEnd,
                content = { end() },
            )
        }
        HorizontalDividerMMD()
    }

    /** A bar action: a word you can tap, sized for a thumb. */
    @Composable
    private fun BarAction(label: String, onClick: () -> Unit) {
        TextMMD(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelMedium.chrome(),
            maxLines = 1,
            modifier = Modifier
                .clickable { onClick() }
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }

    @Composable
    private fun MainShell() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(white),
        ) {
            ScreenBar(
                title = currentTitle,
                start = {
                    if (currentStack.isNotEmpty()) BarAction("Back") { onBack() }
                },
                end = {
                    when {
                        section == Section.PODCASTS && currentStack.isEmpty() ->
                            BarAction("Add") { switchSection(Section.SEARCH) }
                        // A station needs a name as well as a URL, so it gets
                        // its own form rather than going through Search
                        isRadioTab -> BarAction("Add") { showAddStation = true }
                        // How many are lined up, said where the screen names
                        // itself rather than crowded into the bottom bar
                        section == Section.QUEUE && queueCount > 0 -> TextMMD(
                            text = "$queueCount",
                            style = MaterialTheme.typography.labelMedium.chrome(),
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                },
            )

            // Tabs replace a whole level of drilling in the library
            if (section == Section.LIBRARY && currentStack.isEmpty()) {
                PrimaryTabRowMMD(selectedTabIndex = libraryTab) {
                    // Radio belongs here rather than in the bottom bar: stations
                    // are the server's, alongside albums and playlists, and a
                    // fifth destination would not fit this screen's width.
                    listOf("Artists", "Albums", "Playlists", "Radio").forEachIndexed { index, label ->
                        TabMMD(
                            selected = libraryTab == index,
                            onClick = { selectLibraryTab(index) },
                            text = {
                                TextMMD(label.uppercase(), style = MaterialTheme.typography.labelMedium.chrome())
                            },
                        )
                    }
                }
            }

            // Filtering an episode list is the one place a second tab row earns
            // its height: a long feed is mostly things you have already heard
            if (section == Section.PODCASTS && currentStack.isNotEmpty()) {
                PrimaryTabRowMMD(selectedTabIndex = episodeFilter) {
                    listOf("All", "Unplayed", "Started").forEachIndexed { index, label ->
                        TabMMD(
                            selected = episodeFilter == index,
                            onClick = { episodeFilter = index },
                            text = {
                                TextMMD(label.uppercase(), style = MaterialTheme.typography.labelMedium.chrome())
                            },
                        )
                    }
                }
            }

            StatusLine()

            Box(modifier = Modifier.weight(1f)) {
                if (section == Section.SEARCH) SearchScreen() else BrowseList()

                if (statusMessage.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(12.dp),
                    ) {
                        SnackbarMMD {
                            TextMMD(
                                text = statusMessage,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            if (hasMedia) MiniPlayer()
            BottomNav()
        }

        // Sheets rather than inline panels, which used to shove the list around
        actionTarget?.let { QueueActionsSheet(it) }
        if (showAddStation) AddStationSheet()
    }

    @Composable
    private fun StatusLine() {
        val text = when {
            errorMessage.isNotEmpty() -> errorMessage
            loading -> "Loading…"
            else -> ""
        }
        if (text.isEmpty()) return
        TextMMD(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
        HorizontalDividerMMD()
    }

    @Composable
    private fun BrowseList() {
        LazyColumnMMD(
            modifier = Modifier.fillMaxSize(),
            // Three rows per drag rather than four: four read as the list
            // over-reacting to a gesture
            scrollStep = 3,
        ) {
            val visible = visibleRows
            itemsIndexed(visible) { index, item ->
                BrowseRow(item)
                if (index < visible.lastIndex) HorizontalDividerMMD()
            }
        }
    }

    @Composable
    private fun BottomNav() {
        NavigationBarMMD {
            NavItem("Library", Section.LIBRARY)
            NavItem("Podcasts", Section.PODCASTS)
            NavItem("Queue", Section.QUEUE)
            NavItem("Search", Section.SEARCH)
        }
    }

    /**
     * The current destination is marked by weight and an underline rather than
     * by filling the cell black. A solid black block is the loudest mark on the
     * screen, and spending it on the bar you are already looking at drew the eye
     * downwards, away from the content. The underline is MMD's own tab
     * indicator: 3dp, square-ended.
     */
    @Composable
    private fun RowScope.NavItem(label: String, target: Section) {
        val selected = section == target
        Box(
            modifier = Modifier
                .weight(1f)
                .defaultMinSize(minHeight = 48.dp)
                .background(white)
                .clickable { switchSection(target) },
            contentAlignment = Alignment.Center,
        ) {
            TextMMD(
                // The count moved to the title bar, where the screen names
                // itself, so the destination can just be the destination
                text = label.uppercase(),
                color = black,
                // Bold against regular, rather than Black against bold: at this
                // size Black closes up, and the underline is carrying the mark
                // anyway
                style = if (selected) {
                    MaterialTheme.typography.labelSmall.chrome()
                } else {
                    MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.0.sp)
                },
                maxLines = 1,
            )
            if (selected) SelectionUnderline()
        }
    }

    /**
     * Two lines of type, no colour: hierarchy comes from size alone, because
     * MMD has no grey.
     *
     * Playing and pressed used to share one mark - the row filled black either
     * way - so they were indistinguishable, and the playing one sat filled
     * permanently, which is the worst thing to leave on an E-Ink panel. Now the
     * fill means only *your finger is here*, which is what an inversion is good
     * at, and the row that is playing is marked down its leading edge with its
     * title a weight heavier. Only one row is ever current, so the heaviest
     * weight reads as a marker rather than as the wall it would be stacked.
     */
    @Composable
    private fun BrowseRow(item: MediaItem) {
        val haptics = LocalHapticFeedback.current
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()

        val isCurrent = item.mediaId.isNotEmpty() && item.mediaId == playingMediaId
        val background = if (pressed) black else white
        val foreground = if (pressed) white else black

        val extras = item.mediaMetadata.extras
        val progress = extras?.getFloat(MusicService.EXTRA_PROGRESS) ?: 0f
        val played = extras?.getBoolean(MusicService.EXTRA_PLAYED) ?: false

        val baseSubtitle = item.mediaMetadata.subtitle?.toString() ?: ""
        val subtitle = if (played && progress <= 0f) {
            listOf("Played", baseSubtitle).filter { it.isNotEmpty() }.joinToString(" - ")
        } else {
            baseSubtitle
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(background)
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = { onRowTapped(item) },
                    onLongClick = {
                        // A subscribed feed is browsable rather than playable,
                        // so gating on playable alone left no way to hold it
                        if (item.mediaMetadata.isPlayable == true || isFeedRow(item)) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            actionTarget = item
                        }
                    },
                )
                .defaultMinSize(minHeight = 48.dp),
        ) {
            // Sits in the row's existing 16dp margin, so nothing shifts when a
            // different row becomes the current one
            if (isCurrent && !pressed) LeadingMark()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The queue is the one list where order is information rather
                // than decoration - it is what you are about to hear, in the
                // sequence you will hear it - so it is the one list numbered.
                queueIndexOf(item.mediaId)?.let { index ->
                    TextMMD(
                        text = "${index + 1}",
                        color = foreground,
                        style = MaterialTheme.typography.labelSmall.chrome(),
                        maxLines = 1,
                        modifier = Modifier.width(28.dp),
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    TextMMD(
                        text = item.mediaMetadata.title?.toString() ?: "",
                        color = foreground,
                        style = if (isCurrent) {
                            MaterialTheme.typography.bodyMedium.heavy()
                        } else {
                            MaterialTheme.typography.bodyMedium.strong()
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitle.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        TextMMD(
                            text = subtitle,
                            color = foreground,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (progress > 0f) {
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicatorMMD(
                            progress = { progress },
                            color = foreground,
                            borderColor = foreground,
                        )
                    }
                }

                // The mark and its absence both carry: a row that opens says so,
                // and a row without one plays. Nothing distinguished them before,
                // so the only way to find out was to tap.
                if (item.mediaMetadata.isBrowsable == true) {
                    Spacer(Modifier.width(12.dp))
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron),
                        contentDescription = null,
                        tint = foreground,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }

    // ---------- Mini player and Now Playing ----------

    @Composable
    private fun MiniPlayer() {
        HorizontalDividerMMD()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(white)
                .clickable { showNowPlaying = true }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                TextMMD(
                    text = nowPlayingTitle ?: "Nothing playing",
                    style = MaterialTheme.typography.bodySmall.strong(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isRadio) {
                    TextMMD(
                        text = nowPlayingSubtitle?.takeIf { it.isNotEmpty() } ?: "Live",
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else if (durationMs > 0L) {
                    TextMMD(
                        text = "${formatClockMs(positionMs)} / ${formatClockMs(durationMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                }
            }
            OutlinedButtonMMD(
                onClick = { togglePlayPause() },
                modifier = Modifier.defaultMinSize(minHeight = 44.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Icon(
                    painter = painterResource(
                        if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                    ),
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = black,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }

    @Composable
    private fun NowPlayingScreen() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(white),
        ) {
            ScreenBar(
                title = "Now playing",
                start = { BarAction("Close") { showNowPlaying = false } },
            )

            // Deliberately not scrollable: everything is sized to fit, so the
            // controls are always where you left them
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
            ) {
                // Where you are in the run. Both numbers were already in state
                // and neither was drawn, so four episodes into a queue of twelve
                // looked exactly like the first.
                if (!isRadio && queueCount > 1) {
                    TextMMD(
                        text = "${queueIndex + 1} OF $queueCount",
                        style = MaterialTheme.typography.labelSmall.chrome(),
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(6.dp))
                }
                TextMMD(
                    text = nowPlayingTitle ?: "Nothing playing",
                    style = MaterialTheme.typography.titleLarge.heavy(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                nowPlayingSubtitle?.takeIf { it.isNotEmpty() }?.let {
                    Spacer(Modifier.height(8.dp))
                    TextMMD(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(Modifier.weight(1f))

                if (isRadio) {
                    // Nothing to scrub and nothing to count down to. The clock
                    // is time spent listening, which is what a sleep timer is
                    // set against.
                    Row(modifier = Modifier.fillMaxWidth()) {
                        TextMMD(
                            text = if (isBuffering) "Live - connecting" else "Live",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextMMD(
                            text = formatClockMs(positionMs),
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.End,
                        )
                    }
                } else if (durationMs > 0L) {
                    SliderMMD(
                        value = if (scrubbing) {
                            scrubFraction
                        } else {
                            (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
                        },
                        onValueChange = {
                            scrubbing = true
                            scrubFraction = it
                        },
                        onValueChangeFinished = {
                            browser?.seekTo((scrubFraction * durationMs).toLong())
                            scrubbing = false
                            refreshPosition()
                        },
                        // A square on its corner, because a round handle at this
                        // size dithers into a smudge and stops reading as a thing
                        // you can take hold of.
                        thumb = {
                            Box(
                                modifier = Modifier
                                    .size(16.dp)
                                    .rotate(45f)
                                    .background(black),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        TextMMD(
                            text = formatClockMs(positionMs),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextMMD(
                            text = "-${formatClockMs((durationMs - positionMs).coerceAtLeast(0L))}",
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.End,
                        )
                    }
                } else if (isBuffering) {
                    TextMMD("Loading", style = MaterialTheme.typography.bodySmall)
                }

                Spacer(Modifier.height(20.dp))

                // One row rather than two, so the screen fits without scrolling.
                // Play/pause still dominates through size; every icon is black
                // on white, so all five read the same way.
                //
                // Radio gets play/pause alone: there is nothing to skip to, and
                // +/-15 on a live stream either does nothing or drops you out
                // of the buffer.
                if (isRadio) {
                    IconAction(
                        icon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                        label = if (isPlaying) "Pause" else "Play",
                        modifier = Modifier.fillMaxWidth(),
                        minHeight = 72.dp,
                        iconSize = 34.dp,
                    ) { togglePlayPause() }
                } else Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconAction(
                        icon = R.drawable.ic_prev,
                        label = "Previous",
                        modifier = Modifier.weight(1f),
                        minHeight = 56.dp,
                    ) { browser?.seekToPreviousMediaItem() }

                    LabelAction("-15", Modifier.weight(1f), minHeight = 56.dp) {
                        browser?.seekBack()
                    }

                    IconAction(
                        icon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                        label = if (isPlaying) "Pause" else "Play",
                        modifier = Modifier.weight(1.5f),
                        minHeight = 72.dp,
                        iconSize = 34.dp,
                    ) { togglePlayPause() }

                    LabelAction("+15", Modifier.weight(1f), minHeight = 56.dp) {
                        browser?.seekForward()
                    }

                    IconAction(
                        icon = R.drawable.ic_next,
                        label = "Next",
                        modifier = Modifier.weight(1f),
                        minHeight = 56.dp,
                    ) { browser?.seekToNextMediaItem() }
                }

                Spacer(Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Speed is for recordings. Playing a live stream faster than
                    // it arrives only empties the buffer.
                    if (!isRadio) {
                        SmallAction("Speed ${speedLabel(speed)}", Modifier.weight(1f)) {
                            cycleSpeed()
                        }
                    }
                    SmallAction(
                        if (sleepMinutes == 0) "Sleep off" else "Sleep $sleepMinutes",
                        Modifier.weight(1f),
                    ) { cycleSleepTimer() }
                }
                // The queue has its own destination in the bottom bar, so a
                // button for it here only pushed the screen past its height

                Spacer(Modifier.weight(1f))
            }
        }
    }

    /** Black icon on white, outlined: the transport controls read as one set. */
    @Composable
    private fun IconAction(
        icon: Int,
        label: String,
        modifier: Modifier = Modifier,
        minHeight: Dp = 56.dp,
        iconSize: Dp = 26.dp,
        onClick: () -> Unit,
    ) {
        OutlinedButtonMMD(
            onClick = onClick,
            modifier = modifier.defaultMinSize(minHeight = minHeight),
            contentPadding = PaddingValues(2.dp),
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = label,
                tint = black,
                modifier = Modifier.size(iconSize),
            )
        }
    }

    @Composable
    private fun LabelAction(
        label: String,
        modifier: Modifier = Modifier,
        minHeight: Dp = 56.dp,
        onClick: () -> Unit,
    ) {
        OutlinedButtonMMD(
            onClick = onClick,
            modifier = modifier.defaultMinSize(minHeight = minHeight),
            contentPadding = PaddingValues(2.dp),
        ) {
            TextMMD(label, style = MaterialTheme.typography.labelMedium.chrome(), maxLines = 1)
        }
    }

    private fun speedLabel(value: Float): String =
        if (value == value.toInt().toFloat()) "${value.toInt()}x" else "${value}x"

    @Composable
    private fun SmallAction(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
        OutlinedButtonMMD(
            onClick = onClick,
            modifier = modifier.defaultMinSize(minHeight = 48.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        ) {
            TextMMD(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelMedium.chrome(),
            maxLines = 1,
        )
        }
    }

    // ---------- Panels ----------

    @Composable
    private fun QueueActionsSheet(item: MediaItem) {
        ModalBottomSheetMMD(
            onDismissRequest = {
                actionTarget = null
                deleteArmed = false
            },
            sheetState = rememberModalBottomSheetMMDState(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                // Uncapped: rows have one line to spend on a title and podcast
                // episodes routinely need three, so holding one is how you read
                // the rest of it
                TextMMD(
                    text = item.mediaMetadata.title?.toString() ?: "",
                    style = MaterialTheme.typography.bodyMedium.strong(),
                )
                item.mediaMetadata.subtitle?.toString()?.takeIf { it.isNotEmpty() }?.let {
                    Spacer(Modifier.height(4.dp))
                    TextMMD(text = it, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val queueIndex = queueIndexOf(item.mediaId)
                    when {
                        // Queueing an endless stream makes no sense, so the one
                        // useful thing to offer on a station is removing it.
                        // That deletes it from Navidrome for everyone, hence
                        // the second tap.
                        item.mediaId.startsWith(MusicService.PREFIX_RADIO) ->
                            SmallAction(
                                if (deleteArmed) "Tap again to delete" else "Delete station",
                                Modifier.weight(1f),
                            ) {
                                if (deleteArmed) deleteStation(item) else deleteArmed = true
                            }

                        isFeedRow(item) ->
                            SmallAction(
                                if (deleteArmed) "Tap again to remove" else "Unsubscribe",
                                Modifier.weight(1f),
                            ) {
                                if (deleteArmed) unsubscribe(item) else deleteArmed = true
                            }

                        queueIndex != null -> {
                            SmallAction("Move up", Modifier.weight(1f)) {
                                moveInQueue(queueIndex, queueIndex - 1)
                            }
                            SmallAction("Move down", Modifier.weight(1f)) {
                                moveInQueue(queueIndex, queueIndex + 1)
                            }
                        }

                        else -> {
                            SmallAction("Play next", Modifier.weight(1f)) { playNext(item) }
                            SmallAction("Queue", Modifier.weight(1f)) { addToQueue(item) }
                        }
                    }
                }

                // Four actions do not fit across this screen, and reordering is
                // the one you reach for repeatedly, so it gets the first row
                queueIndexOf(item.mediaId)?.let { queueIndex ->
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SmallAction("Remove", Modifier.weight(1f)) {
                            removeFromQueue(queueIndex)
                        }
                        SmallAction("Clear all", Modifier.weight(1f)) { clearQueue() }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    @Composable
    private fun AddStationSheet() {
        ModalBottomSheetMMD(
            onDismissRequest = { showAddStation = false },
            sheetState = rememberModalBottomSheetMMDState(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                TextMMD("Add a station", style = MaterialTheme.typography.bodyMedium.strong())
                Spacer(Modifier.height(12.dp))

                TextFieldMMD(
                    value = stationNameField,
                    onValueChange = { stationNameField = it },
                    label = { TextMMD("Name", style = MaterialTheme.typography.labelMedium) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))

                TextFieldMMD(
                    value = stationUrlField,
                    onValueChange = { stationUrlField = it },
                    label = {
                        TextMMD("Stream URL", style = MaterialTheme.typography.labelMedium)
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { addStation() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))

                ButtonMMD(
                    onClick = { addStation() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp),
                ) {
                    TextMMD("Add", style = MaterialTheme.typography.labelLarge.strong())
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    @Composable
    private fun SearchScreen() {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                TextFieldMMD(
                    value = searchField,
                    onValueChange = { searchField = it },
                    label = {
                        TextMMD(
                            "Tracks and podcasts",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                ButtonMMD(
                    onClick = { runSearch() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp),
                ) {
                    TextMMD("Search", style = MaterialTheme.typography.labelLarge.strong())
                }
            }
            HorizontalDividerMMD()

            LazyColumnMMD(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                scrollStep = 3,
            ) {
                if (searchSongs.isNotEmpty()) {
                    item { SectionHeading("Tracks") }
                    items(searchSongs.size) { i ->
                        val song = searchSongs[i]
                        SimpleRow(
                            title = song.title,
                            subtitle = listOf(song.artist, formatClock(song.duration))
                                .filter { it.isNotEmpty() }.joinToString(" - "),
                        ) { playSearchResult(song) }
                        HorizontalDividerMMD()
                    }
                }
                if (podcastResults.isNotEmpty()) {
                    item { SectionHeading("Podcasts") }
                    items(podcastResults.size) { i ->
                        val result = podcastResults[i]
                        SimpleRow(
                            title = result.name,
                            subtitle = result.author,
                        ) { addFeed(result.feedUrl) }
                        HorizontalDividerMMD()
                    }
                }
            }
        }
    }

    /**
     * A rule and a weight rather than a black band. The band put the loudest
     * mark on the screen behind the least important text on it, and left a
     * ghost across the width of the list it was scrolling over.
     */
    @Composable
    private fun SectionHeading(label: String) {
        TextMMD(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall.chrome(),
            modifier = Modifier
                .fillMaxWidth()
                .background(white)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            color = black,
        )
        HorizontalDividerMMD()
    }

    @Composable
    private fun SimpleRow(title: String, subtitle: String, onClick: () -> Unit) {
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val background = if (pressed) black else white
        val foreground = if (pressed) white else black

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(background)
                .clickable(interactionSource = interaction, indication = null) { onClick() }
                .defaultMinSize(minHeight = 48.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            TextMMD(
                text = title,
                color = foreground,
                style = MaterialTheme.typography.bodyMedium.strong(),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                TextMMD(
                    text = subtitle,
                    color = foreground,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    // ---------- Crash report ----------

    private fun dismissCrashReport() {
        CrashLog.clear(this)
        crashReport = ""
        // Startup was held back while the report was showing
        connectBrowser()
    }

    /**
     * Shown once after a crash, ahead of everything else. Sideloaded builds
     * have no adb behind them, so this is the only way the trace gets off the
     * phone - hence Copy rather than just Dismiss.
     */
    @Composable
    private fun CrashScreen() {
        val clipboard = LocalClipboardManager.current
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(white)
                .padding(horizontal = 16.dp, vertical = 16.dp),
        ) {
            TextMMD("It crashed", style = MaterialTheme.typography.titleLarge.heavy())
            Spacer(Modifier.height(4.dp))
            TextMMD(
                "Copy this and send it over - it says what went wrong.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                TextMMD(text = crashReport, style = MaterialTheme.typography.labelSmall)
            }

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SmallAction("Copy", Modifier.weight(1f)) {
                    clipboard.setText(AnnotatedString(crashReport))
                }
                SmallAction("Dismiss", Modifier.weight(1f)) {
                    dismissCrashReport()
                }
            }
            Spacer(Modifier.height(8.dp))

            // A crash that repeats on every launch is usually something saved
            // being read back. This drops the only two things startup touches
            // by itself, and leaves the login and resume positions alone.
            SmallAction("Clear queue and reopen", Modifier.fillMaxWidth()) {
                QueueStore(this@MainActivity).clear()
                resume.saveLast("", "")
                dismissCrashReport()
            }
        }
    }

    // ---------- Login ----------

    private fun onConnectClicked() {
        val server = serverField.trim().trimEnd('/')
        val username = usernameField.trim()
        val password = passwordField

        if (server.isEmpty() || username.isEmpty() || password.isEmpty()) {
            loginStatus = "Fill in all three fields"
            return
        }

        getSharedPreferences(NavidromeApi.PREFS, MODE_PRIVATE).edit()
            .putString("server", server)
            .putString("username", username)
            .putString("password", password)
            .putInt(
                "max_bitrate",
                bitrateField.trim().toIntOrNull() ?: NavidromeApi.DEFAULT_BITRATE,
            )
            .apply()

        loginStatus = ""
        showLogin = false
        browseCache.clear()
        if (browser == null) connectBrowser() else loadCurrent()
    }

    @Composable
    private fun LoginScreen() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(white)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
        ) {
            TextMMD("Navi player", style = MaterialTheme.typography.headlineLarge.heavy())
            Spacer(Modifier.height(24.dp))

            TextFieldMMD(
                value = serverField,
                onValueChange = { serverField = it },
                label = { TextMMD("Server", style = MaterialTheme.typography.labelMedium) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))

            TextFieldMMD(
                value = usernameField,
                onValueChange = { usernameField = it },
                label = { TextMMD("Username", style = MaterialTheme.typography.labelMedium) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))

            TextFieldMMD(
                value = passwordField,
                onValueChange = { passwordField = it },
                label = { TextMMD("Password", style = MaterialTheme.typography.labelMedium) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))

            TextFieldMMD(
                value = bitrateField,
                onValueChange = { bitrateField = it },
                label = {
                    TextMMD(
                        "Max kbps, 0 for original",
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(24.dp))

            ButtonMMD(
                onClick = { onConnectClicked() },
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp),
            ) {
                TextMMD("Connect", style = MaterialTheme.typography.labelLarge.strong())
            }

            if (loginStatus.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                TextMMD(loginStatus, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }
}
