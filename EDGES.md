# Edges on the dab-family maps

How the route graph on `dab`, `dabnt` and `dabeone` is defined, derived and validated.
Written 2026-08-16 against physics version 2.

These maps look complicated and are not. Whatever a boid does it returns to the same
stretch of corridor in a bounded, predictable number of ticks, and along the way it faces
a decision tree with only two real outcomes.

```
choose from {exit 1, exit 2, neither}
  exit 1 or exit 2 -> score ~53, choose again in ~530 ticks
  neither          -> score 0,   choose again in ~285 ticks
```

The psyboid can always take an exit. An ordinary boid takes one only when other boids
happen to be positioned so that it turns right at the critical moment.

---

## 1. The five circuits

Five closed circuits, named A–E, each a lap starting and ending at a chosen start state.
A is the main loop and does not score. B–E all take an exit and score.

| route | takes | scoring loop | overrides (dabnt) |
| --- | --- | --- | --- |
| A | neither exit | — | none |
| B | exit 1 | anticlockwise | `{19,32,+1}`, `{gateSofC-5,8,+1}` |
| C | exit 1 | clockwise | `{19,32,+1}` |
| D | exit 2 | anticlockwise | `{122,32,+1}`, `{gateSofE-5,8,+1}` |
| E | exit 2 | clockwise | `{122,32,+1}` |

Overrides are `{onset tick, duration, turn}`, `+1` being a right turn. B and D differ from
C and E only by a second, later override that reverses the direction the scoring loop is
flown. `gateSofC` / `gateSofE` are the ticks at which C and E cross the scoring gate.

**These onset numbers are not intrinsic.** See §5.

## 2. The nine edges, plus X

An edge's name is the set of routes that traverse it.

```java
{"ABCDE", "ADE", "A", "BC", "DE", "BCDE", "BD", "CE", "ABD", "X"}
```

Three branch vertices (one in, two out) and three merge vertices (two in, one out):

| kind | vertex | meaning |
| --- | --- | --- |
| branch | `ABCDE -> BC | ADE` | exit 1 taken, or not |
| branch | `ADE -> DE | A` | exit 2 taken, or not |
| branch | `BCDE -> BD | CE` | which way round the scoring loop |
| merge | `{BC, DE} -> BCDE` | the two exits rejoin |
| merge | `{A, BD} -> ABD` | main loop rejoins |
| merge | `{ABD, CE} -> ABCDE` | everything rejoins |

**Edge X** carries no route. It splits off `CE` and merges into `BC`, and largely overlaps
`A` while running the opposite way. It exists because the corridors permit it, not because
any circuit uses it. It is derived as A's complement: in each region A occupies, whichever
direction-half A does not own is X.

X matters — it is why a boid that has taken an exit can still avoid scoring (§5).

## 3. The two defining rules

Both are properties of the state graph, where a transition is a permitted turn
(`NavMap.constrainTurn(x,y,d,t) == t`) followed by a step.

> **At a branch**, a state that can still reach both outcomes is on the **inbound** edge.
> **At a merge**, a state reachable from both inbound edges is on the **outbound** edge.

So an edge boundary is not a line anyone draws — it is where the set of reachable futures
(or reachable pasts) changes.

### Direction, not position

Most live pixels belong to exactly **two** edges, one per direction of travel. `BC` and
`DE` are disjoint in `(x,y,d)` but overlap heavily in `(x,y)`, running opposite ways.
Any representation keyed on position alone is wrong.

The two small crossings between the main loop and the outer loop carry **four** edges
through the same pixels. Signal bleed there is the main hazard when deriving edges.

## 4. Deriving edges: what worked

The edge map is `(x,y,d) -> edge`, stored as one byte per state, mirroring the navmap's
indexing: `(x + y*width) * TURNS + heading`.

1. **Trace the five circuits** from the start state with the overrides above. Each yields
   `(tick, x, y, d)` per step.
2. **Region annotation** generalises those 1-pixel-wide traces to whole corridors. A
   hand-drawn overlay marks continuous regions; each region-half is exactly one edge:
   - **red** region: `16<d<48` is one edge, `d<16 or d>48` the other;
   - **blue** region: `0<d<32` is one edge, `32<d` the other;
   - **white** at the two four-edge crossings.
   A region must never span a branch or merge — that is the whole load-bearing constraint.
3. **Resolve splits and merges** with the tables in §2.
4. **Seed X** as A's complement in A's regions.

Validated on dabnt: all five circuits reproduce the stated edge order, 2,377 route states
checked with 0 wrong, and every unresolved state is unreachable.

### Route tracing gotcha

