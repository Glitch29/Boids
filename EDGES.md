# Edges

**Canonical for edges, routes and leader windows.** Rewritten 2026-08-28 against dabeone
ingest `609cffdb84be218c`, physics version 2.

**Status:** 2026-09-06. Structure is physics-independent and stands; **figures are physics 2
unless marked otherwise**, and the flown scoring lap in §6 and §9 is the first measured under
physics 3.

An edge is a set of live `(x, y, d)` states. Edges are **defined relative to one another** —
there is no line anyone draws and no geometry in the definition. This document states that
definition exactly, then everything derived from it.

---

## 1. What an edge is

### The axiom

> A set of edges is a **valid decomposition** of a bidirectionally-navigable map when
> **every live point is on exactly one edge**, and **all points on the same edge have the same
> set of predecessor edges and the same set of successor edges.**

That is the whole definition. It mentions no coordinates, no lines, no directions and no
distances. An edge is identified only by how it connects to the other edges.

A transition here is a permitted turn followed by a step: `NavMap.constrainTurn(x,y,d,t) == t`,
then move. Nothing else counts as adjacency.

### "Next edge" means the first *different* one

Successor edge means the first edge **other than this one** reachable over all legal turn
sequences — not whatever is one step away.

This matters more than it looks. One-step adjacency shatters every path edge immediately: the
interior of a path steps into itself, and only its last point steps out, so every point would
have a different one-step successor set and refinement would split until each point stood
alone. The correct reading closes over travel within the edge first.

### Both directions, always

Predecessor sets are **not optional and do not fall out of successor sets.**

Forward alone finds branches but is blind to merges: arriving from somewhere new creates no
forward distinction, because everything downstream of the merge has the same future whichever
way it arrived. Run forward-only on dabeone's main loop — which has two branches and two
merges — and it comes out as two segments where three are correct.

So refinement computes a fixed point in each direction and splits on the pair.

### What the axiom does at a branch and a merge

The same rule, read locally, is the intuition:

> **At a branch**, a state that can still reach both outcomes is on the **inbound** edge.
> **At a merge**, a state reachable from both inbound edges is on the **outbound** edge.

**An edge boundary is where the set of reachable futures, or reachable pasts, changes.** It is
a property of the graph. Nobody places it.

### Direction, not position

Most live pixels belong to **two** edges, one per direction of travel. Two edges can be
disjoint in `(x, y, d)` and overlap almost completely in `(x, y)`, running opposite ways
through the same corridor. Every decomposition has this mirror structure: on dabeone every
edge size appears twice, once travelling one way and once the other.

Where a corridor crosses another, **four** edges pass through the same pixels.

**Any representation keyed on position alone is wrong.** This is the single most common source
of error in this project, and the reason `(x, y)` is a visualisation space and nothing more.

### It is a test, not a constructor

Many decompositions satisfy the axiom. Every point as its own edge is valid; every point as a
single edge is valid. Something else has to choose which one you want — see §3.

### Refinement is free

The constructive consequence: whenever points within an edge disagree about their
next-or-previous edge sets, split the edge into one piece per distinct pair of sets. Repeat.

This terminates. In practice, more than a couple of iterations means something is wrong.

---

## 2. Gates are a bootstrap, not an analysis tool

A **gate** is a line segment in `(x, y)` used to cut cycles so that a first decomposition can
be constructed. That is its only sanctioned use.

**That a gate lives in `(x, y)` at all is the tell that it is unfit for analysis.** It cannot
distinguish the two directions of travel through a corridor, it has no meaning in
`(edge, tau)`, and its placement is a human choice that nothing downstream should depend on.
Anything that evaluates a gate where it should be evaluating an edge relation is a bug.

Gates are recorded in `SolverFacts.Gate` **for provenance only** — so a reader can reproduce a
decomposition. Nothing at solve time reads one, and nothing new should.

Known-good gates, kept because rebuilding a decomposition needs them:

| map | gate | edges |
| --- | --- | --- |
| dabeone | `x=202, y=[174,191]`, decreasing | 9 |
| dabnt | `x=202, y=[174,191]`, decreasing — **the same one** | 9 |
| plait | `y=360, x=[335,350]`, both ways | 6 |

**dabnt takes dabeone's gate unchanged**, found 2026-09-06 by trying it first and then sweeping
vertical lines: of every candidate tried, only that one decomposed into a sane edge count. The two
maps are the same geometry with and without a trap, so a gate that cuts every cycle in one cuts
every cycle in the other — which is a hint that a gate is a property of the corridor layout rather
than of the pixels, and that a sweep is a workable way to find one.

