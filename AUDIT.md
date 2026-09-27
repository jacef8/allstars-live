# All-Stars Live — Full Audit

**Date:** 2026-09-27
**Scope:** native Android app (`app/`, ~6,600 lines Kotlin/C++) and the web scorer (`reference/web-scoring/`, ~9,000 lines JS/HTML) plus the relay, build, and deploy configuration.
**Method:** every file cited below was read in full; every count was measured, not estimated. Nothing here is inferred from file names or comments alone.

Severity: 🔴 must fix · 🟠 should fix · 🟡 worth fixing · 🟢 tidy-up
Effort: **S** under an hour · **M** a session · **L** multi-session

---

## Executive summary — the ten that matter most

| # | Finding | Sev | Effort |
|---|---|---|---|
| 1 | A "ONE-TIME LINEUP FIX (REMOVE AFTER CONFIRMED)" block dated 2026-07-02 still ships: real minors' names and jersey numbers in public source, polling every user's page for 32 s per load, force-pushing to Firestore if a team named "…AA…" matches. | 🔴 | S |
| 2 | The WebSocket relay has no authentication and no game scoping. Any client can send a `full` state and every viewer displays it; two games scored at once corrupt each other's viewers. | 🔴 | M |
| 3 | `WebView.setWebContentsDebuggingEnabled(true)` is unconditional — release builds allow `chrome://inspect` into the signed-in WebView. | 🔴 | S |
| 4 | A foreground service with a persistent "waiting for the Mevo" notification starts on **every launch for every user**, opening port 1935, a GL context and a hardware decoder — whether or not they will ever stream. It is `START_STICKY` and nothing in the normal flow stops it. | 🟠 | M |
| 5 | The entire SRT ingest stack is unreachable: `SrtVideoSource`, `srt_jni.cpp`, `ts_demuxer`, the vendored `libsrt.a`, the CMake/NDK toolchain requirement, and `CameraSettings` (with its Keystore migration and an alpha crypto dependency). ~700 lines plus a native library, all dead. The screen is still named `SrtIngestScreen`. | 🟠 | M |
| 6 | `RtmpReceiver.naluToAnnexB` builds every video frame through an `ArrayList<Byte>` — one boxed object per byte, ~300 K allocations per keyframe, 30×/s, on the live decode path. | 🟠 | S |
| 7 | 78 hand-rolled `.replace(/</g,…)` escapers remain in the scorer after the html-safe pass. The pass's own header explains why the partial ones are an attribute-injection hole. | 🟠 | M |
| 8 | Production Firebase hosting serves ~12 MB of unreferenced source artwork (`logo-star-master.png` alone is 7.1 MB), a separate unrelated PWA (`pitching-plan-test.html`), the `_archive/` prototypes, and — on the next deploy — last session's `redesign-preview.html`. | 🟠 | S |
| 9 | A 39 MB APK is committed to git and has been rewritten in 8 commits. The repo carries ~300 MB of binary history and grows by 39 MB per build. | 🟠 | S |
| 10 | 271 empty `catch(e){}` blocks in the scorer. Several bugs recorded in the code's own comments ("no visible symptom", "no way to tell after the fact") are exactly what silent catches produce. | 🟡 | L |

---

## 1. Camera connection

This was the area you named specifically, so it gets the most detail. The short version: the code that is actually running is the RTMP-receive path (`RtmpHub` + `RtmpReceiver` + `RtmpVideoSource`), and it is reasonable code. Around it sits an abandoned SRT architecture and a network-routing layer built for a topology the app's own setup guide now steers users away from.

### C1 · The SRT stack is dead code that still ships — 🟠 M

`SrtIngestScreen.kt:79-82` hardcodes `RtmpVideoSource`. Nothing else instantiates `SrtVideoSource`. Consequently unreachable:

