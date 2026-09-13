# What is being built now

**Status:** 2026-09-12. `README.md` has the inventory; this file has the work in front of us
and the specifications for it. **§0i is the live thread — read that first**; its last subsection
records that braiding closed the `E⊥S` question. §0h is closed; its handoff is kept as the record
of what the benchmark measured and why that search was abandoned.

---

## The goal

A solver for dab-like maps: given still frames of a flock, name the psyboid or say honestly
that the scenario does not determine one. The wider purpose is an evaluation — see
`README.md`.

## Where the era stands

Both halves of the argument now exist in code and are wired together:

- **Requires explanation, from a snapshot alone** — a boid on an unstable edge. Needs only
  `(x, y, d)` and the decomposition.
- **Candidate reasons** — the critical-envelope windows say where a leader would have had to
  be, and `UnstableEdgeClue` chases the question backwards until it lands somewhere no
  explanation is owed.
- **Grading** — `SimTest.graded` against the plan corpus, `SimTest.solve` against synthetic
  overrides, `SimTest.solverInvariants` for the no-window skeleton.

**The exit classifier is now sound** — rebuilt on `CriticalEnvelope`, measured against the plan
corpus, accounting for 551 of 553 exits with the residue characterised (§1, §2). What is still
missing is a solver measurement worth believing: every grading figure predates the rebuild and
has been discarded, and windows are meant to be derived from a corpus rather than hand-read.
**Item 3 is what unblocks solver work.**

## 0. In flight — naming the phase map's regions

**Started 2026-08-30.** The goal for this era: turn the 21% of phase-map exits that no account
covers into *named* classes — either understood, or grouped into a category we have decided not
to understand further — and then identify and enumerate those regions programmatically, either
statistically from simulation output or as a chain of computations from the map. The regions,
not the percentage, are the workable unit: the white is not a haze, it is a handful of bands and
blobs with hard edges.

**Built: `ThreeBoidSamples`**, driven from `SimTest.main`, writing
`render/phase40-samples.png`. It reads the hand-annotated overlay
`analysis/3BoidAreasOfInterest.png` against the base `render/phase40.png`, recovers each painted
region as a connected component, picks the cell nearest each region's centroid whose recorded
account matches what the region is a region *of*, replays that arrangement out of
`render/phase40-replays.tsv`, and draws it at critical-envelope entry with a 200-cell crop of the
marked phase map inset in the corner. **20 regions, 21 tiles** — the one green region is sampled
twice, split by whether the psyboid was actually under an override. Every replay reproduced the
account recorded for its cell.

**Hand-marking is scaffolding and is meant to be.** Finding these regions programmatically is the
actual goal; marking them by eye is how we find out what a region *is* before writing a detector,
and the same overlay is what a detector will be scored against.

> ⚠ **The overlay is bound to one rendering of the phase map, and nothing enforces that.**
> `analysis/3BoidAreasOfInterest.png` is painted over the exact pixels of a particular
> `phase40.png`; a region is a set of cells in that picture. `ThreeBoidSamples` checks that the
> overlay matches the layout's dimensions, which catches a changed resolution or route set, but
> **it cannot detect a phase map whose sampling changed while its size did not**, and the marks
> would then name different arrangements without complaining. The overlay is committed for that
> reason; the phase map it was painted on is regenerable from seed 1 at resolution 0.5.

**Open.** Whether two pairs of regions are one feature or two — `R01`/`R03` and `R04`/`R05` are
13 and 4 cells apart, above the 6-cell join threshold but close enough to be one stroke. And
`R05` (1 cell) and `R06` (4 cells) lie over **psyboid-led amber** rather than over white or cyan,
which is exactly the five stray rose-over-amber pixels in the overlay; they are more likely
overspill onto the band than deliberate marks.

### `R20`, read all the way through — the residue, not a near miss

Asked 2026-08-30: `R20` is two cells, which looks like a single-leader window missed by a
whisker. It is not. `ThreeBoidSamples.explain` prints the whole approach; the finding is that the
account is not narrowly missing, it is **split between two boids that never overlap.**

The suspect is settled to tick 13, enters the envelope at tick 45 and crosses at 47, so
admission has to cover ticks 13–45. Its entry state **is** in the tables, so the entry itself was
recognised. The third boid is not a candidate for it — 213 px out against `rFlock` 150, asking
`+0` where the entry turn is `+1` — so the only candidate is the psyboid, and admission rejected
it.

The flown history says why, once the free ticks are taken out:

| ticks | what happens | who can explain it |
| --- | --- | --- |
| 0–12 | coasting on settled ground | free |
| **13–17** | a `-1` off settled ground | **third boid only**, at 149 px against `rFlock` 150 |
| 18–40 | the veto overrides every request | free — anybody, including a boid doing nothing |
| **41–45** | the `+1` onto the envelope | **psyboid only**, closing 94 → 82 px |

**Ten of the thirty-three ticks demand a leader, five each, and the intersection is empty.** So
this is arc `4->0`'s multi-leader residue in its purest form: not a baton *pass* with an overlap
to hand over in, but two accounts that abut. The handover rule proposed above cannot rescue it,
because there is no tick at which both are true.

> ⚠ **This casts doubt on a supporting figure above, not on the conclusion.** The coverage counts
> quoted for the `2->1` and `4->0` residue ("20, 21 and 14 of 24") come from
> `SimTest.replayHistory`, which counts a tick as accounted for whenever the neighbour's
> single-neighbour steer reproduces the move — and on a vetoed or coasting tick that is true of
> every neighbour on the map. Those counts are therefore inflated, and **"the two coverages
> overlap for three ticks" needs rechecking on demanding ticks before the handover rule is built
> on it.** The multi-leader conclusion itself is safe: free ticks only ever add coverage, so a
> boid that fails to cover a window even with them counted in its favour has genuinely failed.
> Not fixed here — reported, per the rule about not replacing an existing answer in the turn that
> found it.

Also visible, and not yet chased: the prune (`pruneOutOfRangeLeaders`) marks **CUT** on one of
the two neighbours for almost the whole window, the two swapping at tick 32 as the psyboid comes
inside `rFlock` and the third boid goes out. Whether admission would have found a history for the
psyboid with the prune off is untested, and testing it needs the exact out-of-range collapse §1
lists as unbuilt.


### Stable+ — the ground a history has to reach

Specified by the user 2026-08-30, first implementation the same day, **behind an interface
because it is expected to change**: `StateSet` (the operations) and `MapStates` (the algebra bound
to a map), driven by `SimTest.stablePlus` and `SimTest.stablePlusSweep`.

**The problem it solves.** Admission terminates when the exiting boid's history reaches a
*settled* state, and settled is what a boid **alone** can hold. No boid in a scene is alone. The
flock knocks everyone slightly off it constantly, without any of that needing a psyboid or
anything unusual, so a history that merely starts a little off stable is treated as unexplained
when it should not be. Stable+ names the states ordinary multi-boid traffic reaches.

**The chain**, exactly as specified:

```java
StateSet stable = pureStable(1).partialTick(STRAIGHT).closed(STRAIGHT);
StateSet plus   = stable.expandByAgreement(pureStable(1), agreementRatio);
```

**Measured on dabeone `609cffdb84be218c`:**

| set | states | edges touched |
| --- | --- | --- |
| `pureStable(1)` | **278**, in exactly **one** straight-travel loop | 2:96 4:93 7:89 |
| `.partialTick.closed` — map-wide stable | **1,610** | 2:567 4:560 7:483 |
| `.expandByAgreement(pure, 16)` | **13,624** (8.5×) | 2:5,768 4:2,573 7:5,283 |

One loop rather than the predicted one-to-four, and its 278 ticks sit against the clock's 275.29
for loop `[4, 2, 7]` — two measurements that know nothing about each other agreeing to 1%.

**The ratio is fixed by the edges, and the boundary is sharp.** Stable+ must be bigger than
stable and must reach no new edge; an edge reachable only once jostling is allowed is a route, not
a wobble, and a route is what the solver is supposed to find remarkable.

| ratio | quorum | states | edges |
| --- | --- | --- | --- |
| 8 | 34 | 7,044 | 2, 4, 7 |
| 14 | 19 | 13,322 | 2, 4, 7 |
| **16** | **17** | **13,624** | **2, 4, 7** |
| 18 | 15 | 14,687 | **+ 0, 3, 5, 8** |
| 20 | 13 | 15,382 | + 0, 1, 3, 5, 8 |

**16 is the largest ratio that adds no edge**, and 18 leaks onto four at once. Ratios 2 and 4
expand nothing at all — no state has 69 of the 278 placements agreeing.

### It settles `R20`

At ratio 16 the suspect is on stable+ through tick 13, off it 14–24, **back on it 25–41**, and off
for the last four ticks as it is steered onto the envelope. So the window a leader must cover is
**41–45, not 13–45** — and the psyboid alone covers all five ticks, every one of them demanding.

> **`R20` is a one-leader exit once stable+ is the ground**, with the leader on **edge 2**. At
> ratio 8 it is not: the set is 4.4× stable but the path is still last on it at tick 13, so 8 was
> a false negative here and 14 is the smallest ratio tested that flips it.

⚠ **Measured on the flown history only.** Admission explores every backward pair chain, not one
witness, so turning this into a classification means giving `CriticalEnvelope.admit` stable+ as
its terminal set instead of `settled`. That has **not** been done — what is shown is that the
witness exists.

**Open on the definition itself:**

- `pureStable(n)`'s argument is read here as **how many boids are in play**, which is the reading
  that makes "all the states a boid could end up in on a map by itself" true. Unconfirmed.
- The ratio is chosen on one map and one arc. Whether 16 is a dabeone number or a general one is
  untested.
- Nothing yet applies stable+ to the other nineteen regions, or to the corpus.

### The single-quorum rewrite, and where exits appear

Respecified by the user 2026-08-30, same day. The first version tied **three** numbers to the one
ratio — the quorum to start a turn, the share that had to drop out before it could end, and the
share that had to remain for it to continue — and split states into mid-turn, which were not
closed under straight travel, and end-turn, which were. That left the result not closed, which is
both harder to reason about and harder to predict.

Now: **one quorum, `|influencers| / agreementRatio`, gating both starting and continuing; nothing
gates ending, so the turn may stop at any tick and every state along it is added.** The set is
closed under straight travel at all times, so adding is

```java
for (int s = state; s >= 0 && !out.get(s); s = straight[s]) out.set(s);
```

— add, and walk the straight successors until they meet ground already covered, which is sound
precisely because anything already present brought its own straight future with it.

**The prediction this buys, and it holds.** With one quorum and everything closed, an exit can
only enter the set if an exit window sits on the stable loop with a quorum of influencers on it.
Measured across the whole range:

| ratio | quorum | states | ×stable | edges reached |
| --- | --- | --- | --- | --- |
| 8 | 34 | 6,300 | 3.9× | 2, 4, 7 |
| 16 | 17 | 13,515 | 8.4× | 2, 4, 7 |
| 34 | 8 | 14,966 | 9.3× | 2, 4, 7 |
| 55 | 5 | 15,225 | 9.5× | 2, 4, 7 |
| 92 | 3 | 15,347 | 9.5× | 2, 4, 7 |
| 139 | **2** | 15,413 | 9.6× | 2, 4, 7 |
| 278 | **1** | 18,358 | 11.4× | **+ 0, 1, 3, 5, 8** |

**No exits at any quorum of 2 or more; exits onto five edges at quorum 1.** The user's expectation
was that quorums above 2 would be safe; the boundary is one lower still. Note the set barely grows
between quorum 5 and quorum 2 (15,225 → 15,413) and then jumps 3,000 at quorum 1, which is the
same fact seen as size.

Drawn by `StateSetRender` at `render/stable-plus-by-ratio.png` — one panel per ratio, projected to
`(x, y)`, each pixel coloured by how many of its 64 headings are in the set.

### Cost to leave, with stable+ substituted

**Correction to a premise.** The cost-to-leave the edge graph publishes is reduced over each
edge's **inbound points** — 618 on edge 4, 382 on edge 2 — not over `pureStable(1)`, which has 93
and 96. They are not the same set, and they do not always agree:

| source set | `4->0` min–max | `2->1` min–max |
| --- | --- | --- |
| inbound points — **what the edge graph publishes** | **8**–8 | **7**–8 |
| `pureStable(1)` | 8–10 | 8–16 |
| map-wide stable | 6–15 | 7–19 |
| **stable+, quorum 17 down to 2** | **5**–15 | **6**–19 |

The guess that the published figure matches `pureStable(1)` holds for `4->0` (both 8) and fails by
one for `2->1` (7 against 8).

> **Cost to leave `4->0` falls from 8 to 5, and `2->1` from 7 to 6**, once the source set is
> stable+ rather than the inbound points. Three ticks and one tick of steering that a boid left
> where ordinary traffic leaves it does not have to spend.

**The minimum is flat across the whole safe range.** 5 and 6 for every quorum from 17 down to 2 —
only quorum 34 is higher (6 and 7) and quorum 1 collapses to 0, since by then the set contains the
exit edges themselves. A number that does not move over an order of magnitude of the free
parameter is a property of the map rather than of the parameter.

`EdgeNavigation.steerCostTo` hands the per-state cost array back unreduced so this uses the
traversal `analyse` already runs rather than a second copy of it.



### The definitive stable+, and the phase map rebuilt on it

Settled by the user 2026-08-30. The definition, with the partial-tick correction applied again
after the expansion:

```java
pureStable(1).partialTick(STRAIGHT).closed(STRAIGHT)
             .expandByQuorum(pureStable(1), 5)
             .partialTick(STRAIGHT).closed(STRAIGHT)
```

**The quorum is named outright, not derived from a ratio.** What it means is *how many ticks of
influencer positions must agree* — four ticks, both ends included, so five. A ratio made that
depend on how densely the influencer set happens to sample its loop, which is a fact about how the
straight-travel cycles came out on the map rather than about flocking.

`MapStates.stablePlus(quorum)`. On dabeone at quorum 5: **19,861 states**, on edges
2:9,284 4:3,840 7:6,706 **8:31**.

> ⚠ **Two things to look at before this is called settled.**
>
> **The shape formula gives 9, not 5.** `16 / floorPow2(|pure.partialTick| / |pure|) + 1` was
> offered as the derivation. Measured: `|pure| = 278`, `|pure.partialTick| = 1,040`, so the spread
> is **3.74** — which is the four phase offsets the intent expects, but rounding *down* to a power
> of two takes 3.74 to **2**, and the formula returns 9. **Rounding to the nearest power of two
> gives 4 and hence 5.** The intent is confirmed by the measurement; the rounding direction is the
> defect. Quorum 5 is used because it was named explicitly; `MapStates.shapeQuorum` computes the
> formula and the driver prints both and says when they disagree.
>
> **The trailing partial tick reaches edge 8** — 31 states. The expansion alone stays on
> {2, 4, 7} at every quorum down to 2, which is how the parameter was chosen; the closing
> correction breaks that. Almost certainly a labelling effect at an edge boundary rather than a
> route, since a partial tick lands mid-step and the intermediate pixel can carry another edge's
> label. Not chased.

### Stable+ does not contain settled, so the ground is their union

Found while wiring this in, and it matters. On edge 4: **2,371 settled states, 3,840 stable+
states, and only 1,065 in both.** `settled` is seeded from the edge's *entrances*, which cover
arrival geometry the straight-travel loop never touches, so stable+ is not a superset of it.

**Using stable+ alone as admission's ground would therefore refuse histories the old tables
admit** — the opposite of the point. `CriticalEnvelope.analyse` now takes a ground set, and the
driver passes `settled ∪ (stable+ ∩ edge)`: **5,146 states** against 2,371.

`CriticalEnvelopeStore.FORMAT` is **2**, and the ground is fed into the cache key. A table built
against a wider ground answers a different question, and two of them under one name is exactly the
failure the FORMAT rule exists to prevent.

### The result, with the two changes separated

Both runs use the same seed, the same 3,840 starts and produce the same 491,449 exits over the
same 7,080,249 cells, so the only difference is what the tables terminate a history on.

| run | suspect starts | admission ground | unexplained |
| --- | --- | --- | --- |
| previous era | settled, 8-tick band | settled | 108,350 / 516,139 — **21.0%** |
| control | **stable+ on edge 4** | settled | 164,523 / 491,449 — **33.5%** |
| **on stable+ throughout** | **stable+ on edge 4** | **settled ∪ stable+** | **14,923 / 491,449 — 3.0%** |

**Widening the starts alone makes it worse**, from 21.0% to 33.5% — which is what the user
predicted when asking for it: a suspect started deep into the edge can already be past where some
windows act. **Widening the ground then takes 33.5% to 3.0%**, an eleven-fold cut, and more than
pays for the harder population.

`render/phase40-stableplus.png` and `-control.png`, with `-replays.tsv` beside each.
`SimTest.phaseMapOnStablePlus` runs both.

**Open.** The 3.0% has not been characterised — nobody has looked at what the remaining 14,923
are, and the twenty hand-marked regions have not been re-read against the new map.


### The white census of the centre panel, found rather than painted

