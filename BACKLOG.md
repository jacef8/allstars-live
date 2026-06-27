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
- [ ] A5. A play is animating TWICE — is that intentional? (investigate / fix)
- [x] A6. Window borders: consistent thick white, no stray red/thin (done v185–v188 sweep).

## B. Scoreboard + game feed
- [ ] B1. Scorer scoreboard is too small, not grid-defined, no colors — hard to read what's what. Redesign.
- [ ] B2. Game feed: add OUTS and/or RUNS to a play's feed line when relevant.
- [ ] B3. Remove pitch-count increment from wild pitch / passed ball / etc. (and don't show "pitch +1"
      on every feed update for those).
- [ ] B4. Strikeouts need a looking vs swinging option.
- [ ] B5. RBI feed detail, e.g. "Double — Caden Schaefer; Jones scores from 3rd."
- [ ] B6. Caught stealing: add HOW (picked off / catcher threw them out).
- [ ] B7. End-of-inning notification/result in the feed (e.g. after the 3rd out: "3 outs — end of T3").
- [ ] B8. Update the batter display after his at-bat completes.
- [ ] B9. Pitch controls: dark bg behind red B/S/F icons is hard to read — use a light/white background;
      explore baseball graphics (white ball + red stitching), B/S/F over a baseball emblem.
- [ ] B10. Viewer page feed is EMPTY during the game (bug).
- [ ] B11. Shared game link → recipient's Home shows no "resume/enter game," even signed in with our team
      listed; no way into the live game.

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
- [ ] D4. BUG: after End Game → "save stats this season?" → Yes, the team record/stats didn't update and
      the game still shows as not-yet-played on the schedule.

## E. Pitch count
- [ ] E1. Track the OPPONENT pitch count too, and expose it in the editor.

## F. Streaming / connectivity
- [ ] F1. IP address changed — no way to see whether the video is connected to the app (status).
- [ ] F2. YouTube connector is broken again (investigate).

## G. Small / polish
- [x] G1. Short, light haptic vibrate on pitch-button input (v189).
- [ ] G2. Changing rules mid-game must apply immediately mid-game.

---
Done earlier this session: edit/correction ghost-click fix (v185), grid-button sweep (v184),
window-border consistency (v185–v188), cross-device stale-write protection (v188).
