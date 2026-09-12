# Edges

**Canonical for edges, routes and leader windows.** Rewritten 2026-08-28 against dabeone
ingest `609cffdb84be218c`, physics version 2.

**Status:** 2026-09-12. Structure is physics-independent and stands; **figures are physics 2
unless marked otherwise**, and the flown scoring lap in §6 and §9 is the first measured under
physics 3. **§2 was rewritten and §2a added on 2026-09-08**: what was called a gate is now a *cut
line*, and **gate** names a formal construct with an exactly-once guarantee. The rule "gates are a
bootstrap, not an analysis tool" is retired. **§2a gained edge insertion and navigation gates on
2026-09-10** — how a gate is actually built, and how the same machinery does on-edge navigation
for shortcuts. **§1 and §2a gained braiding on 2026-09-12** — the axiom groups by next edges, not
by outcomes, so a one-to-three junction does not settle and a bifurcated `E⊥S` stays out of the
decomposition.

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

**"Reachable futures" means the set of next edges, not the set of outcomes.** Two states that can
both still reach `A`, `B` and `C` are on different edges if one leaves the three-open stretch by
stepping into `{AB, C}` and the other into `{A, BC}` — their *menus* differ, so their futures as
edges differ, even though their futures as outcomes are the same. With two successors there is
only one possible menu, `{A, B}`, so a binary branch or merge always settles; with three there
are eight, and every distinct menu is a distinct origin for the pieces below it, which split in
turn. That is **braiding**, and it is why a one-to-three junction does not settle — §2a.

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

## 2. Cut lines

A **cut line** is a line segment in `(x, y)` used to cut cycles so that a first decomposition
can be constructed. That is its only sanctioned use. It is what `SolverFacts.Gate` holds and
what every entry point still calls "the gate".

**The rule this section used to carry — "gates are a bootstrap, not an analysis tool" — was
retired 2026-09-08 and is not a rule any more.** It existed to stop one specific regression:
measuring *commitment to an edge* by proximity to some nearby line, when commitment is a
property of the edge relation and nothing else. That regression is still a regression. What
changed is the word: **gate** now names a formal construct with a guarantee (§2a), of which a
cut line is one instance, and the new gates are the primary way of advancing a boid.

**A cut line still lives in `(x, y)` and that is still its limitation.** It cannot distinguish
the two directions of travel through a corridor, it has no meaning in `(edge, tau)`, and its
placement is a human choice. It is recorded for provenance — so a reader can reproduce a
decomposition — and nothing at solve time reads one.

Known-good cut lines, kept because rebuilding a decomposition needs them:

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

## 2a. Gates

**Canonical, specified 2026-09-08.** A **gate** is a set of trigger conditions guaranteed to fire
**exactly once as a boid traverses an edge**. Gates are the primary way of advancing a boid for
psyboid evaluation, and probably for most other analysis too.

### The property

If an edge has a gate `G` associated with it, then **for any state `s` on that edge, exactly one
of the following is true**:

1. `s` is in `G`.
2. Any infinite *backward* navigation from `s` is in `G` for exactly one tick.
3. Any infinite *forward* navigation from `s` is in `G` for exactly one tick.

Both 2 and 3 assume the path in question never visits the edge again.

Note the quantifier: **any**, not *some*. Every navigation, steered or coasting, meets the gate
exactly once. That is what makes a gate something a psyboid cannot dodge and a search cannot skip,
and it is why the property has to be *verified* of a candidate set rather than assumed.

Read the trichotomy as: the gate is either **on** you, **behind** you, or **ahead** of you, and
never two of those. A set that some path crosses twice fails it; so does a set some path bypasses.

### Two implementations

Both are gates. Either can be used, depending on need.

| | |
| --- | --- |
| **state-based** | a set of `(x, y, d)`, firing when a boid lands on one |
| **transition-based** | a set of `((x, y, d), (x, y, d))`, or of `((x, y, d), {LEFT, STRAIGHT, RIGHT})`, firing when a boid paths through one |

**Transition-based is the slightly more versatile set.** A state-based gate can be stepped over
where a transition-based one cannot, which matters wherever the ~4 px step means a boid crosses a
tau level between ticks rather than landing on it.

Ultimately gates are stored as **sets** — of states, or of transitions. They are just sets with a
particular guarantee attached. There is no interesting representation question here.

### What follows