Asked 2026-08-30. Confined to the centre panel `[4,2,1,5,8] x [4,2,1,5,8]`, which carries the
cross of the centre route and most of the structure. **3,777 unexplained cells in 20 clumps of 40
or more**, plus 102 smaller ones holding 385 cells between them. `ThreeBoidSamples.features`
finds them, `bands` groups them, `windowFit` tests a claimed size, `atlas` crops each in place and
`sampleFeatures` replays one arrangement per clump.

**Three families, and the geometry predicts the mechanism.**

**Slabs on the third-boid band** — horizontal, wider than tall, dense, lying on or against the
cyan band. Best 38x12 window: **W12 52%, W06 50%, W13 42%, W11 33%, W08 29%, W09 29%.** This is
the family the 38x12 estimate names, and 38x12 is a good description of it.

**Columns on the psyboid band** — the same thing transposed: vertical, riding the edge of the
amber band. Best 12x38 window: **W17 60%, W16 34%, W03 33%, W19 28%, W12 28%, W15 25%, W20 25%,
W02 20%.**

**Diffuse clouds** — no dense core in either orientation, sitting in open ground or in the corner
where two bands meet: W01, W04, W05, W07, W10, W14. Fill 7–18% over the whole box.

**They repeat on a lattice, which is the structure worth detecting.** Clumps sharing a range on
one axis and scattered along the other:

| axis | range | appears at |
| --- | --- | --- |
| psyboid phase | x 580..624 | y 151, 314, 377 — W06, W09, W12 |
| third-boid phase | y 373..396 | x 99, 410, 582 — W13, W11, W12 |
| third-boid phase | y 581..624 | x 72, 311, 369 — W18, W19, W17 |
| psyboid phase | x 370..392 | y 434, 581 — W15, W17 |
| psyboid phase | x 71..89 | y 96, 582 — W02, W18 |

`W12` sits at the crossing of the first two and is the densest clump on the panel, which is what a
lattice of two independent phase conditions should produce.

### None of them is a near miss

`ThreeBoidSamples.classify` replays one arrangement per clump and asks, over the window from the
last tick on stable+ to the envelope entry, which ticks **demand** a leader at all and who
accounts for them.

| verdict | meaning | count |
| --- | --- | --- |
| single | one boid explains every demanding tick — a modified physics could catch it | **0** |
| split | the two between them explain all of them, neither alone | **9** |
| UNCOVERED | some demanding tick neither neighbour alone reproduces | **11** |

**Not one of the twenty is a one-leader exit at any constants.** Nine need a leader handover,
which is pairwise per tick but not per history; eleven need genuine superposition, which
`HINTS.md` §10a says no pairwise table represents at any constants.

**And the split falls along the families.** All six diffuse clouds are UNCOVERED. The structured
slabs and columns are mostly split — 9 of 14 — with 5 uncovered. `W07`, the largest diffuse cloud,
is the extreme case: of its 5 demanding ticks, **neither neighbour alone reproduces a single
one.**

> So the answer to whether these are extensions of existing features is **no**. A carefully chosen
> modified physics would not catch them, because there is no single leader to widen a window
> around. The nine splits are the case the leader-handover rule in §1 was proposed for; the eleven
> uncovered ones need a genuinely multi-boid influence model.

⚠ **One representative per clump.** These are samples, not censuses — a clump of 200 cells is
being characterised by one of them. The verdicts are a strong hint about what each family is, not
a measurement of every cell in it.

---


## 0a. Survey — how the neighbour signals are aggregated, and what else is sensible

Asked 2026-08-30. A survey, not an optimisation: `Aggregation` holds seven ways of condensing
several neighbours into one desired direction, `AggregationSurvey` scores them all on the same
sampled arrangements, and `ThreeBoidPhase.compare` cross-tabulates the phase maps they fly.
**Nothing here has been adopted; `MovementLogic`'s default path is untouched and
`Aggregation.CURRENT` is checked against it on every run** — 0 turn disagreements over 20,000
arrangements, worst vector gap `7.1e-14`.

### What the simulation does, stated exactly

Per rule: sum the neighbour contributions, then `w * v / |v|`. Three details are worth having
written down because none is obvious from the formula:

- **Cohesion sums raw offsets, not unit vectors.** Before normalisation a neighbour 140 px away
  counts fourteen times one at 10 px; after it, only the direction survives, which is the
  direction of the centroid.
- **Separation's distance falloff is erased whenever exactly one neighbour is close**, because
  normalising a single vector discards its length. **The falloff only ever shapes a direction,
  never a magnitude.**
- **There is no magnitude bound anywhere downstream**, and no inertia term. The renormalised
  direction goes straight into the turn choice.

### The defect, measured on real map geometry

The length of a rule's sum is exactly the measure of how much the neighbours agree, and
normalising throws it away. Over 399,321 arrangements sampled from live navmap states:

- **alignment cancellation** (sum of lengths / length of sum) — median **1.41x**, p90 **10.2x**,
  and **10.0% of arrangements above 10x**. In a tenth of cases the surviving direction is a
  residue under a tenth of the length that went in, restored to the full `W_ALI` of 70.
- the **pseudo-triangle rule** holds for the current scheme in only **64.5%** of two-neighbour
  arrangements at `k = 1` and **66.7%** at `k = 0.5`.
- **amplification** `|signal(A,B)| / max(|signal(A)|, |signal(B)|)` reaches **4.14 at p99**: the
  pair asks for something four times more decisive than either neighbour asked for alone.

### The seven variants

| variant | tri k=1 | tri k=.5 | amp p99 | jolt p99 | 1px flip | match 2 | match 3 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `CURRENT` | 64.5% | 66.7% | 4.14 | 3.85 | 0.78% | — | — |
| `RULE_MEAN` | 93.6% | 64.7% | 1.51 | 2.32 | 0.56% | 70.2% | 64.9% |
| `RULE_SUM_CLAMP` | 78.3% | 69.8% | 2.00 | 2.46 | 0.56% | 79.9% | 84.9% |
| **`RULE_CLAMP_STEP`** | 75.8% | 71.3% | 2.31 | 3.30 | 0.80% | **86.4%** | **92.7%** |
| **`VOTE_MEAN`** | **100.0%** | 65.2% | **0.99** | **1.61** | **0.54%** | 72.1% | 63.9% |
| `VOTE_CLAMP` | 50.7% | **93.9%** | 1.97 | 2.94 | 0.69% | 74.7% | 74.1% |
| `VOTE_NORM` | 74.6% | 66.4% | 2.49 | **4.40** | 0.70% | 70.6% | 70.2% |

*jolt* is how far the signal moves for a one-pixel nudge of one neighbour, in units of the
straight bias — the only scale on which a signal change means anything, since that is what a turn
has to beat. *match* is agreement with today's turn on two- and three-neighbour arrangements.

**`VOTE_MEAN` satisfies the pseudo-triangle rule exactly, and provably.** Averaging bounded
per-neighbour votes is a convex combination and the across-heading component is a linear
functional, so the result cannot leave the interval. It is also the calmest by a factor of 2.4
against today, and **strictly cheaper** — it does no square roots at all where the current scheme
does three per decision.

**`VOTE_NORM` is the control and it settles the ordering question.** Summing the votes and
normalising the total is *worse* than today (jolt 4.40 against 3.85). **Moving the normalisation
does not help; normalising at all is the defect.** Reordering operations is not the lever.

**`VOTE_CLAMP` fails on its own terms.** It sums rather than averages, so it is *more* decisive
than today, and it is the only variant that makes things worse everywhere it is measured.

**Why `RULE_MEAN` is not also exact.** Each rule averages over its own contributors — separation
over the close ones, cohesion and alignment over all — so the total is a weighted sum of three
convex combinations with different denominators, which is not itself a convex combination of the
single-neighbour results. Averaging inside the rules is not the same as averaging across the
neighbours, and only the second gives the guarantee.

### Complexity: same or lower, in every case

Every variant is one pass over the neighbours, O(n), no extra state, no second pass. The `VOTE_*`
family removes the three per-rule square roots and `RULE_MEAN` removes them too. Nothing here adds
a layer.

### What other implementations do

Neither of the two most-copied references normalises each rule's sum to a fixed magnitude with
nothing downstream to bound it.

- **Conrad Parker's pseudocode** — cohesion is the *mean* neighbour position, alignment the *mean*
  neighbour velocity, separation a raw sum; no per-rule normalisation, and a single optional
  velocity clamp applied once after all three are combined.
- **The Nature of Code** — sum, then `div(count)`, then `setMag(maxspeed)`, then
  `steer = desired - velocity`, then **`limit(maxforce)`**.

Both average by neighbour count, and both bound magnitude **once, at the end, on the total**. The
Nature of Code's `steer = desired - velocity` followed by a force limit is a low-pass filter: the
boid's existing velocity dominates and the amplified part is clamped away. **This project has no
analogue of either.** Its discrete-turn formulation has no velocity to subtract, and the straight
bias is hysteresis rather than a magnitude bound, so an amplified direction passes through
undamped. That, rather than the three rules, is what makes this simulation unusual.

### What it does to the phase map

Arc `4->0` on stable+, same seed and starts, tables shared — **three of the variants agree with
today exactly for a single neighbour**, so `EdgeInfluence.steer`, the critical envelope, the
windows and the tables are all unchanged and only the trajectories move.

| variant | unexplained rate | white to accounted | white to no exit | account moved | centre-panel clumps |
| --- | --- | --- | --- | --- | --- |
| `CURRENT` | 3.04% of 491,449 | — | — | — | 20 (3,777 cells) |
| `RULE_CLAMP_STEP` | **2.15%** of 481,358 | 2.2% | 33.5% | 0.09% | 17 (2,712) |
| `VOTE_MEAN` | **1.37%** of 459,198 | 2.1% | 57.6% | 0.12% | **9 (1,780)** |
| `VOTE_CLAMP` | 3.06% of 535,899 | 3.3% | 36.3% | 0.32% | 25 (4,585) |

> **The white does not become explained. It stops being an exit.** Only 2 to 3% of unaccounted
> cells acquire an account under any variant; a third to a half stop producing an exit at all.
> That is consistent with those exits having been artefacts of the amplification — take the
> amplification away and the boid does not turn — but it is a different claim from "the account
> got better", and the two must not be reported as one number.

**Leader attribution is stable to about a tenth of a percent.** Amber essentially never becomes
cyan: 216 of 476,520 accounted cells change which boid led under `RULE_CLAMP_STEP`, 387 under
`VOTE_MEAN`. What moves is whether an exit happens, not who caused it — so the criterion of
keeping the accounted regions in place is met by all of them, and `render/agg-centre-panels.png`
shows it.

### The initial hypothesis, tested and confirmed

Do the unaccounted exits involve abnormally high normalisation? Taking the arrangement at envelope
entry for 8,324 unaccounted and 1,977 accounted exits from the same run:

| class | alignment cancel p50 | p90 | above 4x | cohesion p50 | invention factor |
| --- | --- | --- | --- | --- | --- |
| unaccounted | **1.49** | **5.13** | **16.6%** | 1.38 | **1.88** |
| accounted | 1.11 | 2.12 | 3.6% | 1.16 | 1.21 |

*Invention factor* is the length of today's desired direction over the length of the average of
what the neighbours individually asked for. **At the median an unaccounted exit's signal is 1.88x
longer than anything its neighbours asked for; an accounted one is 1.21x.** Heavy cancellation is
**4.6x** enriched among the unaccounted. The hypothesis holds.

### What adopting one would cost

A change here is a change to `Params.PHYSICS`, not a `Flocking` argument: it changes what the
boids do. Every ingest, the psyboid corpus, every plan label and every stored table taken under
physics 2 would stop meaning what it means. That is the reason to survey before choosing, and the
reason nothing has been chosen.

**If the criterion is least disturbance for a real improvement, `RULE_CLAMP_STEP` is the
candidate** — identical for one neighbour, identical whenever the neighbours agree, 92.7%
agreement on three-boid turns, and a 29% cut in the unaccounted rate. **If the criterion is the
pseudo-triangle rule itself, only `VOTE_MEAN` satisfies it**, and it is cheaper and calmer, at the
cost of agreeing with today on 64% of three-boid turns.

### Not surveyed

Only arc `4->0` on dabeone, and only the aggregation. The straight bias is the other half of the
decision and is untouched here — under a bounded aggregation the signal is smaller, so the bias is
effectively stronger, and the two would want tuning together rather than one at a time.

---


## 0b. Proposed physics 3 — `RULE_SUM_CLAMP`, run end to end

Chosen by the user 2026-08-30 from the survey above: **unit cohesion, falloff separation, and each
rule's sum capped at magnitude 1 rather than rescaled to it.** Two close neighbours at 1/3 and 2/3
falloff pulling the same way sum to exactly 1 and max out separation, which is the behaviour
asked for. `Aggregation.RULE_SUM_CLAMP`, driven end to end by `SimTest.proposedPhysics`.

**Not adopted. `Params.PHYSICS` is still 2 and `Flocking.of` still returns `sepFalloff = false`.**
This section is the evidence for the decision, not the decision.

### Why the falloff is worth restoring, beyond taste

Under physics 2 a lone neighbour's influence is `(W_COH - [d < rSep] W_SEP) u + W_ALI a`, and the
`u` coefficient **jumps from -90 to +30 as the neighbour crosses `rSep`** — a discontinuity of 120
at a radius nothing else marks. The falloff exists in `MovementLogic` to smooth exactly that, and
the normalisation cancels it: normalising a single vector discards its length, so the falloff has
never once set a magnitude.

Restore it and the handover is continuous. Separation ramps from `W_SEP` at `d = 0` to zero at
`d = rSep`, so the `u` coefficient runs smoothly from -90 up through zero at
`d = 0.75 rSep` to +30, and nothing happens at the radius itself. **The proposal removes a
discontinuity rather than adding a parameter**, which is also why it is closer to what other
implementations do.

The cost is that the response to a lone neighbour in the outer separation annulus **reverses
sign**: at `d = 49` against `rSep = 50` a boid used to flee at -90 and now approaches at +27.6.
That is a real behavioural change and it is where most of what follows comes from.

### What does not change, checked rather than assumed

The navmap, the edge decomposition, the clock, `pureStable(1)` (278), map-wide stable (1,610), the
critical envelope itself (84 states) and cost-to-leave. **None of them consults the flocking
constants** — they are properties of the map and of straight travel alone.

### What does change

`EdgeInfluence.steer` is the aggregation restricted to one neighbour, so it has to move with it.
The coupling runs through `Flocking.sepFalloff`, set from `Aggregation.separationFalloffAtOne()`
and never by hand, and **`AggregationSurvey.checkClosedForm` asserts the two agree** — 0
disagreements over 4,000 arrangements each for `CURRENT` and `RULE_SUM_CLAMP`, and the pipeline
refuses to build a table if they ever differ. The separation profile is fed into
`CriticalEnvelopeStore`'s cache key for the same reason.

> `VOTE_NORM` **fails that check by construction** — it renormalises the total, so its
> one-neighbour behaviour is not `EdgeInfluence.steer` at any setting. That disqualifies it from
> adoption without rewriting the whole critical-envelope analysis, and is worth knowing about any
> future candidate.

| stage | physics 2 | proposed |
| --- | --- | --- |
| stable+ q5 | 19,861 states, edges 2/4/7/8 | **20,008**, **the same edges** |
| envelope | 84 states | 84 states |
| pairings | 598,253 | 612,776 |
| exits on the phase map | 491,449 | 518,535 (+5.5%) |
| unexplained | 14,923 — **3.04%** | 9,840 — **1.90%** |
| centre-panel clumps | 20 (3,777 cells) | **16 (2,694 cells)** |
| diluted model needed | 6 | **479** |

### Is it "similar end to end"? Yes structurally, no cell by cell

Cell by cell the churn looks alarming — **38.8% of accounted cells move**, almost all of it
accounted becoming *no longer an exit* while a comparable number of new exits appear. Leader
identity is much steadier: only about 1.1% of accounted cells swap psyboid-led for third-led.

But **a cell is a single draw and never a majority**, so a dithered band whose density moves from
70% to 75% looks identical and churns a third of its cells. Asked of neighbourhoods instead:

| block | same dominant class | drift in exit rate | drift in white |
| --- | --- | --- | --- |
| 4x4 | 94.77% | 2.49 pp | 0.15 pp |
| 8x8 | 94.66% | 1.73 pp | 0.12 pp |
| 16x16 | 94.72% | 1.30 pp | 0.10 pp |

**Stable at 94.7% across every scale tested**, so it is not an artefact of the window size. The
bands are where they were, at the widths they were; `render/prop-centre-panels.png` shows it.

**And 15.3% of the previously unaccounted cells are now accounted**, against 2–3% for the
single-neighbour-identical variants surveyed earlier — because this time the tables moved too, so
the analysis can see influences it previously could not.

### Reading the 38.8% correctly

The survey measured **84.9% agreement on individual three-boid turns**. A trajectory is dozens of
decisions, so 85% per tick compounds to roughly 61% agreement on whether an exit happens at all.
Per-tick agreement is not trajectory agreement and should not be quoted as if it were.


### Arc `2->1` under the same proposal

Run 2026-09-04. `5->6` is deliberately not run: it occurs only in the first 500 ticks and then
never again across 1.28M boid-ticks, so its behaviour under a physics change is not information
anybody will use.

