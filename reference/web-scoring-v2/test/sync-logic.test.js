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
