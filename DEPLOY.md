# Deploying All-Stars Live

Two pieces get deployed. Both are already configured in this repo — you're redeploying
an existing setup, not standing one up from scratch.

| Piece | What it is | Hosted on |
|------|------------|-----------|
| **Web app** (`reference/web-scoring/scoring-controller.html`) | The whole app — home, teams, live scoring, broadcast monitor, and the fan viewer — all one file, one route (`/`). Firestore powers cross-device sync + the live viewer. | **Firebase Hosting** |
| **Relay** (`reference/web-scoring/server.js`) | A WebSocket relay used as an additional live-sync channel alongside Firestore. | **Railway** |

There is no separate setup/watch/viewer/overlay page anymore — that was an earlier
prototype architecture, archived in `reference/web-scoring/_archive/`. Sharing (QR code,
email, text, copy link, OBS overlay link) is a modal built into the app itself; every
link it generates just points back at `scoring-controller.html` with different query
params (`?view=viewer`, `?follow=`, `?player=`, `?overlay=1`, `?watch=`).

**GitHub Pages is intentionally OFF** — it was accidentally enabled once, failed on most
pushes, and was disabled (2026-07-03). It has no bearing on the real deploy.

---

## 0. Prerequisites (install once)

- **Node.js 18+** — check with `node -v`
- **Git** — check with `git --version`
- **A Firebase project** (already set up: project id `allstars-live`) — https://console.firebase.google.com
- **Firebase CLI** — `npm install -g firebase-tools`, then `firebase login`
- **A Railway account**, for the relay — https://railway.app (only needed if redeploying the relay)

---

## 1. Deploy the web app to Firebase Hosting

The repo already contains `firebase.json` (public dir = `reference/web-scoring`, `/`
rewritten to `scoring-controller.html`) and `.firebaserc` (project `allstars-live`).

```bash
firebase deploy --only hosting
```

That's it — no build step, no separate pages to wire together. The app is live at:

```
https://allstars-live.web.app/
```

If you changed `reference/web-scoring/firestore.rules`, deploy those too (separately —
`--only hosting` does not touch rules):

```bash
firebase deploy --only firestore:rules
```

Pushing to `origin/main` does **not** auto-deploy this — always run `firebase deploy`
explicitly after a web change.

---

## 2. Deploy the relay to Railway

The repo already contains `railway.json`, a root `package.json`, and a `Procfile`.

1. Railway → your `allstars-live` service → it auto-deploys `node reference/web-scoring/server.js`
   on every push to `main` (GitHub integration). No manual step needed for routine changes.
2. **Do NOT set `PORT`** — Railway injects it; the server reads `process.env.PORT`.
3. Confirm it's healthy: `https://web-production-77d34.up.railway.app/health` → should
   print `ok`.

The app's default relay URL (`wss://web-production-77d34.up.railway.app`) is already
baked in; `?server=`/`?ws=` on the URL overrides it if you ever run a second relay.

### 2a. (Optional) Firebase crash-recovery persistence for the relay

Skip this to run as a pure in-memory relay (fine for most games). To survive a relay
restart mid-game, set two variables in **Railway → Variables**:

| Variable | Value |
|----------|-------|
| `FIREBASE_DB_URL` | Realtime Database URL, e.g. `https://allstars-live-default-rtdb.firebaseio.com` |
| `FIREBASE_SERVICE_ACCOUNT` | The **entire** service-account key JSON, pasted as one value |

Getting the service-account JSON: Firebase Console → ⚙ **Project settings → Service
accounts → Generate new private key** — a `.json` downloads; paste its full contents as
the `FIREBASE_SERVICE_ACCOUNT` value. Make sure Realtime Database is created first
(Console → Build → Realtime Database → Create Database).

⚠️ **Never commit the service-account file** — `.gitignore` already blocks it.

---

## 3. Sharing the game (how fans actually get a link)

Nothing to configure here — this is just how it works, in case you're wondering where a
fan's link comes from. Open the app, tap **Share** (top bar, or on a team/player page).
The modal offers:

- **QR code** — bundled locally (`lib/qrcode.min.js`), renders even on flaky field Wi-Fi.
- **Email / Text** — pre-filled subject + body with the link. Routes through the native
  app's `openExternal` bridge when running inside the tablet app (mailto:/sms: links
  don't open from inside that WebView otherwise); falls back to the OS share sheet or a
  plain `mailto:`/`sms:` link in a browser.
- **Copy link** — just the URL.
- **Copy OBS overlay link** (game shares only, or when a game's actually live) — a bare
  `?watch=<id>&overlay=1` link for a browser-source in OBS/Streamlabs/vMix; transparent
  background, just the scorebug.

The link itself is always `https://allstars-live.web.app/?view=viewer&...` — the app
detects viewer mode and shows the live scoreboard/feed/video, no separate page.

---

## Quick reference

```bash
# Redeploy the web app after an HTML/JS change:
firebase deploy --only hosting

# Redeploy Firestore rules after editing firestore.rules:
firebase deploy --only firestore:rules

# Redeploy the relay after a server.js change:
git add -A && git commit -m "relay: <what changed>" && git push    # Railway auto-deploys on push

# Tail relay logs:
#   Railway dashboard → your service → Deployments → View Logs
```

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| Web changes not showing up live | You need `firebase deploy --only hosting` — pushing to git alone doesn't deploy the web app. |
| Firestore rule changes not taking effect | Same idea — `firebase deploy --only firestore:rules`, separate from `--only hosting`. |
| A shared link opens to a blank/generic Home instead of the expected team/player/game | Check the query param is one the app actually reads: `view`, `feed`, `vid`, `yt`, `watch`, `tn`, `follow`, `player`, `overlay`. |
| Relay logs `Firebase init failed` | `FIREBASE_SERVICE_ACCOUNT` isn't valid JSON, or Realtime Database wasn't created — re-paste the full key, create the DB. |
| `firebase deploy` uploads `server.js`/`node_modules` | They're in `firebase.json`'s hosting `ignore` list — make sure it wasn't removed. |
