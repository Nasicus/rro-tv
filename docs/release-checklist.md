# Release checklist

Everything that needs to happen by hand around each Play Store release. The
code-side bits (signing, building, AAB creation) are automated by
`.github/workflows/release.yml`.

## One-time setup (already done)

- [x] Upload keystore generated; stored in GitHub Secrets as
      `SIGNING_KEYSTORE_BASE64` / `SIGNING_KEYSTORE_PASSWORD` /
      `SIGNING_KEY_ALIAS` / `SIGNING_KEY_PASSWORD`.
- [x] Local backup at `~/keystores/rro-tv-upload.jks`. **Back this up to a
      password manager / cloud drive — losing it while enrolled in Play App
      Signing is recoverable via Google support, but still a hassle.**
- [x] Local signing creds in `~/.gradle/gradle.properties` so
      `./gradlew :app:bundleRelease` works on this machine without CI.
- [x] Privacy policy at `docs/index.html` (publish via GitHub Pages — see
      below).

## Still to do before first release

### Play Console

- [ ] Sign up at <https://play.google.com/console/signup> ($25).
- [ ] Create app:
      - **Display name on Play**: `rro — Radio Rottu Oberwallis` (Play
        Console "App name" field — this is what shows in the store and on
        the launcher. The in-app / launcher banner keeps showing the short
        "RRO" because `strings.xml → app_name` is separate.)
      - Default language: German (Switzerland).
      - Category: **Music & Audio** (NOT News — News demands publisher
        authorization we can't provide).
      - Free, contains no ads.
      - Declarations — App access: no login; ads: none; data safety: none
        collected; target audience: 18+.
- [ ] Form factors → **enable Android TV**. This triggers an extra manual
      review (3–7 days).
- [ ] **Enrol in Play App Signing** at app creation (default now). Upload
      our keystore as the upload key when prompted. Google then holds the
      real signing key; if we ever lose our upload key, Google can reset it.

### GitHub Pages (privacy policy)

- [ ] Repo → Settings → Pages → Source: **Deploy from a branch**, branch
      `main`, folder `/docs`. Save.
- [ ] Wait ~1 min, then privacy policy lives at
      <https://nasicus.github.io/rro-tv/>
- [ ] Paste that URL into Play Console → App content → Privacy policy.

### Store listing assets

Pre-generated under `docs/store-assets/` — drag straight into Play Console:

- [x] **App icon** 512×512: `docs/store-assets/play-icon-512.png`
- [x] **TV banner** 1280×720: `docs/store-assets/tv-banner-1280x720.png`
- [x] **Feature graphic** 1024×500: `docs/store-assets/feature-graphic-1024x500.png`

Still to capture by hand:

- [ ] **TV screenshots**: at least 3 at 1920×1080. Grab live from the TV:
      `adb exec-out screencap -p > shot-$(date +%s).png`. Recommended:
      home with RRO playing, channel-switch state, song info visible.
- [ ] **Phone screenshots**: at least 2. Play asks even for TV-only apps.
      Spin up any phone emulator + install the debug APK, take screenshots.

Copy:

- [ ] **Short description** (≤80 chars):
      `Inoffizielle Google TV App für Radio Rottu Oberwallis.`
- [ ] **Full description** (≤4000 chars): TBD. Keep "inoffiziell / nicht von
      RRO oder Pomona betrieben" in the first paragraph.

### Decisions still open

- [ ] Contact email to list on Play Console. Pick something you're willing
      to monitor — Google and the odd user will email it.
- [ ] Whether to pre-notify Pomona. Current plan: don't; take down on
      complaint.

## Release flow (every version after setup is done)

```bash
# 1. Bump version in app/build.gradle.kts
#    versionCode = 2
#    versionName = "0.2.0"
# 2. Commit, tag, push.
git commit -am "release: 0.2.0"
git tag v0.2.0
git push origin main v0.2.0
```

CI builds a signed AAB + APK and attaches them to a new GitHub Release.
Then in Play Console:

- [ ] Release → Production (or Internal testing first) → Create new release.
- [ ] Drag the `app-release.aab` from the GitHub Release into the upload area.
- [ ] Release notes: the auto-generated GitHub release notes make a decent
      starting point; trim for Play Store's 500-char limit.
- [ ] Submit for review. TV review = 3–7 days the first time, faster after.

## If Play ever bounces us

Most common reasons for a TV app rejection:

- Missing D-pad focus path → we already cover all interactive elements with
  focusable Compose buttons; re-check if we add any new UI.
- `android.hardware.touchscreen` not declared `required="false"` → we have
  this in the manifest, verify it survives any refactor.
- Stale metadata (e.g. the app does more than the store listing promises).
- Trademark complaint from Pomona / RRO → pull the app voluntarily.

## Key files

| Path | What |
|------|------|
| `.github/workflows/release.yml` | CI that turns a `v*` tag into a signed AAB + APK |
| `app/build.gradle.kts` → `releaseSigning()` | Reads creds from CI env or local `~/.gradle/gradle.properties` |
| `app/proguard-rules.pro` | Keep rules for Media3 reflection |
| `docs/index.html` | Privacy policy served via GitHub Pages |
| `~/keystores/rro-tv-upload.jks` | Local copy of the upload key (NOT in git) |
