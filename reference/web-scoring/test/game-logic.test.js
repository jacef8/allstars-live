/* Regression tests for game-logic.js — the pure scoring rules extracted from
 * scoring-controller.html. Run with: node --test test/
 *
 * These specifically cover bugs that were found and fixed live, by hand, over one long session
 * (see the game's memory/commit history for "fielder's choice", "double play", "runner out" for
 * the full stories) — the exact kind of thing that should never need re-discovering the same way
 * twice. No test framework dependency: Node's built-in `node:test` + `node:assert`.
 */
const test = require("node:test");
const assert = require("node:assert/strict");
const {
  buildPlan, playSummary, outsFromKind, rbiEligible,
  teamRec, teamRecForSeason, gameSeason, idealText,
  RUNCOLORS, BATTER_GREEN, OUT_MARKER_COLOR,
} = require("../game-logic.js");

function runner(label, color, key) { return { label, name: "", color, key: key || label }; }

test("buildPlan: fielder's choice sends EVERY on-base runner to the force base, not just the out one", () => {
  // Runner on 1st and 3rd, FC to short — defaults to the LEAD runner (3rd) being out.
  const bases = { 1: runner("#12", "#e11d2e"), 2: null, 3: runner("#27", "#2196f3") };
  const plan = buildPlan("fc", bases, "#1", "Batter", null, "bk");
  const batter = plan.find(m => m.isBatter);
  const r1 = plan.find(m => m.start === 1);
  const r3 = plan.find(m => m.start === 3);
  assert.equal(batter.out, false, "batter always reaches safely on a fielder's choice");
  assert.equal(batter.dest, 1);
  assert.equal(r3.out, true, "defaults to the lead runner (3rd) being the one retired");
  assert.equal(r3.dest, 4, "the out runner's dest is the FORCE base they were retired at, not their own base — this was the exact bug: 'it only shows an x at first base but really the out was made at 2nd'");
  assert.equal(r1.out, false);
  assert.equal(r1.dest, 2, "every OTHER on-base runner is still forced to attempt the next base on an FC");
});

test("buildPlan: fielder's choice honors an explicit fcOut override (reassigning who's out)", () => {
  const bases = { 1: runner("#12", "#e11d2e"), 2: null, 3: runner("#27", "#2196f3") };
  const plan = buildPlan("fc", bases, "#1", "Batter", { fcOut: 1 }, "bk");
  const r1 = plan.find(m => m.start === 1);
  const r3 = plan.find(m => m.start === 3);
  assert.equal(r1.out, true, "fcOut:1 reassigns the out to the runner from 1st instead of the default lead runner");
  assert.equal(r3.out, false, "the previously-defaulted lead runner is safe once reassigned away from them");
  assert.equal(r3.dest, 4, "and still advances to the force base since they were part of the same play");
});

test("buildPlan: double play is a force at first — NOT the same shape as a caught fly ball", () => {
  const bases = { 1: runner("#12", "#e11d2e"), 2: null, 3: null };
  const plan = buildPlan("dp", bases, "#1", "Batter", null, "bk");
  const batter = plan.find(m => m.isBatter);
  const r1 = plan.find(m => m.start === 1);
  assert.equal(batter.out, true, "the batter is out at first on a double play — this is what makes DP wrong to use for a caught-fly-ball scenario");
  assert.equal(r1.out, true, "and the force runner is also out");
  assert.equal(outsFromKind("dp"), 2);
});

test("buildPlan: a fly out only outs the batter — the runner holds, unless marked out separately", () => {
  // This is the exact scenario reported: fly ball caught (batter out), runner on 3rd tags up and is
  // thrown out at the plate. "Double play" would have wrongly implied a force at first; the correct
  // call is Fly out + the runners screen's independent per-runner OUT toggle (see commit() in the
  // main app for how outsFromKind is only the FLOOR, not the ceiling, once a runner is marked out
  // by hand).
  const bases = { 1: null, 2: null, 3: runner("#27", "#2196f3") };
  const plan = buildPlan("flyout", bases, "#1", "Batter", null, "bk");
  const batter = plan.find(m => m.isBatter);
  const r3 = plan.find(m => m.start === 3);
  assert.equal(batter.out, true);
  assert.equal(r3.out, false, "buildPlan alone never outs a runner on a flyout — that's the app's markout() toggle's job, layered on top");
  assert.equal(r3.dest, 3, "holds their base by default");
  assert.equal(outsFromKind("flyout"), 1, "outsFromKind is a FLOOR (the batter's guaranteed out) — the app counts the CONFIRMED PLAN's actual out entries on top of this, not this fixed number alone");
});

