# Hints for the psyboid problem

Everything learned across seven sessions that made some part of this easier, plus the
reasoning about which intermediate problems turned out to matter and which did not.

Not instructions. Closer to: *X is a way to compute Y; Y is worth having because of Z; Z is
how you know you are winning.* Numbers are from `dabeone` and `plait` unless stated.

**Status:** 2026-08-30 (later). This file is also the *training-wheels* condition of the evaluation —
everything the expert can write down — so it is written to be read by someone who has not seen
the code. For terms, see `GLOSSARY.md`; for what currently exists, `README.md`.

**Terminology.** This file still writes *tick* where it means **tau**, a state's position
along its own edge. The two senses are now separated: **tau** for position, **tick** for
simulation time. Read §4 and §7 accordingly.

---

## 0. The shape of the problem

Boids fly under Reynolds' three rules. One of them — the **psyboid** — is additionally
steered by an override, and the task is to identify it from a small number of still frames.

The thing that makes it tractable: **a boid never chooses.** Left alone it holds its heading.
So *any* deviation from straight travel is evidence that something acted on it, and there are
only two candidates — it was overridden, or another boid was positioned so as to turn it.
Everything downstream is an elaboration of that one observation.

**What success looks like.** The eventual measure is whether a classifier can name the
psyboid from photographs. Long before that, the usable proxies are:

- **Can you account for every non-straight edge transition in a corpus?** An unexplained one
  means the model of what can steer a boid is incomplete.
- **Does a distance estimate between two states match the ticks actually flown?**
  Standard deviation as a fraction of the mean is the metric that matters; a clock that runs
  uniformly fast or slow still compares distances correctly.

---

## 0a. The inferential move that actually solves a case

This is the reasoning the whole apparatus exists to automate. It is worth reading before
anything else.

**Models reliably notice that boids on the outer loop tend to be psyboids. They reliably fail
to understand why**, and that failure is what the test is for.

Worked example — `dab_v2` case 08, which every model tested got wrong. They answered PURPLE
because PURPLE was travelling anticlockwise around the scoring loop and was therefore the
visual odd-one-out. **The answer is SALMON**, and the reasoning is:

1. Rewind roughly eight seconds from the photograph. All four boids are in order, on a
   scoring trajectory.
2. SALMON is the **leader** of that group.
3. A boid takes an exit either because it was overridden, or because another boid was in
   front of it inducing the turn. But a boid in front of SALMON would have reached the
   scoring loop *first* — so no such boid existed.
4. Therefore SALMON exited under an override. **The followers prove nothing**: they could
   simply have followed SALMON.

**Being the odd one out is not evidence. Being the boid that had nothing to follow is.**

Two structural facts make this computable rather than a matter of eyeballing:

- **There are very few moments where the psyboid has meaningful agency.** Almost all of the
  time a boid's future is fixed. That is what lets the map be treated as a directed graph
  rather than a two-dimensional space — and a graph supports **efficient rewind to possible
  prior states**, with the caveat that paths are narrow but not perfectly deterministic, so
  travel time along an edge carries a little variance. *Removing that variance is what the
  clock is for.*
- Normal boids go one way at a decision point **overwhelmingly** but not always. On dab the
  default is to stay on the non-scoring "main loop"; leaving it for the outer loop is the rare
  event worth explaining.

Other signals that were identified as real but never fully exploited:

- **A boid consistently sitting where it would be advantageous to steer from** is a psyboid
  indicator, independent of what it scores.
- **How many other boids can see a given boid** — the 240° FOV makes visibility asymmetric, so
  this is not the same as how many it can see.
- Nothing prevents a solver from **running its own simulations** to establish reference
  points. Treat that information as derivable even where deriving it is expensive.
- Activity level (how often the psyboid is being steered) is itself derivable from ~12 photos,
  and the three levels behave differently enough to be treated as separate problems.

**Ambiguity is a legitimate answer.** Some scenarios genuinely do not determine a psyboid, and
recognising that is a harder test than always naming the most likely candidate.

## 0b. Architecture decisions that paid off

Made in the first two sessions, before any of the analysis existed, and each one removed a
class of problem rather than solving one.

- **The psyboid is not a decision-maker.** It is an ordinary deterministic boid whose movement
  request is overwritten for a stretch. *The simulation never needs to know which boid is the
  psyboid, or how many there are.* An override is just another `MovementControl` in the same
  chain as the flocking rules and the collision veto, choosing from the same three turns. This
  is why a psyboid cannot be found by looking for special-case code paths — there aren't any.
- **State is one immutable object.** Required because futures branch off a shared state; a
  state that has been handed out is never written to, so the same state can be re-advanced any
  number of times. This is what makes exhaustive search and replay possible at all.
- **Contracts on the play area are deliberately *not* verified by the code.** Traversability,
  Complexity, and Safety at Tangents (see `CONTRACTS.md`) are guarantees the *designer* makes.
  Their purpose is to define the boundary of the spec: any corner case that can only arise
  when one is violated has *arbitrary* behaviour, and the code is not expected to detect or
  degrade gracefully. This is why the navmap can assume what it assumes.
- **Time symmetry is not a contract.** Reversibility is a natural property of plain boids, and
  an early spec leaned on working backwards from a state — but discrete intervals and
  uncertainty about the psyboid *both already break it*. Do not build a solver that depends on
  running the simulation backwards.
