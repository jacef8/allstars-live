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

## H3. TV mode — cast/mirror the viewer to a TV (NEW 2026-06-28)
- [x] H3. jford: "can we allow the viewer screen to be casted to a tv?" Built v238: a fullscreen, no-chrome
      lean-back view = the pasted/streamed video filling the screen + a BIG lower-third broadcast bug
      (`tvBug()`, bottom-left, sized to read across a room). Reachable two ways: a "TV" button in the viewer
      monitor / the Watch-with-scorebug sheet (`tventer` → also requests browser Fullscreen), OR open
      `?tv=1` directly on a smart-TV / Chromecast browser (boot flag forces viewerLocked+viewer). `tvMode`
      short-circuits render() like OVERLAY. Controls: Video (reopen paste/delay sheet), Fullscreen (`tvcast`
      toggles Fullscreen API + a cast/mirror hint), Exit TV. Honest limit: a true one-tap Google Cast SDK
      button is a FOLLOW-UP — YouTube iframe + HTML overlay can't be cast as one element, so v1 is
      fullscreen + screen-mirroring (or open the ?tv link on the TV). Verified in preview: video fills,
      bug shows RAY 3 / HAW 5 with correct count/outs/bases + LIVE·+8s delay tag.

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

## J. Coach-pitch league mode — separate scoring screen (NEW 2026-06-30, requested by jford)
- [ ] J1. A distinct league/scoring MODE for coach-pitch (coach pitches to their own batters). Picked per
      team/league like the existing DYB presets. When active, the scorer screen changes:
      - **No balls.** Balls don't exist — REMOVE walks (BB) and hit-by-pitch (HBP) entirely. No ball button.
      - **5-pitch limit per batter:** a batter gets a total of **5 pitches**. If not put in play or struck
        out by the 5th, the at-bat ends (out / next batter). **EXCEPTION: fouling it off extends** — a foul
        on the last pitch does NOT end the at-bat (like a foul on 2 strikes in regular rules); keep pitching
        until they put it in play, swing-and-miss out, or stop fouling.
      - **Remove pitcher-specific tracking:** no pitch counts / pitcher pitch limits (pitchMax), no
        pitcher-centric stats. (Coach is pitching, not a player.)
      - **KEEP / track:** strikes, **total number of pitches** seen (per at-bat), and **where they hit it**
        (hit location / spray) — with simpler pitch outcomes, batted-ball location is the main data to log.
      - **Defense: FOUR outfielders** for this league (≈10 fielders). Add the 4th OF to the positions list +
        the field diagram / position picker when coach-pitch mode is on.
      Open Qs for build time: exact "5 pitches" UI (counter + foul handling), whether strikeouts still apply
      (swing-and-miss on the 5th?), and how the 4-OF layout maps on the spray field. Relates to
      [[allstars-league-rules]] (DYB presets), positions UX in section C.

## K. Two scorers on one live game — concurrency lock (NEW 2026-06-30, jford hit this in the field)
- [ ] K1. jford was able to sign in and START scoring a game that someone ELSE was already actively
      scoring — both live at once. Today's model (v152/153) is last-claim-wins + a "Take over" handoff
      (`watchLiveTakeover` watches `games/<teamId>-live`; a newer `activeUid/activeAt` demotes the other to
      read-only). It only coordinates IF the 2nd person goes through "Take over." If they just resume/score
      directly (in-progress row not synced yet, or fresh New Game), there's NO hard lock → both write to the
      same live doc, last-write-wins clobbers, plays double/diverge. Fix direction:
      - **Heartbeat lease:** active scorer writes `activeAt` every ~10–15s; "fresh" = within ~30–45s, else stale/claimable.
      - **Warn on entry, don't silently score:** when a signed-in scorer opens/starts a team whose live doc
        has a FRESH lease from another uid, block silent scoring → modal "⚠️ <name> is scoring this game
        right now. Take over?" Explicit takeover only (no accidental double-scoring).
      - **Write-guard every action:** before each scoring write, verify `activeUid==me`; if not, STOP and
        flip to read-only with a toast. Stops the demoted device double-writing during the tug-of-war.
      - **Clear demotion banner** on the device that lost it: "Someone took over scoring — you're watching now."
      Relates to [[allstars-cross-device-live-game]], [[allstars-live-access-control]]. Requires signed-in scorer.

## L. Share-access landing + clarity (NEW 2026-06-30, jford: "lots of issues sharing access")
- [x] L0. Share > Text dropped the link on iPhone (sms:?body= → iOS needs &body=). Fixed v277: prefer
      navigator.share (carries the link into Messages), platform-aware sms: fallback. All share types.
- [x] L1 (v278). **Recipient lands on the wrong screen / is asked to sign in or create a team.** Root cause:
      `cloudClaimFollow` + `cloudClaimInvite` both `if(!em)return` when the recipient isn't signed in — so
      a `?follow=`/`?invite=` link dumps them on the generic home ("create your first team" + sign-in) with
      NO context, and the invite is silently lost (only completes IF they happen to sign in; the auth
      handler re-runs claim after sign-in). Fix (v278): a focused INVITE LANDING when `?follow`/`?invite`
      is present and signed-out — names the team (cloudGetTeam) + "Sign in to follow / score" + the sign-in
      buttons, instead of the default home. After sign-in the existing claim completes + `_joinTeamId`
      navigates to the team.
- [ ] L2. **Too many share buttons, unclear which to send** (Share game vs Share & invite team vs Share
      player vs scorer invite vs Share app). Consolidate/clarify: one obvious "Share team" (fan follow) +
      a clearly separate "Invite a scorer" (access), with one-line "what this does" on each. Consider a
      single smart link that lands on a public team page (scores/schedule/roster, no sign-in) with opt-in
      Follow / Request-to-score buttons. Design pass needed. Relates to [[allstars-membership-tiers]],
      [[allstars-live-access-control]].

