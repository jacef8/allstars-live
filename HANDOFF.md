# All‑Stars Live — Project Handoff / Master Status

> **Read this first.** This is the single source of truth for picking up the project — e.g. from a
> fresh Claude Code session or a different account. The code is the truth; this explains the rest.

_Last updated: 2026‑06‑24 (app version **v64**). **Keep this file current as work continues** —
update the version, status, and pending lists with each meaningful change._

> ## ⚠️ FILE LOCATION — the code is NOT on OneDrive
> **All real project files live in `C:\Users\jford\Documents\Baseball`** (a normal local git repo).
> They are **deliberately NOT under OneDrive.** There is a second, near‑empty folder at
> `C:\Users\jford\OneDrive - Liberty County Clerk of Court\Desktop\Jace\Apps\Baseball` that only
> holds `.claude\launch.json` (a pointer to the real repo) — **do not edit code there.** Why it
> matters: OneDrive sync can silently revert, duplicate ("file (1)"), or lock files mid‑edit, which
> corrupts a single‑file app like `scoring-controller.html`. Always edit and run git from
> `Documents\Baseball`. If a tool/editor ever opens the OneDrive path, switch to `Documents\Baseball`.

> ## 🛟 RECOVERY — if `scoring-controller.html` gets gutted / app goes blank
> The whole app is one file; an accidental overwrite (an editor/agent replacing it with a stub) makes
> the app blank. **It's always recoverable from git** — the full file is every committed version:
> 1. `cd "C:\Users\jford\Documents\Baseball"`
> 2. `git restore --source=HEAD -- reference/web-scoring/scoring-controller.html` (restores last commit)
> 3. Confirm it's whole: `wc -l reference/web-scoring/scoring-controller.html` → should be **~2640 lines**
>    (a ~115‑line file is the broken stub), and it contains `var APP_VERSION="vNN"`.
> 4. If the **live site** is also broken (Railway serves whatever is on `origin/main`): check
>    `git log --oneline origin/main` for bogus "Update scoring-controller.html" commits, restore the
>    good file, bump the version, commit, and push so Railway redeploys. (This happened on 2026‑06‑24:
>    two stub commits had reached origin and blanked the live app; recovered to **v64** via an
>    `git merge -s ours origin/main` so the bad commits couldn't resurrect.)

> Recent: **v61–v64.** v64 = **recovery** (above) + version bump. v63 = home polish: "signed in as"
> moved to the bottom, team cards top‑align with New Game (dropped offsetting label), removed useless
> "0 games", bigger hub/schedule fonts, **chat‑keyboard fix** (`--avh` now tracks the *visual*
> viewport so the keyboard no longer pushes the chat box off‑screen), league‑rulebook URL is
> owner‑only. v62 = **public team directory** (owner `public`/`statsPublic` toggles in Team access →
> Discovery; `cloudSearchTeams()`; "Find a public team" search in the opponent picker; rules allow
> read when `public==true` — needs `deploy-rules.bat`), plus Refresh moved into **Settings**, a
> **Share team** button (copyable `?follow=` link), and the favorite **star now means "follow"** and
> is hidden on teams you own/score. v61 = **WebView `vh`=0 root‑cause fix** — `vh` resolves to 0 in
> this WebView (vw is fine), which had been collapsing the chat modal and in‑game event picker to
> zero size; now a JS‑maintained `--avh` (px‑per‑1vh from innerHeight/visualViewport) is used via
> `calc(var(--avh)*N)` instead of `Nvh`. Sign‑out now clears the local team cache.
> **STILL OPEN (requested, not yet built):** add/transfer/remove **co‑owners**; opponent‑picker
> **autocomplete + recent opponents**; **calendar view** of the schedule; **custom time picker**
> (native `<input type=time>` OK/Cancel buttons overflow the popup in the WebView); native
> email/text **invite autocomplete** (needs an APK rebuild). YouTube streaming works (API enabled).
>
> Recent: **v60 — two tablet fixes** (verified live via WebView CDP). (1) Tablet/wide home was
> HIDING all team cards: the card list wrapper used `max-height:46vh` but `vh` resolves to **0px**
> in this Android WebView, collapsing the wrapper to height:0 and clipping every card (phone/narrow
> layout was fine). Now uses a JS px height from `window.innerHeight`. **Rule: avoid `vh`/`vw` units
> in the native WebView — compute px from innerHeight/innerWidth.** (2) Scorer **invite** fired
> `mailto:`/`sms:` via the native VIEW intent, which opens the mail/SMS app with NO subject/body, so
> the operator had no link to send. Now it authorizes the scorer and shows the `?invite=` link in an
> in-app dialog with a **Copy link** button (clipboard works in the https WebView). Also: Firestore
> rules now deploy via **`deploy-rules.bat`** / `firebase deploy --only firestore:rules` (CLI login
> `groundlinkapp@gmail.com`; app login `jaceford08@gmail.com`; same `allstars-live` project). The
> `is list` guard was removed from the team `isScorer()/isFollower()` because it broke Firestore's
> array-contains QUERY analyzer (scorer/follower listeners were permission-denied).
> **v59 FIX — Firebase auth now survives app restart.** In the Android WebView the SDK
> silently used in-memory persistence (`firebaseLocalStorageDb` existed but was EMPTY), so the
> signed-in session was lost on every restart ("it's like I'm not getting signed in"). Fix:
> `auth.setPersistence(LOCAL)` after init and again before `signInWithCredential` (auth.js). Web-only,
> no APK rebuild. Diagnosed live via WebView CDP (`adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>`).
> v52 unified Email/phone invite + fixed delete; v53 delete-tombstones + turf launcher icon;
> v54 **team page is now a hub** (grid of sections: Roster, Schedule, Stats, Access, Rules, + a
> prominent Team chat) driven by `teamTab`. v55 **native Google sign-in** built: web
> `Continue with Google` → `AllStars.googleSignIn()` → GoogleSignIn(requestIdToken=WEB_CLIENT_ID in
> MainActivity) → `window.__googleCredential(idToken)` → firebase `signInWithCredential`.
> **Needs the app's signing SHA-1 registered in Firebase** (debug SHA-1:
> D2:D3:46:06:8E:DD:EC:BD:B0:23:AC:51:D1:1C:32:F0:BD:8B:A1:8F) or Google returns error 10. A release/
> Play build needs its own SHA-1 added too. **applicationId is now `com.libertyclerk.allstarslive.app`**
> (code namespace unchanged) to avoid an OAuth package+SHA-1 conflict that existed in another project;
> register THAT package + the SHA-1 in Firebase. Launch: `am start -n com.libertyclerk.allstarslive.app/com.libertyclerk.allstarslive.MainActivity`.

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
