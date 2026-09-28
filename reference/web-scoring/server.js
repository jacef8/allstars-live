// All-Stars Live — the Railway service: a plain static server for the web app in this folder.
//
// The native Android app's WebView loads scoring-controller.html from here (APP_URL in
// GameScorerScreen.kt), so a push to main reaches the tablets on Railway's auto-deploy. Fans'
// share links point at Firebase Hosting, which serves this same folder (see DEPLOY.md).
//
// Live game sync is Firestore (cloud-data.js). The WebSocket relay that used to live here is
// gone — it had no auth and no game scoping, and Firestore already carried every play.
//
// ── Railway env vars ─────────────────────────────────────────────────────────
//   PORT   set AUTOMATICALLY by Railway — do not set it

const http = require("http");
const fs = require("fs");
const path = require("path");

const ROOT = __dirname; // static files live next to this server (reference/web-scoring)

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
    if (ext === ".html" || /(^|\/)(sw|firebase-config)\.js$/.test(urlPath)) cache = "no-cache";
    else if (urlPath.startsWith("/icons/") || urlPath.startsWith("/lib/")) cache = "public, max-age=86400";
    // Content-Length: without it Node falls back to chunked transfer, which works fine for a
    // download but hides the size from a plain HEAD request — the download page's live
    // size/last-modified readout (see download.html) needs this to show anything at all.
    res.writeHead(200, { "Content-Type": type, "Cache-Control": cache, "Content-Length": st.size, "Last-Modified": st.mtime.toUTCString() });
    if (req.method === "HEAD") { res.end(); return; }
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


const PORT = process.env.PORT || 8080;     // Railway injects PORT; 8080 for local dev
server.listen(PORT, () => console.log("All-Stars Live app server on :" + PORT));