**Health check: arrival count.** A gate must be a *real cut* — every cycle crossing it. A bad
gate does not fail loudly; it corrupts the partition. An arrival count far below the
corridor's cross-section means the line is clipping something rather than spanning it. 62
arrivals against a healthy 120–180 signalled a bad gate that made refinement run away to 493
edges.

---

## 3. Constructing a decomposition

The axiom accepts many answers, so a construction picks a useful one. Gates enter here and
nowhere else.

1. **`O`** = the points that can return to themselves without crossing the gate — the union of
   cycles in the gate-cut graph. Its strongly connected components are the **orbits**, one
   edge each.
2. **Merge orbit phases.** A ~4 px step means pixels four apart are one trajectory sampled a
   tick apart; the three between belong to trajectories that never touch it.
3. **The complement of `O`** splits into components connected by forward *or* backward travel
   without leaving the complement. These are candidate edges.
4. **`mergeAlongside`** — merge edges within one tick's travel and ±1 heading **mod 32**. This
   must **exclude orbits**; 54.8% of one plait region lies alongside an orbit and merging it
   destroys the decomposition. Errant merges are otherwise harmless — refinement splits them
   again.
5. **Refine**, per §1.
6. **`splitOrbits`** — cut each orbit with its own gate.

An **orbit** is any edge some point of which can forward-navigate back to itself. The axiom
cannot split one: it is strongly connected, so every point reaches everything and all
next-edge sets are equal. It has to be cut. A single state is not enough — removing one state
from a strongly connected orbit leaves it strongly connected by another path.

**Placing an orbit's gate:** keep it away from **both** directions of traffic across the
orbit's boundary, `O → Oᶜ` and `Oᶜ → O`. Measuring only the outgoing side put dabeone's gate
at a junction adjoining four edges at once, which is what produced the 493-edge runaway.
Correcting the boundary test moved them to plain corridor and both orbits cut cleanly.

**Limit:** refinement gives up above **63 edges** (64-bit mask), `SolverFacts.MAX_EDGES`. The
error message distinguishes this from bad gate placement, but the limit remains.

**The edge boundary is the commit boundary.** The edges were designed to force that
equivalence: the tick a boid leaves an edge is the tick its choice of destination is locked in.
That is why an exit is reported at the crossing, even though it is *attributed* at an earlier
moment — see `ROADMAP.md` §1.

**Not implemented:** revert-on-invalid-merge. The spec calls for backing out to the uncut orbit
and retrying when a merged edge fails the axiom. Correct gate placement removed the need on
the current maps, but a bad gate still corrupts the partition rather than being rejected.

---

## 4. Per-edge properties

All from `EdgeNavigation`, all stored in `SolverFacts`.

**`straightTo[e]`** — where unsteered travel from `e` leads. **A dominant destination, not a
universal one.** The exceptions are **follow-through states**: once a boid has been turned
into a branch it stays labelled on the old edge for a few ticks, and straight travel from
there completes the crossing. On dabeone, 20 states of edge 2 go straight to edge 1, 16 of
edge 4 to edge 0, 18 of edge 5 to edge 6. Treating `straightTo` as universal inflated a
route-change count by 50%.

**`stable[e]`** — unsteered travel returns to the edge without scoring. **This is the
snapshot-only test for "requires explanation":** a boid on an unstable edge is somewhere
unsteered travel would not have left it. On dabeone the only unsteered cycle is
`2 → 7 → 4 → 2`, so `{2,4,7}` are stable and `{0,1,3,5,6,8}` are not.

**`scoring[e]`** — least fixed point of three rules: holds scoring states; leads somewhere
scoring; every way in comes from somewhere scoring.

**`exitTurn[from][to]`** — which turn a crossing counts as: `-1` left, `0` straight, `+1`
right, `NO_EXIT` where no turn connects the pair. **An exit is a property of the pair of
edges, not of what the boid was steering when it crossed.** A boid that turned a corner over
thirty ticks arrives at the boundary long since committed, and the last tick before crossing
asks for nothing in particular; reading the classification off instantaneous steering makes
that look like a straight crossing. Straight is applied last so it wins where more than one
turn reaches the same edge.

**Steering cost is dynamic programming, not a search.** The minimum steered ticks to leave by
a given exit is a 0-1 BFS: states reaching the target in one straight tick have value 0, in
one turning tick have value 1, otherwise `min(1 + left, 1 + right, 0 + straight)` over
navigations that stay on the edge. Non-straight ticks **need not be consecutive**, and a
right-facing exit *can* navigate left. Getting this right took dabeone from 103–119 ticks to
**7–8**, and plait from 717 to **6**.

