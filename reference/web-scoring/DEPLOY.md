# Deploy — All-Stars Live web (the console)

**What this deploys:** the **all-in-one console** (`scoring-controller.html`) — the
broadcast monitor + scorebug + scoring controls on a single page, one device. This is
the web mirror of the native tablet app.

**Fans don't open any of our pages** — they watch the **YouTube stream** (the scorebug
is burned into the video). The console's **Share with fans** button hands out the
YouTube watch link / QR. There is intentionally **no separate viewer page**.

Two pieces:
- **Relay** (Railway) — `server.js`. Optional now: it passes live state between devices
  and is kept for the native app / future multi-device use. The single console works
  without it.
- **Pages** (GitHub Pages) — the static console, reachable from any device by URL.

---

## A. Relay on Railway  ✅ deployed

- Live at **`https://web-production-77d34.up.railway.app`** (root says
  *"Liberty League relay is running."*, `/health` returns `ok`).
- Config is pinned in `railway.json` (`node server.js`, healthcheck `/health`).
- **Root Directory** = `reference/web-scoring`. **Do not set `PORT`** — Railway injects it.
- The `wss://web-production-77d34.up.railway.app` URL is already baked into the console
  as the default (an explicit `?server=` / `?ws=` still overrides).
- *(Optional)* crash-recovery persistence: add env vars `FIREBASE_DB_URL` and
  `FIREBASE_SERVICE_ACCOUNT`. Leave unset to run as a pure in-memory relay.

---

## B. Pages on GitHub Pages

1. Repo **Settings → Pages → Source = Deploy from a branch → `main`** → Save.
2. Wait ~1 min for the green "Your site is live" banner.
3. Clean entry URL (repo-root `index.html` redirects to the console):
   **`https://jacef8.github.io/allstars-live/`**
   - Direct: `…/allstars-live/reference/web-scoring/scoring-controller.html`
   - Embed the live YouTube feed in the monitor with `?yt=VIDEO_ID`.

---

## Notes / gotchas
- GitHub Pages is **HTTPS**, so the relay must be **WSS** — Railway provides that. ✓
- The Pages repo is **public** (no secrets are committed; the keystore and any
  service-account JSON are git-ignored).
- The **camera → video → YouTube** pipeline is the native tablet app (M2/M3) and is
  deployed separately from this web console.
- Archived prototypes (the old separate viewer / gamecast / overlay / setup pages) live
  in `_archive/` and are not part of the deployed app.