- `ingest/SrtVideoSource.kt` (241 lines) — including `joinCameraWifi`, already marked `@Suppress("unused")`
- `cpp/srt_jni.cpp` (202) and `cpp/ts_demuxer.cpp/.h` (169)
- `cpp/third_party/srt/libs/arm64-v8a/libsrt.a`, linked in via `-DUSE_LIBSRT=ON` (`app/build.gradle.kts:25`) and packaged into every APK — never loaded, because `System.loadLibrary("srtjni")` lives in `SrtVideoSource`'s companion `init`, and that class is never initialised
- `ingest/CameraSettings.kt` (78) — the encrypted-prefs holder for the SRT URL and Mevo Wi-Fi passphrase, plus its legacy-migration code and the `androidx.security:security-crypto:1.1.0-alpha06` dependency it exists for. Zero references.
- The `externalNativeBuild` / `ndkVersion` / `cmake` pins in `build.gradle.kts` — meaning **every developer must install NDK 25 and CMake 3.22 to build an app whose native code never runs**, and `abiFilters += "arm64-v8a"` (justified in a comment as "for the spike") still restricts the APK to 64-bit ARM.

**Fix:** delete the six files and `third_party/srt`; remove `externalNativeBuild`, `ndkVersion`, `abiFilters`, and the security-crypto dependency from Gradle; rename `SrtIngestScreen` → `CameraScreen`. Then delete the SRT-era doc comments in `VideoSource.kt`, `MediaCodecVideoDecoder.kt` ("the real M1 decode-to-surface harness for the libsrt route") and `AndroidManifest.xml` ("Hold up the Mevo's Wi-Fi and bind SRT to it").

### C2 · Two contradictory network strategies, one of which the app no longer recommends — 🟠 M

`NetworkRouter.kt:27-29`: *"We deliberately do NOT bindProcessToNetwork()."*
`SrtVideoSource.kt:185`: `cm.bindProcessToNetwork(network)` — binds the whole process to the camera's internet-less Wi-Fi.

Both were live at once until SRT was retired; today only `NetworkRouter` runs. But `NetworkRouter.bindProcessToCellular()` (`:126`) is itself a process-wide side effect: for the RTMP handshake window it rebinds *every* new socket in the process — including the WebView's Firestore and relay connections — to cellular. The comment acknowledges the blast radius and argues the window is short. It is, but it is a global hack for a local need.

More importantly, `SrtIngestScreen.kt:121-139` and the in-app Setup Guide (`:994`) now say the direct-Wi-Fi topology is *"Fallback only… YouTube sign-in and live scoring may not work in this mode"* and Hotspot mode is *"the only truly repeatable architecture."* In Hotspot mode the tablet's default route never changes and **none of `NetworkRouter` is needed**. So ~140 lines of the most delicate code in the app, plus the `bindProcessToCellular` calls in `YouTubeStreamer` and `SettingsScreen`, exist to prop up the path you tell users not to use.

**Decision needed, not just a fix:** either (a) commit to Hotspot mode, delete `NetworkRouter`, and turn the fallback into a plain "connect to cellular first" instruction; or (b) keep both and accept the complexity. I'd pick (a) — the app's own copy already made that call.

### C3 · The camera pipeline starts at every launch, for everyone — 🟠 M

`MainActivity.kt:110-112`: if `captureMode == MODE_EXTERNAL` (the default), `RtmpReceiverService.start(this, 1935)` runs in `onCreate`. That means every person who opens the app — a parent checking a score, a coach editing a roster — gets:

- a persistent notification reading "Camera link ready — waiting for the Mevo" (`RtmpReceiverService.kt:284`)
- an open TCP listener on :1935
- an EGL context, a GL thread, and a hardware H.264 decoder instantiated and idling (`RtmpHub.start`)
- a `START_STICKY` service that the OS will resurrect and that nothing stops unless the user opens Camera Setup and taps Restart

`Broadcast.goLive` and `startRecording` both already call `RtmpHub.ensureStarted(context)` — the pipeline can come up lazily when it is actually wanted. The eager start exists so Go Live works "from any tab," but `ensureStarted` already guarantees that.

**Fix:** remove the launch-time start; start on Go Live / Record / opening the Video screen; stop the service when the broadcast ends and the Video screen closes. Also replace `android.R.drawable.ic_menu_camera` (`:285`) — system drawables render as a white square on many OEM builds — with an app mipmap, and drop "Mevo" from the notification text (the app supports GoPro/DJI/phone).

### C4 · `naluToAnnexB` boxes every byte of every frame — 🟠 S

