// Firebase project config — SAFE to commit (these are public identifiers, not secrets;
// access is controlled by Firestore security rules, not by hiding these).
//
// Fill in from: Firebase console → Project settings → "Your apps" → Web app → SDK config.
// Until `apiKey` is set, the cloud layer stays OFF and the app runs LOCAL-ONLY exactly as
// before (no sign-in, no sync) — so adding this file changes nothing until you configure it.
window.FIREBASE_CONFIG = {
  apiKey: "",
  authDomain: "",        // e.g. allstars-live.firebaseapp.com
  projectId: "",         // e.g. allstars-live
  storageBucket: "",     // e.g. allstars-live.appspot.com
  messagingSenderId: "",
  appId: "",
};
