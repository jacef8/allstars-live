/* Tests for the at-bat correction helpers in game-logic.js (playFootprint, footprintDelta,
 * playSubFix, playSubRename) used by the scorer's "Edit this at-bat" sheet. Run with: node --test test/
 */
const test = require("node:test");
const assert = require("node:assert/strict");
const { playFootprint, footprintDelta, playSubFix, playSubRename } = require("../game-logic.js");

test("playFootprint: AB / H / BB / K / E / pitcher outs per kind", () => {
  assert.deepEqual(playFootprint("single"), { ab: 1, h: 1, bb: 0, k: 0, e: 0, outs: 0 });
  assert.deepEqual(playFootprint("error"), { ab: 1, h: 0, bb: 0, k: 0, e: 1, outs: 0 });
  assert.deepEqual(playFootprint("walk"), { ab: 0, h: 0, bb: 1, k: 0, e: 0, outs: 0 });
  assert.deepEqual(playFootprint("strikeout"), { ab: 1, h: 0, bb: 0, k: 1, e: 0, outs: 1 }, "a K's out is added on top of outsFromKind, as creditBatter() does");
  assert.deepEqual(playFootprint("dp"), { ab: 1, h: 0, bb: 0, k: 0, e: 0, outs: 2 });
  assert.equal(playFootprint("ci").ab, 0, "catcher's interference is not an at-bat");
  assert.equal(playFootprint("sacfly").ab, 0);
  assert.equal(playFootprint("cpout").outs, 1);
});

test("footprintDelta: out re-ruled as an error adds a team E and keeps the AB", () => {
  const d = footprintDelta("out1", "error");
  assert.equal(d.e, 1); assert.equal(d.ab, 0); assert.equal(d.h, 0); assert.equal(d.outs, -1);
});

test("footprintDelta: single re-ruled as a strikeout moves a hit to a K", () => {
  const d = footprintDelta("single", "strikeout");
  assert.equal(d.h, -1); assert.equal(d.k, 1); assert.equal(d.ab, 0); assert.equal(d.e, 0);
});

test("footprintDelta: same kind is all zeros", () => {
  assert.ok(Object.values(footprintDelta("double", "double")).every(v => v === 0));
});

test("playSubFix: rewrites the RBI tail and keeps the rest", () => {
  assert.equal(playSubFix("Sam to left field; Jo scores from 3rd · 1 RBI", { rbi: 2 }), "Sam to left field; Jo scores from 3rd · 2 RBI");
  assert.equal(playSubFix("Sam to left field · 1 RBI", { rbi: 0 }), "Sam to left field");
  assert.equal(playSubFix("Sam to left field", { rbi: 1 }), "Sam to left field · 1 RBI");
  assert.equal(playSubFix("", { rbi: 1 }), " · 1 RBI");
  assert.equal(playSubFix("Ball four · ", { rbi: 1 }), "Ball four · 1 RBI", "an unnamed batter's walk line doesn't get a doubled separator");
});

test("playSubFix: drops a strikeout's K-type tag, and an RBI edit keeps it in place", () => {
  assert.equal(playSubFix("Sam · looking", { dropKo: true }), "Sam");
  assert.equal(playSubFix("looking", { dropKo: true }), "", "an unnamed batter's K line is the tag alone");
  assert.equal(playSubFix("Overlooking", { dropKo: true }), "Overlooking", "only a whole-word tag");
  assert.equal(playSubFix("Sam · swinging", { rbi: 1 }), "Sam · 1 RBI · swinging");
  assert.equal(playSubFix("Sam", {}), "Sam");
  assert.equal(playSubFix(undefined, {}), "");
});

test("playSubRename: swaps the batter's name wherever the line put it", () => {
  assert.equal(playSubRename("Sam to left field · 1 RBI", "Sam", "Max"), "Max to left field · 1 RBI");
  assert.equal(playSubRename("Ball four · Sam", "Sam", "Max"), "Ball four · Max");
  assert.equal(playSubRename(" to left field", "", "Max"), "Max to left field", "an unnamed batter's line starts with where it went");
  assert.equal(playSubRename("Ball four · ", "", "Max"), "Ball four · Max");
  assert.equal(playSubRename("", "", "Max"), "Max");
  assert.equal(playSubRename("Sam to left field", "Sam", ""), " to left field", "renaming to an unnamed batter leaves the rest");
  assert.equal(playSubRename("Something else", "", "Max"), "Something else", "unknown wording is left alone");
});

/* ---- feed lines a correction rewrote (hist entry .fe) survive thinning and saving ---- */
const { histCompact, histPack, histUnpack } = require("../game-logic.js");

test("histCompact: a thinned correction's before-copies move to the kept entry before it, older copies winning", () => {
  const hist = [
    { g: { s: 0 }, ev: 1, cp: true, fe: { 5: { id: 5, kind: "single", tag: "kept" } } },
    { g: { s: 1 }, ev: 2, cp: false, fe: { 5: { id: 5, kind: "double" }, 6: { id: 6, kind: "walk" } } },   // thinned
    { g: { s: 2 }, ev: 3, cp: true },
    { g: { s: 3 }, ev: 4, cp: false },
  ];
  histCompact(hist, 1, 100);
  assert.equal(hist.length, 3, "the correction snapshot was thinned");
  assert.deepEqual(hist[0].fe, { 5: { id: 5, kind: "single", tag: "kept" }, 6: { id: 6, kind: "walk" } });
});

test("histPack/histUnpack: keep a snapshot's feed before-copies", () => {
  const hist = [{ g: { a: 1 }, ev: 1, fl: 1, n: 1, fe: { 3: { id: 3, kind: "strikeout", sub: "Sam · looking" } } }, { g: { a: 2 }, ev: 2, fl: 2, n: 2 }];
  const back = histUnpack(JSON.parse(JSON.stringify(histPack(hist, 0))));
  assert.deepEqual(back[0].fe, hist[0].fe);
  assert.equal(back[1].fe, undefined, "no fe on an entry that had none (older saves load as before)");
});