- **A boid that leaves the image throws.** No respawning, no toroidal wrap, no clamping. Boids
  are only ever placed at initialisation, and only in live states. Anything else is a bug that
  should be loud.
- **Flocking is extremely tolerant.** It is difficult to tune the parameters into
  non-flocking behaviour; the one easy way is a play area smaller than ~3× the turning
  diameter. Do not spend effort on metrics for how flock-like the flock is.

## 1. Physics — exact and load-bearing

These were verified rather than assumed, several of them against 300k random flocks.

- `TURNS = 64`. A boid turns **at most one step per tick**, ever. The entire action space is
  `{-1, 0, +1}` for every control — flocking, override, and collision veto alike.
- Speed is the chord of the 64-gon: `2 R sin(π/64)`. At `R = 40` that is **≈3.925 px/tick**.
- Steps are **integer vectors**, and antipodally exact: `step(d+32) == −step(d)`.
- **No 1/n normalisation.** Each rule sums its neighbours, normalises the sum to unit length,
  then multiplies by its weight. `W_SEP=120, W_COH=30, W_ALI=70`,
  `STRAIGHT_BIAS = (120+30+70)/64 = 3.4375`.
- **Consequence — influence is quantised, not graded.** One neighbour and twenty neighbours
  produce the *same magnitude* from a given rule. A second influencer is **not additive**: it
  changes the direction to the vector mean, not the strength.
- **Separation falloff cancels entirely for a single neighbour.** The `(rSep−d)/rSep` term
  scales the vector and its own magnitude equally, so normalisation removes it. Separation is
  therefore **all-or-nothing at `rSep`**, not a ramp.
- Cohesion and alignment share membership exactly (0 differences in 300k trials). Separation
  is a strict subset (`d < rSep`). The 240° FOV gate applies to **all three**, separation
  included.
- Radii scale with turning radius: `rSep = 1.25 R` (=50), `rFlock = 3.75 R` (=150).

### The two-boid closed form

With exactly one neighbour, the whole influence collapses to two regimes. Writing `u` for the
unit vector toward the neighbour and `a` for its heading:

| regime | desired direction |
|---|---|
| flocking annulus (`rSep ≤ d ≤ rFlock`) | `30·u + 70·a` |
| inside separation (`d < rSep`) | `−90·u + 70·a` |

**They point opposite ways along `u`.** The same neighbour position means opposite things
either side of `rSep`. This closed form is what makes exhaustive single-neighbour analysis
cheap enough to run hundreds of millions of times.

**Worked example — two parallel boids exactly abreast.** Desired direction is 23.20° toward
the partner in the annulus (score 72.61 vs 73.44 for straight) and 52.13° away inside
separation (78.49 vs 73.44). So: **away below 50px, straight from 50 to 150, and never
toward, at any distance.** Two abreast boids can sit at 51px indefinitely with no lateral
force at all. Note the annulus margin is only 0.83 — a `STRAIGHT_BIAS` below 2.61 would flip
it to *toward*.

### Sequencing

Boids advance **in index order within a tick**, each deciding against the array as it stands.
When boid `i` decides, boids `0..i-1` have already moved and `i+1..n-1` have not. This is
deliberate: a simultaneous update is symmetric, and under it two boids that ever coincide stay
identical forever.

**Anything reconstructing a decision must reproduce that mid-tick arrangement**, or it is
answering a different question. A tick has an interior.

---

## 2. Map representation

- Maps are **content-addressed**. The editable PNG is a design doc; the simulation reads a
  frozen ingest named by its hash (`MapStore`). Never compute physics from the display copy —
  dead pixels lie on the swept paths of live ones and rebuilding from it gives a smaller,
  wrong answer.
- The **navmap** is the set of `(x, y, heading)` states a boid can survive from — infinite
  past *and* infinite future. From a live state the collision layer can always find a live
  turn, so **a run that starts live stays in play forever**.
- Almost all work lives in `(x,y,d)` space. `(x,y)` alone is only for ingest and rendering.
- **`d` and `d+32` are never close.** They are opposite directions through the same pixel and
  belong to unrelated trajectories. The one deliberate exception is merging edges that run
  alongside each other, where headings are compared mod 32.

### The phase artifact — recurs constantly, costs hours

A ~4px step means **pixels four apart are the same trajectory sampled a tick apart, and the
three between belong to trajectories that never touch it.**

This produces things that look exactly like findings and are not:

- A source set that appears to reach "only 5 of 36 terminals" — an artifact; after making the
  sources phase-complete, all terminals were reachable.
- Speckled patterns spread evenly through a corridor rather than concentrated anywhere.

**If a result is a fine comb rather than a region, suspect phase before believing it.**

---

## 3. Edge decomposition

Cut the state graph into edges so that "where is this boid" has a coarse answer.

### The axiom — the most important definition in the project

> A set of edges is a valid decomposition of a bidirectionally infinitely navigable map if
> **every live point is on exactly one edge**, and **all points on the same edge have the same
> set of predecessor edges and the same set of successor edges.**

Two consequences worth having in mind:

- **It is a test, not a constructor.** Many decompositions satisfy it. Every point as its own
  edge is valid; every point as a single edge is valid. Something else has to choose which one
  you want.
