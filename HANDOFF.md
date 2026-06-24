# All‑Stars Live — Project Handoff / Master Status

> **Read this first.** This is the single source of truth for picking up the project — e.g. from a
> fresh Claude Code session or a different account. The code is the truth; this explains the rest.

_Last updated: 2026‑06‑23 (app version **v54**). **Keep this file current as work continues** —
update the version, status, and pending lists with each meaningful change._

> Recent: v52 unified Email/phone invite + fixed delete; v53 delete-tombstones + turf launcher icon;
> v54 **team page is now a hub** (grid of sections: Roster, Schedule, Stats, Access, Rules, + a
> prominent Team chat) driven by `teamTab`. Native Google sign-in still pending (WebView blocks
> Google OAuth — needs native Credential Manager + the OAuth Web client ID); email-link works in-app.

> **v51 ARCHITECTURE CHANGE:** the native app now loads the **live https web app** (offline‑cached),
> not bundled `file://` assets. → **web pushes reach the native app with no APK rebuild**; rebuild the
> APK only for native Kotlin (camera/streaming, splash, icon, WebView loader). Sign‑in (Google +
> email) + cloud now work on native. (See `createScorerWebView` in `GameScorerScreen.kt`: `APP_URL`.)

---

## 1. What this is
**All‑Stars Live** — a youth‑baseball **scoring + live‑broadcast** app for jford (Liberty County
Clerk of Court). Two surfaces, **one shared web app**:
- **Native Android app** (`com.libertyclerk.allstarslive`) — the primary product. Kotlin/Jetpack
  Compose shell that wraps the web app in a WebView and adds **camera ingest + streaming** (Mevo →
  RTMP → composite scorebug → YouTube Live).
- **Web app / PWA** — the same scoring app at the Railway URL; used by people **without** the native
  app (e.g. a parent who taps a shared link to watch, or to score from a browser).