`RtmpReceiver.kt:264-275`:
```kotlin
val out = ArrayList<Byte>(d.size + 16)
…
for (i in 0 until len) out.add(d[p + i])
…
return out.toByteArray()
```
Each `add` boxes a `Byte` object. A 300 KB keyframe becomes ~300,000 heap objects, then a second copy on `toByteArray()`, 30 times a second, on the thread feeding `MediaCodec`. This is the single most expensive line in the native app and it is trivially fixable: walk the NALUs once to compute the output length, allocate one `ByteArray`, `System.arraycopy` each NALU behind a 4-byte start code.

### C5 · `usingHotspot` is a toggle that changes nothing — 🟡 S

`SrtIngestScreen.kt:139`, persisted as `using_hotspot`, passed to two sheets — and every use is in a string literal or a placeholder. No behaviour reads it. A user flips "Use my Mobile Hotspot (recommended)" ON and reasonably believes a mode changed. Either wire it to something (e.g. suppress the no-internet banner, or gate `NetworkRouter` off) or present it as what it is: a choice that selects which instructions to show.

### C6 · The RTMP publish URL is smuggled through a status string — 🟡 S

`RtmpHub.start` writes the URL into `stats.message`; `CameraStatus` (`SrtIngestScreen.kt:391`) parses it back out with `message.substringAfter("rtmp://")`. `RtmpHub.currentPublishUrl()` already exists and is what the setup sheet uses. One source of truth.

### C7 · Robustness gaps in the live path — 🟡 M

- `MediaCodecVideoDecoder.onError` (`:68`) only logs. If the codec dies — most commonly when a camera switches resolution mid-stream, which GoPro and phone apps do — nothing restarts it; the feed is dead until the user restarts the link. Recreate the decoder on a non-transient error, or on `onOutputFormatChanged` with new dimensions.
- `RtmpHub._stats.value = _stats.value.copy(…)` is a non-atomic read-modify-write on a `StateFlow` from four threads (decoder callback, RTMP thread, camera thread, watchdog). Use `_stats.update { }`.
- `RtmpHub.watchdog` loops on `while (receiver != null)` — a non-volatile field read cross-thread — and is only started by `start()`, so **device-camera mode has no stall detection at all**.
- `RtmpReceiver.readMessage` (`:165`) only reads the extended timestamp when this chunk's own header signalled it. Per the RTMP spec, fmt-3 continuation chunks of a message that used an extended timestamp also carry the 4-byte field; the code reads those bytes as payload. Practically only triggers past ~4.6 h of stream time — a doubleheader, not a game.
- `DeviceCamera` ignores `SENSOR_ORIENTATION`. Tablets are landscape-native so this works today; the all-in-one mode on a phone will produce a rotated feed. It also uses the deprecated `createCaptureSession(List<Surface>, …)`; `SessionConfiguration` is available at your `minSdk 26`+2.

### C8 · Instructions contradict the recommended mode — 🟡 S

The Mevo profile's step 1 (`SrtIngestScreen.kt:1111`) reads *"Connect this tablet to the Mevo's Wi-Fi"* — the fallback path. The same sheet, three sections down, recommends Hotspot mode where the camera joins the tablet. A first-time user following the numbered steps will do the thing the app tells them not to.

### C9 · The `VideoSource` abstraction no longer abstracts — 🟢 S

`VideoSource.kt` promises the UI can swap transports "at exactly one factory call." In practice `SrtIngestScreen` calls `source.shutdown()` (not on the interface) and reaches into `RtmpHub` directly nine times (`attachPreview`, `startDeviceCamera`, `captureMode`, `lensBack`, `publishHint`, `currentPublishUrl`, `port`, `stop`, `MODE_*`). The interface is honoured only by `StubVideoSource`. Either make `RtmpHub` the explicit dependency and delete the interface, or actually route through it.

---

## 2. Function — correctness bugs

### F1 · A one-off roster migration is still in production three months later — 🔴 S

`scoring-controller.html:7437-7475`. Labelled *"ONE-TIME LINEUP + NUMBER FIX (REMOVE AFTER CONFIRMED)"*, dated 2026-07-02, with the instruction *"Delete this block once confirmed on-device."* It is 2026-09-27. On every page load, for every user, it:

1. starts an 800 ms `setInterval` that runs up to 40 times (32 s),
2. searches `DB.teams` for any team whose name matches `/\bAA\b/` — not *your* team, any team with "AA" in its name,
3. if found, rewrites jersey numbers and player order and calls `cloudSaveTeam(t, true)` (a force-push).

