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
  function fdb()   { return window.Cloud && window.Cloud.db; }
  function myUid() { return window.Cloud && window.Cloud.user && window.Cloud.user.uid; }
  function myEmail(){ return ((window.Cloud && window.Cloud.user && window.Cloud.user.email) || "").toLowerCase(); }
  var _unsub = [];
  // Stop all team onSnapshot listeners (called on sign-out so the next person on a shared
  // tablet doesn't keep receiving / seeing the previous account's teams).
  window.cloudStopSync = function () { _unsub.forEach(function (f) { try { f(); } catch (e) {} }); _unsub = []; };

  // Whitelist the fields we persist (drop any runtime junk).
  function teamDoc(t) {
    return {
      id: t.id, name: t.name || "", short: t.short || "", color: t.color || "#2E6BE6",
      players: t.players || [], record: t.record || { w: 0, l: 0, t: 0 }, season: t.season || {}, fav: !!t.fav,
      schedule: t.schedule || [], lineup: t.lineup || null, rulesUrl: t.rulesUrl || "",
      ownerUid: t.ownerUid || myUid(), ownerEmail: t.ownerEmail || myEmail(),
      scorers: t.scorers || [], followers: t.followers || [], updatedAt: Date.now(),
    };
  }

  // Push one team up (claims ownership if it has none / is mine).
  window.cloudSaveTeam = function (t) {
    var d = fdb(), u = myUid();
    if (!d || !u || !t || !t.id) return;
    if (t.ownerUid && t.ownerUid !== u && (t.scorers || []).indexOf(myEmail()) < 0) return; // not mine
    d.collection("teams").doc(t.id).set(teamDoc(t), { merge: true })
      .catch(function (e) { console.warn("cloudSaveTeam:", e.message); });
  };

  // Debounced push of all my teams (called from saveDB on any team edit).
  var _pt;
  window.cloudPushSoon = function () {
    if (!fdb() || !myUid()) return;
    if (window._cloudApplying) return;   // we're applying an inbound snapshot — don't echo it back (no write-loop)
    clearTimeout(_pt);
    _pt = setTimeout(function () { try { (DB.teams || []).forEach(window.cloudSaveTeam); } catch (e) {} }, 800);
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

  // On sign-in: claim + push local teams, then live-subscribe to my owned + scorer teams.
  window.cloudSyncTeams = function () {
    var d = fdb(), u = myUid(), em = myEmail();
    if (!d || !u) return;
    try {
      (DB.teams || []).forEach(function (t) { if (!t.ownerUid) { t.ownerUid = u; t.ownerEmail = em; } window.cloudSaveTeam(t); });
      saveDB();
    } catch (e) {}

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
      var changed = false;
      var dead = deletedSet();
      docs.forEach(function (doc) {
        var c = doc.data(); if (!c || !c.id) return;
        if (dead.indexOf(c.id) >= 0) return;   // user deleted this team — never re-add it
        var i = (DB.teams || []).findIndex(function (x) { return x.id === c.id; });
        if (i < 0) { DB.teams.push(c); changed = true; }
        else {
          var merged = Object.assign({}, DB.teams[i], c);
          if (sansTimestamp(merged) !== sansTimestamp(DB.teams[i])) { DB.teams[i] = merged; changed = true; }  // ignore updatedAt-only churn
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
      try { saveDB(); } catch (e) {}   // persist locally WITHOUT re-pushing (guarded above)
      window._cloudApplying = false;
      cloudRenderSoon();               // deferred + interaction-aware (won't kill an open dropdown)
    }
    _unsub.push(d.collection("teams").where("ownerUid", "==", u)
      .onSnapshot(function (s) { merge(s.docs); }, function (e) { console.warn("teams(owned):", e.message); }));
    if (em) _unsub.push(d.collection("teams").where("scorers", "array-contains", em)
      .onSnapshot(function (s) { merge(s.docs); }, function (e) { console.warn("teams(scorer):", e.message); }));
    if (em) _unsub.push(d.collection("teams").where("followers", "array-contains", em)
      .onSnapshot(function (s) { merge(s.docs); }, function (e) { console.warn("teams(follower):", e.message); }));
  };

  // Membership helpers (used to gate the chat composer + game scoring on the web).
  window.cloudIsScorer = function (t) {
    var em = myEmail(); return !!(t && (window.cloudIsOwner(t) || (Array.isArray(t.scorers) && t.scorers.indexOf(em) >= 0)));
  };
  window.cloudIsMember = function (t) {
    var em = myEmail(); return !!(t && (window.cloudIsScorer(t) || (Array.isArray(t.followers) && t.followers.indexOf(em) >= 0)));
  };

  // Follow a team to read/post its chat (parents/fans) — NOT a scorer. From a ?follow=<teamId> link.
  window.cloudFollowTeam = function (teamId) {
    var d = fdb(), em = myEmail(); if (!d || !em || !teamId) return Promise.resolve();
    return d.collection("teams").doc(teamId)
      .update({ followers: firebase.firestore.FieldValue.arrayUnion(em), updatedAt: Date.now() })
      .then(function () { window._joinTeamId = teamId; ctoast("You're now following this team."); })
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
    }).catch(function (e) { ctoast("Couldn't send: " + e.message); });
  };
  window.cloudDeleteMessage = function (teamId, msgId) {
    var d = fdb(); if (!d || !teamId || !msgId) return Promise.resolve();
    return d.collection("teams").doc(teamId).collection("messages").doc(msgId).delete()
      .catch(function (e) { ctoast("Couldn't delete: " + e.message); });
  };

  /* ===== Persistent cloud game — owner/scorer writes games/{id}; anyone with the link reads (watch) ===== */
  var _gp;
  window.cloudPublishGame = function (id, payload) {
    var d = fdb(), u = myUid(); if (!d || !u || !id) return;
    if (window._cloudApplying) return;
    clearTimeout(_gp);
    _gp = setTimeout(function () {
      d.collection("games").doc(id).set({
        id: id, teamId: payload.teamId || "", ownerUid: u,
        state: payload, updatedAt: Date.now(),
      }, { merge: true }).catch(function (e) { console.warn("cloudPublishGame:", e.message); });
    }, 1000);
  };
  window.cloudSubscribeGame = function (id, cb) {
    var d = fdb(); if (!d || !id) return function () {};
    try {
      return d.collection("games").doc(id).onSnapshot(function (s) {
        var v = s.exists ? s.data() : null; try { cb(v && v.state ? v.state : null); } catch (e) {}
      }, function (e) { console.warn("game:", e.message); });
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
