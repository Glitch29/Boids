# Glossary

Every term in this project that carries a precise meaning, and the name it goes by in the
code. Where a word has been used two ways, the collision is called out and one reading is
declared canonical.

**Status:** 2026-09-05, physics 3, against dabeone ingest `609cffdb84be218c`. The table at the end lists
every named analysis and the class that owns it; check there before building anything.

---

## The three spaces

Most confusion in this project is a category error between these.

**`(x, y, d)` — micro navigation.** Position in pixels plus one of `Params.TURNS = 64`
headings. Every physical fact — what a boid can do, where it can be, what it perceives —
lives here. A state is indexed `(x + y * width) * TURNS + d`.

**`(edge, tau)` — macro navigation.** Which stretch of the map a boid is on, and how far
along it. Every route, distance, window and solver argument lives here. This is the space the
solver actually reasons in.

**`(x, y)` — visualisation only.** Position with the heading discarded. Renders and hand
inspection. **Nothing may be concluded from it**, because most live pixels carry two or more
edges travelling different directions through them, so a claim about `(x, y)` is a claim
about several unrelated trajectories at once.

---

## Simulation

**boid** — an agent with `(x, y, d)`. Moves one fixed-length step per tick and turns by at
most one of 64 headings per tick. Action space is `{-1, 0, +1}` for every control.

**psyboid** — a boid whose movement request is overwritten for a stretch. **Not a
decision-maker and not a special case in the engine**: an override is another
`MovementControl` in the same chain as the flocking rules and the collision veto, choosing
from the same three turns. The simulation never knows which boid is the psyboid. `Sim.PSYBOID
= 0` is only the default index an override is installed on.

**override** — one boid, one turn, held for a stretch, as `(onset, duration, turn, who)`.
`PsyboidOverride`. Runs after flocking and before the veto, so it is a *request*: the map can
still refuse it.

**tick** — **one simulation time step.** `Sim.State.tick`. Reserve the word for this. See
*tau* for position along an edge.

**veto / collision veto** — `NavMap.constrainTurn`. Converts a request that would leave the
play area into the nearest turn that does not. Always has something to offer from a live
state.

**sequencing / mid-tick** — boids advance in index order within a tick, each deciding against
the array as it stands, so boids `0..i-1` have moved and `i+1..n-1` have not. **A tick has an
interior.** Anything reconstructing a decision must reproduce that arrangement or it is
answering a different question. `Boids2DEngine.Trace` fires mid-tick for this reason.

**flocking constants** — `Params` is what the simulation does and is never edited. `Flocking`
is the same constants as an argument, so an analysis can ask its question at widened values
without moving anything the simulation reads.

**`sepFalloff`** — whether a *lone* close neighbour's separation term keeps its distance falloff.
False under physics 2, and by accident rather than decision: `MovementLogic` computes the falloff
and then normalises the separation sum, which erases the length of a single vector. So the `u`
coefficient **jumps from -90 to +30 as a neighbour crosses `rSep`**, a step of 120 at a radius
nothing else marks. On `Flocking`, set from `Aggregation.separationFalloffAtOne()` and never by
hand, since `EdgeInfluence.steer` *is* the aggregation at one neighbour and the two must not drift.


**aggregation** — how several neighbours are condensed into one desired direction, as opposed to
what each rule wants from one neighbour. `Aggregation`, with `SIMULATION` naming the one the
flock flies (physics 3: `RULE_SUM_CLAMP`), `RULE_NORMALISE` the physics-2 record, and five more
surveyed beside them. **The rules are not where implementations differ; the aggregation is.**

**cancellation ratio** — for one rule, `sum of the contributions' lengths / length of their sum`.
One is unanimity; ten means the neighbours nearly cancelled and only a short residue survived.
**A property of the arrangement, not of the aggregation**, which is what makes it usable as
evidence about which exits the normalisation is inventing. `AggregationSurvey.cancellation`.

**amplification** — `|signal(A,B)| / max(|signal(A)|, |signal(B)|)`, where *signal* is the
across-heading component of the desired direction. Above one means the pair asked for something
more decisive than either neighbour asked for alone, which no bounded aggregation can produce.

