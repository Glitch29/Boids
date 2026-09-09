# From a PNG to the current analysis

Every step needed to take a hand-drawn play area to the level of analysis dabeone currently
has. Written as the sequence actually runs, with the real invocations and the real numbers, so
a new map can be taken through it and so the parts that still need a human can be seen.

Worked throughout on **dabeone**: 379×407 px, turning radius 40, ingest hash
`609cffdb84be218c`, 9 edges, 136,276 live states. **plait** has since been taken through the same
sequence from its PNG — ingest `46f880d41d2c1e4e`, 6 edges — which is what checked that it is a
sequence and not a description of one map.

**As of 2026-09-06 there is a single entry point**, and the steps below are what it does:

```java
PsyboidCorpus.Corpus c = Pipeline.corpus(preset, gate, CorpusPreset.PLANS_40);
```

A map and a gate in, a verified corpus out. Read on for what each tier is and how to inspect it;
read `CORPUS.md` for what the recipe resolves per map and what still blocks running unattended.
The gate is the only argument that is neither the map nor a named recipe.

**Status:** 2026-09-08, physics 3. **The corpus step is gone** — `Pipeline.corpus` and everything
under it were removed on 2026-09-08 with the search that produced plans; `Pipeline.build` still
takes a map and a cut line to solver facts, and step 18 onward has no implementation. See
`ROADMAP.md` §0i. **Every path below moved**: derived output is addressed by
the inputs it depends on, in a structure tier and a behaviour tier under the ingest — see
`README.md`'s artifact index and `ROADMAP.md` §0c. Entry points take a `Derived.Structure` or
`Derived.Behaviour`, which `SimTest.structure` and `SimTest.behaviour` build from the gate and the
constants a call site already has. Steps 1-10 are otherwise current. **Step 11 was rewritten** on 2026-08-29:
`ExitAudit` is now table lookup over `CriticalEnvelope` and the invocation below is out of date,
though the plumbing it describes is not. Steps 12-17 were added 2026-08-28 to 30; step 13a on 2026-09-05.

**Terminology.** This file writes *tick* in places where it means **tau**, a state's position
along its own edge. Canonical now: **tau** for position, **tick** for simulation time.

---

## 1. Draw the map

A PNG where **`#000000` is out of bounds** and everything else is playable. Scoring regions are
distinguished by colour.

The designer, not the code, guarantees the three contracts in `CONTRACTS.md` — Traversability,
Complexity, and Safety at Tangents. **They are deliberately unverified.** Their purpose is to
say that any corner case that can only arise when one is violated has arbitrary behaviour, so
the navmap is entitled to assume them.

Practical notes:

- MSPaint antialiasing produces off-palette pixels at edges. There is an established way of
  dealing with it rather than maintaining a list of what counts as which colour.
- A play area smaller than about **3× the turning diameter** is the one easy way to break
  flocking. Otherwise boids are extremely tolerant and no tuning is needed.
- The map is the design surface. Several structural problems on plait were fixed by editing
  the map rather than the code, and that was the right call each time.

Lives in `areas/<name>/<name>.png`.

## 2. Register it

Add a constant to `PresetScenarioParameter`:

```java
DABEONE ("dabeone.png", 40f, 4),   // filename, turning radius, default flock size
```

The turning radius is load-bearing everywhere downstream — speed, both perception radii and
the navmap all derive from it. It is never a free constant.

## 3. Ingest — freeze the map

```java
MapStore.Ingest ingest = PresetScenarioParameter.DABEONE.ingest();
```

Happens automatically on first access. Content-addresses the source by SHA-256 and writes
`ingests/<hash>/`:

| file | what |
|---|---|
| `map.png` | the frozen map. **The only file physics may be computed from.** |
| `display.png` | the same map with dead pixels painted as wall; rendering only |
| `meta.txt` | dimensions, pixel counts, radius, navigability, physics version, source |

**Do not compute from `display.png`.** Dead pixels are load-bearing — they form a band hugging
every wall, in play but too late to turn away from, and a boid flying along a wall sweeps its
four-pixel step through that band. Blanking them blocks the step. On dab at radius 40, 1,565
dead pixels cost **19,422 live states**, a tenth of the kernel.

