package com.stephane.naviplayer

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Color
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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*

/**
 * Browses through MediaBrowserCompat and drives playback through
 * MediaControllerCompat. Holds no MediaPlayer of its own, so closing this
 * screen does not stop the music.
 */
class MainActivity : Activity() {

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

    private lateinit var loginPanel: View
    private lateinit var browsePanel: View
    private lateinit var inputServer: EditText
    private lateinit var inputUsername: EditText
    private lateinit var inputPassword: EditText
    private lateinit var buttonConnect: Button
    private lateinit var loginStatus: TextView
    private lateinit var buttonBack: Button
    private lateinit var textTitle: TextView
    private lateinit var browseList: ListView
    private lateinit var nowPlaying: TextView
    private lateinit var buttonPrev: Button
    private lateinit var buttonRewind: Button
    private lateinit var buttonPlayPause: Button
    private lateinit var buttonForward: Button
    private lateinit var buttonNext: Button

    /** Breadcrumb of (mediaId, screen title) - last entry is what is on screen. */
    private val stack = mutableListOf<Pair<String, String>>()
    private var rows: List<MediaBrowserCompat.MediaItem> = emptyList()
    private var playingMediaId: String? = null

    private val tickHandler = Handler(Looper.getMainLooper())
    private val positionTick = object : Runnable {
        override fun run() {
            renderTransport(controller?.playbackState, controller?.metadata)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        volumeControlStream = AudioManager.STREAM_MUSIC

        api = NavidromeApi(this)

        loginPanel = findViewById(R.id.login_panel)
        browsePanel = findViewById(R.id.browse_panel)
        inputServer = findViewById(R.id.input_server)
        inputUsername = findViewById(R.id.input_username)
        inputPassword = findViewById(R.id.input_password)
        buttonConnect = findViewById(R.id.button_connect)
        loginStatus = findViewById(R.id.login_status)
        buttonBack = findViewById(R.id.button_back)
        textTitle = findViewById(R.id.text_title)
        browseList = findViewById(R.id.browse_list)
        nowPlaying = findViewById(R.id.now_playing)
        buttonPrev = findViewById(R.id.button_prev)
        buttonRewind = findViewById(R.id.button_rewind)
        buttonPlayPause = findViewById(R.id.button_play_pause)
        buttonForward = findViewById(R.id.button_forward)
        buttonNext = findViewById(R.id.button_next)

        buttonConnect.setOnClickListener { onConnectClicked() }
        buttonBack.setOnClickListener { goUp() }
        buttonPrev.setOnClickListener { controller?.transportControls?.skipToPrevious() }
        buttonNext.setOnClickListener { controller?.transportControls?.skipToNext() }
        buttonRewind.setOnClickListener { controller?.transportControls?.rewind() }
        buttonForward.setOnClickListener { controller?.transportControls?.fastForward() }
        buttonPlayPause.setOnClickListener { togglePlayPause() }
        browseList.setOnItemClickListener { _, _, position, _ -> onRowTapped(position) }

        requestNotificationPermissionIfNeeded()

        browser = MediaBrowserCompat(
            this,
            ComponentName(this, MusicService::class.java),
            connectionCallback,
            null
        )

        if (api.isConfigured()) {
            inputServer.setText(api.server)
            inputUsername.setText(
                getSharedPreferences(NavidromeApi.PREFS, MODE_PRIVATE)
                    .getString("username", "")
            )
            inputPassword.setText(
                getSharedPreferences(NavidromeApi.PREFS, MODE_PRIVATE)
                    .getString("password", "")
            )
            showBrowsePanel()
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

            renderTransport(ctrl.playbackState, ctrl.metadata)

            if (api.isConfigured()) {
                if (stack.isEmpty()) stack.add(MusicService.MEDIA_ID_ROOT to ROOT_TITLE)
                subscribeCurrent()
            }
        }

        override fun onConnectionFailed() {
            loginStatus.text = "Playback service unavailable"
        }
    }

    private val controllerCallback = object : MediaControllerCompat.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
            renderTransport(state, controller?.metadata)
        }

        override fun onMetadataChanged(metadata: MediaMetadataCompat?) {
            renderTransport(controller?.playbackState, metadata)
            renderRows()
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
            renderRows()
            if (children.isEmpty()) {
                Toast.makeText(this@MainActivity, "Nothing here", Toast.LENGTH_SHORT).show()
            }
        }

