# All-Stars Live — Backlog (captured 2026-06-27)

Status: [ ] todo · [~] in progress · [x] done. Grouped by theme; recurring/meta items first.

## A. Recurring layout/UX rules (these have been reported more than once — fix durably)
- [ ] A1. Tablet/PC windows must NOT scroll; top row of scorer page is back to 2 rows. Responsive rule:
      large devices (PC/tablet) → buttons can be LARGER but stay on ONE top row; phones → smaller
      icons, stay on one top row. Conserve vertical space for the important content.
- [x] A2. Windows must not snap-scroll back to top every time I enter an input (v189: scroll positions
      remembered + restored after each render).
- [x] A3. Editing a number: tapping the number box should select it so I can type a new value WITHOUT
      deleting the old one first (v189: global select-on-focus).
- [x] A4. Background is too dark — brighten the page background a little (v189: lightened turf scrim).
- [~] A5. A play is animating TWICE — animations are de-duped by anim id (triggerAnim guards
      a.id===_lastAnimId), so a same-play double shouldn't happen. Needs on-device repro: which play
      type? (e.g. a hit that's then thrown out plays ball-flight + throw — that's two arcs by design.)
- [x] A6. Window borders: consistent thick white, no stray red/thin (done v185–v188 sweep).

## B. Scoreboard + game feed
- [ ] B1. Scorer scoreboard is too small, not grid-defined, no colors — hard to read what's what. Redesign.
- [ ] B2. Game feed: add OUTS and/or RUNS to a play's feed line when relevant.
- [ ] B3. Remove pitch-count increment from wild pitch / passed ball / etc. (and don't show "pitch +1"
      on every feed update for those).
- [ ] B4. Strikeouts need a looking vs swinging option.
- [ ] B5. RBI feed detail, e.g. "Double — Caden Schaefer; Jones scores from 3rd."
- [ ] B6. Caught stealing: add HOW (picked off / catcher threw them out).
- [x] B7. End-of-inning notification in the feed (v193): doFlip adds a centered divider line
      "End of Top 3 · 3 outs · TEAM 2 runs, 3 hits" (reason = 3 outs vs run cap). Verified.
- [ ] B8. Update the batter display after his at-bat completes.
- [ ] B9. Pitch controls: dark bg behind red B/S/F icons is hard to read — use a light/white background;
      explore baseball graphics (white ball + red stitching), B/S/F over a baseball emblem.
- [x] B10/B11. (v191) Followers can now WATCH a live game from inside the app. Home subscribes to live
      docs for ALL my teams (not just scorable); a follower sees the in-progress row with "Watch live"
      (scorers still get "Take over"). Tapping it streams the game + FEED from the cloud live doc in
      read-only viewer mode — so the feed is no longer empty and there's a real way into the live game.
      (Same-device scorer→viewer feed already worked; the gap was the remote/follower path.)

## C. Lineup / roster / positions
- [ ] C1. In-game lineup edit: don't require a second window to edit a player; first/last name boxes
      inconsistent vs elsewhere.
- [ ] C2. Better position changes: a single swap shouldn't cascade-reassign others. Offer a field-map
      view on the lineup page to place players (drag-and-drop), or a simple single swap.
- [ ] C3. Skip a batter in the batting order.
- [ ] C4. Highlight the CURRENT batter on the in-game lineup edit page (find the next-to-edit fast).
- [ ] C5. Record lineup changes throughout the game for the historical record.
- [ ] C6. Edit the OPPONENT roster before the game-setup screen.

## D. Schedule / teams / setup
- [ ] D1. Send a link to the game from the schedule window.
- [ ] D2. Adding a team in Schedule should make it selectable in the opponent picker at game start.
- [ ] D3. Autopopulate the YouTube video name as "My Team vs Opponent."
- [x] D4. BUG: after End Game → stats/record/schedule didn't update (v190). Root cause: finished game
      stamped with UTC date (evening scoring → tomorrow) so it never matched the local scheduled date.
      Fix: local date (todayLocal); plus games started from the schedule now carry the schedule's id+date
      so they're marked played for sure, even with timezone/opponent-name mismatches. Save path itself
      (games log, derived W-L-T record, season batting rollup) verified end-to-end.

## E. Pitch count
- [ ] E1. Track the OPPONENT pitch count too, and expose it in the editor.

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

## G. Small / polish
- [x] G1. Short, light haptic vibrate on pitch-button input (v189).
- [ ] G2. Changing rules mid-game must apply immediately mid-game.

---
Done earlier this session: edit/correction ghost-click fix (v185), grid-button sweep (v184),
window-border consistency (v185–v188), cross-device stale-write protection (v188).
