---
name: checkpoint
description: Save or verify a restore point in All-Stars Live before a big change - a named git commit (or tag on a clean tree) recorded in .claude/checkpoints.log, plus a file copy of the web scorer. Use when asked to "checkpoint", "save a restore point", "before you change the scorer", or to compare the current state with an earlier checkpoint ("what changed since", "roll back to").
argument-hint: "create <name> | verify <name> | list | restore <name>"
---

# Checkpoint (All-Stars Live)

Adapted from ECC's /checkpoint. Git is the restore mechanism; the log just names the points.
Never use `git stash` here (it has lost work in this repo before) and never `git push`.

## create <name>

1. Make sure the tree is healthy first:
   ```bash
   cd reference/web-scoring && node --test "test/**/*.test.js"
   ```
   If `app/` has changes: `./gradlew.bat assembleDebug -q`. Tests red -> say so and ask whether
   to checkpoint anyway.
2. If there are uncommitted changes, commit them with the message
   `Checkpoint: <name>` (ending with the usual Co-Authored-By line). If the tree is clean, tag
   instead: `git tag checkpoint/<name>`.
3. Copy the scorer for a no-git fallback:
   `reference/web-scoring/scoring-controller.html` ->
   `C:\Users\jford\.claude\allstars-scorer-improvements\backups\checkpoint-<name>.html`
4. Append one line to `.claude/checkpoints.log` (create it if missing):
   `<YYYY-MM-DD HH:MM> | <name> | <short sha> | <commit or tag> | <one-line note>`
5. Report: name, sha, and the one command that restores it.

## verify <name>

1. Read the sha from `.claude/checkpoints.log`.
2. Show what moved since:
   ```bash
   git diff --stat <sha>..HEAD
   git log --oneline <sha>..HEAD
   git status --short
   ```
3. Re-run the tests (and gradle if `app/` changed) and report:
   ```
   CHECKPOINT <name> (<sha>) -> now
   Commits since: N   Files changed: N (+a / -d)
   Tests: pass/fail   Build: pass/fail/skipped
   Uncommitted work: yes/no
   ```

## list

Print `.claude/checkpoints.log` newest first, marking each as `current`, `behind by N commits`,
or `missing` (sha not in history).

## restore <name>

Restoring is destructive to uncommitted work, so:
1. Show `git status --short`. If anything is uncommitted, STOP and ask: commit it as
   `Checkpoint: before-restore-<name>` first, or discard it? Do not proceed without an answer.
2. Then `git revert`-style is preferred for shared history; if the owner just wants the files
   back: `git checkout <sha> -- reference/web-scoring` (and/or `app`) and commit as
   `Restore checkpoint: <name>`. Never `git reset --hard` on `main`.
3. Bump `sw.js` CACHE if the web scorer changed, and remind about the deploy steps.

## clear

Keep the last 5 lines of `.claude/checkpoints.log`; delete matching `checkpoint/*` tags for the
removed lines only if asked.