The hash covers radius, navigability, trap-trimming and `Params.PHYSICS`, so changing the
physics produces a different ingest rather than silently reinterpreting an old one. Everything
derived from a map is written **inside its own ingest folder**, so a result can never be read
against a map that has since been edited.

*If you edit the map, edit the source and re-ingest. Editing the frozen copy is the one way to
lose work here.*

## 4. Build the navmap

```java
NavMap map = NavMapBuilder.buildFromPng(preset.mapPath(), Math.round(preset.turningRadius()));
```

The **viability kernel** over `(x, y, heading)` — every state a boid can survive from, meaning
it has both an infinite future and an infinite past (`Navigability.BIDIRECTIONAL`). On dabeone
that is **136,276 live states** out of 379 × 407 × 64.

The guarantee this buys: from a live state the collision layer can always find a live turn, so
**a run that starts live stays in play forever**. Boids are only ever placed at
initialisation, only in live states, and a boid leaving the image throws rather than being
respawned or clamped.

`NavMap` also provides `successor(state, turn)` (veto included), `steeredSuccessors`,
`steeredPredecessors` and `unsteeredPredecessors`. Predecessors are **FLIP → MOVE → TURN →
FLIP**; verified against brute force over all live states with zero mismatches.

Render a check with `NavMapRender.write(map, 1, out)`. Any **red** pixel is a trap — a place
with no survivable heading at all — and should not exist in a sane map.

## 5. Choose a gate and decompose

```java
SimTest.Labelling l = SimTest.labelFor(DABEONE, /*horizontal=*/false, /*line=*/202,
                                       /*lo=*/174, /*hi=*/191, /*dir=*/-1);
// or SimTest.decompose(...) for the same thing plus reports and the graph render
```

**This is the one step that genuinely needs a human.** The gate is a line across a corridor
and it must be a **real cut** — every cycle in the map must cross it. A bad gate does not fail
loudly; it corrupts the partition.

Known-good gates:

| map | gate | edges |
|---|---|---|
| dabeone | `x=202, y=[174,191]`, decreasing | 9 |
| plait | `y=360, x=[335,350]`, both ways | 6 |

**Health check:** arrival count. Far below a corridor's cross-section means the line is wrong —
62 arrivals against a healthy 120–180 signalled a bad gate that made refinement run away to
493 edges.

What the algorithm does, per the axiom in `HINTS.md` §3:

1. `O` = points that can reach themselves without crossing the gate. Split by connectivity →
   **orbits**, one edge each.
2. Merge orbit phases (`mergePhases`) — the ~4px step means pixels four apart are one
   trajectory sampled a tick apart.
3. Complement of `O`, split by **forward and backward** connectivity → candidate edges.
4. `mergeAlongside` — merge edges within one tick's travel and ±1 heading **mod 32**. Excludes
   orbits; that exclusion is load-bearing (54.8% of one plait region lies alongside an orbit).
5. `refine` — split any edge whose points differ in predecessor or successor edge sets.
   Terminates. **Gives up silently above 63 edges** (64-bit mask).
6. `splitOrbits` — cut each orbit with its own gate.

Writes `ingests/<hash>/edges/decomposition.png`, `graph.dot` and `graph.html`. **The gate
parameters are recorded in the graph title** — currently the only place they persist.

## 6. Per-edge navigation properties

```java
EdgeNavigation.EdgeNav[] navs = EdgeNavigation.analyse(map, live, liveCount, edge, edges);
EdgeNavigation.Properties props = SimTest.properties(preset, l);
```

Gives per edge:

- `straightTo` — where unsteered travel leads. **A dominant destination, not a universal one**:
  20 states of dabeone's edge 2 go straight to edge 1, 16 of edge 4 to edge 0, 18 of edge 5 to
  edge 6. Those are follow-through states, where a boid already turned into a branch is still
  labelled on the old edge.
- `stable` — unsteered travel returns without scoring. On dabeone the only unsteered cycle is
  **2 → 7 → 4 → 2**, so edges {2,4,7} are stable and {0,1,3,5,6,8} are not.