**This comparison is cleaner than `4->0`'s.** Stable+ restricted to edge 2 is **unchanged** at
9,284 states, so both runs used the same 9,284 suspect starts and the sampler took the identical
path — 2,576,562 cells from 1,599,436 simulations out of 26,794,174 attempts, in both. Nothing
varies except the physics and the tables it moved.

| | physics 2 | proposed |
| --- | --- | --- |
| envelope | 97 states | 97 states |
| pairings | 596,805 | 580,789 (**down** 2.7%) |
| exits | 62,980 | **79,293 (+25.9%)** |
| led by the psyboid | 51,239 | 68,071 (+32.8%) |
| led by the third boid | 11,496 | 11,039 (−4.0%) |
| diluted model needed | 28 | 33 |
| unexplained | 245 — **0.39%** | 183 — **0.23%** |
| clumps of 40+, any panel | **0** | **0** |

**The classification barely moves: 4.49% of accounted cells changed, none of them to
unexplained**, and only 125 of 62,707 swapped which boid led — 0.20%. At block scale 96.6% to
98.0% keep their dominant class with an exit-rate drift of 0.8 pp and a white drift of 0.01 pp.
`render/prop21-panels.png` shows two pictures that are hard to tell apart.

**But the arc is taken a quarter more often**, which is a larger relative change than `4->0`'s
+5.5%, and essentially all of it is the psyboid becoming more effective at inducing the exit
(+32.8%) while the third boid becomes slightly less so (−4.0%). That is the sign reversal in the
outer separation annulus showing up as behaviour: a psyboid sitting just inside `rSep` used to
push the suspect away and now draws it in.

**And `2->1` has no white structure to fix.** 245 unexplained cells across all four panels and
**not one clump of 40 or more**, before or after. The banded white regions are a `4->0`
phenomenon, which is consistent with §1: `2->1` is separation-carried and one boid holds a whole
history, while `4->0` is alignment-carried and the leadership shifts.

### The two arcs side by side

| | `4->0` | `2->1` |
| --- | --- | --- |
| exits | +5.5% | **+25.9%** |
| unexplained rate | 3.04% → **1.90%** | 0.39% → **0.23%** |
| accounted cells moved | 38.8% | **4.5%** |
| blocks keeping dominant class | 94.7% | **96.6–98.0%** |
| leader reattributed | 1.1% | 0.20% |
| diluted model needed | **6 → 479** | 28 → 33 |
| white clumps of 40+ | 20 → 16 | 0 → 0 |

**The 80x jump in diluted-model usage is specific to `4->0`.** On `2->1` it barely moves. Whatever
it is, it is not a general consequence of restoring the falloff, and it remains the one result
here nobody has explained.

### Before pulling the trigger

- **`Params.PHYSICS` -> 3**, which re-hashes every ingest. Every stored table, the psyboid corpus
  and every plan label taken under physics 2 stops meaning what it means and has to be rebuilt.
- **The 80x jump in the diluted model on `4->0`** (6 -> 479) is unexplained, and confirmed
  arc-specific by `2->1`, where it goes 28 -> 33. `Flocking.diluted` doubles `wSep` and now
  carries the falloff too, so the fallback changed shape as well as the base model. **This is the
  one result nobody has explained and the cheapest thing to look at next.**
- **The arcs are run.** `4->0` and `2->1` both done, on dabeone. `5->6` deliberately not:
  it occurs only in the first 500 ticks and then never again over 1.28M boid-ticks, so its
  behaviour under a physics change is not information anybody will use.
- **Exit frequency moves more than classification does, and by more on `2->1` (+25.9%) than on
  `4->0` (+5.5%).** Almost all of it is the psyboid becoming better at inducing the exit. Whether
  a psyboid with more leverage is wanted is a judgement about the evaluation, not a measurement —
  it means more signal per scene, and also a flock that is easier to herd.
- The straight bias is untouched, and under a clamped aggregation the signal is smaller, so the
  bias is effectively stronger. The two want tuning together.
- Only dabeone. Plait has not been looked at.

---


## 0c. Physics 3 and the addressing rework — **shipped**

Asked 2026-09-04: ship `RULE_SUM_CLAMP` project-wide, audit for reproduced steering logic, and
clean up old artifacts. Preparing it turned up a defect that made the ship unsafe, and the
cleanup question turned out to be the same question. Both are now fixed and shipped — see the end
of this section.

### The audit: one reproduction, deliberate, now guarded

Searched every use of the weights, the field of view and the perception test.

- **`TwoBoid` is clean.** It drives the real `MovementLogic` on a two-element array and says so:
  *"The real MovementLogic rather than a copy of its arithmetic ... a reimplementation here would
  be a second definition of flocking that has to be kept in step with the first."* Its
  out-of-range fast path agrees with `perceived` by construction. It will pick up new physics with
  no change.
- **`CriticalEnvelope` reproduces nothing itself.** It reasons entirely through
  `EdgeInfluence.steer`.
- **`EdgeInfluence.steer` is the one reproduction**, and it is deliberate: the single-neighbour
  closed form the whole envelope analysis is built on. It is now coupled to the aggregation
  through `Flocking.sepFalloff` and **asserted equivalent at one neighbour** by
  `AggregationSurvey.checkClosedForm`, which the pipeline refuses to proceed past.
- **One accidental copy, mine, from the survey.** `AggregationSurvey.see` had its own
  range-and-FOV test rather than calling `MovementLogic.perceived`. Fixed; the numbers are
  unchanged, which is what a faithful copy should do and is not a reason to have kept it.

### The defect: `Params.PHYSICS` does not separate ingests

`GLOSSARY.md` says of the physics version: *"Part of the ingest hash, so a physics change produces
a different ingest rather than silently reinterpreting an old one."* **That is false.**
`MapStore.open` computes the folder name as `digest(img, shown)` — the pixels of the source and
display images and nothing else. `Build.key()`, which does include `p<PHYSICS>`, is only the
in-process cache key. `MapStore.Build`'s own javadoc makes the same claim and is right about
radius and trap-trimming, which do change `shown`, and wrong about `PHYSICS`, which changes
neither image.

**So bumping `PHYSICS` to 3 would have written physics-3 artifacts into
`ingests/609cffdb84be218c/` beside the physics-2 ones.** What that would have done, store by
store:

| store | keyed on | under a physics bump |
| --- | --- | --- |
| `EdgeMetricStore` | inputs + `FORMAT`, hashed into the filename | correct — the clock is navigation-only and genuinely unchanged |
| `CriticalEnvelopeStore` | inputs + ground + `sepFalloff` + `FORMAT`, hashed into the filename | correct, but **by luck**: `sepFalloff` was added to the key yesterday for a different reason |
| `SolverStore` | `FORMAT` only, fixed path `solver/facts.bin` | **silently overwritten and silently reread** |
| `PsyboidCorpus` | nothing, fixed path `psyboid/plans.tsv` | **silently reread.** Rows are verified by replay when *written*, never when read |

The corpus is the ground truth the solver is graded against, so that last row is the serious one.

> **The systemic version of the bug:** this project consistently records the discriminating input
> in an artifact's **content** and not in its **address**. `meta.txt` records the physics version.
> The edge graph records the gate in its title. `plans.tsv` records its configuration in a header.
> None of them is in a path, so none of them can stop a reader picking up the wrong file. The two
> stores that hash their inputs into the filename are the two that are safe, and they are safe for
> exactly that reason.

**The gate has the same problem.** `edges/` is a fixed path under the ingest, so decomposing with a
different gate overwrites the previous decomposition in place — and the gate is a human choice
that changes every edge number downstream.

### Artifact storage: the design, and what was built

The principle every content-addressed build system converges on is that **an artifact's address is
a hash of its entire input closure, including the addresses of its inputs.** Two of the four stores
here already do that. The recommendation is to make the other two match, and to make the tiers
explicit in the path so a human can see what belongs to what.

The dependency graph has three tiers and they are cleanly separated:

| tier | depends on | holds |
| --- | --- | --- |
| **map** | pixels, radius, navigability, trap-trimming | `map.png`, `display.png`, `meta.txt` |
| **structure** | map + gate + weighting scheme | navmap, edge decomposition, the clock, cost-to-leave, `pureStable`, map-wide stable |
| **behaviour** | structure + `PHYSICS` + flocking constants | envelope tables, windows, two-boid reachability, psyboid corpus, solver facts, audits, phase maps |

Nothing in the structure tier reads a flocking constant — verified while running the proposal, and
it is why `pureStable(1)`, map-wide stable, the envelope and cost-to-leave came out identical.

```
ingests/<mapHash>/
  map.png  display.png  meta.txt
  structure/<structureHash>/   edges/  metric/  navmap/     meta.txt
  behaviour/<behaviourHash>/   envelope/  windows/  twoboid/  psyboid/  solver/  audit/   meta.txt
```

**What it buys.** A physics bump rebuilds only the behaviour tier — the clock's thousands of
gradient steps are kept, which is the expensive thing. Cleanup becomes "delete the behaviour
directories you no longer want", which is safe because it is obvious what each one is. Nothing can
be read against inputs it was not built from. And a second gate stops silently destroying the
first decomposition.

**What it costs.** Every store's path construction changes, paths get longer, and "everything
about this map in one folder" is lost. It is perhaps half a day.

**Two cheaper options, for completeness.**

1. **Minimal, unblocks today:** add `PHYSICS` to `MapStore`'s digest, so the folder name matches
   what the docs already claim. One line, plus committing the new frozen `map.png` — byte-identical
   pixels under a new hash. Old artifacts stay under the old hash and are self-evidently physics 2,
   so *the cleanup becomes nothing*. The cost is rebuilding the structure tier, clock included,
   for a physics change that does not touch it.
2. **Flat but addressed:** keep one directory and give every artifact an input-hash filename, the
   way `envelope/` and `metric/` already do. Least disruptive, but cleanup gets harder because
   nothing in a filename tells a human which physics it belongs to.

**What was built is the tiered split, with two tiers rather than three.** The clock was going to
need its own level to avoid being over-keyed on the physics version; instead it sits in the
structure tier, which is keyed on the gate and the weighting scheme it genuinely depends on, and
the behaviour tier nests inside it. That costs one deliberate over-keying — the envelope does not
depend on the weighting scheme — and buys a layout that fits in the head. The minimal option was
not needed: the map hash never had to move, because the map never changed.

### Shipped, 2026-09-04

Both halves landed together, because either alone is unsafe: physics 3 without the addressing
would have written into physics 2's paths, and the addressing without the physics would have been
a refactor nobody could check.

**The physics.** `Params.PHYSICS` is 3. `Aggregation.SIMULATION` is the single place that says
what the flock flies; `MovementLogic` defaults to it and `Flocking.of` takes its separation
profile from it, so the decision rules and the single-neighbour closed form **cannot be set to
disagree** by editing one and forgetting the other. `CURRENT` is renamed `RULE_NORMALISE` — it
stopped being current — and remains the record of physics 2 and the baseline every survey number
in §0a is quoted against.

> **`MovementLogic` no longer holds its own copy of the rules.** The physics-2 aggregation sits
> beside the others and is reached the same way, so the survey and the simulation run the same
> code down to the last branch. The fidelity check's worst vector gap went from `7.1e-14` to
> **exactly zero** — the residue had been two transcriptions of one formula, and now there is one.

**The addressing.** `Derived` gives two tiers under the ingest, each writing a `meta.txt` naming
its own inputs. `MapStore.output` and `outputDir` are no longer reachable for derived output, so
all thirty-three call sites had to name a tier — which is the point: the compiler, not a
convention, is what stops the next artifact landing at a fixed path.

**Verified end to end.** The map hash is unchanged at `609cffdb84be218c` and `map.png` and
`display.png` came back **byte-identical** after re-ingesting from `areas/` — only `meta.txt`
moved, because it carries the physics stamp. The map did not change, and now the layout says so.
The clock's filename hash is likewise unchanged, confirming it is physics-independent rather than
merely believed to be.

**The audit, for the record.** `TwoBoid` drives the real `MovementLogic` on a two-element array.
`CriticalEnvelope` reasons entirely through `EdgeInfluence.steer`, which is the one deliberate
reproduction and is asserted equivalent at one neighbour on every run. The only accidental copy
was in `AggregationSurvey`, and it is gone.

---

## 0d. Corpus addressing — **built**

Raised 2026-09-04: corpora have been under-labelled, and it is worth asking whether they should
use the same addressing as everything else. **They should, one level deeper, and the same argument
applies.**

A corpus is a set of plans; a plan is a seed plus a list of overrides. Now that it lives in the
behaviour tier it is already addressed by the map, the gate, the weighting scheme, the physics
version, the flocking constants and the aggregation. **What is still only in its content is the
recipe**: how many seeds, the warm-up, the run length, the branch policy, which arcs it targets.
Two corpora differing in any of those collide at `psyboid/plans.tsv`, exactly as facts did.

**The proposal.**

```
behaviour/<behaviour>/psyboid/<recipe>/plans.tsv
                                       meta.txt
```

where `<recipe>` hashes a **corpus preset** — the user's own suggestion, and the right shape.
`PresetScenarioParameter` names maps; an enum in the same spirit names corpus recipes, so that a
corpus is generated by naming one rather than by passing six numbers that nothing records
together:

```java
public enum CorpusPreset {
    PLANS_40(/* seeds */ 40, /* warm */ PsyboidBits.WARM, /* run */ 3000, ...),
    ...
}
```

`Derived.corpus(behaviour, preset)` then addresses it and writes the `meta.txt`, exactly as the
other two tiers do.

**What a row should carry, beyond the label.** The label is the artifact and must stay
replay-exact, so this is additional columns rather than a change to it. Worth having, in rough
order of usefulness:

- **which arcs the plan actually exercises**, and how many exits of each — today the only way to
  know is to fly it;
- **score and control**, already computed during the build and then thrown away for all but a
  summary line;
- **the warm-up state's fingerprint**, so a corpus flown under a different warm-up is detectable
  rather than merely differently addressed;
- **whether the plan needed the diluted model**, which is the cheapest signal of a crowd effect.

**Built 2026-09-05**, and generated through a preset from the start rather than migrated into one.
`CorpusPreset` holds the recipes, `Derived.Corpus` addresses them, and
`PsyboidCorpus.labels(Derived.Behaviour)` **refuses when more than one corpus exists** rather than
picking — naming a preset is then a fix the caller has to make deliberately.

All four extra columns landed: `occFlock`, `occPsy`, `occOthers`, `occControl` and `impactful`.
The warm-up fingerprint did not, because the warm-up question turned out to want measuring rather
than recording — see `CORPUS.md`.

> **The psyboid/others split earned itself on its first run.** On `PLANS_40` under physics 3 the
> psyboid scores at 0.1125 occupancy against 0.0400 for the boids it is herding — 2.8x — while the
> control is exactly 0.0000, so every point the others score is psyboid-caused. **Two of forty
> plans move nobody at all**, scoring purely by parking. Total score cannot see any of that.

Full treatment, and the four figures, in `CORPUS.md`.

## 0e. The corpus scoring floor — **confirmed**

**Started and finished 2026-09-05.** A corpus that scores well should score *predictably*: much
the same rate from seed to seed, and where it is lowest it should bottom out at something with a
name rather than trailing off. The claim under test, the user's:

> the scoring rate has a clearly defined lower bound, and that bound is the score per tick of a
> **single psyboid simulation** — one boid alone on the map, with nothing to herd.

The reasoning: a psyboid in a flock can always fall back on flying the scoring loop itself, so a
plan can be worse at herding but not worse than a lone boid. The floor is then what the psyboid
earns on its own account, and the spread above it is herding.

**Built: `SimTest.scoringFloor`**, with `SimTest.scoringLaps` for one seed at a time, writing
`floor.tsv` beside `plans.tsv`. `PsyboidBits.search` now takes a `ScenarioParameter` rather than a
preset, which is the whole change needed to fly the same search on a flock of one.

**The claim holds, and the bound is exact rather than approximate.** A solo psyboid on dabeone
scores **54 points every 533 ticks — 0.101313 per tick — with a standard deviation of zero across
all 40 seeds.** Unsteered alone it scores nothing at all. In a flock the psyboid runs at 0.998x
that, 0.980x at worst, so **the flock costs a psyboid under 2% of its own scoring**; the two
parked plans sit on the floor and everything above it is herding. Full treatment in `CORPUS.md`.

> ⚠ **The 5% seed-to-seed spread in the corpus figures is the measuring window, not the flock.**
> The usable window is 2,739 ticks against a 533-tick lap — 5.14 laps — so it catches five passes
> or six depending on where its edges fall. Measured that way the *solo* psyboid, whose behaviour
> is identical in every seed, reads 0.11350 with a 4.6% cv. **Every occupancy figure recorded for
> `PLANS_40` is windowed and about 12% high as an asymptotic rate**, which is the right number for
> what a case is drawn from and the wrong one to quote as a rate. Whether the *others*' 0.0400
> carries the same bias is untested.

