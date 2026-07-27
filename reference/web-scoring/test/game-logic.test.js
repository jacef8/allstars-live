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