It is idempotent by lineup id, so it will not re-apply to your team. But it is a privacy exposure — six children's names, numbers, and a coach's first name in JavaScript served to the public — and a correctness landmine for any other league using the app. Delete the block.

### F2 · The relay is a single unauthenticated global room — 🔴 M

`server.js:123-133`: every message from any client becomes `lastState` and is rebroadcast to every client. No origin check, no token, no room. `applyRemote` (`scoring-controller.html:1853-1891`) applies any `type:"full"` message a viewer receives with no check of which game it belongs to, and a viewer's `hello` makes **every** connected scorer resend.

Consequences: two teams scoring simultaneously — which is the normal case for any league with more than one field — send their viewers each other's plays. Anyone who reads the source (it is public) can push a fabricated final score to every open viewer and it is persisted as `lastState` (and to Firebase RTDB if configured).

The Firestore path (`?watch=<gameId>`, `cloudSubscribeGame`) is correctly scoped and rules-protected, and `DEPLOY.md` already says Firestore sync "covers cross-device/cross-network sync on its own." **Recommendation:** remove the relay from the live-sync path entirely. If it must stay, key messages by `G.cloudId`, have the server maintain per-game rooms, and require the Firebase ID token on `hello`.

### F3 · Silent-failure culture: 271 empty catch blocks — 🟡 L

`catch(e){}` appears 271 times in the scorer. The code comments document at least four incidents whose defining symptom was "no visible error anywhere" — a game that never synced (`cloud-data.js:167-176`), a publish that was never attempted (`scoring-controller.html:2166-2170`), an auth drop mid-game (`auth.js:70-73`). Each of those was eventually fixed by *adding* a `netLog` line. The pattern will keep producing that class of bug. A single `swallow(e, where)` helper that logs to `netLog` in the field and no-ops nowhere else would convert every one of these into a diagnostic for free.

### F4 · Audio PTS drifts in mute/silence mode — 🟡 S

`YouTubeStreamer.startAudio` (`:280-285`): silence chunks of 1,024 samples (23.2 ms at 44.1 kHz) are produced every `Thread.sleep(20)`, ~16 % faster than real time, and `samplesWritten` advances whether or not `feedAudio` actually queued the chunk (it silently drops on a full encoder). Over a game in mute mode the audio timeline runs ahead and gaps. Sleep for `chunkSamples * 1000 / sampleRate` and only advance on a successful queue.

### F5 · `lastCamNs` is dead state with a misleading comment — 🟢 S

`VideoCompositor.kt:52` is written on every frame and never read. The keep-alive it describes works anyway (`renderEncoder` always redraws the last texture), but the comment describes a mechanism that does not exist.

---

## 3. Security and privacy

### S1 · WebView debugging enabled in release — 🔴 S

`GameScorerScreen.kt:66`: `WebView.setWebContentsDebuggingEnabled(true)` with no `BuildConfig.DEBUG` guard. Anyone with USB access to a signed-in tablet can open `chrome://inspect`, read Firestore auth state, and execute JavaScript as the user. Gate it.

### S2 · Minors' PII in shipped source — 🔴 S

See F1.

### S3 · Unauthenticated relay — 🔴 M

See F2.

### S4 · 78 partial HTML escapers remain — 🟠 M