- `scoring` — least fixed point of three rules (holds scoring states; leads somewhere scoring;
  every way in comes from somewhere scoring).

**This is the snapshot-only test for "requires explanation": a boid on an unstable edge.**

## 7. The clock

```java
double[][] chain = EdgeWeights.blend(new double[][]{{1,1,1},{1,1,1},{1,1,1}}, 0);
EdgeMetric.Metric m = EdgeMetricStore.of(
        SimTest.structure(preset, horizontal, line, lo, hi, dir).at("metric"),
        l.map(), l.edge(), l.live(), l.liveCount(),
        l.edges(), EdgeWeights.Scheme.MOMENTUM, chain);
```

That invocation is the **lifted memoryless** weighting, which is the best measured. It gives
every state a tick and every edge a **real-valued** length. Cached in the ingest, keyed on the
map, the live set, the labelling, the scheme *and the format version*.

**Bump `EdgeMetricStore.FORMAT` whenever the way a clock is computed changes.** The key is
built from inputs, so improving the solver otherwise leaves every key where it was and the
store hands back answers from the old code without a word. This has already caused one wrong
conclusion.

Expect roughly, under this weighting: dabeone lengths ≈ 158.14, 158.62, 100.59, 100.97, 93.73,
102.59, 71.84, 80.97, 72.76; 3,000–8,000 CG steps; residual ~1e-8. (Under `UNIFORM` the same
edges come out ≈0.8–1.7 shorter — the lengths are weighting-dependent, so never compare across
schemes.) Inverse pairs should come out near-equal — nothing in the solve knows about
inverses, so that agreement is a free correctness check.

Distances then come from `EdgeDistance.between(edge, m, from, to, cap)`, where
`cap = ticks + Σ all edge lengths`. **The cap belongs on the estimate, not the route sum** —
plait's edges are 753 long, and a cap of 3 × ticks pruned every route leaving the start edge,
producing errors of 815 ticks.

## 8. Validate against a corpus

```java
SimTest.corpus(preset, false, 202, 174, 191, -1,
               new int[]{1,2,4,8,16,32}, 1024, 100, EdgeWeights.Scheme.MOMENTUM, chain);
```

Flies short journeys from cold starts and compares flown ticks against the clock's estimate.
Two numbers matter: **`sd/mean`** (do journeys agree with each other — forgives a uniformly
fast or slow clock) and **`rms-100`** (do they agree with the truth). Current best is ~1.65%
and ~2.01% on dabeone.

Also available: `SimTest.tickField(...)` for the two per-pixel field maps, and
`SimTest.metric(...)` for lengths and tick ranges. Note the field maps get **worse** as corpus
accuracy improves — they diagnose local smoothness, which is in tension with journey accuracy.

## 9. Exact two-boid reachability

```java
TwoBoid.Reachable r = TwoBoid.explore(map, turningRadius, live, liveCount, startP, startB);
TwoBoid.write(ingest.output("twoboid", "reachable.bin.gz"), r);
```

Seed from a warmed-up two-boid run: advance 500 ticks, then take `(successor(p_end, 0), b_end)`
— the state is sampled **after the psyboid moves and before the boid does**, which halves the
space and loses nothing.

Costs a 2.16 GiB bitset and about 17 seconds on dabeone. Pack the frontier as `(p<<32)|b`, not
as the bit index — recovering indices from a bit number needs a 64-bit division that otherwise
dominates the runtime.

Yields directly: which edge transitions a psyboid can induce at all
(`TwoBoid.boidEdgeMoves`), which states the boid can never occupy, and how tightly one boid's
position constrains the other.

## 10. Windows

```java
SimTest.windows(preset, false, 202, 174, 191, -1, /*from=*/4, /*keep=*/0,
                EdgeWeights.Scheme.MOMENTUM, chain, Flocking.of(turningRadius));
```

For each transition that requires explanation, where a leader must be, tick by tick. Run it for
each steered transition the two-boid census found — on dabeone `4→0`, `2→1`, `5→6`.

