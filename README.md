# RRO TV (unofficial)

Dead-simple Google TV / Android TV app for [Radio Rottu Oberwallis](https://www.rro.ch).
Pick a channel, hit play. Lets the TV's ambient screensaver take over while
playback continues; song info shows up in the system "now playing" overlay.

**Not affiliated with RRO / Pomona.** Open source under MIT.

## Channels

| Channel       | Stream URL                                |
|---------------|-------------------------------------------|
| RRO           | `https://streams.rro.ch/rro.mp3`          |
| Swiss Melody  | `https://streams.rro.ch/swissmelody.mp3`  |
| Müsig Pur     | `https://streams.rro.ch/muesigpur.mp3`    |
| Event Radio   | `https://streams.rro.ch/event.mp3`        |

Now-playing info comes from ICY metadata embedded in the MP3 streams —
no scraping, no API keys, no analytics.

## Build

Requires Android Studio Ladybug+ (or just JDK 17 + Android SDK + a Gradle install).

```bash
# one-time: generate the gradle wrapper jar
gradle wrapper

# debug build to a connected TV / emulator
./gradlew :app:installDebug
```

The app uses the Leanback launcher intent only — it shows up on Android TV
home screens but is hidden on phones (by design; user requested TV-only).

## Stack

- Kotlin 2.0, AGP 8.7
- Jetpack Compose (Material 3)
- AndroidX Media3 (ExoPlayer + MediaSession)
- minSdk 26, targetSdk 35

## Layout

```
app/src/main/java/ch/nasicus/rro/tv/
├── MainActivity.kt      # binds MediaController, drives Compose UI
├── PlayerScreen.kt      # 4 channel cards + play/pause/stop
├── PlaybackService.kt   # MediaSessionService owning ExoPlayer
├── Channels.kt          # static channel list
└── RroApp.kt            # Application
```

## Notes

- Stop clears the queue — re-pick a channel afterwards.
- Event Radio is silent outside of live event broadcasts.
- Now-playing text depends on what the broadcaster's encoder sends; some shows / ads strip the title.