The html-safe pass (`html-safe.js`) explains the hole precisely: an escaper that handles only `<` leaves `"` unescaped, and *"a `"` in a team/player name that isn't escaped breaks out of the attribute and can inject a new one."* 78 inline `.replace(/</g,…)` sites remain in `scoring-controller.html`. The fix is mechanical: replace each with `escHtml(…)`.

### S5 · Attack surface left over from the file:// era — 🟡 S

- `settings.allowFileAccess = true` (`GameScorerScreen.kt:72`) — the app loads https; file access is no longer used.
- `android:usesCleartextTraffic="true"` (manifest) — the app, YouTube, Firestore and the relay are all TLS. RTMP is inbound, not HTTP.
- `settings.databaseEnabled = true` — WebSQL was removed from Chromium in 2024; the flag is inert.
- `android:allowBackup="true"` — backs up `SharedPreferences` (YouTube channel name, camera prefs) and WebView storage to the user's Google account. Probably fine; worth a conscious decision.

---

## 4. Efficiency

### E1 · Per-byte boxing on the video path — 🟠 S

See C4.

### E2 · Scorebug: full-frame Canvas draw and 3.7 MB GPU upload per web render — 🟡 S

`ScorerBridge.setScore` (`GameScorerScreen.kt:227-237`) is called from the web's `render()` on every call while in-game (`scoring-controller.html:5984`). Each call paints a 1280×720 bitmap and `GLUtils.texImage2D`s it. The bitmap-pool fix already addressed allocation; the paint and upload still happen even when none of the nine inputs changed — e.g. a render triggered by opening a picker. Hash the inputs (plus logo identity) and skip when equal.

### E3 · Per-pitch write amplification in the scorer — 🟡 M

`broadcast()` (`scoring-controller.html:2159`) runs on every state change and triggers:

1. `saveGame()` — synchronous `localStorage.setItem` of `{G, OUR, OPP, …}`
2. `LINK.send(_full)` — which itself does a **second** synchronous `localStorage.setItem` of the same full snapshot (`:1841`, the cross-tab fallback), a `BroadcastChannel.postMessage`, and a WebSocket send
3. `cloudPublishGame` — Firestore write, debounced 1 s

The snapshot includes `gameFeed` (the entire play log) and `G.anim.paths`, so cost grows through the game. Two synchronous main-thread JSON serialisations per pitch of an ever-growing object is the classic jank source in this style of app. The `localStorage` channel exists as a fallback for browsers without `BroadcastChannel`, which is none of the browsers this app targets (WebView 54+, all evergreen). Drop it. Consider sending `gameFeed` as a delta rather than the whole log.

### E4 · Full-DOM rerender on every interaction — 🟡 L (architectural)

`render()` (293 lines) → `paintContent` → `innerHTML` replaces the screen. `actionArea()` is a **1,835-line** function rendering 30 modes inline. There are 332 `render()` call sites and 1,828 inline `style=""` attributes in the templates. `fitPage()` then runs twice (immediately and after a 120 ms timeout), each forcing layout, using CSS `zoom`. The app already carries workarounds for this architecture (`uiBusy()`, `cloudRenderSoon`, scroll-position memory) because a snapshot mid-tap rebuilds the DOM and closes a native `<select>`. This is not a one-session fix; it is the direction the comp work last session was heading (tokens, classes, no inline styles). Noting it so it is on the list, not because it should be next.

### E5 · Every launch pays for a pipeline most launches never use — 🟠 M

See C3.

### E6 · APK weight — 🟡 S

`isMinifyEnabled = false` (`build.gradle.kts:32`): no R8 on release. The 39 MB APK ships the full `material-icons-extended` set (three icons are used), all of RootEncoder, play-services-auth, Firebase Messaging, ZXing, an alpha security-crypto library used only by dead code, and a native `.so` that is never loaded. Enabling R8 with a minimal keep list for RootEncoder is the single largest size win available; deleting the dead native build is the second.

### E7 · Minor hot-loop notes — 🟢

- `VideoCompositor.encoderTick` runs at 200 ms while off-air (5 wake-ups/s on the GL thread). The comment says "costs nothing"; it is close to nothing. Cancel the tick when no encoder is attached and repost on attach.
- `alignPreview` polls `getBoundingClientRect` every 400 ms (`:2953`) — correctly guarded so it only crosses the bridge on change. A `ResizeObserver` on `#monitor` would remove the poll.
- `cloudRefreshFollowed` (`cloud-data.js:424`) does a Firestore `get()` per followed team every 5 minutes for the life of the page. Fine at small scale; a single `in` query would make it one read.

---

## 5. Redundancies and dead code

### Dead (zero references)

| What | Lines | Notes |
|---|---|---|
| `SrtVideoSource.kt`, `srt_jni.cpp`, `ts_demuxer.cpp/.h`, `libsrt.a`, CMake/NDK config | ~620 + binary | See C1 |
| `CameraSettings.kt` + `security-crypto` dependency | 78 | See C1 |
| `wifi/LocalApManager.kt` | 196 | Its header calls itself "the core of the tablet-broadcast pivot." That pivot became the Hotspot-mode *instructions*; the local-only-AP implementation was never wired up. |
| `VideoCompositor.lastCamNs` | 1 field | See F5 |
| `SrtVideoSource.wifiSsid/wifiPassphrase` | 2 fields | Never read even inside their own (dead) class |