- **It gives you the refinement algorithm for free.** Whenever points within an edge differ in
  their next-or-previous edge sets, split the edge into one piece per distinct set. This
  terminates. In practice, if it takes more than a couple of iterations something is wrong.

**Predecessors are not optional.** An early version used successors only, on the assumption
that bidirectionality falls out of forward directionality. **It does not.** Both sides must
match.

### Construction from a single gate

1. `O` = the set of points that can **reach themselves without crossing the gate**. (Equivalently: points that can either reach or be reached by any point of the live space without crossing it — two computations, same set.)
2. Split `O` by connectivity *within* `O`. Each connected subset is an **orbit**, and each orbit is one edge.
3. Split the complement of `O` by connectivity under **both forward and backward** navigation. These are candidate edges.
4. Apply the axiom to refine them.
5. Cut each orbit with its own gate so the orbits become more than one edge each.

An **orbit** for this purpose is any edge where a point can forward-navigate to itself.

### Predecessors

Boid movement is just a string of `(turn + move)`. The reverse step is therefore
**FLIP → MOVE → TURN → FLIP**: turn the boid by +32, move one step along its new direction,
let it turn, flip back. Verified against brute force over all 136,276 live states — 0
mismatches, and 0 of 319,646 live candidates failed forward confirmation.

Reachability — whether an `(x,y,d)` has infinite predecessors — is answerable from navmap
information already present, and is what "live" means.

**Construction from one gate:** orbits (SCCs of the gate-cut graph) → complement components →
refinement → `splitOrbits`. Gates are recorded in the graph output, e.g. dabeone
`x=202, y=[174,191]` decreasing → **9 edges**; plait `y=360, x=[335,350]` both ways → **6**.

Things that cost time:

- **The gate must be a real cut** — every cycle must cross it. A badly placed gate does not
  fail loudly; it corrupts the partition. **Arrival count is the health check**: far below a
  corridor's cross-section means the line is wrong (62 arrivals against a healthy 120–180
  signalled a bad gate that made refinement run away to 493 edges).
- Refinement silently gives up above **63 edges** (64-bit mask). A confusing exception blamed
  gate placement when this was the real cause.
- A pixel-thin feature split many ways is usually **phase**, fixed by merging edges within one
  tick's travel and ±1 heading mod 32 (`mergeAlongside`). This must **exclude orbits** — on
  plait 54.8% of one region lies alongside an orbit and merging it destroys the decomposition.
- Errant merges are mostly harmless; later refinement splits them again.

**Per-edge properties worth computing** (`EdgeNavigation`):

- `straightTo[e]` — where unsteered travel leads. **Caution: this is the dominant destination,
  not the universal one.** On dabeone 20 states of edge 2 go straight to edge 1, 16 of edge 4
  to edge 0, 18 of edge 5 to edge 6. Those are *follow-through* states: once a boid has been
  turned into a branch it stays labelled on the old edge for a few ticks. Treating
  `straightTo` as universal inflated a route-change count by 50%.
- `stable` — unsteered travel returns to the edge without scoring. On dabeone the only
  unsteered cycle is **2→7→4→2**.
- Steering cost to an exit is a **0-1 BFS**, not a count of consecutive override ticks.
  Non-straight ticks need not be consecutive, and a right-facing exit *can* navigate left.
  Getting this right took dabeone from 103–119 override ticks to **7–8**, and plait from 717
  to **6** — and revealed real structure (plait edge 0 has a decision that single-direction
  holds could never see).

---

## 4. The clock — tick values and edge lengths

**The problem it solves.** The decomposition says which stretch a state is on but not where
along it. Two states on the same edge are incomparable and on different edges doubly so, so
any measurement between them depends on where the edges were cut. Give every state a **tick**
and distances become subtractions.

**Lengths add.** Leaving an edge at tick `t` puts the boid at `t + 1 − L(e)` on the next.
More usefully at a vertex with several edges: subtract each arriving edge's own length and
every edge around the vertex is measured from the vertex itself.

**Distance formula:** `tick(end) − tick(start) + Σ lengths of every edge on the route except
the last`. Enumerate routes by DFS over the edge arcs.

### How to solve it

One **joint least squares** over all states and all lengths at once: one residual per
transition, `t(u) − t(s) − 1 + L(edge(s))`, linear in both. Solve matrix-free by conjugate
gradient on the normal equations.

Things that did not work, with reasons:

- **Gauss-Seidel / sweeping diverges in practice.** With one state anchored, conditioning goes
  as the *square* of the graph diameter. A 62-minute background run produced nothing. CG
  converges in a few thousand steps.
- **Reading the span back and refitting the length diverges.** dabeone edge 7 walked 90 → 375.
  The span (end-to-end clock time) is *not* the length (tick drop across a boundary). The
  shortest way through an edge is a shortcut — the clock crosses dabeone edge 2 in 123 ticks
  where the shortest path is 109 states.
- **Tikhonov / low-weight soft constraints are a trap.** They are correct only as their weight
  → 0, and they destroy convergence: cost goes as `1/ε` for CG and `1/ε²` for marching. Fix
  the gauge **structurally** instead.
