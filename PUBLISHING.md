# Publishing notes (parked)

Status: app works locally on a Chromecast w/ Google TV, runs auto-play +
GraphQL now-playing + ICY fallback + custom branding. Not yet shipped
anywhere. This file collects everything needed to resume the
publish-to-Play-Store decision.

## TL;DR

Three options, pick later:

1. **Pay $25 → Play Store**: real reach, but trademark/policy risk on a
   third-party RRO app. Could face takedown.
2. **GitHub Releases only**: free, zero risk, basically zero reach (only
   sideloaders find it).
3. **Stay local-install**: keep using it on your own TVs, no public
   distribution.

The $25 is the only path that hits regular Walliser users.

## Trademark / legal context

- "RRO" and "Radio Rottu Oberwallis" are Pomona's trademarks. Our app uses
  both, plus their wordmark vector (extracted from pomona.ch's footer SVG).
- We pull live song info from `rro.playlist-api.deliver.media/graphql` —
  open public endpoint, no auth, but technically their backend.
- README + app name say "(unofficial)" — that helps but doesn't immunize.
- Decision: ship without asking Pomona, take down if they complain. Avoid
  drafting a courtesy email — they're a small company, low attention
  surface, asking forces a "no" decision they probably wouldn't bother
  making otherwise.

## Play Store publishing — concrete checklist

### One-time setup

1. **Google Play Console account**: https://play.google.com/console/signup
   - $25 lifetime fee, individual or business, verification ~24h.

2. **Generate release keystore** (NEVER commit, NEVER lose):
   ```bash
   keytool -genkey -v -keystore ~/keystores/rro-tv-release.jks \
     -keyalg RSA -keysize 2048 -validity 10000 -alias rro-tv
   ```

3. **Add credentials to `~/.gradle/gradle.properties`** (user-global, not in
   repo):
   ```properties
   RRO_KEYSTORE_FILE=/home/pz/keystores/rro-tv-release.jks
   RRO_KEYSTORE_PASSWORD=…
   RRO_KEY_ALIAS=rro-tv
   RRO_KEY_PASSWORD=…
   ```

### Code changes needed before first release

In `app/build.gradle.kts`:

```kotlin
android {
    signingConfigs {
        create("release") {
            storeFile = file(providers.gradleProperty("RRO_KEYSTORE_FILE").get())
            storePassword = providers.gradleProperty("RRO_KEYSTORE_PASSWORD").get()
            keyAlias = providers.gradleProperty("RRO_KEY_ALIAS").get()
            keyPassword = providers.gradleProperty("RRO_KEY_PASSWORD").get()
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"  // keeps debug installs side-by-side with release
        }
    }
}
```

The `applicationIdSuffix = ".debug"` is already in place; release builds
will use the clean `ch.nasicus.rro.tv` package id automatically.

Build the bundle:

```bash
./gradlew :app:bundleRelease
# output: app/build/outputs/bundle/release/app-release.aab
```

### Play Console — required listing assets

| Asset | Spec | Status |
|---|---|---|
| App icon | 512×512 PNG | Need to generate (rasterize the brackets at 512×512 with red bg) |
| TV banner | 1280×720 PNG | Need to render at high-res (current `banner.xml` is XML drawable, Play wants PNG) |
| Phone screenshots | min 2, 1080×1920 ish | Take from emulator |
| TV screenshots | min 3, 1920×1080 PNG | Take from real TV via `adb exec-out screencap -p > shot.png` |
| Short description | ≤80 chars | "Inoffizielle Google TV App für Radio Rottu Oberwallis." |
| Full description | ≤4000 chars | TBD |
| Privacy policy URL | required | Host one-pager on GitHub Pages — see template below |

### Compliance questionnaires

- **Content rating**: Everyone, no ads/violence/nothing. Fast.
- **Target audience**: 18+ keeps things simple.
- **Data safety**: declare zero data collection (true — no analytics, no
  crash reports, no user data leaves the device).
- **App access**: no login required.
- **Category**: pick **"Music & Audio"** (NOT News — News forces a
  publisher-authorization claim we can't truthfully make).
- **Form factors → Android TV**: enable. This triggers the manual TV
  review (3–7 extra days).

### Privacy policy template (one HTML page on GitHub Pages)

```text
RRO TV (unofficial) — Privacy Policy

This app does not collect, store, or transmit any personal data. It plays
public radio streams from streaming.rro.ch and reads now-playing metadata
from rro.playlist-api.deliver.media. No analytics, no tracking, no
accounts. The only network requests are:
- the audio stream URL of the channel you select
- a JSON GraphQL query to the public playlist API once per song change

Contact: <your-email>
Last updated: <date>
```

Stick that as `index.html` in a `gh-pages` branch (or use `docs/` folder
+ Pages config). Free, https, instant.

### Track rollout strategy

Play has 4 tracks: **Internal → Closed → Open → Production**.

Always:
1. **Internal testing** first — just you + a couple of friends with their
   Google accounts allowlisted. Catches obvious crashes.
2. After ~a week of internal usage with no crashes, promote to **Production**.

Skip Closed/Open unless you actually want a beta program.

## Alternative distribution (if skipping Play Store)

| Channel | Effort | Reach on Google TV |
|---|---|---|
| **GitHub Releases** | `gh release create v0.1.0 app-release.apk` | Sideloaders only — needs ADB or "Downloader" app |
| **F-Droid** | metadata PR; build must be reproducible | Almost zero on TV |
| **Aptoide TV** | sign up + upload, free | Modest in Europe |
| **APKMirror / APKPure** | upload, no review | Discovery only, still requires sideload |

For your Walliser audience (mostly non-technical), only Play Store actually
scales. Everything else means "manually walk relatives through sideloading."

## Pre-publish polish list (open)

Things worth doing before the first public release:

- [ ] Bump `versionCode` to 2, `versionName` to "1.0.0"
- [ ] Generate proper 512×512 app icon PNG and 1280×720 TV banner PNG
- [ ] Take 3+ screenshots from the real TV
- [ ] Decide on Pomona email (current: skip)
- [ ] Add Proguard rules if R8 strips ExoPlayer/Media3 reflection
      (test with `./gradlew :app:installRelease` first)
- [ ] Verify the Now Playing card on Backdrop now works with a
      release-signed build (it currently doesn't on debug — possibly
      because of the `.debug` suffix or sideload allowlist filtering)
- [ ] Decide privacy policy hosting (GitHub Pages on this repo or
      separate gist)

## Resume checklist when picking this back up

1. Read this file top to bottom.
2. Decide path: Play Store / GitHub Releases / stay local.
3. If Play Store:
   - Pay $25, set up account
   - Generate keystore (back it up to a password manager too)
   - Wire signing config into `app/build.gradle.kts`
   - Build release bundle
   - Knock out the assets list above
   - Submit to internal testing
   - Wait for TV review
4. If GitHub Releases: just `./gradlew :app:assembleRelease` (skip
   bundle), upload `app-release.apk` to a tagged release, write a
   sideload how-to in README.
