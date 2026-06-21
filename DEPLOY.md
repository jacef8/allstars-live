# Deploying All-Stars Live

This guide takes you from a fresh clone to a live game that fans can watch on their
phones. Two pieces get deployed:

| Piece | What it is | Hosted on |
|------|------------|-----------|
| **Relay** (`reference/web-scoring/server.js`) | A WebSocket server that passes the scorekeeper's taps to every viewer, and (optionally) saves state to Firebase for crash recovery | **Railway** |
| **Web pages** (`setup.html`, `scoring-controller.html`, `watch.html`, `viewer.html`, `broadcast-overlay.html`) | Game setup, the scorekeeper console, the fan landing page + viewer, and the OBS overlay | **Firebase Hosting** |

The flow at game time:

```
 scoring-controller.html ──► Railway relay (server.js) ──► viewer.html (fans)
        (you tap)              (fans out + persists)        broadcast-overlay.html (OBS)
                                      │
                                      ▼
                          Firebase Realtime DB  (optional, crash recovery)
```

You do this once. After that, each game is just: open the controller, open the viewer link, play.

---

## 0. Prerequisites (install once)

- **Node.js 18+** — check with `node -v`
- **Git** — check with `git --version`
- **A GitHub account** and the repo pushed (Step 1)
- **A Railway account** — https://railway.app (sign in with GitHub)
- **A Firebase project** — https://console.firebase.google.com
- **Firebase CLI** — `npm install -g firebase-tools`

---

## 1. Push to GitHub

From the project root:

```bash
git add -A
git commit -m "Deploy: relay + web scoring/viewer"        # if you have uncommitted changes
gh repo create allstars-live --private --source=. --push  # if the repo isn't on GitHub yet
# ...or, if the GitHub repo already exists:
git remote add origin https://github.com/<your-username>/allstars-live.git   # first time only
git push -u origin main
```

> No `gh` CLI? Create an empty repo at github.com → "New repository", then run the
> `git remote add origin …` + `git push` lines above.

---

## 2. Deploy the relay to Railway

The repo already contains `railway.json`, a root `package.json` (with a `start` script),
and a `Procfile`, so Railway knows what to run.

1. Go to https://railway.app → **New Project** → **Deploy from GitHub repo** → pick `allstars-live`.
2. Railway auto-detects Node, runs `npm install`, and starts it with
   `node reference/web-scoring/server.js`.
3. **Do NOT set `PORT`.** Railway injects it automatically; the server reads `process.env.PORT`.
4. Generate a public URL: project → service → **Settings → Networking → Generate Domain**.
   You'll get something like `allstars-live-production.up.railway.app`.
5. Confirm it's healthy: open `https://allstars-live-production.up.railway.app/health`
   in a browser — it should print `ok`. (Railway also pings `/health` automatically.)

Your **WebSocket URL** is that domain with `wss://` and no path:

```
wss://allstars-live-production.up.railway.app
```

> **HTTP vs WS:** the browser uses `https://…/health` to check health, but the live
> data uses `wss://…` (secure WebSocket). Same host, different scheme.

### 2a. (Optional) Turn on Firebase crash recovery

Skip this to run as a pure relay (state lives only in memory — fine for most games).
To survive a relay restart mid-game, set two variables in **Railway → Variables**:

| Variable | Value |
|----------|-------|
| `FIREBASE_DB_URL` | Your Realtime Database URL, e.g. `https://allstars-live-default-rtdb.firebaseio.com` |
| `FIREBASE_SERVICE_ACCOUNT` | The **entire** service-account key JSON, pasted as one value (see below) |

