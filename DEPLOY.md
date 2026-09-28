# Deploying All-Stars Live

Two pieces get deployed. Both are already configured in this repo — you're redeploying
an existing setup, not standing one up from scratch.

| Piece | What it is | Hosted on |
|------|------------|-----------|
| **Web app** (`reference/web-scoring/scoring-controller.html`) | The whole app — home, teams, live scoring, broadcast monitor, and the fan viewer — all one file, one route (`/`). Firestore powers cross-device sync + the live viewer. | **Firebase Hosting** |
| **App server** (`reference/web-scoring/server.js`) | A plain static server for the same web app — it is what the native Android app's WebView loads. | **Railway** |

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
- **A Railway account**, for the app server — https://railway.app (only needed if redeploying it)

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

## 2. Deploy the app server to Railway

Railway runs `reference/web-scoring/server.js` whichever way the service is configured: with
its Root Directory set to `reference/web-scoring` (what that folder's own `DEPLOY.md` documents)
it uses the folder's `railway.json` + `package.json` (`node server.js`); with no Root Directory it
uses the root `railway.json` / `Procfile` (`node reference/web-scoring/server.js`). Both sets are
kept in sync.

1. Railway → your `allstars-live` service → it auto-deploys the server on every push to `main`
   (GitHub integration). No manual step needed for routine changes.
2. **Do NOT set `PORT`** — Railway injects it; the server reads `process.env.PORT`.
3. Confirm it's healthy: `https://web-production-77d34.up.railway.app/health` → should
   print `ok`.

The native Android app loads the web app from this Railway URL (`APP_URL` in
`GameScorerScreen.kt`), so this deploy is what reaches the tablets. There is no relay and no
Railway-side Firebase configuration any more — live sync is Firestore, deployed in step 1.

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

# Redeploy the Railway app server (what the tablets load) after a web change:
git add -A && git commit -m "web: <what changed>" && git push    # Railway auto-deploys on push

# Tail Railway logs:
#   Railway dashboard → your service → Deployments → View Logs
```

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| Web changes not showing up live | You need `firebase deploy --only hosting` — pushing to git alone doesn't deploy the web app. |
| Firestore rule changes not taking effect | Same idea — `firebase deploy --only firestore:rules`, separate from `--only hosting`. |
| A shared link opens to a blank/generic Home instead of the expected team/player/game | Check the query param is one the app actually reads: `view`, `feed`, `vid`, `yt`, `watch`, `tn`, `follow`, `player`, `overlay`. |
| `firebase deploy` uploads `server.js`/`node_modules` | They're in `firebase.json`'s hosting `ignore` list — make sure it wasn't removed. |