- **Pinning canonical paths at whole ticks** is the main source of boundary strain. Releasing
  it took dabeone's worst vertex discontinuity from **8.37 → 0.39** and plait's from
  **28.67 → 0.56**.

### The gauge

The fit is underdetermined in a fully describable way: the null direction is `δt` constant per
edge (`c_e`) with `δL(e) = c_e − c_f`, and `c` must agree across all edges downstream of a
common edge. Quotient by that; a **spanning forest of the class graph** gives exactly the
pinnable lengths (5 on dabeone, 3 on plait) plus one tick. Restore them afterwards by a small
least-squares solve against the traversal estimate.

**Sanity check that costs nothing:** inverse edge pairs should come out near-equal
(157.37/157.77, 99.75/100.15, 752.89/753.39). Nothing in the solve knows about inverses, so
agreement is independent evidence the gauge picked a sensible representative.

### Two traps

- **Run the solver to a residual, not a fixed step cap.** plait's worst vertex error read 0.56
  or 1.00 depending only on which state was anchored; 1.00 was the true figure and 0.56 was
  unconverged.
- **Keep lengths as reals.** Rounding fitted lengths to whole ticks injects up to half a tick
  of error *at every edge crossing*, invisible inside an edge because a length only enters
  where a boid leaves one. It was the same order as the effects being measured. Fixing it
  changed conclusions: a weighting that looked like a regression became an improvement, and a
  non-monotonicity vanished. Worst single-tick step fell 0.506 → 0.296 (dabeone UNIFORM) and
  0.968 → 0.602 (plait CIRCULATION).

### Diagnostics that told the truth

- **Clock advance per tick** over every transition: mean is the *scale*, spread is the
  consistency. But the **unweighted mean is a poor proxy** — dabeone UNIFORM advances 0.9983
  per tick unweighted yet a 100-tick journey reads 98.10, because the transitions boids
  actually fly are ~1.8% slower than the average transition. The traffic-weighted version is
  just the corpus mean.
- **Band violations concentrate entirely at states with in-degree ≠ out-degree.** 1in/1out,
  2in/2out, 3in/3out are all at 0.0%; 1in/2out is at 61.3%. An early claim that they came from
  "routes of very different lengths" was wrong.
- Band checks must be **pairwise**, one composite at a time, with the length increment applied
  first. Pooling predecessors from different upstream edges invents a wide band and hides
  violations (3 found pooled vs 2675 pairwise).
- The red/blue rate map shows strain that is **transverse to the corridor** — the inside and
  outside lines of every bend disagree, because one edge length has to serve two physically
  different path lengths. It is not intersections and not rare edges.
- **Local smoothness and journey accuracy are in tension.** Pixels off-target *rise*
  monotonically as weighting improves the corpus (dabeone unsteered map 2448 → 5714). The
  field maps diagnose a different thing than the corpus does.

---

## 5. Transition weights

A weight should be **how often a transition is actually taken**. The property to insist on
first is **conservation**: inflow equals outflow at every state, because traffic does not
appear or vanish. All-weights-equal fails this wherever in-degree ≠ out-degree, and that
failure *is* the `(in−out)/(in+out)` bias.

**Enforce it multiplicatively, not additively.** Conservation is linear so the nearest
conserving weights are a closed-form projection — which sends about a tenth of them **negative**
(range −1.48 to 4.31, ~9% negative, 37,064 clamped). Clamping destroys the conservation it
existed to create and leaves a 4×10⁶ weight ratio that pushed the solve from 2,791 to 18,970
iterations. Geometric-mean rescaling stays positive by construction.

**Results, `sd/mean` and `rms-100` averaged over flock sizes {1,2,4,8,16,32}:**

| weighting | dabeone sd/mean | dabeone rms-100 | plait sd/mean | plait rms-100 |
|---|---|---|---|---|
| UNIFORM | 1.782% | 2.847% | 1.442% | 1.851% |
| CIRCULATION | 1.756% | 2.502% | 1.433% | 1.724% |
| **lifted, memoryless** | **1.653%** | **2.014%** | **1.410%** | **1.567%** |
| lifted, γ=0.50 momentum | 1.678% | 2.120% | 1.419% | 1.595% |
| lifted, γ=1.00 momentum | 1.692% | 2.193% | 1.429% | 1.613% |

**The winner is the lifted memoryless flow**, and the reason is worth stating: it seeds one
unit of weight per *request* rather than per *distinct successor*. Where the collision veto
collapses two requests onto the same successor, request-weighting counts it twice — which is
what the simulation actually does. It beat CIRCULATION by more than CIRCULATION beat UNIFORM.

**Solving the lifted flow.** Nodes are `(state, last request)`, 3× the state count. Seed each
arc from the chain and balance as usual. **Critically: ~19% of lifted nodes are unreachable** —
`(state, left)` where nothing arrives at that state by a left request. No conserving flow
through them exists, so balancing can only creep them toward zero and **stalls everywhere
else** (worst node flow 1.95 and 5.80 rather than ~0.05). Prune to the part where every arc
lies on a cycle, as a fixed point, before balancing. Leaving them in reverses the conclusion.