**pseudo-triangle rule** — `min(signal(A), signal(B)) <= k * signal(A and B) <= max(...)` for a
fixed `k`. The property a well-behaved aggregation has and the current one does not: it holds in
64.5% of sampled two-neighbour arrangements today, and exactly always for a mean of per-neighbour
votes, because a mean is a convex combination and the across-component is linear.

**invention factor** — the length of the desired direction under the current aggregation over its
length under a mean of per-neighbour votes. How much longer the signal is than anything the
neighbours actually asked for. Median **1.88** at unaccounted exits against **1.21** at accounted
ones.

**jolt** — how far the signal moves when one neighbour moves one pixel, measured in straight
biases, since that is the only scale on which a signal change can change a turn.

**straight bias** — hysteresis on holding the current heading, `(wSep + wCoh + wAli) / TURNS`.
The knob of choice for widening a window: it scales how decisive an influence must be without
altering what any influence is.

**control** — score a run accumulates with no psyboid. A case whose control is non-zero has
scoring that the psyboid does not explain.

**phase** — how far round its loop a boid is, in ticks. **Conserved:** a boid advances exactly one
step per tick along a route of fixed length, so its phase at tick `t` is its spawn phase plus `t`,
and the flock's distribution over phase is carried rather than mixed. Consequences everywhere —
the mean edge occupancy at a given tick is *periodic* rather than convergent (autocorrelation 0.99
at two lap lengths, after 73 laps), so **a warm-up, being a time shift, cannot flatten it.** What
warm-up removes is the non-phase part of a spawn: boids sitting on edges a warm flock never
occupies.

**edge occupancy** — the fraction of boid-ticks spent on each edge. `EdgeOccupancy`. On dabeone a
warm flock is `2:0.3556 4:0.3285 7:0.3156` with everything else at or below `0.0002` — 99.9% on
the three stable edges, and **not** proportional to edge length, which would be
`0.365 / 0.341 / 0.294`.

**spawn rule** — where a flock is placed at tick 0. `EdgeOccupancy.Spawn`: `UNIFORM` is the
simulation's own (uniform over live states), `STABLE_PLUS` uniform over stable+, `TAU_UNIFORM`
uniform by tau along the stable edges and then matched into stable+. **A better spawn beats any
warm-up**, and one that looks better can be worse — see `CORPUS.md`.

---

## Maps and ingests

**play area / map** — a PNG. `#000000` is out of bounds, everything else is playable,
scoring regions are a distinguished colour (`FF7F27`). Authored in `areas/<name>/<name>.png`.

**ingest** — a frozen, content-addressed copy of a map at `ingests/<hash>/`, named by the
SHA-256 of its pixels plus radius, navigability, trap-trimming and physics version.
`MapStore`. **The editable PNG is a design document; the ingest is what the simulation
reads.** Everything derived from a map is written inside its own ingest, so a result can
never be read against a map that has since been edited.


**structure tier** — everything derived from a map's geometry and nothing a boid decides: the
navmap, the edge decomposition and the clock. Addressed by the map hash, the step geometry, the
gate and the weighting scheme. `Derived.Structure`, at
`ingests/<map>/structure/<hash>/`. **Nothing under it reads a flocking constant**, which is why a
physics change leaves it alone.

**behaviour tier** — everything that also depends on what a boid decides: critical-envelope tables
and their windows, two-boid reachability, the psyboid corpus, solver facts, audits, influence
renders and flown corpora. Addressed by the structure hash plus the physics version, the flocking
constants and the aggregation. `Derived.Behaviour`, nested **inside** its structure because it
depends on it, so deleting a decomposition takes its tables with it.

> **The rule both tiers exist for:** an artifact's address is a hash of its whole input closure,
> including the addresses of its inputs. Recording the discriminating input in an artifact's
> *content* — the physics version in `meta.txt`, the gate in the edge graph's title, the recipe in
> a corpus header — cannot stop a reader picking up the wrong file, and until 2026-09-04
> `solver/facts.bin` and `psyboid/plans.tsv` were being silently overwritten and silently reread.
> **Over-keying costs a rebuild; under-keying returns the wrong answer without saying so.**

**corpus tier** — one psyboid corpus: a named recipe flown under one set of decision rules on one
decomposition of one map. `Derived.Corpus`, at
`<behaviour>/psyboid/<preset>-<hash>/`. The third addressing level, added because a corpus is a
function of more than the physics — seeds, warm-up, run length, search spread — and those used to
live in a header comment where nothing could act on them. Full treatment in `CORPUS.md`.

