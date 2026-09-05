# What is being built now

**Status:** 2026-09-04. `README.md` has the inventory; this file has the work in front of us
and the specifications for it.

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


## 0c. Shipping physics 3 — **blocked on one defect**, and what artifact storage should look like

Asked 2026-09-04: ship `RULE_SUM_CLAMP` project-wide, audit for reproduced steering logic, and
clean up old artifacts. The audit is done and mostly clean. **The ship is held on a defect found
while preparing it**, and the cleanup question turns out to be the same question.

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

**So bumping `PHYSICS` to 3 writes physics-3 artifacts into `ingests/609cffdb84be218c/` beside the
physics-2 ones.** What happens then, store by store:

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

### Artifact storage: what I would do

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

**I would take the three-tier split**, and take option 1 first if the ship should not wait for it —
they compose, since option 1's new ingest is the same map hash the tiered layout would sit under.

### What is done and what is held

Done and committed: the audit, the accidental-copy fix, and the guard that already catches a
mismatched closed form.

**Held pending a decision on the layout:** the `PHYSICS` bump, the `MovementLogic` default, the
`Flocking.of` default, the re-ingest and the rebuild. All four are small; none is safe until an
artifact written under physics 3 cannot be read as though it were physics 2.

---

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

**Extract the decomposition algorithm from `SimTest`.** `SimTest` is 2,573 lines holding 15
entry points *and* the whole decomposition. The algorithm should be its own class with a
descriptive name; `SimTest` should be the driver. More generally: **descriptive class names,
and a canonical name in `GLOSSARY.md` for any analysis expected to be reused.**

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
