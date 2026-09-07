# cursor-for-android

An unofficial, native Android client for [Cursor Cloud Agents](https://cursor.com/docs/cloud-agent/api/endpoints), built with Kotlin and Jetpack Compose. It reproduces the official Cursor Agents application (the desktop Agents window and cursor.com/agents) with Android primitives: the same sidebar, the composer-first New Chat pane with the recent-chats list, the conversation view, and the design tokens shipped in the desktop build.

Not affiliated with Anysphere, Inc.

## Features

- Sign in with a Cursor user API key (validated against `GET /v1/me`), stored encrypted with the Android Keystore and excluded from backups.
- New Chat pane as home: repository / branch / environment selectors, the composer ("+" image attachments via the system photo picker, model picker with plan mode and auto-PR, dictation / send), then the recent chats with preview cards.
- Sidebar (edge-swipe drawer on phones, permanent 280dp column on tablets and foldables): New Chat, "Chats" with the filter menu (group by, sort, Repo / Status / Git / Source filters, metadata toggles), Pinned and date groups, search, pull-to-refresh, long-press actions (pin, open on cursor.com, copy link, archive, delete).
- Conversation view: transcript (`/v0/agents/{id}/conversation`), run footers ("Worked 3m 5s" + branch / PR pills), live SSE streaming of the active run with thinking, "Explored N files, M searches" tool rows, subagent cards and markdown rendering; follow-ups with image attachments (`prompt.images`), and stop.
- Live notifications, the Android counterpart of the iOS app's Live Activities: while agents run, an ongoing notification shows the status, title, current step and a Stop action for one agent, or one condensed line per agent (up to eight) for several; when an agent finishes, a card with "Finished", `+80 −230 · 3 Files` (or the duration when no tool reported line counts), the final reply, Review and View PR. On Android 16 it is a promoted Live Update (status-bar chip, lock screen). Backed by a `dataSync` foreground service that only runs while something is running; toggle in Settings › Notifications.
- Settings with Cursor Dark / Cursor Light / system theme; demo mode with an in-memory backend so the UI can be explored without an API key.
- Predictive back (Android 14+): screens recede with the gesture to reveal the one underneath, the drawer and bottom sheets shrink with it, the filter sheet's drill-in pages slide back under the finger, and from the New Chat pane the system's back-to-home animation plays. Cancelling a gesture rewinds; releasing it commits.

## Build

Requirements: JDK 17+, Android SDK with platform 36 (compileSdk; targetSdk stays 35) and build-tools 35.

```bash
./gradlew :app:assembleDebug          # APK at app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest      # JVM unit tests
./gradlew :app:recordRoborazziDebug   # re-render screenshots/ from the demo backend
./gradlew :app:verifyRoborazziDebug   # compare the demo walkthrough against screenshots/
./gradlew :app:assembleRelease        # R8-minified APK; signed with the debug key unless release signing is configured
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## CI and releases

Everything runs on GitHub Actions (`.github/workflows/`); the shared toolchain (JDK 17, SDK 35, Gradle cache) lives in `.github/actions/android-toolchain`.

| Workflow | Runs on | Does |
| --- | --- | --- |
| `ci.yml` | pushes to `main`, pull requests | `lintDebug`, debug + release APKs (downloadable from the run's artifacts, stamped `0.1.0-dev.<run>+g<sha>`), JVM unit tests, screenshot verification against `screenshots/` |
| `release.yml` | tags `vX.Y.Z`, or manually for an existing tag | lint + tests, then a signed release APK and AAB, the R8 `mapping.txt`, `SHA256SUMS.txt` and a GitHub Release with generated notes (`.github/release.yml` groups them by label) |
| `update-screenshots.yml` | manually | re-records `screenshots/` on a clean runner and opens a pull request |

### Cutting a release

```bash
git tag v0.2.0 && git push origin v0.2.0        # stable release, marked "latest"
git tag v0.2.0-rc.1 && git push origin v0.2.0-rc.1   # pre-release
```

The tag is the only input. `versionName` is the tag without the `v`; `versionCode` is derived from it as `MAJOR * 1_000_000 + MINOR * 10_000 + PATCH * 100 + STAGE`, where `STAGE` is 0–24 for `alpha.N`, 25–49 for `beta.N`, 50–98 for `rc.N` and 99 for a stable version, so every pre-release sorts below its final build (`0.2.0-rc.1` → `20051`, `0.2.0` → `20199`). Local and CI dev builds use `app.versionName` from `gradle.properties`; bump it after tagging so dev builds report the version they lead up to. `-Papp.versionName=…` and `-Papp.versionCode=…` override both.

### Release signing

Run `scripts/release-keystore.sh --set-secrets` once (needs a logged-in `gh`). It creates `release.jks` and uploads the `RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS` and `RELEASE_KEY_PASSWORD` repository secrets; without `--set-secrets` it prints the values to add by hand. Keep the keystore and password backed up: Android only installs an update if it is signed with the same key. Until the secrets exist, the release workflow still publishes but signs with the debug key and says so in the release notes; re-run it for the tag ("Run workflow") once they are in place. For local release builds, put the same values in a git-ignored `keystore.properties` next to `settings.gradle.kts` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`).

