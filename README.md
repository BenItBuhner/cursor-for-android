# cursor-for-android

An unofficial, native Android client for [Cursor Cloud Agents](https://cursor.com/docs/cloud-agent/api/endpoints), built with Kotlin and Jetpack Compose. It reproduces the official Cursor Agents application (the desktop Agents window and cursor.com/agents) with Android primitives: the same sidebar, the composer-first New Chat pane with the recent-chats list, the conversation view, and the design tokens shipped in the desktop build.

Not affiliated with Anysphere, Inc.

## Features

- Sign in with a Cursor user API key (validated against `GET /v1/me`), stored encrypted with the Android Keystore and excluded from backups.
- New Chat pane as home: repository / branch / environment selectors, the composer ("+" image attachments via the system photo picker, model picker with plan mode and auto-PR, dictation / send), then the recent chats with preview cards.
- Sidebar (edge-swipe drawer on phones, permanent 280dp column on tablets and foldables): New Chat, "Chats" with the filter menu (group by, sort, Repo / Status / Git / Source filters, metadata toggles), Pinned and date groups, search, pull-to-refresh, long-press actions (pin, open on cursor.com, copy link, archive, delete).
- Conversation view: transcript (`/v0/agents/{id}/conversation`), run footers ("Worked 3m 5s" + branch / PR pills), live SSE streaming of the active run with thinking, "Explored N files, M searches" tool rows, subagent cards and markdown rendering; follow-ups with image attachments (`prompt.images`), and stop.
- Settings with Cursor Dark / Cursor Light / system theme; demo mode with an in-memory backend so the UI can be explored without an API key.

## Build

Requirements: JDK 17+, Android SDK with platform 35 and build-tools 35.

```bash
./gradlew :app:assembleDebug          # APK at app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest      # JVM unit tests
./gradlew :app:recordRoborazziDebug   # re-render screenshots/ from the demo backend
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

## Design tokens and geometry

Taken from the Cursor 3.19 desktop build (`workbench.glass.main.*` and the bundled `theme-cursor` themes): surfaces `#141414` (chat canvas, `--cursor-chrome`), `#181818` (sidebar, inputs, cards); the foreground `#F0F0F0` (light: `#141414`) at 100 / 74 / 60 / 36 % for text, 66 / 52 / 28 % for icons, 20 / 14 / 8 / 6 / 4 % for fills and 20 / 12 / 8 / 4 % for strokes; accents `#81A1C1`, `#3FA266`, `#70B489` (git added), `#FC6B83` (git removed), `#E34671`; radii 4 / 6 / 8 / 12 / 14; type 11 / 12 / 13 / 14 with 14 / 16 / 18 / 22 line heights. UI text uses the system sans (the desktop app uses the platform system font too); code uses the bundled JetBrains Mono (SIL OFL, see `app/licenses`). Geometry (36px header, 32px rows on a 33px pitch, 6px selection inset with radius 6, 24px composer buttons, 640px composer, 134×84 preview cards on a 106px pitch, 13px body text with 12px labels) was measured from the official web app at 2x and is recorded in `CursorDimens`. See `app/src/main/java/com/cursorforandroid/ui/theme/` for the mapping.
