package com.stephane.naviplayer

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.mudita.mmd.ThemeMMD
import com.mudita.mmd.black
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.text_field.TextFieldMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.mudita.mmd.white

/**
 * Browses and controls playback through a Media3 MediaBrowser, which is also a
 * Player - so transport state, position and the current item all come from the
 * one object. Holds no player of its own, so closing this screen does not stop
 * the music.
 *
 * Presented with Mudita Mindful Design: [ThemeMMD] supplies the pure black and
 * white E-Ink colour scheme, the Lato type scale and a globally disabled
 * ripple, and [LazyColumnMMD] replaces smooth scrolling with a stepped jump of
 * whole rows so each drag costs one clean E-Ink refresh instead of a smear.
 */
class MainActivity : ComponentActivity() {

    companion object {
        /** Title of the mode picker at the top of the browse tree. */
        private const val ROOT_TITLE = "Library"

        /**
         * How often the elapsed-time readout refreshes while playing. Coarse on
         * purpose: a ticking clock is exactly the kind of repeated partial
         * redraw that ghosts on E-Ink.
         */
        private const val TICK_MS = 15_000L
    }

    private var browserFuture: ListenableFuture<MediaBrowser>? = null
    private var browser: MediaBrowser? = null
    private lateinit var api: NavidromeApi

    /** Breadcrumb of (mediaId, screen title) - last entry is what is on screen. */
    private val stack = mutableListOf<Pair<String, String>>()

    // ---------- Everything the UI reads ----------

    private var rows by mutableStateOf<List<MediaItem>>(emptyList())
    private var playingMediaId by mutableStateOf<String?>(null)
    private var isPlaying by mutableStateOf(false)
    private var isBuffering by mutableStateOf(false)
    private var nowPlayingTitle by mutableStateOf<String?>(null)
    private var positionMs by mutableStateOf(0L)
    private var durationMs by mutableStateOf(0L)
    private var screenTitle by mutableStateOf(ROOT_TITLE)
    private var canGoUp by mutableStateOf(false)
    private var showLogin by mutableStateOf(true)
    private var loginStatus by mutableStateOf("")

    private var serverField by mutableStateOf("")
    private var usernameField by mutableStateOf("")
    private var passwordField by mutableStateOf("")
    private var bitrateField by mutableStateOf(NavidromeApi.DEFAULT_BITRATE.toString())

    private val tickHandler = Handler(Looper.getMainLooper())
    private val positionTick = object : Runnable {
        override fun run() = refreshPosition()
    }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            syncFromPlayer(player)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = AudioManager.STREAM_MUSIC