test("buildPlan: origColor survives an out (so a runner can be un-marked cleanly)", () => {
  const bases = { 1: null, 2: null, 3: runner("#27", "#2196f3") };
  const plan = buildPlan("dp", bases, "#1", "Batter", { dpOut: 3 }, "bk");
  const r3 = plan.find(m => m.start === 3);
  assert.equal(r3.out, true);
  assert.equal(r3.color, OUT_MARKER_COLOR, "an out runner's display color is tinted...");
  assert.equal(r3.origColor, "#2196f3", "...but origColor always keeps their real identity color underneath");
});

test("gameSeason / teamRec / teamRecForSeason: a stale season filter can't hide a game from the ALL-TIME record", () => {
  // This is the "Home screen says 1-3 but Schedule says 2-2" class of bug — always confirm teamRec()
  // (no filter) matches teamRecForSeason(t, "all") exactly; a stale non-"all" filter is the only way
  // these two could ever legitimately disagree.
  const team = {
    games: [
      { date: "2026-07-05", result: "W" },
      { date: "2026-07-04", result: "L" },
      { date: "2025-06-01", result: "L" },
      { date: "2025-05-01", result: "T" },
    ],
  };
  assert.deepEqual(teamRec(team), { w: 1, l: 2, t: 1 });
  assert.deepEqual(teamRecForSeason(team, "all"), teamRec(team), "teamRecForSeason('all') must always exactly equal the unfiltered teamRec()");
  assert.deepEqual(teamRecForSeason(team, "2026"), { w: 1, l: 1, t: 0 });
  assert.deepEqual(teamRecForSeason(team, "2025"), { w: 0, l: 1, t: 1 });
  assert.equal(gameSeason({ date: "" }), "Undated");
});

test("outsFromKind / rbiEligible: sanity-check the whole table (a typo here silently changes the score)", () => {
  assert.equal(outsFromKind("dp"), 2);
  for (const k of ["out1", "flyout", "popout", "lineout", "sacfly", "fc", "sacbunt", "cpout"]) {
    assert.equal(outsFromKind(k), 1, `${k} should be exactly 1 out`);
  }
  for (const k of ["single", "double", "triple", "hr", "walk", "hbp", "error"]) {
    assert.equal(outsFromKind(k), 0, `${k} should be 0 outs`);
  }
  for (const k of ["single", "double", "triple", "hr", "sacfly", "sacbunt", "walk", "hbp"]) {
    assert.equal(rbiEligible(k), true, `${k} should be RBI-eligible`);
  }
  for (const k of ["fc", "error", "dp", "out1"]) {
    assert.equal(rbiEligible(k), false, `${k} should NOT be RBI-eligible`);
  }
});

test("idealText: picks readable text color against light and dark swatches", () => {
  assert.equal(idealText("#FFFFFF"), "#10141A", "white background needs dark text");
  assert.equal(idealText("#000000"), "#fff", "black background needs white text");
  assert.equal(idealText("not-a-color"), "#fff", "never throws on bad input");
});

test("playSummary: walk/HBP never carry a fielding zone; everything else does when tapped", () => {
  assert.equal(playSummary({ kind: "walk" }, { zone: "left field" }), "Walk");
  assert.equal(playSummary({ kind: "flyout" }, { zone: "left field" }), "Fly out left field");
  assert.equal(playSummary({ kind: "flyout" }, null), "Fly out");
});