Getting the service-account JSON:
1. Firebase Console → ⚙ **Project settings → Service accounts → Generate new private key**.
2. A `.json` file downloads. Open it, copy **all** of it, and paste it as the value of
   `FIREBASE_SERVICE_ACCOUNT` in Railway. (It's multi-line JSON — Railway accepts that.)
3. Make sure **Realtime Database** is created: Firebase Console → **Build → Realtime Database → Create Database**.

⚠️ **Never commit the service-account file.** `.gitignore` already blocks
`serviceAccountKey.json` and `firebase-service-account*.json`.

After saving the variables Railway redeploys; the logs should show
`Firebase persistence enabled at /games/current.`

---

## 3. Deploy the web pages to Firebase Hosting

The repo already contains `firebase.json` (public dir = `reference/web-scoring`) and
`.firebaserc` (placeholder project `allstars-live`).

1. **Log in:** `firebase login`
2. **Point at your project.** Edit `.firebaserc` and replace `allstars-live` with your
   actual Firebase project ID, *or* run:
   ```bash
   firebase use --add        # pick your project, give it the alias "default"
   ```
   > You only need `firebase init hosting` if you want to regenerate config — it's
   > already set up here. If you do run it, **keep the existing `firebase.json`** (public
   > dir `reference/web-scoring`) and **do not** overwrite `viewer.html`/`404.html`.
3. **Deploy:**
   ```bash
   firebase deploy --only hosting
   ```
4. Firebase prints your Hosting URL, e.g. `https://allstars-live.web.app`.

Your pages are now at:

- Fan landing (and site root): `https://allstars-live.web.app/` → `watch.html`
- Game setup: `https://allstars-live.web.app/setup.html`
- Scorekeeper: `https://allstars-live.web.app/scoring-controller.html`
- Fan viewer: `https://allstars-live.web.app/viewer.html`
- OBS overlay: `https://allstars-live.web.app/broadcast-overlay.html`

The site **root** (`/`) serves the fan landing page (`watch.html`). Any unknown path
falls back to the friendly `404.html` hub.

---

## 4. Wire the pages to the relay

The relay URL is `wss://<your-app>.up.railway.app`. You only ever type it **once** —
into the game-setup page; everything else is generated from there.

**Scorekeeper — start here every game:**
```
https://allstars-live.web.app/setup.html
```
Fill in the teams and rules, paste the relay URL into the **Relay server URL** field
(it's remembered for next time), and tap **Start Game**. That hands off to the
scoring controller with everything baked into the URL — you never edit a query string
by hand. (Bookmark `setup.html`; that's your one entry point.)

**Fans:** don't share a URL manually — use the controller's **Share** button
(see [Sharing the game](#sharing-the-game)). It points fans at the landing page
`watch.html`, with the relay and team names already filled in.

**OBS overlay (browser source):**
```
https://allstars-live.web.app/broadcast-overlay.html?ws=wss://allstars-live-production.up.railway.app&obs
```

> The viewer and overlay accept **both** `?server=` and `?ws=` — they mean the same thing.
> Open the viewer with neither and it runs a built-in **demo** so you can preview the UI.

---

## 5. Sharing the game

Once a game is set up, the **scoring controller has a Share button** in its top
toolbar (next to the connection dot). Tap it and you get three ways to get fans
watching — no copy-pasting long URLs at the field:

- **QR code** — a QR appears in the modal. Fans point their phone camera at it and tap
  the link. The QR library is bundled locally (`lib/qrcode.min.js`), so it renders even
  if the field Wi-Fi is flaky.
- **Text a fan** — on phones that support it, this opens the native share sheet
  (Messages, WhatsApp, AirDrop, etc.) pre-filled with *"Watch All-Stars Live — Follow the
  game live:"* and the link. On devices without it, the button copies the link instead.
- **Copy link** — copies the link to paste into the team group chat, a tournament page,
  or anywhere.

### What fans get

All three share the **landing page**, not the raw viewer:

```
https://allstars-live.web.app/watch.html?ws=wss://<your-app>.up.railway.app&home=LIB&homeName=Liberty+County&away=VIS&awayName=Visitors
```

`watch.html` shows the matchup ("Visitors vs Liberty County"), a pulsing **● LIVE**
badge, and a big **Watch Now** button that opens the live viewer — a friendlier first
impression than dropping a fan straight into a scoreboard. The relay URL rides along, so
the viewer connects automatically.

> The controller builds this link for you from the current game (relay + team names), so
> you normally never type it. **The Share button is the workflow** — QR at the
> concession stand / dugout fence, "Text a fan" to the team chat.

### Customizing the team names in the link

The team names come from the four query params, so you can hand-craft a link if you ever
need to (e.g. for a flyer printed before setup):

| Param | Meaning | Example |
|-------|---------|---------|
| `ws` | Relay URL (required for live data) | `wss://allstars-live-production.up.railway.app` |
| `home` | Home abbreviation (≤3 chars) | `LIB` |
| `homeName` | Home full name (URL-encode spaces as `+` or `%20`) | `Liberty+County` |
| `away` | Away abbreviation | `VIS` |
| `awayName` | Away full name | `Visitors` |

Only `ws` is needed for the viewer to work; the team params just pre-fill the landing
page. The shortest possible fan link is just `watch.html?ws=wss://…`.

> **Tip:** the bare site root also works — `https://allstars-live.web.app/` serves the
> landing page (via the `firebase.json` rewrite). Add `?ws=…&home=…` to personalize it.

Fans just open it — no install, no login. On the viewer, the connection dot shows
**LIVE** when receiving, **RECONNECTING** if the network blips (it auto-recovers), and a
**"Waiting for the game to start…"** card before the first pitch.

---

## Quick reference

```bash
# Redeploy the relay after a server.js change:
git add -A && git commit -m "relay: <what changed>" && git push    # Railway auto-deploys on push

# Redeploy the web pages after an HTML change:
firebase deploy --only hosting

# Tail relay logs:
#   Railway dashboard → your service → Deployments → View Logs
```

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| Viewer stuck on **RECONNECTING/OFFLINE** | Check the `wss://` URL is exactly the Railway domain (no `https://`, no trailing path). Open `…/health` over https to confirm the relay is up. |
| Viewer shows **DEMO** badge | No `?server=`/`?ws=` param on the URL — add it. |
| Relay logs `Firebase init failed` | The `FIREBASE_SERVICE_ACCOUNT` value isn't valid JSON, or Realtime Database wasn't created. Re-paste the full key; create the DB. |
| `firebase deploy` uploads `server.js`/`node_modules` | They're already in `firebase.json`'s `ignore` list — make sure you didn't remove it. |
| Fans see an old score on join | Expected to self-correct on the next tap; the relay replays the last state on connect. With Firebase enabled, a relay restart also recovers the last state. |
