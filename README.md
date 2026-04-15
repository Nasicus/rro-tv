# RRO TV (unofficial)

Dead-simple Google TV / Android TV app for [Radio Rottu Oberwallis](https://www.rro.ch).
Pick a channel, hit play. Song title + artist show up on-screen and in the
system "now playing" overlay while the TV's ambient screensaver takes over.

**Not affiliated with RRO / Pomona.** Open source under MIT.

## Channels

| Channel       | Stream URL                                              |
|---------------|---------------------------------------------------------|
| RRO           | `https://streaming.rro.ch/rro/mp3_128`                  |
| Swiss Melody  | `https://streaming.rro.ch/rro_swissmelody/mp3_128`      |
| Müsig Pur     | `https://streaming.rro.ch/rro_musigpur/mp3_128`         |

Now-playing info is pulled from RRO's public GraphQL playlist API
(`https://rro.playlist-api.deliver.media/graphql`) — separate artist / title /
album fields for all channels, including talk-heavy main programming where
the ICY stream metadata is empty. Polling is scheduled per track from
`start_time + duration_ms`, not on a fixed timer, so we hit the API once per
song. No auth, no scraping, no analytics.

## Build

Requires Android Studio Ladybug+ (or just JDK 21 + Android SDK + Gradle 8.10+).

```bash
# one-time: generate the gradle wrapper jar
gradle wrapper

# debug build to a connected TV / emulator
./gradlew :app:installDebug
```

The app uses the Leanback launcher intent only — it shows up on Android TV
home screens but is hidden on phones (TV-only by design).

## Stack

- Kotlin 2.0, AGP 8.7
- Jetpack Compose (Material 3)
- AndroidX Media3 (ExoPlayer + MediaSession)
- minSdk 26, targetSdk 35

## Layout

```
app/src/main/java/ch/nasicus/rro/tv/
├── MainActivity.kt      # binds MediaController, drives Compose UI
├── PlayerScreen.kt      # 3 channel cards + play/pause toggle
├── PlaybackService.kt   # MediaSessionService owning ExoPlayer + NowPlayingPoller
├── PlaylistApi.kt       # GraphQL client for the now-playing endpoint
├── Channels.kt          # static channel list + API ID mapping
└── RroApp.kt            # Application
```

## Notes

- Now-playing text depends on what the playlist API returns; during talk
  shows, news, and ads the "current track" field is empty and the UI falls
  back to just the channel name.
- The app polls the API once per detected track boundary — expect a
  few-second lag between real playback and the displayed title (stream
  buffering + API refresh cadence).
