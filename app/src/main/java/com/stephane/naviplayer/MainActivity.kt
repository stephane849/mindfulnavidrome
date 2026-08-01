package com.stephane.naviplayer

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
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
 * Browses through MediaBrowserCompat and drives playback through
 * MediaControllerCompat. Holds no MediaPlayer of its own, so closing this
 * screen does not stop the music.
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

    private lateinit var browser: MediaBrowserCompat
    private var controller: MediaControllerCompat? = null
    private lateinit var api: NavidromeApi

    /** Breadcrumb of (mediaId, screen title) - last entry is what is on screen. */
    private val stack = mutableListOf<Pair<String, String>>()

    // ---------- Everything the UI reads ----------

    private var rows by mutableStateOf<List<MediaBrowserCompat.MediaItem>>(emptyList())
    private var playingMediaId by mutableStateOf<String?>(null)
    private var playbackState by mutableStateOf<PlaybackStateCompat?>(null)
    private var metadata by mutableStateOf<MediaMetadataCompat?>(null)
    private var positionMs by mutableStateOf(0L)
    private var screenTitle by mutableStateOf(ROOT_TITLE)
    private var canGoUp by mutableStateOf(false)
    private var showLogin by mutableStateOf(true)
    private var loginStatus by mutableStateOf("")

    private var serverField by mutableStateOf("")
    private var usernameField by mutableStateOf("")
    private var passwordField by mutableStateOf("")

    private val tickHandler = Handler(Looper.getMainLooper())
    private val positionTick = object : Runnable {
        override fun run() = refreshPosition()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = AudioManager.STREAM_MUSIC

        api = NavidromeApi(this)
        requestNotificationPermissionIfNeeded()

        browser = MediaBrowserCompat(
            this,
            ComponentName(this, MusicService::class.java),
            connectionCallback,
            null
        )

        if (api.isConfigured()) {
            val prefs = getSharedPreferences(NavidromeApi.PREFS, MODE_PRIVATE)
            serverField = api.server
            usernameField = prefs.getString("username", "") ?: ""
            passwordField = prefs.getString("password", "") ?: ""
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
        if (!browser.isConnected) browser.connect()
    }

    override fun onStop() {
        super.onStop()
        tickHandler.removeCallbacks(positionTick)
        controller?.unregisterCallback(controllerCallback)
        if (stack.isNotEmpty()) browser.unsubscribe(stack.last().first)
        browser.disconnect()
    }

    // ---------- Service connection ----------

    private val connectionCallback = object : MediaBrowserCompat.ConnectionCallback() {
        override fun onConnected() {
            val ctrl = MediaControllerCompat(this@MainActivity, browser.sessionToken)
            MediaControllerCompat.setMediaController(this@MainActivity, ctrl)
            ctrl.registerCallback(controllerCallback)
            controller = ctrl

            playbackState = ctrl.playbackState
            metadata = ctrl.metadata
            playingMediaId = ctrl.metadata?.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID)
            refreshPosition()

            if (api.isConfigured()) {
                if (stack.isEmpty()) stack.add(MusicService.MEDIA_ID_ROOT to ROOT_TITLE)
                subscribeCurrent()
            }
        }

        override fun onConnectionFailed() {
            loginStatus = "Playback service unavailable"
        }
    }

    private val controllerCallback = object : MediaControllerCompat.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
            playbackState = state
            refreshPosition()
        }

        override fun onMetadataChanged(md: MediaMetadataCompat?) {
            metadata = md
            playingMediaId = md?.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID)
        }
    }

    // ---------- Browsing ----------

    private val subscriptionCallback = object : MediaBrowserCompat.SubscriptionCallback() {
        override fun onChildrenLoaded(
            parentId: String,
            children: MutableList<MediaBrowserCompat.MediaItem>
        ) {
            if (stack.isEmpty() || parentId != stack.last().first) return
            rows = children
            if (children.isEmpty()) {
                Toast.makeText(this@MainActivity, "Nothing here", Toast.LENGTH_SHORT).show()
            }
        }

        override fun onError(parentId: String) {
            if (!api.isConfigured()) {
                loginStatus = "Enter your server details"
                showLogin = true
            } else {
                Toast.makeText(
                    this@MainActivity,
                    "Couldn't reach the server",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun subscribeCurrent() {
        val (mediaId, title) = stack.last()
        rows = emptyList()
        canGoUp = stack.size > 1
        screenTitle = title
        browser.unsubscribe(mediaId)
        browser.subscribe(mediaId, subscriptionCallback)
    }

    private fun onRowTapped(item: MediaBrowserCompat.MediaItem) {
        val mediaId = item.mediaId ?: return

        if (item.isBrowsable) {
            browser.unsubscribe(stack.last().first)
            stack.add(mediaId to (item.description.title?.toString() ?: ""))
            subscribeCurrent()
        } else {
            controller?.transportControls?.playFromMediaId(mediaId, null)
        }
    }

    private fun goUp() {
        if (stack.size <= 1) return
        browser.unsubscribe(stack.last().first)
        stack.removeAt(stack.size - 1)
        subscribeCurrent()
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
            .apply()

        loginStatus = ""
        showLogin = false
        stack.clear()
        stack.add(MusicService.MEDIA_ID_ROOT to ROOT_TITLE)
        if (browser.isConnected) subscribeCurrent() else browser.connect()
    }

    // ---------- Transport ----------

    private fun togglePlayPause() {
        val ctrl = controller ?: return
        if (ctrl.playbackState?.state == PlaybackStateCompat.STATE_PLAYING) {
            ctrl.transportControls.pause()
        } else {
            ctrl.transportControls.play()
        }
    }

    /**
     * The session only publishes a position when the state changes, so between
     * updates it has to be extrapolated from the wall clock.
     */
    private fun elapsedMs(state: PlaybackStateCompat?): Long {
        if (state == null) return 0L
        var position = state.position
        if (state.state == PlaybackStateCompat.STATE_PLAYING) {
            val drift = SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
            position += (drift * state.playbackSpeed).toLong()
        }
        return if (position < 0L) 0L else position
    }

    /** Publishes the position and re-arms the tick, but only while playing. */
    private fun refreshPosition() {
        tickHandler.removeCallbacks(positionTick)
        positionMs = elapsedMs(playbackState)
        if (playbackState?.state == PlaybackStateCompat.STATE_PLAYING) {
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
    private fun BrowseRow(item: MediaBrowserCompat.MediaItem) {
        val isPlaying = item.mediaId != null && item.mediaId == playingMediaId
        val background = if (isPlaying) black else white
        val foreground = if (isPlaying) white else black
        val subtitle = item.description.subtitle?.toString() ?: ""

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(background)
                .clickable { onRowTapped(item) }
                .defaultMinSize(minHeight = 48.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            TextMMD(
                text = item.description.title?.toString() ?: "",
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
        val state = playbackState
        val playing = state?.state == PlaybackStateCompat.STATE_PLAYING
        val totalMs = metadata?.getLong(MediaMetadataCompat.METADATA_KEY_DURATION) ?: 0L
        val title = metadata?.getString(MediaMetadataCompat.METADATA_KEY_TITLE)

        // Clock first: the line is single-line and a long lecture title would
        // otherwise push the time off the end of the screen.
        val line = when {
            !title.isNullOrEmpty() && totalMs > 0L ->
                "${formatClockMs(positionMs)} / ${formatClockMs(totalMs)}   $title"
            !title.isNullOrEmpty() -> title
            state?.state == PlaybackStateCompat.STATE_BUFFERING -> "Loading"
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
                    text = line,
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
                        controller?.transportControls?.skipToPrevious()
                    }
                    TransportButton("-15", Modifier.weight(1f)) {
                        controller?.transportControls?.rewind()
                    }
                    TransportButton(
                        label = if (playing) "Pause" else "Play",
                        modifier = Modifier.weight(1f),
                        primary = true,
                    ) { togglePlayPause() }
                    TransportButton("+15", Modifier.weight(1f)) {
                        controller?.transportControls?.fastForward()
                    }
                    TransportButton("Next", Modifier.weight(1f)) {
                        controller?.transportControls?.skipToNext()
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