        override fun onError(parentId: String) {
            textTitle.text = stack.lastOrNull()?.second ?: ROOT_TITLE
            if (!api.isConfigured()) {
                showLoginPanel("Enter your server details")
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
        browseList.adapter = RowAdapter(emptyList())
        buttonBack.visibility = if (stack.size > 1) View.VISIBLE else View.GONE
        browser.unsubscribe(mediaId)
        browser.subscribe(mediaId, subscriptionCallback)
        textTitle.text = title
    }

    private fun onRowTapped(position: Int) {
        val item = rows.getOrNull(position) ?: return
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

    override fun onBackPressed() {
        if (browsePanel.visibility == View.VISIBLE && stack.size > 1) goUp()
        else super.onBackPressed()
    }

    // ---------- Login ----------

    private fun onConnectClicked() {
        val server = inputServer.text.toString().trim().trimEnd('/')
        val username = inputUsername.text.toString().trim()
        val password = inputPassword.text.toString()

        if (server.isEmpty() || username.isEmpty() || password.isEmpty()) {
            loginStatus.text = "Fill in all three fields"
            return
        }

        getSharedPreferences(NavidromeApi.PREFS, MODE_PRIVATE).edit()
            .putString("server", server)
            .putString("username", username)
            .putString("password", password)
            .apply()

        showBrowsePanel()
        stack.clear()
        stack.add(MusicService.MEDIA_ID_ROOT to ROOT_TITLE)
        if (browser.isConnected) subscribeCurrent() else browser.connect()
    }

    private fun showBrowsePanel() {
        loginPanel.visibility = View.GONE
        browsePanel.visibility = View.VISIBLE
    }

    private fun showLoginPanel(message: String) {
        browsePanel.visibility = View.GONE
        loginPanel.visibility = View.VISIBLE
        loginStatus.text = message
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

    private fun renderTransport(state: PlaybackStateCompat?, metadata: MediaMetadataCompat?) {
        val playing = state?.state == PlaybackStateCompat.STATE_PLAYING
        buttonPlayPause.text = if (playing) "Pause" else "Play"

        playingMediaId = metadata?.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID)
        val title = metadata?.getString(MediaMetadataCompat.METADATA_KEY_TITLE)
        val totalMs = metadata?.getLong(MediaMetadataCompat.METADATA_KEY_DURATION) ?: 0L

        // Clock first: the line is single-line and a long lecture title would
        // otherwise push the time off the end of the screen.
        nowPlaying.text = when {
            !title.isNullOrEmpty() && totalMs > 0L ->
                "${formatClockMs(elapsedMs(state))} / ${formatClockMs(totalMs)}   $title"
            !title.isNullOrEmpty() -> title
            state?.state == PlaybackStateCompat.STATE_BUFFERING -> "Loading"
            else -> "Nothing playing"
        }

        tickHandler.removeCallbacks(positionTick)
        if (playing) tickHandler.postDelayed(positionTick, TICK_MS)
    }

    // ---------- Rendering ----------

    private fun renderRows() {
        browseList.adapter = RowAdapter(rows)
    }

    inner class RowAdapter(
        private val items: List<MediaBrowserCompat.MediaItem>
    ) : BaseAdapter() {

        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: LayoutInflater.from(this@MainActivity)
                .inflate(R.layout.list_item_row, parent, false)

            val item = items[position]
            val titleView = view.findViewById<TextView>(R.id.row_title)
            val subtitleView = view.findViewById<TextView>(R.id.row_subtitle)

            val subtitle = item.description.subtitle?.toString() ?: ""
            titleView.text = item.description.title?.toString() ?: ""
            subtitleView.text = subtitle
            subtitleView.visibility = if (subtitle.isEmpty()) View.GONE else View.VISIBLE

            // Playing row inverts to white on black: the only selection cue
            val inverted = item.mediaId != null && item.mediaId == playingMediaId
            if (inverted) {
                view.setBackgroundColor(Color.BLACK)
                titleView.setTextColor(Color.WHITE)
                subtitleView.setTextColor(Color.parseColor("#CCCCCC"))
            } else {
                view.setBackgroundColor(Color.WHITE)
                titleView.setTextColor(Color.BLACK)
                subtitleView.setTextColor(Color.parseColor("#777777"))
            }

            return view
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