Only the **opening band** is meaningful; widths saturate to whole edges within a few ticks
because the search lets a leader weave to hold station.

To widen, pass a modified `Flocking` — halved `straightBias`, and doubled `wSep` for separation
windows. **Never edit `Params`**: every corpus and ingest taken under the old constants would
silently stop meaning what it meant.

## 11. Audit a corpus ⚠ unsound

> **Do not rely on this step's output.** `ExitAudit` is wrong in both its committed and its
> working-tree form: the committed sufficiency test passes every distant boid, and the
> working-tree version reads the very windows it is supposed to validate. `ROADMAP.md` §2 has
> the specification it is being rebuilt to. The invocation below is retained because the
> plumbing — the mid-tick `Trace` hook, the warmup, the rendering — is unchanged and correct.

```java
ExitAudit audit = new ExitAudit(map, l.edge(), m.tick(), straightTo, turningRadius, overrides)
        .widenedBy(relaxed)
        .rendering(ingest.display(), Path.of("render", "recent"), 3);
engine.trace(audit);
```

Rides the `Boids2DEngine.Trace` hook, which fires **mid-tick with the live arrangement** — the
one the suspect actually decided against, with earlier boids moved and later ones not. That is
why the classifier is exact about sequencing, and why a picture of a failure has to be drawn
inside the callback.

Accounts for every route change as **psyboid** or **led**. Warm up 500 ticks first: `5→6`
occurs only in the first 500 and then never again in 1.28M boid-ticks.

**No expected counts are given.** The figures that used to be here came from the unsound
classifier and have been withdrawn; see the warning above.

---

## 12. Build the solver's facts

```java
SolverFacts f = SolverStore.prepare(preset,
        new SolverFacts.Gate(false, 202, 174, 191, -1),
        EdgeWeights.Scheme.MOMENTUM, chain, Flocking.of(turningRadius));
```

Everything a solver may know about a map before it is shown a scene: the decomposition,
stability and scoring, the clock, `straightTo`, `exitTurn`, and the windows. Minutes of work
on a first call and a file read afterwards, at `ingests/<hash>/solver/facts.bin`.

**`prepare` is the manual half and is meant to be called deliberately, by hand, once per map
version.** `SolverStore.load` is what a solve calls, and a missing file is an error rather than
an invitation — the alternative is a solver quietly spending four minutes doing map-level work
in the middle of answering a question about a photograph.

**Bump `SolverStore.FORMAT` whenever the meaning of anything stored changes**, for the same
reason `EdgeMetricStore` carries one. These facts are the solver's entire model of the map, and
a stale one does not look stale — it answers a slightly different map's questions.

## 13. Answer a scene

```java
Solver s = Solver.of(PresetScenarioParameter.DABEONE);   // load; never builds
int[] candidates = s.candidates(state);
System.out.println(s.report(state));
```

What arrives is a `Sim.State` and nothing else — no tick, no history, no correspondence
between frames. One name means the scene determines the answer; several means it does not yet,
which is a real outcome rather than a failure. An empty list is also real: it says no single
boid accounts for everything the arrangement demands.

`report` prints each clue's verdict per boid, with the edge each boid is on and a `*` on edges
unsteered travel does not keep a boid on — which is how you see *which* clue did the excluding.

## 13a. Generate a corpus, and check that it scores

```java
CorpusPreset recipe = CorpusPreset.PLANS_40;
PsyboidCorpus.Corpus corpus = PsyboidCorpus.build(preset, facts, recipe);
SimTest.scoringFloor(preset, facts, recipe, corpus);       // and scoringLaps for one seed
```

**Grading needs a corpus, and a corpus needs checking before anything is graded on it.** `build`
searches every seed, verifies each plan by replaying it from its own label, and writes
`<behaviour>/psyboid/<preset>-<hash>/plans.tsv`. Six seconds for forty seeds on dabeone.

What to read off it, in order:

1. **Every plan reproduced.** `build` throws otherwise; a corpus that does not replay would
   corrupt everything downstream silently.
2. **Fidelity is exact** — every turn asked for was crossed, nothing crossed unbid. 248 of 248
   on dabeone, 40 of 40 plans matching branch for branch.