- **Edge transitions are gates by construction.** The set of transitions leaving an edge fires
  exactly once per traversal, because a traversal ends by leaving.
- **A gate is generally located on the edge it belongs to, but need not be.** There may be reason
  to put a gate several ticks *before* an edge starts, in which case it sits near the end of every
  edge that feeds into it. The association is with the edge whose traversal it counts, not with
  the pixels it occupies.
- **Gates cannot be placed on an edge where infinite stalling is possible.** A boid that can
  loiter can cross a gate and come back to it, which breaks exactly-once. No such edge exists on
  dabeone, dabnt or plait — but some certainly will, and the freely-navigable edge in §11 is the
  shape of the problem.
- **A gate is tied to a decomposition, not just to a map.** Decomposition is fairly rigid but not
  unique, and the gate property is defined in terms of the edge property.

### Inserting an edge, which is how a gate is built

**Specified 2026-09-10.** A gate is not drawn; it is derived from a decomposition that has had a
new edge inserted into it. Take an edge `E` and a set of states `S` on it, split `S` off, and
refine: `E` comes apart into `S` and three more, `E<S`, `E⊥S` and `E>S`. **Every transition
between two edges is well-formed by the axiom**, so the boundaries that fall out are gates for
free — no separate proof of the exactly-once property is needed.

**`S` may be almost arbitrary.** What the construction produces is the *minimal well-formed edge*
around whatever states it is given. Three conditions govern it:

1. **Reachability, which is an assertion about the `S` supplied and not something the algorithm
   can fix.** Every **entrance** of `E` — an on-edge state with an off-edge predecessor — must be
   able to navigate to `S`; and every **exit** of `E` — an *off*-edge state with an on-edge
   predecessor — must be navigable to from some point of `S`. Fail this and the result is not a
   gate, because a traversal can enter and leave without ever meeting it.
2. **Closure, which the algorithm can and should fix**, as a further perfection step: any state
   that can navigate both **to** and **from** a state of `S` without leaving `E` belongs in `S`.
3. **Phase completeness, optional.** Where `S` is phase-complete, `E⊥S` comes out as fewer
   disconnected regions — see below for what those regions actually are, which is not what this
   condition was first written to expect.

**Relationship to `splitOrbits`.** The cut-and-heal in §3 step 6 is the same operation, and the
trace shows it plainly — during dabeone's first orbit cut, final edge 2 sits in three pieces at
once. The differences are that `splitOrbits` manufactures **one very specific cut, chosen to
work**, and then heals the seam afterwards; edge insertion takes a **mostly arbitrary** set and
keeps the result.

**A self-inverse edge has to be handled.** `mergeAlongside` (§3 step 4) identifies headings mod 32
and so deliberately declines to separate an edge from its inverse — it was added against a plait
blowup, and in most cases a later step separates the two directions anyway unless they are
functionally identical. That is still tenable, but while it stands, **edge insertion must check
whether `S`'s inverse lies on the same edge**, and union it in where it does. Measured on dabeone:
edge 8 is the only self-inverse edge, and splitting a state off only one of its two halves runs
refinement past the 63-edge mask; unioning the inverse settles it at once.

> ⚠ **On dabeone and dabnt `mergeAlongside` merges nothing at all** — two edges in, two out, and
> the labelling is byte-identical with it skipped. So edge 8 being self-inverse there is *not*
> that step's doing; it is what the axiom produces, the scoring corridor having the same
> predecessor and successor edges travelled either way. The requirement to check the inverse holds
> regardless of which cause applies on a given map.


**`E⊥S` has no sides — it is the phases that bypass `S`.** Splitting it into connected components
and then merging with `mergeAlongside` was expected to leave one edge per side of `S`, so one or
two. It leaves **exactly one, every time**, and the tau ranges say why: every component *spans*
`S` rather than sitting before or after it. On dabeone edge 6, `S` is at tau 36-37 and the four
components run [29-46], [28-46], [28-45] and [29-45], of 428, 428, 427 and 393 states — four
phase offsets of one stretch of corridor, not four regions. `E<S` and `E>S` already take
everything genuinely before and after; what is left over is only the parallel phases, and
`mergeAlongside` rejoining them is exactly the job it was written for.

Component counts seen on dabeone: **1** where the partial tick covered every phase, **4** where it
did not, and **2** on edge 8 — two halves of 1,910 states apiece, which are its two mutually
inverse regions. All merge to one.

