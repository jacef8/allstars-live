# Deploy — All-Stars Live web (relay + pages)

**What this deploys:** the WEB prototype — the **scorer** (`scoring-controller.html`),
the **viewer** (`viewer.html`), and **GameCast** (`gamecast.html`). It does **not**
include the native camera app (that runs on the tablet from the Android build).

Two pieces work together:
- **Relay** (Railway) — `server.js`. Passes live score state between devices and
  catches late joiners up. Without it, the pages only sync within one browser.
- **Pages** (GitHub Pages) — the static HTML, reachable from any device by URL.

---

## A. Relay on Railway

1. Push this project to a GitHub repo (see **B.1** — use the same repo).
2. [railway.app](https://railway.app) → **New Project → Deploy from GitHub repo** → pick the repo.
3. Project → **Settings → Root Directory** = `reference/web-scoring`
   (so Railway finds this `package.json` / `server.js`).
4. Railway auto-detects Node and runs `npm install` then `npm start`.
   **Do not set `PORT`** — Railway injects it.
5. **Settings → Networking → Generate Domain.** You'll get a URL like
   `https://allstars-live.up.railway.app`. The WebSocket URL is the same host with `wss://`:
   **`wss://allstars-live.up.railway.app`**
6. Verify: open the `https://…` URL in a browser → it should say
   *"Liberty League relay is running."* (and `/health` returns `ok`).
7. *(Optional)* crash-recovery persistence: add env vars `FIREBASE_DB_URL` and
   `FIREBASE_SERVICE_ACCOUNT`. Leave them unset to run as a pure in-memory relay.

---

## B. Pages on GitHub Pages

1. Create a GitHub repo and push the project.
2. Repo **Settings → Pages → Source = Deploy from branch → `main`**.
   - Root of repo → pages live at `…/reference/web-scoring/scoring-controller.html`.
   - For clean URLs, serve from `/docs` (copy these files there) or a `gh-pages` branch.
3. GitHub gives you `https://<user>.github.io/<repo>/…`.
4. Open on each device, pointing at the relay with `?server=`:
   - **Tablet (scorer):** `…/scoring-controller.html?server=wss://allstars-live.up.railway.app`
   - **Phones / TV (fans):** `…/viewer.html?server=wss://…` and `…/gamecast.html?server=wss://…`
   - GameCast can also embed YouTube: add `&yt=VIDEO_ID`.

---

## C. Skip the `?server=` (optional)

So you don't type the relay URL every time, I can set it as the **default** in the
pages. Send me your Railway `wss://…` URL and I'll bake it in (pages still honor an
explicit `?server=` override).

---

## Notes / gotchas
- GitHub Pages is **HTTPS**, so the relay must be **WSS** — Railway provides that. ✓
- GitHub Pages repos are **public** (the scoreboard/scorer will be publicly reachable by URL).
- Same browser/device syncs via BroadcastChannel automatically; **cross-device needs the relay** (`?server=`).
- This is the scoring + fan-view experience over the internet. The **camera → video → YouTube**
  pipeline is the native tablet app (M2/M3) and is deployed separately.