3. **`scoringFloor` puts every plan against what its own psyboid scores alone.** On dabeone the
   floor is 54 points every 533 ticks, `sd 0.00` across all forty seeds, and the corpus sits at
   0.998x it. A plan well below its own solo rate is the signal that something is wrong with the
   search, the warm-up or the window — not that the seed was hard.
4. **Control should be zero, or explained.** Non-zero control is scoring the psyboid does not
   account for.

**Do not read a rate off the usable window without dividing by whole laps.** The window is 5.14
laps long, so it catches five passes or six and reads about 12% high with a 4.6% spread that is
pure quantisation. `CORPUS.md` has the treatment.

**The warm-up is a per-map number and wants measuring, not inheriting.**

```java
EdgeOccupancy.run(preset, facts, plus, behaviour, 2000, 50, 20_000, 5, 40_000, 60_000);
```

Flies psyboid-free seeds under each spawn rule and reports how fast the flock's distribution over
edges forgets where it started. On dabeone the uniform spawn is done at **tick 500** and the rest
is a permanent oscillation — a boid's phase is conserved, so a warm-up cannot mix it. `WARM` is
still 5,000; `ROADMAP.md` §0f has the three options and why the choice is not a measurement.

## 14. Grade the solver

```java
SimTest.graded(preset, false, 202, 174, 191, -1, scheme, chain, flock, /*every=*/13, clue);
SimTest.solve  (preset, false, 202, 174, 191, -1, scheme, chain, flock, seeds, warm);
```

`graded` runs the plan corpus at `ingests/<hash>/psyboid/plans.tsv`; `solve` runs synthetic
overrides spread along a timeline.

**The grade is `SolverScore`, a penalty to be minimised.** Each answered class is given the
K-weighted psyboid rate actually found inside it, and every boid is scored on squared error
against that, a psyboid counting `K = 3` times a boid. Where the hedge caps do not bind it
collapses to `g(K·FN, TN) + g(K·TP, FP)` with `g(a,b) = ab/(a+b)`.

The property that makes it usable: **abstaining is the worst attainable score, and every
uninformative assignment ties with it exactly.** So the number cannot be inflated by being
indiscriminately generous or indiscriminately strict, and it moves only on discrimination.
`SolverScore.normalised` rescales to **1.000 perfect, 0.000 knowing nothing**, which is what
`graded` averages across plans. Not to be confused with a run's *score*, which is what the
psyboid maximises.

Three things that decide what the grade means:

- **Warm properly.** At 500 ticks a scene is routinely explained by a crossing 250 ticks
  earlier, so windows get paid to cover cold-start behaviour and look valuable when they are
  not. 2,500 reversed the conclusion.
- **Average within a plan, then across plans.** Scenes inside one plan are not independent
  samples; the plan is the sample.
- **Sample while the override is still running.** A scene taken only after a plan finishes
  contains no psyboid — just a boid that used to be one.

---

## 15. Build the critical-envelope tables, and audit a corpus against them

```java
CriticalEnvelope.pruneOutOfRangeLeaders = true;          // approximate; see ROADMAP §1
ExitAudit.Tables tables = ExitAudit.Tables.of(
        SimTest.behaviour(preset, horizontal, line, lo, hi, dir, flock).at("envelope"),
        l.map(), l.edge(), l.live(), l.liveCount(),
        new int[][]{{2, 1}, {4, 0}, {5, 6}}, flock, flock.diluted());
SimTest.auditCorpus(preset, false, 202, 174, 191, -1, arcs, flock, flock.diluted());
```

Minutes per arc on a first call and **nothing at all afterwards** — the tables are content-
addressed into the ingest and shared immutably, so one build serves every consumer and every
thread. Bump `CriticalEnvelopeStore.FORMAT` whenever the meaning of a table changes.

`auditCorpus` replays the plan corpus rather than plain seeds, because **a settled flock almost
never leaves the main loop**: 553 exits from 40 plans against 15 from a comparable unsteered
run. A psyboid is what makes exits happen.

