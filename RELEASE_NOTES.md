A Navidrome and podcast player for the Mudita Kompakt, built for long-form
spoken word — classes, lectures and podcasts — on an E-Ink screen.

## Before you install

**This is a debug-signed build.** The signing key is the conventional Android
debug key and it is committed to this repository, so it proves nothing about
who produced the APK, and the build is marked debuggable. That is fine for
personal sideloading and is what makes updates install cleanly over each other,
but it is not a build to distribute widely. Anything else would need a real
keystore held in repository secrets.

Note also that the app stores your Navidrome password in plain SharedPreferences
and sends it as a cleartext query parameter, which the Subsonic API expects.
Keep that in mind if your server is reachable from outside your network.

## Playback

Built on Media3/ExoPlayer rather than MediaPlayer, for reasons specific to long
recordings:

- **±15 seconds that actually lands.** Class recordings ripped to MP3 often have
  no Xing header and so no seek table; constant-bitrate seeking is enabled so
  those files seek accurately instead of by guesswork.
- **A dropped connection no longer abandons your class.** Playback errors retry
  at the current position rather than skipping to the next track.
- **Playback speed** from 0.8× to 2×, and a **sleep timer** that runs in the
  playback service so it keeps working with the app closed.
- Buffering tuned for hour-long files, and a bitrate ceiling for speech that
  defaults to 96 kbps and is configurable on the login screen.

## Resume and queue

- Positions are remembered per track and restored when you come back, for
  anything longer than two minutes.
- **Continue listening** reopens whatever you had on, and the app returns to the
  screen you left rather than the root.
- A **queue** you build by long-pressing anything playable — Play next or Queue —
  with its own screen for reordering out, and it survives a reboot.

## Podcasts

Navidrome answers every Subsonic podcast endpoint with HTTP 501, so podcasts are
subscribed to in the app: search a directory by name or paste a feed URL. Feeds
are cached and refreshed behind you, and episodes stream from their own host.
Episode lists filter by All, Unplayed or Started.

## Design

Presented with [Mudita Mindful Design](https://github.com/mudita/MMD), the
design system Mudita publishes for its own E-Ink devices:

- Lato at MMD's type scale, pure black on white with no grey anywhere —
  hierarchy comes from size, because mid-greys dither on E-Ink
- Stepped scrolling that moves whole rows per drag, so a gesture costs one clean
  refresh rather than a smear, with MMD's chevron scrollbar
- No ripple and no animations; rows invert while pressed instead
- 3dp dividers rather than hairlines, which alias badly on E-Ink
- Bottom navigation across Library, Podcasts, Queue and Search, a mini player
  that only exists when something is loaded, and a full Now Playing screen
