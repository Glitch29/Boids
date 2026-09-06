# Boids — the psyboid solver

**Status:** 2026-09-06. **Physics 3** — see `ROADMAP.md` §0a-§0f. Verified against dabeone ingest
`609cffdb84be218c` unless stated. Every figure below carries the ingest it was measured on; a
figure without one is not reproducible and should not be trusted, and **figures taken under
physics 2 are marked as such** rather than silently carried forward.

---

## What this is

A boids simulation in which exactly one boid — the **psyboid** — has its movement overridden
for stretches, in order to herd the flock into a scoring region. Given a handful of still
frames, name the psyboid, or say honestly that the scenario does not determine one.

The wider purpose is an **evaluation**. The solver built here is the expert's answer, against
which other models are measured — unaided, and with "training wheels" (everything the expert
can write down, `HINTS.md`). So the value is not only a solver that works, but knowing
exactly *why* it works, in terms transferable to something reasoning from pictures.

**What makes it tractable:** a boid never chooses. Left alone it holds its heading, which on a
dab-like map means it follows a fixed route. Almost all of the time a boid's future is already
determined; the rare moments where it is not are the entire content of the problem. So a solve
is: find states that **require explanation**, enumerate the **candidate reasons** they could
have arisen, and where the only surviving reason is "it was overridden", that boid is the
psyboid.

**The inferential move**, which is what the machinery automates: a boid at the *front* of a
group entering a scoring trajectory had nothing in front of it to follow, so no leader-based
explanation exists and the override explanation is forced. Followers prove nothing. Being the
odd one out is not evidence; being the boid that had nothing to follow is. Worked example in
`HINTS.md` §0a.

## The three spaces

| space | for | rule |
| --- | --- | --- |
| `(x, y, d)` | micro navigation — all physics | every state fact lives here |
| `(edge, tau)` | macro navigation — routes, distances, windows | the solver reasons here |
| `(x, y)` | visualisation | **conclude nothing from it** |

Most live pixels carry two or more edges running different directions through them, so any
claim keyed on `(x, y)` alone is a claim about unrelated trajectories at once. An analysis
that needs `(x, y)` is either a renderer or a mistake. Gates are the one sanctioned
exception, and only for bootstrapping — see `EDGES.md` §2.

---

## Where the work stands

### Verified and relied upon

| result | figure | where |
| --- | --- | --- |
| viability kernel | 136,276 live states on dabeone | `NavMap` |
| predecessors are `FLIP → MOVE → TURN → FLIP` | 0 mismatches vs brute force over all live states | `NavMap` |
| edge decomposition | 9 edges on dabeone, refinement stable; 6 on plait | `<ingest>/edges/` |
| stability | unsteered cycle `2 → 7 → 4 → 2`, so `{2,4,7}` stable | `EdgeNavigation` |
| the clock | `sd/mean` 1.65%, `rms-100` 2.01% under lifted memoryless | `<ingest>/metric/` |
| two-boid reachability | 213,423,450 arrangements, bit-identical from 5 seeds | `TwoBoid` |
| steerable arcs | **exactly three** on dabeone: `2→1`, `4→0`, `5→6` | `TwoBoid.boidEdgeMoves` |
| leader windows | opening bands per arc; tightest `2→1` at leader edge 7, 0.2 ticks | `<ingest>/windows/` |
| psyboid corpus | 40 plans, every row verified by replay, regenerates byte-identical | `<behaviour>/psyboid/<preset>-<hash>/plans.tsv` |
| the scoring floor | a solo psyboid scores 54 points every 533 ticks, `sd 0.00` over 40 seeds | `<preset>-<hash>/floor.tsv` |
| phase is conserved | edge occupancy oscillates at the lap period, autocorrelation 0.99 after 73 laps | `EdgeOccupancy` |
| warm-up needed | **500 ticks** on edge occupancy; the scoring criterion never terminates. `WARM` unchanged, see `ROADMAP.md` §0f | `<behaviour>/occupancy/` |

The **snapshot-only test for "requires explanation"** exists and is the basis of the solver: a
boid on an **unstable edge** is somewhere unsteered travel would not have left it, and that
needs only `(x, y, d)` plus the decomposition — both of which a photograph plus precomputation
supply.

### In flight

