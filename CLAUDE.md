# Working agreements for this repository

A solver for "which boid is the psyboid", built from still frames of a boids simulation.
Java, one package, `src/boids`. See `README.md` for what exists.

---

## Read these before proposing anything

1. `README.md` — what exists, what is verified, what is in flight, and where every artifact
   lives. **Start here.**
2. `GLOSSARY.md` — the terms below have precise meanings. Do not guess at them.
3. `EDGES.md` — canonical for edges, routes and leader windows.
4. `ROADMAP.md` — what is being built now and why.

## Check before you build

This project has a long history of analysis that already exists. The failure mode it keeps
hitting is a session inventing a fresh method for a question that was answered weeks ago,
then overwriting the answer with the invention.

**Before proposing a method for any of the following, find the existing one in `README.md`'s
artifact index and say what it is:**

- edge decomposition · the clock (tau values, edge lengths) · leader windows and the
  critical envelope · two-boid reachability · transition weights · exit classification ·
  the solver, its clues and its grading

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
- **Gates are a bootstrap, not an analysis tool.** See `EDGES.md` §2.

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