**corpus preset** — a named recipe for generating a corpus, in the same spirit as
`PresetScenarioParameter`. `CorpusPreset`, currently `SMOKE` (3 seeds, not a sample) and
`PLANS_40` (the standard corpus). Seeds are always `0 .. N-1`, so a preset is reproducible from
its name and a larger one is a superset of a smaller one.

**occupancy rate** — the mean fraction of the flock in a scoring zone at any moment: score divided
by flock size, since one score point is one boid in a zone for one tick. The unit corpus figures
are read in, because it means the same thing across flock sizes and run lengths where raw score
does not. Recorded four ways — flock, psyboid, others, control — because **a psyboid that herds
the flock into a zone and one that flies into a zone itself are indistinguishable by total
score**, and only the first is what this project is about.

**impactful tick** — a tick on which the psyboid's turn *after the collision veto* differed from
what the flocking rules alone would have produced. **What a psyboid spends**, as against the
override count: an override the map refuses, or one the flock would have obeyed anyway, costs
nothing and changes nothing. The second axis of the Pareto frontier a psyboid algorithm is judged
on, the first being flock occupancy.

**scoring pass** — one maximal run of consecutive ticks a boid spends in a scoring region. A
rate factors into how often a boid comes round and how long it stays, and those move
independently, so a pass is the unit to measure rather than the rate. **On dabeone a solo
psyboid's pass is 54 ticks and its period is 533, in every seed and to the tick.**

**scoring floor** — the score per tick of **one psyboid alone on the map**, and the lower bound a
corpus's scoring rate should sit on: a psyboid in a flock can always fall back on flying the
scoring loop itself, so a plan may be worse at herding but not worse than a boid with nothing to
herd. `SimTest.scoringFloor`, per-seed detail in `SimTest.scoringLaps`. On dabeone it is
`54 / 533 = 0.101313` per tick. See `CORPUS.md`.

**settled rate** — a rate measured over **whole laps only**, first pass start to last, as against
one measured over a window. **The two differ by more than they look.** A 2,739-tick window over a
533-tick lap holds 5.14 laps and so catches either five passes or six; the windowed rate reads
12% high and varies 4.6% across seeds where the settled rate varies not at all. Every occupancy
figure in `CORPUS.md` is windowed, which is correct for what a case is drawn from and wrong to
read as an asymptotic rate.

**`map.png`** — the frozen map. **The only file physics may be computed from.**

**`display.png`** — the same map with dead pixels painted as wall. **Rendering only.**

**dead pixel** — in play, but no heading survives from it. Forms a band hugging every wall.
Load-bearing: a boid flying along a wall sweeps its step through that band, so rebuilding a
navmap from `display.png` loses states (19,422 on dab, a tenth of the kernel).

**trap** — a pixel with no survivable heading at all. Drawn red by `NavMapRender`. Should not
exist in a sane map.

**navmap** — the viability kernel over `(x, y, d)`: every state with both an infinite future
and an infinite past. `NavMap`, built by `NavMapBuilder`. A run that starts live stays in
play forever.

**live / alive** — in the viability kernel. `Navigability.BIDIRECTIONAL` is the default and
means both directions; `FORWARD` exists only to widen spawning.

**physics version** — `Params.PHYSICS`, **currently 3**. Names the decision rules, so it changes
when {@code Aggregation.SIMULATION} does. Recorded in `meta.txt` and in a map history line, and —
since 2026-09-04 — **in the behaviour tier's hash**, which is what actually separates one
physics's artifacts from another's.

> It is deliberately **not** in the ingest hash. The map's pixels do not depend on the decision
> rules, and on the physics 2 to 3 bump `map.png` and `display.png` came back byte-identical. An
> earlier version of this entry claimed the ingest hash carried it; that was false, and the gap
> is what `Derived` closes. See `ROADMAP.md` §0c.

---

## Edges

Full treatment in `EDGES.md`.

**edge** — a set of live `(x, y, d)` states, defined *relative to the other edges*: all
points of an edge share the same set of successor edges and the same set of predecessor
edges. Edges are numbered `0..n-1` per map.