**Not chased, and worth knowing.** The comparison is exactly paired — `Boids2DEngine.init` draws
boid 0 first and boid 0 is the psyboid, so a seed at flock size one starts the psyboid where the
same seed with the flock does. That makes flock size a controlled variable for anything else worth
asking about a plan, and nothing else uses it yet.

## 0f. Edge-occupancy decay — **measured. `WARM` not changed; the two criteria disagree**

**Started 2026-09-05.** `CORPUS.md` has flagged `PsyboidBits.WARM = 5,000` since it was written:
it was chosen on a *settling* criterion, which the minimality argument says is the wrong question,
and total edge length on dabeone is 940. The user's suspicion is **500 to 1,000**, and
edge-occupancy decay is the proxy the doc already names as the one to measure.

**The measurement.** Seeds flown with no psyboid; mean edge occupancy across seeds in 50-tick
windows through tick 2,000, squared distance to the long-run occupancy, against the standard
deviation of a single seed's window occupancy in the long-run regime. The crossing points at
1.0σ, 0.5σ and 0.1σ are the candidate warm-ups.

**And two spawn rules that may make the question go away**, both flown on the same seeds:

- **`STABLE_PLUS`** — spawn uniformly from the stable+ set.
- **`TAU_UNIFORM`** — per boid, draw `(edge, tau)` uniformly by tau along the stable edges, then
  take a stable+ state on that edge or one adjacent to it whose tau, rebased into the chosen
  edge's frame, matches.

If a spawn rule starts the flock already at the long-run occupancy, the warm-up is there to
correct a defect in the spawn rather than to model anything.

**Built: `EdgeOccupancy`**, driven from `SimTest.main`, writing
`<behaviour>/occupancy/decay-<rule>-<seeds>s<window>w.tsv`. 2,000 seeds, ~25 s a rule. Full
treatment and every figure in `CORPUS.md`.

**The answer to the question as asked: 500.** The uniform spawn's bias is at its permanent
plateau by tick 500 — envelope `0.430, 0.185, 0.120, 0.111` over the first four 250-tick blocks,
then flat to tick 20,000. **`WARM = 5,000` buys a 6% reduction in a residual that is not going
anywhere.** The user's 500–1,000 was right.

**And a structural finding that reframes what a warm-up can do at all.** A boid advances one step
per tick along a loop of fixed length, so its position at tick `t` is its spawn position plus `t`:
**the flock's distribution over phase is conserved, not mixed.** The mean occupancy at a given
tick is therefore periodic rather than convergent — autocorrelation **0.99 at a lag of two stable
cycles, still holding after 73 laps** — and a warm-up, being a time shift, cannot flatten a
periodic function. Warm-up fixes only the non-phase part of the spawn: getting boids off the six
edges a warm flock never occupies.

**The spawn rules.** `TAU_UNIFORM` plateaus at **0.0793**, below `UNIFORM`'s 0.1098 **from tick
0** — a better spawn beats any warm-up. `STABLE_PLUS` plateaus at **0.1445**, *worse* than the
rule it replaces, because stable+ is a set rather than a measure: `0.464 / 0.194 / 0.341` over the
stable edges against a long-run `0.356 / 0.328 / 0.316`, and phase conservation makes that
permanent.

### The competing criterion, re-measured — and it does not terminate

**Corrected 2026-09-06, and it corrects this section's own first draft.** The user observed that
non-scoring is equivalent to all edge occupancy sitting on edges `{2, 4, 7}`, so the warm-up's
original criterion should be inferable from this data — and that `PsyboidBits.WARM`'s javadoc
looked stale. **Both were right.**

The equivalence holds exactly on dabeone: stable edges `{2, 4, 7}` hold no scoring state, scoring
edges `{0, 1, 3, 5, 6, 8}` are exactly the rest. `EdgeOccupancy.warmupScoring` re-measures the
criterion directly, 2,000 seeds, physics 3, over a 4,000-tick run from each start:

| start | 0 | 500 | 1,000 | 2,000 | 5,000 | 10,000 |
| --- | --- | --- | --- | --- | --- | --- |
| **scores unsteered** | 98.8% | **16.4%** | 8.5% | 7.0% | **4.8%** | **2.7%** |
| leaves the stable cycle | 99.0% | 17.3% | 8.9% | 7.8% | 5.1% | 2.8% |
| javadoc, physics 2, 40 seeds | 100% | **45%** | — | **25%** | **12.5%** | **12.5%** |

**Every level was about 2.7x too high, and the claimed floor does not exist.** The javadoc read
5/40 at both 5,000 and 10,000 and concluded those were "seeds that score unsteered however long
you wait, a property of the map rather than of the warmup". The rate is still falling at 10,000
(4.8% → 2.7%) and reaches **0.2% of 250-tick windows by tick 20,000**. Five of forty was a sample
too small to see it still moving.

> **So this criterion never terminates, and cannot choose a warm-up.** Longer is always better on
> it — which is exactly how the constant reached 5,000. And what it improves is the flock settling
> into a formation whose members stop pushing each other off the cycle: the homogenisation the
> minimality argument says not to optimise for, and the reason `occControl` is identically
> `0.0000`. **Edge-occupancy decay does terminate, at 500.** That asymmetry is the argument.

**The cost of 500 is a fifth of what this section first claimed.** Written against the stale
javadoc, it said dropping to 500 would make `occControl` non-zero for "roughly half the corpus".
The real figure is **16.4% over 4,000 ticks**, and lower over the corpus's own 2,739 — so of 40
plans, five or so rather than eighteen. The two measurements also reconcile: at `WARM = 5,000`
about 3-5% of seeds score unsteered, so 0 of 40 plans doing so is ordinary luck, not evidence of a
hard floor.

> ⚠ **`WARM` is still not changed, because the decision is a specification one.** It sits in the
> `CorpusPreset` fingerprint, so moving it re-addresses every corpus. **Three coherent options**,
> in preference order:
>
> 1. **Switch the spawn to `TAU_UNIFORM` and set `WARM` low.** Best on the occupancy metric from
>    tick 0, and it makes the warm-up's job explicit rather than incidental.
> 2. **Keep `UNIFORM`, set `WARM = 500`**, and report excess over control rather than assuming
>    control is zero — about five plans in forty would have a non-zero one.
> 3. **Keep `WARM = 5,000`** and record that it is a *settling* choice made deliberately, so the
>    minimality argument in `CORPUS.md` stops reading as an unmet obligation.
>
> Option 3 is now harder to defend than it was: with no floor in the scoring criterion, 5,000 is
> not a natural stopping point on it either — just a place someone stopped.

### Two timescales, and they are not the same relaxation

Worth keeping straight, because the two curves disagree about when the flock is "settled".

- **Occupancy bias** — how far the mean distribution over edges sits from its long-run value.
  Plateaus at **tick 500** and then oscillates forever, because phase is conserved.
- **Excursion rate** — how often a flock puts a boid off the stable cycle at all. Falls right
  through: 95% of seeds per 250-tick window at the start, 5.9% by 750, 1.3% at 2,000, 0.7% at
  4,000, 0.3% at 10,000, **0.2% at 20,000**, still going.

The second is the flock tightening, and it is what governs `occControl`. Both spawn-from-stable+
rules show the same slow decline, and something they do not: they start *cleaner* than they end
up, 0.4-0.5% in the first block rising to 1.0-1.5% by tick 1,000-2,000 before falling back. **A
flock spawned tidily gets untidier before it settles**, which is worth knowing before reading a
low excursion rate at tick 0 as a spawn being good.

## 0g. End-to-end wiring, and plait — **built**

**2026-09-06**, on the user's decision closing §0f: **`TAU_UNIFORM` is the spawn rule and the
warm-up is total edge length over speed** — the time to traverse every edge once, 940 ticks on
dabeone. Long-term equilibrium was never the goal; a sufficiently obfuscated history is.

> **The user's mechanism for §0f's endless decline, recorded because it is better than the one
> measured.** Scoring events after the warm-up come from a small number of windows — perhaps one —
> in which a boid on a stable orbit induces another to exit. The rate keeps falling because
> **exits add entropy to phase offsets and non-exits do not**: any configuration is equally likely
> to be arrived at from a random state, but a non-exiting configuration locks in while an exiting
> one re-randomises itself. That is an absorbing dynamic rather than a mixing one, which is why
> there is no floor.

**Built.**

- **`Spawn`** — the rules promoted out of `EdgeOccupancy`, which was a survey and should not own
  something the simulation depends on. Binds a rule to a map, hands out a flock, and is threaded
  through every replay path, because a replay that spawns differently does not reproduce the
  timeline whatever the label says.
- **`CorpusPreset.Warmup`** — a policy, since the value is a function of the map. `PLANS_40` and
  `SMOKE` take the new defaults; **`LEGACY_40`** carries the pre-2026-09-06 recipe so every figure
  recorded under it stays reproducible.
- **`Pipeline`** — a map and a gate in, a verified corpus out, every tier derived on the way.
- **plait, from its PNG.** It had a source map and no ingest; it now has both, at
  `46f880d41d2c1e4e`.

**Both maps run end to end.** Figures and the resolved-parameter table in `CORPUS.md`.

### The spread was a dabeone constant, and failed silently

**Found on plait, and it is the more useful half of this section.** The search forks when the
psyboid arrives on a branching edge; the override fires a further *coast* ticks later. Plait's
branch edge is 756 ticks long, so the coast runs to 775 — and a walk that stops 640 ticks after
its root never sees the branch taken. Both children of the fork end in the same place, tie, and
**the tie goes to declining**, so the search asks for no turns at all and reports nothing wrong.
Measured over eight plait seeds: **0 turns at spreads 640, 960 and 1,280; 2 at 1,600; 9 at 2,400.**

`PsyboidBits.minimumSpread` now derives it — one stable lap, plus the furthest a branch edge's
critical state can be, plus the longest hold. **1,597 on plait, 398 on dabeone**, and the recipe
takes whichever of that and its named spread is larger, so the measured 640 stands where it was
measured.

### The blockers, named

1. **The gate.** Human judgement, and silent when wrong.
2. **`PsyboidBits` only tries holding right**, and drops anything else without saying so.
   `PsyboidBits.reaches` now tries both and reports: **plait `0->3` needs a left hold of 8 ticks**,
   so half of plait's psyboid is invisible, and **dabeone `5->6` needs a left hold of 14** — which
   makes the javadoc's "no hold reaches it" wrong, though the cold-start argument for dropping it
   is a separate one that may still stand.
3. **No generic psyboid override-generating algorithm**, as the user already had it.

Nothing else. Every other tier derives from the map without a decision.

### Not done

- **Fixing (2).** Searching both holds is a change to the psyboid algorithm, and the algorithm has
  not been chosen. Reported, not replaced.
- **`PsyboidBits.WARM` and the `SimTest` entry points that read it.** Six audit entry points
  replay corpus plans at the constant rather than at the corpus's own warm-up. Harmless while
  everything they read is `LEGACY_40`, wrong the moment they read anything else, and they should
  take the warm from the corpus they are reading.

## 0h. In flight — a psyboid that can read plait

**Started 2026-09-06.** Plait's failure under `PsyboidBits` is a **success of map design**, and the
user says so: plait was built to *obfuscate the implied score associated with edge occupancy*, and
by extension the value of a pathing decision. Both long edges are ~756 ticks, both sides have a
~55-tick bypass, and a turn taken at the end of edge 1 does not reach a scoring pixel until most
of edge 0 and then edge 2 have gone by — some 890 ticks later. At `alpha` 0.95 per eight ticks
that payoff is worth 0.006 of its face value. **§0g's spread floor let the search see the turn
happen; it still could not see the turn pay.**

**The goal:** maximum corpus score on plait, at under **one second per thousand ticks**.
**The restriction:** no training on full plait simulations — deterministic map properties, things
computed from them, and short 1-, 2- and 3-boid measurements under real physics.

### Built: `EdgePrice`, the price function

Per edge, the ticks a coasting traversal takes and how many of them score; then every simple
cycle; then the **gain** `lambda*`, the best score per tick any cycle sustains, and a **bias**
`h(e)` whose greedy policy is single-boid optimal. Average reward, not discounted — **a discount
would reintroduce the horizon the map was built to exploit.**

| | plait | dabeone |
| --- | --- | --- |
| scoring edge | 2, 80.8 of 142.7 ticks | 8, 53.9 of 79.1 |
| best cycle | `[0, 2, 1, 5]`, 1,738.4t | `[1, 5, 8, 4, 2]`, 505.7t |
| **gain** | **0.046471** | **0.106634** |
| bypasses, scoring nothing | `[0,3]` 821.6t, `[1,4]` 806.1t | `[2,7,4]` 254.0t, `[3,5,6]` 263.4t |

> ⚠ **Value iteration does not converge here, and it fails quietly.** The transition graph is
> deterministic, so its recurrent class is a bare cycle and therefore perfectly periodic;
> synchronous sweeps oscillate with the cycle's own period forever. The first run had the policy
> right and the values four sweeps out of phase — `exit[1]` pointed at the bypass when the exit
> was worth 75 more. **Fixed by charging the gain into each arc's weight and taking longest
> paths**: every cycle then has weight at most zero, so Bellman-Ford settles in `n` rounds.

### Built: `PsyboidOverride` as an interface, and `EdgePilot`

An override was one thing — a fixed turn at an absolute tick — so a plan was a schedule worked out
in advance, and on 750-tick edges the boid's accumulated slip is larger than the window a turn has
to land in. The interface now carries `from`/`to`/`asks`/`actsAt`, which is every question any
caller actually asked; `HeldTurn` is the old kind, `EdgePilot` the new one.

**A pilot carries a route, not a schedule.** Each tick it reads where the boid *is*, looks up
`EdgeNavigation.steerCostTo` for the fewest non-straight ticks to leave by the target exit, and
asks for nothing when coasting already does it. It is therefore self-correcting, and **inert on
99.65% of ticks** — 138 steers in 40,000. A cost table is built only where the route differs from
`straightTo`, which on both maps is one edge.

### Measured: the price function describes the physics

One boid, no flocking, flying the price route:

| | flown | gain | |
| --- | --- | --- | --- |
| plait | **0.046575** | 0.046471 | **100.2%** |
| dabeone | 0.100440 | 0.106634 | 94.2% |

Coasting scores **exactly zero** on both. Dabeone's 5.8% shortfall is the price function's own
optimism — it costs a lap at the coasted 505.7 ticks where a boid flies 533 — and 0.100440 sits
right on the independently measured solo floor of 0.101313, so the two agree about the physics and
disagree only about the estimate.

### Where that leaves the goal

Four boids, 20 seeds, 5,000 ticks, `TAU_UNIFORM`, the pilot on boid 0:

| | control | piloted | psyboid | each other boid | previous corpus |
| --- | --- | --- | --- | --- | --- |
| plait | 0.000810 | **0.044500** | 0.040440 | 0.001353 | 0.00630 |
| dabeone | 0.000550 | 0.170510 | 0.078680 | 0.030610 | 0.20100 |

**Plait is 7x better than the corpus it replaces, and it is all selfishness.** Each other boid
contributes 0.001353 against a control share of 0.000203 — a lift, but next to nothing. The flock
ceiling if all four flew the route is 0.185886, so this reaches **24% of it**. On dabeone the
naive pilot is *worse* than `PsyboidBits` (0.171 against 0.201), which is the same fact from the
other side: a search that looks at the flock finds herding, and a pilot that only reads the map
does not.

**Compute: 0.001 s per 1,000 ticks — a thousandfold under the budget.** Every remaining problem is
one of what to search for, not of how much searching is affordable.

### Windows convert — the first forward test in the project

**Asked by the user, 2026-09-06, and it was the right question:** every check on a window so far
has run *backwards*. `ExitAudit` takes exits that happened and asks whether a leader was in
position to account for them, which shows the bands are not too **narrow** and says nothing about
whether they are too **wide**. A band that admits every placement explains every exit and predicts
none.

`Herding.trial` asks it forwards — put a leader inside the band, fly two boids, does the other one
leave? Against two controls: the leader outside the band on the same edge, and no leader at all.
**About a second per map.**

| arc | leader in band | outside, same edge | alone |
| --- | --- | --- | --- |
| plait `0->3` | **29.3%** | 2.1% | 0.0% |
| plait `1->5` | **14.1%** | 1.7% | 0.8% |
| dabeone `2->1` | **26.6%** | 1.5% | 1.6% |
| dabeone `4->0` | **41.9%** | 0.0% | 0.0% |
| dabeone `5->6` | **11.2%** | 0.3% | 0.0% |

**Windows are levers, not descriptions.** A leader standing in the band converts fifteen to forty
times more often than one standing outside it on the same edge, and a boid with no neighbour at
all essentially never exits — which is the same fact the near-zero control occupancy shows, from
a different direction.

### The conversion table, which is the cheap estimator

**Conversion is dominated by which edge the leader stands on, and hardly at all by how tight the
band is.** Dabeone's edge 0 has a 16.1-tick band on arc `2->1` that converts at **1.0%** and a
33.3-tick band on arc `4->0` that converts at **86.9%**. Width is confounded, not causal.

