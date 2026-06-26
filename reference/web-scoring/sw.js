/* All-Stars Live — service worker.
 * App-shell caching so the PWA installs, opens instantly, and survives a flaky
 * connection at the field. The live game data (relay WebSocket, Firebase, the
 * YouTube player) is cross-origin / non-GET, so it always goes straight to the
 * network — only the static shell is cached here. Bump CACHE to ship an update. */
// ⬆️ BUMP THIS STRING ON EVERY DEPLOY. Changing it is what makes the installed PWA
// notice a new version, activate it, and auto-reload (see the SW-update code in the page).
const CACHE = "allstars-v159";
const SHELL = [
  "./",
  "./scoring-controller.html",
  "./firebase-config.js",
  "./auth.js",
  "./cloud-data.js",
  "./manifest.webmanifest",
  "./bg-turf.jpg",
  "./icons/logo-emblem.png",
  "./icons/logo-star.png",
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

  // The app page: stale-while-revalidate. Serve the cached shell INSTANTLY (so a weak/slow
  // signal at the field never blocks the load — it doesn't wait on the network), and fetch a
  // fresh copy in the background to update the cache for next launch. A new deploy still lands
  // because the SW itself updates (CACHE bump) and the page auto-reloads on the new SW; the
  // SHELL list is pre-cached on install so the first post-update load is already cached too.
  if (req.mode === "navigate" || req.destination === "document") {
    const net = fetch(req)
      .then((r) => { const cp = r.clone(); caches.open(CACHE).then((c) => c.put(req, cp)); return r; })
      .catch(() => null);
    e.waitUntil(net.catch(() => {}));   // keep the SW alive to finish the background refresh
    e.respondWith(
      caches.match(req).then((cached) =>
        cached || net.then((r) => r || caches.match("./scoring-controller.html")),
      ),
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