> **Collision, resolved.** `EDGES.md` formerly used *edge* for the ten hand-annotated named
> route segments on dabnt (`ABCDE`, `ADE`, `A`, `BC`, `DE`, `BCDE`, `BD`, `CE`, `ABD`, `X`).
> Those were a bootstrapping device and are **retired**. *Edge* now means only the numbered
> `(x, y, d)` sets above.

**the axiom** — the validity test for a decomposition. Every live point on exactly one edge;
all points of an edge agree on their successor-edge set *and* their predecessor-edge set. **A
test, not a constructor** — many decompositions satisfy it.

**refinement** — the constructive consequence of the axiom: split any edge whose points
disagree about their next-or-previous edge sets. Terminates.

**orbit** — an edge some point of which can forward-navigate back to itself. Strongly
connected, so the axiom cannot split it; it has to be cut.

**gate** — a line segment in `(x, y)` used to cut cycles so a decomposition can be
bootstrapped. **Not an analysis tool.** That it lives in `(x, y)` at all is the tell. See
`EDGES.md` §2.

**stable edge** — unsteered travel returns to it without scoring. A boid on a stable edge
could have been there forever and owes no explanation. On dabeone: `{2, 4, 7}`.

**unstable edge** — anything else. **A boid on an unstable edge is the snapshot-only test for
"requires explanation".**

**scoring edge** — least fixed point of: holds scoring states, leads somewhere scoring, every
way in comes from somewhere scoring.

**`straightTo[e]`** — where unsteered travel from edge `e` leads. **A dominant destination,
not a universal one.** Follow-through states violate it.

**follow-through state** — a state still labelled on the edge a boid is leaving, a few ticks
after the turn that committed it. 20 on dabeone edge 2, 16 on edge 4, 18 on edge 5. Treating
`straightTo` as universal because of these inflated a route-change count by 50%.

**exit** — a crossing from one edge to another that unsteered travel would not have made.
**A property of the pair of edges, not of what the boid was steering when it crossed.**
`EdgeNavigation.exitTurns` gives `-1 / 0 / +1` per ordered pair, `NO_EXIT` where no turn
connects them.

**route** — a path through the edge graph, written as a series of edges. On dabeone the only
unsteered cycle is `2 → 7 → 4 → 2`.

---

## The clock

**tau (τ)** — **how far along its own edge a state is, in ticks.** Real-valued. This is the
macro-space position coordinate.

> **Collision, resolved.** The code currently calls this `tick` in `EdgeMetric.Metric.tick()`,
> `SolverFacts.tickAt/tickOf/tickLo/tickHi`, and `ExitAudit.Exit.suspectTick` — while
> `Sim.State.tick` and `ExitAudit.Exit.tick` mean simulation time. `ExitAudit.Exit` carries
> both senses in one record. **Canonical: `tau` for position along an edge, `tick` for
> simulation time.** Docs use `tau` now; the code rename is pending (`ROADMAP.md`).

**edge length** — one real number per edge, shared by every way through it. **Lengths add**:
leaving an edge at tau `t` puts the boid at `t + 1 - L(e)` on the next. Keep them real —
rounding to whole ticks injects half a tick of error at every crossing.

**span** — end-to-end clock time across an edge. **Not the same as its length**, and reading
one back as the other diverges.

**the clock** — the assignment of a tau to every state and a length to every edge, as one
joint least-squares fit solved by conjugate gradient. `EdgeMetric`, cached by
`EdgeMetricStore`.

**distance** — `tau(end) - tau(start) + Σ lengths of every edge on the route except the last`.
`EdgeDistance.between`, which returns one value per route rather than picking.

**gauge** — the fit is underdetermined in a describable way; a spanning forest of the class
graph gives exactly the pinnable lengths. Fix it structurally, never with Tikhonov weights.

**weighting scheme** — how much each transition counts in the fit. `EdgeWeights.Scheme`:
`UNIFORM`, `CIRCULATION`, `MOMENTUM`. Best measured is the **lifted memoryless flow**
(`MOMENTUM` with an all-ones chain at γ=0). Lengths are weighting-dependent: **never compare
across schemes.**

**lifted flow** — traffic solved over `(state, last request)` nodes rather than states. Seeds
one unit per *request*, which is what the simulation does where the veto collapses two
requests onto one successor.

---

## Windows and the critical envelope

