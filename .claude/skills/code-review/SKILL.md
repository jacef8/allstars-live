---
name: code-review
description: Review the uncommitted changes (or a named commit range / PR) in All-Stars Live for bugs before pushing to Railway or deploying to Firebase. Checks scoring correctness, saved-game compatibility, escaping, both phone and tablet layouts, the Android build, and the sw.js cache bump. Use when asked to "review", "check my changes", "is this safe to push", or before any push/deploy.
argument-hint: "[blank for uncommitted | <commit>..<commit> | PR number]"
---

# Code review (All-Stars Live)

Adapted from ECC's /code-review for this repo. Two parts: the web scorer
(`reference/web-scoring/`, one ~7,500-line vanilla-JS file plus small modules) and the native
Android app (`app/`, Kotlin + Compose). Pushing `main` auto-deploys the web app to Railway (what
the tablets load), so a review happens BEFORE the push.

## 1. Gather

```bash
git status --short
git diff --stat            # or: git diff <range> --stat ; gh pr diff <N> --stat
```

Nothing changed -> say "Nothing to review" and stop. Read every changed file region in full
with surrounding context, not just hunks.

## 2. Web scorer checks (reference/web-scoring)

Scoring correctness - trace at least one real play through changed code by hand:
- `G` state: outs, bases, runs, `G.line`, `G.pa`, pitcher lines (`pstats`), LOB, RBI.
- `snapshot()` / `undo()` / `commit()` / `resolve()` / `creditBatter()` - does every new
  state change happen inside a snapshot so Undo still works?
- `broadcast()` / `saveGame()` / cloud publish: any new field on `G`, `hist`, or `gameFeed`
  must default when absent so OLDER saved games and cloud docs still load (`G.x || default`).
- Firestore rejects nested arrays - anything array-of-arrays on `G` must be reshaped for the
  cloud copy (see `broadcast()`'s `_fsG`) and restored in `applyRemote()`.
- Every new `data-act="..."` has a handler in the delegated click handler; every removed one has
  no leftover producer. Every new `mode` is rendered by `actionArea()` and handled by the back
  key / toolbar / picker guard lists.
- Escaping: user-entered text in `innerHTML` goes through `esc()` (html-safe.js).
- Both layouts: anything behind `isWide()` has a phone equivalent unless intentionally not.
- Viewer mode (`viewMode==="viewer"`, `?watch=`) must not be able to mutate or publish.

Mechanical checks (run them):
```bash
cd reference/web-scoring && node --test "test/**/*.test.js"
node --check server.js
# syntax of the inline <script> blocks: extract each block (no src=) to a temp .js and node --check it
```
- `sw.js`: `const CACHE = "allstars-vNNN"` must be bumped exactly once per deployable web change
  (installed PWAs and the tablet WebView otherwise keep the old shell).
- `DEPLOY.md` / `HANDOFF.md` still true after the change.

## 3. Android checks (app/)

```bash
./gradlew.bat assembleDebug -q
```
- `Broadcast` / `RtmpHub` lifecycle: the camera link starts from the Video screen or Go Live /
  Record, never at launch, and is never torn down while a camera is connected.
- Foreground service type `connectedDevice` keeps a qualifying permission in the manifest
  (`CHANGE_NETWORK_STATE`) - Android 14 crashes without it.
- `BuildConfig.DEBUG` still gates WebView debugging.
- JS bridge (`ScorerBridge`): new `@JavascriptInterface` methods must not trust string args
  (they come from the web page) for anything beyond the app's own UI state.
- No new `NetworkRouter`-style multi-network code; Hotspot mode is the one topology.

## 4. Severity and decision

| Severity | Meaning |
|---|---|
| CRITICAL | wrong score/stat reaches viewers or the season record; data loss; security |
| HIGH | a flow breaks (throws, dead button, old saved game fails to load) |
| MEDIUM | works but incomplete vs intent, missing layout, stale doc/comment |
| LOW | nits |

Any CRITICAL/HIGH -> "Do not push yet" with the fix. Otherwise "Safe to push" plus the exact
deploy steps: `git push` (Railway, tablets), `firebase deploy --only hosting` (fans), and
`firebase deploy --only firestore:rules` only if rules changed.

## 5. Report

```
CODE REVIEW - <what was reviewed> - <date>
Decision: SAFE TO PUSH | DO NOT PUSH YET

Findings (file:line - what - why it matters - fix), most severe first.
Checks: tests <pass/fail> - script syntax <ok> - gradle <ok/skipped> - sw.js bumped <yes/no/n.a.>
Deploy: <steps>
```

Plain language; one line per finding; no style nits unless asked.
