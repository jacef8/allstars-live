# All-Stars Live — Backlog (captured 2026-06-27)

Status: [ ] todo · [~] in progress · [x] done. Grouped by theme; recurring/meta items first.

## A. Recurring layout/UX rules (these have been reported more than once — fix durably)
- [~] A1. Top toolbar one row (v194): container is now flex-wrap:nowrap (+ horizontal-scroll safety),
      and the tablet game-controls (Lineup/Inning log/Settings/Home/End) are ICON-ONLY 44px buttons so
      they fit one row instead of wrapping. Verified one row at 1280 (47px) and 380 (44px), no overflow.
      Undo stays labeled. (Tablet/PC windows-shouldnt-scroll part of A1 still open per-window.)
- [x] A2. Windows must not snap-scroll back to top every time I enter an input (v189: scroll positions
      remembered + restored after each render).
- [x] A3. Editing a number: tapping the number box should select it so I can type a new value WITHOUT
      deleting the old one first (v189: global select-on-focus).
- [x] A4. Background is too dark — brighten the page background a little (v189: lightened turf scrim).
- [~] A5. Double-animation: the play animation uses SVG SMIL (begin="0s"), which RESTARTS whenever
      render() re-inserts it. flash() fired a full render() at 1900ms to clear its toast — restarting the
      animation for any play whose hold runs >1.9s (HR/DP/run-scored). Fixed v200: flash now removes
      just its toast element, no render. Verified no extra render fires. If plain singles/outs still
      double on-device, a different trigger remains — needs repro of the exact play.
- [x] A6. Window borders: consistent thick white, no stray red/thin (done v185–v188 sweep).

## B. Scoreboard + game feed
- [~] B1. Scoreboard redesign (v207): statusBar rebuilt as a bigger, GRID scoreboard — team rows with a
      6px team-colour band + tint/dot for whoever is batting, and labelled cells (INNING / COUNT / OUTS
      / ON BASE / TIME). All animation hooks preserved. NOTE: this is the compact scorebug (float window
      + phone); the wide inline scoreBar may need the same treatment depending on what jford sees — awaiting his eyes/iteration.
- [~] B2. Game feed: out-making plays now show "· N OUT(S)" (v195). Runs-scored on a play still TODO —
      folded into B5 (RBI detail) since both need run-attribution.
- [~] B3. "Score doesnt need to be on every feed update" DONE (v198): per-pitch feed lines no longer
      show the running score (only play lines do). The "remove pitch-count increment from wild pitch/
      passed ball" part is NOT done — WP/PB is genuinely a pitch (ball/strike) so counting it is correct,
      and the sheet flow already records it once; need to clarify the exact double-count scenario.