**Rendered, and it is unambiguous.** `render/gate-split/edge4-components-zoom.png` draws edge 4 at
8x with each component in its own colour: the four come out as **interleaved one-pixel stripes
repeating on a four-pixel cycle** down the middle of the corridor, bracketed by `E<S` at one end
and `E>S` at the other. Four phase offsets of a single trajectory, sampled a tick apart — not four
regions, and nothing lateral about them. The step is ~3.925 px, which is the period on the
picture.


### Where `S` may be placed, measured

**2026-09-11, dabeone, edge 8 excluded.** Eleven taus per edge across eight edges, `S` fully
conditioned each time — partial tick, perfected both ways, unioned with its inverse, perfected
again — so that placement is the only thing varying. **15 of 88 settle at twelve.**

**There is a floor.** No placement closer than about **12 ticks** to an edge boundary settles
anywhere, on any edge. The closest that does are edge 2 and edge 3 at 12.1.

**Above the floor it is neither monotone nor symmetric.** Distance to the nearest boundary does
not predict the outcome: dabeone edge 0 **blows up at tau 39.5 and settles at tau 118.6**, both of
which are 39.5 ticks from an end. Edges 4 and 5 settle at their midpoint and nowhere else; edge 2
settles at three of eleven taus.

**Conditioning matters more than placement.** The same sweep with a raw partial tick and no
perfection settles **1 of 88** — the midpoint of edge 2 — where the conditioned sweep settles 15.
Midpoints included: an unconditioned `S` at the middle of edge 0 gives 297 pieces.

**A swollen `S` predicts a blowup.** Conditioning a badly placed seed grows it — 82, 102, 78, 65
states against the 5 to 14 typical of a good one. Not a clean rule (a 36-state `S` settles on edge
7) but a strong hint, and free to check before spending a refinement.

**Trap: maximising clearance finds junctions.** Picking the state with the largest square of live
states at its own heading — the obvious reading of "room on either side" — lands on taus 1.5, 3.5
and 7.3 on edges whose lengths are 100, 81 and 102, because the widest part of a map is where
corridors cross and four edges share the pixels. Every one blew up. Constrained to the middle 40%
of an edge, the same rule picks well: **four of eight settle on a minimal `S` with no conditioning
at all**, which is the placement doing work the perfection chain otherwise has to.


**Seeing it: the cross-section, sliced by tau — keep this; it is the way to look at a constricted
stretch when diagnosing a decomposition.** A map view cannot answer whether `S` divides an
edge, because it collapses `d` and paints over every state sharing a pixel. The cross-section is
two-dimensional — one axis across the corridor, and `d` — and `GateSplit.drawCrossSections` draws
it, one tile per tick of tau, taken **perpendicular to travel** since an edge bends. Written to
`render/gate-split/edge<e>-cross.png` and `-cross-zoom.png`.

**What it shows on dabeone edge 4 is why `E⊥S` cannot bifurcate there.** The cross-section is a
blob about ten states across and ten headings tall, and `S` — the state plus its partial tick — is
**a single white pixel near the middle of it**. It touches no side at all. `E⊥S` therefore runs
right around `S` and is connected, whatever its phase structure. **The actual requirement for
`E⊥S` to split is a phase-complete bifurcation of this cross-section**, whose two axes are `d`
and the `(x, y)` translation orthogonal to `d`'s step vector — and which shapes achieve that is
measured directly below.


### What a line across the cross-section has to be, tested

**2026-09-11, dabeone edge 4 at x=222**, a straight axis-aligned stretch with no phase bleed. The
cross-section there is 52 states in a diamond — 8 wide in `y` at the along-track headings 31–33,
tapering to 2 at `d`=28 and 36 — and the thing that decides everything is the third column:

```
d=28  ....##..   stepY=2
d=29  ..#####.   stepY=1
d=30  .#######   stepY=1
d=31  ########   stepY=0
d=32  ########   stepY=0
d=33  ########   stepY=0
d=34  #######.   stepY=-1
d=35  .#####..   stepY=-1
d=36  ..##....   stepY=-2
```

**Prediction.** A tick changes `d` by at most one, so a line at constant `d` cannot be stepped over:
any path from below it to above it lands on `d0`. But `y` moves by `stepY(d)` per tick, which is 0
on the axis and **2** at the top and bottom of the diamond — so at `d`=28 or 36 a boid jumps a
constant-`y` line without touching it, and any line with a `y` component has that hole.