Expect on dabeone, arcs `2->1` and `5->6`: 300 under an override, 250 with a leader, 1 needing
the diluted model, 2 unclassified. Arc `4->0` separately: 175 / 160 / 0 / 20.

## 16. Map the three-boid phase space

```java
ThreeBoidPhase.run(preset, l, facts, tables, 4, 0, /*band=*/8, /*kBoid=*/8, /*kPsy=*/8,
                   /*resolution=*/0.5, /*targetFill=*/0.9995, seed, out);
```

Where the residue lives. Two axes of phase difference — psyboid and third boid, each against the
suspect — one panel per pair of **simple loops** from the suspect's edge back to itself. Run
length is coverage rather than compute: it stops after `1/(1-targetFill)` consecutive attempts
that land only on known cells, and anneals `k` to zero once on the way so that placements a
coasting boid cannot reach still get sampled.

Resolution 0.5 fills 7.05M cells to 99–100% per panel in **86 seconds**, so this is cheap to
iterate on; 0.25 is affordable. Every exit is written with its three start states, so any cell in
the picture can be flown again.

## 17. Sample the marked regions of the phase map

```java
ThreeBoidSamples.run(preset, facts, tables, 4, 0, /*resolution=*/0.5,
                     Path.of("analysis", "3BoidAreasOfInterest.png"),
                     Path.of("render", "phase40.png"),
                     Path.of("render", "phase40-replays.tsv"),
                     /*columns=*/4, /*scale=*/2, out);
```

**A human marks the regions first.** Open the phase map, paint each distinct feature over in
**rose `#FFAEC9`**, and paint **green `#22B14C`** over any region believed to hold more than one
behaviour. Save it beside the original, not over it — the overlay is an input and is versioned;
`render/` is not.

Then this reads the two pictures, recovers each painted patch as a connected component (joining
cells within 6 of each other, since paint lands only on cells of the class being marked and those
are dithered), picks the cell nearest each region's centroid whose recorded account matches what
the region was painted over, replays it out of the `-replays.tsv`, and draws the arrangement at
**critical-envelope entry** with a 200-cell crop of the marked map inset in the corner.

Expect on the dabeone `4->0` overlay: **20 regions, 21 tiles** (the green region is sampled twice,
split by whether the psyboid was under an override), 15 over unexplained white, 2 over
third-boid-led cyan, 4 over psyboid-led amber. Seconds, once the envelope tables are on disk.

⚠ **The overlay is bound to the exact pixels of the phase map it was painted on.** The dimension
check catches a changed resolution or route set; it cannot catch a phase map whose sampling
changed while its size did not. Repaint after any change to `ThreeBoidPhase`'s sampling.


To ask why one region's exit has no account, name its cell:

```java
ThreeBoidSamples.explain(preset, l, facts, tables, 4, 0, /*route=*/2, /*otherRoute=*/1,
                         /*cell=*/158, 255, replays, flock, flock.diluted(), out);
```

`steeringHistory` for an arrangement out of the phase map rather than the plan corpus, plus the
two things that decide admission — whether the suspect was on settled ground, and whether the
prune would have cut the step. **Read the `leader?` column first.** A tick marked `free` is one
coasting would have produced anyway, so every neighbour on the map "accounts" for it; only the
`DEMANDS` ticks carry information, and the summary restates coverage over just those.

---

## What still needs a human

1. **Drawing the map**, and guaranteeing the contracts.
2. **Choosing the gate.** Everything else follows from it, and a bad one corrupts the partition
   without complaining.
3. **Reading the decomposition** to confirm the edge count is sane. Expected ranges were known
   in advance for both maps (dabeone 9, plait 6–18) and that expectation caught real bugs.
4. **Building the facts** (step 12). Deliberate, once per map version, by whoever chose the
   gate and looked at what it produced.

Everything after step 5 runs without judgement calls, given the gate.

**The gate is the only place a human choice enters, and it is a bootstrap.** It exists to cut
cycles so a first decomposition can be constructed. It is not an analysis tool, nothing
downstream may depend on it, and it is carried in `SolverFacts.Gate` for provenance only. See
`EDGES.md` §2.