### Screenshots

`AppScreenshotTest` pins the clock (`AppClock`) to 2025-01-15 14:00 UTC, the zone and the locale, and disables ripples, so the PNGs render identically on every machine. After an intentional UI change, re-record with `./gradlew :app:recordRoborazziDebug` or the "Update screenshots" workflow and commit `screenshots/`.

## API surface used

| Purpose | Endpoint |
| --- | --- |
| Validate key / account | `GET /v1/me` |
| List agents (identity, lifecycle, `latestRunId`) | `GET /v1/agents` |
| Repo / branch / PR / summary for list rows | `GET /v0/agents` (legacy, merged by id) |
| Agent detail | `GET /v1/agents/{id}` |
| Transcript | `GET /v0/agents/{id}/conversation` (v1 has no equivalent) |
| Runs | `GET /v1/agents/{id}/runs`, `GET /v1/agents/{id}/runs/{runId}` |
| Live stream | `GET /v1/agents/{id}/runs/{runId}/stream` (SSE, `Last-Event-ID` reconnect) |
| Launch / follow up / cancel | `POST /v1/agents`, `POST /v1/agents/{id}/runs`, `POST …/cancel` |
| Lifecycle | `POST …/archive`, `POST …/unarchive`, `DELETE /v1/agents/{id}` |
| Pickers | `GET /v1/models`, `GET /v1/repositories` |

## Design tokens and geometry

Taken from the Cursor 3.19 desktop build (`workbench.glass.main.*` and the bundled `theme-cursor` themes): surfaces `#141414` (chat canvas, `--cursor-chrome`), `#181818` (sidebar, inputs, cards); the foreground `#F0F0F0` (light: `#141414`) at 100 / 74 / 60 / 36 % for text, 66 / 52 / 28 % for icons, 20 / 14 / 8 / 6 / 4 % for fills and 20 / 12 / 8 / 4 % for strokes; accents `#81A1C1`, `#3FA266`, `#70B489` (git added), `#FC6B83` (git removed), `#E34671`; radii 4 / 6 / 8 / 12 / 14; type 11 / 12 / 13 / 14 with 14 / 16 / 18 / 22 line heights. UI text uses the system sans (the desktop app uses the platform system font too); code uses the bundled JetBrains Mono (SIL OFL, see `app/licenses`). Geometry (36px header, 32px rows on a 33px pitch, 6px selection inset with radius 6, 24px composer buttons, 640px composer, 134×84 preview cards on a 106px pitch, 13px body text with 12px labels) was measured from the official web app at 2x and is recorded in `CursorDimens`. See `app/src/main/java/com/cursorforandroid/ui/theme/` for the mapping.