**critical state** — a state on an edge from which one unsteered tick commits the boid the
wrong way. The last moment anything can be done.

**envelope** — *being redefined; see `ROADMAP.md` §1.*
**Current:** the forward closure of the critical states within the edge, so a boid in it stays
in it until it leaves the edge altogether. With `terminal` (one steered tick from the exit) and
`source` (where a boid enters the envelope).
`EdgeInfluence.envelope → Envelope(envelope, terminal, source, critical)`.
**Replacement:** the **unsteered**-predecessor closure of the exit edge, plus the states on the
downstream edge that reverse-navigate to the current edge in one tick. It terminates because
unsteered travel from the start of an edge never leaves it by a non-straight transition, and it
is complete because every boid that exits was steered onto it.

**unsteered predecessor** — of a state `S`, any state from which **straight steering** would
bring the boid to `S`. Steering means the **intended direction, before the physics veto**, so a
state whose straight request the veto turns is still an unsteered predecessor of where it
actually lands.

**envelope entry** — the tick a boid moves onto the envelope from a state off it. **The moment
attribution is done at**, as distinct from the crossing tick, which is when the exit is
*reported*. Always a steered move, since unsteered travel into the envelope implies you were
already in it. Only the final entry is analysed.

**commit boundary** — where a boid's exit becomes unavoidable. **It is the edge boundary
itself**; the edges were designed to force that equivalence. *Not* the envelope boundary — a
boid that has entered the envelope can still be steered back out, which is hard on dabeone and
plait but always available to a psyboid.

**settled** — the predicate the backward pair search terminates on: a state where the exiting
boid is flying its ordinary unsteered course. Constructed as **`{closure, partial tick,
closure}`** over the unsteered paths through the edge. **Every state by which a boid enters an
edge is settled**, which is what bounds the backward search to within one edge. **Named to
avoid colliding with `stable`**, which is an edge property; *settled* is a property of a state.

**partial tick** — turn as normal, then advance only partially, landing on any intermediate
sample point of the navmap's out-of-bounds check. Its purpose is **phase alignment**: boids
clump in tau modulo the step length, so states lying on the general path of unsteered travel
get missed by being crossed between ticks. **Applied exactly once** — chaining partial ticks
would let a boid strafe up to 45° off its heading.

**lead** — where a second boid can be to hold the first inside the envelope, computed from
the single-neighbour closed form. `EdgeInfluence.lead → Lead`.

**band** — a stretch of one leader edge, in tau. `EdgeSlice.Band(edge, lo, hi, count)` when
computed; `SolverFacts.Band(tau, leaderEdge, lo, hi)` when stored. **The band's width is the
answer, not its position** — position depends on the arbitrary choice of tau.

**window** — for one arc `from → keep`, the bands per leader edge, tau by tau.
`SolverFacts.Window(from, keep, opens, bands)`. **Only the opening band is meaningful**;
widths saturate to whole edges within a few ticks.

**vacuous** — a band so wide it says nothing. `SolverFacts.vacuous`, threshold `VACUOUS = 0.9`
of the leader edge.

**critical-envelope analysis** — **the umbrella name for the whole pipeline above.** No
single class owns it; see the named-analyses table below.

**cause** — why a leader induced the turn: `SEPARATION` or `ALIGNMENT_AND_COHESION`.
Alignment and cohesion are a **joint cause** — there is no clean split and they work in
tandem. Expected to be sharply bimodal under almost any measure; the most robust is which
influence set, subject to its range limits, has the **larger component orthogonal to the
direction of travel with the sign matching the turn being executed**. Recorded at envelope
entry. Recorded on `CriticalEnvelope.Entry`.

**diluted model** — the second physics an exiting boid may choose on any tick during
critical-envelope analysis: `wAli = 0`, `wCoh = 0`, `wSep` doubled. `Flocking.diluted()`.
Models what a crowd does — alignment and cohesion are summed over neighbours then normalised, so
a spread of them partially cancels, while a neighbour inside `rSep` keeps pushing at full
strength. **Not the same as halving the straight bias**, which amplifies alignment and cohesion
exactly where a crowd suppresses them.