Design intent (jford's words): *all users are on the native app (Android/iOS); the web is the
no‑app option — but the web can still score, it just can't stream.*

---

## 2. Where everything lives
- **Repo (the real one):** `C:\Users\jford\Documents\Baseball`
  - ⚠️ The Cowork/Claude working dir is `C:\Users\jford\OneDrive - ...\Desktop\Jace\Apps\Baseball`,
    which **only** holds `.claude/launch.json` pointing at the real repo. **Always edit the
    `Documents\Baseball` copy.**
- **GitHub remote:** `https://github.com/jacef8/allstars-live` (branch `main`).
- **Live web app (Railway):** `https://web-production-77d34.up.railway.app/scoring-controller.html`
  (push to `main` → Railway auto‑deploys).
- **Claude memory** (this account only, NOT in the repo):
  `C:\Users\jford\.claude\projects\C--Users-jford-OneDrive...-Baseball\memory\` — start at
  `MEMORY.md`. A new account won't have this; copy that folder, or rely on this HANDOFF.

### Key files
| File | Purpose |
|---|---|
| `reference/web-scoring/scoring-controller.html` | **THE app** — one big vanilla‑JS file (UI, scoring, state, render). Single source of truth. |
| `reference/web-scoring/auth.js` | Firebase Auth (Google + email‑link). Dormant on `file://`. |
| `reference/web-scoring/cloud-data.js` | Firestore sync: teams, scorers/followers, **chat**, **persistent games**. |
| `reference/web-scoring/firebase-config.js` | Firebase web config (public key — safe to commit). |
| `reference/web-scoring/firestore.rules` | Security rules — **must be published in the Firebase console by jford**. |
| `reference/web-scoring/sw.js` | PWA service worker; bump `CACHE` every deploy. |
| `reference/web-scoring/server.js` | Railway server: serves the app + the WebSocket **relay** (live viewer sync). |
| `app/src/main/java/.../MainActivity.kt` | Native shell: full‑screen WebView + splash + camera overlay (no tabs as of v48). |
| `app/src/main/java/.../scorer/GameScorerScreen.kt` | The WebView + JS bridge (`AllStars`) + turf background + camera preview. |
| `app/src/main/java/.../ingest/` | RTMP receiver, decoder, compositor, YouTube push. |
| `app/build.gradle.kts` | `syncScorerAssets` copies `reference/web-scoring/` → `app/src/main/assets/scorer/` at build. |
| `reference/dyb-rules-2026.txt` | Extracted Diamond Youth Baseball 2026 rules (league presets source). |

---

## 3. How to build / deploy
**Web (PWA + native's bundled copy both come from here):**
1. Edit `reference/web-scoring/scoring-controller.html`.
2. Bump `var APP_VERSION="vNN"` (bottom of the file) **and** `CACHE="allstars-vNN"` in `sw.js` together.
3. Syntax check: `node -e '...'` over the inline `<script>` blocks (vanilla JS, no build step).
4. `git add <files> && git commit && git push origin main` → Railway auto‑deploys. PWA self‑updates
   via the service worker (forced reload on new `CACHE`).

**Native APK (only when a native change, or to ship the latest web to the tablet):**
```
export ANDROID_HOME="/c/Users/jford/AppData/Local/Android/Sdk"
cd /c/Users/jford/Documents/Baseball
./gradlew :app:assembleDebug            # runs syncScorerAssets (copies reference web → assets)
$ANDROID_HOME/platform-tools/adb.exe install -r app/build/outputs/apk/debug/app-debug.apk
```
Tablet device id seen in this project: `R52XC09BYRH`. ADB screenshots: `adb exec-out screencap -p > out.png`.

**Conventions:** commit messages end with `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.
Only commit when asked / push when asked. The native files have at times been intentionally left
**uncommitted** (jford's call) — check `git status` before assuming.

---

## 4. Architecture quick map
- **Scoring/state:** all in `scoring-controller.html` — `G` (game state), `DB`/`teams`
  (localStorage `allstars-scorer-db`), `render()` rebuilds `#content` from a `mode` state machine,
  event delegation on `#app` via `data-act`. `IS_APP = window.AllStars?.isApp` distinguishes native.
- **Live viewing:** `broadcast()` sends state over the WebSocket **relay** (server.js). A
  `?view=viewer`/`?watch=` link hydrates via `applyRemote()`. The relay is real‑time only.
- **Cloud (Firebase, web/https only):** Auth (Google + email‑link), Firestore `teams` (owner +
  `scorers[]` + `followers[]`), team **chat** (`teams/{id}/messages`), **persistent games**
  (`games/{id}`, public‑read for watch links). All **dormant until signed in AND rules published**.
- **Native streaming:** Mevo pushes RTMP to the tablet (`RtmpReceiver`, port 1935) → decode →
  composite scorebug → push to YouTube. Reached via **Settings → Camera & stream setup**
  (`AllStars.openVideo()` bridge). Camera preview shows in the web's "monitor" region during a game.

---

## 5. Current status (done)
- Full scoring console; teams/rosters/season stats; **reusable lineups via roster**; DYB **league
  presets + rule‑alert popups**; **schedules** (games/practices); **game timer**.
- PWA (installable, forced auto‑update, version badge bottom‑right).
- **Firebase ACTIVATED** by jford (DB created, rules published, sync confirmed) — teams + scorers work.
- Built (dormant until jford re‑publishes the updated rules): **team chat** (owner/scorers/followers),
  **followers** model (`?follow=` link), **persistent cloud games** (`?watch=` link).
- Native: full‑screen web (no tabs as of v48), **Settings gear** → camera/stream, splash, blue‑turf
  background, keyboard `adjustPan`, OS‑back = go‑back‑a‑page.

## 6. Pending / next steps
- **jford action:** re‑publish `firestore.rules` (adds followers/chat/games) → activates chat +
  persistent games. (Console → Firestore → Rules → paste → Publish.)
- **Native cloud/sign‑in (BIG, recommended):** the native WebView loads bundled `file://` assets, so
  **Firebase/sign‑in/Google do NOT work on native** (auth.js bails on `file://`). Fix = point the
  native WebView at the **https Railway URL** with offline SW cache. This also makes **web changes
  auto‑reach native (no more APK rebuilds)** and makes native == web (Google login, cloud, chat).
- **Gallery + push notifications:** need Firebase **Blaze** plan (jford chose to skip for now).
- **AI coach + lineup‑from‑photo:** need a server‑side Anthropic key proxy (deferred to the end).
- **Viewer field animations** (ball + runners): not built; needs feel‑tuning.
- **Home polish:** bigger hero A + always‑visible Share button — DONE (v49). Launcher icon set to
  the A on #0B0E13 (adaptive: `drawable-nodpi/ic_launcher_foreground.png` + dark `ic_launcher_background`).
- **Single‑source convergence (jford's goal):** make ONE edit update BOTH surfaces automatically.
  Path = the https‑load switch above — then the web app (one HTML) is the source of truth, deployed
  to Railway, and the native app loads it live (offline‑cached). Native‑only Kotlin (camera/stream,
  splash, icon) changes rarely. Result: edit the shared web once → web + native both update, no APK
  rebuild. (Until then, native is updated by rebuilding the APK, which bundles the web.)
- Minor: native camera screen first‑run overlap (YouTube prompt over "waiting for camera").

## 7. Firebase project
Project **allstars-live**. Web config is in `firebase-config.js` (public apiKey, safe to commit).
Auth: Google + Email‑link enabled. Authorized domains include the Railway domain + localhost.
Rules: `firestore.rules` (republish after any change).
