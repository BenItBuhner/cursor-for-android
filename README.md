# cursor-for-android

An unofficial, native Android client for [Cursor Cloud Agents](https://cursor.com/docs/cloud-agent/api/endpoints), built with Kotlin and Jetpack Compose. It mirrors the layout of the official iOS app (sidebar, Customize sheet, conversation view, New Agent composer) and reproduces the desktop app's colour, radius and typography system using Android primitives instead of Liquid Glass.

Not affiliated with Anysphere, Inc.

## Features

- Sign in with a Cursor user API key (validated against `GET /v1/me`), stored encrypted with the Android Keystore.
- Agent list with pinned + date / repo / status grouping, sorting, search, unread tracking, pull-to-refresh, and a swipe-in sidebar drawer over every detail screen (permanent sidebar on tablets and foldables).
- Customize sheet: Group by, Sort, Repo / Status / Git / Source filters, and Agent Metadata toggles (Workspace, Branch Status, Runtime).
- Conversation view: transcript (`/v0/agents/{id}/conversation`), run footers ("Worked 3m 5s" + branch / PR chips), and live SSE streaming of the active run with thinking blocks, "Explored N files, M searches" tool activity, Subagents cards, and markdown rendering.
- Follow-ups, stop run, archive / unarchive / delete, pin, copy URL, open on the web.
- New Agent composer with repository, branch, model + variant, plan mode and auto-PR options, plus system speech input.
- Inbox for unread / failed agents, settings with Cursor Dark / Cursor Light / system theme.
- Demo mode with an in-memory backend so the UI can be explored without an API key.

## Build

Requirements: JDK 17+, Android SDK with platform 35 and build-tools 35.

```bash
./gradlew :app:assembleDebug          # APK at app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest      # JVM unit tests
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

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

## Design tokens

Colours come from the theme JSON bundled with the desktop app ("Cursor Dark Anysphere" and "Cursor Light"), cross-checked against the web app and the iOS screenshots: `#141414` canvas, `#181818` elevated surfaces, `#E4E4E4` base overlay at 92 / 55 / 37 / 15 / 12 / 7 / 4 percent for text, focus, selection, borders and washes, accents `#81A1C1` (blue), `#3FA266` (green), `#F1B467` (orange), `#E34671` (danger). Radii follow the desktop 6 / 8 / 10 / 12 scale. UI text uses the system sans (the desktop app uses the platform system font too); code uses the bundled JetBrains Mono (SIL OFL, see `app/licenses`).