| dabeone `4->0` | band | converts | | dabeone `2->1` | band | converts |
| --- | --- | --- | --- | --- | --- | --- |
| leader edge 0 | 33.3 | **86.9%** | | leader edge 1 | 33.9 | **66.2%** |
| leader edge 1 | 57.9 | **69.8%** | | leader edge 6 | 36.2 | **45.1%** |
| leader edge 3 | 38.0 | 46.9% | | leader edge 3 | 25.6 | 24.8% |
| leader edge 5 | 19.5 | 17.5% | | leader edge 8 | 11.5 | 24.5% |
| leader edge 4 | 9.7 | 5.7% | | leader edge 2 | 11.3 | 5.1% |
| leader edge 2 | 31.9 | 4.4% | | leader edge 0 | 16.1 | **1.0%**, under its control |

So the table itself is the estimator the search should branch on: `(arc, leader edge) ->
conversion probability`, measured once per map from two-boid physics in about a second. It says
where a lead is *possible*; whether a given one materialises still needs lookahead, exactly as the
user expects — 40% is a long way from certainty, and exits chaining into one another is not in
this table at all.

> ⚠ **`SolverFacts.VACUOUS = 0.9` is far too permissive on a long edge.** Plait's bands wider than
> 300 ticks convert at **1.7%** against an overall control of 2.1% — no better than nothing — yet
> they pass the vacuity test, because 439 ticks is only 58% of a 756-tick edge. **A band that
> covers half a long edge predicts nothing and is counted as a constraint.** Anything reading band
> counts on plait is over-counting; `EDGES.md` §7 carries this.

### Plait is not a boring map

Its windows exist, they convert, and **the useful leader positions are on the psyboid's own
cycle.** The arc that matters is `1->5` — the one that pulls a boid off the non-scoring stable
cycle and onto the scoring route — and its best leader edge is **5, converting at 24.2%**, which
is where the psyboid is immediately after taking that exit itself. So the natural configuration is
the productive one: **exit first, and the boid behind you may follow.** Leading costs phase, not a
detour.

`0->3` is the opposite and is a hazard rather than an opportunity: it pulls a boid off edge 0 onto
the bypass, away from the scoring edge. A psyboid should avoid causing it, and it converts at
29.3% — so avoiding it is not automatic.

### Built: `EdgeReach`, so a window on one edge can be compared with a boid on another

A leader window is recorded against the edge the **led** boid is on, and names a leader standing
somewhere else entirely. Answering "will my psyboid be in that band when that boid gets there"
means holding two positions on two edges and a duration and rebasing all three, which done by hand
at each call site is an invitation to a silent sign error — a distance one edge-length out still
looks like a distance.

- **`advance(at, ticks, route)`** — the forward rebase, and what the other two are built from.
- **`unsteered(from, to)`** — coasting ticks, **absent when coasting never arrives**. That is the
  common case rather than the exception: unsteered travel from the stable cycle stays on it, so
  every window on an unstable edge is unreachable this way. Verified — plait `1->5` and dabeone
  `2->1` both return absent.
- **`steered(from, to)`** — fewest ticks with steering allowed, Dijkstra over the edge graph.
- **`willLead(...)`** — all three rebasings in one call, which is the question a psyboid asks.

Checked against something known independently: **dabeone's circuit back to edge 1 reads 528.3
steered against 528.3 from summing the optimal cycle's clock lengths.** On plait the shortest
circuit back to edge 0 is 811.5 — the bypass, not the 1,703-tick scoring loop — which is right and
worth remembering: **shortest is not the route the psyboid wants.**

### Built: `PhaseShift`, the currency an override tick is spent in

**A psyboid cannot reach a leader window by waiting.** Waiting advances it and the boid it wants
to lead by the same amount, because phase is conserved (§0f). The only way to change a phase
relationship is to travel a route through an edge that is longer or shorter than the coasting one,
and what that costs is override ticks. Those are free today and will not be forever, so the ranking
is by **phase per override tick**, not by the largest shift available.

One dynamic program over `(state, budget)` per edge — tau is monotone, so the edge's internal graph
is a DAG — for the fewest and most ticks to leave **by the same exit coasting takes**. Same exit is
what makes it a phase change rather than a route change. Under half a second for both maps.

**Both of the user's predictions came out, which is the test this was built to pass.**

| plait | span | hurry | dawdle | per tick | |
| --- | --- | --- | --- | --- | --- |
| **edge 0** | **64** | **53** | 11 | **2.21** | **the bulb** — 3x the next edge |
| edge 1 | 30 | 11 | 19 | 0.79 | |
| edge 2 | 18 | 7 | 11 | 0.50 | |

> **The bulb, with the sign the other way round from expected.** Coasting on edge 0 sits **83% of
> the way up** its own range: the coasting line already takes the wide way round, so relative to
> the fast line there is a 53-tick longcut and the boid is already on it. Measured against
> coasting it therefore prints as *hurry*. Operationally that is the fact that matters — a psyboid
> on plait's edge 0 can arrive **53 ticks early and only 11 late.**

| dabeone | span | hurry | dawdle | per tick | |
| --- | --- | --- | --- | --- | --- |
| edge 5 | 27 | **18** | 9 | 0.75 | the one real shortcut; coasting sits 67% up |
| **edge 7** | **10** | 5 | 5 | **0.33** | **"a modest difference"**, and the lowest on the map |

Dabeone is **on rails for hurrying**: 63 ticks of hurry available across the whole map against 133
of dawdle, and every edge but 5 has coasting sitting low in its own range. Plait is the opposite on
its long edges.

### The vacuity filter is gone from the trial

`SolverFacts.VACUOUS` turned out to be live only in code written this week plus one render entry
point. Including every band changes the headline conversions by under a point — 30.0 / 13.2 / 27.5
/ 41.2 / 11.0% against 29.3 / 14.1 / 26.6 / 41.9 / 11.2% — so it was doing almost nothing, while
the per-width table it was standing in for says the real thing: on plait, bands over 300 ticks wide
convert at **2.2%**. **A measured conversion is a better answer than any fraction of an edge**, and
the constant is left alone rather than retuned.

### Built: `Bench`, and what an algorithm is measured against

Asked for by the user 2026-09-06, and it is the thing that should have existed before any psyboid
was called good. Four scenarios — **dabnt-4, dabeone-4, plait-4, plait-10** — every entrant on the
same seeds, with what it spent beside what it scored.

**dabnt takes dabeone's gate unchanged**, `x=202, y=[174,191]` decreasing, 9 edges. Found by trying
it first and then sweeping vertical lines; nothing else decomposed sanely. The two maps are the same
corridors with and without a trap, which is a hint that a gate is a property of the layout rather
than of the pixels.

**The controls.**

| | what it is |
| --- | --- |
| `none` | no override. The floor, and on three of four scenarios it is exactly zero |
| **`route`** | the psyboid held to the price-optimal scoring cycle, coasting otherwise. **Pure selfishness — it never looks at the flock, so anything that does not beat it is not herding** |
| `bits` | the established branch search at its own budget |
| `bits-ultra` | the same at twice the spread, so the gap between them separates compute from method |
| `priced` | the same tree, valuing a leaf by the flock's *position* rather than by the points it collected |

**The lookahead is derived, not inherited.** 320 ticks at 0.95 per second were fitted on a map
whose scoring lap is 506; on plait's 1,738-tick lap they discount every decision's payoff to 0.006
of face value, which is why the search declined everything there. Both now come from the map: the
lookahead is one optimal scoring lap, and `alpha = 0.4 ^ (SECOND / lap)` puts a point at the end of
it at 0.4. That is the shape that has worked before, in terms the map supplies.

> ⚠ **A harness bug worth recording, because it would have made every compute figure wrong.** The
> search commits decisions for as long as its `run` asks, and the first version passed 100,000
> while flying 5,000 — twenty times the planning actually used, charged to the algorithm's compute
> budget. Caught before the numbers were quoted. **A search's horizon must be told the length of
> the run it is planning for.**

### The benchmark, 20 seeds x 5,000 ticks

`impactful` is ticks the psyboid's turn differed from the rules' after the veto — **what it
spends**. `occ/1ksp` is occupancy per thousand of those, the axis that matters once override ticks
are budgeted. `s/1000t` is compute against a budget of 1.

| scenario | algorithm | occFlock | occPsy | occOthers | impactful | occ/1ksp | s/1000t |
| --- | --- | --- | --- | --- | --- | --- | --- |
| **dabnt-4** | none | 0.000000 | 0.000000 | 0.000000 | 0.0 | — | 0.0005 |
| gain 0.110390 | **route** | 0.026000 | **0.096660** | 0.002447 | 166.9 | 0.156 | **0.0004** |
| | bits/640 | 0.025068 | 0.083330 | 0.005647 | 217.5 | 0.115 | 0.0095 |
| | **bits-ultra/1280** | **0.026347** | 0.083520 | 0.007290 | 221.3 | 0.119 | 0.0645 |
| | priced/640 | 0.024437 | 0.076260 | 0.007163 | 206.4 | 0.118 | 0.0084 |
| | piloted/640 | 0.023903 | 0.089830 | 0.001927 | **150.7** | **0.159** | 0.0087 |
| **dabeone-4** | none | 0.000137 | 0.000550 | 0.000000 | 0.0 | — | 0.0003 |
| gain 0.106634 | route | 0.047523 | **0.097140** | 0.030983 | 136.6 | 0.348 | **0.0004** |
| | bits/640 | 0.053818 | 0.097010 | 0.039420 | 235.2 | 0.229 | 0.0078 |
| | bits-ultra/1280 | 0.057203 | 0.096320 | 0.044163 | 233.6 | 0.245 | 0.0493 |
| | **priced/640** | **0.057308** | 0.095140 | **0.044697** | 224.0 | 0.256 | 0.0083 |
| | piloted/640 | 0.046183 | 0.095150 | 0.029860 | **131.7** | **0.351** | 0.0083 |
| **plait-4** | none | 0.000203 | 0.000000 | 0.000270 | 0.0 | — | 0.0004 |
| gain 0.046471 | **route** | **0.010095** | **0.032290** | **0.002697** | 20.8 | 0.487 | **0.0004** |
| | bits/1597 | 0.003970 | 0.013450 | 0.000810 | 9.7 | 0.411 | 0.0046 |
| | bits-ultra/3194 | 0.004172 | 0.013450 | 0.001080 | 11.7 | 0.357 | 0.0167 |
| | priced/1597 | 0.003832 | 0.012900 | 0.000810 | 30.7 | 0.125 | 0.0055 |
| | piloted/1597 | 0.007983 | 0.027070 | 0.001620 | **14.3** | **0.560** | 0.0059 |
| **plait-10** | none | 0.000730 | 0.000810 | 0.000721 | 0.0 | — | 0.0012 |
| gain 0.046471 | **route** | **0.005226** | **0.033010** | 0.002139 | 18.5 | 0.282 | **0.0013** |
| | bits/1597 | 0.002676 | 0.014620 | 0.001349 | 13.2 | 0.203 | 0.0142 |
| | bits-ultra/3194 | 0.003308 | 0.015320 | 0.001973 | 19.2 | 0.172 | 0.0573 |
| | priced/1597 | 0.002826 | 0.014520 | 0.001527 | 23.0 | 0.123 | 0.0167 |
| | piloted/1597 | 0.003800 | 0.022720 | 0.001698 | **11.1** | **0.344** | 0.0165 |

**Compute is not the constraint anywhere.** The dearest entrant is 0.065 s per thousand ticks
against a budget of 1, and `route` is **0.0004 — two and a half thousand times under**.

### The correction: "bad aim" was a bug in the pilot, not a property of anything

The previous table showed `route` flying 56–76% of the price gain in a flock and the conclusion
drawn was that a pilot corrects too late. **The user rejected the premise**: the edge axiom makes a
missed exit impossible for anything that simply avoids stepping onto a wrong edge, so bad aim is
not an available explanation, and if an exit is missed the code is wrong or the decomposition is.

Both halves checked out.

**The decomposition is fine.** `Pipeline.checkNavigable` tests the invariant exhaustively — from
every live state of every edge, does some single turn stay on the edge or reach the chosen exit —
and it **holds on all three maps**, every arc, every state, in under 0.05 s. It now runs on every
build and throws.

**The pilot was wrong.** It carried a 0-1 BFS per edge for the fewest non-straight ticks to the
exit, and steered only when *that* number said to. Two faults, one fatal: it guarded against
failing to reach the target and **not** against being pushed onto some third edge entirely, and
its "no route from here" case **returned silently**. Replaced by the rule itself — leave the
flock's request alone if its successor stays on the edge or reaches the target, else take the
first turn that does, else throw.

| | occPsy before | occPsy after | of gain |
| --- | --- | --- | --- |
| dabnt-4 `route` | 0.059910 | **0.096660** | 54% → **88%** |
| dabeone-4 `route` | 0.082070 | **0.097140** | 77% → **91%** |
| plait-4 `route` | 0.032300 | 0.032290 | 69% (already right) |

**A third of the psyboid's score on the dab-like maps was being lost to that bug**, and the
compute fell 14x with the BFS gone. It changes the standings: `route` now matches the searches on
the psyboid's own score everywhere, wins outright on dabnt and both plaits, and is beaten only on
dabeone — where the searches earn it in `occOthers`, 0.0447 against 0.0310, which is real herding.


### Why the searches lose to `route` on plait: a real gap, and a fix that is not worth it

Asked 2026-09-07: `route` is a policy the search could choose, so either it is never considered or
it is wrongly pruned. **Never considered**, and the mechanism is exact.

`PsyboidBits.walk` looks for a fork on a **change of edge** — `now != was`, with `was` seeded to the
edge the root is already on. After committing a decision, `search` advances the root past the
override's end. On plait, edge 1 is 756 ticks and the spread is 1,597, so the root routinely lands
**mid-edge-1**. The decision the psyboid is in the middle of is then invisible, the boid coasts
through the branch, and the plan records a decline it never actually considered. Dabeone's edges
are ~100 ticks, so the root rarely occupies one branch edge across a whole advance and the gap
almost never bites there.

**Seeding `was = -1` at the top level closes it, and the cure is worse.** Measured:

| | before | after |
| --- | --- | --- |
| plait-4 `bits` occFlock | 0.003970 | 0.004818 (+21%, still less than half `route`'s 0.010095) |
| dabnt-4 `bits` occFlock | 0.025068 | 0.022223 (**−11%**) |
| plait-4 `bits` compute | 0.0057 s/1000t | **3.55 — over the budget of 1** |

The compute goes because a root fork now fires on most calls, so the outer loop commits far more
often and every commit pays for a whole tree. **Reverted**, with the gap documented at the line
that causes it. It is not the shape of the fix — the branch structure wants respecifying rather
than patching, and the user is doing that.
### Nothing dominates, and the frontier is the honest answer

| | best occupancy | best per override tick |
| --- | --- | --- |
| dabnt-4 | bits-ultra 0.0263 (route 0.0260 at 1/160 the compute) | **piloted 0.159** |
| dabeone-4 | priced 0.0573 | **piloted 0.351** |
| plait-4 | **route 0.0101** | **piloted 0.560** |
| plait-10 | **route 0.0052** | **piloted 0.344** |

`piloted` is the most efficient entrant on every scenario and the highest-scoring on none;
`route` is the cheapest by three orders of magnitude and wins three scenarios outright. The
searches buy their remaining advantage on dabeone with **1.7x the override ticks**.

### Built in response: `piloted` — the search decides, a pilot executes

Finding 2 says the two failures are separable, so each decision the search commits becomes an
`EdgePilot` confined to that decision's own stretch of timeline, carrying the *route* the search
chose rather than the tick it guessed. Deciding and steering stop sharing a failure mode.


### Handoff — where this stands, 2026-09-07

**Waiting on the user, who is specifying the search procedure.** Do not invent one first; the
last three attempts at improving it from the inside are recorded above and the pattern is that
the *structure* is wrong rather than the tuning.

**The bar to beat is `route`**, not any search. It is the price-optimal edge route flown by a
pilot that never looks at the flock, it costs 0.0004 s per thousand ticks, and it wins three of
four scenarios outright. **Any proposal that does not beat it is not herding**, whatever else it
does. The only scenario where a search earns its place is dabeone, where it buys +21% occupancy
with 1.7x the override ticks.

**What exists and should be reused rather than rebuilt** — `GLOSSARY.md`'s table is canonical, but
the ones that matter here:

| | |
| --- | --- |
| `Bench` | the measure. Four scenarios, named controls, `-Dseeds -Dticks -Donly=<scenario>` |
| `EdgePrice` | gain and bias over `(edge, tau)`, and `potential` over a whole flock |
| `EdgePilot` | route-following override; **one step of lookahead is all of navigation** |
| `PsyboidOverride` | the interface. `HeldTurn` is the schedule kind, and a schedule is what keeps failing |
| `Herding` | where a lead is possible, and the measured conversion table |
| `EdgeDistance` / `PhaseShift` | forward distance in `(edge, tau)`, and what an override tick buys in phase |

**Three things known to be true and easy to forget.**

1. **A held turn is not navigation.** The axiom promises nothing about always-left or
   always-right; an override that holds one turn for a fixed span can miss an exit the
   decomposition guarantees. `EDGES.md` §7.
2. **Bad aim cannot explain a missed exit.** One-step navigability is verified exhaustively on
   every build and throws. If an exit is missed, the steering was not following the rule.
3. **The searches skip decisions they are already inside.** `PsyboidBits.walk` forks on a change
   of edge, so a root landing mid-branch-edge never considers that visit. Documented at the line.
   The obvious patch was measured and rejected — see above.

**Two open threads that are not blocked on the spec.** The `VACUOUS = 0.9` band test is far too
permissive on a long edge and inflates plait's usable-band counts (`EDGES.md` §7); and six
`SimTest` audit entry points still replay corpus plans at `PsyboidBits.WARM` rather than the
corpus's own warm-up, which is correct only for `LEGACY_40`.

## 0i. In flight — gates, decision points, and a search over decisions

**Started 2026-09-08. §0h is closed**: the user is specifying the search procedure rather than
tuning the one that lost to `route`. The §0h implementations have been torn out — see the
tear-out below — and what replaces them is specified here by the user, not invented.

### The strategy

**Reduce the map to a series of binary decision points**, in a system parallel to and heavily
overlapping with edge decomposition.

From the tree search's side, every action is a **binary decision**: take this exit? take this
shortcut? take this longcut? do some exit-inducing wiggle? Those decisions go into the override.
From the simulation's side an **override is a black box** that steers the psyboid. The override
itself holds no policy — it **references precomputed tables** that translate a decision in the
search tree into specific actions at specific nodes.

The steps, in order:

1. **Define decision points** — invocation, decision, execution.
   - **Edge exits** — done, possibly damaged in the last session; needs manual review.
   - **Shortcuts / longcuts** — done sloppily as `PhaseShift`, deleted. The replacement mechanism
     is a **navigation gate**, specified below; not built.
   - **Wiggles** — deferred.
2. **Control and data flow** for overrides and search.
3. **Tables** for the override implementation.
4. **Search structure.**
5. **Comparator for pruning, and the commit mechanism** — with heuristics for endpoint
   evaluation to be tested.

### Gates — canonical statement in `EDGES.md` §2a

A **gate** is a set of trigger conditions guaranteed to fire **exactly once as a boid traverses
an edge**. For a state `s` on the gate's edge, exactly one of: `s` is in the gate; any infinite
backward navigation from `s` is in it for exactly one tick; any infinite forward navigation from
`s` is. (The latter two assume the path never revisits the edge.)

State-based and transition-based are both gates and either can be used; transition-based is the
slightly more versatile set. **Only the state-based form is being discussed for the moment.**
Gates are stored as sets — sets with a guarantee, nothing more. Code-wise a gate is an abstract
contract, which is to say nothing; there will be **a class for gate helper methods**, and whether
the several kinds need abstracting is not yet known.

Three consequences worth writing down:

- **A gate is usually, but need not be, located on the edge it belongs to.** A gate several ticks
  before an edge starts would sit near the end of every edge feeding into it.
- **No gate can live on an edge where infinite stalling is possible.** None exist today; some
  certainly will.
- **A gate is tied to a decomposition**, since the gate property is defined on the edge property,
  and decomposition — though fairly rigid — is not unique.

### There is no such thing as being pushed out of an edge early

Stated here because a session got it wrong on 2026-09-08 and it is the recurring error.

**While on an edge it is physically impossible to do anything but continue on that edge until
landing on a successor edge.** The flock cannot displace a boid off an edge; it can only
influence *which* successor. A traversal always completes, so a gate always fires.

The axiom is what guarantees it, and the memorable form is: **an edge is a corridor with no side
doors** — edges are cut exactly where the options change, so inside one, the options cannot.
Now in `CLAUDE.md`'s hard rules in place of the retired gate rule.

### Decisions

A `Decision` holds **some options** and **a `State` in need of an override**. Which override gets
applied is the result of how `decide()` is called.

**Exits and shortcuts are not fundamentally different.** An exit decision gives a boid that
started anywhere in the gate an override making it take certain precomputed navigation decisions
at some of its upcoming states, the end result being a forced edge exit. A shortcut decision does
exactly the same thing, and the result is advancing or regressing relative phase in tau-space.

**`decide` returns the state the decision took place on, with the override installed.** For the
caller, how and when that decision gets implemented is a black box — it might have no effect on
the next tick, so advancing a single tick was never a meaningful thing to return.

```java
stateWithOverride = state.nextDecision(Decision.Gate.PSYBOID_EXIT).decide(Decision.Option.EXIT);
```

Helper methods could shorten that to `state.exit()` and `state.continue()` where the exit is or
is not taken.

Search tree expansion is then, ideally:

```java
List<State> getChildren(State state) {
    List<State> states = new ArrayList<>();
    List<Decision.Gate> gates = new ArrayList<>(Decision.Gate.PSYBOID_EXIT);

    if (Windows.inPhase(state)) gates.add(Decision.Gate.SHORTCUT);

    Decision decision = state.nextDecision(gates);

    if (decision.hasGateType(Decision.Gate.SHORTCUT)) {
        states.add(decision.decide(Decision.Option.FAST));
        states.add(decision.decide(Decision.Option.SLOW));
        states.add(decision.decide(Decision.Option.NONE));
    } else {
        states.add(decision.decide(Decision.Option.EXIT));
        states.add(decision.decide(Decision.Option.NONE));
    }
}
```

**Evaluation data belongs on the `Decision`.** Functionally the same as reading it off the state,
but it can be read *before* a `State` is made from a `Decision` — which is the cheap place to
prune, since every child of a node is the same arrangement differing only in installed override.

### Tested: a gate from one state, via the decomposition algorithm

**Specified by the user 2026-09-08, tested 2026-09-09** by `GateSplit`, run by hand. Take an edge
`E` and a state `S` on it; split `E` into `E \ S` and `{S}`; refine. The prediction is that
`E \ S` comes apart into exactly `E<S` (can reach `S`), `E⊥S` (neither), and `E>S` (reachable
from `S`), so `E` yields four edges and a gate follows from the pieces.

Run on dabeone `609cffdb84be218c`, all nine edges, one state per edge nearest the middle in tau,
one edge split at a time. **The control matters and passed**: refining the decomposition unchanged
gives 9 edges out of 9 in, so it is a fixed point and any change is the split.

| | |
| --- | --- |
| **The partition is never violated** | **0 pieces** out of 470 straddle two sides, on any edge |
| Exactly four pieces | **4 of 9** edges — 0, 2, 6, 7 — converging in two rounds |
| More than four | 5 of 9 — edges 1, 3, 4, 5, 8 — all of which then passed the 63-edge mask limit |

**So the prediction is right about *where* the boundaries fall and wrong that there are only
three.** Every piece refinement produces is wholly inside one of `E<S`, `E⊥S`, `E>S`; refinement
simply does not stop there on five of the nine. The purity result is sound despite the truncation,
because refinement only ever splits — a grouping that is pure at any round stays pure at the fixed
point — but the *counts* for those five are lower bounds, not fixed points.

**The extra pieces are phase, not structure.** `render/gate-split/edge<e>-pieces.png` against
`-sides.png`: edge 0 is three clean regions, and edge 1 — the same corridor travelled the other
way — is the same three regions plus a confetti speckle confined to one short diagonal stretch
where the corridor crosses another. An evenly spread speckle rather than a region is the phase
artifact of §8, so what shatters is `E⊥S` and the approach to `S`, subdividing by which phase
trajectory a state is on.

**Open, and geometric:** why one direction of a corridor shatters and the other does not. Edges 0
and 1 are inverses of one edge, the same pixels, the same crossing, and their chosen states sit at
tau 79.07 and 79.31 — yet edge 0 settles at four pieces and edge 1 runs past 170. Size is not it:
the two largest edges are 0 and 1, and 6 and 7 are the second largest and both clean.

**A trap worth not repeating.** The first two runs of this were void, in two different ways, and
both looked like findings. Splitting all nine edges in one pass blew past `refine`'s 63-edge mask
in a single round, so it returned a grouping that was never a fixed point. Then building adjacency
from `NavMap.successor(s, t)` recorded the same successor up to three times, because that method
returns the *veto-constrained* successor rather than skipping a turn the veto would alter — which
is what the decomposition itself does. The corrected graph is the one in `SimTest.label`, and with
it the clean cases converge in two rounds.

### The phase-bleed fix, and why `G` is the gate rather than `S`

**2026-09-09, second pass.** The first pass split a single state off each edge and asked whether
refinement produced exactly `{E<S, E⊥S, E>S, S}`. The user's reading of the result: a single state
occupies one phase of the step lattice, so `E<S` cannot cover the whole edge entrance and `E>S`
cannot cover the whole exit — full coverage needs phase bleed. Edge blowup is not a bug; it is
what happens when a split cuts finer than the grain the edge is made of, and shattering to
singleton edges is still a valid decomposition, merely a useless one.

**The fix, as specified:** take `S` to be the chosen state **together with its partial unsteered
forward tick** — `MapStates.of(s).partialTick(STRAIGHT)`, the existing model of exactly this
phase alignment. That makes `S` four to seven states instead of one.

**`S` is not the gate and never was.** A detour worth recording so it is not retried: `S` is a
handful of states in a corridor hundreds wide, so a boid on another phase walks straight past it.
Tested directly — **every exit of every edge is reachable from an entrance while avoiding `S`**,
in both variants, all nine edges. The gate is `G`, the boundary of `E<S`.

**The lemma `G` rests on, and it holds.** If `s` steps to `s'` and `s'` can reach `S`, then `s`
can reach `S`; so a state outside `E<S` has no successor inside it and **`E<S` is never
re-entered**. Measured rather than assumed: **0 violations across all 18 runs.** Given that, a
traversal crosses out of `E<S` at most once, and exactly once when it entered inside `E<S`.

> **So `G` is a gate for an edge exactly when every entrance of that edge can reach `S`.** That
> is the whole test, and it is one number per edge.

| | `S` = one state | `S` = state + partial tick |
| --- | --- | --- |
| **`G` is a gate** | **7 of 9** edges | **8 of 9** edges |
| fails | edges 5 and 8 | edge 8 |
| `E⊥S`, edge 0 | 2,304 states | 1,353 |
| `E⊥S`, edge 5 | 6,778 | 2,082 |
| exactly four pieces | 4 of 9 | 0 of 9 |

**The phase bleed does what it was predicted to do.** Entrance coverage on edge 5 goes 92.0% to
100%, on edge 4's exit 92.2% to 100%, and `E⊥S` roughly halves everywhere. `G` runs 292–502 states,
a full corridor cross-section, against `S`'s four to seven.

**Piece count moves the other way, and it does not matter.** Splitting a bigger `S` produces *more*
pieces, not fewer — 0 of 9 edges now stop at four, against 4 of 9 before — because `S` spanning
several phases puts a cut on each of them and refinement distinguishes states by which combination
lies ahead. There is a real tension here: **coverage wants `S` to span phases, piece count wants it
on one.** It is only a tension if the pieces are load-bearing, and they are not — `G` is built from
`E<S`, which is a union of pieces however finely they are cut.

**Edge 8 is the one failure and is worth a look.** dabeone's scoring edge: 89.9% of entrances reach
`S`, and only 12% of exits are reachable from it, against 100% on six of the other eight. `S` sits
at tau 36.38 of a 72.76-length edge, so the middle by the clock is not the middle by reachability
there. Not diagnosed.

### Where the construction stands: eight of nine, and a decomposition bug fixed

**2026-09-09, third pass.** The construction is `S = ` seed state, partial tick, perfected both
ways; split it off `E`; refine. **Eight of nine edges now come apart into exactly the predicted
four and settle there after two rounds.** Edge 8 is the lone holdout.

Two fixes got it there, and the first is a defect in the decomposition itself.

**1. Refinement was splitting on a distinction that was never real.** Reaching `X` and reaching
`X`'s successors are the same fact: `{X}`, `{all of X's successors}` and `{X}` plus any of its
successors are one equivalence class. *(Retracted in part 2026-09-12: only the first and third
are one class; `{all of X's successors}` is a different menu, and treating it as `{X}` is the
outcome reading that braiding rules out — `EDGES.md` §2a. The code only ever implemented the
first-and-third half.)* The algorithm applied that once, implicitly — what stays in
`E` is what paths to all of `E`'s successors — but never reduced a mask holding both an edge and
something already downstream of it. So `E<S` splitting over whether it could slip into `E>S`, while
both sides agreed they reach `S`, was read as a real difference when `E>S` is downstream of `S`.
`SimTest.absorb` reduces a mask to its class before anything is grouped on it, with an antisymmetry
guard so two edges that step to each other do not cancel out. **Known to be needed when the
algorithm was specified and deferred then, because it can only bite on edges shorter than one
tick.** Behind `SimTest.absorbEquivalentMasks`.

> **It costs nothing.** dabeone, dabnt and plait all produce the same edge count *and the same
> labelling fingerprint* with it on — `5d1ff610…`, `c9ca9029…`, `c68de7a7…`. No artifact
> re-derives and no recorded figure moves.

**2. The perfection operators had the same bug.** A candidate was rejected for having a successor
outside `S` when that successor was merely one step *past* `S`. Admission now reads `S` union
`S`'s successors, with the allowance frozen at the receiver.

**The candidate set must stay narrow, and that is the whole difference.** Widening the admission
rule invites widening the frontier to match — a state could in principle qualify while touching no
member at all — and that admits far too much: on dabeone edge 0 it swallowed the entire 17,028-state
edge and the split stopped splitting anything. Keeping candidates to the predecessors of members
(and successors, going forwards) kills the runaway, restores independence, and is what takes the
result from six of nine to eight.

| perfection | edges settling at exactly 12 |
| --- | --- |
| none | 1 of 9 |
| forwards only | 1 of 9 |
| backwards only | 7 of 9 |
| **both** | **8 of 9** |

Backwards does nearly all the work; forwards alone is worth almost nothing but is what settles
edge 3, which backwards cannot. **Independence is restored** — no edge needs more than one pass of
each, measured, so the alternation is a formality again.

**Edge 8 needed its inverse, and then it worked too: nine of nine.** It is dabeone's only
self-inverse edge — `inverted()` maps all 14,280 of its states back onto itself, where every other
edge pairs off 0-1, 2-3, 4-5, 6-7 — so it is two mutually inverse regions and splitting a state off
reached only one of them. Left alone it does not merely overshoot but blows up completely:
12, 15, 21, 33, 74, hitting the 63-edge mask cap. **Unioning `S` with `S.inverted()` before the
split settles it at exactly 12**, with `S` going 52 to 104, precisely double. It costs nothing
elsewhere: edges 0-7 come out identical, their inverses landing on the paired edge and dropping out
of the split.

**The inverse convention, confirmed rather than assumed.** `inverse(x, y, d)` is
`(x - stepX(d), y - stepY(d), d + TURNS/2)` — one step back down the heading, then flipped, which
is what `SimTest.renderEdges` already used to pair edges. Measured on dabeone: an exact involution
over all 136,276 live states with none falling off the map, and a total edge pairing. Now
`StateSet.inverted()`.

### Shortcuts and longcuts have a mechanism now: navigation gates

**Specified 2026-09-10, not built.** Step 1's third decision point needed a way to steer a boid
*within* an edge, which is the one thing the edge machinery had no answer for — a route across
edges is one step of lookahead, but nothing bounded travel along a wall.

**Edge insertion supplies it.** `EDGES.md` §2a: split a chosen `S` off `E`, refine, and the
boundaries that fall out are gates for free, because every transition between two edges is
well-formed by the axiom. That gives gates at near-arbitrary precision.

**And such a gate need not join the decomposition.** It can serve as an extra edge in
**navigation logic alone**, invisible to everything that reads the real one. Choose an `S` that
bounds travel along a wall, keep a chosen subset of the transitions the insertion creates — say
`E<S → E⊥S` on the left and `E<S → S`, omitting `E<S → E⊥S` on the right — and a boid steered by
the same greedy one-step lookahead that crosses real edges simply cannot pass through it.

**So a shortcut needs no new steering.** It needs a gate where the wall is, and `EdgePilot`.
That closes the question `PhaseShift` was deleted over: the thing to precompute is not a table of
hurry-and-dawdle offers but the gates that bound each one.

Two conditions on `S` fall out of §2a and want building:

- **The reachability condition is an assertion**, not something the algorithm can repair: every
  entrance of `E` must be able to navigate to `S`, and every exit of `E` reachable from some point
  of `S`. Fail it and the result is not a gate.
- **The closure condition is another perfection step**: any state that can navigate both to and
  from `S` without leaving `E` belongs in `S`. This is a third operator alongside
  `backwardsPerfect` and `forwardsPerfect`, and does not exist yet.


### Where this stands, and what the next session starts with — 2026-09-11

**The construction works on dabeone, nine of nine**, and the machinery that makes it work is
known and named: absorb in refinement, perfection in both directions with a narrow frontier, the
partial tick for phase completeness, and the inverse for a self-inverse edge. `EDGES.md` §2a is
canonical for all of it; `HINTS.md` §3a is the transferable version.

**What a cut across a corridor has to be is now measured, not guessed**: constant `d`, spanning
the across-axis, because `d` is the one axis a boid cannot skip. That bifurcates `E⊥S` exactly.
The cross-section view that made it visible is a standing diagnostic.