A circuit is complete when it returns within Euclidean distance 4 of the start **and**
within 8 heading steps of the start heading. Position alone is not enough: every outer-loop
route passes the start point twice, outbound and inbound. Waiting for an exact state repeat
overshoots by whole extra laps.

## 5. The override-window trap

**Do not define an exit window by sweeping override onsets.** Sweeping onsets with a fixed
override duration measures the override vocabulary as much as the map: the earliest onset
that still exits is the true commit point shifted back by the duration, so halving the
duration moves every window. On dabeone a 32-tick right turn gave "exit 1 at onsets
268–277 and 0–12" — the upper bound 12 is real, the lower bound is an artifact.

The intrinsic question is about states, not ticks:

> How far can a boid go before it **must** already be turning right to exit,
> and where is it **guaranteed** to exit however it turns?

### `firstGates` — the right computation

For every state, which landmark could be the **first** one crossed, over all legal turn
sequences. A least fixed point: a transition crossing a gate contributes that gate; one
that does not contributes its destination's set. Then:

- both an exit and a continue landmark reachable -> **decision still open** (inbound edge)
- only an exit landmark -> **committed** (already on the outgoing edge)
- only a continue landmark -> exit no longer possible

The commit boundary is placement-free. The *counts* on either side are not — they depend
on where the continue landmark sits.

### What is and is not intrinsic

`avoidScoring` computes the viability kernel over a map where the scoring region counts as
wall, giving states from which a boid can fly forever without scoring. Its complement is
"cannot avoid scoring", which needs no drawn landmarks at all.

**On its own it does not locate the exit branch.** Measured on dabeone: a boid that takes
exit 1 at tick 12 does not become unable to avoid scoring until tick ~386 — because edge X
lets it leave the exit without ever entering the scoring loop. So `avoidScoring` alone
locates the **scoring-loop commit**, a real and separate vertex, roughly where `pre-score`
is crossed.

### One anchor on A is enough

Taking an exit is irreversible with respect to A. A boid that has exited can avoid scoring
indefinitely by way of X, but it **can never rejoin an A edge without scoring first**. So:

> **has exited** == **cannot reach a known A state without scoring**

That needs no drawn line, no direction convention and no annotation — just one anchor state
anywhere on route A. Backward breadth-first from the anchor over the real transition
relation, keeping only steps whose segment misses the scoring region; everything not
reached has exited.

Verified on dabeone from anchor `(200,179,33)`, reproducing the landmark-derived boundaries
exactly:

| onset | exits at | state |
| --- | --- | --- |
| none | never | stays on A |
| 0, 6, 12 | tick 22 | `(147,129,50)` |
| 13 | never | — |
| 100, 104, 108 | tick 124 | `(95,261,25)` |
| 109 | never | — |

89,600 of 136,276 live states have exited; the remaining 46,676 are the A edges.

### Cross-checked against dabnt's annotated edge map

Anchored on the settled single-boid orbit at `(264,361,51)`, against the hand-built edge
map, over all 172,666 live states:

| edge | expected | classified on A | classified exited |
| --- | --- | --- | --- |
| ABCDE, ADE, A, ABD | on A | **all** | 0 |
| BC, DE, BCDE, X | exited | 0 | **all** |
| BD | exited | 1,553 | 5,223 |
| CE | exited | 826 | 7,336 |

Eight of ten edges exact, X included. The 2,379 disagreements (1.4%) are entirely on BD and
CE, and they are not errors: those two edges **span the scoring region**, running from the
scoring-loop split back to the merges. A boid on the far end of BD has already scored and
can reach A without scoring *again*, so it correctly fails the test.

So the test measures "will score before returning to A", which equals "has exited" only for
edges lying wholly before the scoring event. It cannot place BD or CE. For solver purposes
that cut may be the more useful one, since it is exactly the set of boids with a score
still ahead of them.

Note that **every onset within a window commits at the identical state and tick**, twelve
ticks of onset spread notwithstanding. The corridor funnels them: near an exit the veto
leaves only one legal turn, so the branch is a specific gate state rather than a diffuse
boundary. The same effect appears on dabnt, where ticks 19–26 have left and straight both
vetoed.

What still needs a reference is telling the outgoing edges apart — `BC` from `DE`, `BD`
from `CE` — since those differ by direction through shared pixels. dabeone's blue lines do
that job in one bit each.

## 6. Map-specific data

### dabnt

> ⚠ **The old start `(270, 156, 32)` is dead under physics 2** — `alive == false`, and the
> edge map has no label for it. `constrainTurn` returns the proposed turn unchanged from a
> dead state, so `traceRoute` still produced paths from it and the edge map built on those
> paths validated against itself; the check was circular, not sound. The traces and edge
> map in `ingests/48b46d3d06e54c75/routes/` therefore start from a state no boid can
> occupy and need regenerating from a live start. Take one from the settled single-boid
> orbit, e.g. `(264, 361, 51)`, which is live, reachable and labelled `A`.