The solver skeleton is built and grading runs end to end: `Solver` + `Clue` +
`UnstableEdgeClue`, facts built by `SolverStore` into `<ingest>/solver/facts.bin`, graded by
`SimTest.graded` against the plan corpus and by `SimTest.solve` against synthetic overrides.

**There are no trustworthy grading figures.** Every number previously recorded was taken
against a broken classifier and a corpus since found unsound; they have been removed from the
docs and the javadoc rather than carried forward with caveats. The five windows in
`UnstableEdgeClue`'s enum are hand-read placeholders — bounds from that classifier, `SEP` /
`ALIGN` suffixes assumed rather than derived.

`UnstableEdgeClue.PAYING` is empty **by design**: solver windows are to be populated only from
a training corpus, over only the ranges seen in it. Several windows the critical-envelope
analysis can derive will never occur in any corpus, because reaching them requires the psyboid
to act against its own interest — and a window that covers behaviour no psyboid exhibits is
pure cost, since it admits every boid inside its band and can only ever spend true negatives.

One methodological caution survives independently of the discarded numbers: **warm the corpus
properly.** At a 500-tick warmup a scene is routinely explained by a crossing 250 ticks
earlier, so the flock has not settled where it counts.

### The exit classifier, rebuilt

`ExitAudit` was **unsound in both directions until 2026-08-29** and has been rebuilt on
`CriticalEnvelope`. Kept here because it invalidated figures that are quoted in several places,
and because two of the three defects are worth not repeating.

1. **It was circular.** It read `SolverFacts.windowsInto(...)` and asked whether a candidate fell
   in a recorded band — while being the ground truth those bands are *validated against*. Every
   sighting count it produced was a statement about band geometry rather than about flocking.
2. **The version before that was wrong the other way.** Sufficiency was tested by recomputing a
   decision with one candidate as sole neighbour, but `EdgeInfluence.steer` returns `0` out of
   range and behind the FOV, so once a suspect was committed **every distant boid passed as a
   sufficient leader.** The "85 of 89 led" figure in `HINTS.md` §8 over-counts.
3. **Cause was never measured.** `widened` and `separating` were declared and never incremented,
   so every sighting was labelled `ALIGN` and the widening path was a silent no-op.

All three are gone: it reads no `SolverFacts`, attributes at envelope entry rather than by
recomputing a committed decision, and takes cause from the envelope tables. The one thing kept
from the broken edit was right — `EdgeNavigation.exitTurns` in place of the `straightTo` filter,
since an exit is a property of the edge pair, which recovered ~30% of branch crossings the old
filter silently dropped.

Current standing, 40-plan corpus over arcs `2->1` and `5->6`: **553 exits, 300 under an
override, 250 with a leader under the true constants, 1 needing the diluted model, 2
unclassified, 0 without an envelope entry.** The two are a multi-leader baton pass and are
unclassified by design — `ROADMAP.md` §1.

---

## Map of the code

One package, `src/boids`, 52 files.

**Simulation** — `Params` (constants; never edited) · `MovementLogic` (the flocking rules and
the single definition of what a boid perceives) · `Aggregation` (candidate ways of condensing
several neighbours into one direction; `CURRENT` is what the simulation does and is the default
everywhere) · `Boids2DEngine` (one tick in index order,
with the `Trace` tap) · `MovementControl`, `BoidArray`, `Engine` (the decision interface) ·
`PsyboidOverride` · `Sim` (the state container) · `ScenarioParameter`,
`PresetScenarioParameter` (registered maps).

**Maps and storage** — `MapStore` (content-addressed ingests) · `Derived` (the structure and
behaviour tiers: an artifact is addressed by everything it is a function of) · `NavMap`,
`NavMapBuilder` (viability kernel, successors, predecessors, the veto).

**Macro structure** — `EdgeNavigation` (per-edge navigation, stability, scoring, exit turns) ·
`EdgeMetric`, `EdgeMetricStore` (the clock) · `EdgeWeights` (transition weights) ·
`EdgeDistance` (distance across routes).

**Critical envelope** — `CriticalEnvelope` (the envelope, settled states, admitted pairings),
stored by `CriticalEnvelopeStore` · `EdgeInfluence` (`steer`, the single-neighbour closed form) ·
`EdgeSlice` (bands at a fixed tau) · `Flocking` (constants as an argument, and `diluted()`).

**State sets** — `StateSet` (the algebra: `partialTick`, `closed`, `expandByAgreement`) ·
`MapStates` (that algebra bound to one map, plus `pureStable` and the straight-travel cycles).
Behind an interface because stable+ is not yet defined and is expected to change.