**The next session starts by asking whether that bifurcation survives.** With the constant-`d` cut
and proper merging, `E⊥S` comes apart into two clean halves — but whether those two halves can be
kept as separate edges *without the decomposition blowing up downstream of them* has not been
tested. **The user expects the answer is no**: that `E⊥S` can have several parts for analysis or
for psyboid logic, but they cannot be made to satisfy the edge axiom. If that holds, a split
`E⊥S` is a navigation-gate object rather than a decomposition object, and the design should say so.

**Two things not to redo.** Refinement's piece count is not the test for whether a line cuts —
a bifurcated `E⊥S` is two edges by construction and the pockets beside a line are fragmentary, so
a bare `refine()` will run past the mask and mean nothing. Use two rounds and read the component
sides. And the map render cannot show whether a set divides an edge; use `drawCrossSections`.

**Still open from earlier**: the reachability assertion and the interior-perfection operator as
first-class checks in a real `insert`, rather than the test rig's; the cut-construction algorithm
for a map that is not phase-locked; and edge 8, which is self-inverse and needs its inverse
unioned in, which every other edge tolerates but does not need.

### Braiding closes the `E⊥S` question — 2026-09-12

**Answered without touching a map.** The session was set to bug-hunt `refine()`, on the
hypothesis that a one-to-three branch should never need more than thirteen edges — `O`, the three
pairs, and three pieces per outcome — and that blowing past that was a defect. Feeding hand-built
state graphs to the real `EdgeDecomposition.refine` (`RefineToy`) showed the thirteen come out
exactly when the three-open layer has one menu, and that the axiom splits the layer the moment
two of its states can peel options in a different order: `{AB, C}` against `{A, BC}`. Every such
split is a new origin for the pieces below, and the count is unbounded in the number of outcomes.
That is **braiding** — canonical in `EDGES.md` §1 and §2a, transferable in `HINTS.md` §3a. Binary
joints have one menu and cannot braid, which is exactly the observed pattern: two-edge splits and
merges settle, three-edge ones blow up. **`refine` has no defect on any of the toys.**

