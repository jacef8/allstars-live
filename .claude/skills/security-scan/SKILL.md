---
name: security-scan
description: Security scan for All-Stars Live - open Firebase/Firestore/Storage rules, exposed keys or secrets, unsafe HTML rendering in the web scorer, and risky Claude Code config. Use before a push, after editing firestore.rules / storage.rules / firebase-config.js / the Android manifest, or whenever asked to "check security", "scan", or "are my rules open".
---

# Security scan (All-Stars Live)

Adapted from ECC's security-scan / security-review for this repo: a single-file web scorer
(`reference/web-scoring/scoring-controller.html` + `cloud-data.js` + `auth.js`) on Firebase
(Auth, Firestore, Storage) and Railway, plus a native Android app (`app/`). Other people sign
in to this app, so open rules and leaked secrets matter most.

Run every section. Report only facts you verified in files; quote the line. Do not invent
findings. Do not change rules or keys without saying exactly what will change first.

## 1. Firebase rules (CRITICAL)

Read `reference/web-scoring/firestore.rules` and `reference/web-scoring/storage.rules` in full.
Flag any of these:

- `allow read, write: if true;` or `if request.auth != null` on a whole collection with no
  ownership check - anyone signed in can rewrite other teams' data.
- A `match /{document=**}` wildcard that grants write.
- Writes to `teams/{id}` not restricted to `ownerUid` or the team's `scorers[]`.
- Live game docs (`games/{id}`) writable by non-scorers; public read is intended for watch links.
- Chat messages (`teams/{id}/messages`) writable by non-members, or no size limit on text.
- Storage: uploads with no auth, no content-type/size cap, or paths not scoped to the team.
- A `request.time` / rate guard missing on anything a fan can write.

For each: file, line, what it allows today, the one-line rule that closes it. Note that rules
only take effect after `firebase deploy --only firestore:rules` (or `storage:rules`) - say so.

## 2. Exposed keys and secrets (CRITICAL)

```bash
git grep -nE "AIza[0-9A-Za-z_-]{30,}|sk-[A-Za-z0-9]{20,}|ya29\.|AKIA[0-9A-Z]{16}|-----BEGIN (RSA |EC )?PRIVATE KEY|\"private_key\"|client_secret|streamKey\s*[:=]\s*\"" -- . ':!*.png' ':!*.jpg' ':!*.webp'
git grep -nE "service[-_]?account|\.jks|keystore|storePassword|keyPassword" -- . ':!.gitignore'
git log --all --diff-filter=A --name-only --pretty=format: | sort -u | grep -iE "service.?account|\.jks|\.keystore|\.env$" || true
```

Rules of thumb for this repo:
- The Firebase **web** config in `firebase-config.js` (apiKey `AIza...`, projectId) is public by
  design - NOT a finding by itself. It IS a finding if Firestore rules are open (section 1),
  because then the public key is enough to write.
- YouTube OAuth: the Android app uses Google Identity authorization (no client secret in the
  APK). A `client_secret` anywhere, or a YouTube **stream key** logged, committed, or shown in a
  screenshot file (`_*.png` at the repo root are gitignored - confirm), is a finding.
- Anything matched under `git log --all` (secret added then deleted) is still in history: say
  so and recommend rotating the credential, not just deleting the file.
- `google-services.json` in `app/` is expected (public identifiers). A `serviceAccountKey.json`
  or `firebase-service-account*.json` anywhere is critical.

## 3. Web scorer rendering (HIGH)

The UI is built with template literals assigned to `innerHTML`. User-entered text (team and
player names, opponent names, chat, schedule notes, invite emails) must go through
`escHtml`/`esc` from `html-safe.js`.

```bash
git grep -nE "innerHTML\s*=|\.insertAdjacentHTML\(" reference/web-scoring/scoring-controller.html | wc -l
git grep -nE "\$\{(t|team|p|pl|m|msg|opp|OUR|OPP|TEAMS)\.(name|short|text|note|email)\}" reference/web-scoring/scoring-controller.html | grep -v "esc(" | head -40
```

Anything from the second command where the value is user-entered and not wrapped in `esc(...)`
is a finding (worst inside an attribute like `title="${...}"`). Also check `watch`/`follow`/
`invite` URL params are never written into HTML unescaped.

## 4. Android app (MEDIUM)

- `WebView.setWebContentsDebuggingEnabled` must stay behind `BuildConfig.DEBUG`
  (`scorer/GameScorerScreen.kt`).
- `AndroidManifest.xml`: `android:exported="true"` only on `MainActivity`; no `debuggable`;
  `usesCleartextTraffic` is known and accepted (RTMP from the camera is a raw socket, not HTTP).
- The RTMP receiver (`ingest/RtmpReceiver.kt`) listens on port 1935 with no auth by design (local
  Wi-Fi/hotspot only) - note it, do not flag it.
- No `Log.*` of OAuth tokens or stream keys (`git grep -n "Log\.[idwev](" app/src | grep -iE "token|key"`).

## 5. Claude Code config (LOW)

Read `.claude/settings.json`, `.claude/settings.local.json` (if present), `.claude/launch.json`
and `.claude/skills/*/SKILL.md`: flag `bypassPermissions`, blanket `Bash(*)` allows, hooks that
run network commands, or skills that instruct pushing/deploying without confirmation.
(ECC's AgentShield does this automatically: `npx ecc-agentshield scan` - optional, needs npm.)

## Report

```
SECURITY SCAN - All-Stars Live - <date>
Grade: A/B/C/D   (D = any CRITICAL open)

CRITICAL
- <file:line> - what it allows - fix
HIGH
- ...
MEDIUM / LOW
- ...
Checked and fine: <one line per section>
Remediation order: 1. ... 2. ...
Deploy note: rules need `firebase deploy --only firestore:rules`; web changes need a push (Railway) and `firebase deploy --only hosting`.
```

Keep it plain; the owner is not a security engineer. Lead with what a stranger could do today.