**Surveys** — `EdgeOccupancy` (how fast a flock forgets its spawn, and three spawn rules
beside each other) · `AggregationSurvey` (candidate aggregations scored against the simulation on
sampled arrangements, with a fidelity check that the baseline *is* the simulation).

**Exhaustive and sampled** — `TwoBoid` (all reachable two-boid arrangements) ·
`ThreeBoidPhase` (three-boid arrangements sampled and mapped by phase difference, since three
will not enumerate) · `ThreeBoidSamples` (one replayed arrangement per hand-marked region of
that map, drawn at envelope entry).

**Solving** — `SolverFacts` (what a solver may know) · `SolverStore` (builds and stores it) ·
`Solver` · `Clue` · `UnstableEdgeClue` · `SolverScore` (how an answer is graded).

**Psyboid** — `PsyboidBits` (bit-string search over branch decisions) · `PsyboidCorpus`
(plans, verified by replay) · `CorpusPreset` (named recipes, so a corpus is addressed by the
settings that produced it). See `CORPUS.md`.

**Audit** — `ExitAudit` (a shareable `Tables` plus a cheap per-thread instance) ·
`CriticalEnvelopeStore` · `ExitRender`.

**Rendering** — `Boids2DRenderer` · `Renderer` · `NavMapRender` · `EdgeGraphRender` ·
`StateSetRender` (state sets projected to `(x, y)`, several to a sheet) ·
`SceneRender` · `TwoBoidRender` · `TwoBoidRouteSheet`.

**Driver** — `SimTest`, 3,986 lines. Holds every entry point below *and* the whole
decomposition algorithm. Splitting the algorithm out is an open item in `ROADMAP.md`.

## Entry points

All in `SimTest`, all taking the gate as `(horizontal, line, lo, hi, dir)`. Known-good gates:
**dabeone** `x=202, y=[174,191]`, decreasing → 9 edges; **plait** `y=360, x=[335,350]`, both
ways → 6 edges.

| call | answers |
| --- | --- |
| `decompose` | cut the map into edges; writes the decomposition and graph renders |
| `labelFor` | the same, as a `Labelling`, without the reports |
| `metric` | edge lengths and tau ranges |
| `corpus` | does the clock match ticks actually flown (`sd/mean`, `rms-100`) |
| `tickField` | the two per-pixel field maps; diagnoses local smoothness, not accuracy |
| `synthetic` | Markov-chain journeys, for ranking weighting schemes |
| `steering` | measured steering marginals |
| `envelopeReport` | sizes of the sets the leader search runs over |
| `influence` | where a second boid could induce a saving turn |
| `slice` | leader bands at one tau |
| `windows` | the critical-envelope windows for one arc; writes `windows/window_f_t.tsv` |
| `census` | which windows the audit actually sees, and how often |
| `solve` | grade the solver on synthetic overrides |
| `graded` | grade the solver on the plan corpus |
| `scoringFloor` | what one psyboid scores alone, and every plan against it. Writes `floor.tsv` |
| `scoringLaps` | one solo seed's passes lap by lap, with the route each came round on |
| `EdgeOccupancy.run` | how fast edge occupancy forgets the spawn, under each spawn rule. Not in `SimTest` |
| `EdgeOccupancy.warmupScoring` | % of seeds that score with no psyboid, per candidate warm-up. Not in `SimTest` |
| `solverInvariants` | assert what the solver must do with no windows |
| `envelope` | build one arc's critical-envelope table and report it |
| `chains` | how far a history walks back inside an edge, and by what |
| `census` | audit plain seeds — few exits, so mostly a smoke test |
| `auditCorpus` | audit the psyboid corpus. **The one that has enough exits to mean anything** |
| `renderUnexplained` | draw every exit nothing accounts for, with its influence decomposition |
| `steeringHistory` | per tick, what *every* neighbour accounts for — finds multi-leader histories |
| `renderTick` | one exit at whole-map scale, for structural rather than influence questions |
| `stablePlus` | the map-wide stable set and what agreement expands it to, per edge |
| `stablePlusScan` | the same over a range of agreement ratios, with cost-to-leave and a render |
| `aggregationPhaseMaps` | the phase map flown under each candidate aggregation, cross-tabbed against the baseline |
| `proposedPhysics` | the whole pipeline — stable+, tables, phase map — rerun under a proposed aggregation, with the closed-form check |
| `phaseMapOnStablePlus` | the three-boid phase map with stable+ as both the suspect population and admission's ground, plus a control |
| `tablesOnStablePlus` | the critical-envelope tables for one arc on that same ground |