        api = NavidromeApi(this)
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
                BackHandler(enabled = !showLogin && canGoUp) { goUp() }
                if (showLogin) LoginScreen() else BrowseScreen()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        connectBrowser()
    }

    override fun onStop() {
        super.onStop()
        tickHandler.removeCallbacks(positionTick)
        browser?.removeListener(playerListener)
        browserFuture?.let { MediaBrowser.releaseFuture(it) }
        browserFuture = null
        browser = null
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

                if (api.isConfigured()) {
                    if (stack.isEmpty()) stack.add(MusicService.MEDIA_ID_ROOT to ROOT_TITLE)
                    loadCurrent()
                }
            },
            MoreExecutors.directExecutor(),
        )
    }

    private fun syncFromPlayer(player: Player) {
        isPlaying = player.isPlaying
        isBuffering = player.playbackState == Player.STATE_BUFFERING
        playingMediaId = player.currentMediaItem?.mediaId
        nowPlayingTitle = player.currentMediaItem?.mediaMetadata?.title?.toString()
        durationMs = player.duration.let { if (it == C.TIME_UNSET) 0L else it }
        refreshPosition()
    }

    // ---------- Browsing ----------

    private fun loadCurrent() {
        val browser = this.browser ?: return
        val (mediaId, title) = stack.last()

        rows = emptyList()
        canGoUp = stack.size > 1
        screenTitle = title

        val future = browser.getChildren(mediaId, 0, Int.MAX_VALUE, null)
        future.addListener(
            {
                val children = try {
                    future.get().value ?: emptyList()
                } catch (e: Exception) {
                    Toast.makeText(this, "Couldn't reach the server", Toast.LENGTH_LONG).show()
                    return@addListener
                }
                if (stack.isNotEmpty() && stack.last().first == mediaId) {
                    rows = children
                    if (children.isEmpty()) {
                        Toast.makeText(this, "Nothing here", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            MoreExecutors.directExecutor(),
        )
    }

    private fun onRowTapped(item: MediaItem) {
        val mediaId = item.mediaId
        if (mediaId.isEmpty()) return

        if (item.mediaMetadata.isBrowsable == true) {
            stack.add(mediaId to (item.mediaMetadata.title?.toString() ?: ""))
            loadCurrent()
        } else {
            play(item)
        }
    }

    /**
     * Queue the whole list the track came from, so next/previous walk the album
     * or playlist exactly as they did before. ExoPlayer owns the queue now.
     */
    private fun play(item: MediaItem) {
        val browser = this.browser ?: return
        val playables = rows.filter { it.mediaMetadata.isPlayable == true }
        val startIndex = playables.indexOfFirst { it.mediaId == item.mediaId }

        if (startIndex < 0) {
            browser.setMediaItem(item)
        } else {
            browser.setMediaItems(playables, startIndex, C.TIME_UNSET)
        }
        browser.prepare()
        browser.play()
    }

    private fun goUp() {
        if (stack.size <= 1) return
        stack.removeAt(stack.size - 1)
        loadCurrent()
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
            .putInt("max_bitrate", bitrateField.trim().toIntOrNull() ?: NavidromeApi.DEFAULT_BITRATE)
            .apply()

        loginStatus = ""
        showLogin = false
        stack.clear()
        stack.add(MusicService.MEDIA_ID_ROOT to ROOT_TITLE)
        if (browser == null) connectBrowser() else loadCurrent()
    }

    // ---------- Transport ----------

    private fun togglePlayPause() {
        val browser = this.browser ?: return
        if (browser.isPlaying) browser.pause() else browser.play()
    }

    /** Publishes the position and re-arms the tick, but only while playing. */
    private fun refreshPosition() {
        tickHandler.removeCallbacks(positionTick)
        val browser = this.browser ?: return
        positionMs = browser.currentPosition.coerceAtLeast(0L)
        if (browser.isPlaying) {
            tickHandler.postDelayed(positionTick, TICK_MS)
        }
    }

    // ---------- Screens ----------

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

    @Composable
    private fun BrowseScreen() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(white),
        ) {
            TopAppBarMMD(
                title = {
                    TextMMD(
                        text = screenTitle,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (canGoUp) {
                        TextMMD(
                            text = "Back",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier
                                .clickable { goUp() }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                },
            )

            LazyColumnMMD(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                itemsIndexed(rows) { index, item ->
                    BrowseRow(item)
                    if (index < rows.lastIndex) HorizontalDividerMMD()
                }
            }

            TransportBar()
        }
    }

    /**
     * Two lines of type, no colour: hierarchy comes from size alone, because
     * MMD has no grey and mid-greys dither badly on E-Ink. The row currently
     * playing inverts to white on black, which is the only selection cue.
     */
    @Composable
    private fun BrowseRow(item: MediaItem) {
        val isCurrent = item.mediaId.isNotEmpty() && item.mediaId == playingMediaId
        val background = if (isCurrent) black else white
        val foreground = if (isCurrent) white else black
        val subtitle = item.mediaMetadata.subtitle?.toString() ?: ""

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(background)
                .clickable { onRowTapped(item) }
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
        }
    }

    @Composable
    private fun TransportBar() {
        // Clock first: the line is single-line and a long lecture title would
        // otherwise push the time off the end of the screen.
        val line = when {
            !nowPlayingTitle.isNullOrEmpty() && durationMs > 0L ->
                "${formatClockMs(positionMs)} / ${formatClockMs(durationMs)}   $nowPlayingTitle"
            !nowPlayingTitle.isNullOrEmpty() -> nowPlayingTitle
            isBuffering -> "Loading"
            else -> "Nothing playing"
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(white),
        ) {
            HorizontalDividerMMD()
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                TextMMD(
                    text = line ?: "Nothing playing",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TransportButton("Prev", Modifier.weight(1f)) {
                        browser?.seekToPreviousMediaItem()
                    }
                    TransportButton("-15", Modifier.weight(1f)) {
                        browser?.seekBack()
                    }
                    TransportButton(
                        label = if (isPlaying) "Pause" else "Play",
                        modifier = Modifier.weight(1f),
                        primary = true,
                    ) { togglePlayPause() }
                    TransportButton("+15", Modifier.weight(1f)) {
                        browser?.seekForward()
                    }
                    TransportButton("Next", Modifier.weight(1f)) {
                        browser?.seekToNextMediaItem()
                    }
                }
            }
        }
    }

    /**
     * Play/pause is the filled button, everything else outlined: MMD expresses
     * emphasis through fill, not colour or size.
     */
    @Composable
    private fun TransportButton(
        label: String,
        modifier: Modifier = Modifier,
        primary: Boolean = false,
        onClick: () -> Unit,
    ) {
        val content: @Composable RowScope.() -> Unit = {
            TextMMD(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
        }
        // Five controls on a narrow screen, so the padding is tighter than the
        // MMD default while the 48dp touch target is kept.
        val padding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
        val sized = modifier.defaultMinSize(minHeight = 48.dp)

        if (primary) {
            ButtonMMD(
                onClick = onClick,
                modifier = sized,
                contentPadding = padding,
                content = content,
            )
        } else {
            OutlinedButtonMMD(
                onClick = onClick,
                modifier = sized,
                contentPadding = padding,
                content = content,
            )
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
