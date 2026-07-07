/* All-Stars Live — pure cross-device merge logic, extracted from cloud-data.js so it can be unit
 * tested in isolation (no Firestore/DOM). unionGames/unionSchedule are the two functions directly
 * responsible for past real incidents: the "missing game" report (a stale filter hid an archived
 * game — see the ALL-TIME-record test in game-logic.test.js) and the schedule-sync bug (v329,
 * "created a new game and old games came back" — a whole-doc last-write-wins merge could silently
 * drop an entry the OTHER device hadn't seen yet). Both union by id instead of picking one whole
 * array, so an add on either device can never be lost to a timestamp race.
 *
 * Same UMD-ish export pattern as game-logic.js/html-safe.js — classic <script> (shared global
 * scope, loaded before cloud-data.js) or plain Node require().
 */
(function (root) {
  // Union two GAME-LOG arrays by id. Local (a) wins an id collision UNLESS only the other side
  // (b) has a box score (`.bat`) — an in-progress game synced without one shouldn't clobber a
  // completed box score that arrived from another device. Tombstoned ids are dropped from both
  // sides so a deleted game can't resurrect. Sorted newest-first, capped at 60 (matches the
  // existing "recent games" window elsewhere in the app).
  function unionGames(a, b, dead) {
    a = Array.isArray(a) ? a : []; b = Array.isArray(b) ? b : []; dead = dead || {};
    var byId = {}, order = [];
    function add(g) { if (!g || !g.id || dead[g.id]) return;
      if (!(g.id in byId)) { byId[g.id] = g; order.push(g.id); }
      else if (!byId[g.id].bat && g.bat) byId[g.id] = g; }
    a.forEach(add); b.forEach(add);
    var out = order.map(function (id) { return byId[id]; });
    out.sort(function (x, y) { var dx = x.date || "", dy = y.date || ""; return dx < dy ? 1 : dx > dy ? -1 : 0; });
    return out.slice(0, 60);
  }

  // Union two SCHEDULE arrays by id, same reasoning as unionGames. Local (a) wins an id
  // collision (no box-score-style tiebreak needed — schedule entries don't have one). Tombstoned
  // ids are dropped from both sides. Sorted soonest-first by date+time.
  function unionSchedule(a, b, dead) {
    a = Array.isArray(a) ? a : []; b = Array.isArray(b) ? b : []; dead = dead || {};
    var byId = {}, order = [];
    function add(s) { if (!s || !s.id || dead[s.id]) return;
      if (!(s.id in byId)) { byId[s.id] = s; order.push(s.id); } }
    a.forEach(add); b.forEach(add);
    var out = order.map(function (id) { return byId[id]; });
    out.sort(function (x, y) { var dx = (x.date || "") + (x.time || ""), dy = (y.date || "") + (y.time || ""); return dx < dy ? -1 : dx > dy ? 1 : 0; });
    return out;
  }

  var api = { unionGames: unionGames, unionSchedule: unionSchedule };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  else Object.assign(root, api);
})(typeof window !== "undefined" ? window : globalThis);
