# Navi player - for Mudita Kompakt

A minimal Navidrome client styled per Mudita Mindful Design: pure black and
white, no ripple effects, no window animations, large touch targets.

## Architecture

Playback lives in a `MediaBrowserServiceCompat`, not in the activity.

    MainActivity                      MusicService
    (MediaBrowserCompat client)  <->  (MediaBrowserServiceCompat)
      - browses the tree                - owns the MediaPlayer
      - sends transport commands        - owns the MediaSessionCompat
      - holds no player                 - posts the MediaStyle notification
                                        - handles audio focus

Because the activity holds no player, closing the app, locking the screen, or
letting Android destroy the activity does not stop the music. The service runs
in the foreground with a notification for as long as audio is playing.

### Browse tree

The service exposes the library through `onLoadChildren`, so the hierarchy is
addressable by any media browser client, not just this app:

    root                      -> artists   (browsable)
    artist/<artistId>         -> albums    (browsable)
    album/<albumId>           -> tracks    (playable)
    track/<albumId>/<songId>  -> played via onPlayFromMediaId

The album id is carried inside the track media id so the service can rebuild
the queue from a single tap without extra state.

### What the session gives you for free

- **Headset buttons.** The 3.5mm jack's inline remote and Bluetooth controls
  route through `MediaButtonReceiver` into the session. Play, pause, and
  double-press-for-next work with no extra code.
- **Lock screen and notification controls.** Prev, play/pause, next.
- **Call handling.** Audio focus is requested on play and released on stop.
  An incoming call pauses playback and it resumes when the call ends.
- **Headphone unplug.** `ACTION_AUDIO_BECOMING_NOISY` pauses rather than
  switching to the loudspeaker.

## Subsonic endpoints used

| Level   | Endpoint          | Notes                                       |
|---------|-------------------|---------------------------------------------|
| Artists | `getArtists.view` | Arrive nested in A-Z buckets; app flattens  |
| Albums  | `getArtist.view`  | Takes the artist id                         |
| Tracks  | `getAlbum.view`   | Takes the album id                          |
| Audio   | `stream.view`     | Handed to MediaPlayer inside the service    |

The last album fetched is cached, so tapping a track does not refetch it.

## Build steps

This project has **not** been compiled into an .apk - it was generated without
access to Android's build toolchain. Build it yourself:

1. Open Android Studio.
2. File > Open > select the `NavidromePlayer` folder.
3. Let Gradle sync. It pulls `androidx.media:media:1.7.0` from Google Maven,
   so the machine needs network access on first build.
4. Build > Build Bundle(s) / APK(s) > Build APK(s).
5. Output at `app/build/outputs/apk/debug/app-debug.apk`.
6. Sideload via Mudita Center.

On first launch the app asks for notification permission (Android 13+). Denying
it does not stop playback, but you lose the notification controls.

## Known gaps

- **No HTTPS.** The VPS is plain HTTP, so credentials and audio stream in
  cleartext. Worth fixing with Caddy plus a domain, or Tailscale, before using
  this on public wifi. Note that credentials are also sitting in the stream URL
  query string, which is normal for Subsonic but another reason to add TLS.
- **`onGetRoot` accepts every caller.** Any app on the device can browse the
  library through the service. On a de-Googled single-user phone that is
  unlikely to matter, but a package/signature check is the correct fix.
- **Fling scrolling, not jump scrolling.** MMD's `LazyColumnMMD` scrolls in
  discrete steps to avoid E-Ink ghosting. This build uses a plain `ListView`.
  Fixing it means pulling in `com.mudita:MMD:1.0.0` and rewriting the lists in
  Compose.
- **No search, no playlists, no offline caching.** Streaming only.
- **No resume across reboots.** The queue is held in memory only.