---

## 5. `(edge, tau)` — the macro space

The decomposition says which stretch a state is on but not where along it, so two states on
one edge are incomparable and states on different edges doubly so. The **clock** fixes that:
every state gets a **tau**, every edge a real-valued **length**, and distance becomes
subtraction.

**Lengths add.** Leaving an edge at tau `t` puts the boid at `t + 1 − L(e)` on the next. More
usefully at a vertex where several edges meet: subtract each arriving edge's own length and
every edge around the vertex is measured from the vertex itself.

**Distance** = `tau(end) − tau(start) + Σ lengths of every edge on the route except the last`,
enumerated by DFS over the edge arcs. `EdgeDistance.between` returns one value per route
rather than picking, because they are genuinely different journeys.

**The cap belongs on the estimate, not the route sum.** plait's edges are 753 long, and a cap
of `3 × ticks` pruned every route leaving the start edge, producing errors of 815 ticks. Use
`cap = ticks + Σ all edge lengths`.

**Keep lengths real.** Rounding to whole ticks injects up to half a tick of error at every
crossing — invisible inside an edge, and the same order as the effects being measured.

**Lengths are weighting-dependent.** Never compare across schemes. Under lifted memoryless,
dabeone's nine lengths are ≈ 158.14, 158.62, 100.59, 100.97, 93.73, 102.59, 71.84, 80.97,
72.76.

**Free correctness check:** inverse edge pairs should come out near-equal (157.37/157.77,
99.75/100.15, 752.89/753.39). Nothing in the solve knows about inverses, so agreement is
independent evidence.

---

## 6. Routes

**A route is a series of edges.** That is the whole representation. Positions along it come
from tau plus the accumulated lengths of everything crossed.

### Simple loops, and why they are the unit that wraps

**There is no single lap length on a dab-like map** — how long a lap takes depends which way the
boid went round. So there is nothing to take a phase difference modulo, and a phase relationship
measured against "a lap" appears once per possible lap rather than once.

A **simple loop** fixes that: a cycle from an edge back to itself with no edge repeated. It has
one unambiguous length, so a phase difference wraps cleanly against it. Simple meaning *no
repeats* is load-bearing — a route that revisited an edge would offer two offsets for the same
state, and the coordinate would stop being a function.

Dabeone has exactly three from edge 4, enumerated by `ThreeBoidPhase.loops`:

| loop | length |
| --- | --- |
| `[4, 0, 3, 5, 8]` | 528.19 |
| `[4, 2, 1, 5, 8]` | 528.30 |
| `[4, 2, 7]` | 275.29 |

The two exit loops agreeing to **0.11 ticks** is a free correctness check of the same kind as
inverse edge pairs: nothing in the clock's fit knows they are near-mirrors.

**A lap flown is 533 ticks against the clock's 528.30.** A psyboid alone on the map goes round
`[4, 2, 1, 5, 8]` in exactly 533 ticks and scores on exactly 54 of them, identically in all 40
seeds of `PLANS_40` — `SimTest.scoringFloor`, and `CORPUS.md`. The 0.9% disagreement with the
clock sits inside the clock's own 1.65% `sd/mean`, so this is **a third independent check on the
metric** rather than a defect in it: nothing in the clock's gradient fit knows how long a lap
takes in ticks.

**Edge 6 lies on no simple loop**, which is correct and useful — it is reachable only during
warmup, and anything keyed on loops drops it without needing to special-case it.

On dabeone the only unsteered cycle is `2 → 7 → 4 → 2`, with an exit branching off edges 4 and
2. Everything else on the map is reached by taking one of those exits.

**Exactly three steered edge transitions exist anywhere on dabeone** — `2→1`, `4→0`, `5→6` —
established exhaustively by `TwoBoid`. Everything else is straight-only. That is what makes a
finite cover of the explanations possible.

`5→6` closes a loop over `{3,5,6}` that a psyboid can maintain to keep a boid off the scoring
edge indefinitely. **It occurs only in the first 500 ticks and then never again** across 1.28M
boid-ticks, so it is a cold-start phenomenon; warm up before concluding anything about it.

---

## 7. The critical envelope, and windows

The umbrella name for this analysis is **critical-envelope analysis**. It has no single owning
class; the stages are:

| stage | code | what it produces |
| --- | --- | --- |
| 1. critical states + envelope | `EdgeInfluence.envelope` | `Envelope(envelope, terminal, source, critical)` |
| 2. leader positions | `EdgeInfluence.lead` | `Lead` |
| 3. bands at one tau | `EdgeSlice.at` | `Slice(tau, followers, bands)` |
| 4. driver + storage | `SimTest.windows` → `SolverFacts.Window` | `<ingest>/windows/window_<f>_<t>.tsv` |

