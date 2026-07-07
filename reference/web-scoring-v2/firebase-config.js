// Firebase project config — SAFE to commit (these are public identifiers, not secrets;
// access is controlled by Firestore security rules + Auth, not by hiding these).
//
// From: Firebase console → Project settings → "Your apps" → Web app → SDK config.
// With apiKey set, the cloud layer (auth.js) activates: email magic-link sign-in + (later) sync.
window.FIREBASE_CONFIG = {
  apiKey: "AIzaSyDlGe5_A-d5_SgWIlOPf9Q6lGzGeVJNPKQ",
  authDomain: "allstars-live.firebaseapp.com",
  projectId: "allstars-live",
  storageBucket: "allstars-live.firebasestorage.app",
  messagingSenderId: "55677156135",
  appId: "1:55677156135:web:4d05306da22a47f4a74702",
  measurementId: "G-YWP4T2W58J",
};