**Momentum is real but does not help the clock.** Measured pre-veto steering is very sticky —
`P(left | left) ≈ 87%`, and **L↔R reversal in one tick is 2–3%**, essentially forbidden. But
weighting by it makes the clock slightly *worse*, monotonically. Also: the exit rate from a
turn is nearly flock-independent (~10%/tick) while the exit rate from straight swings 1.8% →
24% with crowding, and **they cross at about 8 boids**.

---

## 6. Two boids can be solved exactly

With two boids the whole system is small enough to enumerate, which turns "what did a thousand
simulations show" into "what is possible at all".

**Sample the state after the psyboid moves and before the boid does.** The state is then two
triples instead of two triples plus a phase flag, halving the space; the other half is
recovered by letting the boid take the move it was always going to take.

- Pair space on dabeone: `136,276² = 18,571,148,176`. A visited bitset is **2.16 GiB** as one
  `long[290,174,190]` — comfortably feasible.
- **Pack the frontier as `(p<<32)|b`, not as the bit index.** Recovering indices from a bit
  number needs a 64-bit division, and at hundreds of millions of expansions that division is
  most of the runtime.
- Forward closure from one warmed-up arrangement found **213,423,450 states (1.15%) in 17
  seconds**. Four other independent seeds each reproduced the *bit-identical* set.

**What it yields immediately:**

- The psyboid reaches **100%** of live states; the boid reaches **79.86%**. There are 27,449
  states the boid can *never* occupy however it is herded.
- Given the boid's exact state, the psyboid is confined to **1,272–3,448** states on average
  (0.93%–2.53% of the map) — so even without filtering, a single boid's position is highly
  informative. A prior intuition that "the psyboid can be anywhere" was wrong.
- **Exactly three steered edge transitions exist anywhere** on dabeone: `2→1`, `4→0`, `5→6`.
  Everything else is straight-only. That is what makes a finite cover possible.

**Plotted in clock coordinates** (boid tick × psyboid tick, one panel per edge), the constraint
is **slope-1 diagonal bands**: while neither boid leaves its edge, the tick *difference* is
conserved. Bands broken into pieces against one psyboid route are not broken constraints —
they are constraints belonging to a *different* route, visible only where the routes share an
edge. Against the right route a single streak runs unbroken across five edges.

---

## 7. Windows

A **window** is where a leader must be, tick by tick, for a boid to take an exit it otherwise
would not. Found via the leader-slice method: fix the follower at tick `τ` along its edge and
ask where its leader can be; the answer is a band per leader edge.

**Do not define a window against a hardcoded override length.** Doing so makes the window's
start nothing but the edge boundary displaced by that constant. The two quantities that
actually matter are:

- how far a boid can travel in `(x,y,d)` before it **absolutely has to** turn to take the exit;
- where it is **guaranteed to exit no matter how it turns**.

Both lie entirely inside one edge. And the second set is best thought of as *already having
exited* — it has simply not yet crossed whatever landmark was drawn near the start of the exit.
Landmarks are a convenience for describing geometry, not part of the definition; the
decomposition-from-gates method replaced them entirely.

### Steering cost is dynamic programming, not a search

Non-straight ticks **need not be consecutive**, and a right-facing exit *can* navigate left.
So the minimum number of steered ticks to reach a target edge is `O(states)`:

- states reaching the target in one straight tick have value 0;
- states reaching it in one turning tick have value 1;
- otherwise `value = min(1 + value(left), 1 + value(right), 0 + value(straight))`, for
  navigations that stay on the edge.

Then search the states that can backwards-navigate off the edge for the lowest value. This is
a 0-1 BFS. It took dabeone from 103–119 to **7–8** and plait from 717 to **6**.

- **The band's width is the answer, not its position.** Position depends on the arbitrary
  choice of `τ`; width is the tolerance in the leader's position and does not move when the
  envelope does.
- **Widths saturate to the whole edge within a few ticks** because the search lets a leader
  weave to hold station. Only the *opening* band is meaningful. Tightest measured: `2→1` at
  leader edge 7, **0.2 ticks**; `4→0` at leader edge 2, **1.0**.