> ⚠ **The envelope is being redefined and the stages below will change.** The replacement is
> the set of states that reverse-navigate from the exit edge — complete by construction, since
> every boid that exits was steered onto it — and attribution moves to the tick the boid
> *enters* the envelope rather than the tick it crosses. Full specification in `ROADMAP.md` §1.
> What follows describes what is built today.

**Critical state** — one from which a single unsteered tick commits the boid the wrong way.
The last moment anything can be done.

**Envelope** — the forward closure of the critical states *within the edge*, so a boid in it
stays in it until it leaves the edge altogether. Critical states alone lack that property, and
without it "kept in the envelope" is not a condition a leader could hold onto tick after tick.

The suspect side of this is acknowledged to be crude: unsteered travel from the start of the
edge, intersected with an expanded band of critical states. The leader side admits **any
navigable position**, and deliberately so — restricting it to `TwoBoid`-reachable arrangements
would over-constrain where a leader could have been coming into the turn.

**Window** — for one arc `from → keep`, where a leader must be, tau by tau, as a band per
leader edge.

**The band's width is the answer, not its position.** Position depends on the arbitrary choice
of tau; width is the tolerance in the leader's position and does not move when the envelope
does.

**Only the opening band is meaningful.** Widths saturate to whole edges within a few ticks,
because the search lets a leader weave to hold station. Tightest measured on dabeone: `2→1` at
leader edge 7, **0.2 ticks**; `4→0` at leader edge 2, **1.0**. `SolverFacts.vacuous` marks a
band too wide to say anything, at 0.9 of the leader edge.

**Backward closure for the envelope must use unsteered predecessors only**, and sources must be
folded in *before* the forward closure or forward-closedness breaks.

**Widening.** Halve `straightBias` rather than changing `wSep`: it scales how decisive an
influence must be without altering what any influence *is*, and the same knob is wanted on the
photo-solving side. For separation, additionally record a **double-strength separation** band;
it only bites within `rSep`, so applying it globally still changes only separation cases.
**Never do this by editing `Params`** — pass a `Flocking`.

**Cause** is `SEPARATION` or `ALIGNMENT_AND_COHESION`. Alignment and cohesion are a **joint
cause**: there is no clean distinction and they frequently work in tandem. *This is not yet a
field on any band* — see `ROADMAP.md`.

### Do not define a window by sweeping override onsets

Sweeping onsets at a fixed override duration measures the override vocabulary as much as the
map: the earliest onset that still exits is the true commit point shifted back by the
duration, so halving the duration moves every window. On dabeone a 32-tick right turn gave
"exit 1 at onsets 268–277 and 0–12" — the upper bound is real, the lower bound is an artifact.

The intrinsic question is about states, not ticks: how far can a boid go before it **must**
already be turning to exit, and where is it **guaranteed** to exit however it turns. Both lie
entirely inside one edge.

Relatedly, **every onset within a window commits at the identical state and tick.** The
corridor funnels them: near an exit the veto leaves only one legal turn, so the branch is a
specific gate state rather than a diffuse boundary.

---


### A band's width is not its usefulness, and `VACUOUS` is too permissive

Measured 2026-09-06 by `Herding.trial`, the first test of a window in the direction a psyboid
uses one. Two findings, both of which change how a band should be read.

**Conversion is dominated by the leader *edge*, not by the band's width.** Standing inside a band
converts an exit 11–42% of the time against 0–2% outside it, but that aggregate hides an order of
magnitude: dabeone's edge 0 carries a 16.1-tick band on `2->1` that converts at **1.0%** — below
its own 2.4% control — and a 33.3-tick band on `4->0` that converts at **86.9%**. A tight band is
not automatically a good lever, and a loose one is not automatically a bad one.

**`SolverFacts.VACUOUS = 0.9` lets through bands that predict nothing.** The test is whether a
band spans 90% of its edge, which is a sound idea and the wrong scale on a long edge. Plait's
edges 0 and 1 run ~756 ticks, so a 439-tick band is 58% of one and passes — and bands wider than
300 ticks convert at **1.7%** against an overall control of 2.1%. **Half of a long edge is not a
constraint.** Any count of "usable bands" on plait is therefore inflated; on dabeone, whose edges
are 70–160 ticks, the threshold bites much earlier and the counts are closer to honest.