**three-boid phase map** — a sampled map of what three boids do to each other, with both axes
phase differences in ticks: the psyboid's tau minus the suspect's on one, the third boid's on
the other, **rebased along a route rather than by shortest path** — a boid's position is its tau
plus the lengths of the route's edges before its own, so a sample lives in
`(x, y, route, route)` and there is one panel per pair of simple loops. `ThreeBoidPhase`.
One sample per cell, so a colour is one draw rather than a majority. Sampling rather than
enumeration because three boids will not fit in `(x, y, d)` the way `TwoBoid` does.

**region overlay** — the three-boid phase map with regions of interest painted over it by hand,
one colour per kind of interest. `analysis/3BoidAreasOfInterest.png` is the dabeone `4->0` one:
**rose `#FFAEC9`** for one distinct feature per region, **green `#22B14C`** for a region believed
to hold more than one. It is an *input*, versioned for that reason, and it is bound to the exact
pixels of the phase map it was painted on. Hand-marking is scaffolding: identifying these regions
programmatically is the goal, and the overlay is what such a detector gets scored against.

**region sample sheet** — one replayed arrangement per painted region of the overlay, drawn at
critical-envelope entry, with a crop of the marked phase map inset so a tile can be matched to
its region by eye. `ThreeBoidSamples`, written to `render/phase<f>_<t>-samples.png`. The
representative is the cell nearest the region's centroid whose recorded account matches the class
the region was painted over — nearest-the-middle because a region shades into its neighbours at
the edge, and a sample taken there would be a picture of the boundary.


**map-wide stable** — the states a boid **alone** can end up in: the states straight travel
returns to itself, plus all their partial-tick straight successors, closed under straight travel.
`MapStates.pureStable(1).partialTick(STRAIGHT).closed(STRAIGHT)`. The map-wide counterpart of the
per-edge *stable edge*, which took the closure from every way into the edge instead. On dabeone:
**1,610 states, on edges 2, 4 and 7** — exactly the unsteered cycle.

**pureStable(n)** — the states straight travel returns to themselves. Straight travel is a
function, so its graph is rho-shaped and this is the union of its cycles. `n` is how many boids
are in play and **only `pureStable(1)` is defined**. On dabeone it is **one cycle of 278 states**,
96 on edge 2, 93 on edge 4, 89 on edge 7 — one lap of `2 → 7 → 4 → 2`, and 278 ticks against the
clock's 275.29 for the same loop, which nothing in either knows about the other.

**stable+** — the states a boid reaches in **ordinary multi-boid
traffic**, visited without psyboid activity or abnormal circumstances. Wanted because *stable* is
what a lone boid holds and no boid in a scene is alone — the flock knocks everyone slightly off it
constantly — so a history that merely starts a little off stable should not thereby be
unexplained. Built by `StateSet.expandByAgreement`, and **closed under straight travel at every
point**, which is what makes it predictable: an exit can only enter it if an exit window sits on
the stable loop with a quorum of influencers on it. Settled 2026-08-30 as
`pureStable(1).partialTick.closed.expandByQuorum(pureStable(1), 5).partialTick.closed`,
`MapStates.stablePlus`. **It does not contain the per-edge *settled* set** — settled is seeded from
the edge's entrances, which the straight-travel loop never touches, and on dabeone edge 4 only
1,065 of 2,371 settled states are in it. Anything using stable+ as ground must **union** with
settled, never replace it.

**quorum** — the one free parameter in stable+, and **one** number: that many influencer
placements must ask for a turn before it may start, the same count must keep asking for it to
continue, and nothing at all is needed to end it, so a turn may stop at any tick. **It is a count
of ticks of influencer positions** — four ticks with both ends included, hence **5** on dabeone,
where the pure loop carries one state per tick. Superseded `agreementRatio`, which made the same
requirement mean different things depending on how densely the influencer set sampled its loop.
On dabeone `4->0` the expansion reaches no edge beyond the stable ones at any quorum of 2 or more,
and spills onto five at quorum 1.



**feature** — a clump of cells of one account in one panel of the phase map, found in the picture
rather than painted on it. `ThreeBoidSamples.Feature`, with `features` to find them, `bands` to
group ones sharing a range on one axis, `windowFit` to test a claimed size against the densest
window of it, and `atlas` to crop each in place. The successor to the hand-drawn *region overlay*,
and the thing a detector will eventually be scored on.

