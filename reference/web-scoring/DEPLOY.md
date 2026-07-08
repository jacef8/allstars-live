# Deploy — All-Stars Live web (the console)

**What this deploys:** the **single-page app** (`scoring-controller.html`) — home screen,
team management, live scoring, the broadcast monitor + scorebug, and the fan viewer are
all one file, one route. There are no separate `setup.html`/`watch.html`/`viewer.html`/
`broadcast-overlay.html` pages anymore (those were an earlier prototype architecture —
archived in `_archive/`, not part of the deployed app). This is the web mirror of the
native tablet app (`app/`), minus the camera/RTMP pipeline, which is native-only.

**Sharing** is built into the app itself — the Share button (top bar, home screen, or a
team/player page) opens a QR + Email/Text/Copy modal. Every link it generates points back
at this same `scoring-controller.html`, just with different query params
(`?view=viewer`, `?follow=<teamId>`, `?player=<teamId>:<playerId>`, `?overlay=1`, etc.) —
there's no separate landing page to keep in sync.

Two pieces get deployed:

- **Firebase** (Hosting + Firestore + Storage) — the real production target. `firebase.json`
  serves this whole directory (`reference/web-scoring`) with `/` rewritten straight to
  `scoring-controller.html`. Firestore holds teams/games/chat/schedule and powers
  cross-device sync + the live viewer (`onSnapshot` on a team's live-game doc). This is
  what `appBaseUrl()` in the app points at (`https://allstars-live.web.app/`).
- **Railway** (`server.js`) — a WebSocket relay, still deployed and still used as a
  live-sync channel (`new WebSocket(...)`, default `wss://web-production-77d34.up.railway.app`,
  overridable with `?server=`/`?ws=`) alongside Firestore. Not required for the app to work
  (Firestore sync covers cross-device/cross-network sync on its own) but still wired in.

**GitHub Pages is OFF.** It was accidentally enabled at one point, failed on most pushes,
and was explicitly disabled (2026-07-03) — it has zero effect on the real deploy. Ignore
any old instructions that mention it.

---

## A. Relay on Railway ✅ deployed

- Config is pinned in `railway.json` (`node reference/web-scoring/server.js`,
  healthcheck `/health`).
- **Root Directory** = `reference/web-scoring`. **Do not set `PORT`** — Railway injects it.
- The `wss://web-production-77d34.up.railway.app` URL is baked into the app as the
  default relay (`?server=`/`?ws=` overrides it).

## B. App on Firebase Hosting ✅ deployed

```bash
firebase deploy --only hosting          # scoring-controller.html + assets
firebase deploy --only firestore:rules  # after editing firestore.rules
```

- Live at **`https://allstars-live.web.app/`** — this is the real production domain
  the app's own `appBaseUrl()` hands out in every share link (not a placeholder).
- `.firebaserc` project id: `allstars-live`.
- `firebase.json`'s hosting `ignore` list keeps `server.js`/`package.json`/`node_modules`
  out of the Hosting bundle (those are Railway's job, not Firebase's).

---

## Notes / gotchas

- The **camera → video → YouTube** pipeline (RTMP receive, compositor, Broadcast) is the
  native tablet app only (`app/`) — deployed separately via a Gradle build + `adb install`,
  not through this file.
- Archived prototypes (the old separate viewer / gamecast / overlay / setup pages) live
  in `_archive/` and are not part of the deployed app.
- Pushing to `origin/main` does **not** auto-deploy either piece — Firebase Hosting and
  Firestore rules both need an explicit `firebase deploy`; Railway auto-deploys the relay
  on push (per its GitHub integration) but the web app itself does not.