Not fixed. Tightening the constant would change every window on both maps and the right
replacement is a measurement — conversion against control — rather than another fraction.
## 8. Things that look like findings and are not

1. **Phase combs.** A ~4 px step means anything that comes out as an evenly-spread speckle
   rather than a region is phase, not structure. A source set appearing to reach "only 5 of 36
   terminals" was this; after making the sources phase-complete, all were reachable.
   *The one place phase is modelled rather than distrusted is the **partial tick** in the
   settled-state construction — boids clump in tau modulo the step length, so states genuinely
   on the path of unsteered travel get crossed between ticks. See `ROADMAP.md` §1.*
2. **`straightTo` as a universal.** Follow-through states violate it — §4.
3. **Wide bands near an exit.** Windows saturate by construction; only the opening is real.
4. **Cold-start transients.** `5→6` exists only in the first 500 ticks. Measure warmed.
5. **A stale cache.** Identical numbers after a real fix means the key is wrong. Bump `FORMAT`.
6. **Extremes at edge crossings.** Usually the rounding of an edge length.
7. **`d` and `d+32` being "close".** They are opposite directions through one pixel and belong
   to unrelated trajectories. The one deliberate exception is `mergeAlongside`, which compares
   headings mod 32.

---

## 9. dabeone reference data

Ingest `609cffdb84be218c`, 379×407, turning radius 40, physics 2.

| | |
| --- | --- |
| live states | 136,276 |
| edges | 9 |
| gate | `x=202, y=[174,191]`, decreasing |
| stable edges | `{2, 4, 7}` |
| unsteered cycle | `2 → 7 → 4 → 2` |
| steered arcs | `2→1`, `4→0`, `5→6` (exhaustive) |
| follow-through states | 20 on edge 2, 16 on edge 4, 18 on edge 5 |
| route A lap | 278 ticks |
| exit routes | 533–536 ticks, score 54 |
| flown scoring lap | **exactly 533 ticks, exactly 54 points**, physics 3 |
| warm edge occupancy | `2:0.3556 4:0.3285 7:0.3156`, everything else at or below `0.0002`, physics 3 |
| reachable pairs | 213,423,450 (1.15%), bit-identical from 5 seeds |
| psyboid reaches | 100% of live states; the boid reaches 79.86% |

---

## 10. Retired

Recorded so they are not rediscovered and mistaken for current method.

**Named route edges.** `EDGES.md` formerly defined ten edges by which of five circuits
traversed them — `ABCDE`, `ADE`, `A`, `BC`, `DE`, `BCDE`, `BD`, `CE`, `ABD`, and `X` (which
carried no route and existed because the corridors permitted it). Built by hand on dabnt.
Retired: programmatic decomposition supersedes it, and the naming does not generalise.

**Hand-drawn region annotation.** A painted overlay marking corridor regions, each half of
which was one edge. **Purely a bootstrapping tool** for calculating edges before the axiom was
implemented. No longer needed and not to be regenerated.

**The five circuits A–E and their traces.** Routes are now a series of edges. The traces in
The traces were stale and `routes/` has been deleted; `ingests/48b46d3d06e54c75/routes/` remains
on disk unversioned. They were generated from dabnt start state `(270, 156, 32)`, which is
**dead under physics 2**. `constrainTurn` returns the
proposed turn unchanged from a dead state, so tracing still produced paths and the edge map
built on them validated against itself. The check was circular, not sound.

**`avoidScoring` / the A-anchor test.** A viability kernel over a map where the scoring region
counts as wall, used to derive "has this boid exited" from a single anchor state. It works and
needs no annotation, but it measures "will score before returning to A", which equals "has
exited" only for edges lying wholly before the scoring event. Superseded by stability.

---

## 11. Open

- **The critical envelope may not cover the earliest possible influence.** Whether the
  envelope is large enough is the next thing to check, and it is priority one — see
  `ROADMAP.md`. A window is only a cover if it opens at or before the first tick a leader
  could act.
- **Cause is not recorded on bands.** `SEPARATION` vs `ALIGNMENT_AND_COHESION` currently
  exists only as hand-written labels in `UnstableEdgeClue`.
- **Modified-physics separation windows are not stored separately.** `SolverFacts` holds one
  `Window[]`, so the fallback set has nowhere to live.
- **Two-boid leader combinations** — flocking sums its neighbours before choosing, so two
  boids can produce a turn neither would produce alone. Not characterised.
- **Freely navigable edges** — an edge where a boid at any state can turn around and come back.
  Nothing models this, and most of the machinery assumes monotone progress along an edge, so
  tau would not be monotone in time. Dabeone and plait have none, which is why it has not bitten.