**Consequences.** A bifurcated `E⊥S` cannot be kept in the decomposition: `E<S` would branch three
ways and `E>S` merge three ways. It is a navigation object, as the user expected. The `absorb`
rule's second half — `{all of X's successors}` ≡ `{X}` — was the outcome reading and is retracted;
the code never implemented it. A one-to-three branch decomposes cleanly only when two of the three
first peels are empty, i.e. it is really a chain of binary forks.

**Parked, not planned:** an altered edge rule, or a sub-edge structure, that could carry a
three-way joint. For now a joint where three edges meet is a dead end for the decomposition.

**Frontier, unchanged otherwise.** Step 1's third decision point — shortcuts via navigation gates —
is specified and not built; the reachability assertion and interior perfection want a real
`insert`; the cut-construction for unlocked maps has a definition and no algorithm.

### Decision zones for psyboid logic — exits **built and verified** 2026-09-12

**`Gate` (transition sets), `DecisionZone` (an edge's exit decision: region, opening gate,
prohibited gates per option, closing gate) and `DecisionOverride` (a stateless override that
honours the prohibitions while inside a zone), driven by `SimTest.zones`. Canonical in
`EDGES.md` §2a "Decision zones".** Specified by the user this session: a psyboid crosses a gate
that opens a decision zone, is handed one or more gates it will not cross, and crosses a gate that
closes the zone. For exits: `S` = every state of `E` that can leave `E`, plus its forward closure
on `E`; the opening gate is every transition `E−S → S`; the prohibited gate for a choice is every
transition from `E` into a successor not chosen; the closing gate is every transition off `E`.
Opening and closing gates on one edge, opening upstream, are crossed in pairs — equivalent to
bounding a convex region, which is `S` itself — so the zone is stored as its region and the
prohibitions stay transitions.

**Measured on dabeone `609cffdb84be218c`.** Nine of nine zones sound: no entrance inside its
region, nothing bypassing it, nothing leaking back out. `DecisionOverride` and `EdgePilot` flown
from one state round `[4, 2, 1, 5, 8]` for 4,000 ticks, alone and in a flock of four: **0
mismatching ticks in either flight**; every zone's opening and closing gates crossed in equal
numbers; a lone psyboid laps in 535 ticks scoring 54. (533 is on record for the retired
held-turn plans; the pilot turns as late as possible. Not chased.)

**This partly supersedes the `Decision` sketch above.** What a decision installs is now known:
a chosen option per zone, carried as data on a stateless override, with *inside the region*
standing in for *between the gates*. Of the four open questions put to the user: (2) is moot for
exits — one zone per edge, one option set; (4) is answered by the region — a decision instance is
`(edge, nth entry into its zone)`, and the tick is never needed. (1) and (3) remain.

### Subpaths — shortcuts and longcuts — **built and verified** 2026-09-12

**The user's respecification, same session.** Cutting `E⊥S` in two was never needed: forbidding
`E<S → E⊥S`, and thereby forcing `E<S → S`, does the job directly with four phantom edges at a
time. A shortcut or longcut is a **subpath** — `N` transitions along one edge, specified
explicitly — with an `S_k` per transition; the alternate phases fall out of the construction. The
zone opens at the states that can leave `E<S_1`, forward-closed on `E<S_1`, exactly as an exit
zone opens at the states that can leave `E`; it closes on the transition off `E<S_N`. Subpaths
that cross an edge border are deferred; the first and last `S` are to saturate the entrance and
the exit so borders never come into it. Choosing *where* a subpath should run is a separate task,
not started. `DecisionZone.subpath`, flown by the unchanged `DecisionOverride`, driven by
`SimTest.subpaths`; canonical in `EDGES.md` §2a "Subpaths".

**One amendment to the spec, measured in.** Taking every `S_k` literally as its transition's
partial tick broke the chain at turns — the sample formula rounds a phase onto the wrong pixel
about one turn in four — and every break left states with all three turns forbidden, on which a
flocked psyboid was stranded. `S_1` is the partial tick; each `S_{k+1}` is where `S_k` lands
taking the path's turn. Chain breaks and stuck states went from 10 and 31–61 to **0**.

**Measured, dabeone edge 4.** Entrance saturation has a floor: `S_1` must sit at tau ≥ 32–36
before all 618 entrances can reach it (sweep in `EDGES.md`). Past it, three paths — coasting,
left-hugging, right-hugging — give **six of six sound zones** across both exit loops, and a
psyboid told to take one lands on every `S_k` in order on every full traversal, alone and in a
flock of four, then takes either exit. The zone opens 4–14 ticks before `S_1`. The wall-hugging
paths change the lap by at most a tick, the stretch being straight.

**Next.** Placement — which subpaths shorten or lengthen a lap, and by how much — and then the
search that chooses among decisions. Cross-border subpaths when wanted.

### The tear-out, 2026-09-08

Deleted, all recoverable at `0f9b2b7`:

| | why |
| --- | --- |
| `PsyboidBits` | an old map-specific algorithm for dabeone |
| `Bench` | built on `PsyboidBits`, which explains one of the last session's bugs |
| `PsyboidCorpus`, and both corpora on disk | the corpora were nonsense; archived to `archive/2026-09-08/` |
| `HeldTurn` | part of the same nonsense. `PsyboidOverride.held` survives as an *analysis* primitive with no label format |
| `Herding` | window conversion; the finding is in `EDGES.md` §7 |
| `PhaseShift` | shortcuts and longcuts, done sloppily |
| `Sim.segmentedOverride` | uncalled, pre-decomposition era |
| `SimTest.stablePlusSweep` | the agreement-ratio scan `QUORUM = 5` settled by naming |
| `SimTest.graded`, `auditCorpus`, `renderUnexplained`, `steeringHistory`, `renderTick`, `scoringFloor`, `scoringLaps` | every one read a corpus. `SimTest` fell 3,982 → 3,096 lines |

`EdgeReach` was kept on the guess that its `(edge, tau)` rebase primitives will be wanted for the
execution tables; if they are not, that will become obvious and it can go.

**One defect fell out of the tear-out.** `EdgePrice.of` took a `steerableOnly` flag whose only
caller passed `true`, and that path dropped any exit no *single held turn* reached from a critical
state — the held-turn fallacy §0h had already disproved. One-step navigability says every
successor is reachable from every state, so the filter could only discard real exits and the
cycles through them. **Every `EdgePrice` figure on record — `gain`, the best cycle, the bias
`h(e)` — was measured through it and needs re-measuring.**

### Open questions put to the user

1. **Does `nextDecision` tick the flock, and if so where does the context come from?**
   `Sim.State` is a context-free holder — `n, x, y, h, tick, score, boidScore, label,
   psyboidOverrides` — with no reference to the map, the facts or the engine, and that is
   load-bearing at several dozen call sites. `state.nextDecision(...)` needs all three.
2. **Can two gates fire on the same state**, and if so does the `Decision` carry both option sets?
   The sketch reads SHORTCUT as taking priority over PSYBOID_EXIT.
3. **What is `Windows.inPhase`?** It gates whether a shortcut is worth considering and is the only
   part of the sketch reaching outside the decision machinery.
4. **How is a decision instance identified in a plan** — `(gate, nth trigger)` or `(gate, tick)`?
   The ordinal is warm-up independent; the tick is not, which is what made the retired labels
   only meaningful alongside their recipe.

## 1. Critical-envelope analysis — redesign — **priority one**

Specified 2026-08-28, **built 2026-08-29** as `CriticalEnvelope`, driven by `SimTest.envelope`,
diagnosed by `SimTest.chains` and `SimTest.steeringHistory`, and persisted by
`CriticalEnvelopeStore` into `<ingest>/envelope/`.

**Tables are built once and shared.** `ExitAudit.Tables` holds the loaded pairings, is immutable
after construction and carries no per-run state, so one build serves every thread; an
`ExitAudit` is a cheap per-thread wrapper around it. That matters because the audit is meant to
be called often and concurrently, which is also the reason it does no geometry of its own.
Measured: **101 s to build two arcs, 0 s to load them**, with bit-identical results.

Outstanding: the out-of-range collapse under "Cost", and the full minimal-subpath set (a
shortest leader path is recorded, which is minimal among those found but is not the whole
minimal set).

> **Arc `4->0` builds in 64 seconds.** It previously did not finish at all — a run was killed at
> 2h46m and 8.3 GB — and the cause was a one-line defect rather than the problem being large:
> `rejected` was written on every exhausted search and then only ever consulted for the
> *starting* pair, never inside the BFS, so six million cached rejections pruned nothing and
> every search re-expanded ground already proved barren. Reading the memo inside the loop fixed
> it. `BUDGET` is 12M, largest component seen 2,489,608.
>
> The lesson generalises: **a negative cache that is written and not read looks exactly like an
> intractable problem.** `CriticalEnvelope.analyse` now reports progress every ten seconds, which
> is what made this diagnosable — before that, a working build and a hung one were
> indistinguishable.

### What it produces, and what it is not

Critical-envelope analysis (CEA) generates **its own two-boid data**. It is not driven by
`TwoBoid`, and it must not be: `TwoBoid` enumerates arrangements a *run* can actually reach,
and using that as the leader domain would over-restrict where a leader could have been coming
into the turn. CEA admits **any navigable position** for the leader.

Its output is **annotated paired-state tables for `ExitAudit` to consume** — states, not
`(edge, tau)`. The translation into tau happens later, when solver windows are built.

### The envelope, redefined

Today the suspect side is a crude approximation: unsteered travel from the start of the edge,
intersected with an expanded band of critical states. Replace it.

> **The envelope is the set of states that reverse-navigate from the exit edge.**

Two properties make this the right object:

- **It terminates.** Unsteered navigation from the start of an edge never leads to a
  non-straight transition off that edge, so the closure cannot run back to the edge's start.
- **It is complete.** Every boid that exits is guaranteed to have been steered onto a state in
  the envelope. Nothing that exits can avoid it.

It must also include **states on the downstream edge one tick in from this one**, because the
first tick of steering can land directly on the downstream edge when the cost to leave is
exactly 1.

> **That downstream part must count every way across, not only the straight one** — found by a
> corpus run on 2026-08-29. Seeded from straight successors alone, a boid whose coasting
> successor stays on this edge but which turns once and is immediately on the exit edge never
> stands on an envelope state at all, and the exit escapes the net completely: four of six
> exits on arc `2->1` were escaping exactly this way. Straight crossings still define the
> envelope's *front*, whose population is the edge's follow-through count.

The closure is over **unsteered predecessors**: an unsteered predecessor of `S` is any state
from which *straight steering* would bring the boid to `S`. **Steering means the intended
direction, before the physics veto** — so a state whose straight request the veto turns is
still an unsteered predecessor of wherever it actually lands. `NavMap` already has the
predecessor and successor helpers. The unsteered predecessors of an exit are expected to be
fairly local.

### Entry onto the envelope is always a steered move

Free, and worth stating because it is what makes detection trivial: if unsteered travel from
`s` lands in the envelope, then unsteered travel from `s` reaches the exit, so `s` was already
in the envelope. **A boid can therefore never enter the envelope by an unsteered move**, and
`ExitAudit` needs only to watch envelope membership flip false→true.

The converse does **not** hold. Entering the envelope is not committing: a boid that has
entered can be steered the opposite way and leave again. That is difficult on dabeone and
plait, but a psyboid is always capable of it.

> **The actual commit boundary is the edge boundary itself.** The edges were designed to force
> that equivalence, which is why the exit is *reported* at the crossing.

### The analysed moment

**The tick at which the boid navigates onto the envelope from a state off it.** Not the tick it
crosses onto the exit edge. That is the moment the steering decision is visible, and it is
where the leader question is asked.

Only the *final* entry onto the envelope is analysed — see the reversal case above. Reaching a
second entry requires multiple complete reversals from right to left steering and is unlikely
ever to arise outside deliberate psyboid behaviour.

### Output shape

A list of window properties, each paired with the **dual states at envelope entrance**: a
mapping from the **boid's state immediately before it moved onto the envelope** to the **set of
leader states** consistent with that entry. Structure is an implementation choice; the content
is that pairing.

**Admission criterion.** A `(boid, leader)` pair enters the table only if the pair can
**reverse-navigate back to a settled state for the exiting boid**, where forward navigation
follows the `TwoBoid` rules — leader has free choice, exiting boid follows ordinary boid logic.

> ⚠ **Admission is where the corpus residue comes from, and it is stricter than the physics.**
> Measured 2026-08-29 on the eight exits the tables did not explain: the suspect's entry state
> was present in **8 of 8**, and in **7 of 8** a neighbour that alone produces the turn was
> nonetheless absent from the table — rejected by admission. Only one of the eight is genuine
> superposition.
>
> The reason is structural. Admission asks whether *those two boids alone* could have reached
> the arrangement; the run had four, and **a four-boid run reaches arrangements no two-boid
> history can**. Seven of eight had two neighbours inside `rSep`, which is precisely an
> arrangement the second boid created.
>
> There is also a mechanism that no pairwise table can hold at all: separation sums its
> neighbours before normalising and emits one unit vector at `W_SEP`, so two close boids produce
> a single push along their **vector mean**. That direction belongs to neither of them
> individually.
>
> **Largely answered by letting the boid choose between two physics** — see the section below.
> Admission relaxes exactly where it was too strict, and the corpus residue falls from 8 in 553
> to **2 in 553 (0.36%)**. Five of the six recovered were entries that were always admissible
> under the true constants and whose *history* needed the second model at one step.
>
> **The two survivors are a baton pass, and are unclassified by design.** Diagnosed 2026-08-29
> with `SimTest.steeringHistory`, which reports what *every* neighbour accounts for tick by tick
> rather than one chosen leader. Over the 24 ticks ending at entry, one boid at 48–52 px against
> `rSep` = 50 accounts for the early ticks through alignment and cohesion, a second at ~8 px
> accounts for the late ticks through separation, the two coverages overlap for three ticks, and
> **no single boid accounts for the whole window** (20, 21 and 14 of 24).
>
> A pairwise table holds one leader for an entire history, so this is not a cover that is too
> narrow — it is **a window that exists only in three-boid space**, which no setting of any
> two-boid constants reaches. These stay unclassified until multi-boid classification exists, and
> the residue of **2 in 553 (0.36%)** is a characterised limit rather than an unexplained
> remainder.
>
> When multi-boid classification is taken up, the overlap is the lever: the handover needs no
> unexplained step, so "the leader may change at a tick where both account" is a far tighter rule
> than free switching, and both of these fall to it.
>
> **Arc `4->0` is the same phenomenon and much more common — 20 of 355, 5.6%.** All twenty are
> multi-leader over a 70-tick window, none has a single boid covering it, and 19 of 20 reach
> settled ground so the question is well posed. *A 24-tick window says the opposite for half of
> them*: edge 4's unsteered chains run to 48 ticks, so a short window catches the history before
> the handover. Set the lookback from the edge's chain depth.
>
> **The two arcs fail for opposite reasons, and that predicts where the residue will be worst.**
> `2->1` is separation-carried (+90 to +117, two boids inside `rSep` in 7 of 8); `4->0` is
> alignment-carried (+20 to +70, *no* boid inside `rSep` in 14 of 20). Alignment is long-range,
> weak and diffuse, so several boids each contribute a little and which one leads shifts easily
> along a history; separation is short-range and decisive, so one boid holds a whole stretch.
> **Expect multi-leader residue to be worst on whichever arcs are alignment-carried.**
>
> This is also why the diluted model helps `2->1` and does nothing here: it zeroes alignment, so
> it has nothing to say where alignment is the whole story. It is **inert rather than weak** —
> with nobody inside `rSep` its desired vector is exactly zero and every neighbour reads `+0`.
> A fallback aimed at alignment would be a different object, not a retuning of this one.
>
> ⚠ **The residue rejects genuine led exits, which is worse than its size suggests.** Drawn at
> map scale (`SimTest.renderTick`), the exit at tick 9489 on seed 9 is a textbook one: boid 0 was
> 42 ticks *ahead of the suspect on edge 4*, took the `4->0` exit itself, and the suspect followed
> sixty ticks later. It is refused because boid 0 sits at 148.2 px against an `rFlock` of 150 —
> on the perception boundary — and alone loses to straight by 2.15 inside a 3.4375 bias. The
> across-component that turns the boid comes from a third boid on edge 2 across the map, which
> works only because boid 0 cancels *its* cohesion.
>
> **The solver's whole argument is "a boid at the front had nothing to follow".** An unexplained
> exit therefore reads as evidence toward the suspect, so a residue concentrated on real
> leader-follower pairs is a wrong answer in a specific direction rather than noise. Weight that
> above the 5.6% when deciding what to do about it.
>
> Note for whoever picks the fallback back up: **halving the straight bias would not have caught
> this one** — boid 0 needs the bias under 1.29 and half is 1.72. A `wCoh = 0` model does, by
> crediting the third boid. Checked at one tick only.

### "Settled" states

The predicate the backward search terminates on. **Called *settled* here to avoid colliding
with `stable[e]`**, which is an edge property meaning unsteered travel returns to it; this is a
property of a state.

The construction is **`{closure, partial tick, closure}`**:

1. Forward-close the union of all unsteered paths through the edge.
2. Apply **one partial tick** — turn as normal, but advance only partially, landing on any of
   the intermediate sample points from the out-of-bounds check that builds the navmap rather
   than on the endpoint. States carry the post-turn heading.
3. Forward-close again.

**What the partial tick is for: phase alignment.** Boids do not stay evenly distributed in tau
modulo the step length. Four boids bucketed `1111` can clump to `0202` after travelling around
obstacles, and states then get missed because they are crossed *between* ticks despite lying on
the path of unsteered travel in the general sense. The partial tick recovers them. This is the
same phase artifact documented in `EDGES.md` §8, in the one place where the fix is to model the
sub-tick position rather than to distrust the result.

**Applied exactly once, deliberately.** Repeated partial ticks would let a boid "strafe" at up
to 45° off its heading by chaining one-pixel steps. Once, sandwiched between two closures,
captures the entire set traversable with a single partial tick and nothing more.

It moves away from discrete calculation, which is a cost. It does work.

### Recorded per pair, from the backward navigation

- **The leader's edge path** — normally one edge, sometimes more. Among all paths leading to the
  same `(boid, leader)` envelope entrance, keep only those **minimal under the subpath
  relation**: discard any path that strictly contains another possible path.

### Recorded per pair, at the moment of entry

- **Boid and leader positions immediately prior.**
- **Cause: `SEPARATION` or `ALIGNMENT_AND_COHESION`.** Expected to be sharply bimodal under
  almost any measure. The most robust one: of the two influence sets, subject to their range
  limits, take whichever has the **larger component orthogonal to the direction of travel with
  the sign matching the turn being executed**.

### One run, two physics the boid may choose between

**Superseded 2026-08-29: not a second run at half straight bias.** Building a whole second table
under widened constants turns every marginal straight into a turn along the entire backward
path, and that is the wrong shape as well as the wrong size — a real multi-boid arrangement
requires some precise subset of those straights to become turns, not all of them. A uniformly
widened run cannot express "the crowd tipped it here and not there".

> **The boid may choose either physics at each step, independently.** The leader keeps its free
> choice of steering; the exiting boid follows boid logic under whichever of the two models it
> likes on that tick. The admitted set is the set of pairs reachable that way.

The second model is **`(wAli = 0, wCoh = 0, wSep x2)`**, not half straight bias. The reasoning
is about which way the terms move when boids are added:

- For a separation-dominated leader, **alignment and cohesion are obstacles** — cohesion pulls
  along the line to the neighbour where separation pushes back along it.
- Extra boids **dilute** alignment and cohesion, because each term is summed over neighbours and
  then normalised, so a spread of neighbours partially cancels.
- Halving the straight bias **amplifies** alignment and cohesion instead, which is the opposite
  of what a real crowd does. Zeroing them models the dilution.

It also keeps the fallback targeted: with alignment and cohesion at zero the second model cannot
manufacture new alignment-dominated admissions, only separation-driven ones.

**One table, entries tagged by which model produced the entry turn.** Entry under the true
constants ranks as {@code ENVELOPE}; entry that needed the diluted model ranks as
{@code ENVELOPE_WIDENED}. That preserves the reason ordering in §2 without paying for a second
full build, which matters at thirteen minutes a pass.

### Cost — bounded in depth, not in breadth

**Every state by which a boid enters an edge is settled**, so the search terminates within the
edge and needs no tick budget or depth cap. Depth was never the problem. Measured 2026-08-29 by
`SimTest.chains`:

- **Unsteered backward chains terminate fast.** Longest 25 / 48 / 35 ticks on edges 2 / 4 / 5,
  mean 2.92 / 7.12 / 7.19, and about a fifth of unsettled states have no unsteered predecessor
  at all.
- **They never reach settled ground.** 0 of 10,814, 0 of 10,100, 0 of 8,252. This is structural:
  *settled* is an unsteered forward closure, so it is closed under coasting, so its complement
  is closed under coasting backwards. **A boid off the coasting tube cannot rejoin it by
  coasting** — only a steered step gets there, and a steered step needs a leader in range.
- **Breadth is the cost.** Backward closure over every predecessor covers the whole edge —
  15,361 of 15,361, 12,471 of 12,471 — so the pair space is 1.7–2.1e9. Unpruned, arc `4->0` had
  not finished after fifteen minutes at 8.2 GB.

Two things follow, one built and one not.

**Built: solve the component, not the pair.** Stopping at the first settled state resolves one
pair and leaves everything else the search touched unresolved, so the next candidate rewalks it.
Instead each search exhausts its component and then propagates *reaches settled* back out from
the terminals, so every pair it touches comes out resolved either way. Total work over an arc is
one sweep of the union of the components rather than one sweep per candidate.

**Specified, not built: collapse out-of-range leaders.** When the leader is beyond `rFlock` it
steers nothing, so the boid coasts and *which* out-of-range state the leader occupies makes no
difference to it. Representing all of them as one abstract state is therefore an **exact
abstraction rather than an approximation**. Re-entering range going backwards then only has to
consider leader states within a step or two of the boundary — a thin shell rather than the whole
map. The measurements above say this is exactly where the blow-up lives.

Until that exists, `CriticalEnvelope.pruneOutOfRangeLeaders` makes the search tractable. **It is
off by default and it is an approximation.**

It prunes with a **grace period** rather than at the range boundary, because the boundary case
that matters is two boids running just off parallel, which stay near each other for a long time.
Prune when

```
speed * pi/64 * (d_leader - d_boid)^2  +  (distance - rFlock)  >  2 * speed
```

— both terms in pixels, the first a small-angle estimate of ground given up to divergence, the
budget two ticks of travel. That is a shell about eight pixels deep for parallel headings, closing
entirely once the headings differ by seven steps.

**Cost, measured 2026-08-29.** It recovers real histories at roughly two to four times the time,
and it finishes:

| arc | hard cutoff | grace period |
| --- | --- | --- |
| `2->1` | 7.0 s, 291,337 pairs, 52 entry states | 15.3 s, 389,302 pairs, 67 entry states |
| `4->0` | 186 s, 85,969 pairs, 40 entry states | 754 s, 117,946 pairs, 59 entry states |
| `5->6` | 4 s | not measured |

Edge 4 is the worst case: deepest unsteered chains and only 19% of it settled, so the shell adds
leader states at every step of a long walk. Thirteen minutes for all three arcs is tolerable for
a table built once per map version — but the tables are not yet written and reloaded, so every
run that constructs an {@code ExitAudit} pays it again. **Persisting them is the cheap next
step; the exact collapse above is the one that removes the cost rather than amortising it.**


## 2. `ExitAudit` — respecify and rebuild

`ExitAudit` is the **exit classifier**: the omniscient counterpart to the solver, with full
information about a run. It is the ground truth everything else is measured against.

> **Rebuilt 2026-08-29 to the specification below.** It is now pure table lookup against
> `CriticalEnvelope` with no geometry of its own, reports at the crossing and attributes at the
> envelope entry, and no longer reads `SolverFacts` at all — so the circularity is gone.
> `SimTest.census` is the corpus test. First result, 8 seeds x 20,000 ticks warmed 1,000 with no
> psyboid and level 3 unchecked: **15 exits, 13 explained, 2 unexplained, 0 without an envelope
> entry.** Both unexplained are boid 0 on arc `4->0` from a state six explained exits also cross
> from, so the entry state is in the table and that particular leader pairing is not — either
> multi-boid superposition or a history the range pruning dropped.

### What it is for

Note **exactly when an exit occurs**, and propose the **most plausible mechanisms** for it.
Where several mechanisms are genuinely available, report them all — some consumers need to
know that a psyboid took an exit it did not need an override to take.

### What it may guarantee

Only two things, and they are properties measured over a corpus rather than promises the
function makes about itself:

1. No reasonless exits were found in the corpus.
2. An attempt was made to order the reasons from highest to lowest reliability.

It must **not** write reckless contingencies using its own geometric reasoning in order to
manufacture a candidate. Testing establishes that at least one candidate is always generated;
the function does not guarantee it.

### Anchoring

An exit is **reported** at a single tick: the tick on which the boid actually leaves the edge,
having started that tick on it. Because of how edges are defined, this is the same tick on
which the choice of destination edge is locked in.

It is **attributed** at a different moment — the tick the boid moved onto the critical envelope
from a state off it (§1). The influence and the crossing are not the same event and generally
not the same tick.

So `ExitAudit` must track two things per boid:

- the **most recent state at which it moved onto the critical envelope** from a state off it,
  together with the state immediately prior — for the boid and for every candidate leader;
- an **alert when the exit is actually taken**.

Attribution is then a lookup of that stored `(boid-prior, leader-prior)` pair in the CEA table.
No geometry.

### Reason ordering, highest reliability first

1. **The exiting boid is a psyboid.** Exits are assumed intentional.
2. **A critical-envelope match under normal physics.** If the suspect/leader state pair on the
   exit tick lies in the critical-envelope analysis, that is sufficient. This is where most
   labels should come from.
3. **A critical-envelope match whose entry needed the diluted model.** The leader credited is
   the one in the matched pair and the cause comes from the pair's own recorded label. One table
   serves this and level 2, tagged per entry — see §1, "One run, two physics".

Anything escaping this net is a finding, not a fallback. **Measured 2026-08-29 over a 40-plan
corpus: 553 exits, 2 escaped (0.36%).** Of the rest, 300 were under an override, 250 had a
leader under the true constants and 1 needed the diluted model at its entry. The residue is a
single arrangement found twice, and it is an admission rejection rather than a superposition —
§1 has the detail.

### Constraints

- **No geometry.** `ExitAudit` reads precomputed tables, for speed and accuracy both. Keep it
  that way unless the critical-envelope analysis has been exhausted and exits are still
  escaping.
- **Do not track the leader's approach sequence.** If the suspect/leader pair on the exit tick
  is in the envelope analysis, that is enough. Reconstructing how the leader got there is
  overkill.
- **Keep `EdgeNavigation.exitTurns`.** An exit is a property of the edge pair. The current
  working-tree change got that right and it recovered the ~30% of branch crossings the old
  `straightTo` filter silently dropped.
- **Restore ground truth.** Whatever replaces `inWindow`, `ExitAudit` must not derive its
  verdict from the same `SolverFacts` windows the solver reads, or `census` measures nothing.

### Schema gaps this exposes

Both block the specification above and should land with it:

- **Cause is not stored.** Bands carry no `SEPARATION` / `ALIGNMENT_AND_COHESION` field.
  `EdgeSlice.Band` is `(edge, lo, hi, count)` and `SolverFacts.Band` is
  `(tau, leaderEdge, lo, hi)`. Cause currently exists only as hand-written suffixes in
  `UnstableEdgeClue`'s window enum, and as `ExitAudit.Exit.separating`, which is permanently 0.
- **Modified-physics separation windows have nowhere to live.** `SolverFacts` holds a single
  `Window[]`, so the fallback set cannot be stored alongside the normal-physics set.

## 3. Re-establish the corpus

`ingests/609cffdb84be218c/psyboid/plans.tsv` holds 47 plans, each verified by replay. What is
not established is whether it is *adequate*: the previous corpus (`data/searches.tsv`) covered
no dab-like map at all, and grading on overrides invented inside the grading harness measures
the harness. Warmup is the known trap — 500 ticks is not enough, and 2,500 changed the
conclusion about whether windows pay.

Once items 1 and 2 land, re-run `census` and `graded` and treat every previously recorded
figure as provisional.

---

## Then: back to the solver

The work that was interrupted.

**`UnstableEdgeClue.PAYING` is empty by design, not by measurement.** The intent is that solver
windows are populated **only from a training corpus** — only windows that actually appear in
it, and only over the ranges that appear in it. Several windows CEA can derive will never show
up in any corpus, because reaching them requires counterproductive psyboid behaviour, and a
window that only covers behaviour no psyboid exhibits spends true negatives for nothing.

This is also why the tau translation lives here rather than in CEA: CEA emits state pairs, and
turning them into `(edge, tau)` bands is part of building solver windows from corpus evidence.

The structural fact that survives, because it is true a priori rather than measured: **a window
can only ever spend true negatives.** It admits every boid inside its band, not only the one
that actually led, so it has to buy a true positive to be worth anything at all — and how often
a window is the right answer says nothing about what it costs.

**Every earlier grading figure has been discarded**, in the docs and in the javadoc. They were
taken against a broken classifier and a corpus since found unsound, and they assumed neither.
The five windows currently in `UnstableEdgeClue`'s enum are hand-read placeholders whose bounds
came from that classifier and whose `SEP` / `ALIGN` suffixes were assumed, not derived.

Open questions inside this goal:

- Drift tolerances have not been set from the corpus. The windows saturate to whole edges if
  allowed to.
- Whether the window test is the right *shape*. A window asks "is a leader in the band", not
  "would that leader alone suffice" — a weaker and more appropriate question.

---

## Non-landmark work

**Extract the decomposition algorithm from `SimTest`.** ~~Done 2026-09-10~~ — `EdgeDecomposition`,
822 lines, holding the axiom and all six construction steps; `SimTest` keeps the driver and the
reporting. Verified a pure move: all three maps produce byte-identical labelling fingerprints, and
the gate split still settles nine of nine. `SimTest` is 2,501 lines and still holds fourteen entry
points. More generally: **descriptive class names, and a canonical name in `GLOSSARY.md` for any
analysis expected to be reused.**

**Rename `tick` → `tau` for position along an edge.** Agreed 2026-08-27. Affects
`EdgeMetric.Metric.tick()`, `SolverFacts.tickAt/tickOf/tickLo/tickHi`,
`ExitAudit.Exit.suspectTick`, and `EdgeSlice`. `Sim.State.tick` and `ExitAudit.Exit.tick` keep
their meaning as simulation time. Docs already use `tau`.

**Commit hygiene.** ~~Twenty commits named "Periodic check-in", and `git add` has never added a
new file.~~ Resolved 2026-08-29: every source file is now on GitHub, and the session landed as
one described commit. Worth keeping up — the previous history carries no information about what
changed when.

**Known robustness gaps.** `refine` gives up silently above 63 edges. Lifted weight balancing
does not fully converge (worst node flow ~0.05 after 2000 passes), and neither does the flat
one (~0.02–0.08); reported rather than assumed, but not fixed.

**Freely navigable edges** are unmodelled — an edge where a boid at any state can turn around.
Most machinery assumes monotone progress along an edge, so tau would not be monotone in time.
Dabeone and plait have none.

---

## Next eras

**Psyboid behaviour revisit.** The psyboid flies whatever `PsyboidBits` hands it. A
solver-driven project wants explicit criteria for what makes a psyboid *good* — not just
high-scoring, but producing a scenario that is solvable, unambiguous, and requires the
reasoning the evaluation is testing. A psyboid that parks in the scoring zone is trivially
visible; one that herds is the interesting case.

**Case generation.** Turning a chosen timeline into a case: photograph selection, rendering,
answer recording, and a shippable package. **The previous packet is retired, not paused** —
physics 2 invalidated all 21 labels and the artifacts have been deleted. Rebuilding is the
last or second-to-last step of the project.

**Blind tests.** Running the packet against models, including the training-wheels condition,
and measuring where they fail and why.
