/* All-Stars Live — pure cross-device merge logic, extracted from cloud-data.js so it can be unit
 * tested in isolation (no Firestore, no DOM). unionGames/unionSchedule are the two functions
 * directly responsible for past real incidents: the "missing game" report (a stale filter hid an
 * archived game — see the ALL-TIME-record test in game-logic.test.js) and the schedule-sync bug
 * (v329, "created a new game and old games came back") — a whole-doc last-write-wins merge could
 * silently drop an entry the OTHER device hadn't seen yet. Both union by id instead of picking one
 * whole array, so an add on either device can never be lost to a timestamp race.
 *
 * Same UMD-ish export pattern as game-logic.js/html-safe.js — classic <script> (shared global
 * scope, loaded before cloud-data.js) or plain Node require(). Run the tests with `npm test`.
 */
(function (root) {
  // Union two game logs by id so neither device ever loses a game it scored (the log is append-mostly).
  // On an id collision keep the RICHER copy (the one with a per-game box score). Newest-first, capped 60.
  // This is what makes the record CONVERGE across devices instead of one device's stale whole-doc push
  // clobbering games scored on another. Game-level deletes aren't tombstoned yet (additive by design).
  function unionGames(a, b, dead) {
    a = Array.isArray(a) ? a : []; b = Array.isArray(b) ? b : []; dead = dead || {};
    var byId = {}, order = [];
    function add(g) { if (!g || !g.id || dead[g.id]) return;   // skip tombstoned (deleted) games
      if (!(g.id in byId)) { byId[g.id] = g; order.push(g.id); }
      else if (!byId[g.id].bat && g.bat) byId[g.id] = g;   // keep the one with a box score
      // Both box-scored: a game re-archived after Resume/Undo (archiveGameTo() in the scorer) keeps its
      // id and stamps .at, so the later-written copy wins and the correction reaches every device.
      else if (g.bat && (g.at || 0) > (byId[g.id].at || 0)) byId[g.id] = g; }
    a.forEach(add); b.forEach(add);
    var out = order.map(function (id) { return byId[id]; });
    // One game archived twice under DIFFERENT ids (both carry the scorer's game key, .gk): a game taken
    // over on a second device before the first device's log reached it gets its own entry there.
    // Collapse each key to one entry or the game would count twice in the record and the stats forever.
    // The winner must not depend on which side is local, so every device lands on the same entry:
    // a box score beats none, then the later-written copy (.at), then the lower id.
    var byGk = {};
    out.forEach(function (g) { if (!g.gk) return; var cur = byGk[g.gk];
      if (!cur) { byGk[g.gk] = g; return; }
      var gb = g.bat ? 1 : 0, cb = cur.bat ? 1 : 0, ga = g.at || 0, ca = cur.at || 0;
      if (gb > cb || (gb === cb && (ga > ca || (ga === ca && g.id < cur.id)))) byGk[g.gk] = g; });
    out = out.filter(function (g) { return !g.gk || byGk[g.gk] === g; });
    out.sort(function (x, y) { var dx = x.date || "", dy = y.date || ""; return dx < dy ? 1 : dx > dy ? -1 : 0; });
    return out.slice(0, 60);
  }

  // Union two SCHEDULE arrays by id, same reasoning as unionGames — a schedule entry added on one
  // device must never vanish because the whole-doc merge picked the OTHER device's (older, entry-
  // missing) copy on a timestamp race. Local (a) wins an id collision — the device that just
  // added/edited the entry is presumably the one calling this. Tombstoned (deleted) ids are dropped
  // from both sides so a deleted event can't resurrect. (jford, 2026-07-03: "created a new game
  // earlier this morning but it's not in the schedule now" — root cause: schedule was a plain
  // "newer updatedAt wins the whole doc" scalar, so a stale snapshot arriving after the add's own
  // push (e.g. racing the chat-message updatedAt bump right after schedadd) could clobber it.)
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
