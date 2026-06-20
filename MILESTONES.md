# All-Stars Live — Milestone Plan

Path from today's state to a tool the scorer can run at a real Liberty County
AAA all-star game. Each milestone has a **Definition of Done** that is observable
at the field, not just "code written."

**Where things stand (2026-06-20):** the app is an M0 skeleton plus an in-progress
SRT ingest spike (M1). All polished scoring/overlay UI currently lives in the web
prototypes under `reference/web-scoring/` — none of it is in the Android app yet.
The two worlds (native video, web scoring) are not yet integrated.

Legend: ✅ done · 🔶 in progress · ⬜ not started

---

## M0 — App skeleton ✅
Compose app, landscape + keep-screen-on, theme, home→ingest navigation.
- **DoD:** app launches on the tablet and opens the ingest screen. *(met)*

---

## M1 — SRT ingest: real Mevo frame on the tablet 🔶
Receive the Mevo SRT feed over local Wi-Fi and render a live frame, with an
honest FPS/latency HUD. Route chosen: **libsrt + NDK → MediaCodec** (see the
`allstars-live-srt-ingest-decision` memo; ffmpeg-kit's retirement ruled out the
FFmpeg AAR).

- 🔶 Native scaffold landed (`app/src/main/cpp/`), compiles in stub mode.
- ⬜ Vendor + build libsrt (mbedTLS), flip `USE_LIBSRT=ON`.
- ⬜ Implement the SRT receive loop (`srt_jni.cpp::runReceive`).
- ⬜ Implement the MPEG-TS depacketizer (`ts_demuxer.cpp::feed`).
- ⬜ Swap `StubVideoSource` → `SrtVideoSource`.
- ⬜ Reconnect handling (survive a Wi-Fi blip mid-game).
- ⬜ Live validation: real Mevo + tablet on a dedicated AP; confirm
  caller/listener + port, tune `SRTO_LATENCY`, decide audio source.
- **DoD:** point the Mevo at the tablet, press Connect, and see live video with
  stable FPS and sub-~300 ms latency; survives a brief network drop.

---

## M2 — Scoring engine in the Android app ⬜
Bring the web controller's logic and touch UI into the app. The web engine is the
reference of record (controller = source of truth); the run-cap and continuous-
lineup rules are already implemented there and must be carried over.

- ⬜ Decide port strategy: reimplement in Kotlin/Compose **or** host the existing
  HTML in a `WebView`. (Lean Kotlin for the composited overlay path in M3.)
- ⬜ Port the state machine: balls/strikes/fouls, outs, inning flip, score,
  forced-walk advancement, DP/error/FC pickers, **End Inning**, multi-step undo.
- ⬜ Port the field-as-input flow (locate → contact → result → runners).
- ⬜ Game setup: teams, our side, lineup entry (replace hardcoded roster).
- **DoD:** a full half-inning can be scored on the tablet with no laptop, and the
  resulting state matches what the web controller would produce for the same taps.

---

## M3 — Unified game screen + navigation ⬜
The screen the scorer actually uses: **live SRT video + scorebug overlay + scoring
controls, all at once** — what `studio-preview.html` only fakes with a webcam.

- ⬜ Composite the scorebug over the live video surface (WebView overlay on the
  `SurfaceView`, or a Compose-drawn bug).
- ⬜ Drive the overlay from the in-app scoring state (replace the web-only
  BroadcastChannel/WebSocket link).
- ⬜ App navigation: Game / Lineup / Stats / Connection-Settings (replace the
  2-state enum) — design exists in `nav-preview.html`.
- **DoD:** one screen shows the live game feed with the live scorebug updating as
  the scorer taps; sections are reachable via real navigation.

---

## M4 — Tournament rules + persistence ⬜
Make it correct and survivable for a real all-star game.

- ✅ Run-per-inning cap (web engine) — **port to app.**
- ✅ Continuous batting order, 10–12 (web engine) — **port to app.**
- ⬜ Remaining rule gaps as needed: dropped third strike, courtesy runner,
  run-rule / mercy game-end, third-out run timing guard.
- ⬜ Game persistence: auto-save state so closing/crashing mid-game recovers.
- ⬜ Stats accumulation (the Stats tab is an empty placeholder today).
- **DoD:** a 6-inning game with a 5-run cap and a 12-player continuous order
  scores correctly end-to-end; killing and reopening the app restores the game.

---

## M5 — Streaming egress (broadcast out) ⬜
Push the composited video+overlay to YouTube/RTMP. There is **no egress path
today** — `studio-preview.html` flags this as the unbuilt "ffmpeg layer."

- ⬜ Encode the composited surface (video + burned-in bug) to H.264.
- ⬜ RTMP/RTMPS push to YouTube Live (or SRT out), with reconnect.
- ⬜ Bitrate/keyframe tuning for the venue's uplink; bandwidth fallback.
- **DoD:** a private YouTube Live stream shows the game with the scorebug, stable
  for a full game on the field's actual connection.

---

## M6 — Field-readiness / productionization ⬜
- ⬜ App icon, naming, real theme polish.
- ⬜ Battery/thermal endurance for a 2-hour handheld session; glare/landscape
  ergonomics; large tap targets.
- ⬜ NDK packaging checks (16 KB page size), release build hardening.
- ✅ Put the project under version control (git initialized).
- ⬜ Dry-run at a scrimmage before relying on it for a real game.
- **DoD:** a non-developer can set up and run a full game unaided.

---

## Web stack deployment (controller + viewer + relay) ✅ wired

The web scoring/viewer system deploys today, independent of the Android app:
**Railway** hosts the WebSocket relay (`server.js`); **Firebase Hosting** serves the
HTML pages; **Firebase Realtime Database** optionally persists state for crash recovery.
Full step-by-step (with troubleshooting) is in **[DEPLOY.md](DEPLOY.md)** — the essentials:

```bash
# 1. Code → GitHub
git push -u origin main

# 2. Relay → Railway (auto-runs `node reference/web-scoring/server.js` via railway.json)
#    Railway sets PORT automatically. Optional crash recovery: set these in Railway → Variables:
#      FIREBASE_DB_URL=https://<project>-default-rtdb.firebaseio.com
#      FIREBASE_SERVICE_ACCOUNT=<full service-account key JSON>
#    Grab the generated domain → your WS URL is  wss://<app>.up.railway.app

# 3. Pages → Firebase Hosting (public dir = reference/web-scoring, per firebase.json)
firebase login
firebase use --add            # select your project (edit .firebaserc's "allstars-live" first)
firebase deploy --only hosting
```

Then point the pages at the relay:
- Fans:        `…/viewer.html?ws=wss://<app>.up.railway.app`
- Scorekeeper: `…/scoring-controller.html?server=wss://<app>.up.railway.app`
- OBS:         `…/broadcast-overlay.html?ws=wss://<app>.up.railway.app&obs`

---

### Critical path
M1 (see a frame) → M2 (score in-app) → M3 (one combined screen) is the spine of a
*usable* scorer. M4 makes it *correct and safe* for tournament play. M5 is a
separate track only needed if you want to **broadcast**, not just score on the
glass — it can run in parallel once M3 lands.
