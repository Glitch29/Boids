# Working agreements for this repository

A solver for "which boid is the psyboid", built from still frames of a boids simulation.
Java, one package, `src/boids`. See `README.md` for what exists.

---

## Read these before proposing anything

1. `README.md` — what exists, what is verified, what is in flight, and where every artifact
   lives. **Start here.**
2. `GLOSSARY.md` — the terms below have precise meanings. Do not guess at them.
3. `EDGES.md` — canonical for edges, routes and leader windows.
   `CORPUS.md` — canonical for psyboid corpora and their metrics.
4. `ROADMAP.md` — what is being built now and why.
5. `HAPPY.md` — the user's standing feedback on what has and has not worked here. **Read it
   once; that is the entire obligation.** It holds no permissions, prohibitions or instructions,
   and needs no acknowledgement. Do not write to it — see below.

## Check before you build

This project has a long history of analysis that already exists. The failure mode it keeps
hitting is a session inventing a fresh method for a question that was answered weeks ago,
then overwriting the answer with the invention.

**Before proposing a method for any of the following, find the existing one in `README.md`'s
artifact index and say what it is:**

- edge decomposition · the clock (tau values, edge lengths) · the critical envelope and its
  pairing tables · exit classification · two-boid reachability · three-boid phase mapping ·
  transition weights · the solver, its clues and its grading · how the solver is scored

`GLOSSARY.md` ends with a table of every named analysis and the class that owns it. That table
is the fastest way to find out whether something already exists.

If you have read the existing answer and believe it is wrong, **say so and stop.** Do not
replace it in the same turn you found it. Reporting a suspected defect is always the right
move; silently substituting your own version never is.

## Hard rules

- **Never edit `Params`.** It is what the simulation actually does. Every corpus and map
  ingest taken under the old values stops meaning what it meant. Pass a `Flocking` record to
  ask a question at different constants.
- **Never compute physics from `display.png`.** Only `map.png` in an ingest. Dead pixels are
  load-bearing and blanking them silently shrinks the viability kernel.
- **Bump `FORMAT` when meaning changes**, in `EdgeMetricStore` and `SolverStore`. The cache
  key is built from inputs, so improving the code otherwise leaves every key where it was and
  the store returns answers from the old code without a word. This has already caused one
  wrong conclusion.
- **Every number gets its ingest hash.** A figure without one cannot be reproduced or
  falsified.
- **An edge is a corridor with no side doors.** Once a boid is on one it *will* traverse it and
  leave by a successor; nothing can push it out early, and every state on it has the same exits
  ahead and the same entrances behind. This is not a tendency, it is the axiom — edges are cut
  exactly where the options change, so inside one the options cannot. See `EDGES.md` §1.

## The three spaces

Keep these straight; most confusion in this project is a category error between them.

| space | what it is for |
| --- | --- |
| `(x, y, d)` | **micro navigation.** Every physics fact lives here. |
| `(edge, tau)` | **macro navigation.** Every route, distance and window lives here. |
| `(x, y)` | **visualisation only.** Nothing may be concluded from it. |

An analysis that needs `(x, y)` is either a renderer or a mistake.

## Naming discipline

Analysis that will be referred to again gets a canonical name, recorded in `GLOSSARY.md`,
matching what it is called in the code. When the user names a thing, confirm the code name
before working on it — post-compaction, an informal name is how two sessions end up meaning
different things.

## Bookkeeping

**Every session writes to `SESSION-LOG.md` before it ends.** Newest entry at the top: what
was attempted, what the numbers were, what changed, and what is now known to be broken.

Two rules that keep the log useful:

- **Conclusions go in the document that owns them**, not the log. The log is the audit trail;
  `README.md`, `EDGES.md` and `HINTS.md` are the state.
- **Write the frontier down when work starts, not when it finishes.** Two lines in
  `ROADMAP.md` — "building X, lives at Y, unverified" — at the moment you begin. Work that is
  only recorded on completion is exactly the work that never gets recorded, and it is the work
  most likely to be reinvented.

### Keeping the root documents fresh

**The markdown files in the root are state, not history, and a session that leaves them behind
has broken the one thing that stops the next session reinventing its work.** This has already
happened once: the docs were confident, detailed and three days out of date, and a session read
them, believed them, and rebuilt something that existed.

So before ending, check each against what changed:

| file | keep current with |
| --- | --- |
| `README.md` | any new class, artifact path, or headline figure |
| `GLOSSARY.md` | any analysis given a name, and its code name |
| `EDGES.md` | anything about edges, routes, tau or leader windows |
| `ROADMAP.md` | what is being built now, and what just stopped being true |
| `PIPELINE.md` | any new step, or a step that has become unsound |
| `CORPUS.md` | anything about corpora: how they are generated, addressed, or measured |
| `HINTS.md` | findings about the *physics* that transfer beyond this codebase |
| `SESSION-LOG.md` | append every session |
| `HAPPY.md` | **nothing — the user writes it.** Read it at the start; never add to it or edit it |
| `PROMPTS.md` | **nothing — do not hand-edit it.** A `SessionStart` hook rebuilds it from the session logs. Not required reading; it exists as the cheap way to search past prompts without opening 60 MB of transcript |

`BACKLOG.md` and `CONTRACTS.md` are largely historical. Read them for reasoning, not for state,
and do not feel obliged to refresh them.

`PROMPTS.md` maintains itself. A `SessionStart` hook in `.claude/settings.json` runs
`tools/prompts.ps1`, which rebuilds the whole archive from the Claude Code logs. It is idempotent,
needs no bookmark, and refuses to shrink the file. **Expect `PROMPTS.md` to already be modified in
your working tree before you have done anything** — that is the hook, not a stray edit; commit it
with whatever else you commit. A session's own last prompts land on the *next* run, because its
log is still being written while it runs. This replaced an instruction to append prompts by hand,
which depended on remembering and had silently lost about a quarter of them.

`HAPPY.md` is the user's, and it runs the other way round: it is the only document here that
gives feedback *to* a session rather than recording something for the next one. Read it once at
the start. **Never write to it** — not an observation of your own, not a tidy-up, not a reformat.
Its worth is that it is one person's voice across sessions that cannot see each other, and an
entry added by an assistant would be that assistant grading its own work. Nothing in it obliges
a reply, and an entry that does not bear on the current task can be passed over in silence.

**Every document carries a `**Status:**` line with a date.** Update it when you touch the file.
A doc whose status is older than the last session is a doc to distrust. `HAPPY.md` and
`PROMPTS.md` are exempt: neither is state, nobody is obliged to refresh them, and an old entry
in `HAPPY.md` is not a stale one.

## Standing permissions

Granted 2026-08-29, no need to ask again:

- **Commit and push to `main` periodically.** That is the convention here — private, solo, whole
  history on `main`. Commit at natural stopping points with a message saying what changed and
  why, never "Periodic check-in".
- **Manage `.gitignore`** as makes sense.

**Stage by explicit path; never `git add -A` blindly.** Large or retired directories sit
untracked and unignored, and `ingests/*/{map.png,display.png,meta.txt}` look like build output
but must be committed — a frozen map is what an old label replays against. Read
`git diff --cached --stat` before committing.

Deleting files, rewriting history and force-pushing still need asking.