## M. Phone scorer control reachability + iOS scroll (NEW 2026-06-30, jford)
- [x] M1 (v279). **Skip batter unreachable on phone.** The visible EDIT pencil (→ Correct-the-game, which
      holds the "SKIP / GO BACK" / "At-bat spot" stepper) is `fieldOverlays()` = WIDE-ONLY; phone's only
      path was an undiscoverable long-press on the `statusbar`/`scorebar` (which is the ON-AIR scorebug —
      can't put a control there). Added a visible **"✎ Fix/Skip"** button to the phone footer (between
      Lineup and Setup) → `correctopen`. NOTE the footer (Undo/Home/Lineup/Fix-Skip/Setup/End) only shows
      when `mode==="idle" && !playing` — hidden mid-at-bat. **Edit lineup = the footer "Lineup" button**
      (→ mode="lineup"); it's there, just only between plays.
- [x] M2 (v279). **"Edit window" (toolbar drag-edit toggle `togglebaredit`, ⊢ glyph) ran off the right edge
      on phones.** The batter `chip` in the top toolbar was `flex:none` so a long name pushed the row wide
      (overflow-x:auto) and the toggle scrolled off. Made the chip `flex:0 1 auto` (ellipsis). No more
      horizontal overflow; toggle stays on-screen.
- [x] M3 (v279). **iOS "screen moves around instead of scrolling."** `--avh` was recomputed on every
      `visualViewport "scroll"` event → Safari's sliding address bar resized every `calc(var(--avh)*N)`
      element mid-scroll → lurch. Removed the scroll listener + added a 10px threshold; only keyboard/
      rotation update `--avh` now. ⚠️ DON'T reintroduce the visualViewport scroll→setAvh listener. Needs
      on-device iOS confirmation.

## N. Paste an external YouTube link into the app while scoring (NEW 2026-06-30, jford single-device)
- [x] N1 (v282, NATIVE PATH NEEDS ON-DEVICE TEST). Scenario: ONE device (tablet), camera streams DIRECTLY to YouTube (DJI Mimo), so the app is NOT
      doing the streaming. jford wants to paste that YouTube link into the app so the live video shows in
      the monitor ALONGSIDE the game feed + score (no bug burned into the video — bug stays app-side).
      What EXISTS: the "CONNECT YOUR STREAM" modal (`openStream`/`ytmodal`, `streamYt()` from `?yt=` or
      `al-stream-yt`) + the viewer "watch with scorebug" (`watchvidopen` → paste link → video + scorebug;
      becomes the shared `&yt=` link). So web + viewers already do this.
      THE GAP: in the NATIVE app the monitor IGNORES a pasted link unless the app's OWN broadcast is live —
      `const yt=(IS_APP && window.__bcastPhase!=="LIVE")?"":streamYt();` (line ~2445). So a scorer using
      Mimo (app not self-broadcasting) can't see/paste an external link in the monitor. FIX: let the native
      app show a pasted EXTERNAL `streamYt()` in the monitor when it isn't self-broadcasting (distinguish
      "external pasted link" from "the app's own published stream" so we don't override the real broadcast),
      and surface a "Paste a YouTube link" entry in the scorer monitor (not just viewer mode). Make sure the
      shared follower link carries that `&yt=` so fans see video + score too. Relates to
      [[allstars-watch-with-scorebug]], [[allstars-live-operator-ux-no-backend]]. Touches native monitor
      gating — verify it doesn't break the app's own streaming path.

## O. Day / Night mode toggle (NEW 2026-06-30, jford — daytime/sunlight readability)
- [ ] O1. Settings toggle (al-day-mode). DAY = light backgrounds + DARK text; jford wants EVERYTHING light
      incl. the video monitor + on-air scorebug (confirmed). Approach: make `T` swappable (dark/light
      palettes) + `applyTheme()` re-render; ICN is fine (icons use `currentColor`). ⚠️ HARD PART — the `T`
      keys are OVERLOADED: `T.line` = white BORDERS *and* white TEXT on red/colored buttons; `T.sage` = white
      text in spots. A naive swap → dark text on red buttons + invisible borders. Must SPLIT overloaded keys
      (e.g. add `T.onAccent` for button text, separate `T.border` from any "needs-to-stay-white" use) before
      flipping. Also a sweep of HARDCODED darks (`rgba(13,19,32,…)`, `#0B0E13`, `#10141A`, gradients in the
      scoreboard/monitor) → theme-aware. VERIFY every screen in BOTH modes (home, scorer, lineup/field map,
      stats, modals, scorebug, monitor). Keep the field graphic green/clay. Relates to [[allstars-brand-rwb]],
      [[allstars-recurring-ux-rules]] (thick borders rule = borders, not literally white in day mode).

## P. Sync clarity — different-account confusion (NEW 2026-06-30)
- [ ] P1. jford hit "games scored on device A don't show on device B" — ROOT CAUSE was the two devices on
      DIFFERENT Google accounts (teams/live games sync within ONE account, or via shared scorer/follower).
      Not a bug. CONSIDER a UX nudge: Diagnostics already shows the account; maybe surface a clearer hint
      when a shared/live game can't be found ("this team isn't on this account — sign in as <owner> or get
      invited as a scorer"). Low priority; the real fix is same-account or a scorer invite.

---
Done earlier this session: edit/correction ghost-click fix (v185), grid-button sweep (v184),
window-border consistency (v185–v188), cross-device stale-write protection (v188).