- Backward closure for the envelope must use **unsteered** predecessors only, and sources must
  be folded in *before* the forward closure or forward-closedness breaks (this changed "8 of
  10 sources leadable" to 10 of 10).

**Widening the window.** Generate windows at **halved `STRAIGHT_BIAS`** rather than by
changing `SEP_W` — it scales how decisive an influence must be without altering what any
influence is, and the same knob is wanted on the photo-solving side. Halving widens most bands
25–40% and opens several earlier. For separation windows additionally record a
**double-strength separation** band; it only bites within `rSep`, so applying it globally still
only changes separation cases.

**Never do this by editing `Params`.** The simulation's constants must not move or every
corpus and ingest taken under the old values quietly stops meaning what it meant. Pass a
`Flocking` record instead.

---

## 8. Classifying a corpus

For every turn that changes which edge comes next, assign a cause: **the suspect was
overridden**, or **some single other boid would have sufficed alone**. Anything else is a
classification failure.

> ⚠ **The figures in this section over-count, and the method as stated is wrong.** Testing
> sufficiency by recomputing the decision with one candidate as sole neighbour looks exact and
> is not: `EdgeInfluence.steer` returns `0` for anything out of range or behind the FOV, so
> once the suspect is already committed, straight travel lands it on the edge it reached and
> **every distant boid passes as a sufficient leader.** The 85-of-89 result below is therefore
> an upper bound on "led" and a lower bound on "unexplained". `ExitAudit` is being respecified
> — see `ROADMAP.md` §2. Keep the *shape* of what follows; distrust the numbers.

**Test sufficiency directly** — recompute the suspect's decision with that candidate as its
only neighbour and check it lands on the same edge. Exact, no tolerances, and it needs the
single-neighbour closed form to be fast. The fix is to ask the question while the outcome is
still open — at the last uncommitted state, with the candidate inside flocking vision — not at
the crossing itself.

**Only route changes matter.** Leaving an edge a tick early or late is a perturbation along
the route the boid was already on. Counting those buries the handful of real cases under
thousands (LATE alone ran 5,149 events to WRONG's 159).

**The counts that used to be here have been withdrawn.** They were produced by the classifier
described above, which passed every distant boid as a sufficient leader, so both the "led"
figure and the residue of unexplained cases were wrong. What survives is the shape: the great
majority of route changes did have a single sufficient leader, a small residue did not, and
every residue case had a leader inside the separation radius. Re-measure before quoting
anything.

**Why failures happen at all:** flocking sums its neighbours before choosing, so two boids can
produce a turn neither would produce alone. With one boid inside `rSep` and `n` outside, the
close one contributes one boid's worth of push and the far ones `n/2` boids' worth of pull.

**Warmup matters and is cheap.** The `5→6` transition occurs only in the first 500 ticks —
five events, then **zero across 1.28M boid-ticks**. `2→1` and `4→0` continue at a steady rate
forever. Anything measured from a cold start should be re-measured warmed before it is
believed.

---

## 9. Synthetic corpora

A three-state Markov chain over `{L, S, R}`, plus the map's veto, reproduces flown journeys
well enough to be useful and is **map-independent by construction**.

- Draw the first steering from the chain's **equilibrium**; run the chain on the *intention*,
  letting the veto overrule the move without changing the boid's mind.
- **Momentum is the knob that sets travel-distance spread.** iid → sticky moves dabeone's
  `sd/mean` 1.374% → 1.716% against a corpus band of 1.747–1.803%.
- Matching needs a chain **stickier than the measured tick-to-tick rate** (92/7/1 against a
  measured 87/11/2), and I have no confirmed explanation for the gap.
- The synthetic harness **ranks weighting schemes correctly** (never got the sign wrong across
  40 runs) but **cannot size the gain** — off by up to 1.7× and drifting with a parameter that
  has nothing to do with weighting.
- Independent check worth doing: the chain that matches the spread should also match the
  **veto rate** (real flocks are overruled on 56–61% of requests on dabeone). Two unrelated
  quantities agreeing on one chain is much better evidence than either alone.

---

## 10. Method notes that repeatedly paid off

- **Cache expensive results content-addressed, and put the *code version* in the key.** A
  metric store keyed only on inputs silently returned answers from the old solver after a bug
  fix, producing bit-identical wrong numbers. That cost a full wrong conclusion.
- **A control that isolates one variable is worth more than the experiment.** Running the
  lifted weighting at zero momentum revealed that the entire gain was the lifting, not the
  momentum — and that the measured marginals contributed nothing at all.
- **Paired comparisons.** The synthetic walk does not depend on the metric, so the same
  journeys can be scored under every clock. That makes a 0.03-point difference meaningful.
- **Prefer an exact reformulation to a bigger sample.** Two-boid reachability, single-neighbour
  closed forms, and structural gauge fixing each replaced a sampling problem with an answer.
- **Reuse the real rules rather than reimplementing them.** Where a specialised copy is
  unavoidable for speed, verify it against the original (400,000 random cases, 0 disagreements)
  — evaluation order and strict comparisons decide every tie, and *straight beats both turns,
  left beats right*.
- **Suspect your own filters when a count moves and shouldn't.** Two conclusions this project
  nearly shipped were caused by measurement bugs, not by the system.

---

## 10a. Superposition — why one neighbour is not the unit of explanation

Established 2026-08-29 by drawing every exit a full-information classifier could not account
for. This is physics rather than bookkeeping, and it limits what *any* pairwise analysis can do.

**Each rule normalises before weighting** (§1). The consequence is that a rule's output is a
direction with a fixed magnitude, and adding neighbours changes the direction, not the strength.
Three things follow, all of them observed:

- **Two close boids push along their vector mean.** Separation sums its neighbours' pushes and
  *then* normalises to `W_SEP`, so two boids inside `rSep` produce one full-strength push in a
  direction belonging to neither. A boid that turns under both, where neither alone turns it, is
  not a tolerance problem — no pairwise table represents it at any constants.
- **Partial cancellation is amplification.** Two alignment vectors that largely cancel leave a
  short residual which normalisation scales back up to the full `W_ALI`. Measured on one exit:
  two headings whose sum retained 0.855 of a possible 2, renormalised to an across-heading
  component of `+60` against a straight bias of `3.44`.
- **A neighbour can matter by cancelling another neighbour's term.** In the same exit, one boid
  contributed almost no alignment itself; its role was to cancel the *cohesion* that was
  defeating the boid which did. Cohesion pulls toward a neighbour where separation pushes away,
  so for a separation-dominated or a behind-you neighbour, cohesion is an obstacle and a crowd
  removes it.

**Which rule dominates predicts how hard the exit is to account for.** Separation is short-range
and decisive, so one boid holds a whole stretch of history. Alignment is long-range, weak and
diffuse, so several boids each contribute a little and which one leads shifts along the history.
Measured: an alignment-carried arc left fifteen times the unaccounted residue of a
separation-carried one on the same map.

**And the residue is not random with respect to the argument.** It concentrates on genuine
leader-follower pairs — a real leader present at the edge of perception, doing the leading, and
refused because it is not individually sufficient. Since an unexplained turn reads as evidence
*toward* the suspect, that is a wrong answer in a specific direction rather than noise.


**A tick only needs a leader if coasting would not have produced the move**, and on this map most
ticks of a history do not. Measured 2026-08-30 on one three-boid exit: of the 33 ticks between
the suspect's last settled state and its entry onto the envelope, **10 demanded an influence and
23 were free** — either nothing was steering, or the collision veto overrode the request and every
turn collapsed to the same successor. On a free tick *every* neighbour "accounts" for the move,
including one on the far side of the map contributing nothing.

That matters twice over. It makes a raw coverage count — "boid 3 accounts for 20 of 24 ticks" —
flatter every candidate equally, since the free ticks are free for all of them. And it makes a
handover look easy: two coverages that overlap for twenty ticks may not overlap on a single tick
that demanded anything. **Count only the demanding ticks.** Same exit, restated on them: the third
boid explains 5 of 10 and the psyboid the other 5, with an empty intersection — the two accounts
abut rather than overlap.

Free ticks never break a negative result, because they only ever *add* coverage; a boid that
fails to cover the window when free ticks are counted in its favour has certainly failed. They
break positive ones, and the handover rule is a positive one.


## 10b. Straight travel collapses almost everything into one orbit

Measured 2026-08-30 on dabeone. Straight steering is a *function* on live states — one successor
each — so its graph is rho-shaped: every trajectory runs into a cycle and stays there. The union
of those cycles is small in a way worth knowing about.

**Of 136,276 live states, 278 lie on a straight-travel cycle, and they form a single loop.** Not
one loop per phase of the step lattice, as the four-pixel step would suggest; one. The veto is
what does it — a request it refuses snaps two trajectories that were a pixel apart onto the same
state, and after enough wall-following everything has been merged. That is the same phenomenon as
offset lock seen from the other side.

Two consequences:

- **A lone boid on a dab-like map has almost no long-run freedom.** Whatever it starts as, it is
  on one of 278 states within a few laps. Anything a scene shows that is not on or near that orbit
  is evidence that something else is in the picture — which is the whole basis of the
  requires-explanation test, now stated map-wide rather than per edge.
- **A free correctness check.** The loop is 278 states, so one lap is 278 ticks; the clock,
  fitted independently, gives 275.29 ticks for the simple loop `[4, 2, 7]` over the same edges.
  Two measurements that know nothing about each other, agreeing to 1%.

The set only becomes usable after **one partial tick** — turn as the rules say, advance partway,
land on the samples the collision test walks — which takes it from 278 to 1,610. Without that it
is a measure-zero curve that no boid knocked sideways by a pixel is ever on again.


## 10c. Normalising a sum throws away the one thing that measured agreement

Measured 2026-08-30 across 399,321 arrangements sampled from live states, and it generalises to
any flocking model that normalises per rule.

Each rule sums its neighbours' contributions and then rescales the sum to a fixed weight. **The
length of that sum is the consensus measurement** — full length is unanimity, near zero is
neighbours pulling apart — and rescaling replaces it with a constant. What survives is the
*direction* of a residue, at a magnitude that says nothing about how much of it was left.

**The failure mode is not subtle and not rare.** Signals of `X` and `-X + eY` combine into a
full-strength signal along `Y`: a direction neither neighbour asked for, at a magnitude neither
could have produced. Measured on this map, alignment's cancellation ratio (sum of lengths over
length of sum) has a **median of 1.41 but a p90 of 10.2**, and a tenth of arrangements are above
10x. At p99 a pair asks for something **4.1x** more decisive than either neighbour alone.

**Three consequences worth carrying to any similar model:**

- **A distance falloff inside a normalised rule is inert whenever one neighbour contributes.**
  Normalising a single vector discards its length, so separation's `(rSep - d) / rSep` never sets
  a magnitude; it only ever shapes a direction, and only when two or more neighbours are close.
  A reader of the formula would not guess that.
- **Averaging is the fix and reordering is not.** Summing the per-neighbour votes and normalising
  the *total* — moving the normalisation from the rules to the end — measures **worse** than
  leaving it where it is. Normalising at all is the defect.
- **Averaging bounded per-neighbour votes gives an exact guarantee.** A mean is a convex
  combination and the across-heading component is a linear functional, so the combined signal
  provably lies between the two single-neighbour signals. It also costs less: no square roots at
  all against three per decision.

**Mainstream implementations do not do this.** Conrad Parker's pseudocode takes the mean neighbour
position and the mean neighbour velocity with no per-rule normalisation; the Nature of Code
averages, then rescales, but the steering force is `desired - velocity` under a `limit(maxforce)`
clamp, so the boid's inertia dominates and the amplified part is bounded away. Both bound magnitude
**once, on the total**. A model with per-rule normalisation, no downstream clamp and no velocity
state — as here, where a discrete turn is chosen straight off the desired direction — has removed
every damping term the usual formulation relies on.

**And the residue tracks it.** Exits that no pairwise account covers are, at envelope entry,
**4.6x** more likely to have heavy alignment cancellation than accounted ones, and their desired
direction is at the median **1.88x** longer than the average of what their neighbours individually
asked for, against **1.21x** for accounted exits. The unexplained residue is substantially a
measurement of the aggregation rather than of flocking.


## 11. Things that look like findings and are not

1. **Phase combs.** Anything that comes out as an evenly-spread speckle rather than a region.
2. **A stale cache.** Identical numbers after a real fix means the cache key is wrong.
3. **Extremes at edge crossings.** Usually the rounding of an edge length, not a bug in the clock.
4. **Wide bands near an exit.** Windows saturate by construction; only the opening is real.
5. **Cold-start transients.** Measure warmed before concluding a behaviour exists.
6. **`straightTo` as a universal.** It is a per-edge summary; follow-through states violate it.
7. **A relative residual as an error bound.** With weights spanning 10⁷ the condition number
   makes a 1e-8 residual worth very little.

---

## 12. Where things live

| concern | class |
|---|---|
| flocking rules, the one definition of what a boid perceives | `MovementLogic` |
| one tick, in index order, with the decision tap | `Boids2DEngine` (`Trace`) |
| every tunable constant; **never edited** | `Params` |
| the same constants as an argument, for analysis | `Flocking` |
| a movement override; one boid, one turn, a stretch | `PsyboidOverride` |
| the flock at one instant | `Sim.State` |
| survivable states, successors, predecessors, the veto | `NavMap`, `NavMapBuilder` |
| content-addressed frozen maps | `MapStore` |
| registered maps, radius and flock size | `PresetScenarioParameter` |
| edge decomposition and everything driving it | `SimTest` (`labelFor`, `decompose`) |
| per-edge navigation, stability, scoring, exit turns | `EdgeNavigation` |
| the clock — tau values and real-valued lengths | `EdgeMetric`, cached by `EdgeMetricStore` |
| transition weights, including the lifted flow | `EdgeWeights` |
| distance between two states across routes | `EdgeDistance` |
| critical envelopes, leads, the single-neighbour model | `EdgeInfluence` (`steer`) |
| leader bands at a fixed tau | `EdgeSlice` |
| exhaustive two-boid reachability | `TwoBoid` |
| accounting for every exit in a run | `ExitAudit`, `ExitRender` |
| what a solver may know before it sees a scene | `SolverFacts`, built by `SolverStore` |
| naming the psyboid from one arrangement | `Solver` |
| one kind of evidence, as a per-boid weight | `Clue`, `UnstableEdgeClue` |
| psyboid search over branch decisions | `PsyboidBits` |
| the plan corpus, verified by replay | `PsyboidCorpus` |
| renders | `Boids2DRenderer`, `NavMapRender`, `EdgeGraphRender`, `SceneRender`, `TwoBoidRender`, `TwoBoidRouteSheet` |

`Params` holds what the simulation actually does and **must not be edited** — every corpus and
map ingest taken under the old values silently stops meaning what it meant. Pass a `Flocking`
instead.

`GLOSSARY.md` carries the same table with canonical names and output paths; `README.md` has
the artifact index and the entry points.

Documents:

- `README.md` — what exists, what is verified, where every artifact lives. **Start here.**
- `GLOSSARY.md` — every term with a precise meaning, and its name in the code.
- `EDGES.md` — canonical for edges, routes and leader windows.
- `ROADMAP.md` — what is being built now, and the specifications for it.
- `PIPELINE.md` — every step from a PNG to this level of analysis, with real invocations.
- `CONTRACTS.md` — what a play area guarantees. Partly historical.
- `BACKLOG.md` — parked work, with the reasoning for parking it. Ageing.
- `SESSION-LOG.md` — what each session did.
- `PROMPTS.md` — every prompt given about this project. Append yours; not required reading.

## 13. Open threads

- **Whether the critical envelope covers the earliest possible influence.** Coordination runs
  over several ticks, and if the envelope begins after the first tick a leader could act, the
  window opens too late and a real leader falls outside it — indistinguishable from a genuine
  multi-boid case. Priority one; see `ROADMAP.md` §1.
- The `{3,5,6}` loop on dabeone: `5→6` closes a cycle the psyboid can maintain to keep a boid
  off the scoring edge indefinitely. Only reachable cold; excluded after warmup.
- Multi-boid superposition was thought to cover the last 4.5% of route changes. **That figure
  is now unreliable** — see the warning in §8 — but the mechanism is real: flocking sums its
  neighbours before choosing, so two boids can produce a turn neither would produce alone.
- Drift tolerances for the cover have not been set from the corpus yet.
- Cause — `SEPARATION` vs `ALIGNMENT_AND_COHESION` — is not stored on any band. Alignment and
  cohesion are a joint cause; there is no clean split and they work in tandem.
- Whether the momentum lift survives conditioning on state: `P(turn | state, last)` against
  `P(turn | state)` was never measured, so the 2.4× unconditional lift may be map geometry
  the state-based weighting already has.