`ThreeBoidPhase.run`, `ThreeBoidSamples.run` and `ThreeBoidSamples.explain` are the other entry
points and do not live in `SimTest`. `explain` is the per-region counterpart of
`steeringHistory`: it prints one sampled exit tick by tick, marking which ticks actually **demand**
a leader and which are free because coasting or the veto produced the move anyway.

`SimTest.main` is a scratch dispatcher, not an interface. `PIPELINE.md` gives the real
invocations in order with expected numbers.

## Artifact index

**Every artifact is addressed by everything it is a function of.** That is `Derived`'s whole job:
a path names the map, and then the inputs each further tier adds, so nothing can be read against
inputs it was not built from. Before 2026-09-04 the discriminating input was recorded in an
artifact's *content* — the physics version in `meta.txt`, the gate in the edge graph's title —
which cannot stop a reader picking up the wrong file, and `solver/facts.bin` and
`psyboid/plans.tsv` were being silently overwritten and silently reread.

```
ingests/<map>/                                pixels, radius, navigability, trap-trimming
  map.png  display.png  meta.txt
  structure/<structure>/                      + step geometry, gate, weighting scheme
      edges/  metric/                         meta.txt
    behaviour/<behaviour>/                    + physics version, flocking constants, aggregation
        envelope/  windows/  corpus/  audit/
        influence/  twoboid/  psyboid/  solver/    meta.txt
```

**Nothing in the structure tier reads a flocking constant**, so a physics change costs the
behaviour tier and leaves the clock's thousands of gradient steps alone. Each tier writes a
`meta.txt` naming its inputs, because a hash nobody can explain is a hash nobody will trust.

| path | produced by | what it is |
| --- | --- | --- |
| `areas/<name>/<name>.png` | hand | the editable map. A design document |
| `areas/<name>/ingests.txt` | `MapStore` | which hash is which build |
| `ingests/<map>/map.png` | `MapStore` | frozen map. **Physics reads only this** |
| `ingests/<map>/display.png` | `MapStore` | dead pixels as wall. Rendering only |
| `ingests/<map>/meta.txt` | `MapStore` | dimensions, pixel counts, radius, physics version |
| **structure tier** | | **map geometry, gate and weighting scheme** |
| `<structure>/meta.txt` | `Derived` | what this hash is of |
| `<structure>/edges/decomposition.png` | `SimTest.decompose` | where each edge lies |
| `<structure>/edges/graph.dot`, `graph.html` | `EdgeGraphRender` | the edge graph |
| `<structure>/edges/tick_*.png` | `SimTest.tickField` | the per-pixel field maps |
| `<structure>/metric/metric-*.bin` | `EdgeMetricStore` | the clock, keyed on inputs *and* `FORMAT` |
| **behaviour tier** | | **+ physics version, flocking constants, aggregation** |
| `<behaviour>/meta.txt` | `Derived` | what this hash is of |
| `<behaviour>/envelope/envelope-*.bin` | `CriticalEnvelopeStore` | the CEA pairing tables, keyed on inputs, the admission ground *and* `FORMAT`. Minutes to build, instant to load |
| `<behaviour>/envelope/arc_<f>_<t>.tsv` | `SimTest.envelope` | the same tables in readable form, for inspection only |
| `<behaviour>/windows/window_<f>_<t>.tsv` | `SimTest.windows` | critical-envelope bands, tau by tau |
| `<behaviour>/influence/*.png` | `SimTest.slice`, `influence`, `envelopeReport` | where a leader could be. **Moved out of `edges/`, which mixed these with structure** |
| `<behaviour>/corpus/*.tsv` | `SimTest.corpus`, `steering` | flown journeys against the clock; measured steering marginals |
| `<behaviour>/twoboid/` | `TwoBoid` | reachable pairs. 254 MB; rebuilds in ~17 s |
| `<behaviour>/audit/exits_*.tsv` | `ExitAudit` | every classified exit |
| `<behaviour>/psyboid/<preset>-<hash>/plans.tsv` | `PsyboidCorpus` | the plan corpus. The label is the artifact; the recipe is in the path. See `CORPUS.md` |
| `<behaviour>/psyboid/<preset>-<hash>/floor.tsv` | `SimTest.scoringFloor` | every plan's scoring rate against what its psyboid scores alone |
| `<behaviour>/occupancy/decay-<rule>-<seeds>s<window>w.tsv` | `EdgeOccupancy` | edge occupancy per 50-tick window against the long run, one file per spawn rule |
| `<behaviour>/psyboid/<preset>-<hash>/meta.txt` | `Derived` | which recipe, and its every setting |
| `<behaviour>/solver/facts.bin` | `SolverStore` | everything a solver may know |
| **loose renders** | | **gitignored, regenerable** |
| `render/phase<f>_<t>.png` | `ThreeBoidPhase` | the three-boid phase map, one panel per route pair |
| `render/phase<f>_<t>-replays.tsv` | `ThreeBoidPhase` | every exit in it, with the three start states, so any cell can be flown again |
| `render/*-samples.png`, `-atlas.png` | `ThreeBoidSamples` | one replayed arrangement per region or clump, at envelope entry |
| `render/prop<f><t>-*.png` | `SimTest.proposedPhysics` | one physics against another, end to end, per arc |
| `archive/<date>/` | hand | retired output, ignored. **Not a backup** — the maps it came from are in `areas/` |