test("constants: RUNCOLORS has no yellow (must stay distinguishable on the orange basepath)", () => {
  assert.equal(RUNCOLORS.some(c => /^#F{0,1}FF00$/i.test(c)), false);
  assert.ok(BATTER_GREEN.startsWith("#"));
});

/* ---- undo history + feed housekeeping ---- */
const { histCompact, rewindIndex, trimFeed, histPack, histUnpack, isEditablePlay } = require("../game-logic.js");

// A pitch-by-pitch game: each at-bat is two pitch snapshots then the play's snapshot (cp true).
// Feed ids: the snapshot's ev is the last id handed out before it; its action then adds one line.
function fakeGame(atBats) {
  const hist = [], feed = []; let id = 0;
  for (let a = 0; a < atBats; a++) {
    for (let p = 0; p < 2; p++) { hist.push({ g: { a, p }, ev: id, cp: false }); feed.push({ id: ++id, type: "pitch", away: 0, home: 0 }); }
    hist.push({ g: { a, p: "play" }, ev: id, cp: true }); feed.push({ id: ++id, type: "play", kind: "single", away: 0, home: 0 });
  }
  return { hist, feed };
}

test("histCompact: keeps the recent taps whole and one checkpoint per older play, so Rewind reaches the first at-bat", () => {
  const { hist, feed } = fakeGame(30);   // 90 snapshots: well past the old flat cap of 40
  histCompact(hist, 12, 500);
  assert.equal(hist.length, 12 + 26, "12 recent + the 26 older at-bats' play checkpoints");
  const firstPlay = feed.find(isEditablePlay);
  const i = rewindIndex(hist, firstPlay.id);
  assert.ok(i >= 0, "the very first play is still rewindable");
  assert.deepEqual(hist[i].g, { a: 0, p: "play" }, "and it restores the state just before that play");
  for (const line of feed.filter(isEditablePlay)) assert.ok(rewindIndex(hist, line.id) >= 0, "every play line stays rewindable");
});

test("rewindIndex: refuses (instead of over-rewinding) when the snapshots just before a line were thinned away", () => {
  const { hist, feed } = fakeGame(10);
  histCompact(hist, 3, 500);
  const oldPitch = feed[3];   // a pitch of at-bat 2, whose own snapshot was dropped
  assert.equal(rewindIndex(hist, oldPitch.id), -1, "restoring the previous checkpoint would also undo the play before it");
  assert.equal(rewindIndex([], 5), -1);
});

test("rewindIndex: snapshots with no ev fall back to the feed position", () => {
  assert.equal(rewindIndex([{ fl: 0 }, { fl: 3 }], "x", 2), 0);
});

test("histCompact: hard ceiling drops the oldest entries", () => {
  const { hist } = fakeGame(40);
  histCompact(hist, 10, 30);
  assert.equal(hist.length, 30);
});

test("trimFeed: sheds old non-scoring pitch lines before any play line", () => {
  const feed = [];
  for (let i = 1; i <= 10; i++) feed.push({ id: i, type: i % 2 ? "pitch" : "play", kind: "single", away: 0, home: i >= 6 ? 1 : 0 });
  trimFeed(feed, 7);
  assert.equal(feed.length, 7);
  assert.deepEqual(feed.filter(x => x.type === "play").map(x => x.id), [2, 4, 6, 8, 10], "no play line was dropped");
  assert.ok(feed.some(x => x.id === 1), "the first line has nothing before it to compare scores with, so it stays while other pitches can go");
  const only = [{ id: 1, type: "play", away: 0, home: 0 }, { id: 2, type: "play", away: 0, home: 0 }];
  trimFeed(only, 1);
  assert.deepEqual(only.map(x => x.id), [2], "falls back to dropping the oldest line");
});

test("trimFeed: never drops the current at-bat's pitches, even once plays alone fill the cap", () => {
  const feed = [];
  for (let i = 1; i <= 220; i++) feed.push({ id: i, type: "play", kind: "single", away: 0, home: 0 });
  feed.push({ id: 221, type: "pitch", away: 0, home: 0 });
  trimFeed(feed, 220);
  assert.equal(feed.length, 220);
  assert.equal(feed[feed.length - 1].id, 221, "the pitch just added is still there");
  assert.equal(feed[0].id, 2, "the oldest line went instead");
  // A finished at-bat's pitch (before the last play) is still shed ahead of any play line.
  const f2 = [{ id: 1, type: "play", away: 0, home: 0 }, { id: 2, type: "pitch", away: 0, home: 0 }, { id: 3, type: "play", away: 0, home: 0 }, { id: 4, type: "pitch", away: 0, home: 0 }];
  trimFeed(f2, 3);
  assert.deepEqual(f2.map(x => x.id), [1, 3, 4]);
});

test("histPack/histUnpack: round-trips the history, shares repeated values, and gives every entry its own objects", () => {
  const G1 = { inning: 1, bases: { 1: null, 2: { label: "#4" }, 3: null }, stats: { o1: { ab: 1 }, o2: { ab: 0 } }, innLog: [], name: undefined };
  const G2 = JSON.parse(JSON.stringify(G1)); G2.stats.o2.ab = 1;
  const hist = [{ g: G1, ev: 4, fl: 4, n: 1, cp: true, dz: 6 }, { g: G2, ev: 7, fl: 7, n: 2, cp: false, play: { eid: 8 } }];
  const packed = JSON.parse(JSON.stringify(histPack(hist, 1)));
  assert.ok(packed.pool.length < 10, "identical field values are stored once");
  const back = histUnpack(packed);
  assert.deepEqual(back.map(h => h.g), [JSON.parse(JSON.stringify(G1)), G2]);
  assert.deepEqual(back.map(h => [h.ev, h.fl, h.n, h.cp, h.dz]), [[4, 4, 1, true, 6], [7, 7, 2, false, undefined]]);
  assert.deepEqual(back[1].play, { eid: 8 });
  back[0].g.stats.o1.ab = 99;
  assert.equal(back[1].g.stats.o1.ab, 1, "changing one restored snapshot (Undo makes it the live G) never leaks into another");
  assert.deepEqual(histUnpack({ v: 2 }), [], "an unknown format restores nothing rather than garbage");
});

/* ---- late pitching change: move a pitcher's charges to the new pitcher ---- */
const { pitcherCreditSince, movePitcherCredit } = require("../game-logic.js");

test("pitcherCreditSince: only what the old pitcher was charged with since the starting point", () => {
  const before = { pitches: { o3: 40, o7: 2 }, pstats: { o3: { k: 4, bb: 1, h: 2, r: 1, outs: 9 } } };
  const now = { pitches: { o3: 46, o7: 2 }, pstats: { o3: { k: 5, bb: 1, h: 3, r: 1, outs: 10 } } };
  const c = pitcherCreditSince(before, now, "o3");
  assert.equal(c.n, 6);
  assert.deepEqual(c.stats, { k: 1, h: 1, outs: 1 }, "unchanged fields are left out");
  assert.equal(c.any, true);
  assert.equal(pitcherCreditSince(before, now, "o7").any, false, "another pitcher's unchanged line moves nothing");
});

test("pitcherCreditSince: missing pitches / pstats (older saved games) count as zero, never negative", () => {
  const c = pitcherCreditSince({}, { pitches: { o1: 3 } }, "o1");
  assert.equal(c.n, 3);
  assert.deepEqual(c.stats, {});
  const down = pitcherCreditSince({ pitches: { o1: 9 } }, { pitches: { o1: 5 } }, "o1");
  assert.equal(down.n, 0, "a count that went down (a correction) moves nothing");
  assert.equal(down.any, false);
  assert.equal(pitcherCreditSince(null, null, "o1").any, false);
});

test("movePitcherCredit: moves pitches and stat line, creating the new pitcher's row", () => {
  const g = { pitches: { o3: 46 }, pstats: { o3: { k: 5, bb: 1, h: 3, r: 1, outs: 10 } } };
  const moved = movePitcherCredit(g, "o3", "o8", { n: 6, stats: { k: 1, h: 1, outs: 1 } });
  assert.equal(moved, true);
  assert.deepEqual(g.pitches, { o3: 40, o8: 6 });
  assert.deepEqual(g.pstats.o3, { k: 4, bb: 1, h: 2, r: 1, outs: 9 });
  assert.deepEqual(g.pstats.o8, { k: 1, bb: 0, h: 1, r: 0, outs: 1 });
});

test("movePitcherCredit: never takes more than the old pitcher has, and refuses no-op moves", () => {
  const g = { pitches: { o3: 2 }, pstats: { o3: { k: 0, bb: 0, h: 0, r: 0, outs: 1 } } };
  movePitcherCredit(g, "o3", "o8", { n: 5, stats: { k: 2, outs: 3 } });
  assert.deepEqual(g.pitches, { o3: 0, o8: 2 });
  assert.equal(g.pstats.o3.outs, 0);
  assert.equal(g.pstats.o8.outs, 1);
  assert.equal(g.pstats.o8.k, 0, "nothing to take, nothing given");
  assert.equal(movePitcherCredit(g, "o8", "o8", { n: 1, stats: {} }), false, "same pitcher");
  assert.equal(movePitcherCredit({}, "o1", "o2", { n: 3, stats: { k: 1 } }), false, "an older game with no pitches/pstats yet");
});

test("movePitcherCredit: a move of pitches only still gives the new pitcher a box-score line", () => {
  // The box score lists pitchers from the pstats keys; a reliever with pitches but no K/BB/H/R/out
  // yet would otherwise have his moved pitches show up nowhere.
  const g = { pitches: { o3: 30 }, pstats: { o3: { k: 2, bb: 0, h: 1, r: 0, outs: 6 } } };
  assert.equal(movePitcherCredit(g, "o3", "o8", { n: 4, stats: {} }), true);
  assert.deepEqual(g.pitches, { o3: 26, o8: 4 });
  assert.deepEqual(g.pstats.o8, { k: 0, bb: 0, h: 0, r: 0, outs: 0 }, "pstats row exists after a pitches-only move");
  assert.deepEqual(g.pstats.o3, { k: 2, bb: 0, h: 1, r: 0, outs: 6 }, "the old pitcher's line is untouched");
});

/* ---- league rules: run cap schedule / final-inning exception, run rule, pitch/catch tiers ---- */
const { runCapFor, mercyTiers, mercyCall, pitchAlertTier, catcherAlertTier } = require("../game-logic.js");

test("runCapFor: a flat cap with no extra fields reads exactly as before (older saved games)", () => {
  assert.equal(runCapFor({ runCap: 7, innings: 6 }, 1), 7);
  assert.equal(runCapFor({ runCap: 7, innings: 6 }, 6), 7, "no finalFree flag = the final inning is capped too");
  assert.equal(runCapFor({ runCap: 0, innings: 6 }, 3), 0, "0 = no cap");
  assert.equal(runCapFor(null, 3), 0);
  assert.equal(runCapFor({ runCap: "x" }, 3), 0, "junk reads as no cap, never NaN");
});

test("runCapFor: DYB 'except in the sixth inning' lifts the cap only in the regulation final inning", () => {
  const r = { runCap: 7, innings: 6, finalFree: true };
  assert.equal(runCapFor(r, 5), 7);
  assert.equal(runCapFor(r, 6), 0, "no cap in the 6th");
  assert.equal(runCapFor(r, 7), 7, "after the sixth the cap is in force again (extra innings)");
  assert.equal(runCapFor({ runCap: 5, innings: 4, finalFree: true }, 4), 0, "follows the game's own innings");
});

test("runCapFor: a per-inning schedule changes the cap from its inning onward", () => {
  const r = { runCap: 5, innings: 6, sched: [{ from: 5, cap: 0 }] };
  assert.deepEqual([1, 2, 3, 4, 5, 6, 7].map(i => runCapFor(r, i)), [5, 5, 5, 5, 0, 0, 0], "5 runs innings 1-4, unlimited after");
  const r2 = { runCap: 3, innings: 6, sched: [{ from: 4, cap: 6 }, { from: 2, cap: 4 }] };
  assert.deepEqual([1, 2, 3, 4, 5].map(i => runCapFor(r2, i)), [3, 4, 4, 6, 6], "steps apply in inning order whatever order they were saved in");
  assert.equal(runCapFor({ runCap: 5, innings: 6, sched: [{ from: 0, cap: 9 }] }, 3), 5, "a step with no inning is ignored");
  assert.equal(runCapFor({ runCap: 5, innings: 6, finalFree: true, sched: [{ from: 3, cap: 8 }] }, 6), 0, "the final-inning exception wins over the schedule");
});

test("mercyTiers / mercyCall: legacy {r15,r10} shape, largest margin first", () => {
  assert.deepEqual(mercyTiers({ r15: 3, r10: 4 }), [{ diff: 15, after: 3 }, { diff: 10, after: 4 }]);
  assert.deepEqual(mercyTiers({ r15: 0, r10: 4 }), [{ diff: 10, after: 4 }], "0 = that tier is off");
  assert.deepEqual(mercyTiers(null), []);
  const m = { r15: 3, r10: 4 };
  assert.equal(mercyCall(m, 3, "T", 16, 0), null, "visitors up 16 in the top of the 3rd: home hasn't batted 3 times");
  assert.deepEqual(mercyCall(m, 4, "T", 16, 0), { diff: 15, after: 3, lead: 16, homeBottom: false });
  assert.deepEqual(mercyCall(m, 5, "T", 12, 0), { diff: 10, after: 4, lead: 12, homeBottom: false });
  assert.equal(mercyCall(m, 4, "T", 12, 0), null, "10-run rule needs four complete innings");
  assert.deepEqual(mercyCall(m, 5, "T", 0, 20), { diff: 15, after: 3, lead: 20, homeBottom: false }, "the bigger tier wins");
});

test("mercyCall: home team leading in the bottom of the rule inning ends it a half early (DYB 4.09)", () => {
  const m = { r15: 3, r10: 4 };
  assert.deepEqual(mercyCall(m, 3, "B", 0, 15), { diff: 15, after: 3, lead: 15, homeBottom: true });
  assert.equal(mercyCall(m, 3, "B", 15, 0), null, "a VISITING lead in the bottom of the 3rd still needs the home team's turn");
  assert.deepEqual(mercyCall(m, 4, "B", 1, 11), { diff: 10, after: 4, lead: 10, homeBottom: true });
  assert.deepEqual(mercyCall([{ diff: 8, after: 5 }], 5, "B", 0, 8), { diff: 8, after: 5, lead: 8, homeBottom: true }, "tier array shape");
});

test("pitchAlertTier: DYB tournament thresholds, highest reached only, coincident ones together", () => {
  const r = { daily: 75, catchMax: 40, rest: [{ min: 41, label: "36 hours" }, { min: 66, label: "48 hours" }] };
  assert.equal(pitchAlertTier(37, r), null);
  assert.deepEqual(pitchAlertTier(38, r), { at: 38, items: [{ kind: "nearcatch" }] });
  assert.deepEqual(pitchAlertTier(40, r), { at: 40, items: [{ kind: "lastcatch" }] });
  assert.deepEqual(pitchAlertTier(41, r), { at: 41, items: [{ kind: "rest", label: "36 hours" }, { kind: "nocatch" }] }, "41 = can't catch AND 36-hour rest, one moment");
  assert.deepEqual(pitchAlertTier(50, r), { at: 41, items: [{ kind: "rest", label: "36 hours" }, { kind: "nocatch" }] });
  assert.deepEqual(pitchAlertTier(66, r), { at: 66, items: [{ kind: "rest", label: "48 hours" }] });
  assert.deepEqual(pitchAlertTier(80, r), { at: 75, items: [{ kind: "daily" }] }, "the league's 75, not a hard-coded 85");
  assert.deepEqual(pitchAlertTier(90, { daily: 0, catchMax: 40, rest: [] }), { at: 41, items: [{ kind: "nocatch" }] }, "no daily limit set = no daily alert");
  assert.equal(pitchAlertTier(90, {}), null);
});

test("catcherAlertTier: DYB 11.17 four innings caught", () => {
  assert.equal(catcherAlertTier(2, 4), null);
  assert.equal(catcherAlertTier(3, 4), "near");
  assert.equal(catcherAlertTier(4, 4), "out");
  assert.equal(catcherAlertTier(9, 0), null, "0 = off");
  assert.equal(catcherAlertTier(1, 1), "out");
});
