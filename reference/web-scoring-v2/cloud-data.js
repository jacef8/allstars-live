/* All-Stars Live — cloud team ownership + authorized scorers (Firestore).
 *
 * DORMANT until signed in (window.Cloud.user). Mirrors the local team DB (DB.teams) to
 * Firestore collection "teams", each doc owned by ownerUid with a `scorers` email list.
 * A signed-in user sees teams they OWN or are an authorized SCORER on. Reads the page's
 * globals (DB, saveDB, render, teamById) — classic scripts share the global lexical scope.
 *
 * Security is enforced server-side by Firestore rules (see firestore.rules), not here.
 */
(function () {
  // ── SANDBOX ISOLATION ── this copy shares the SAME Firebase project as production; every
  // collection name gets a "sandbox_" prefix here so nothing this copy does can ever touch a
  // real team's data. One choke point (fdb()) instead of touching every call site.
  var SANDBOX_PREFIX = "sandbox_";
  function fdb() {
    var real = window.Cloud && window.Cloud.db;
    if (!real || real.__sandboxWrapped) return real;
    var wrapped = Object.create(real);
    wrapped.__sandboxWrapped = true;
    wrapped.collection = function (name) { return real.collection(SANDBOX_PREFIX + name); };
    return wrapped;
  }
  function myUid() { return window.Cloud && window.Cloud.user && window.Cloud.user.uid; }
  function myEmail(){ return ((window.Cloud && window.Cloud.user && window.Cloud.user.email) || "").toLowerCase(); }
  var _unsub = [];
  // Stop all team onSnapshot listeners (called on sign-out so the next person on a shared
  // tablet doesn't keep receiving / seeing the previous account's teams).
  window.cloudStopSync = function () { _unsub.forEach(function (f) { try { f(); } catch (e) {} }); _unsub = []; };

  // Whitelist the fields we persist (drop any runtime junk).
  // updatedAt is the content's REAL last-changed time, carried through verbatim — NOT regenerated to
  // "now" on every push. That, plus the server rule that rejects an update whose updatedAt is older
  // than the stored one (firestore.rules), is what stops a device holding STALE data from clobbering
  // a device with newer data: stale content keeps its old timestamp and the write is rejected.
  function teamDoc(t) {
    return {
      id: t.id, name: t.name || "", short: t.short || "", color: t.color || "#2E6BE6",
      players: t.players || [], record: t.record || { w: 0, l: 0, t: 0 }, season: t.season || {}, fav: !!t.fav,
      schedule: t.schedule || [], lineup: t.lineup || null, rulesUrl: t.rulesUrl || "",
      lineups: t.lineups || [], activeLineupId: t.activeLineupId || null,   // saved batting orders
      rulebookId: t.rulebookId || null,
      league: t.league || "custom",                        // rules preset every NEW game starts from — was
                                                              // NEVER in this whitelist, so it only ever
                                                              // existed on whichever single device set it
                                                              // (jford, 2026-07-03: "team management settings
                                                              // are not the same" — set on one device, another
                                                              // device never received it, silently fell back
                                                              // to "custom" everywhere leagueById() is called)
      ownerUid: t.ownerUid || myUid(), ownerEmail: t.ownerEmail || myEmail(),
      scorers: t.scorers || [], followers: t.followers || [], coOwners: t.coOwners || [],
      members: t.members || [],                           // APPROVED members (emails) → team chat + photos
      memberRequests: t.memberRequests || [],             // pending join requests awaiting owner approval
      updatedAt: t.updatedAt || Date.now(),
      public: !!t.public, statsPublic: !!t.statsPublic,   // owner-controlled discoverability
      lastMsgAt: t.lastMsgAt || 0,                        // newest chat ts → powers home unread badge
      games: t.games || [],                               // finished-game log (Recent games list)
      deletedGames: t.deletedGames || [],                 // tombstoned game ids → additive merge won't resurrect a deleted game
      deletedSchedule: t.deletedSchedule || [],           // tombstoned schedule-entry ids → additive merge won't resurrect a deleted event
      logo: t.logo || "",                                 // team logo data URL (downscaled ≤240px)
      archived: !!t.archived,                             // season ended → hidden from Home (still in Teams)
    };
  }

  // ===== Content-change tracking — so updatedAt only advances on a REAL edit, never just because a
  // team was open or re-saved. _sig[id] holds the last known-synced CONTENT signature (the doc minus
  // its updatedAt). We only bump updatedAt → "now" when the current content differs from that
  // baseline, and we only push changed teams. Unchanged/stale content keeps its old timestamp, so it
  // can't win the merge or the server's updatedAt guard. =====
  var _sig = {};
  function teamSig(t) { try { var d = teamDoc(t); delete d.updatedAt; return JSON.stringify(d); } catch (e) { return ""; } }
  function markClean(t) { if (t && t.id) _sig[t.id] = teamSig(t); }   // baseline = current content
  // Union two game logs by id so neither device ever loses a game it scored (the log is append-mostly).
  // On an id collision keep the RICHER copy (the one with a per-game box score). Newest-first, capped 60.
  // This is what makes the record CONVERGE across devices instead of one device's stale whole-doc push
  // clobbering games scored on another. Game-level deletes aren't tombstoned yet (additive by design).
  function unionGames(a, b, dead) {
    a = Array.isArray(a) ? a : []; b = Array.isArray(b) ? b : []; dead = dead || {};
    var byId = {}, order = [];
    function add(g) { if (!g || !g.id || dead[g.id]) return;   // skip tombstoned (deleted) games
      if (!(g.id in byId)) { byId[g.id] = g; order.push(g.id); }
      else if (!byId[g.id].bat && g.bat) byId[g.id] = g; }   // keep the one with a box score
    a.forEach(add); b.forEach(add);
    var out = order.map(function (id) { return byId[id]; });
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
  // Called from saveDB (NOT while applying an inbound snapshot): bump updatedAt for any team whose
  // content actually changed vs its synced baseline. Teams with no baseline yet (never synced this
  // session) are left alone — we don't know they changed, so we must not stamp them "now" and risk
  // clobbering a newer cloud copy on first sign-in.
  window.cloudTouchChanged = function () {
    try {
      (DB.teams || []).forEach(function (t) {
        if (!t || !t.id) return;
        if (_sig[t.id] !== undefined && _sig[t.id] !== teamSig(t)) t.updatedAt = Date.now();
      });
    } catch (e) {}
  };

  // Search the public team directory. Returns lightweight cards (no full season detail).
  // Firestore can't substring-search, so we pull public teams and filter by name client-side.
  window.cloudSearchTeams = function (qtext) {
    var d = fdb(); if (!d) return Promise.resolve([]);
    var q = (qtext || "").trim().toLowerCase();
    return d.collection("teams").where("public", "==", true).limit(100).get()
      .then(function (snap) {
        var out = [];
        snap.forEach(function (doc) {
          var c = doc.data(); if (!c || !c.id) return;
          if (q && (c.name || "").toLowerCase().indexOf(q) < 0 && (c.short || "").toLowerCase().indexOf(q) < 0) return;
          out.push({ id: c.id, name: c.name || "Team", short: c.short || "", color: c.color || "#2E6BE6",
            players: (c.players || []).length, record: c.record || { w: 0, l: 0, t: 0 }, statsPublic: !!c.statsPublic });
        });
        return out;
      })
      .catch(function (e) { console.warn("cloudSearchTeams:", e.message); return []; });
  };

  // Fetch one public team's FULL doc (roster + record + season) for the stats viewer.
  // Returns null if it isn't public (rules also enforce this server-side).
  window.cloudGetTeam = function (id) {
    var d = fdb(); if (!d || !id) return Promise.resolve(null);
    return d.collection("teams").doc(id).get()
      .then(function (doc) {
        if (!doc.exists) return null;
        var c = doc.data(); if (!c || !c.public) return null;
        return c;
      })
      .catch(function (e) { console.warn("cloudGetTeam:", e.message); return null; });
  };

  // Auto-refresh "followed" public teams (cached locally with external:true). Re-pulls each one's
  // current public doc and updates the local snapshot, so a watched team's record/roster/games
  // stay fresh on Home. Runs on sign-in, on app focus, and every few minutes.
  window.cloudRefreshFollowed = function () {
    var d = fdb(); if (!d) return;
    var ext = (DB.teams || []).filter(function (t) { return t && t.external && t.id; });
    if (!ext.length) return;
    var changed = false, pending = ext.length;
    ext.forEach(function (t) {
      d.collection("teams").doc(t.id).get().then(function (doc) {
        if (doc.exists) {
          var c = doc.data();
          if (c && c.public) {
            var i = (DB.teams || []).findIndex(function (x) { return x.id === t.id; });
            if (i >= 0) {
              var merged = Object.assign({}, c, { external: true, fav: DB.teams[i].fav !== false });
              if (JSON.stringify(merged) !== JSON.stringify(DB.teams[i])) { DB.teams[i] = merged; changed = true; }
            }
          }
        }
      }).catch(function () {}).finally(function () {
        if (--pending === 0 && changed) { try { saveDB(); } catch (e) {} try { if (typeof render === "function") render(); } catch (e) {} }
      });
    });
  };

  // Push one team up (claims ownership if it has none / is mine). `force` skips the unchanged-content
  // short-circuit (used by Sync now + sign-in, so a team the cloud doesn't have yet still uploads even
  // when its content matches our baseline).
  window.cloudSaveTeam = function (t, force) {
    var d = fdb(), u = myUid();
    if (!d || !u || !t || !t.id) return;
    if (t.ownerUid && t.ownerUid !== u && (t.scorers || []).indexOf(myEmail()) < 0 && (t.coOwners || []).indexOf(myEmail()) < 0) return; // not mine
    if (!force && _sig[t.id] !== undefined && _sig[t.id] === teamSig(t)) return;   // nothing changed → don't re-stamp/re-push
    d.collection("teams").doc(t.id).set(teamDoc(t), { merge: true })
      .then(function () {
        window._lastCloudPush = Date.now(); markClean(t);
        if (window.netLog && window._teamSyncWasFailing) window.netLog("Team sync: recovered, \"" + (t.name || t.id) + "\" pushed");
        window._teamSyncWasFailing = false;
        t._syncRetries = 0;
      })
      .catch(function (e) {
        if (window.netLog && !window._teamSyncWasFailing) window.netLog("Team sync FAILED for \"" + (t.name || t.id) + "\": " + e.message);
        window._teamSyncWasFailing = true;
        console.warn("cloudSaveTeam:", e.message);
        // Multiple devices signed into the SAME account (e.g. a tablet scoring + a phone watching,
        // both with this team loaded) can each independently retry-push this team doc. The server
        // rule rejects any write whose updatedAt looks behind what's already stored (the stale-write
        // guard) — but our own retries kept resending the SAME frozen updatedAt forever if this
        // team's CONTENT wasn't otherwise changing, so once another device's write moved the stored
        // value ahead, ours could never catch up and just failed on repeat, indefinitely. (jford,
        // 2026-07-03: ran a live test with the tablet scoring + phone watching simultaneously — the
        // team record never reached either the phone or the PC, "Missing or insufficient permissions"
        // repeating every ~1.5s in logcat the whole time.) Bumping updatedAt here doesn't affect the
        // dirty-tracking signature (teamSig() strips updatedAt before hashing) — it just guarantees
        // the NEXT retry carries a genuinely newer timestamp than this failed one, so it can actually
        // win the race instead of retrying the identical losing timestamp forever.
        if (e && e.code === "permission-denied" && t) {
          t.updatedAt = Date.now();
          // But nothing was actually SCHEDULING that "next retry" — it only happened if some other
          // edit later called saveDB() again. (jford, 2026-07-04: tablet's own Diagnostics log showed
          // the whole story — a game ended, the auth state flickered signed-out/in twice within two
          // seconds, the team push landed in that gap and got permission-denied, and then NOTHING
          // retried it until "Refresh app" was tapped minutes later. The phone sat showing the
          // pre-game record the entire time, with no error visible anywhere on either device.) A
          // permission-denied here is almost always that kind of momentary hiccup, not a real rules
          // problem, so self-heal with a short bounded backoff instead of waiting on the user.
          t._syncRetries = (t._syncRetries || 0) + 1;
          if (t._syncRetries <= 5) {
            var delay = Math.min(30000, 2000 * Math.pow(2, t._syncRetries - 1));
            setTimeout(function () { try { window.cloudSaveTeam(t); } catch (e2) {} }, delay);
          }
        }
      });
  };

  // Force an immediate push of every team I can write (bypasses the debounce) — wired to the
  // "Sync now" button on the Diagnostics page. Bump-changed-then-force so genuinely edited teams carry
  // a fresh updatedAt while unchanged ones still carry their real (older) one (server arbitrates).
  window.cloudSyncNow = function () {
    // PULL the latest and converge. cloudSyncTeams re-subscribes (re-running the team merge, which UNIONS
    // game logs so nothing is lost) and uploads only teams the cloud is MISSING. We deliberately do NOT
    // blanket force-push every local team anymore — THAT is what let a device with a stale record shove its
    // old copy over everyone else's when the user hit "Sync now".
    try { window.cloudSyncTeams(); } catch (e) {}
    try { window.cloudRefreshFollowed(); } catch (e) {}
  };

  // ===== Per-user UI preferences (e.g. floating scorer-window layout) — synced so a user's setup
  // follows them across devices and into their next game. Stored at userPrefs/{uid}. =====
  var _prefsTimer = null, _pendingPrefs = null;
  window.cloudSavePrefs = function (prefs) {
    // Accumulate fields across calls (e.g. a follow change + a float-layout change within the debounce
    // window) so the later call can't drop the earlier one. Cleared after each write.
    _pendingPrefs = Object.assign(_pendingPrefs || {}, prefs || {});
    var d = fdb(), u = myUid(); if (!d || !u) return;
    if (_prefsTimer) clearTimeout(_prefsTimer);
    _prefsTimer = setTimeout(function () {
      var payload = _pendingPrefs || {}; _pendingPrefs = null;
      try { d.collection("userPrefs").doc(u).set(payload, { merge: true }).catch(function () {}); } catch (e) {}
    }, 900);
  };
  window.cloudLoadPrefs = function () {
    var d = fdb(), u = myUid(); if (!d || !u) return Promise.resolve(null);
    return d.collection("userPrefs").doc(u).get()
      .then(function (doc) { return doc.exists ? doc.data() : null; })
      .catch(function () { return null; });
  };

  // Debounced push of all my teams (called from saveDB on any team edit).
  var _pt;
  window.cloudPushSoon = function () {
    if (!fdb() || !myUid()) return;
    if (window._cloudApplying) return;   // we're applying an inbound snapshot — don't echo it back (no write-loop)
    clearTimeout(_pt);
    _pt = setTimeout(function () { try { (DB.teams || []).forEach(function (t) { window.cloudSaveTeam(t); }); } catch (e) {} }, 800);
  };

  // Owner adds/removes an authorized scorer by email.
  window.cloudAddScorer = function (teamId, email) {
    var d = fdb(); email = (email || "").trim().toLowerCase();
    if (!d || !teamId || !email || email.indexOf("@") < 0) return Promise.resolve();
    return d.collection("teams").doc(teamId)
      .update({ scorers: firebase.firestore.FieldValue.arrayUnion(email) })
      .catch(function (e) { try { alert("Couldn't add scorer: " + e.message); } catch (x) {} });
  };
  // Tombstones: ids the user deleted. merge() never re-adds these, so a deleted team can't come
  // back even if the cloud delete is slow/denied or another device still has it.
  function deletedSet() { try { return JSON.parse(localStorage.getItem("al-deleted-teams") || "[]"); } catch (e) { return []; } }
  function tombstone(id) { try { var s = deletedSet(); if (s.indexOf(id) < 0) { s.push(id); localStorage.setItem("al-deleted-teams", JSON.stringify(s)); } } catch (e) {} }

  // Delete the team: tombstone it locally (so sync won't resurrect it) + delete the cloud doc.
  window.cloudDeleteTeam = function (teamId) {
    if (!teamId) return Promise.resolve();
    tombstone(teamId);
    var d = fdb(); if (!d) return Promise.resolve();
    return d.collection("teams").doc(teamId).delete()
      .catch(function (e) { console.warn("cloudDeleteTeam:", e.message); });
  };
  window.cloudRemoveScorer = function (teamId, email) {
    var d = fdb(); if (!d || !teamId) return Promise.resolve();
    return d.collection("teams").doc(teamId)
      .update({ scorers: firebase.firestore.FieldValue.arrayRemove((email || "").toLowerCase()) })
      .catch(function (e) { console.warn("cloudRemoveScorer:", e.message); });
  };

  // Owner adds/removes a CO-OWNER by email (full management access — like a second owner,
  // but can't delete the team or manage co-owners). Rules let the primary owner edit coOwners.
  window.cloudAddCoOwner = function (teamId, email) {
    var d = fdb(); email = (email || "").trim().toLowerCase();
    if (!d || !teamId || !email || email.indexOf("@") < 0) return Promise.resolve();
    return d.collection("teams").doc(teamId)
      .update({ coOwners: firebase.firestore.FieldValue.arrayUnion(email), updatedAt: Date.now() })
      .catch(function (e) { try { alert("Couldn't add co-owner: " + e.message); } catch (x) {} });
  };
  window.cloudRemoveCoOwner = function (teamId, email) {
    var d = fdb(); if (!d || !teamId) return Promise.resolve();
    return d.collection("teams").doc(teamId)
      .update({ coOwners: firebase.firestore.FieldValue.arrayRemove((email || "").toLowerCase()), updatedAt: Date.now() })
      .catch(function (e) { console.warn("cloudRemoveCoOwner:", e.message); });
  };

  // On sign-in: claim ownership of un-owned local teams, then live-subscribe. We do NOT blanket-push
  // OR blanket-markClean every local team here — a device that loaded STALE data must not overwrite
  // newer cloud copies on sign-in, and a team whose last push genuinely never reached the cloud must
  // not get silently laundered into looking already-synced. Instead we wait for the real onSnapshot:
  // uploadMissing() force-pushes teams the cloud doesn't have at all, and merge() below only
  // markClean()s a team once it has ACTUALLY compared local vs cloud timestamps and confirmed they
  // agree (cTs >= lTs, identical content) — never just because this is the first time this device-
  // session has looked at it.
  //
  // v349, jford 2026-07-03: this function used to blanket-markClean every team upfront ("existing
  // data isn't a fresh edit"), meant only for a brand-new device's very first sign-in. But it ALSO ran
  // on every "Refresh app" tap and every subsequent sign-in, unconditionally re-baselining ALL teams
  // to their current content — including a team whose actual cloudSaveTeam() push had genuinely
  // failed (e.g. a transient network blip right at End Game). That silently erased the "still needs
  // to push" signal before the normal debounced push ever got a chance to retry it — and since _sig
  // is in-memory only, this re-happened on every fresh reload too, not just once. Symptom: a finished
  // game with a real win stayed local-only on the tablet — every OTHER device stuck at 0-2 — through
  // repeated sign-out/in and Refresh app taps, because each one silently "confirmed" a push that had
  // never actually gone through. Removed entirely; a team that's genuinely behind now keeps retrying
  // via the normal debounced push (cloudPushSoon, fired by any saveDB) until merge() honestly confirms
  // the cloud has caught up. Worst case for an already-in-sync team is one harmless redundant write
  // right at sign-in (a no-op merge, or rejected outright by the server's stale-updatedAt rule).
  window.cloudSyncTeams = function () {
    var d = fdb(), u = myUid(), em = myEmail();
    if (!d || !u) return;
    var _didInitialUpload = false;
    try {
      (DB.teams || []).forEach(function (t) { if (!t.ownerUid) { t.ownerUid = u; t.ownerEmail = em; t.updatedAt = Date.now(); } });
      saveDB();
    } catch (e) {}
    // After the cloud tells us which of my teams it already has, push up only the ones it's missing.
    function uploadMissing(cloudIds) {
      if (_didInitialUpload) return; _didInitialUpload = true;
      try {
        (DB.teams || []).forEach(function (t) {
          if (!t || !t.id) return;
          var mine = (!t.ownerUid || t.ownerUid === u);
          if (mine && cloudIds.indexOf(t.id) < 0) { if (!t.updatedAt) t.updatedAt = Date.now(); window.cloudSaveTeam(t, true); }
        });
      } catch (e) {}
    }

    _unsub.forEach(function (f) { try { f(); } catch (e) {} });
    _unsub = [];
    // Re-render for a cloud change ONLY when the user isn't interacting — a snapshot landing
    // mid-tap would rebuild the DOM and instantly close a native <select>/date dropdown (phones).
    function cloudRenderSoon() {
      if (window._cloudRT) return;
      window._cloudRT = setTimeout(function tick() {
        if (typeof uiBusy === "function" && uiBusy()) { window._cloudRT = setTimeout(tick, 600); return; }
        window._cloudRT = null; try { render(); } catch (e) {}
      }, 350);
    }
    function sansTimestamp(o) { var x = Object.assign({}, o); delete x.updatedAt; return JSON.stringify(x); }
    function merge(docs) {
      window._lastCloudPull = Date.now();
      var changed = false, convergedIds = {};
      var dead = deletedSet();
      docs.forEach(function (doc) {
        var c = doc.data(); if (!c || !c.id) return;
        if (dead.indexOf(c.id) >= 0) return;   // user deleted this team — never re-add it
        var i = (DB.teams || []).findIndex(function (x) { return x.id === c.id; });
        if (i < 0) { DB.teams.push(c); markClean(c); changed = true; return; }
        var loc = DB.teams[i];
        var lTs = loc.updatedAt || 0, cTs = c.updatedAt || 0;
        if (loc.external) {
          // Followed team — we don't edit it; take the cloud copy when newer (refreshFollowed also pulls it).
          if (cTs > lTs) { var m = Object.assign({}, loc, c, { external: true });
            if (sansTimestamp(m) !== sansTimestamp(loc)) { DB.teams[i] = m; changed = true; } }
          markClean(DB.teams[i]); return;
        }
        // OUR team (owned / scorer / co-owner): CONVERGE instead of whole-doc last-write-wins.
        //  • the game LOG and the SCHEDULE are both additive — unioned by id, so neither device ever
        //    loses a game it scored OR an event it added (this is the real fix for "the record never
        //    updates across devices" and for a just-added schedule entry vanishing on a timestamp race);
        //  • other scalar config (name/color/players/archived…) follows the newer timestamp;
        //  • the RECORD is DERIVED from the unioned log, so it can't disagree with the games.
        // Merge the game/schedule tombstones (union — a delete on EITHER device wins and propagates via
        // the doc), and exclude tombstoned ids from the unioned lists so a deleted item can't resurrect.
        var deadG = {};
        (loc.deletedGames || []).forEach(function (id) { deadG[id] = 1; });
        (c.deletedGames || []).forEach(function (id) { deadG[id] = 1; });
        var unioned = unionGames(loc.games, c.games, deadG);
        var gamesGrew = unioned.length !== ((loc.games || []).length);
        var deadS = {};
        (loc.deletedSchedule || []).forEach(function (id) { deadS[id] = 1; });
        (c.deletedSchedule || []).forEach(function (id) { deadS[id] = 1; });
        var unionedSched = unionSchedule(loc.schedule, c.schedule, deadS);
        var schedGrew = unionedSched.length !== ((loc.schedule || []).length);
        var next = (cTs > lTs) ? Object.assign({}, loc, c) : Object.assign({}, loc);   // local scalars stay if we're newer
        next.games = unioned;
        next.deletedGames = Object.keys(deadG).slice(-200);   // carry the merged tombstone set
        next.schedule = unionedSched;
        next.deletedSchedule = Object.keys(deadS).slice(-200);
        try { if (typeof teamRec === "function") next.record = teamRec(next); } catch (e) {}
        if (sansTimestamp(next) !== sansTimestamp(loc)) {
          if (gamesGrew || schedGrew || cTs > lTs) next.updatedAt = Math.max(lTs, cTs) + 1;   // monotonic bump → the converged doc re-pushes & wins
          DB.teams[i] = next; changed = true; convergedIds[next.id] = true;
        } else if (cTs >= lTs) {
          markClean(DB.teams[i]);   // identical & cloud not behind → in sync, don't echo back
          // cTs < lTs with identical content: keep the baseline dirty so an un-pushed local edit still uploads.
        }
      });
      // A just-accepted texted invite: jump to that team's management page + favorite it (follow).
      if (window._joinTeamId) {
        var jt = (DB.teams || []).find(function (x) { return x.id === window._joinTeamId; });
        if (jt) {
          window._joinTeamId = null;
          if (!jt.fav) jt.fav = true;
          try { openTeamId = jt.id; mode = "team"; } catch (e) {}
          try { saveDB(); } catch (e) {}   // persist + push the favorite
          try { render(); } catch (e) {}
          return;
        }
      }
      if (!changed) return;
      window._cloudApplying = true;
      try {
        // Recompute season stats from the (now unioned) game log for any team whose games changed, then
        // re-baseline so we don't needlessly echo. Runs under _cloudApplying so its saveDB doesn't push.
        Object.keys(convergedIds).forEach(function (id) {
          var t = (DB.teams || []).find(function (x) { return x.id === id; });
          if (t) { try { if (typeof recomputeSeasonFromGames === "function") recomputeSeasonFromGames(t); } catch (e) {} markClean(t); }
        });
        saveDB();   // persist locally WITHOUT re-pushing (guarded)
      } catch (e) {}
      window._cloudApplying = false;
      // Push the converged docs so the cloud + every other device adopt the unioned log (idempotent once settled).
      try { Object.keys(convergedIds).forEach(function (id) { var t = (DB.teams || []).find(function (x) { return x.id === id; }); if (t && window.cloudSaveTeam) window.cloudSaveTeam(t, true); }); } catch (e) {}
      cloudRenderSoon();               // deferred + interaction-aware (won't kill an open dropdown)
    }
    _unsub.push(d.collection("teams").where("ownerUid", "==", u)
      .onSnapshot(function (s) { merge(s.docs); uploadMissing(s.docs.map(function (x) { return x.id; })); },
                  function (e) { console.warn("teams(owned):", e.message); }));
    if (em) _unsub.push(d.collection("teams").where("scorers", "array-contains", em)
      .onSnapshot(function (s) { merge(s.docs); }, function (e) { console.warn("teams(scorer):", e.message); }));
    if (em) _unsub.push(d.collection("teams").where("followers", "array-contains", em)
      .onSnapshot(function (s) { merge(s.docs); }, function (e) { console.warn("teams(follower):", e.message); }));
    if (em) _unsub.push(d.collection("teams").where("coOwners", "array-contains", em)
      .onSnapshot(function (s) { merge(s.docs); }, function (e) { console.warn("teams(coOwner):", e.message); }));
    // Live-sync this user's personal prefs (followed players + floating-window layout) across every
    // device they're signed into. applyCloudPrefs does the last-write-wins merge into local state;
    // cloudRenderSoon repaints without disrupting an active interaction.
    _unsub.push(d.collection("userPrefs").doc(u)
      .onSnapshot(function (doc) { if (doc.exists && typeof window.applyCloudPrefs === "function") { try { window.applyCloudPrefs(doc.data()); } catch (e) {} cloudRenderSoon(); } },
                  function (e) { console.warn("userPrefs:", e.message); }));
    // Followed public teams aren't covered by the member queries above — refresh them on sign-in,
    // when the app regains focus, and on a slow timer.
    try { window.cloudRefreshFollowed(); } catch (e) {}
    if (!window._followRefreshHooked) {
      window._followRefreshHooked = true;
      try { document.addEventListener("visibilitychange", function () { if (!document.hidden) { try { window.cloudRefreshFollowed(); } catch (e) {} } }); } catch (e) {}
      try { setInterval(function () { try { window.cloudRefreshFollowed(); } catch (e) {} }, 5 * 60 * 1000); } catch (e) {}
    }
  };

  // Membership helpers (used to gate the chat composer + game scoring on the web).
  window.cloudIsCoOwner = function (t) {
    var em = myEmail(); return !!(t && Array.isArray(t.coOwners) && em && t.coOwners.indexOf(em) >= 0);
  };
  // Full management access: the primary owner OR a co-owner. Gates rulebook/scorers/discovery
  // edits. (Deleting the team + adding/removing co-owners stays primary-owner-only — cloudIsOwner.)
  window.cloudCanManage = function (t) {
    return !!(t && (window.cloudIsOwner(t) || window.cloudIsCoOwner(t)));
  };
  window.cloudIsScorer = function (t) {
    var em = myEmail(); return !!(t && (window.cloudIsOwner(t) || window.cloudIsCoOwner(t) || (Array.isArray(t.scorers) && t.scorers.indexOf(em) >= 0)));
  };
  // MEMBER = owner / co-owner / scorer / approved member. Members get team chat + photos + full updates.
  // (Used to include any follower; now followers are casual fans — scores/stats/viewer only — and a
  // parent must be APPROVED into `members` for the private team info.)
  window.cloudIsMember = function (t) {
    var em = myEmail(); return !!(t && (window.cloudIsScorer(t) || (Array.isArray(t.members) && t.members.indexOf(em) >= 0)));
  };
  window.cloudIsPendingMember = function (t) {
    var em = myEmail(); return !!(t && Array.isArray(t.memberRequests) && t.memberRequests.indexOf(em) >= 0);
  };
  // A signed-in fan asks to become a member (rules allow appending ONLY your own email to memberRequests).
  window.cloudRequestMembership = function (teamId) {
    var d = fdb(), em = myEmail(); if (!d || !em || !teamId) return Promise.resolve(false);
    return d.collection("teams").doc(teamId)
      .update({ memberRequests: firebase.firestore.FieldValue.arrayUnion(em), updatedAt: Date.now() })
      .then(function () { ctoast("Request sent — the team owner will approve you."); return true; })
      .catch(function (e) { ctoast("Couldn't send request: " + e.message); return false; });
  };
  // Owner / co-owner approves: move the email from memberRequests → members.
  window.cloudApproveMember = function (teamId, email) {
    var d = fdb(); email = (email || "").toLowerCase(); if (!d || !teamId || !email) return Promise.resolve();
    return d.collection("teams").doc(teamId).update({
      members: firebase.firestore.FieldValue.arrayUnion(email),
      memberRequests: firebase.firestore.FieldValue.arrayRemove(email), updatedAt: Date.now()
    }).catch(function (e) { ctoast("Couldn't approve: " + e.message); });
  };
  window.cloudDenyMember = function (teamId, email) {
    var d = fdb(); email = (email || "").toLowerCase(); if (!d || !teamId || !email) return Promise.resolve();
    return d.collection("teams").doc(teamId)
      .update({ memberRequests: firebase.firestore.FieldValue.arrayRemove(email), updatedAt: Date.now() })
      .catch(function (e) { ctoast("Couldn't deny: " + e.message); });
  };
  window.cloudRemoveMember = function (teamId, email) {
    var d = fdb(); email = (email || "").toLowerCase(); if (!d || !teamId || !email) return Promise.resolve();
    return d.collection("teams").doc(teamId)
      .update({ members: firebase.firestore.FieldValue.arrayRemove(email), updatedAt: Date.now() })
      .catch(function (e) { ctoast("Couldn't remove member: " + e.message); });
  };

  // Follow a team as a FAN (scores / stats / live viewer) — NOT a member, NOT a scorer. ?follow=<teamId>.
  window.cloudFollowTeam = function (teamId) {
    var d = fdb(), em = myEmail(); if (!d || !em || !teamId) return Promise.resolve();
    return d.collection("teams").doc(teamId)
      .update({ followers: firebase.firestore.FieldValue.arrayUnion(em), updatedAt: Date.now() })
      .then(function () { window._joinTeamId = teamId; ctoast("You're now following this team's scores."); })
      .catch(function (e) { ctoast("Couldn't follow team: " + e.message); });
  };
  window.cloudClaimFollow = function () {
    var d = fdb(), em = myEmail(); if (!d || !em) return;
    var tid; try { tid = new URLSearchParams(location.search).get("follow"); } catch (e) {}
    if (!tid) return;
    window.cloudFollowTeam(tid).finally(function () {
      try { var u = new URL(location.href); u.searchParams.delete("follow"); history.replaceState(null, "", u.pathname + u.search); } catch (e) {}
    });
  };

  /* ===== Team chat (messages subcollection) — owner/scorers/followers can read+post; owner deletes ===== */
  window.cloudSubscribeMessages = function (teamId, cb) {
    var d = fdb(); if (!d || !teamId) return function () {};
    try {
      return d.collection("teams").doc(teamId).collection("messages").orderBy("ts", "asc").limitToLast(200)
        .onSnapshot(function (s) { var out = []; s.forEach(function (m) { out.push(Object.assign({ id: m.id }, m.data())); }); try { cb(out); } catch (e) {} },
                    function (e) { console.warn("messages:", e.message); try { cb(null, e); } catch (x) {} });
    } catch (e) { return function () {}; }
  };
  window.cloudSendMessage = function (teamId, text) {
    var d = fdb(), u = myUid(); text = (text || "").trim();
    if (!d || !u || !teamId || !text) return Promise.resolve();
    return d.collection("teams").doc(teamId).collection("messages").add({
      text: text.slice(0, 2000), authorUid: u, authorName: (window.cloudMyName ? window.cloudMyName() : myEmail()) || "Someone",
      authorEmail: myEmail(), ts: Date.now(),
    }).then(function () {
      // Stamp the team doc so other members' home screens can show an unread badge without
      // each subscribing to the whole messages subcollection. (Rules let any member bump
      // ONLY lastMsgAt/updatedAt.) Best-effort — a failure here never blocks the message.
      try { d.collection("teams").doc(teamId).update({ lastMsgAt: Date.now(), updatedAt: Date.now() }).catch(function () {}); } catch (e) {}
    }).catch(function (e) { ctoast("Couldn't send: " + e.message); });
  };
  /* ===== Team photo gallery =====
   * Image BYTES live in Cloud Storage (teams/{id}/photos/{photoId}.jpg); a small Firestore doc
   * (teams/{id}/photos/{photoId}) holds the download URL + metadata. Members read/add; author or
   * owner deletes. Each photo carries expireAt (1-year retention) — cloudPrunePhotos() deletes
   * the expired ones (both the Storage object and the Firestore doc). Old inline-base64 photos
   * (field `img`) still render via `img`. */
  var PHOTO_RETENTION_MS = 365 * 24 * 60 * 60 * 1000;   // 1 year
  window.cloudGetPhotos = function (teamId) {
    var d = fdb(); if (!d || !teamId) return Promise.resolve([]);
    return d.collection("teams").doc(teamId).collection("photos").orderBy("ts", "desc").limit(300).get()
      .then(function (s) { var out = []; s.forEach(function (m) { out.push(Object.assign({ id: m.id }, m.data())); }); return out; })
      .catch(function (e) { console.warn("getPhotos:", e.message); return null; });
  };
  window.cloudAddPhoto = function (teamId, dataUrl, caption) {
    var d = fdb(), u = myUid(), st = window.Cloud && window.Cloud.storage;
    if (!d || !u || !teamId || !dataUrl) return Promise.resolve();
    var meta = function (extra) {
      return Object.assign({ caption: (caption || "").slice(0, 200), authorUid: u,
        authorName: (window.cloudMyName ? window.cloudMyName() : myEmail()) || "Someone",
        ts: Date.now(), expireAt: Date.now() + PHOTO_RETENTION_MS }, extra);
    };
    var col = d.collection("teams").doc(teamId).collection("photos");
    if (!st) {   // Storage SDK missing → fall back to inline base64 in Firestore (old behavior)
      return col.add(meta({ img: dataUrl })).catch(function (e) { ctoast("Couldn't add photo: " + e.message); });
    }
    var id = col.doc().id, path = "teams/" + teamId + "/photos/" + id + ".jpg", ref = st.ref(path);
    return ref.putString(dataUrl, "data_url").then(function () { return ref.getDownloadURL(); })
      .then(function (url) { return col.doc(id).set(meta({ url: url, path: path })); })
      .catch(function (e) { ctoast("Couldn't add photo: " + (e && e.message || e)); });
  };
  window.cloudDeletePhoto = function (teamId, photoId) {
    var d = fdb(), st = window.Cloud && window.Cloud.storage; if (!d || !teamId || !photoId) return Promise.resolve();
    var ref = d.collection("teams").doc(teamId).collection("photos").doc(photoId);
    return ref.get().then(function (doc) {
      var p = doc.exists ? doc.data().path : null;
      return ref.delete().then(function () { if (p && st) { return st.ref(p).delete().catch(function () {}); } });
    }).catch(function (e) { ctoast("Couldn't delete: " + e.message); });
  };
  // Delete photos past their retention (expireAt < now). Best-effort, called when the gallery opens.
  window.cloudPrunePhotos = function (teamId) {
    var d = fdb(); if (!d || !teamId) return Promise.resolve();
    return d.collection("teams").doc(teamId).collection("photos").where("expireAt", "<", Date.now()).limit(50).get()
      .then(function (s) { var ps = []; s.forEach(function (m) { ps.push(window.cloudDeletePhoto(teamId, m.id)); }); return Promise.all(ps); })
      .catch(function () {});
  };

  window.cloudDeleteMessage = function (teamId, msgId) {
    var d = fdb(); if (!d || !teamId || !msgId) return Promise.resolve();
    return d.collection("teams").doc(teamId).collection("messages").doc(msgId).delete()
      .catch(function (e) { ctoast("Couldn't delete: " + e.message); });
  };

  /* ===== Persistent cloud game — owner/scorer writes games/{id}; anyone with the link reads (watch) ===== */
  var _gp;
  // Publish the live game. `meta` carries cross-device coordination fields: who's the ACTIVE scorer
  // (activeUid/activeName/activeAt — last writer wins, drives the one-scorer-at-a-time handoff),
  // whether the game is final, and a compact summary so the home screen can show "in progress"
  // without reading the whole state.
  window.cloudPublishGame = function (id, payload, meta) {
    var d = fdb(), u = myUid(); if (!d || !u || !id) return;
    if (window._cloudApplying) return;
    meta = meta || {};
    clearTimeout(_gp);
    _gp = setTimeout(function () {
      var doc = {
        id: id, teamId: payload.teamId || "", ownerUid: u,
        state: payload, updatedAt: Date.now(),
        final: !!meta.final,
        summary: meta.summary || null,
      };
      if (meta.activeUid) { doc.activeUid = meta.activeUid; doc.activeName = meta.activeName || ""; doc.activeAt = Date.now(); }
      // Track success/failure so a silent, invisible publish failure (e.g. a transient signed-out
      // moment) is diagnosable afterward instead of just "viewers saw nothing, no one knows why" —
      // jford, 2026-07-03: scored a whole game live, a same-account viewer on another device never
      // saw a single play update, and there was no way after the fact to tell whether the publish was
      // ever even attempted. Surfaced on the Diagnostics page as "Live publish".
      d.collection("games").doc(id).set(doc, { merge: true })
        .then(function () {
          window._lastLivePublishOk = Date.now();
          // Log only the transition (not every ~1s publish while scoring) — a recovery after a prior
          // failure is exactly the moment worth recording.
          if (window.netLog && window._livePublishWasFailing) window.netLog("Live publish: recovered, now reaching Firestore again");
          window._livePublishWasFailing = false;
        })
        .catch(function (e) {
          window._lastLivePublishErr = { at: Date.now(), msg: e.message };
          if (window.netLog && !window._livePublishWasFailing) window.netLog("Live publish FAILED: " + e.message);
          window._livePublishWasFailing = true;
          console.warn("cloudPublishGame:", e.message);
        });
    }, 1000);
  };
  // Cancel a pending debounced publish (used when relinquishing scoring, so a stale write can't
  // re-claim the active slot after another device took over).
  window.cloudCancelPublish = function () { clearTimeout(_gp); };
  // Mark a live game ENDED, IMMEDIATELY (not debounced) — so other devices' Home stops showing it
  // "in progress" even if this device sleeps/backgrounds right after End game. Cancels any pending
  // debounced publish first so it can't re-open the game, and clears the active-scorer slot.
  window.cloudEndGame = function (id) {
    var d = fdb(), u = myUid(); if (!d || !u || !id) return Promise.resolve();
    clearTimeout(_gp);
    return d.collection("games").doc(id)
      .set({ final: true, activeUid: null, updatedAt: Date.now() }, { merge: true })
      .catch(function (e) { console.warn("cloudEndGame:", e.message); });
  };
  // Claim the ACTIVE-scorer slot immediately (take-over) — not debounced, so the handoff is instant.
  window.cloudClaimScoring = function (id, name) {
    var d = fdb(), u = myUid(); if (!d || !u || !id) return Promise.resolve();
    return d.collection("games").doc(id)
      .set({ activeUid: u, activeName: name || "", activeAt: Date.now() }, { merge: true })
      .catch(function (e) { console.warn("cloudClaimScoring:", e.message); });
  };
  window.cloudSubscribeGame = function (id, cb) {
    var d = fdb(); if (!d || !id) return function () {};
    try {
      return d.collection("games").doc(id).onSnapshot(function (s) {
        var v = s.exists ? s.data() : null;
        // cloudEndGame() marks a finished game IMMEDIATELY via the doc's own top-level `final` field
        // (not debounced, so a viewer sees it right away even if the scorer's device sleeps right
        // after End Game) — but that's separate from `state.G.final`, which only updates on the next
        // regular (debounced) broadcast() and wasn't being merged in here at all. A Watch viewer was
        // stuck reading nothing but the last-received `state`, with no way to ever learn the game had
        // actually ended. (jford, 2026-07-03: ended a game on the tablet; the phone's viewer screen
        // just kept sitting there as if it was still live.) Stamp the doc's own final flag onto the
        // state we hand back, so it's authoritative regardless of whether state.G itself caught up.
        var out = v && v.state ? v.state : null;
        if (out && v && v.final) { out = Object.assign({}, out); out.G = Object.assign({}, out.G, { final: true }); }
        try { cb(out); } catch (e) {}
      }, function (e) { console.warn("game:", e.message); });
    } catch (e) { return function () {}; }
  };
  // Full-doc subscriber (state + activeUid + final + summary) — used for take-over detection and the
  // home-screen "game in progress" indicator.
  window.cloudSubscribeGameDoc = function (id, cb) {
    var d = fdb(); if (!d || !id) return function () {};
    try {
      return d.collection("games").doc(id).onSnapshot(function (s) {
        try { cb(s.exists ? s.data() : null); } catch (e) {}
      }, function (e) { console.warn("gamedoc:", e.message); });
    } catch (e) { return function () {}; }
  };

  // Claim a texted invite: ?invite=<teamId> in the URL → add me as a scorer on that team.
  // (Rules allow a signed-in user to append ONLY their own email; the owner can remove anyone.)
  window.cloudClaimInvite = function () {
    var d = fdb(), em = myEmail(); if (!d || !em) return;
    var tid; try { tid = new URLSearchParams(location.search).get("invite"); } catch (e) {}
    if (!tid) return;
    d.collection("teams").doc(tid).update({
      scorers: firebase.firestore.FieldValue.arrayUnion(em), updatedAt: Date.now(),
    })
      .then(function () { window._joinTeamId = tid; ctoast("You're now an authorized scorer for this team."); })
      .catch(function (e) { ctoast("Couldn't join team: " + e.message); })
      .finally(function () {
        try { var u = new URL(location.href); u.searchParams.delete("invite"); history.replaceState(null, "", u.pathname + u.search); } catch (e) {}
      });
  };

  function ctoast(msg) {
    try {
      var dv = document.createElement("div");
      dv.textContent = msg;
      dv.setAttribute("style", "position:fixed;left:50%;top:16px;transform:translateX(-50%);z-index:100000;background:#A3E635;color:#0B0E13;font-family:system-ui,sans-serif;font-weight:800;font-size:13px;padding:10px 16px;border-radius:999px;box-shadow:0 8px 24px rgba(0,0,0,.5);max-width:90vw;text-align:center");
      document.body.appendChild(dv); setTimeout(function () { try { dv.remove(); } catch (e) {} }, 5000);
    } catch (e) {}
  }

  // Is the signed-in user the owner of (or first to claim) this team?
  window.cloudIsOwner = function (t) {
    var u = myUid(); if (!u || !t) return false;
    return !t.ownerUid || t.ownerUid === u;
  };
})();