**Retired 2026-09-04.** `analysis/`, `ingests/` (the eighteen pre-physics-3 hashes), `render/`,
`packet/`, `data/` and `out/` moved to `archive/`, 842 MB. Everything in them was either stale
under physics 3 or loose analysis that had served its purpose, and every map is reproducible from
`areas/`, which is versioned. The frozen `map.png` and `display.png` for the surviving ingest came
back **byte-identical**, which is the check that the map itself did not change.
**Deleted, 2026-08-27 to 29.** `cases/`, `transcript.pdf`, `routes/`, `psyboid-packet.zip`,
`Proposal.txt` and `ANSWER-KEY.txt`. The shipped case packet was invalidated wholesale by
physics 2; it will be rebuilt as the last step of the project rather than restored, so its
artifacts and the answer key to them carried no value. `routes/` was superseded by programmatic
decomposition, and its traces started from a state that is dead under physics 2.

**Kept locally, not versioned.** `packet/` (the stale assembled packet), `analysis/` (its
producer no longer exists), `data/` (sweeps for maps no longer worked on), `render/`,
`transcripts/`, and `<ingest>/routes/` on dabnt — hand-annotated edge maps, superseded.

`Proposal.txt` held the original brief. It is worth knowing it existed and that the shipped
format diverged from it substantially: it described 25 case folders, Python, and a
`culprits.json` answer file, none of which is what the project became.

## Documents

| file | what it is for |
| --- | --- |
| `CLAUDE.md` | working agreements. Auto-loaded every session |
| `README.md` | **this file** — what exists and where |
| `GLOSSARY.md` | every term with a precise meaning, and its code name |
| `EDGES.md` | canonical for edges, routes and leader windows |
| `ROADMAP.md` | what is being built now, and the specifications for it |
| `PIPELINE.md` | every step from a PNG to the current analysis, with real invocations |
| `CORPUS.md` | canonical for corpora: generation, addressing, and which numbers to read |
| `HINTS.md` | transferable knowledge; also the training-wheels condition of the evaluation |
| `CONTRACTS.md` | what a play area guarantees. Partly historical — see its header |
| `BACKLOG.md` | parked work, with the reasoning for parking it. Ageing |
| `SESSION-LOG.md` | what each session did. Append before ending |
| `HAPPY.md` | the user's standing feedback on what has and has not worked here. **Read once at the start; never write to it** |
| `PROMPTS.md` | every prompt given about this project. **Generated by a `SessionStart` hook; never hand-edit. Not required reading** |

Reading order for someone picking this up: this file, then `HINTS.md` §0a, then `EDGES.md`,
then `ROADMAP.md`.

## Bookkeeping

Append to `SESSION-LOG.md` before the session ends — what was attempted, the numbers, what
changed, what is now known broken. Conclusions go in the document that owns them; the log is
the audit trail, not the state. And write the frontier down when work *starts*: two lines in
`ROADMAP.md` saying what is being built and where it will live. Work recorded only on
completion is the work that never gets recorded, and it is exactly the work a later session
reinvents.
