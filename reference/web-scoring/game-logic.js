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
        // lead one. opt.fcOut (from the fcpick screen, same pattern as dp's opt.dpOut) lets the scorer
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

  const api = {
    BATTER_GREEN, RUNCOLORS, OUT_MARKER_COLOR: OUT_MARKER_FALLBACK,
    buildPlan, playSummary, outsFromKind, rbiEligible, teamRec, teamRecForSeason, gameSeason, idealText,
  };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  else Object.assign(root, api);   // classic-script global scope, same pattern as the rest of this app
})(typeof window !== "undefined" ? window : globalThis);