**slab / column / cloud** — the three shapes the unexplained clumps come in on dabeone's centre
panel. A **slab** is horizontal and dense, lying on the third-boid-led band; a **column** is the
same transposed onto the psyboid-led band; a **cloud** has no dense core in either orientation.
Not cosmetic: all six clouds need genuine superposition, while the slabs and columns mostly need
only a leader handover.

**demanding tick** — a tick of a history on which coasting would not have produced the move, so a
leader is genuinely required. The rest are **free**: nothing was steering, or the veto overrode
the request and every turn collapsed to the same successor, and then *every* neighbour on the map
accounts for the move. **Coverage counted over all ticks flatters every candidate equally**; count
demanding ticks only. `ThreeBoidSamples.Approach`.

**single / split / uncovered** — what an unexplained exit turns out to need, from
`ThreeBoidSamples.classify`. **single**: one boid accounts for every demanding tick, so a pairwise
table could hold the history and only the constants or the ground are too tight. **split**: two
boids between them account for all of them and neither alone does — pairwise per tick, not per
history, so it needs a leader handover. **uncovered**: a demanding tick neither neighbour alone
reproduces, which is superposition and out of reach of any two-boid constants.

**cost to leave** — the fewest ticks of non-straight steering needed to get from an edge to a
named other edge; the ticks need not be consecutive or agree in direction. `EdgeNavigation.Exit`,
surfaced in the edge graph. **It is a reduction over a source set, and which set is the whole
question**: the published figure reduces over the edge's *inbound* points, which is right for a
boid arriving cleanly and wrong for one already on the edge because traffic left it there.
`EdgeNavigation.steerCostTo` hands the per-state array back so any source set can be substituted.
On dabeone, over stable+ instead: `4->0` falls from **8 to 5**, `2->1` from **7 to 6**.

**influencers** — the set of places the other boid may be, when expanding by agreement. Must be
closed under straight travel, because the coalition is carried forward by coasting.
`pureStable(1)` is the first choice.

**two-model boid** — the exiting boid in critical-envelope analysis picks the true constants or
the diluted model **independently on every tick**, at the entry and throughout its history. A
history in which the crowd tipped one decision and not the next is then expressible, where a
table built wholly under other constants would turn every marginal straight into a turn along
the whole path — the wrong shape, not merely too many. `CriticalEnvelope.Entry.diluted()` says
which model carried the entry, which is what ranks `ENVELOPE` against `ENVELOPE_WIDENED`.

**single-neighbour closed form** — with exactly one neighbour the whole influence collapses
to `30u + 70a` in the flocking annulus and `-90u + 70a` inside separation, where `u` points
at the neighbour and `a` is its heading. `EdgeInfluence.steer`. **Returns `0` for anything
out of range or behind the FOV** — the property that made the old sufficiency test wrong.

---

## Solving

**scene / arrangement** — one `Sim.State`: positions and headings, no tick, no history.
What a solver is given.

**clue** — one kind of evidence read off a scene, returning a per-boid weight: `1` for "says
nothing", `0` for "rules out", larger for a lean. `Clue`. The solver multiplies them.

**facts** — everything a solver may know about a map before it sees a scene: the
decomposition, stability, the clock, the windows. `SolverFacts`, built once per map version by
`SolverStore.build`, stored at `ingests/<hash>/solver/facts.bin`. **A solver never builds
these.**

**ExitAudit** — the exit classifier. Watches a run with full information and accounts for
every exit. This is the ground truth the solver is measured against; see `ROADMAP.md` for its
specification.

**owing / requires explanation** — a boid on an unstable edge. `SimTest.owing` counts them.

**the grade** — `SolverScore`, a **penalty** over the four outcome counts, to be minimised. Each
answered class is given the K-weighted psyboid rate found inside it, and every boid is scored on
squared error against that, psyboids counting `K` times. Closed form where the caps do not bind:
`g(K·FN, TN) + g(K·TP, FP)` with `g(a,b) = ab/(a+b)`. **Abstaining is the worst attainable score
and every uninformative assignment ties with it**, so the number moves only on discrimination.
`SolverScore.normalised` reads 1.000 for a perfect answer and 0.000 for knowing nothing. Not to
be confused with a run's *score*, which is what the psyboid maximises.

