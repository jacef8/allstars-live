/* All-Stars Live — cloud auth + (later) sync layer.
 *
 * DORMANT-SAFE: if firebase-config.js has no apiKey, this does NOTHING and the app runs
 * exactly as before (local-only, no sign-in). It only activates once the config is filled in.
 *
 * Phase 1 (this file): Firebase Auth via EMAIL MAGIC-LINK (passwordless). Exposes:
 *   window.Cloud = { enabled, ready, user, auth, db }
 *   window.cloudSignIn(email)   -> emails a one-tap sign-in link
 *   window.cloudSignOut()
 *   window.onCloudAuth(user)    -> the app sets this to re-render on sign-in/out
 *
 * Phase 2 (later): mirror teams/rosters/games to Firestore with localStorage as offline cache.
 * Phase 3 (later): team owner + invited scorekeepers, enforced by Firestore security rules.
 */
(function () {
  window.Cloud = { enabled: false, ready: false, user: null, auth: null, db: null };
  // Firebase Auth does NOT work on file:// (the native WebView) — it tries to load an auth
  // iframe from firebaseapp.com and errors out, stalling launch. Stay local-only there. Cloud
  // sign-in / team sync remain a web/PWA feature (served over https).
  if (location.protocol === "file:") { window.Cloud.ready = true; return; }
  var cfg = window.FIREBASE_CONFIG;
  if (!cfg || !cfg.apiKey) { window.Cloud.ready = true; return; }   // not configured → stay off

  var BASE = "https://www.gstatic.com/firebasejs/10.12.5/";
  function load(src) {
    return new Promise(function (res, rej) {
      var s = document.createElement("script");
      s.src = src; s.async = false; s.onload = res; s.onerror = function () { rej(new Error("load " + src)); };
      document.head.appendChild(s);
    });
  }

  load(BASE + "firebase-app-compat.js")
    .then(function () { return load(BASE + "firebase-auth-compat.js"); })
    .then(function () { return load(BASE + "firebase-firestore-compat.js"); })
    .then(function () { return load(BASE + "firebase-storage-compat.js"); })   // photo gallery → Cloud Storage
    .then(function () {
      firebase.initializeApp(cfg);
      var auth = firebase.auth();
      // Force durable LOCAL persistence. In some Android WebViews the SDK's auto-detection
      // silently falls back to IN-MEMORY persistence (the signed-in session is lost on every
      // app restart) even though IndexedDB/localStorage work fine. Setting it explicitly makes
      // the session survive restarts. LOCAL = IndexedDB, with a localStorage fallback.
      try { auth.setPersistence(firebase.auth.Auth.Persistence.LOCAL).catch(function (e) { console.warn("auth persistence:", e && e.message); }); } catch (e) {}
      try { firebase.firestore(); } catch (e) {}
      window.Cloud.enabled = true;
      window.Cloud.auth = auth;
      try { window.Cloud.db = firebase.firestore(); } catch (e) {}
      try { window.Cloud.storage = firebase.storage(); } catch (e) {}

      // Returning from a magic-link? Finish the sign-in.
      try {
        if (auth.isSignInWithEmailLink(window.location.href)) {
          var email = window.localStorage.getItem("al-auth-email") || "";
          if (!email) { try { email = window.prompt("Confirm your email to finish signing in:") || ""; } catch (e) {} }
          if (email) {
            auth.signInWithEmailLink(email, window.location.href)
              .then(function () {
                window.localStorage.removeItem("al-auth-email");
                // strip the long auth query string from the URL
                try { window.history.replaceState(null, "", window.location.pathname); } catch (e) {}
              })
              .catch(function (e) { cloudToast("Sign-in failed: " + e.message); });
          }
        }
      } catch (e) {}

      try { auth.getRedirectResult().catch(function () {}); } catch (e) {}   // complete Google redirect flow
      auth.onAuthStateChanged(function (u) {
        window.Cloud.user = u; window.Cloud.ready = true;
        try { if (typeof window.onCloudAuth === "function") window.onCloudAuth(u); } catch (e) {}
      });
    })
    .catch(function (e) {
      console.error("Cloud (Firebase) failed to load — staying local-only:", e);
      window.Cloud.ready = true;
    });

  // Send the passwordless sign-in link. Returns to THIS page; that origin must be listed under
  // Firebase Auth → Settings → Authorized domains (add the Railway domain + localhost).
  window.cloudSignIn = function (email) {
    email = (email || "").trim();
    if (!window.Cloud.auth || !email) return;
    var url = window.location.origin + window.location.pathname;
    window.Cloud.auth.sendSignInLinkToEmail(email, { url: url, handleCodeInApp: true })
      .then(function () {
        window.localStorage.setItem("al-auth-email", email);
        cloudToast("Sign-in link sent to " + email + " — open it on this device.");
      })
      .catch(function (e) { cloudToast("Couldn't send link: " + e.message); });
  };

  // Native app hands a Google ID token back here → sign in to Firebase with it (bypasses the
  // WebView OAuth block). Called from MainActivity after the native GoogleSignIn flow.
  window.__googleCredential = function (idToken) {
    try {
      if (!window.Cloud || !window.Cloud.auth || typeof firebase === "undefined" || !idToken) return;
      var cred = firebase.auth.GoogleAuthProvider.credential(idToken);
      // Make sure the session is stored durably (survives app restart) BEFORE signing in.
      window.Cloud.auth.setPersistence(firebase.auth.Auth.Persistence.LOCAL)
        .catch(function () {})
        .then(function () { return window.Cloud.auth.signInWithCredential(cred); })
        .catch(function (e) { cloudToast("Google sign-in failed: " + (e.message || e)); });
    } catch (e) { cloudToast("Google sign-in failed."); }
  };
  window.__googleFail = function (msg) { cloudToast(msg || "Google sign-in didn't complete."); };

  // Google sign-in. In the native app, route to the native flow (the WebView blocks Google's
  // OAuth). On the web/PWA, use the popup (with redirect fallback).
  window.cloudSignInGoogle = function () {
    if (window.AllStars && typeof window.AllStars.googleSignIn === "function") { try { window.AllStars.googleSignIn(); return; } catch (e) {} }
    if (!window.Cloud.auth || typeof firebase === "undefined") return;
    var p = new firebase.auth.GoogleAuthProvider();
    // Popup keeps the app open (no navigation/restart — important for installed PWAs).
    // Only fall back to redirect if the popup is truly unsupported/blocked.
    window.Cloud.auth.signInWithPopup(p).catch(function (e) {
      var code = e && e.code;
      if (code === "auth/cancelled-popup-request" || code === "auth/popup-closed-by-user") return;  // user dismissed
      if (code === "auth/popup-blocked" || code === "auth/operation-not-supported-in-this-environment") {
        try { window.Cloud.auth.signInWithRedirect(p); } catch (_) { cloudToast("Google sign-in failed: " + (e.message || code)); }
      } else if (e) { cloudToast("Google sign-in failed: " + (e.message || code)); }
    });
  };

  window.cloudSignOut = function () { if (window.Cloud.auth) window.Cloud.auth.signOut(); };

  // Minimal toast (auth runs before the app's toast helpers may exist).
  function cloudToast(msg) {
    try {
      var d = document.createElement("div");
      d.textContent = msg;
      d.setAttribute("style", "position:fixed;left:50%;top:16px;transform:translateX(-50%);z-index:100000;background:#A3E635;color:#0B0E13;font-family:system-ui,sans-serif;font-weight:800;font-size:13px;padding:10px 16px;border-radius:999px;box-shadow:0 8px 24px rgba(0,0,0,.5);max-width:90vw;text-align:center");
      document.body.appendChild(d);
      setTimeout(function () { try { d.remove(); } catch (e) {} }, 5000);
    } catch (e) { try { alert(msg); } catch (e2) {} }
  }
})();