**Result.** Ten lines, each phase-completed, `E⊥S` split into components and merged back, each
survivor called by the sign of the line at its states:

| line | pieces of `E⊥S` below / straddling / above |
| --- | --- |
| **constant `d` (all `y`)** | **1 / 0 / 1** — 250 and 250 |
| constant `d`, two thick | 1 / 0 / 1 |
| constant `y` (all `d`) | 0 / **1** / 0 |
| constant `y`, two thick | 0 / **1** / 0 |
| both diagonals, thin and thick | 0 / **1** / 0 |
| shallow and steep | 0 / **1** / 0 |

**Only a constant-`d` line bifurcates `E⊥S`**, and it does so exactly, into two equal halves.
Every other orientation leaves one piece that straddles the line — `E⊥S` gets round it at the
`stepY`=2 headings. So the connectivity a cut needs is **one state per `y`, all at one `d`**: it is
the `d` axis that cannot be skipped, not the spatial one.

**Phase completeness is not optional here, and "no phase bleed" is the reason.** A step is 4 px,
so a line drawn at one `x` is reachable by one phase in four, and the other three make a
5,883-state `E⊥S` that spans the whole edge and swamps everything. With no bleed nothing else
brings those phases onto `S`; the partial tick has to. Once it does, exit coverage goes 94% to 100%
on every line and `E⊥S` collapses to the pockets beside `S`.

**Refinement does not settle on any of these, and that is expected**, since a bifurcated `E⊥S` is
two edges and the pockets beside a line are small and fragmentary. The piece count from
`refine()` is not the test for this question; the component sides are. Runs are capped at two
rounds accordingly.

**On a map that is not phase-locked** the cut will need constructing rather than drawing, and the
user's sketch of it is worth keeping: take a one-tick band of the tunnel, score each state −1 to 1
for the side it should end up on, and find the set of removed states that maximises the total
square of the sums over the remaining connected pieces. Whether an algorithm for that or something
near it exists is open.

### Braiding: why a one-to-three junction does not settle

**Found 2026-09-12, by feeding abstract state graphs to the real `EdgeDecomposition.refine`**
(`RefineToy`, a dozen states apiece, coarse labels `W → O → {A, B, C}`). It closes the question
the previous session left open, and it is the reason edge blowup is confined to junctions where
three edges meet.

The axiom groups states by the set of edges they can step into next — call it the **menu** — and
not by the set of outcomes they can still reach. At a binary branch those coincide: a state with
`A` and `B` open has the menu `{A, B}` and no other. At a three-way branch a three-open state can
leave the three-open stretch by peeling one option — menu `{A, BC}`, `{B, AC}` or `{C, AB}` — or
into pair-pieces, `{AB, AC, BC}` and its subsets; **eight menus after `absorb`**. Two three-open
states with different menus are different edges. Each of those is then a different *origin* for
the pair-pieces and tails below it, which split by origin; the merges below those split by which
entrance pieces reach them, including partial merges; and every phase lattice is a strand of its
own. Nested combinations — `{{AB,C},{A,BC}}` against `{{AB,C},{AC,B}}` against
`{{AC,B},{A,BC}}` — are all distinct. There is no bound in the number of outcomes alone.

| toy graph | shape of the three-open layer | pieces of `O` | settled in |
| --- | --- | --- | --- |
| FAN | one menu, `{AB, AC, BC}`; tails merge pairwise | **13** | 2 rounds |
| PLAZA | two menus, `{AB, C}` and `{A, BC}`, under one entrance | **8** where 6 were predicted — the layer itself in three | 2 rounds |
| BINARY control | the PLAZA shape with two outcomes | 3 | 1 round |
| TRIDENT | a direct commit `O → A` alongside pair-pieces | 13, including an `A via O` tail and a split layer | 3 rounds |

FAN is exactly the thirteen predicted by hand — `O, AB, AC, BC`, and for each outcome a tail per
pair-piece plus the merge — so `refine` is doing what the axiom says on the case that settles.
PLAZA is the same axiom on a layer with two menus, and it splits the layer. **No defect in
`refine` was found on any of them**; the braiding is the axiom's own behaviour.

