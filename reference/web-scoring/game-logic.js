/* All-Stars Live — pure scoring rules, extracted from scoring-controller.html.
 *
 * WHY THIS FILE EXISTS: every fix to buildPlan()/teamRec()/etc. this project has ever needed was
 * verified by hand — extracting a script block from the monolith, `new Function()`-ing it, poking
 * at it in a browser preview. That works, but it means every regression risk rides on someone
 * remembering to re-check it by hand. These specific functions have NO dependency on `document`,
 * `window`, `localStorage`, or the live game state (`G`) — they're pure input-in/output-out rules —
 * so they can live in their own file and get real, automated tests (see test/game-logic.test.js)
 * instead. Run them with `npm test`.
 *
 * Loaded as a plain <script> BEFORE the main script block in scoring-controller.html — same
 * "classic scripts share one global scope" pattern already used by cloud-data.js/auth.js/
 * html-safe.js, so every existing call site keeps using `buildPlan`, `RUNCOLORS`, etc. as bare
 * globals with no change. Also plain `require()`-able from Node with zero setup, since there's
 * nothing here that needs a DOM.
 */
(function (root) {
  // Runner identity colors (sky/pink/violet/teal — high contrast on the orange basepath, no
  // yellow) and the batter's own color. These moved here from scoring-controller.html so there's
  // exactly ONE definition; the main script still reads them as bare globals.
  const BATTER_GREEN = "#3BE85A";
  const RUNCOLORS = ["#38BDF8", "#F472B6", "#A78BFA", "#2DD4BF"];

  // The sentinel color an out runner's .color field gets tinted to. In the browser this MUST stay
  // the same value as T.out in scoring-controller.html, so we read T at call time (it's a global
  // lexical binding declared in the main script block, which is shared across classic scripts and
  // always defined by the time buildPlan actually runs). The literal is only the Node/test
  // fallback, where no T exists — keeping T the single source of truth rather than a hand-synced
  // copy that could silently drift if the theme changes.
  const OUT_MARKER_FALLBACK = "var(--out)";
  const outMarker = () => (typeof T !== "undefined" && T && T.out) ? T.out : OUT_MARKER_FALLBACK;

  /* ---- default runner movement (each runner keeps a persistent color) ---- */
  function buildPlan(kind, bases, batterLabel, batterName, opt, batterKey) {
    const r1 = bases[1], r2 = bases[2], r3 = bases[3];
    const batterColor = BATTER_GREEN;
    const m = [], add = (label, name, color, start, dest, o = {}) => m.push({ label, name: name || "", color, start, dest, out: !!o.out, isBatter: !!o.isBatter });
    const nm = r => r ? r.name : "";
    const col = r => r && r.color ? r.color : RUNCOLORS[0];
    const adv = n => { if (r3) add(r3.label, nm(r3), col(r3), 3, 4); if (r2) add(r2.label, nm(r2), col(r2), 2, Math.min(4, 2 + n)); if (r1) add(r1.label, nm(r1), col(r1), 1, Math.min(4, 1 + n)); };
    switch (kind) {
      case "single": add(batterLabel, batterName, batterColor, 0, 1, { isBatter: 1 }); adv(1); break;
      case "double": add(batterLabel, batterName, batterColor, 0, 2, { isBatter: 1 }); adv(2); break;
      case "triple": add(batterLabel, batterName, batterColor, 0, 3, { isBatter: 1 }); adv(3); break;
      case "hr": add(batterLabel, batterName, batterColor, 0, 4, { isBatter: 1 }); adv(4); break;
      case "error": add(batterLabel, batterName, batterColor, 0, 1, { isBatter: 1 }); if (r3) add(r3.label, nm(r3), col(r3), 3, 4); if (r2) add(r2.label, nm(r2), col(r2), 2, 3); if (r1) add(r1.label, nm(r1), col(r1), 1, 2); break;
      case "walk": case "hbp": case "ci": {   // ci = catcher's interference — batter awarded 1st, forced runners advance
        add(batterLabel, batterName, batterColor, 0, 1, { isBatter: 1 });
        if (r1) { add(r1.label, nm(r1), col(r1), 1, 2); if (r2) { add(r2.label, nm(r2), col(r2), 2, 3); if (r3) add(r3.label, nm(r3), col(r3), 3, 4); } else if (r3) add(r3.label, nm(r3), col(r3), 3, 3); }
        else { if (r2) add(r2.label, nm(r2), col(r2), 2, 2); if (r3) add(r3.label, nm(r3), col(r3), 3, 3); } break;
      }
      case "sacfly": add(batterLabel, batterName, batterColor, 0, 1, { isBatter: 1, out: 1 }); if (r3) add(r3.label, nm(r3), col(r3), 3, 4); if (r2) add(r2.label, nm(r2), col(r2), 2, 2); if (r1) add(r1.label, nm(r1), col(r1), 1, 1); break;
      case "sacbunt": add(batterLabel, batterName, batterColor, 0, 1, { isBatter: 1, out: 1 }); adv(1); break;
      case "fc": {
        // Which runner the defense actually threw to is a real scoring decision, not always the lead
        // runner — e.g. a shortstop can go to 2nd for the trailing runner instead of the plate for the
        // lead one. opt.fcOut (from the runners screen's OUT cell via setFcOut, same pattern as dp's opt.dpOut) lets the scorer
        // say who, defaulting to the lead runner when unspecified. (jford, 2026-07-06: "need to have the
        // ability to change who got out on fielders choice... no way to pick 'out' as an option.")
        // dest is ALWAYS start+1 here, out or not — every on-base runner is forced to attempt the next
        // base on a fielder's choice; the out one just gets thrown out AT that base instead of reaching
        // it. Previously the out runner's dest was their OWN base (as if they never left), so nothing
        // ever showed them actually running to — and being retired at — the force base. (jford,
        // 2026-07-06: runner on 1st, FC to short, "it does not show the runner from first going to 2nd
        // where he got out. it only shows an x at first base but really the out was made at 2nd.")
        // resolve()/planState() both skip m.out entries entirely when placing runners on bases, so
        // this dest is purely informational (where the play/animation shows them) — safe to correct.
        add(batterLabel, batterName, batterColor, 0, 1, { isBatter: 1 });
        const lead = (opt && opt.fcOut) ? opt.fcOut : (r3 ? 3 : r2 ? 2 : 1);
        if (r3) add(r3.label, nm(r3), col(r3), 3, 4, { out: lead === 3 });
        if (r2) add(r2.label, nm(r2), col(r2), 2, 3, { out: lead === 2 });
        if (r1) add(r1.label, nm(r1), col(r1), 1, 2, { out: lead === 1 }); break;
      }
      case "dp": {
        add(batterLabel, batterName, batterColor, 0, 1, { isBatter: 1, out: 1 });
        const lead = (opt && opt.dpOut) ? opt.dpOut : (r1 ? 1 : r2 ? 2 : 3);
        // Only the actually-forced/out runner attempted (and was retired at) the next base; the
        // OTHER on-base runners aren't part of the force and hold their base by default (unlike fc
        // above, where every runner is assumed forced) — same "the out runner's dest was their own
        // base, not the force base" bug as fc, just fixed only for whichever runner is out=true here.
        if (r1) add(r1.label, nm(r1), col(r1), 1, lead === 1 ? 2 : 1, { out: lead === 1 });
        if (r2) add(r2.label, nm(r2), col(r2), 2, lead === 2 ? 3 : 2, { out: lead === 2 });
        if (r3) add(r3.label, nm(r3), col(r3), 3, lead === 3 ? 4 : 3, { out: lead === 3 }); break;
      }
      case "out1": case "flyout": case "lineout": case "popout":
        add(batterLabel, batterName, batterColor, 0, 1, { isBatter: 1, out: 1 });
        if (r1) add(r1.label, nm(r1), col(r1), 1, 1); if (r2) add(r2.label, nm(r2), col(r2), 2, 2); if (r3) add(r3.label, nm(r3), col(r3), 3, 3); break;
      default: if (r1) add(r1.label, nm(r1), col(r1), 1, 1); if (r2) add(r2.label, nm(r2), col(r2), 2, 2); if (r3) add(r3.label, nm(r3), col(r3), 3, 3);
    }
    m.sort((a, b) => a.start - b.start);
    // origColor is each mover's real identity color, kept even once tinted red for an out — so marking
    // a runner out/safe by hand later (markOut(), the runners screen) can always cleanly restore it
    // instead of losing track of who's who.
    const OUT_COLOR = outMarker();
    m.forEach(x => { x.origColor = x.color; if (x.out) x.color = OUT_COLOR; });
    // attach a stable player key by start position: batter (0) -> batterKey, runners -> the base they came from
    m.forEach(x => { x.key = x.start === 0 ? (batterKey || null) : (bases[x.start] ? bases[x.start].key : null); });
    return m;
  }

  function playSummary(r, t) {
    const base = {
      single: "Single", double: "Double", triple: "Triple", hr: "Home run", error: "Reached on error",
      fc: "Fielder's choice", sacfly: "Sacrifice fly", dp: "Double play", out1: "Ground out", flyout: "Fly out",
      popout: "Pop out", lineout: "Line out", walk: "Walk", hbp: "Hit by pitch",
    }[r.kind] || r.kind;
    if (["walk", "hbp"].includes(r.kind)) return base;
    return t && t.zone ? `${base} ${t.zone}` : base;
  }

  const outsFromKind = k => k === "dp" ? 2 : ["out1", "flyout", "popout", "lineout", "sacfly", "fc", "sacbunt", "cpout"].includes(k) ? 1 : 0;
  const rbiEligible = k => ["single", "double", "triple", "hr", "sacfly", "sacbunt", "walk", "hbp"].includes(k);

  /* The team's W-L-T record, computed from its finished-game log so the two are always consistent. */
  function teamRec(t) {
    const g = (t && t.games) || []; let w = 0, l = 0, ti = 0;
    for (const x of g) { if (x.result === "W") w++; else if (x.result === "L") l++; else if (x.result === "T") ti++; }
    return { w: w, l: l, t: ti };
  }
  function teamRecForSeason(t, sel) {
    let w = 0, l = 0, tt = 0;
    (t && t.games || []).forEach(g => {
      if (sel !== "all" && gameSeason(g) !== sel) return;
      if (g.result === "W") w++; else if (g.result === "L") l++; else if (g.result === "T") tt++;
    });
    return { w: w, l: l, t: tt };
  }
  function gameSeason(g) {
    const d = (g && g.date) || ""; const y = (/^(\d{4})/.exec(d) || [])[1]; return y || "Undated";
  }

  /* Pick readable text (dark or white) for a swatch/badge filled with `hex`. */
  function idealText(hex) {
    try {
      hex = (hex || "").replace("#", ""); if (hex.length === 3) hex = hex.split("").map(c => c + c).join("");
      const r = parseInt(hex.slice(0, 2), 16), g = parseInt(hex.slice(2, 4), 16), b = parseInt(hex.slice(4, 6), 16);
      return (0.299 * r + 0.587 * g + 0.114 * b) > 150 ? "#10141A" : "#fff";
    } catch (e) { return "#fff"; }
  }

  /* ---- undo history + game feed housekeeping (used by snapshot()/saveGame()/rewindToEvent()) ----
   * Each undo snapshot is {g: a deep copy of G, ev: the last feed-event id when it was taken,
   * fl: the feed length then (legacy), cp: true when a play line (an at-bat result or a runner
   * event, i.e. a line the feed's EDIT button opens) came right after it, dz: see histCompact(),
   * n: a sequence number, play: the re-open stash commit() leaves on it}.
   * The history used to be a flat 40 entries in memory only: about one inning of pitch-by-pitch
   * scoring, and nothing at all after a reload, so Rewind refused most of the game (per the
   * 2026-09-27 scorer research). Now the newest `keepRecent` snapshots stay tap-by-tap for Undo, and
   * older ones are thinned to the checkpoints that sit just before each play, which is exactly what
   * "Rewind to this play" restores. A full game is roughly 100 checkpoints. */

  // A feed line the scorer can open with EDIT (feedPanel()): a "play" line that isn't a divider.
  const isEditablePlay = x => !!x && x.type === "play" && x.kind !== "inningend" && x.kind !== "gameend";

  // Thin `list` in place: keep the newest keepRecent entries, and older ones only when they are a
  // checkpoint (cp true, or not yet known). A dropped run is remembered on the kept entry before it
  // as dz = the lowest ev that was dropped, so rewindIndex() can tell when that kept entry is no
  // longer the state just before a given play. Past maxTotal the oldest entries go entirely.
  function histCompact(list, keepRecent, maxTotal) {
    if (!Array.isArray(list)) return list;
    const cut = list.length - keepRecent;
    if (cut > 0) {
      const out = []; let last = null;
      for (let i = 0; i < list.length; i++) {
        const h = list[i];
        if (i >= cut || !h || h.cp !== false) { out.push(h); last = h; continue; }
        if (last && typeof h.ev === "number") last.dz = (typeof last.dz === "number") ? Math.min(last.dz, h.ev) : h.ev;
      }
      if (out.length !== list.length) { list.length = 0; for (const h of out) list.push(h); }
    }
    while (maxTotal > 0 && list.length > maxTotal) list.shift();
    return list;
  }

  // Which snapshot "Rewind to this play" restores for feed line eid (idx = its position in the feed,
  // only for old snapshots with no ev): the newest one taken before that line existed. -1 when
  // there is none, or when the snapshots that sat between it and the play were thinned away (dz),
  // because restoring it would also undo the play(s) before the one the scorer picked.
  function rewindIndex(hist, eid, idx) {
    if (!Array.isArray(hist)) return -1;
    for (let i = hist.length - 1; i >= 0; i--) {
      const s = hist[i]; if (!s) continue;
      if (typeof s.ev === "number" && typeof eid === "number") {
        if (s.ev < eid) return (typeof s.dz === "number" && s.dz < eid) ? -1 : i;
      } else if (typeof s.fl === "number" && typeof idx === "number" && s.fl <= idx) return i;
    }
    return -1;
  }

  // Keep the live feed at `cap` lines. Drops the oldest PITCH line first (only one that didn't move
  // the score, so the "score changed" tag on the following line stays right) and only falls back to
  // the very oldest line when no such pitch is left. The plays themselves, which are what the scorer
  // edits and rewinds to, then last the whole game instead of scrolling off after ~220 pitches.
  // Only pitches of FINISHED at-bats (ones before the last non-pitch line) are candidates: in a long
  // game whose plays alone fill the cap, the current at-bat's pitches (including the one just added)
  // must not be the first thing to go; the oldest line goes instead, as before this existed.
  function trimFeed(feed, cap) {
    if (!Array.isArray(feed)) return feed;
    while (feed.length > cap) {
      let k = -1, lastPlay = -1;
      for (let i = feed.length - 1; i >= 0; i--) { if (feed[i] && feed[i].type !== "pitch") { lastPlay = i; break; } }
      for (let i = 1; i < lastPlay; i++) {
        const x = feed[i], p = feed[i - 1];
        if (x && x.type === "pitch" && p && p.away === x.away && p.home === x.home) { k = i; break; }
      }
      feed.splice(k < 0 ? 0 : k, 1);
    }
    return feed;
  }

  // Undo history <-> a compact storable object. Consecutive snapshots are nearly identical, so G is
  // split into its top-level fields (and one level below for objects such as stats/pa/pstats/bases)
  // and every distinct JSON value is stored once in a pool. That keeps a whole game's history to a
  // few hundred KB of localStorage instead of megabytes. The per-entry JSON is cached on the entry
  // as _pk (snapshots are never changed once taken), so a save only re-serializes new entries.
  // playFrom: entries from this index on keep their .play re-open stash (older ones can't use it).
  function packG(g) {
    const out = {};
    for (const k of Object.keys(g || {})) {
      const v = g[k]; if (v === undefined) continue;
      if (v && typeof v === "object" && !Array.isArray(v)) {
        const o = {}; for (const sk of Object.keys(v)) { if (v[sk] !== undefined) o[sk] = JSON.stringify(v[sk]); }
        out[k] = o;
      } else out[k] = JSON.stringify(v);
    }
    return out;
  }
  function histPack(list, playFrom) {
    const pool = [], at = new Map();
    const ref = s => { let i = at.get(s); if (i === undefined) { i = pool.length; pool.push(s); at.set(s, i); } return i; };
    const s = (list || []).map((h, idx) => {
      if (!h._pk) h._pk = packG(h.g);
      const g = {};
      for (const k in h._pk) {
        const v = h._pk[k];
        if (typeof v === "string") g[k] = ref(v);
        else { const o = {}; for (const sk in v) o[sk] = ref(v[sk]); g[k] = { o: o }; }
      }
      const e = { g: g, ev: h.ev, fl: h.fl };
      if (typeof h.n === "number") e.n = h.n;
      if (typeof h.cp === "boolean") e.cp = h.cp;
      if (typeof h.dz === "number") e.dz = h.dz;
      if (h.play && idx >= (playFrom || 0)) e.play = h.play;
      return e;
    });
    return { v: 1, pool: pool, s: s };
  }
  // Every entry gets its own fresh objects (Undo makes one of them the live G, which then changes).
  function histUnpack(o) {
    if (!o || o.v !== 1 || !Array.isArray(o.pool) || !Array.isArray(o.s)) return [];
    const pool = o.pool;
    return o.s.map(e => {
      const g = {}, pk = {};
      for (const k in e.g) {
        const v = e.g[k];
        if (typeof v === "number") { pk[k] = pool[v]; g[k] = JSON.parse(pool[v]); }
        else { const sub = {}, spk = {}; for (const sk in v.o) { spk[sk] = pool[v.o[sk]]; sub[sk] = JSON.parse(pool[v.o[sk]]); } g[k] = sub; pk[k] = spk; }
      }
      const h = { g: g, ev: e.ev, fl: e.fl, _pk: pk };
      if (typeof e.n === "number") h.n = e.n;
      if (typeof e.cp === "boolean") h.cp = e.cp;
      if (typeof e.dz === "number") h.dz = e.dz;
      if (e.play) h.play = e.play;
      return h;
    });
  }

  // Late pitching change (scoring-controller.html pitchMoveOffer()): what pitcher `key` was charged
  // with between an earlier game state and now. G.pitches[key] is his pitch count and
  // G.pstats[key] his line {k, bb, h, r, outs}. Only increases count; a field that went down (an
  // Undo-free correction) moves nothing. any = there is something to move.
  const PSTAT_FIELDS = ["k", "bb", "h", "r", "outs"];
  function pitcherCreditSince(before, now, key) {
    const num = (o, f) => (o && typeof o[f] === "number" && isFinite(o[f])) ? o[f] : 0;
    const n = Math.max(0, num(now && now.pitches, key) - num(before && before.pitches, key));
    const a = before && before.pstats && before.pstats[key], b = now && now.pstats && now.pstats[key];
    const stats = {}; let any = n > 0;
    for (const f of PSTAT_FIELDS) { const d = Math.max(0, num(b, f) - num(a, f)); if (d) { stats[f] = d; any = true; } }
    return { n: n, stats: stats, any: any };
  }
  // Move such a credit from pitcher `from` to pitcher `to` in game state g (mutates g). Never takes
  // more than `from` actually has, so no count or stat can go negative. Returns true if anything moved.
  function movePitcherCredit(g, from, to, credit) {
    if (!g || !credit || !from || !to || from === to) return false;
    if (!g.pitches || typeof g.pitches !== "object") g.pitches = {};
    if (!g.pstats || typeof g.pstats !== "object") g.pstats = {};
    let moved = false;
    const n = Math.min(Math.max(0, credit.n | 0), g.pitches[from] | 0);
    if (n > 0) {
      g.pitches[from] = (g.pitches[from] | 0) - n; g.pitches[to] = (g.pitches[to] | 0) + n; moved = true;
      // The box score's pitching lines are built from the pstats keys, so a new pitcher whose only
      // credit is pitches (no K/BB/H/R/out yet) still needs a line, or those pitches show up nowhere.
      if (!g.pstats[to]) g.pstats[to] = { k: 0, bb: 0, h: 0, r: 0, outs: 0 };
    }
    const src = g.pstats[from], st = credit.stats || {};
    if (src) {
      for (const f of PSTAT_FIELDS) {
        const d = Math.min(Math.max(0, st[f] | 0), src[f] | 0); if (!d) continue;
        if (!g.pstats[to]) g.pstats[to] = { k: 0, bb: 0, h: 0, r: 0, outs: 0 };
        src[f] = (src[f] | 0) - d; g.pstats[to][f] = (g.pstats[to][f] | 0) + d; moved = true;
      }
    }
    return moved;
  }

  const api = {
    BATTER_GREEN, RUNCOLORS, OUT_MARKER_COLOR: OUT_MARKER_FALLBACK,
    buildPlan, playSummary, outsFromKind, rbiEligible, teamRec, teamRecForSeason, gameSeason, idealText,
    isEditablePlay, histCompact, rewindIndex, trimFeed, histPack, histUnpack,
    pitcherCreditSince, movePitcherCredit,
  };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  else Object.assign(root, api);   // classic-script global scope, same pattern as the rest of this app
})(typeof window !== "undefined" ? window : globalThis);
