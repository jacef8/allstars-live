---
name: plan
description: Lay out a feature or change for All-Stars Live before writing code - restate what is wanted, ground it in the existing code (which functions, which modes, which layouts), list the files and steps, name the risks, and WAIT for a yes. Use when asked to "plan", "how would you do X", "before you start", or for any change touching scoring state, saved games, the cloud sync, or the camera pipeline.
argument-hint: "[feature or change description]"
---

# Plan (All-Stars Live)

Adapted from ECC's /plan. Runs inline, no subagents. Produces a plan and then STOPS - no code
until the owner says yes / proceed / go.

## 1. Restate

In 2-4 plain sentences: what the scorekeeper (or fan, or camera operator) will be able to do
that they cannot today, and what must keep working exactly as it does.

Empty request -> ask what to plan. Vague request -> state the assumption you will plan against
and ask one question at most.

## 2. Ground it in the code

Search before designing. For this repo the usual anchors are:

| Area | Where to look |
|---|---|
| Scoring flow | `scoring-controller.html`: `onFieldTap`, `chooseResult`, `commit`, `resolve`, `creditBatter`, `actionArea` (mode branches), the delegated `data-act` handler |
| Game state / persistence | `G`, `snapshot`/`undo`/`hist`, `saveGame`, `broadcast`, `applyRemote`, `cloudPublishGame` (cloud-data.js) |
| Lineups / roster | `assignSlot`, `pickplayer`, `makeLineup`, `logLineup`, team hub screens |
| Rules | league presets, `checkPitchCatchRules`, run cap in `resolve` |
| Layout | `isWide()` branches, `statusBar`, phone footer vs wide toolbar |
| Camera / stream (native) | `ingest/RtmpReceiverService.kt` (RtmpHub), `stream/Broadcast.kt`, `ingest/CameraScreen.kt`, `scorer/GameScorerScreen.kt` (JS bridge) |
| Deploy | `sw.js` CACHE bump, Railway (push) vs Firebase Hosting (`firebase deploy`), rules deploy |

Write a short "Patterns to mirror" table with `file:line` for naming, how errors are surfaced
(`flash(...)`), how state is snapshotted, and how an existing similar screen is rendered. If
nothing similar exists, say so - do not invent a pattern.

## 3. The plan

```
# Plan: <name>
Complexity: Small | Medium | Large     (Small = one session, one file)

## Files to change
| File | Action | Why |

## Steps
1. <step> - mirror: <file:line> - prove it with: <test / browser flow / gradle>
2. ...

## Must still work
- old saved games and cloud docs load (new fields default when absent)
- Undo covers every new state change
- phone AND tablet layouts
- viewer mode stays read-only
- (native) camera link never dropped while a camera is connected

## Risks
| Risk | Likelihood | What we do about it |

## Verify
- node --test "test/**/*.test.js"  (reference/web-scoring)
- browser: <the exact taps to try, both layouts>
- ./gradlew.bat assembleDebug -q  (if app/ changed)
- sw.js CACHE bump + deploy steps
```

Prefer removing a screen or tap over adding one. Prefer an optional in-place control over a
new mandatory step. Keep the plan to one screen of text.

## 4. Wait

End with exactly: **Proceed? (yes / modify: ... / different approach: ...)** and do nothing else
until answered. "modify:" means re-issue the plan with the change, then wait again.

After a yes: implement, then run `/code-review` before any push.
