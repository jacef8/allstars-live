/* Tests for the cross-device merge logic — see sync-logic.js's header for why these two
 * functions get dedicated coverage (they're what actually caused the schedule-sync bug and the
 * "missing game" bug class). Run with: node --test test/
 */
const test = require("node:test");
const assert = require("node:assert/strict");
const { unionGames, unionSchedule } = require("../sync-logic.js");

test("unionGames: a game added on ONE device survives even if the other device's copy is older/entry-missing", () => {
  const deviceA = [{ id: "g1", date: "2026-07-01" }, { id: "g2", date: "2026-07-03" }];
  const deviceB = [{ id: "g1", date: "2026-07-01" }];   // hasn't seen g2 yet
  const out = unionGames(deviceA, deviceB, {});
  assert.ok(out.some(g => g.id === "g2"), "g2 must survive the merge, not vanish");
  assert.equal(out.length, 2);
});

test("unionGames: on an id collision, the copy WITH a box score wins over the one without", () => {
  const withoutBox = [{ id: "g1", date: "2026-07-01" }];
  const withBox = [{ id: "g1", date: "2026-07-01", bat: [{ num: "4", name: "M Ford" }] }];
  const out = unionGames(withoutBox, withBox, {});
  assert.ok(out[0].bat, "the richer (box-scored) copy must win, not the earlier bare one");
});

test("unionGames: a re-archived game (same id, later .at) wins over the stale copy from either side", () => {
  const stale = { id: "g1", date: "2026-07-01", us: 4, them: 5, at: 1000, bat: [{ num: "4" }] };
  const fixed = { id: "g1", date: "2026-07-01", us: 6, them: 5, at: 2000, bat: [{ num: "4" }] };
  assert.equal(unionGames([stale], [fixed], {})[0].us, 6, "the corrected copy from the other device must replace the local stale one");
  assert.equal(unionGames([fixed], [stale], {})[0].us, 6, "an older copy from the other device must not overwrite the correction");
  const legacy = { id: "g1", date: "2026-07-01", us: 4, them: 5, bat: [{ num: "4" }] };   // archived before .at existed
  assert.equal(unionGames([legacy], [fixed], {})[0].us, 6);
  assert.equal(unionGames([legacy], [legacy], {}).length, 1);
  const bare = { id: "g1", date: "2026-07-01", at: 3000 };   // a newer copy WITHOUT a box score never beats one with it
  assert.ok(unionGames([fixed], [bare], {})[0].bat);
});

test("unionGames: one game archived under two ids (same .gk) collapses to one entry, the same on every device", () => {
  const onA = { id: "g1", gk: "kX", date: "2026-07-01", us: 4, them: 5, at: 1000, bat: [{ num: "4" }] };
  const onB = { id: "g2", gk: "kX", date: "2026-07-01", us: 6, them: 5, at: 2000, bat: [{ num: "4" }] };
  const other = { id: "g3", gk: "kY", date: "2026-06-30", us: 1, them: 0, at: 1500, bat: [] };
  const ab = unionGames([onA, other], [onB], {}), ba = unionGames([onB], [onA, other], {});
  assert.equal(ab.length, 2, "the duplicate must collapse, the different game must stay");
  assert.deepEqual(ab.map(g => g.id), ["g2", "g3"]);
  assert.deepEqual(ba.map(g => g.id), ["g2", "g3"], "the result must not depend on which side is local");
  // equal .at: the lower id wins, from either side
  const t1 = Object.assign({}, onA, { at: 5 }), t2 = Object.assign({}, onB, { at: 5 });
  assert.equal(unionGames([t1], [t2], {})[0].id, "g1");
  assert.equal(unionGames([t2], [t1], {})[0].id, "g1");
  // a box score beats a newer bare copy; entries without .gk (older games) are never collapsed
  const bare = { id: "g9", gk: "kX", date: "2026-07-01", at: 9000 };
  assert.equal(unionGames([onA], [bare], {})[0].id, "g1");
  const old1 = { id: "o1", date: "2026-05-01" }, old2 = { id: "o2", date: "2026-05-01" };
  assert.equal(unionGames([old1], [old2], {}).length, 2);
});

test("unionGames: a tombstoned (deleted) game never resurrects from either side", () => {
  const deviceA = [{ id: "g1", date: "2026-07-01" }];
  const deviceB = [{ id: "g1", date: "2026-07-01" }, { id: "g2", date: "2026-07-02" }];
  const out = unionGames(deviceA, deviceB, { g1: 1 });
  assert.ok(!out.some(g => g.id === "g1"), "a tombstoned game must not come back from the OTHER device's copy");
  assert.ok(out.some(g => g.id === "g2"));
});

test("unionGames: sorted newest-first and capped at 60", () => {
  const many = Array.from({ length: 70 }, (_, i) => ({ id: "g" + i, date: "2026-01-" + String((i % 28) + 1).padStart(2, "0") }));
  const out = unionGames(many, [], {});
  assert.equal(out.length, 60, "must cap at 60 even when more are supplied");
  for (let i = 1; i < out.length; i++) assert.ok(out[i - 1].date >= out[i].date, "must be sorted newest-first");
});

test("unionSchedule: an entry added on one device survives a merge against a device that hasn't seen it", () => {
  const deviceA = [{ id: "s1", date: "2026-07-12", time: "18:00" }];
  const deviceB = [];   // the OTHER device raced a whole-doc push before this entry existed there
  const out = unionSchedule(deviceA, deviceB, {});
  assert.equal(out.length, 1);
  assert.equal(out[0].id, "s1");
});

test("unionSchedule: a tombstoned (deleted) entry never resurrects from either side", () => {
  const deviceA = [{ id: "s1", date: "2026-07-12", time: "18:00" }];
  const deviceB = [{ id: "s1", date: "2026-07-12", time: "18:00" }, { id: "s2", date: "2026-07-14", time: "10:00" }];
  const out = unionSchedule(deviceA, deviceB, { s1: 1 });
  assert.ok(!out.some(s => s.id === "s1"));
  assert.ok(out.some(s => s.id === "s2"));
});

test("unionSchedule: sorted soonest-first by date+time", () => {
  const a = [
    { id: "s1", date: "2026-07-14", time: "18:00" },
    { id: "s2", date: "2026-07-12", time: "10:00" },
  ];
  const out = unionSchedule(a, [], {});
  assert.deepEqual(out.map(s => s.id), ["s2", "s1"], "the sooner game (Jul 12) must come first");
});

test("unionGames/unionSchedule: missing/non-array inputs never throw", () => {
  assert.doesNotThrow(() => unionGames(undefined, null, undefined));
  assert.doesNotThrow(() => unionSchedule(undefined, null, undefined));
  assert.deepEqual(unionGames(undefined, undefined, undefined), []);
  assert.deepEqual(unionSchedule(undefined, undefined, undefined), []);
});