| | |
| --- | --- |
| start | ~~`(270, 156, 32)`~~ dead under physics 2 — see above |
| route A lap | 289–290 ticks |
| exit routes | ~520–524 ticks, score 53 |
| live states | 172,666 (physics 2, bidirectional) |
| gates | `gate-A (186,185,179,189)`, `gate-B (81,82,183,190)`, `gate-S (311,310,273,280)` |

Gates are `(fromX, toX, yTop, yBottom)` for a vertical line; the crossing direction is
implied by which x is larger. `Gate.crossed` pads the span by 3 to catch diagonal steps.

### dabeone

Marks are drawn into the map itself and are inert to the engine — neither black
(`000000`, wall) nor scoring orange (`FF7F27`) — so they change the content hash but not
the physics.

| | |
| --- | --- |
| start | `(200, 179, 33)`, agreed by 16/16 seeds |
| route A lap | 278 ticks |
| exit routes | 533–536 ticks, score 54 |
| live states | 136,276 |
| exit 1 available | route A ticks **0–12** |
| exit 2 available | route A ticks **101–108** |
| cannot avoid scoring | 11,574 states |

| mark | colour | geometry | role |
| --- | --- | --- | --- |
| red | `ED1C24` | x=201, y179–186 | **start line**, crossed R->L; opens the exit-1 decision |
| red | `ED1C24` | x=79, y182–190 | crossed L->R; opens the exit-2 decision |
| red | `ED1C24` | x=331, y273–280 | crossed R->L into the scoring loop (A crosses it L->R only) |
| blue | `00A2E8` | x=154, y111–121 | inside exit 1: L->R is BC, R->L is DE |
| blue | `00A2E8` | x=86, y264–269 | exit 2: L->R is BC, R->L is DE |
| grey | `7F7F7F` | x82–268, y278–360 | entirely within A and X |

Each decision **opens exactly as its red line is crossed**, which is why the lines are
where they are.