### Misnamed or vestigial

- `SettingsScreen.kt` contains no `SettingsScreen` — only `YouTubeAccountSection`. The comment says the Settings tab is gone. Rename the file.
- `SrtIngestScreen.kt` does RTMP. Rename.
- `VideoSource` interface — see C9.
- `StubVideoSource` + `CompositorTestScreen` (501 lines) — the M2 development harness, reachable in production via "Use test pattern instead." It has its own Record button writing to `getExternalFilesDir/recordings` (a *third* MP4 writer, `Mp4Recorder`, separate from `LocalRecorder`) and its own Go Live with a manual stream-key field. Move behind `BuildConfig.DEBUG` or delete.

### Duplicated logic

- **AAC mic capture** — `YouTubeStreamer.startAudio/feedAudio/drainAudio` (`:246-321`) and `LocalRecorder.startAudio/feedAudio/drainAudio` (`:167-237`) are the same ~80 lines with *different* PTS arithmetic (sample-counted vs fixed increment). One `AacMicSource` class.
- **H.264 encoder setup** — the same `createVideoFormat` block appears in `YouTubeStreamer`, `LocalRecorder`, and `Mp4Recorder`.
- **Two MP4 recorders** — `LocalRecorder` (MediaStore, with audio) and `Mp4Recorder` (raw path, video only). Keep one.
- **Colours** — `ui/theme/Theme.kt` defines a palette; **0** composables read `MaterialTheme.colorScheme`; **155** hardcoded `Color(0x…)` literals across the native UI. The theme is applied and then ignored.
- **Hand-built controls** — a toggle switch is built from two `Box`es twice (`SrtIngestScreen.kt:683, 756`); a selectable-pill row is built three times (`:594, :618, :653`). Material3 ships `Switch` and `FilterChip`.
- **Prefs** — `setup_mode` and `capture_mode` are both persisted for the same concept (`:174`). `cameraProfile`, `lensBack`, `usingOpal`, `opalWanIp`, `usingHotspot`, `yt_channel` live in `"allstars"` prefs; the dead `CameraSettings` used `"camera_secure"`.
- **Deploy config** — two `railway.json` (root: 100 s healthcheck, 10 retries; `web-scoring/`: 300 s, 3 retries) and two `package.json` for one server. Whichever Railway reads, the other is wrong.
- **Firebase config** — identical values in `firebase-config.js` and `Push.kt:31-34`.
- **Rulebook** — `reference/dyb-rules-2026.txt` and `reference/web-scoring/rules-dyb-2026.txt` are byte-identical (256 KB each). The app references only the latter.
- **Cross-tab sync** — `BroadcastChannel` *and* a `localStorage` storage-event channel carry every broadcast (`:1837-1841`). The second is a fallback for browsers that do not exist in your fleet.
- **Live sync** — the WebSocket relay and Firestore both carry the game state. Firestore is scoped, authenticated, persistent, and already documented as sufficient. See F2.

---

## 6. UI (native)

- `SrtIngestScreen.kt` is 1,153 lines in one file: the screen, a 370-line setup sheet, a 110-line guide sheet, and eight helper composables. Split by sheet.
- Every string the operator reads that names a camera says "Mevo" — the notification, `CameraStatus`, the Setup Guide's steps 2–3 ("Connect the Opal…"). The setup sheet supports five camera profiles. Use the selected profile's name.
- The Setup Guide (`:909-1017`) is excellent content — a real checklist with live status rows — and is reachable only by long-press → gear → "📋 Setup guide". It is the thing a first-time operator most needs and the hardest thing to find.
- `YouTubeAccountSection` is embedded at the bottom of *Camera* setup (`:885`). Connecting YouTube is a prerequisite for streaming, not a camera setting; a first-run user will not look for it there. The `YouTubeSetupPrompt` on the empty state helps, but it opens the same sheet.

## 7. Layout (native)

- The Video overlay (`MainActivity.kt:210-219`) draws a "‹ Done" button at top-left over whatever the child screen puts there; `SrtIngestScreen` had to move its own controls to top-right to avoid it (`:234`). The overlay should own its chrome or the screen should — not both.
- Sheets (`CameraSetupSheet`, `SetupGuideSheet`, `ConfirmDialog`) each hand-build the same scrim + centred card + tap-to-dismiss + `pointerInput` tap-swallow pattern. One `Sheet` composable.

