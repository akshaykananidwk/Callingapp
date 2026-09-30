# CallBridge

Streams live GSM call audio (16 kHz mono PCM, 200 ms frames) to your VPS over WebSocket and shows the
live transcript on the phone. Built from the CallBridge PRD v1.0.

**Server + dashboard setup (Gujarati step-by-step): [deploy/DEPLOY-GU.md](deploy/DEPLOY-GU.md)** —
one command: `sudo bash deploy/install.sh`.

**Website calling through a landline (Grandstream HT813 FXO: [deploy/LANDLINE-GU.md](deploy/LANDLINE-GU.md)) or a GoIP GSM gateway ([deploy/GOIP-GU.md](deploy/GOIP-GU.md)), via Asterisk + WebRTC** —
`sudo bash deploy/pbx-install.sh`. The website becomes the phone: incoming calls ring in the browser, dial any
number, auto-answer with a demo clip injected straight into the line, two-sided recording and live transcript.

| Folder | What |
|---|---|
| `app/` | Android app (Kotlin, Compose) |
| `server/` | Node.js backend: REST API, audio WebSocket, Whisper STT, web dashboard, token management |
| `deploy/` | Installers (app: Nginx, PostgreSQL, whisper.cpp, SSL, systemd; PBX: Asterisk, fail2ban), Asterisk templates, guides |
| `release/` | Prebuilt APK |

## Install the app

1. Download `release/CallBridge-v1.0.1.apk` (or from the dashboard → Settings) (or the `CallBridge-apk` artifact from the GitHub Actions run).
2. On the phone: Settings → allow *Install unknown apps* for your browser/files app → open the APK.
3. First launch: **Grant all permissions** → **Disable battery optimization** → enter VPS URL + API token → **Start CallBridge**.
4. Samsung (One UI): Apps → CallBridge → Battery → *Unrestricted*; Permissions → Microphone → *Allow*.

## Build

```
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk (R8, debug-key signed)
./gradlew assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17+ and an Android SDK with platform 35 (`local.properties` → `sdk.dir=...`).

## Server contract the app uses

Base URL setting, default `https://test.akdwk.in`. All requests send `Authorization: Bearer <token>`.

| Call | Details |
|---|---|
| `POST /api/calls/start` | `{number, direction, timestamp (ISO-8601), language}` → `{call_id}` (or `{id}`) |
| `POST /api/calls/:id/end` | `{duration, number}` |
| `GET /api/calls` | `page, limit, number, from_date` → array or `{calls:[...]}` of `{id, phone_number, direction, started_at, duration_sec}` |
| `GET /api/calls/search?q=` | same shape; optional `text`/`snippet` per row |
| `GET /api/calls/:id/transcript` | array or `{segments:[...]}` of `{text, offset_ms}` |
| `POST /api/device/heartbeat` | every 60 s: `{battery, network, service_status}` |
| `GET /api/device/status` | used by *Test connection* |
| `wss://…/stream` | headers `Authorization`, `X-Call-ID`, `X-Language`; binary PCM frames in; `{"type":"transcript","text","ts"}` out; client sends `{"type":"end","call_id"}` at hang-up |

`ts` may be ms-from-call-start or epoch ms; both are handled.

## Known platform limits

- **Call audio capture:** stock Android 10+ restricts third-party apps from recording call audio. Depending on
  the phone, you may get only your side, or silence. Try each source under Settings → Audio source, and the
  optional *Call audio helper* (Accessibility). This has to be verified on the S25 Ultra and OnePlus 9.
- **After reboot on Android 14+:** Android does not let a microphone foreground service start from boot. CallBridge
  posts a "Tap to resume" notification; one tap restores it. On Android 13 and below it starts on its own.
- Hilt/Room from the PRD tech stack were left out to keep v1 small; history is cached in a local JSON file.
- The dashboard is plain HTML/JS served by the Node server (not Next.js), and services run under systemd
  instead of PM2 — fewer moving parts to install. Transcripts arrive in ~5 s chunks (`CHUNK_SECONDS`).