**What settles.** A one-to-three branch comes apart cleanly only when every state of its
three-open layer has the same menu. The simple case is a chain of binary forks: two of the three
first peels `{A, BC}`, `{B, AC}`, `{C, AB}` empty. The empties could in principle sit deeper in
the braid, but one menu at the top is the way to simple edges.

**What it settles.** A bifurcated `E⊥S` cannot be kept in the decomposition: `E<S` would branch
to `{S, E⊥S₁, E⊥S₂}` and `E>S` would merge from the same three, and both braid. So a split
`E⊥S` is a navigation object — several parts for analysis or psyboid logic — and never a
decomposition object, which is what the user expected. Whether an altered rule, or a sub-edge
structure, can one day handle a three-way joint is open and parked.

**One correction to the record.** The `absorb` rule as specified on 2026-09-09 said `{X}`,
`{all of X's successors}` and `{X} + some of X's successors` are one class. The code implements
the third-equals-first half only — it drops a successor from a mask that also holds the edge
leading to it — and the second half is the outcome reading: under it `{AB, C}`, `{A, BC}` and
`{A, B, C}` would be one class, the pieces so grouped would have different next edges, and
one-step navigability would fail on them (from a `{AB, C}` state no single turn reaches `A`).
Braiding retracts that half. `EdgeDecomposition.absorb`'s javadoc now says what it does.

### Navigation gates: gates that are not part of the decomposition

The insertion above yields gates at **near-arbitrary precision**, and they do not have to be kept
in the decomposition to be useful. **A gate can serve as an additional edge in navigation logic
alone**, invisible to everything that reads the real decomposition.

**This is how on-edge navigation is done for shortcuts and longcuts.** Choose an `S` that bounds
travel along a wall, then build the gate from a chosen subset of the transitions the insertion
creates — for instance including `E<S → E⊥S` on the left and `E<S → S`, while omitting
`E<S → E⊥S` on the right. A boid steered by the same greedy one-step lookahead that handles
navigation across real edges is then unable to travel through it, because the rule refuses any
turn whose successor lies across a boundary it was not sent to. See §7 for that rule and why one
step of lookahead is all of navigation.

So a shortcut needs no new steering mechanism. It needs a gate placed where the wall is, and the
existing pilot.

### Gates versus decisions

A gate is *where* a decision is put; it is not itself a choice. A **decision** hands a boid that
triggered the gate an override that makes it take certain precomputed navigation decisions at some
of its upcoming states. **Exits and shortcuts are not different in kind** — same mechanism, same
table-driven execution, different consequence: an exit decision forces a particular edge exit, a
shortcut decision advances or regresses phase in tau-space. See `ROADMAP.md` §0i.

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



### One-step navigability, and why bad aim cannot exist

A consequence of the axiom, stated because everything that steers a boid depends on it.

> From **every** live state of edge `e`, some single turn either keeps the boid on `e` or takes it
> to any chosen `g` in `e`'s successor set.

**Proof.** Every point of `e` has the same successor set, so from every state of `e` the target
`g` is reachable by some turn sequence. A move that keeps the boid on `e` lands it on another
state of `e`, from which `g` is again reachable. If all three moves left `e` for edges other than
`g`, then no turn sequence from that state reaches `g` first — contradicting reachability.

**So executing a planned exit needs no plan beyond the next tick:**

```
if (successor(STRAIGHT) is on this edge or the planned next) return STRAIGHT
if (successor(LEFT)     is on this edge or the planned next) return LEFT
if (successor(RIGHT)    is on this edge or the planned next) return RIGHT
throw
```

**Verified exhaustively** on dabnt, dabeone and plait — every arc, every live state, no violations,
under 0.05 s a map. `Pipeline.checkNavigable` runs it on every build and throws.

**Two things this rules out.**

- **"The override aimed badly" is not an explanation for a missed exit.** Either the decomposition
  is broken or the steering was not following the rule. Nothing else is available.
- **Holding one turn is not navigation.** The axiom promises nothing about always-left,
  always-right or always-straight; reaching a given exit may require a specific alternation, and
  all three constant policies can lead to the same wrong edge. A held-turn override works where it
  was measured and can miss silently elsewhere.
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
- **Three-way joints.** A one-to-three branch or three-to-one merge braids under the axiom
  (§2a) unless its open layer has a single menu. A bifurcated `E⊥S` is therefore out of the
  decomposition for good; whether an altered rule or a sub-edge structure can carry such a joint
  is parked, not planned.
