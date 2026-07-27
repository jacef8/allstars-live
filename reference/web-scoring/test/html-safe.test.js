/* Tests for the shared HTML-escaping function — see html-safe.js's header for why it exists
 * (9 different, inconsistently-complete ad hoc escapers found in one audit pass). Run with:
 * node --test test/
 */
const test = require("node:test");
const assert = require("node:assert/strict");
const { escHtml } = require("../html-safe.js");

test("escHtml: escapes all 5 HTML-significant characters", () => {
  assert.equal(escHtml(`&<>"'`), "&amp;&lt;&gt;&quot;&#39;");
});

test("escHtml: a crafted team name can't break out of a double-quoted attribute", () => {
  const evil = `x" onmouseover="alert(1)`;
  const rendered = `<div title="${escHtml(evil)}">`;
  assert.ok(!rendered.includes('"><'), "no bare unescaped quote should let a new attribute start");
  assert.ok(rendered.includes("&quot;"));
});

test("escHtml: a crafted name can't inject a new tag in text content", () => {
  const evil = `<img src=x onerror=alert(1)>`;
  const rendered = `<span>${escHtml(evil)}</span>`;
  assert.ok(!rendered.includes("<img"), "the raw tag must never survive into the output");
});

test("escHtml: null/undefined/non-string input never throws", () => {
  assert.equal(escHtml(null), "");
  assert.equal(escHtml(undefined), "");
  assert.equal(escHtml(42), "42");
  assert.equal(escHtml(""), "");
});

test("escHtml: plain text with no special characters passes through unchanged", () => {
  assert.equal(escHtml("LC AA Allstars"), "LC AA Allstars");
  assert.equal(escHtml("B. Mayo #27"), "B. Mayo #27");
});
