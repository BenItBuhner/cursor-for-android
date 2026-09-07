# cursor-for-android

An unofficial, native Android client for [Cursor Cloud Agents](https://cursor.com/docs/cloud-agent/api/endpoints), built with Kotlin and Jetpack Compose. It reproduces the official Cursor Agents application (the desktop Agents window and cursor.com/agents) with Android primitives: the same sidebar, the composer-first New Chat pane with the recent-chats list, the conversation view, and the design tokens shipped in the desktop build.

Not affiliated with Anysphere, Inc.

## Features

- Sign in with a Cursor user API key (validated against `GET /v1/me`), stored encrypted with the Android Keystore and excluded from backups.
- New Chat pane as home: repository / branch / environment selectors, the composer (the "+" menu, model picker with plan mode and auto-PR, send), then the recent chats with preview cards. The branch picker offers the repository's default branch and every branch its agents have started from or pushed — the API has no branch listing, so that is what the app can know — and takes any other branch by name. The model picker lists each model from `GET /v1/models` once; a model's parameters — effort, speed, context window — unfold under it as pickers (a toggle for an on/off parameter, a row of choices otherwise), and every choice resolves to a variant the API lists, so the request never carries a `model.params` combination the model does not accept.
- The composer's "+" menu, as on cursor.com/agents: Multitask (toggles `/multitask` at the front of the prompt), Files (image attachments via the system photo picker), Skills (the built-in cloud skills — `/autopilot`, `/review`, `/review-bugbot`, `/review-security`, `/split-to-prs`, `/subscribe`, `/loop`, … — plus any project or synced skill typed by name, inserted as `/name`), and MCP Servers (HTTP or stdio servers defined in the app, stored encrypted, toggled per server and sent inline as `mcpServers[]` with every prompt while enabled). The same menu sits on the follow-up composer.
- Sidebar (edge-swipe drawer on phones, permanent 280dp column on tablets and foldables): the "+" new-chat button next to search in the header, "Chats" with the filter menu (group by, sort, Repo / Status / Git / Source filters, metadata toggles), Pinned and date groups, search, pull-to-refresh, long-press actions (pin, open on cursor.com, copy link, archive, delete).
- Conversation view: transcript (`/v0/agents/{id}/conversation`), run footers ("Worked 3m 5s" + branch / PR pills), live SSE streaming of the active run, subagent cards and markdown rendering; follow-ups with image attachments (`prompt.images`), stop, and press-and-hold on any prompt or reply to copy it. Everything the agent does between two replies — its reasoning and tool calls, in the order they happened — sits behind one "Explored N files, M searches · thought for Ns" row ("Exploring …" with a spinner while it is still going) that expands to the interleaved trace: each thought as prose, each run of tool calls as a card. Finished runs replay their retained event stream, so the same thinking / tool / subagent trace is there to dig into after the fact; runs past the API's retention window (`410 stream_expired`) keep the text-only transcript.
- Media in replies: the `<img>`, `<video>` and `![alt](src)` an agent writes for its screenshots and recordings render inline. `/opt/cursor/artifacts/…` paths are exchanged for presigned URLs through `GET /v1/agents/{id}/artifacts/download` (cached and refreshed before their 15-minute expiry); images are laid out at one source pixel per dp within the message width and open in a zoomable full-screen viewer, videos show a poster frame and play inline with ExoPlayer.
- Live notifications, the Android counterpart of the iOS app's Live Activities: while agents run, an ongoing notification shows the status, title, current step and a Stop action for one agent, or the total count with one condensed line per agent (live detail for up to eight, then "+N more") for several; when an agent finishes, a card with "Finished", `+80 −230 · 3 Files` (or the duration when no tool reported line counts), the final reply, Review and View PR. On Android 16 it is a promoted Live Update (status-bar chip, lock screen). Backed by a `dataSync` foreground service that only runs while something is running; toggle in Settings › Notifications.
- Instant start: the agent list, the transcripts you opened (plus the most recent ones, prefetched in the background) and the model / repository catalogs are kept on disk (`cacheDir`, wiped on sign-out), so the app renders the last known state immediately and revalidates behind it. Refreshes publish each `/v1/agents` page as it arrives and treat the legacy `/v0/agents` enrichment as best effort, a failed or offline refresh never empties what is on screen, and transient errors (`429`, `5xx`, dropped connections) are retried with backoff for idempotent requests.
- Settings with Cursor Dark / Cursor Light / system theme; demo mode with an in-memory backend so the UI can be explored without an API key.
- Predictive back (Android 14+): the screen being left shrinks into a rounded card that slides in the direction of the swipe (right for a left-edge gesture, left for a right-edge one) and trails the finger vertically, while the screen underneath comes forward from behind a scrim; the drawer and bottom sheets shrink with the gesture, the filter sheet's drill-in pages slide back under the finger, and from the New Chat pane the system's back-to-home animation plays. Cancelling a gesture rewinds from wherever it was; releasing commits from there, and a new gesture that starts mid-rewind takes over. Forward navigation plays the same transition backwards.

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
| `ci.yml` | pushes to `main`, pull requests | `lintDebug`, debug + release APKs (downloadable from the run's artifacts, stamped `<app.versionName>-dev.<run>+g<sha>`, e.g. `0.2.0-dev.42+gabc1234`), JVM unit tests, screenshot verification against `screenshots/` |
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

`AppScreenshotTest` pins the clock (`AppClock`) to 2025-01-15 14:00 UTC, the zone and the locale, and disables ripples, so the PNGs render identically on every machine. After an intentional UI change, re-record with `./gradlew :app:recordRoborazziDebug` or the "Update screenshots" workflow and commit `screenshots/`. The demo backend serves its sample artifacts (`app/src/main/assets/demo/`) as `file:///android_asset/` URLs, so `11_conversation_media.png` exercises the real image pipeline without a network.

## API surface used

| Purpose | Endpoint |
| --- | --- |
| Validate key / account | `GET /v1/me` |
| List agents (identity, lifecycle, `latestRunId`) | `GET /v1/agents` |
| Repo / branch / PR / summary for list rows | `GET /v0/agents` (legacy, merged by id) |
| Agent detail | `GET /v1/agents/{id}` |
| Transcript | `GET /v0/agents/{id}/conversation` (v1 has no equivalent) |
| Runs | `GET /v1/agents/{id}/runs`, `GET /v1/agents/{id}/runs/{runId}` |
| Live stream, and the retained event log of finished runs | `GET /v1/agents/{id}/runs/{runId}/stream` (SSE, `Last-Event-ID` reconnect; a fresh connection replays the run from its first event until `410 stream_expired`) |
| Media in replies | `GET /v1/agents/{id}/artifacts/download?path=artifacts/…` (presigned URL for an `/opt/cursor/artifacts/…` reference) |
| Launch / follow up / cancel | `POST /v1/agents`, `POST /v1/agents/{id}/runs` (both with `prompt.images`, `mode` and inline `mcpServers`), `POST …/cancel` |
| Lifecycle | `POST …/archive`, `POST …/unarchive`, `DELETE /v1/agents/{id}` |
| Pickers | `GET /v1/models`, `GET /v1/repositories` |

## Design tokens and geometry

Taken from the Cursor 3.19 desktop build (`workbench.glass.main.*` and the bundled `theme-cursor` themes): surfaces `#141414` (chat canvas, `--cursor-chrome`), `#181818` (sidebar, inputs, cards); the foreground `#F0F0F0` (light: `#141414`) at 100 / 74 / 60 / 36 % for text, 66 / 52 / 28 % for icons, 20 / 14 / 8 / 6 / 4 % for fills and 20 / 12 / 8 / 4 % for strokes; accents `#81A1C1`, `#3FA266`, `#70B489` (git added), `#FC6B83` (git removed), `#E34671`; radii 4 / 6 / 8 / 12 / 14; type 11 / 12 / 13 / 14 with 14 / 16 / 18 / 22 line heights. UI text uses the system sans (the desktop app uses the platform system font too); code uses the bundled JetBrains Mono (SIL OFL, see `app/licenses`). Icons are line glyphs on a 24-unit grid drawn at a 1.75 stroke, the weight Cursor uses for its 16px icons; their geometry comes from [Lucide](https://lucide.dev) (ISC, see `app/licenses`), while the cube is the official Cursor brand mark (path data verbatim from [cursor.com/brand](https://cursor.com/brand)), the "working" indicator is the web's 3×3 stepping dot grid drawn on a canvas, and the stop square is drawn in-house (`CursorIcons`). Proportions follow the official web app measured at 2x (640px composer, 6px selection radius, 13px body text with 12px labels, preview cards on the left of recent rows), but everything a finger has to hit is sized for touch rather than a pointer — 44dp header, 32dp icon buttons with 44dp touch targets, 36dp sidebar rows, 28dp composer buttons, 44dp list rows — as recorded in `CursorDimens`. See `app/src/main/java/com/cursorforandroid/ui/theme/` for the mapping.

## Navigation

Three destinations (New Chat, Settings, a chat) live on an in-house back stack (`NavStack`, saved across process death) rendered by `CursorNavHost`, which composes at most the resident screen and the one beneath it and drives every transition — pushes, pops and the predictive back gesture — from one seekable progress value. The gesture's `BackEventCompat` is read directly, which is what lets the transition follow the swipe edge and the finger; `navigation-compose` cannot expose either to its transitions. Each entry has its own `rememberSaveable` scope and `ViewModelStore`, released when the entry has left the stack and finished animating out.