**hedge cap** — the clamp that makes the grade monotone. A class answered BOID may not be given
a higher psyboid rate than the population has, nor a class answered PSYBOID a higher boid rate;
`SolverScore.capNegative` / `capPositive`, which sum to 1. Without it the score is symmetric
under swapping every answer, so a perfectly inverted solver also scores zero and correcting a
mistake can *raise* the penalty. **The caps must be the population rates**: a constant cap is
flat at only one class balance and otherwise lets an uninformative solver gain by answering
PSYBOID less often.

**plan** — a searched psyboid timeline, as a seed plus one override per decision.
`PsyboidBits.Replay`.

**label** — the text form of a plan, e.g. `seed200|p1Rd24t10028|…`. **The label is the
artifact**: everything else can be recomputed from it.

**corpus** — a body of runs to measure against. `ingests/<hash>/psyboid/plans.tsv` is the
dab-like one, written by `PsyboidCorpus`, every row verified by replay before it is written.

---

## Named analyses

Canonical name → where it lives. Use these names; confirm the code name before working on
anything not listed.

| name | code | output |
| --- | --- | --- |
| ingest | `MapStore` | `ingests/<hash>/` |
| viability kernel / navmap | `NavMapBuilder` → `NavMap` | in memory |
| edge decomposition | `SimTest.labelFor` / `decompose` | `<ingest>/edges/` |
| per-edge navigation | `EdgeNavigation` | in `SolverFacts` |
| the clock | `EdgeMetric` / `EdgeMetricStore` | `<ingest>/metric/` |
| transition weights | `EdgeWeights` | in the clock |
| **critical-envelope analysis** | `EdgeInfluence` + `EdgeSlice`, driven by `SimTest.windows` | `<ingest>/windows/window_<from>_<to>.tsv` |
| two-boid reachability | `TwoBoid` | `<ingest>/twoboid/` |
| critical-envelope tables | `CriticalEnvelope`, stored by `CriticalEnvelopeStore` | `<ingest>/envelope/` |
| three-boid phase map | `ThreeBoidPhase` | `render/phase<f>_<t>.png` |
| region overlay | hand | `analysis/3BoidAreasOfInterest.png` |
| region sample sheet | `ThreeBoidSamples` | `render/phase<f>_<t>-samples.png` |
| artifact addressing | `Derived` (structure and behaviour tiers) | `ingests/<map>/structure/<h>/behaviour/<h>/` |
| map-wide stable, stable+ | `StateSet` + `MapStates.stablePlus`, scanned by `SimTest.stablePlusScan` | `render/stable-plus-by-ratio.png` |
| phase map on stable+ | `SimTest.phaseMapOnStablePlus` | `render/phase40-stableplus.png` |
| white feature census | `ThreeBoidSamples.features` / `bands` / `classify` | `render/phase40-stableplus-white-atlas.png` |
| aggregation survey | `Aggregation` + `AggregationSurvey`, flown by `SimTest.aggregationPhaseMaps` | `render/agg-*.png` |
| psyboid corpus | `PsyboidCorpus` + `CorpusPreset` | `<behaviour>/psyboid/<preset>-<hash>/` |
| edge-occupancy decay | `EdgeOccupancy`, with its `Spawn` rules | `<behaviour>/occupancy/decay-<rule>-<seeds>s<window>w.tsv` |
| scoring floor | `SimTest.scoringFloor`, per-seed detail in `SimTest.scoringLaps` | `<behaviour>/psyboid/<preset>-<hash>/floor.tsv` |
| proposed physics 3 | `Aggregation.RULE_SUM_CLAMP`, driven by `SimTest.proposedPhysics` | `render/prop-*.png` |
| cost to leave | `EdgeNavigation.analyse`, per state via `steerCostTo` | in the edge graph |
| exit classification | `ExitAudit` | `<ingest>/audit/` |
| solver facts | `SolverStore` → `SolverFacts` | `<ingest>/solver/facts.bin` |
| the solver | `Solver` + `UnstableEdgeClue` | — |
| psyboid search | `PsyboidBits` | — |

> **Two different two-boid analyses. Do not conflate them.**
> **`EdgeInfluence`** is the single-neighbour *closed form*, and it is what the critical
> envelope and every window are computed from.
> **`TwoBoid`** is the *exhaustive enumeration* of reachable pairs. It told us which arcs are
> steerable at all (`2→1`, `4→0`, `5→6` on dabeone); it does not produce windows.
