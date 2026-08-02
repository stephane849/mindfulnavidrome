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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaBrowser
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
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
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
        private const val TICK_MS = 15_000L

        /** Bounded so a large library cannot overflow the Binder transaction. */
        private const val PAGE_SIZE = 400

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
    private var speed by mutableStateOf(1.0f)
    private var sleepMinutes by mutableStateOf(0)

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

    private var showAddFeed by mutableStateOf(false)
    private var feedUrlField by mutableStateOf("")
    private var podcastResults by mutableStateOf<List<PodcastResult>>(emptyList())

    private var searchField by mutableStateOf("")
    private var searchSongs by mutableStateOf<List<Song>>(emptyList())

    private var actionTarget by mutableStateOf<MediaItem?>(null)

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

    private val currentStack: MutableList<Pair<String, String>>
        get() = stacks.getValue(section)

    /** Where a section starts when nothing has been drilled into. */
    private fun rootOf(target: Section): String = when (target) {
        Section.LIBRARY -> when (libraryTab) {
            1 -> MusicService.CAT_ALBUMS
            2 -> MusicService.CAT_PLAYLISTS
            else -> MusicService.CAT_ARTISTS
        }
        Section.PODCASTS -> MusicService.CAT_PODCASTS
        Section.QUEUE -> MusicService.CAT_QUEUE
        Section.SEARCH -> ""
    }

    private val currentMediaId: String
        get() = currentStack.lastOrNull()?.first ?: rootOf(section)

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

        setContent {
            ThemeMMD {
                BackHandler(enabled = !showLogin && (showNowPlaying || currentStack.isNotEmpty())) {
                    onBack()
                }
                when {
                    showLogin -> LoginScreen()
                    showNowPlaying -> NowPlayingScreen()
                    else -> MainShell()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
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
        val future = MediaBrowser.Builder(this, token).buildAsync()
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
        nowPlayingTitle = player.currentMediaItem?.mediaMetadata?.title?.toString()
        nowPlayingSubtitle = player.currentMediaItem?.mediaMetadata?.subtitle?.toString()
        durationMs = player.duration.let { if (it == C.TIME_UNSET) 0L else it }
        queueCount = player.mediaItemCount
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
            browser?.seekTo(index, 0L)
            browser?.play()
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
        val playables = visibleRows.filter { it.mediaMetadata.isPlayable == true }
        val startIndex = playables.indexOfFirst { it.mediaId == item.mediaId }

        if (startIndex < 0) browser.setMediaItem(item)
        else browser.setMediaItems(playables, startIndex, C.TIME_UNSET)

        browser.prepare()
        browser.play()
    }

    // ---------- Queue ----------

    private fun addToQueue(item: MediaItem) {
        val browser = this.browser ?: return
        actionTarget = null
        if (browser.mediaItemCount == 0) {
            browser.setMediaItem(item)
            browser.prepare()
            browser.play()
            showStatus("Playing ${item.mediaMetadata.title}")
        } else {
            browser.addMediaItem(item)
            showStatus("Queued ${item.mediaMetadata.title}")
        }
    }

    private fun playNext(item: MediaItem) {
        val browser = this.browser ?: return
        actionTarget = null
        if (browser.mediaItemCount == 0) {
            addToQueue(item)
            return
        }
        browser.addMediaItem(browser.currentMediaItemIndex + 1, item)
        showStatus("Playing next: ${item.mediaMetadata.title}")
    }

    private fun clearQueue() {
        val browser = this.browser ?: return
        actionTarget = null
        browser.clearMediaItems()
        browseCache.remove(MusicService.CAT_QUEUE)
        showStatus("Queue cleared")
        loadCurrent()
    }

    private fun removeFromQueue(index: Int) {
        val browser = this.browser ?: return
        actionTarget = null
        if (index !in 0 until browser.mediaItemCount) return
        browser.removeMediaItem(index)
        browseCache.remove(MusicService.CAT_QUEUE)
        loadCurrent()
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

    private fun onFeedInputSubmitted() {
        val input = feedUrlField.trim()
        if (input.isEmpty()) return
        if (input.startsWith("http://", true) || input.startsWith("https://", true)) {
            addFeed(input)
        } else {
            // Discovery lives in the Search destination, which can show results
            showStatus("That is not a URL - use Search to find a podcast by name")
        }
    }

    private fun addFeed(url: String) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return
        showStatus("Fetching feed…")
        showAddFeed = false
        feedUrlField = ""
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
        if (browser.isPlaying) tickHandler.postDelayed(positionTick, TICK_MS)
    }

    // ---------- Shell ----------

    @Composable
    private fun MainShell() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(white),
        ) {
            TopAppBarMMD(
                title = {
                    TextMMD(
                        text = currentTitle,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (currentStack.isNotEmpty()) {
                        TextMMD(
                            text = "Back",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier
                                .clickable { onBack() }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                },
                actions = {
                    if (section == Section.PODCASTS && currentStack.isEmpty()) {
                        TextMMD(
                            text = if (showAddFeed) "Close" else "Add",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier
                                .clickable { showAddFeed = !showAddFeed }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                },
            )

            // Tabs replace a whole level of drilling in the library
            if (section == Section.LIBRARY && currentStack.isEmpty()) {
                PrimaryTabRowMMD(selectedTabIndex = libraryTab) {
                    listOf("Artists", "Albums", "Playlists").forEachIndexed { index, label ->
                        TabMMD(
                            selected = libraryTab == index,
                            onClick = { selectLibraryTab(index) },
                            text = {
                                TextMMD(label, style = MaterialTheme.typography.labelMedium)
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
                                TextMMD(label, style = MaterialTheme.typography.labelMedium)
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
        if (showAddFeed) AddFeedSheet()
        actionTarget?.let { QueueActionsSheet(it) }
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

    @Composable
    private fun RowScope.NavItem(label: String, target: Section) {
        val selected = section == target
        Box(
            modifier = Modifier
                .weight(1f)
                .defaultMinSize(minHeight = 48.dp)
                .background(if (selected) black else white)
                .clickable { switchSection(target) },
            contentAlignment = Alignment.Center,
        ) {
            TextMMD(
                text = if (target == Section.QUEUE && queueCount > 1) {
                    "$label $queueCount"
                } else {
                    label
                },
                color = if (selected) white else black,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
        }
    }

    /**
     * Two lines of type, no colour: hierarchy comes from size alone, because
     * MMD has no grey. Inverts while pressed as well as while playing - with
     * ripple disabled globally there is otherwise no sign a tap registered.
     */
    @Composable
    private fun BrowseRow(item: MediaItem) {
        val haptics = LocalHapticFeedback.current
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()

        val isCurrent = item.mediaId.isNotEmpty() && item.mediaId == playingMediaId
        val inverted = isCurrent || pressed
        val background = if (inverted) black else white
        val foreground = if (inverted) white else black

        val extras = item.mediaMetadata.extras
        val progress = extras?.getFloat(MusicService.EXTRA_PROGRESS) ?: 0f
        val played = extras?.getBoolean(MusicService.EXTRA_PLAYED) ?: false

        val baseSubtitle = item.mediaMetadata.subtitle?.toString() ?: ""
        val subtitle = if (played && progress <= 0f) {
            listOf("Played", baseSubtitle).filter { it.isNotEmpty() }.joinToString(" - ")
        } else {
            baseSubtitle
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(background)
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = { onRowTapped(item) },
                    onLongClick = {
                        if (item.mediaMetadata.isPlayable == true) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            actionTarget = item
                        }
                    },
                )
                .defaultMinSize(minHeight = 48.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            TextMMD(
                text = item.mediaMetadata.title?.toString() ?: "",
                color = foreground,
                style = MaterialTheme.typography.bodyMedium,
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
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (durationMs > 0L) {
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
                TextMMD(
                    text = if (isPlaying) "Pause" else "Play",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
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
            TopAppBarMMD(
                title = {
                    TextMMD(
                        text = "Now playing",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                    )
                },
                navigationIcon = {
                    TextMMD(
                        text = "Close",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier
                            .clickable { showNowPlaying = false }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                },
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 24.dp),
            ) {
                TextMMD(
                    text = nowPlayingTitle ?: "Nothing playing",
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 3,
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

                Spacer(Modifier.height(24.dp))

                if (durationMs > 0L) {
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

                Spacer(Modifier.height(24.dp))

                // Play/pause dominates, the 15s jumps flank it, track skipping
                // sits below at lower weight
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButtonMMD(
                        onClick = { browser?.seekBack() },
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 64.dp),
                        contentPadding = PaddingValues(4.dp),
                    ) {
                        TextMMD("-15", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    }
                    ButtonMMD(
                        onClick = { togglePlayPause() },
                        modifier = Modifier
                            .weight(1.4f)
                            .defaultMinSize(minHeight = 72.dp),
                        contentPadding = PaddingValues(4.dp),
                    ) {
                        TextMMD(
                            text = if (isPlaying) "Pause" else "Play",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                        )
                    }
                    OutlinedButtonMMD(
                        onClick = { browser?.seekForward() },
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 64.dp),
                        contentPadding = PaddingValues(4.dp),
                    ) {
                        TextMMD("+15", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    }
                }

                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SmallAction("Prev", Modifier.weight(1f)) {
                        browser?.seekToPreviousMediaItem()
                    }
                    SmallAction("Next", Modifier.weight(1f)) {
                        browser?.seekToNextMediaItem()
                    }
                }

                Spacer(Modifier.height(24.dp))
                HorizontalDividerMMD()
                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SmallAction("Speed ${speedLabel(speed)}", Modifier.weight(1f)) {
                        cycleSpeed()
                    }
                    SmallAction(
                        if (sleepMinutes == 0) "Sleep off" else "Sleep $sleepMinutes",
                        Modifier.weight(1f),
                    ) { cycleSleepTimer() }
                }

                if (queueCount > 1) {
                    Spacer(Modifier.height(16.dp))
                    SmallAction("Queue ($queueCount)", Modifier.fillMaxWidth()) {
                        showNowPlaying = false
                        switchSection(Section.QUEUE)
                    }
                }
            }
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
            TextMMD(text = label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }

    // ---------- Panels ----------

    @Composable
    private fun QueueActionsSheet(item: MediaItem) {
        ModalBottomSheetMMD(
            onDismissRequest = { actionTarget = null },
            sheetState = rememberModalBottomSheetMMDState(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                TextMMD(
                    text = item.mediaMetadata.title?.toString() ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val queueIndex = queueIndexOf(item.mediaId)
                    if (queueIndex != null) {
                        SmallAction("Remove", Modifier.weight(1f)) {
                            removeFromQueue(queueIndex)
                        }
                        SmallAction("Clear all", Modifier.weight(1f)) { clearQueue() }
                    } else {
                        SmallAction("Play next", Modifier.weight(1f)) { playNext(item) }
                        SmallAction("Queue", Modifier.weight(1f)) { addToQueue(item) }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    @Composable
    private fun AddFeedSheet() {
        ModalBottomSheetMMD(
            onDismissRequest = { showAddFeed = false },
            sheetState = rememberModalBottomSheetMMDState(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                TextMMD(
                    text = "Add a podcast",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(4.dp))
                TextMMD(
                    text = "Paste a feed URL, or use Search to find one by name",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(16.dp))

                TextFieldMMD(
                    value = feedUrlField,
                    onValueChange = { feedUrlField = it },
                    label = {
                        TextMMD("Feed URL", style = MaterialTheme.typography.labelMedium)
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(onGo = { onFeedInputSubmitted() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                ButtonMMD(
                    onClick = { onFeedInputSubmitted() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp),
                ) {
                    TextMMD("Subscribe")
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    // ---------- Search ----------

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
                    TextMMD("Search")
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

    @Composable
    private fun SectionHeading(label: String) {
        TextMMD(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .fillMaxWidth()
                .background(black)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            color = white,
        )
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
                style = MaterialTheme.typography.bodyMedium,
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
            TextMMD("Navi player", style = MaterialTheme.typography.headlineLarge)
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
                TextMMD("Connect")
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