Extend gates ~5px past the drawn ends to catch diagonal steps — but check first. Four of
the five run into solid wall, so extension is free. The x=86 blue line has only **4px** of
wall below it before another corridor opens at y=274, so its lower end must be clipped
(y1=270 keeps the gate, including the record's own 3px slack, inside the wall).

## 7. Leader windows (dabnt, physics 2)

Which phase differences let a leader pull an ordinary boid out of route A. Phase difference
is the leader's time-on-lap minus the follower's; the follower starts a fresh lap at 0.

| leader route | induces exit 1 | induces exit 2 | follower reaches exit edge |
| --- | --- | --- | --- |
| B, C | **233–235** | none | tick 42 |
| D, E | none | **21–30, 34–37** | tick 134–136 |

**B ≡ C and D ≡ E exactly** within this phase range, because they diverge only in the
scoring loop, later than any decision here. That collapses the leader dimension from four
routes to two classes: "took exit 1" and "took exit 2". A leader induces the exit it took
itself. The 31–33 hole is real structure, not noise.

Method: leader held on its route with a straight override for 10,000 ticks (a lone boid
proposes straight everywhere outside its route overrides, so this reproduces the circuit
while making the leader deaf to the follower), then the route's own overrides on top. Two
shifted overrides alone are **not** enough — one other boid pushes the leader off the
circuit long before the follower decides. Verify fidelity every run: 251/251 with the
straight hold, 23/251 without.

Caveats: the leader is boid 0 so it moves first, and it flies straight rather than
flocking, so these are idealised-leader windows.

## 7a. Decomposition from gates alone (the validity axiom)

A set of edges is a **valid decomposition** of a bidirectionally-navigable map when every
live point is on exactly one edge, and all points of an edge agree on the set of edges they
can go to next. Many decompositions satisfy this — one edge per point, or one edge for
everything — so a construction is needed to pick a useful one.

Refinement: whenever points of an edge disagree about their next edges, split by that set,
and repeat. Guaranteed to terminate.

> "Next edge" means the first **different** edge reachable over all legal turn sequences,
> not whatever is one step away. One-step shatters every path edge immediately, since its
> interior steps within itself and only its last point steps out.

### Construction from one gate

1. `O` = points that can return to themselves without crossing the gate — the union of
   cycles in the gate-cut graph. Its SCCs are the orbits, one edge each.
2. The complement splits into components connected by forward *or* backward travel without
   leaving the complement.
3. Refine.

On dabeone with gate `x=230, y=[200,325]` rightward (320 transitions cut) this gave five
compound edges directly — refinement was a **no-op**, so the construction lands on a valid
decomposition unaided:

| states | scoring | on A | is |
| --- | --- | --- | --- |
| 43,970 | 0 | all | {ABCDE, ADE, A, ABD} |
| 17,028 | 0 | 0 | {BC₁} |
| 17,028 | 0 | 0 | {DE} |
| 43,970 | 0 | 0 | {CE₁, X, BC₂, BCDE} |
| 14,280 | 9,077 | 2,706 | {BD, CE₂} |

Adjacency `A → {BC₁, DE} → {…BCDE} → {BD, CE₂} → A`. The two 43,970-state edges are exact
mirrors, as are the two 17,028-state ones — the same pixels travelled opposite ways.

### Cutting orbits

An orbit is any edge some point of which can forward-navigate back to itself. The axiom
cannot split one: it is strongly connected, so every point can reach everything and all
next-edge sets are equal. Cut it with a gate, refine, then merge the cut with the edges
either side to heal the artificial seam.

**The gate must be a real cut** — a line across the corridor, every cycle crossing it. A
single state is not enough: removing one state from a strongly connected orbit leaves it
strongly connected by another path, and refinement finds nothing. Place it as far as
possible from the orbit's own borders, and verify no cycle survives before using it.

Cutting dabeone's A orbit split it in two, giving 6 edges, `4 → 2 → 4` with one exit
branching from each.

### The axiom is symmetric

All points of an edge must share **both** their successor edges and their predecessor
edges. Bidirectionality does not fall out of the forward rule. Forward alone finds branches
but is blind to merges — arriving from somewhere new creates no forward distinction — so
dabeone's A loop, which has two branches and two merges, came out as two segments instead
of three. Refinement therefore computes a fixed point in each direction and splits on the
pair.

### Placing an orbit's gate

Keep it away from **both** directions of traffic across the orbit's boundary: O → Oᶜ *and*
Oᶜ → O. Measuring only the outgoing side puts the gate near where the complement flows back
in, which on dabeone meant `(284,285)` — a junction adjoining BCDE, BD, CE and X all at
once. The gate cut raggedly (62 arrival states against a healthy ~120–180) and refinement
ran away to 493 edges. Correcting the boundary test moved the gates to `(14,108,16)` and
`(14,104,48)`, a plain corridor, and both orbits cut cleanly.

Arrival count is a good health check: far below a corridor cross-section means the line is
clipping something rather than spanning it.

### Result on dabeone

Gate `x=230, y=[200,325]` rightward, then one gate per orbit: **9 edges**, refinement stable.

| states | scoring | on A | goes to |
| --- | --- | --- | --- |
| 12,471 | 0 | 12,471 | exit, 15,361 |
| 15,361 | 0 | 15,361 | exit, 16,138 |
| 16,138 | 0 | 16,138 | 12,471 |
| 17,028 ×2 | 0 | 0 | the two exits |
| 12,471 / 15,361 / 16,138 | 0 | 0 | reverse twins (X family) |
| 14,280 | 9,077 | 2,706 | scoring, back to A |

A loops as `12,471 → 15,361 → 16,138 →`, an exit branching off each of the first two. Every
size appears twice, once on A and once reversed — same pixels, opposite headings.

Still unimplemented: revert-on-invalid-merge. The spec calls for backing out to the uncut
orbit and retrying when the merged edge fails the axiom. Correct gate placement removed the
need on this map, but a bad gate still corrupts the partition rather than being rejected.

Verification gate for naming the edges: `x=255, y=[170,200]`, right-to-left selects A,
left-to-right its reverse twin.

## 8. How much annotation is actually needed

Revised after §5. The hand-drawn region overlay was how dabnt's edge map was built, but
most of what it encoded is now derivable:

| what | how | needs from a human |
| --- | --- | --- |
| has the boid exited | backward BFS from an A anchor, scoring excluded | **one state on route A** |
| where the exit branches are | the frontier of that set | nothing |
| the scoring-loop commit | `avoidScoring` viability kernel | nothing |
| `BC` vs `DE`, `BD` vs `CE` | direction through shared pixels | **one bit per edge pair** |
| reachability / unreachable states | `NavMap` bidirectional kernel | nothing |

So the minimum input is an anchor plus a handful of direction bits, not a painted map.
Not yet done: re-deriving dabnt's edges this way and diffing against its hand-annotated
ground truth, which is what would settle whether the annotation is necessary or merely
convenient.

## 9. Open

- Two-boid leader combinations — a minority of induced exits, not yet characterised.
- The canonical `(x,y,d,tick)` path per edge, for backward extrapolation.
- dabeone's own edge map: start, gates and commit boundaries are known; the annotation is
  not yet drawn.
