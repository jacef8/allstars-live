// All-Stars Live — single Railway service.
//
// Serves BOTH:
//   1. the PWA / web app (static files in this folder) over HTTPS, and
//   2. the live relay (WebSocket) — passes the scorekeeper's game-state to every
//      connected viewer/overlay, and catches late joiners up with the latest state.
// One origin for everything, so the app connects to its own relay (no ?server needed).
//
// Optional crash recovery: if FIREBASE_SERVICE_ACCOUNT and FIREBASE_DB_URL are set,
// the latest state is mirrored to Firebase Realtime DB at /games/current and reloaded
// on startup. Unset → pure in-memory relay.
//
// ── Railway env vars ─────────────────────────────────────────────────────────
//   PORT                      set AUTOMATICALLY by Railway — do not set it
//   FIREBASE_DB_URL           (optional) e.g. https://allstars-live-default-rtdb.firebaseio.com
//   FIREBASE_SERVICE_ACCOUNT  (optional) the FULL service-account key JSON, one value

const http = require("http");
const fs = require("fs");
const path = require("path");
const { WebSocketServer } = require("ws");

const ROOT = __dirname; // static files live next to this server (reference/web-scoring)

let lastState = null; // most recent state message (string) — replayed to late joiners
let saveState = () => {}; // no-op unless Firebase is configured below

/* ───────── optional Firebase Realtime Database persistence ───────── */
let dbReady = Promise.resolve();
if (process.env.FIREBASE_SERVICE_ACCOUNT && process.env.FIREBASE_DB_URL) {
  try {
    const admin = require("firebase-admin");
    const creds = JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT);
    admin.initializeApp({
      credential: admin.credential.cert(creds),
      databaseURL: process.env.FIREBASE_DB_URL,
    });
    const ref = admin.database().ref("/games/current");
    saveState = (msg) => {
      ref.set({ state: msg, updatedAt: Date.now() })
        .catch((e) => console.error("Firebase write failed:", e.message));
    };
    dbReady = ref.once("value")
      .then((snap) => {
        const v = snap.val();
        if (v && v.state) { lastState = v.state; console.log("Recovered last game state from Firebase."); }
      })
      .catch((e) => console.error("Firebase read failed:", e.message));
    console.log("Firebase persistence enabled at /games/current.");
  } catch (e) {
    console.error("Firebase init failed — running as a pure relay:", e.message);
  }
} else {
  console.log("Firebase not configured — running as a pure relay (no persistence).");
}

/* ───────── static file serving (the PWA) ───────── */
const MIME = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".mjs": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".webmanifest": "application/manifest+json; charset=utf-8",
  ".png": "image/png",
  ".jpg": "image/jpeg",
  ".jpeg": "image/jpeg",
  ".webp": "image/webp",
  ".svg": "image/svg+xml",
  ".ico": "image/x-icon",
  ".woff2": "font/woff2",
  ".map": "application/json",
};

function serveStatic(req, res) {
  let urlPath = decodeURIComponent((req.url || "/").split("?")[0]);
  if (urlPath === "/" || urlPath === "") urlPath = "/scoring-controller.html";
  const safe = path.normalize(path.join(ROOT, urlPath));
  // Block path traversal — never serve outside ROOT.
  if (safe !== ROOT && !safe.startsWith(ROOT + path.sep)) {
    res.writeHead(403, { "Content-Type": "text/plain" });
    res.end("forbidden");
    return;
  }
  fs.stat(safe, (err, st) => {
    if (err || !st.isFile()) {
      // Unknown path → hand back the app (so deep links / refreshes work).
      fs.readFile(path.join(ROOT, "scoring-controller.html"), (e2, buf) => {
        if (e2) { res.writeHead(404, { "Content-Type": "text/plain" }); res.end("not found"); return; }
        res.writeHead(200, { "Content-Type": MIME[".html"], "Cache-Control": "no-cache" });
        res.end(buf);
      });
      return;
    }
    const ext = path.extname(safe).toLowerCase();
    const type = MIME[ext] || "application/octet-stream";
    // sw.js + HTML must stay fresh; icons/lib can cache hard.
    let cache = "public, max-age=3600";
    if (ext === ".html" || /(^|\/)sw\.js$/.test(urlPath)) cache = "no-cache";
    else if (urlPath.startsWith("/icons/") || urlPath.startsWith("/lib/")) cache = "public, max-age=86400";
    res.writeHead(200, { "Content-Type": type, "Cache-Control": cache });
    fs.createReadStream(safe).pipe(res);
  });
}

/* ───────── HTTP server: health check, then static ───────── */
const server = http.createServer((req, res) => {
  if (req.url === "/health") {            // Railway healthcheck
    res.writeHead(200, { "Content-Type": "text/plain" });
    res.end("ok");
    return;
  }
  serveStatic(req, res);
});

/* ───────── WebSocket relay (shares the same server/port) ───────── */
const wss = new WebSocketServer({ server });

wss.on("connection", (ws) => {
  if (lastState) { try { ws.send(lastState); } catch (e) {} }   // catch the new client up
  ws.on("message", (data) => {
    const msg = data.toString();
    lastState = msg;
    saveState(msg);                                             // mirror to Firebase if enabled
    for (const client of wss.clients) {
      if (client.readyState === 1) { try { client.send(msg); } catch (e) {} }
    }
  });
});

// keep idle connections alive (Railway/proxies drop silent sockets)
setInterval(() => {
  for (const client of wss.clients) {
    if (client.readyState === 1) { try { client.ping(); } catch (e) {} }
  }
}, 30000);

const PORT = process.env.PORT || 8080;     // Railway injects PORT; 8080 for local dev
dbReady.finally(() => {
  server.listen(PORT, () => console.log("All-Stars Live (app + relay) on :" + PORT));
});