- [x] B4. Strikeout looking vs swinging (v208): after a K, a small skippable prompt ("STRIKEOUT —
      Looking / Swinging / x") appears; tapping tags the feed line ("STRIKEOUT — Caden Schaefer · looking")
      and broadcasts to viewers. Any next pitch dismisses it. Verified set/tag/clear.
- [x] B5. RBI feed detail (v197): hit feed lines now name the scorers, e.g. "DOUBLE — Caden Schaefer;
      Jones scores from 3rd, Smith scores from 2nd · 2 RBI" (built from the play movers). Covers B2 runs.
- [x] B6. Caught-stealing HOW (v196): the base-running sheet already had separate actions; relabeled to
      "Caught steal" (catcher threw out) vs "Picked off" so the two methods are clear in the feed.
- [x] B7. End-of-inning notification in the feed (v193): doFlip adds a centered divider line
      "End of Top 3 · 3 outs · TEAM 2 runs, 3 hits" (reason = 3 outs vs run cap). Verified.
- [x] B8. Update the batter display after his at-bat completes — satisfied by architecture: batterCard()/
      batterStrip() are fully derived from live G (batter()/battingSlot()/batterLineParts()) and render()
      runs after every commit()/nextBatter(), so the card shows the next batter the instant an AB completes.
- [x] B12. EDIT A COMPLETED AT-BAT (v236): every play line in the game feed has an EDIT button (scorer only,
      live game). Opens a sheet to (a) "change the call" — reclassify the result, adjusting that batter's
      box-score line AB/H/BB/K (kindStat deltas), (b) RBI ± stepper, (c) "Rewind to this play" which uses
      the snapshot stack (hist) to roll the whole game back to just before the play for any change that
      touches runners/score/outs. bcastEvent now stamps each play's batter key (bk) so the edit knows whom
      to credit. Feed also got an S/M/L density toggle (al-feed-size) — practical "resize" that doesn't
      fight the flex layout.
- [x] B13. EDIT A FINISHED GAME (v236): the finished-game detail (teamTab=gameview) has an "Edit game"
      button (owner/manager). Editable: final score (us/them), opponent name, date, and the per-batter
      box score (AB/R/H/RBI/BB/K, both sides) as inputs. Save recomputes g.totals + W-L-T result, then
      teamRec() + recomputeSeasonFromGames() and cloudSaveTeam(force) so the record + season + followers sync.
- [x] B9. Pitch controls (v209): Ball/Strike/Foul are now white BASEBALLS — red stitching + the B/S/F
      letter (strike S in red) — so the call reads at a glance instead of a red icon lost on a dark circle.
      In Play stays the lime primary button.
- [x] B10/B11. (v191) Followers can now WATCH a live game from inside the app. Home subscribes to live
      docs for ALL my teams (not just scorable); a follower sees the in-progress row with "Watch live"
      (scorers still get "Take over"). Tapping it streams the game + FEED from the cloud live doc in
      read-only viewer mode — so the feed is no longer empty and there's a real way into the live game.
      (Same-device scorer→viewer feed already worked; the gap was the remote/follower path.)

## C. Lineup / roster / positions
- [x] C1. In-game lineup edits IN THE ROW now (v204): tapping a player turns his row into number /
      First / Last fields (same boxes as the team roster, via ensureName split) with a Done button —
      no second window. mode stays 'lineup'. Verified inline render + edit + name split.
- [x] C2. Field-map position editor (v202): the lineup page has a "Batting order / Field map" toggle.
      Field map = a diamond with all 9 spots; tap a position, tap a player, and assignPos does a clean
      2-player swap (displaced player takes the picked players old spot) — all visible at once, no
      cascade. Verified the swap touches only the two involved. (Drag-drop could come later; tap works.)
- [x] C3. Skip a batter (v205): the correction sheet (Edit pencil) has a BATTER stepper — skip to the
      next spot in the order or go back, resetting the count/at-bat. Verified +1/-1 move the order spot.
- [x] C4. Current batter highlighted on the in-game lineup edit (v199): our up-batter slot gets a lime
      border + tint + an "AT BAT" badge, so the row most likely to need editing is easy to find.
- [x] C5. Record lineup changes throughout the game (v211): logLineup() stamps every in-game position/
      batting-order change with inning+half onto G.lineupLog (no-ops during pre-game setup); stored on the
      finished game via liveGameBox; shown as a "LINEUP CHANGES" section in the Inning log modal.
- [x] C6. Edit opponent roster from setup (v206): the New Game setup screen now has an "Edit <OPP>
      roster" button (both layouts) that opens the opponent roster/lineup editor and returns to setup on
      Back (luFromSetup flag). So you can enter their numbers/names before starting. Verified round-trip.

## D. Schedule / teams / setup
- [x] D1. Share a game link from the schedule (v209): each scheduled GAME row has a Share button that
      opens the share sheet with a watch link for that team's live game (?watch=<teamId>-live). Opens for
      a no-login viewer; shows a waiting screen until you start, then goes live. Verified the URL is scoped.
- [x] D2. Scheduled opponent now selectable in the opponent picker (v196). schedadd already saved it to
      local recent-opps; now opening the opponent picker ALSO seeds quick-picks from the team schedule
      (which syncs), so a scheduled opponent shows even on a different device. Verified.
- [ ] D3. Autopopulate the YouTube video name as "My Team vs Opponent."
- [x] D4. BUG: after End Game → stats/record/schedule didn't update (v190). Root cause: finished game
      stamped with UTC date (evening scoring → tomorrow) so it never matched the local scheduled date.
      Fix: local date (todayLocal); plus games started from the schedule now carry the schedule's id+date
      so they're marked played for sure, even with timezone/opponent-name mismatches. Save path itself
      (games log, derived W-L-T record, season batting rollup) verified end-to-end.

## E. Pitch count
- [x] E1. Already supported: recordPitch->countPitch increments the opponent pitcher (oppP) on every
      pitch while we bat; field pitch-chip + box score show it; the correction editor pitch row targets
      the opponent pitcher ("OPP pitcher") when batting. Verify on device.


## F. Streaming / connectivity
- [~] F1. IP changed → Mevo couldn't reach the app. Root cause: the RTMP address shown to the camera
      was captured ONCE at receiver start, so a changed Wi-Fi/AP IP left it stale. Fix (native, installed
      to tablet 2026-06-27): RtmpHub.currentPublishUrl() re-detects the IP; Camera setup re-polls it every
      2s so the address is always current, and a new "✓ receiving video (fps) / ○ waiting" status line
      shows whether the camera actually connected. App launches clean; on-device confirm with the Mevo owed.
- [~] F2. YouTube "sign in failed / unable to connect": ROOT CAUSE = dual-network. At the field the
      tablet is cellular-only; the Mevo serves its own internet-less Wi-Fi the tablet must join to
      receive the camera → no internet for YouTube (and a catch-22: Wi-Fi for camera vs internet for
      YouTube). Fix (native, built, NOT yet installed — USB dropped): NetworkRouter keeps cellular warm
      + pins YouTube API calls to cellular when the active net has no internet; new no-internet warning
      banner in the stream overlay. User also enabled "Mobile data always active" (system fallback).
      Google sign-in runs in Play Services (separate process) so it relies on the system fallback, not
      app binding. INSTALLED + on-device verified 2026-06-27: NetworkRouter starts, requestNetwork
      (cellular) + registerDefaultNetworkCallback fire, "cellular available" logged, no crash; warning
      correctly hidden on validated cellular. FIELD test owed: on the Mevo Wi-Fi confirm the warning
      shows + Go Live succeeds over cellular. NOTE: tablet USB dropped repeatedly today (check cable).

## H2. Watch-with-scorebug — paste any live link (NEW 2026-06-28)
- [x] H2. jford's idea: instead of streaming video THROUGH our app, a viewer pastes a link to someone
      else's YouTube-live of the game and our app overlays the live scorebug on top (no second stream).
      Built v237: viewer monitor (monitorPane) shows a "Paste live video link" button; `watchVidModal()`
      takes a YT link (parseYouTubeId) → `viewerVid` (localStorage al-watch-yt). A viewer-embed branch at
      the TOP of monitorPane (before the IS_APP native branch, so it works on the native app too) renders
      the youtube-nocookie iframe + statusBar() bug overlay. SYNC: `bugDelay` (al-bug-delay, ±1/±5s, 0–60)
      lags the bug/feed to match the stream's ~10–30s latency — applied as a setTimeout on the live-doc
      apply (watchlive action) AND on the shared-link applyRemote path; `_watchGen` guard drops stragglers
      after leaving. Answer to "streamed twice?": NO — overlay is client-side per viewer; the burn-in path
      (server re-encode) was the only double-stream option and we did NOT take it. OBS source overlay
      (?overlay=1, H1) remains the "everyone sees it at the source" path.

## H. Remote scorebug overlay (NEW 2026-06-27)
- [x] H1. Remote scorebug overlay (v210): ?overlay=1 on a watch link renders ONLY a clean broadcast
      scorebug (team colours, score, inning, count, outs, bases) on a TRANSPARENT page — drop it into an
      OBS/Streamlabs/vMix browser source to burn the live score into a stream from another device. Fed by
      the ?watch= subscription (no login). "Copy OBS overlay link" button added to the game-share sheet.
      Verified: transparent bg, bug only, empty until live.

## F2b. End Game must end the YouTube stream (NEW 2026-06-27)
- [x] Root cause: Broadcast.stop() only stopped the local RTMP push + set OFFLINE; it never told YouTube
      to END the broadcast (transition to "complete"), so the stream stayed "live" (no data) until it
      timed out. End Game calls stopStreamNow->stop(), so the stream kept running. FIX (native, built +
      installed to tablet 2026-06-27): store the live broadcastId + app context at go-live; stop() now
      re-acquires a FRESH OAuth token (start-time one expires on a long game) and transitions the
      broadcast to "complete" — routed via NetworkRouter so it works on the Mevo cellular setup. NOTE: a
      stream orphaned by a PREVIOUS app session (broadcastId lost on restart) must still be ended in
      YouTube Studio; the fix ends streams within the same session.

## G. Small / polish
- [x] G1. Short, light haptic vibrate on pitch-button input (v189).
- [x] G2. Already supported: in-game Setup (settingsMode) edits G.runCap/innings/mercy/pitchMax (and
      league presets via ngLeague) directly with a re-render, so changes apply to the live game at once;
      Done (settingsdone) persists via broadcast and returns to play without resetting. Verify on device.

## I. Bug fixes (NEW 2026-06-27)
- [x] I1. Team-chat Delete button did nothing on the tablet (v211). Root cause: the native app has NO
      WebChromeClient, so Android WebView's window.confirm() returns false by default — the "Delete this
      message for everyone?" gate always cancelled. Fix: WebView-safe two-tap inline confirm (Delete →
      "Confirm delete?"), no confirm() dialog. Resets on chat close.
- [x] I2. Delete-saved-lineup had the SAME broken confirm() (v211) → converted to the in-app confirmAct
      modal (the pattern the rest of the app's deletes already use, which works on native).
      NOTE: any other window.confirm() added later will silently fail on the tablet — use confirmAct or a
      two-tap inline confirm, never confirm()/alert() for control flow on native.

---
Done earlier this session: edit/correction ghost-click fix (v185), grid-button sweep (v184),
window-border consistency (v185–v188), cross-device stale-write protection (v188).
