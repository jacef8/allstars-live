/* All-Stars Live — service worker.
 * App-shell caching so the PWA installs, opens instantly, and survives a flaky
 * connection at the field. The live game data (relay WebSocket, Firebase, the
 * YouTube player) is cross-origin / non-GET, so it always goes straight to the
 * network — only the static shell is cached here. Bump CACHE to ship an update. */
// ⬆️ BUMP THIS STRING ON EVERY DEPLOY. Changing it is what makes the installed PWA
// notice a new version, activate it, and auto-reload (see the SW-update code in the page).
const CACHE = "allstars-v93";
const SHELL = [
  "./",
  "./scoring-controller.html",
  "./firebase-config.js",
  "./auth.js",
  "./cloud-data.js",
  "./manifest.webmanifest",
  "./bg-turf.jpg",
  "./icons/icon-192.png",
  "./icons/icon-512.png",
  "./icons/icon-maskable-512.png",
  "./icons/apple-touch-icon.png",
  "./icons/favicon-32.png",
  "./lib/qrcode.min.js",
];

self.addEventListener("install", (e) => {
  self.skipWaiting();
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL).catch(() => {})));
});

// Page tells a freshly-installed SW to take over immediately → triggers the auto-reload.
self.addEventListener("message", (e) => {
  if (e.data && e.data.type === "SKIP_WAITING") self.skipWaiting();
});

self.addEventListener("activate", (e) => {
  e.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener("fetch", (e) => {
  const req = e.request;
  if (req.method !== "GET") return;                 // never cache score writes / API posts
  const url = new URL(req.url);
  if (url.origin !== location.origin) return;        // relay / firebase / youtube → network

  // The app page: network-first so updates land, fall back to cache when offline.
  if (req.mode === "navigate" || req.destination === "document") {
    e.respondWith(
      fetch(req)
        .then((r) => { const cp = r.clone(); caches.open(CACHE).then((c) => c.put(req, cp)); return r; })
        .catch(() => caches.match(req).then((m) => m || caches.match("./scoring-controller.html"))),
    );
    return;
  }

  // Static assets (icons, lib): cache-first for instant loads.
  e.respondWith(
    caches.match(req).then((m) =>
      m || fetch(req).then((r) => { const cp = r.clone(); caches.open(CACHE).then((c) => c.put(req, cp)); return r; }),
    ),
  );
});
