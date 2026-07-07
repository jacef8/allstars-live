/* All-Stars Live — one correct, shared HTML-escaping function.
 *
 * WHY THIS FILE EXISTS: the app renders its entire UI via JS template literals assigned to
 * `innerHTML` (no framework, no auto-escaping) — so escaping user-controllable text (team names,
 * player names, chat messages, opponent names, schedule notes, anything typed into a field and
 * saved) is entirely this codebase's own responsibility, everywhere, every time. An audit found 9
 * different LOCAL `const esc=...` definitions scattered across scoring-controller.html, each with
 * a DIFFERENT level of completeness — some only escaped `<`, missing `&`, `>`, and `"` — plus
 * ~100 more places doing an ad hoc `.replace(/</g,"&lt;")` inline with no named helper at all. An
 * incomplete escaper is a real hole specifically wherever the text lands inside an HTML
 * ATTRIBUTE (e.g. `title="${name}"`) — a `"` in a team/player name that isn't escaped breaks out
 * of the attribute and can inject a new one.
 *
 * One function, escaping all 5 HTML-significant characters (&, <, >, ", '), used everywhere
 * instead. Loaded as a plain <script> before scoring-controller.html (classic-script shared
 * global scope, same pattern as game-logic.js/cloud-data.js/auth.js) so existing `esc(...)` call
 * sites keep working — see scoring-controller.html's own `const esc=escHtml;` aliases.
 */
(function (root) {
  function escHtml(s) {
    if (s == null) return "";
    return String(s)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#39;");
  }

  const api = { escHtml };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  else Object.assign(root, api);
})(typeof window !== "undefined" ? window : globalThis);