Web layout was audited in depth last session (the comp in `redesign-preview.html` and its commit history). The findings there still stand: the count renders as 13 px dots, lists get one visible row, per-pitch controls are a full-width bottom bar rather than a thumb rail.

## 8. Workflow

- **W1** — First launch shows a camera notification before the user has done anything. See C3.
- **W2** — The numbered camera steps and the recommended mode disagree. See C8.
- **W3** — "Use test pattern instead" is offered to end users on the empty camera state. It is a developer harness. See §5.
- **W4** — Pipeline lifecycle has four start paths (`MainActivity.onCreate`, `RtmpVideoSource.start`, `RtmpHub.ensureStarted`, `RtmpReceiverService.onStartCommand`) and two stop paths that `restart()` calls back-to-back (`source.shutdown()` then `RtmpHub.stop()`). All idempotent, so it works — but nobody owns it. `RtmpHub` should be the only starter and stopper; everything else asks it.
- **W5** — `usingHotspot` presents as a mode and is a label. See C5.
- **W6** — The "Sign in to YouTube BEFORE connecting to the camera" guidance appears in three error strings (`SettingsScreen.kt:79, :121`, guide `:1012`) for the fallback topology. In Hotspot mode it is moot; the copy should branch on the toggle, or the toggle should go.

## 9. Build, deploy and repository health

- **B1** 🟠 — `reference/web-scoring/AllStarsLive.apk` (39 MB) is tracked in git via a `.gitignore` exception and has 8 commits. Every rebuild adds 39 MB of permanent history. Serve it from Railway's filesystem, a GitHub Release, or Firebase Storage; remove it from the repo with `git filter-repo` if you want the history back.
- **B2** 🟠 — Firebase hosting `public` is the whole directory. The `ignore` list excludes only server files. Deployed today: `icons/logo-star-master.png` (7.1 MB), `icons/icon-master.png` (2.3 MB), `icons/logo.png` (2.2 MB), `icons/logo-512.png` (0.5 MB) — none referenced by the app, manifest, or service worker; `pitching-plan-test.html` (124 KB, a separate "Liberty Pitching" PWA); `_archive/` (136 KB of retired prototypes); and, after the next deploy, `redesign-preview.html`. Move source artwork and unrelated apps out of the hosting root, or add them to `firebase.json` → `hosting.ignore`.
- **B3** 🟡 — `compileSdk`/`targetSdk` 34, Compose BOM 2023.10.01, Kotlin 1.9.10. Google Play has required `targetSdk` 35 for updates since August 2025. You sideload, so it is not blocking — but it will be the day you want Play distribution, and each year of drift makes the jump harder.
- **B4** 🟡 — `isMinifyEnabled = false`. See E6.
- **B5** 🟢 — `proguard-rules.pro` is empty; when R8 is enabled RootEncoder and the Firebase SDK will need keep rules.
- **B6** 🟢 — `GameScorerScreen.kt:47-48` header still says the web scorer is "bundled into the APK assets at build time." It has loaded the live URL since `635e4b6`.

---

## Suggested order of work

Ranked by (impact ÷ effort), each line independent of the others unless noted.

1. **Delete the roster block** (F1) — five minutes, closes a privacy exposure.
2. **Gate WebView debugging** (S1) — one line.
3. **Fix `naluToAnnexB`** (C4) — twenty lines, measurable on device.
4. **Clean the hosting root** (B2) and **get the APK out of git** (B1).
5. **Delete the SRT stack and its toolchain** (C1) — one commit; the build gets simpler for everyone who touches it.
6. **Stop the eager foreground service** (C3) — the biggest UX improvement for non-streaming users.
7. **Decide on the relay** (F2) — this one needs a decision before code.
8. **Finish the escaper sweep** (S4) — mechanical, 78 sites.
9. **Decide Hotspot-only vs dual-network** (C2); the code shrinks a lot on (a).
10. Dedupe the encoder/audio classes, retire the test harness, rename the misnamed files (§5).
11. R8 + dependency bump (E6, B3).
12. The web render architecture (E4) — last, largest, and already in motion via the comp.
