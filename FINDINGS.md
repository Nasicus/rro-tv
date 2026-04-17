# Chromecast with Google TV: the ambient now-playing card is gated by a hardcoded package allowlist

**TL;DR** — The "now playing" card that appears on the Chromecast with Google TV
ambient / screensaver (a.k.a. Backdrop) is **not** driven by your MediaSession.
It is gated by a hardcoded SHA-256 allowlist of ~1000 package names baked into
the Google TV launcher APK (`com.google.android.apps.tv.launcherx`). Your app
either hashes to one of those ~1000 entries or it never gets the card, no
matter how correctly you implement the documented MediaSession / MediaMetadata
/ notification contract.

No amount of metadata, `setActive()`, `FLAG_HANDLES_TRANSPORT_CONTROLS`,
`requestAudioFocus`, `MediaBrowserService`, or Media3 migration will open this
gate. The official docs
([Now Playing card](https://developer.android.com/training/tv/playback/now-playing),
[Ambient Mode](https://developer.android.com/training/tv/playback/ambient-mode))
do not mention the allowlist.

## How I found it

Symptom: our app (`ch.nasicus.rro.tv`) has a healthy `MediaSessionCompat`
(`state=PLAYING`, full metadata, artwork, foreground-service notification with
`MediaStyle.setMediaSession`, etc.). `dumpsys media_session` shows our session
side by side with `de.radio.android` (radio.net), which does get the card.
Both sessions look structurally identical. Ours never gets the card.

I then pulled the Chromecast with Google TV system APKs via `adb pull
$(adb shell pm path <pkg>)` and disassembled them with the Android SDK
`dexdump` tool.

### Step 1: `com.google.android.apps.tv.dreamx` (the screensaver)

Contains **zero** MediaSession-related code. No `MediaController`, no
`MediaSessionManager.getActiveSessions`, no `NotificationListenerService`.
The only `MediaSessionManager` use is unrelated (camera-doorbell PiP
indicator).

In the obfuscated class backing `com.google.android.apps.tv.dreamx.service.Backdrop`,
`onCreate` registers a BroadcastReceiver for
`android.apps.tv.launcherx.coreservices.NOW_PLAYING_DATA_UPDATED` and sends a
broadcast
`android.apps.tv.launcherx.REQUEST_NOW_PLAYING_DATA` targeted at
`com.google.android.apps.tv.launcherx`.

The screensaver doesn't decide what to show. It asks the launcher.

### Step 2: `com.google.android.apps.tv.launcherx` (the Google TV launcher)

In `com.google.android.apps.tv.launcherx.coreservices.nowplaying.NowPlayingRequestBroadcastReceiver`
the flow is:

```
MediaSessionManager.getActiveSessions(null)
  .stream()
  .filter(blocklist)   // server-side Phenotype flag
  .filter(allowlist)   // <-- the interesting one
  .forEach(register)
```

The allowlist filter (obfuscated as `Lfvc.test` case 9) computes
`SHA-256(packageName)` and binary-searches a hardcoded long[] at file offset
`0x2650a0` in `classes2.dex`, size 32000 bytes = **1000 × 32-byte SHA-256
hashes**. The session is kept only if the hash is present.

The long[] stores each SHA-256 as 4 little-endian `long`s. Swapping each
8-byte group recovers the canonical big-endian digest.

### Step 3: hash-probing

Probing with Python `hashlib.sha256(pkg.encode("utf-8")).digest()` against the
extracted table:

| Package | In list? |
|---|---|
| `de.radio.android` (radio.net) | ✅ |
| `com.spotify.tv.android` | ✅ |
| `tunein.player` | ✅ |
| `com.pandora.android.atv` | ✅ |
| `com.google.android.youtube.tvmusic` | ✅ |
| `com.google.android.apps.mediashell` | ✅ |
| `com.google.android.youtube.tv` | ❌ |
| `com.google.android.apps.youtube.music` (mobile) | ❌ |
| `com.spotify.music` (mobile) | ❌ |
| `com.plexapp.android` | ❌ |
| `ch.nasicus.rro.tv` | ❌ |

The list is narrowly curated to TV-form-factor music/audio/video apps.
Mobile-app package names don't hit. Most of Google's own TV surface doesn't
hit either — of ~80 Google/Alphabet packages probed, only two TV-music
packages are in.

## Why the official docs mislead

The docs describe the **home-screen** "Now Playing card" (the launcher row
that appears when a media session is active). They do not document the
**ambient/screensaver** now-playing card at all. The screensaver card happens
to read from the same launcherx pipeline, but with an additional allowlist
filter that the docs never mention. Developers following
[the docs](https://developer.android.com/training/tv/playback/now-playing)
reasonably expect their compliant session to surface on ambient too. It
doesn't. They conclude they misconfigured something. They didn't.

Evidence that real developers hit this:

- [Google Play Developer Community — Now Playing card not displaying](https://support.google.com/googleplay/android-developer/thread/244286550)
- [.NET MAUI — Now Playing card on Android TV](https://learn.microsoft.com/en-us/answers/questions/1160893/net-maui-android-tv-displaying-now-playing-card)
- [androidx/media#589](https://github.com/androidx/media/issues/589)
  — Google's own Play review tools apparently assume the card surfaces,
  which sets up a Catch-22 for non-allowlisted apps.

No Partner program page exists for "Now Playing card onboarding." Google
hasn't acknowledged the gate publicly.

## Personal-use workaround (this repo)

This repository's debug build masquerades as
`com.google.android.youtube.tvmusic` (see
[`app/build.gradle.kts`](app/build.gradle.kts) — the `androidComponents`
block). YouTube Music for Android TV is one of only two Google-owned packages
in the allowlist and is not preinstalled on Chromecast with Google TV, so
hijacking its package name for a sideloaded debug APK breaks nothing.

**This is for personal sideloading only.** Release builds keep the real
`ch.nasicus.rro.tv` applicationId. Do not distribute a hijacked APK — you'd
be squatting on someone else's package name, confusing users, and hitting
Play Store rejection (Play already owns `com.google.android.youtube.tvmusic`).

## Reproducing

You need a rooted-or-not Chromecast with Google TV or Android TV device with
ADB enabled, and the Android SDK build-tools (for `dexdump`).

```bash
# Pull the launcher
adb pull $(adb shell pm path com.google.android.apps.tv.launcherx | \
  head -1 | cut -d: -f2) /tmp/launcherx.apk
unzip /tmp/launcherx.apk -d /tmp/launcherx-extract

# Dump DEX and locate the table
$ANDROID_HOME/build-tools/35.0.0/dexdump -d /tmp/launcherx-extract/classes2.dex \
  > /tmp/launcherx.dex.txt
# Find "fill-array-data" at the obfuscated class that reads SHA-256 hashes
# (grep for the "32000" total size or the specific obfuscated class).

# Hash-probe from Python:
python3 <<'PY'
import hashlib, struct
with open('/tmp/launcherx-extract/classes2.dex','rb') as f:
    f.seek(0x2650a0 + 8)   # skip 8-byte fill-array-data header
    blob = f.read(32000)
hashes = set()
for i in range(1000):
    row = blob[i*32:(i+1)*32]
    # four little-endian longs -> canonical big-endian digest
    swapped = b''.join(row[j:j+8][::-1] for j in range(0, 32, 8))
    hashes.add(swapped)
print("de.radio.android ->", hashlib.sha256(b"de.radio.android").digest() in hashes)
PY
```

File offsets and class names above are from launcherx version installed on a
Chromecast with Google TV (4K) around April 2026. Future launcherx releases
may reorder or replace the table; the mechanism is likely stable.

## What this repo actually is

[rro-tv](https://github.com/Nasicus/rro-tv) is an unofficial Google TV / Android
TV client for [Radio Rottu Oberwallis](https://www.rro.ch), a Swiss local radio
station. The Chromecast with Google TV ambient card was a stretch goal. It
isn't achievable for a non-allowlisted app, but everything else works:
three channels, auto-play, live now-playing info via the public RRO playlist
API, Leanback launcher integration.
