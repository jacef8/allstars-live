// Liberty League — live relay (+ optional Firebase persistence)
//
// One job: pass game-state messages from the scorekeeper's controller to every
// connected viewer/overlay, and hand the latest state to anyone who joins late.
//
// Optional crash recovery: if FIREBASE_SERVICE_ACCOUNT and FIREBASE_DB_URL are set,
// every state is mirrored to Firebase Realtime Database at /games/current, and on
// startup the last saved state is loaded so a relay restart resumes mid-game.
// With those vars unset, it runs as a pure in-memory relay (no persistence).
//
// ── Railway environment variables ────────────────────────────────────────────
//   PORT                       set AUTOMATICALLY by Railway — do not set it yourself
//   FIREBASE_DB_URL            e.g. https://allstars-live-default-rtdb.firebaseio.com
//   FIREBASE_SERVICE_ACCOUNT   the FULL service-account key JSON, pasted as one value
// See DEPLOY.md for exact steps.
//
// Point BOTH the controller and the viewer/overlay at this relay by adding
//   ?server=wss://YOUR-APP.up.railway.app   (the viewer also accepts ?ws=)

const http = require("http");
const { WebSocketServer } = require("ws");

let lastState = null;        // most recent state message (string) — replayed to late joiners
let saveState = () => {};    // no-op unless Firebase is configured below

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
    // write-through: persist the latest state on every change
    saveState = (msg) => {
      ref.set({ state: msg, updatedAt: Date.now() })
         .catch((e) => console.error("Firebase write failed:", e.message));
    };
    // seed in-memory state from the last saved game (crash recovery)
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

/* ───────── HTTP server: health check + friendly root ───────── */
const server = http.createServer((req, res) => {
  if (req.url === "/health") {            // Railway healthcheck hits this
    res.writeHead(200, { "Content-Type": "text/plain" });
    res.end("ok");
    return;
  }
  res.writeHead(200, { "Content-Type": "text/plain" });
  res.end("Liberty League relay is running.");
});

/* ───────── WebSocket relay ───────── */
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
  server.listen(PORT, () => console.log("Liberty League relay on :" + PORT));
});
