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

  // Whitelist the fields we persist (drop any runtime junk).
  function teamDoc(t) {
    return {
      id: t.id, name: t.name || "", short: t.short || "", color: t.color || "#2E6BE6",
      players: t.players || [], record: t.record || { w: 0, l: 0, t: 0 }, season: t.season || {}, fav: !!t.fav,
      ownerUid: t.ownerUid || myUid(), ownerEmail: t.ownerEmail || myEmail(),
      scorers: t.scorers || [], updatedAt: Date.now(),
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
    function merge(docs) {
      var changed = false;
      docs.forEach(function (doc) {
        var c = doc.data(); if (!c || !c.id) return;
        var i = (DB.teams || []).findIndex(function (x) { return x.id === c.id; });
        if (i < 0) { DB.teams.push(c); changed = true; }
        else { DB.teams[i] = Object.assign({}, DB.teams[i], c); changed = true; }
      });
      if (changed) { saveDB(); try { render(); } catch (e) {} }
    }
    _unsub.push(d.collection("teams").where("ownerUid", "==", u)
      .onSnapshot(function (s) { merge(s.docs); }, function (e) { console.warn("teams(owned):", e.message); }));
    if (em) _unsub.push(d.collection("teams").where("scorers", "array-contains", em)
      .onSnapshot(function (s) { merge(s.docs); }, function (e) { console.warn("teams(scorer):", e.message); }));
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
      .then(function () { ctoast("You're now an authorized scorer for this team."); })
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
