# Session log

What each session attempted, what the numbers were, what changed, and what is now known
broken. **Newest entry at the top. Append before the session ends.**

Conclusions belong in the document that owns them — `README.md`, `EDGES.md`, `ROADMAP.md`,
`HINTS.md`. This is the audit trail, not the state. If an entry here is the only record of a
finding, it is in the wrong place.

Format: date, what was attempted, what came out, what changed on disk, what is open.

---

## 2026-09-04 (later) — asked to ship physics 3; audited, then stopped on a storage defect

**Asked:** ship `RULE_SUM_CLAMP` project-wide, look for places the steering logic was reproduced
rather than used in place, and clean up old artifacts. Full write-up in `ROADMAP.md` §0c.

**The audit came out well.** `TwoBoid` drives the real `MovementLogic` on a two-element array and
documents why, so it needs no change. `CriticalEnvelope` reproduces nothing, reasoning entirely
through `EdgeInfluence.steer` — which *is* a reproduction, deliberately, and is now coupled to the
aggregation through `Flocking.sepFalloff` and asserted equivalent at one neighbour. The only
accidental copy was **mine from last night**: `AggregationSurvey.see` had its own range-and-FOV
test instead of calling `MovementLogic.perceived`. Fixed; fidelity still 0 disagreements and the
survey numbers are unchanged, which is what a faithful copy should do.

**Then the ship stopped.** `GLOSSARY.md` claimed `Params.PHYSICS` is part of the ingest hash.
**It is not.** `MapStore.open` names the folder `digest(img, shown)` — source and display pixels
only. `Build.key()`, which carries `p<PHYSICS>`, is the in-process cache key and nothing else.
`MapStore.Build`'s own javadoc makes the same claim; it is right for radius and trap-trimming,
which change `shown`, and wrong for the physics version, which changes neither image.

So bumping to physics 3 would write into `ingests/609cffdb84be218c/` beside the physics-2
artifacts. `EdgeMetricStore` and `CriticalEnvelopeStore` survive it because they hash their inputs
into the filename — the envelope only by luck, since `sepFalloff` went into its key yesterday for
an unrelated reason. **`SolverStore` (`solver/facts.bin`) and `PsyboidCorpus` (`psyboid/plans.tsv`)
are fixed paths and would be silently overwritten and silently reread.** The corpus is the ground
truth the solver is graded against, and its rows are verified by replay when written and never
when read.

**The systemic version:** this project records the discriminating input in an artifact's *content*
and not in its *address* — physics in `meta.txt`, the gate in the edge graph's title, the config in
the corpus header. The two stores that hash inputs into the filename are the two that are safe.
The same defect means decomposing with a different gate overwrites `edges/` in place.

**Recommended layout**, in §0c: three tiers — map (pixels + build), structure (+ gate + scheme:
navmap, edges, clock, cost-to-leave, stable sets), behaviour (+ physics + flocking: envelope,
windows, twoboid, psyboid, solver, audit). Verified while running the proposal that nothing in the
structure tier reads a flocking constant. A physics bump then rebuilds only the behaviour tier,
cleanup is deleting behaviour directories, and nothing can be read against inputs it was not built
from. A one-line minimal alternative — put `PHYSICS` into `MapStore`'s digest — unblocks today and
composes with the tiered layout.

**Held, pending the layout decision:** the `PHYSICS` bump, the `MovementLogic` default, the
`Flocking.of` default, the re-ingest and the rebuild. All small; none safe until an artifact
written under physics 3 cannot be read as though it were physics 2.

**Not a concern, per the user:** the 80x diluted-model jump on `4->0`. Nothing suggests a bug.


---

## 2026-09-04 — arc `2->1` under the proposed physics 3

Second arc for the `RULE_SUM_CLAMP` proposal (`ROADMAP.md` §0b). `5->6` skipped by agreement:
cold-start only, so its behaviour under a physics change is not information anybody will use.
`SimTest.proposedPhysics` now names its output by the arc, so the two runs cannot overwrite each
other, and it picks the panel to draw by which one holds the most unexplained cells — the previous
hard-coded index picked edge 2's *smallest* panel and showed nothing.

**A cleaner comparison than `4->0`'s.** Stable+ restricted to edge 2 is unchanged at 9,284 states,
so both runs used the same starts and the sampler took an identical path — 2,576,562 cells from
1,599,436 simulations out of 26,794,174 attempts, in both. The only difference is the physics.

| | physics 2 | proposed |
| --- | --- | --- |
| envelope / pairings | 97 / 596,805 | 97 / 580,789 (down 2.7%) |
| exits | 62,980 | **79,293 (+25.9%)** |
| psyboid-led / third-led | 51,239 / 11,496 | 68,071 (+32.8%) / 11,039 (−4.0%) |
| unexplained | 245 — 0.39% | 183 — **0.23%** |
| diluted | 28 | 33 |
| clumps of 40+ | 0 | 0 |

**Classification barely moves and exit frequency moves a lot.** 4.49% of accounted cells changed,
**none to unexplained**, and 125 of 62,707 swapped leader (0.20%); at block scale 96.6–98.0% keep
their dominant class, exit-rate drift 0.8 pp, white drift 0.01 pp. Against that, the arc is taken
a quarter more often, essentially all of it the psyboid getting better at inducing it — the outer
separation annulus sign reversal showing up as behaviour.

**`2->1` has no white structure to fix**: 245 cells across four panels and not one clump of 40+,
before or after. The banded white is a `4->0` phenomenon, consistent with §1 — `2->1` is
separation-carried so one boid holds a whole history.

**The 80x diluted-model jump is `4->0`-specific**, confirmed: 28 -> 33 here against 6 -> 479
there. Still unexplained, and now the only unexplained result in the proposal. Cheapest next
thing.

Comparison across both arcs and the remaining checklist are in `ROADMAP.md` §0b. Still not
adopted: `Params.PHYSICS` is 2 and `Flocking.of` returns `sepFalloff = false`.


---

## 2026-08-30 (following session) — one sample per marked region of the phase map

**Asked for:** a render of the critical-envelope entry tick for each region the user painted onto
the three-boid phase map between sessions, with a crop of the phase map inset so a tile can be
matched to its region, stitched into a single 4-wide sheet. Render only; the analysis is next
turn.

**Read the overlay first, rather than trusting the eye.** `analysis/3BoidAreasOfInterest.png`
turns out to be `render/phase40.png` with 14,783 pixels repainted and nothing else changed. Two
colours, both MS Paint defaults: **rose `#FFAEC9`** (11,767 px) and **green `#22B14C`** (3,016
px). What they cover is not uniform:

| paint | covers | count |
| --- | --- | --- |
| green | psyboid-led amber | 3,016 (100%) |
| rose | unexplained white | 10,598 |
| rose | third-boid-led cyan | 1,164 |
| rose | psyboid-led amber | 5 |

The five rose-over-amber pixels are the whole of regions `R05` (1 cell) and `R06` (4 cells), which
is why they are probably overspill rather than marks. The renderer's own green `0x66C070`
(`Level.PSYBOID`) appears **zero** times in the base, because the suspect is index 2 and is never
the overridden boid, so the hand green collides with nothing.

**Built `ThreeBoidSamples`.** Reads the overlay against the base, recovers each painted patch as a
connected component joining cells within 6 (paint lands only on cells of the class being marked
and those are dithered, so a stroke arrives as a scatter), picks the cell nearest each region's
centroid whose recorded account matches the class painted over, replays it from
`phase40-replays.tsv`, and draws it at envelope entry via `ExitRender` with a 200-cell crop of the
marked map inset. **20 regions, 21 tiles** — green sampled twice, split by override —
`render/phase40-samples.png`, 2842x5198.

**Every replay reproduced the account recorded for its cell.** No `[REPLAY DISAGREES]`, no skips,
no region without a replayable cell of its own class.

**The first sheet was unreadable and the reason is worth keeping.** `ExitRender` crops to the
flocking radius, which on dabeone is 150 px against a 379x407 map — so the crop is very nearly the
whole play area and three boids in it are three specks. Twenty-one tiles of the same grey map.
Fixed by ringing each boid in its role colour, reusing the phase map's own amber/cyan so
"psyboid" and "third boid" mean the same colour in both pictures. `ExitRender` gained a published
`Frame` so a caller can annotate without restating the crop arithmetic.

**Two supporting changes.** `ThreeBoidPhase.layout` extracts the panel geometry `draw` computed
inline, so reading a cell back to a pixel runs the arithmetic that drew it; and its palette is now
named constants rather than literals, since the picture is also an input. The title bug flagged at
the start of the session is fixed: `%.0f` rendered resolution 0.5 as **"1 tick(s) per cell"**, and
every panel dimension says it was 0.5.

**`.gitignore` now versions the overlay.** `analysis/` is ignored as stale derived output, but a
region overlay is an *input* — it is what a region detector will be scored against and it cannot
be regenerated. `analysis/*` plus a negation for the one file.

**Open, and for the user to settle:**

- `R01`/`R03` are 13 cells apart in the same x-range, and `R04`/`R05` 4 cells apart. Above the
  6-cell join threshold, so they are separate regions here, but close enough to be one stroke.
- `R05` and `R06` may be overspill onto the amber band rather than marks.
- The overlay is bound to one rendering of the phase map and only its *dimensions* are checked;
  a sampling change that kept the size would silently rename every region.

**Then: why `R20` has no account.** Asked because two cells looks like a single-leader window
missed by a whisker. Built `ThreeBoidSamples.explain` — `steeringHistory` for a phase-map
arrangement — and the guess does not survive it. The suspect *does* reach settled ground (it is
settled through tick 13) and its entry state *is* in the tables. The account is not narrowly
missing; it is split between two boids whose coverages **abut without overlapping**.

| ticks | what happens | who can explain it |
| --- | --- | --- |
| 0–12 | coasting on settled ground | free |
| **13–17** | a `-1` off settled ground | **third boid only**, 149 px against `rFlock` 150 |
| 18–40 | the veto overrides every request | free |
| **41–45** | the `+1` onto the envelope | **psyboid only**, closing 94 → 82 px |

Raw coverage says 28 of 33 each and a 23-tick overlap, which reads like an easy handover. **Ten
of the 33 ticks actually demand a leader, five each, intersection empty.** The difference is the
collision veto: on ticks 18–40 the map refused the request and every turn collapsed to the same
successor, so any neighbour — including one 230 px away contributing nothing — "accounts" for
those ticks.

That is a defect in a measurement this project already quotes. `SimTest.replayHistory` uses the
same test, so the "20, 21 and 14 of 24" coverage figures in `ROADMAP.md` §1 are inflated the same
way, and **"the two coverages overlap for three ticks" — the observation the proposed handover
rule rests on — needs rechecking on demanding ticks.** The multi-leader conclusion is unaffected:
free ticks only add coverage, so a failure counted with them in the candidate's favour is a real
failure. Reported and not fixed, per the rule about not replacing an existing answer in the turn
that finds it. Written up in `HINTS.md` §10a and `ROADMAP.md` §0.

Also noticed, untested: the prune marks CUT on one of the two neighbours for almost the whole
window, the two swapping at tick 32. Whether admission would find a psyboid history with
`pruneOutOfRangeLeaders` off is unknown, and needs the exact out-of-range collapse §1 lists as
unbuilt.

`render/phase40-R20-approach.png` draws the four moments side by side. `CriticalEnvelope`'s
`unrecoverable` is package-visible now so the diagnostic can report the real prune rather than a
copy of it.

**Then: stable+, specified by the user and built behind an interface.** The point: admission
terminates on *settled*, which is what a boid **alone** can hold, and no boid in a scene is alone.
`StateSet` is the algebra (`partialTick`, `closed`, `expandByAgreement`), `MapStates` binds it to
a map and supplies `pureStable` and the straight-travel cycles. Interface first because the
definition is expected to change.

`pureStable(1)` on dabeone is **278 states in exactly one loop** — the user predicted one to four
— covering edges 2:96, 4:93, 7:89, which is one lap of `2 → 7 → 4 → 2`. Its 278 ticks sit against
the clock's independently fitted 275.29 for loop `[4, 2, 7]`. `.partialTick.closed` takes it to
**1,610**.

Ratio sweep, `expandByAgreement(pureStable(1), r)`:

| ratio | quorum | states | edges |
| --- | --- | --- | --- |
| 2, 4 | 139, 69 | 1,610 (no growth) | 2, 4, 7 |
| 8 | 34 | 7,044 | 2, 4, 7 |
| 14 | 19 | 13,322 | 2, 4, 7 |
| **16** | **17** | **13,624** | **2, 4, 7** |
| 18 | 15 | 14,687 | + 0, 3, 5, 8 |
| 20 | 13 | 15,382 | + 0, 1, 3, 5, 8 |

**16 is the largest ratio that adds no edge, and 18 leaks onto four at once** — a sharp boundary,
so the user's own criterion picks the parameter rather than a taste call. The ratio-8 guess works
in the sense that it stays on {2, 4, 7}, but it is too strict to matter for `R20`.

**And it settles `R20`.** At ratio 16 the suspect is on stable+ through tick 13, off 14–24, back
on **25–41**, off for the last four. The window a leader must cover becomes **41–45 rather than
13–45**, and the psyboid alone covers all five, every one demanding. `R20` is a **one-leader
exit** once stable+ is the ground, with the leader on **edge 2**. At ratio 8 it is not — the path
is still last on stable+ at tick 13, so 8 is a false negative here.

Measured on the flown history only. Making it a classification means giving
`CriticalEnvelope.admit` stable+ instead of `settled` as its terminal set, which is not done.

Also settled a detail of the user's description: the two neighbours are on **edge 7** (the one
that knocks the suspect off stable at tick 13) and **edge 2** (the leader, 41–45). Neither is on
edge 3.

**Then: stable+ respecified to a single quorum.** The user's own diagnosis — the ratio was
controlling three numbers at once, and the result was left not closed under straight travel.
Rewritten: one quorum `|influencers| / agreementRatio` gates starting *and* continuing a turn,
nothing gates ending it, and every state along the turn is added together with its straight
future. The set is closed at all times, so adding is a walk down straight successors that stops
the moment it meets covered ground.

**The prediction that buys, tested across the range: no exits at any quorum of 2 or more, exits
onto edges 0, 1, 3, 5, 8 at quorum 1.** The user expected quorums above 2 to be safe; the boundary
is one lower. Sizes: quorum 34 → 6,300; 17 → 13,515; 8 → 14,966; 5 → 15,225; 2 → 15,413; then a
3,000-state jump to 18,358 at quorum 1. `render/stable-plus-by-ratio.png` draws each, projected to
`(x, y)` and coloured by headings-per-pixel, via the new `StateSetRender`.

**Cost to leave, with stable+ substituted — and a premise corrected.** The figure the edge graph
publishes reduces over each edge's **inbound points** (618 on edge 4, 382 on edge 2), not over
`pureStable(1)` (93 and 96). Not the same set, and they agree on `4->0` (both min 8) but not on
`2->1` (7 against 8).

| source set | `4->0` | `2->1` |
| --- | --- | --- |
| inbound points, published | 8–8 | 7–8 |
| `pureStable(1)` | 8–10 | 8–16 |
| map-wide stable | 6–15 | 7–19 |
| **stable+, quorum 2..17** | **5**–15 | **6**–19 |

**`4->0` falls from 8 to 5, `2->1` from 7 to 6**, and the minimum is flat across the whole safe
quorum range — a number that does not move over an order of magnitude of the free parameter.
`EdgeNavigation.steerCostTo` was added to hand the per-state cost array back unreduced, so this
runs the traversal `analyse` already has rather than a second copy.

Under the new rule `R20` still needs quorum 17 or lower to come back onto stable+ at tick 41;
at quorum 34 it is still last on it at tick 13, as before.

**Then: the definitive stable+, and the phase map rebuilt on it.** Quorum named outright rather
than derived from a ratio, and the partial-tick correction applied again after the expansion:
`pureStable(1).partialTick.closed.expandByQuorum(pure, 5).partialTick.closed`. **19,861 states**,
edges 2:9,284 4:3,840 7:6,706 8:31.

**The shape formula disagrees with the quorum it was meant to derive.** `|pure| = 278`,
`|pure.partialTick| = 1,040`, spread **3.74** — which is the four offsets the intent expects, but
rounding *down* to a power of two takes it to 2 and the formula returns **9**. Rounding to the
*nearest* power of two gives 4 and hence 5. Used 5, since it was named explicitly; the driver
prints both and flags the disagreement.

**Stable+ does not contain settled.** Edge 4: 2,371 settled, 3,840 stable+, only **1,065 in
both** — settled is seeded from the edge's entrances, which the straight-travel loop never
touches. So admission's ground is their **union** (5,146 states); stable+ alone would have refused
histories the old tables admit. `CriticalEnvelope.analyse` takes a ground set now,
`CriticalEnvelopeStore.FORMAT` is **2**, and the ground is fed into the cache key.

**The result, with the two changes separated.** Same seed, same 3,840 starts, same 491,449 exits
over 7,080,249 cells in both runs, so the only difference is the ground:

| run | starts | ground | unexplained |
| --- | --- | --- | --- |
| previous era | settled 8-tick band | settled | 108,350 / 516,139 — **21.0%** |
| control | stable+ on edge 4 | settled | 164,523 / 491,449 — **33.5%** |
| **on stable+ throughout** | stable+ on edge 4 | settled ∪ stable+ | **14,923 / 491,449 — 3.0%** |

Widening the starts alone makes it **worse** — 21.0% to 33.5%, exactly the chaos the user
predicted when asking for it. Widening the ground then takes 33.5% to **3.0%**, an eleven-fold
cut. `render/phase40-stableplus.png`, `-control.png`, and a `-replays.tsv` beside each.

**Also flagged, not chased:** the trailing partial tick puts 31 states on **edge 8**. The
expansion alone stays on {2, 4, 7} at every quorum down to 2, which is how the parameter was
picked, so the closing correction breaks that property. Probably an edge-labelling effect at a
boundary — a partial tick lands mid-step and the intermediate pixel can carry another edge's
label.

**Open:** the 3.0% is uncharacterised, and the twenty hand-marked regions have not been re-read
against the new map.

**Then: the white census of the centre panel, found rather than painted.** `ThreeBoidSamples`
gained `features` (clumps of one account, found in the picture), `bands` (which share a range on
one axis), `windowFit` (densest window of a claimed size), `atlas` (each clump cropped in place),
`sampleFeatures` (one replayed arrangement per clump) and `classify`.

**3,777 unexplained cells in 20 clumps of 40+**, plus 102 smaller ones holding 385 cells. Three
shapes:

- **slabs** on the third-boid band — horizontal, dense. Best 38x12 window: W12 52%, W06 50%,
  W13 42%, W11 33%, W08 29%, W09 29%. The 38x12 estimate describes this family well.
- **columns** on the psyboid band — the same transposed. Best 12x38: W17 60%, W16 34%, W03 33%,
  W19 28%, W15 25%, W20 25%, W02 20%.
- **clouds** — no dense core either way: W01, W04, W05, W07, W10, W14.

**They sit on a lattice.** x 580..624 at y 151/314/377 (W06, W09, W12); y 373..396 at x 99/410/582
(W13, W11, W12); y 581..624 at x 72/311/369 (W18, W19, W17); plus two pairs. W12 is at the
crossing of the first two and is the densest clump on the panel.

**`classify` answers the question behind the request, and the answer is no.** Over the window from
the last tick on stable+ to the envelope entry, counting only demanding ticks:

| verdict | count |
| --- | --- |
| single — one boid explains all of them | **0** |
| split — the two between them, neither alone | **9** |
| UNCOVERED — a tick neither alone reproduces | **11** |

**Not one of the twenty is a one-leader exit at any constants**, so a modified physics would not
catch them. And the verdict tracks the shape: **all six clouds are UNCOVERED**, while slabs and
columns are 9 split to 5 uncovered. W07 is the extreme — of its 5 demanding ticks neither
neighbour alone reproduces a single one.

One representative per clump, so these are samples rather than censuses.

**Then, overnight: a survey of how the neighbour signals are aggregated.** `Aggregation` holds
seven ways of condensing several neighbours into one desired direction, `AggregationSurvey` scores
them on the same sampled arrangements, `MovementLogic` gained a branch that flies one, and
`ThreeBoidPhase.compare` cross-tabulates the phase maps. **Nothing adopted; the default path is
untouched**, and `Aggregation.CURRENT` is checked against `MovementLogic` every run — 0 turn
disagreements over 20,000 arrangements, worst gap 7.1e-14. Full write-up in `ROADMAP.md` §0a,
transferable part in `HINTS.md` §10c.

**Three things about the current scheme that were not written down anywhere.** Cohesion sums raw
offsets rather than unit vectors. Separation's distance falloff is **inert whenever one neighbour
is close**, because normalising a single vector discards its length — it only ever shapes a
direction. And there is no magnitude bound and no inertia term downstream, so an amplified
direction goes straight into the turn choice.

**The defect, on 399,321 sampled arrangements.** Alignment cancellation median 1.41x, **p90
10.2x**, 10% above 10x. The pseudo-triangle rule holds for the current scheme in **64.5%** of
two-neighbour arrangements at k=1. Amplification p99 **4.14**.

**Results.** `VOTE_MEAN` (mean of per-neighbour votes) satisfies the triangle rule **100%** — and
provably, being a convex combination under a linear functional — with the lowest amplification
(p99 0.99) and jolt (1.61 against 3.85), and it is *cheaper*, doing no square roots against three.
`RULE_CLAMP_STEP` is the conservative option: identical for one neighbour, identical when
neighbours agree, **92.7%** agreement on three-boid turns. `VOTE_NORM` is the control and settles
the ordering question — summing votes and normalising the *total* is **worse** than today, so
moving the normalisation does not help and normalising at all is the defect. `VOTE_CLAMP` sums
rather than averages, is more decisive than today, and loses on every measure.

**On the phase map** (arc 4->0, same seed and starts, tables shared since three variants agree
exactly at one neighbour): unexplained rate 3.04% -> **2.15%** under `RULE_CLAMP_STEP` and
**1.37%** under `VOTE_MEAN`; centre-panel clumps 20 -> 17 -> 9. `VOTE_CLAMP` makes it worse
(3.06%, 25 clumps).

> ⚠ **Corrected while writing it up: the white does not become explained, it stops being an
> exit.** Only 2–3% of unaccounted cells acquire an account; a third to a half stop producing an
> exit at all. My first `compare` summed the two into one "resolved" figure, which was wrong and
> flattering. Leader *attribution* meanwhile is stable to ~0.1% — amber essentially never becomes
> cyan, so the accounted regions do stay put.

**The user's hypothesis is confirmed.** At envelope entry, unaccounted exits have alignment
cancellation above 4x **4.6x more often** than accounted ones (16.6% against 3.6%), and their
desired direction is at the median **1.88x** longer than the average of what their neighbours
individually asked for, against 1.21x. The residue is substantially a measurement of the
aggregation rather than of flocking.

**Checked against the field.** Conrad Parker's pseudocode and the Nature of Code both average by
neighbour count and bound magnitude once, on the total; the Nature of Code's
`steer = desired - velocity` under `limit(maxforce)` is a low-pass filter that a discrete-turn
formulation has no analogue of. **Per-rule normalisation with no downstream clamp and no velocity
state is the unusual part of this simulation**, not the three rules.

**Cost of adopting any of it:** a `Params.PHYSICS` bump, so every ingest, plan label and stored
table taken under physics 2 stops meaning what it means. Hence a survey first.

**Not surveyed:** only arc 4->0 on dabeone, and only the aggregation. Under a bounded aggregation
the signal is smaller, so the straight bias is effectively stronger — the two want tuning
together.

**Then: the proposed physics 3, run end to end.** The user picked `RULE_SUM_CLAMP` from the
survey — unit cohesion, **falloff separation**, each rule's sum capped at 1 rather than rescaled
to it — on the grounds that ignoring the falloff for a single neighbour is atypical and unhelpful.
**Not adopted; `Params.PHYSICS` is still 2 and `Flocking.of` still returns `sepFalloff = false`.**
Full evidence in `ROADMAP.md` §0b.

**The argument that came out of building it.** Under physics 2 a lone neighbour's `u` coefficient
**jumps from -90 to +30 as it crosses `rSep`** — a step of 120 at a radius nothing else marks. The
falloff in `MovementLogic` exists to smooth exactly that and the normalisation cancels it. Restore
it and the handover is continuous, crossing zero at `d = 0.75 rSep`. **The proposal removes a
discontinuity rather than adding a parameter.** The cost is a sign reversal: a lone neighbour at
d=49 of rSep=50 used to be fled at -90 and is now approached at +27.6.

**Unlike the earlier variants this is not single-neighbour-identical**, so `EdgeInfluence.steer`,
stable+ and the envelope tables all had to move with it. `Flocking` gained `sepFalloff`, set from
`Aggregation.separationFalloffAtOne()`, fed into `CriticalEnvelopeStore`'s key, and
`AggregationSurvey.checkClosedForm` asserts the closed form and the aggregation agree at n=1 —
0 disagreements over 4,000 arrangements each, and the pipeline refuses to build a table otherwise.
That check immediately earned itself by catching `VOTE_NORM`, which **has no closed form at all**
and is therefore unadoptable without rewriting the critical-envelope analysis.

**Unchanged, verified rather than assumed:** navmap, decomposition, clock, `pureStable(1)` (278),
map-wide stable (1,610), the envelope (84 states), cost-to-leave. None reads the flocking
constants.

**Changed:** stable+ q5 19,861 -> 20,008 with **the same edges**; pairings 598,253 -> 612,776;
exits 491,449 -> 518,535; **unexplained 3.04% -> 1.90%**; centre-panel clumps 20 -> 16 and
3,777 -> 2,694 cells; diluted model needed **6 -> 479**.

**Two measurement corrections worth keeping.** Cell by cell 38.8% of accounted cells move, which
reads as a different simulation; at block scale **94.7% keep their dominant class at every window
size tested (4, 8, 16)**, with exit-rate drift of 1.3-2.5 pp. A cell is a single draw, so a
dithered band that shifts density slightly churns a third of its cells while looking identical —
structural questions belong to neighbourhoods. And the survey's **84.9% per-tick agreement
compounds to about 61% agreement on whether an exit happens**, because a trajectory is dozens of
decisions; per-tick agreement must not be quoted for a trajectory-level question.

**Open before pulling the trigger:** the 80x jump in diluted-model usage is unexplained; only arc
`4->0` on dabeone has been run; and the straight bias is untouched, which under a clamped
aggregation is effectively stronger.

Fixed while writing it: the first version of `Approach.call` tested `psy + third >= demanding`,
which double-counts ticks both cover and called several uncovered clumps `split`. It counts the
union now.

---

## 2026-08-30 (close, later still) — HAPPY.md

Added `HAPPY.md`, a user-maintained file of standing feedback on what has and has not been worth
doing here: feature invocations and decisions that regularly add or subtract value. Header
written by this session, body left empty for the user.

It inverts the direction of every other document in the repository. The rest record something
for the next session; this one gives feedback *to* a session. That is why it carries no
`**Status:**` obligation and why staleness is not a defect in it — an old entry is not a suspect
entry, which is the opposite of the rule the other docs live under.

Registered in the four places documents are catalogued here: `CLAUDE.md`'s read-order list (as
item 5), `CLAUDE.md`'s keep-current table, a paragraph in `CLAUDE.md`'s bookkeeping section, and
`README.md`'s document table. The status-line rule now names `HAPPY.md` and `PROMPTS.md` as its
two exemptions.

The header is written against three ways a file like this fails:

- **Ignored.** Countered by placing it in the auto-loaded `CLAUDE.md` read-order, which is the
  only list a session reliably sees before it starts proposing things.
- **Over-obeyed.** An entry is stated to be a prior, not a rule — it should tip a judgement call
  when nothing stronger pushes the other way, and `CLAUDE.md` wins any direct conflict.
- **Written into by a session.** Explicitly forbidden in all three `CLAUDE.md` locations and in
  the file's own header. An entry added by an assistant would be that assistant grading its own
  work, which is the one perspective the file exists to check from outside.

**Open:** nothing. The body is the user's to fill.

---

## 2026-08-30 (close, later) — PROMPTS.md maintains itself

The entry below records that `PROMPTS.md` had lost a whole session's prompts, and fixed it by
hand. That fix would have kept failing: the archive depended on the assistant remembering to
append, mid-session, work that had already scrolled past.

**Replaced the instruction with a mechanism.** `tools/prompts.ps1` rebuilds the whole file from
the Claude Code logs at `~/.claude/projects/<slug>/*.jsonl` — filtering to `type == "user"`, not
`isSidechain`, no `tool_result` block, then stripping `<system-reminder>` blocks and dropping
`<command-name>` / `<local-command-stdout>` / `<bash-input>` wrappers and interrupt markers.
Resumed sessions replay their history, so logs are read oldest-first and each prompt is
attributed to the log that introduced it.

Regenerating the whole file rather than appending is what makes it idempotent: no bookmark, no
marker to parse, no state that can drift. Two consecutive runs produce an identical hash.

- **Hand-maintained: 231 prompts, 230 KB. Generated: 290 prompts, 667 KB.** Hand-maintenance had
  been losing roughly a quarter of everything ever typed, not just session 08.
- Parsing all 8 logs (~74 MB) takes **2.3 s**.

Wired as a `SessionStart` hook in `.claude/settings.json` (`async`, so it costs no startup
latency and no context). It runs `powershell.exe -File tools/prompts.ps1` through bash — there is
no `pwsh` on this machine, so the schema's `shell: "powershell"` would not have worked.

**Two bugs found and fixed in the generator itself:**

- The shrink guard counted `^### ` lines, but prompt text contains its own markdown headings, so
  it was measuring prompts and their contents together. It now reads the header's own count.
- The header ended flush against the first `---`, which markdown reads as a *setext heading* —
  the count line was silently rendering as an `<h2>`. Also dropped the UTF-8 BOM that
  `Set-Content -Encoding utf8` writes on Windows PowerShell 5.1, which landed just before the
  `#` of the title.

Safe by construction: writes to `.partial`, refuses to shrink the archive, renames only after
both checks pass.

**Open:** the hook has been written and validated but cannot be observed firing from inside the
session that added it — `SessionStart` fires before the session exists. First real evidence is
next session, where `PROMPTS.md` should already be dirty on arrival.

---

## 2026-08-30 (close) — documentation audit

**Audited the root docs against what this session actually built**, rather than assuming they
had kept up. They had not:

- `ThreeBoidPhase` appeared in one document, `SolverScore` in two, `auditCorpus` in none.
- `PIPELINE.md` still warned that step 11 was unsound; `ExitAudit` had been rebuilt the day
  before.
- `ROADMAP.md` still said arc `4->0` had no table; it builds in 64 s.
- `GLOSSARY.md` was dated three days stale.
- **`PROMPTS.md` was missing the entire session** — 22 prompts.

All fixed. `EDGES.md` gained simple loops and why they are the unit that wraps; `HINTS.md`
gained §10a on superposition, which is physics that transfers rather than bookkeeping;
`PIPELINE.md` gained steps 15 and 16; `README.md`'s code map, artifact index and entry-point
table now cover everything.

**The durable fix is in `CLAUDE.md`**, which now carries three things it did not:

1. A table of which document owns what, and the instruction to check each before ending. The
   failure this prevents already happened once — confident, detailed docs three days out of
   date, read and believed, and work rebuilt that existed.
2. **The standing permissions** — commit and push to `main`, manage `.gitignore` — which had
   been granted mid-session and lived only in memory, where a fresh session would not see them.
3. `PROMPTS.md` is append-only and **not required reading**; its own header now says so, and
   that sessions append live because the transcript does not exist until the session ends.

Every document now carries a dated `**Status:**` line, and a doc older than the last session is
explicitly one to distrust.

---

## 2026-08-30 — phase map: target fill, replayability, and two claims tested

**Changed.** Diluted is magenta rather than a brown indistinguishable from psyboid-amber. The
psyboid is advanced by its override's turn where it has one, since a permanently right-steering
psyboid does not coast and carrying it forward unsteered put it where it could not have arrived
from. Run length is now `targetFill` — stop after `1/(1-targetFill)` consecutive attempts that
land only on known cells — which measures the thing that matters and costs nothing when part of
the grid is unreachable. **k anneals**: the first stall drops it to zero rather than ending the
run, so regions a coasting boid cannot occupy still get filled; the second stall ends it.

**Resolution 0.5, target fill 0.9995, 86 seconds**: 7,050,360 cells, 99.0–100.0% per panel. The
anneal fired at 6,900,621 cells and the k=0 phase added **150,000 more** — so those regions are
real and were being missed. 516,139 exits: 275,114 psyboid-led, 132,675 third-boid-led, 21
diluted, **108,350 unexplained (21%)**. Every exit is written to `phase40-replays.tsv` with all
three start states and the override flag, so any cell can be flown again.

### The down-right diagonal — confirmed, and it is sharp

Recording whether the *third* boid also exited tests this directly. Over the two ~528-tick route
panels, binned by `x − y` (which is `psyboid_tau − boid_tau`, the phase difference between the
two non-suspect boids):

| `x − y` | third boid also exited |
| --- | --- |
| 8–47 | rises to **29.8%** at 16–23 |
| 160–255 | rises to **15.5%** at 168–175 |
| everywhere else | **0.0%** |

Two windows with hard edges and complete silence between them. **Whether the third boid exits is
a function of its phase against the psyboid alone**, independent of where the suspect is — which
is exactly what a diagonal in suspect-relative coordinates means. The inference was right.

Also: `otherExited` is **anti**-correlated with the suspect's exit being unexplained — 4.3% of
accounted exits against 0.2% of unexplained ones. Whatever the unexplained residue is, it is not
the psyboid working through the third boid.

### White on the bands — confirmed, including the overhang

Distance from each unexplained cell to the nearest single-leader band, panel
`[4,0,3,5,8] x [4,0,3,5,8]`, in cells of 0.5 ticks:

| | |
| --- | --- |
| on a band (0–1) | **90.7%** |
| just off it (2–5) | **6.2%** |
| near it (6–20) | 2.9% |
| detached (>20) | **0.24%** |

So the white does lie on the coloured bands and does extend slightly past them, as predicted.
Across the four large panels the detached fraction runs 0.24–2.57%. **The route-2 panels read
6–11% detached and I do not trust that**: the band threshold is calibrated for the big panels and
those are half the size, so thin bands there fall below it. The large-panel figures are the ones
to quote.

**The detached cells cluster rather than scatter** — one example is a 9×9-cell blob at
`(584–592, 153–161)` in the psyboid-`[4,2,1,5,8]` × boid-`[4,2,7]` panel, about 4.5 ticks square.
Compact and localised, which is what a real phenomenon looks like and not what sampling noise
looks like.

**Not done.** The 80%-bias test on the detached cells — whether they are near-misses of a
two-boid exit — needs replaying each and re-checking the entry arrangement under modified
constants. The one-dimensional "which `(edge, tau)` ever come within separation range" precompute.
Resolution 0.25, which the current run makes clearly affordable.

---

## 2026-08-29 (end, 7) — the phase map gains routes, and saturates in twenty seconds

**Routes are now part of the coordinate.** A sample lives in `(x, y, route, route)` — one simple
loop from edge 4 back to itself for the psyboid, one for the third boid. There is no single lap
length to wrap against because a lap's length depends which way round it went, but a *route* has
a length, so each panel wraps cleanly and a phase relationship appears once rather than once per
lap. Rebasing follows the route rather than the shortest path, since the offsets differ.

**Dabeone has exactly three simple loops, and edge 6 is on none of them** — both as predicted:

| route | length |
| --- | --- |
| `[4, 0, 3, 5, 8]` | 528.19 |
| `[4, 2, 1, 5, 8]` | 528.30 |
| `[4, 2, 7]` | 275.29 |

The two exit routes agreeing to 0.11 ticks is a free check on the clock: nothing in the fit
knows they are near-mirrors.

**One simulation fills several cells.** Edges are shared between routes, so a boid on a common
stretch belongs to every route through it — with a *different* phase in each. 821,901
simulations filled 1,731,617 cells, about 2.1 apiece.

**Compute is not the constraint.** Twenty seconds fills 1,713,837 cells; ten minutes fills
1,731,617. **Thirty times the compute buys 1.0% more coverage.** The map saturates almost
immediately, so the unfilled remainder is not undersampling — it is phase pairs no arrangement
produces. Per-panel fill confirms the shape of that:

- `[4,2,7] x [4,2,7]` — **100.0%**. On the main loop every phase pair occurs.
- panels with one exit route — 98.3%.
- panels with two exit routes — 96.6%.

So the unreachable cells belong to the exit routes, whose timing is constrained in a way the
main loop's is not. That is a structural fact about the map worth having.

**Outcome over 125,763 exits:** 67,474 led by the psyboid, 31,863 by the third boid, **6** needing
the diluted model, **26,426 unexplained (21%)**. `kPsyboid` is now 8 rather than the effective
zero it was; the diluted count moved off zero, which it had been stuck at, but six cells is
nothing.

**Still unquantified.** The bands and regions are read by eye. Projecting exit counts onto each
axis to locate bands numerically, then measuring each unexplained cell's distance to the nearest
band, is what would separate a growth off a band from a genuinely detached region. Cheap, given
the runtime, and not done.

---

## 2026-08-29 (end, 6) — three-boid phase map

**Built.** `ThreeBoidPhase`. Samples three-boid arrangements and maps them by the two phase
differences: psyboid tau minus suspect tau on one axis, third boid tau minus suspect tau on the
other, everything rebased onto the suspect's edge by the shortest route (Dijkstra over the edge
graph weighted by the length of the edge being left). Suspect starts from a settled state in an
8-tick band across the middle of edge 4, one representative per phase — states whose own
unsteered successor is also in the band are dropped, keeping the last of each chain. Third boid
starts `k = 8` unsteered steps on from a random state; psyboid anywhere; override is a permanent
right turn on half the trials. One sample per cell.

**Fast.** 600,000 attempts in **7.3 s**, filling 222,476 cells. The sanity pass at resolution 5
with 2,000 attempts ran in 4.6 s. This is cheap enough to iterate on.

**Result at resolution 1, 600,000 attempts:** 14,850 exits against 207,626 continuations.
Of the exits, 7,734 led by the psyboid, 3,675 by the third boid, **0 needing the diluted model**,
**3,441 unexplained (23%)**. The diluted zero is consistent with it being inert on this arc.

**The 23% is not comparable to the corpus's 5.6%** and should not be quoted as if it were.
Random placements explore arrangements a searched psyboid never produces, so this is a far
harsher test — which is the point of it.

**The predicted structure is there.** Vertical orange bands where the psyboid alone induces the
exit, horizontal blue bands where the third boid alone does, both appearing **twice** rather than
once. Unexplained cells concentrate where bands cross and extend off them, and there are sizeable
white regions riding *along* the blue horizontals at large x — the third boid in a leading
position, the account still failing, which is where a handover would land.

**That reading is visual and unquantified.** The obvious next step is to project exit counts onto
each axis to locate the bands numerically, then measure each unexplained cell's distance to the
nearest band — which would separate "growth off a band" from "genuinely detached region" instead
of leaving it to the eye.

**Known limits.** One sample per cell, so a colour is one draw and not a majority. The axes do
not wrap, so the plane spans more than one lap and the same relationship can appear at several
x. The psyboid's permanent-right override is dab-specific, as flagged when it was asked for.

---

## 2026-08-29 (end, 5) — the residue rejects genuine led exits

**Built.** `SimTest.renderTick` draws one exit at whole-map scale across several ticks, with the
critical envelope tinted into the background and every boid labelled with its edge and tau. The
cropped `ExitRender` view answers *who could see whom*; this one answers *where everybody is on
the route*, which is the question the residue turned out to need.

**Corrected count.** Five of the twenty `4->0` exits have no sufficient leader at the entry under
*dual* physics, not six — the earlier tally was computed under the true constants alone, since
the `alone` column of `renderUnexplained` calls `steer(..., normal)` only.

**The diluted model is inert, not weak, on this arc.** It zeroes alignment and cohesion, so with
nobody inside `rSep` the desired vector is exactly `(0,0)` and every neighbour reads `+0`.
Fourteen of the twenty have nobody inside `rSep`, so the second model cannot contribute to them
at all — which is the real reason the corpus showed `0 needing the diluted model` here.

**Tick 9489 (seed 9), taken apart.** Suspect boid 2 on edge 4; forward is `(0,-1)`, right is
`(+1,0)`.

| | | |
| --- | --- | --- |
| boid 0 | 148.2 px, annulus, in FOV | `L +85.144  S +90.709  R +88.560` → `+0` |
| boid 1 | 44.8 px, inside `rSep` | **behind the FOV**, contributes nothing |
| boid 3 | 121.5 px, annulus, in FOV | `L -50.448  S -44.344  R -44.655` → `+0` |

Alignment sums the neighbours' heading unit vectors and *then* renormalises: `(-0.098,-0.995)`
and `(+0.831,+0.556)` largely cancel — the sum keeps 0.855 of a possible 2 — and the residual is
scaled back to the full 70, giving across `+60.04`. **Partial cancellation followed by
renormalisation amplifies whatever survives.** Cohesion meanwhile flips from backward
`(+29.55,+47.78)` for boid 3 alone to forward `(+2.35,-29.91)` for the pair, because boid 0 is
ahead-right and boid 3 behind-left.

**The finding the map view gave, which the cropped view could not.** Boid 0 was 42 ticks *ahead
of the suspect on edge 4* at tick 9429, took the `4->0` exit itself, and the suspect followed
about sixty ticks later. **That is a textbook led exit, and the analysis credits nobody for it.**
Boid 0 is refused because at 148.2 px against an `rFlock` of 150 it sits on the perception
boundary and loses to straight by 2.15 inside a 3.4375 bias. The across-component that does the
work comes from boid 3, which is on edge 2 across the map with no structural relation to the
exit.

**So the residue is biased against exactly the cases the project's central argument is about.**
The solver rests on *a boid at the front had nothing to follow*. Here something was being
followed, and the audit says unexplained — which reads as evidence toward the suspect. That is a
wrong answer in a direction that matters, not noise.

**Two consequences for the fallback.** Halving the straight bias would *not* have caught this:
boid 0 needs the bias under 1.29 and half is 1.72. A `wCoh = 0` model does catch it, by making
boid 3 alone sufficient — checked at this tick only. It would credit a boid no human would name
as the leader, which is acceptable for the audit's purpose (something other than an override
accounts for the turn) but worth knowing.

---

## 2026-08-29 (end, 4) — arc `4->0`'s residue is the same baton pass, far more common

**All 20 unexplained exits on arc `4->0` are multi-leader.** Over a 70-tick window ending at
envelope entry, **no single boid accounts for the whole window in any of the twenty**, and 19 of
the 20 windows do reach settled ground, so the window is long enough for the question to be
well posed. Images in `render/unexplained40/`.

**A 24-tick window gives the wrong answer**, and it is worth knowing why. At 24 ticks, 10 of 20
looked as though one boid covered everything. Edge 4's unsteered chains run to 48 ticks and only
19% of the edge is settled, so 24 ticks does not reach settled ground; the leader only appears
constant because the handover has not happened yet. **Set the lookback from the edge's chain
depth, not by eye.**

**The influence shape is the opposite of arc `2->1`'s**, and that is what explains the
order-of-magnitude difference in residue (5.6% against 0.36%):

| | `2->1` | `4->0` |
| --- | --- | --- |
| dominant term | separation, +90 to +117 | **alignment, +20 to +70** |
| boids inside `rSep` | two, in 7 of 8 | **none, in 14 of 20** |
| entry state in the tables | 8 of 8 | 20 of 20 |
| a single neighbour suffices at entry | 7 of 8 | 14 of 20 |

**Alignment-dominated exits are structurally more prone to multi-leader histories.** Alignment
is long-range, weak and diffuse — several boids each contribute a little, so which one is "the"
leader shifts easily along the history. Separation is short-range and decisive, so one boid
dominates for a whole stretch. That is a claim about the physics rather than about this corpus,
and it predicts the residue is worst on whichever arcs are alignment-carried.

**It also explains "0 needed the diluted model" on this arc.** The diluted model zeroes
alignment, so it contributes nothing at all where alignment is what carries the turn. **The
fallback we built is separation-shaped and this arc's residue is alignment-shaped**; they do not
meet. That is not a defect in the fallback — it was designed for the case it was designed for —
but it means the diluted model cannot be expected to help here, and a second fallback aimed at
alignment would be a different object.

**Open.** Six of the twenty have no single sufficient neighbour even at the entry, so they are
superposition as well as multi-leader. The remaining fourteen are pairwise-representable at the
entry and fail only in the history. One window (case 8) never reaches settled ground even at 70
ticks and wants a longer lookback.

---

## 2026-08-29 (end, 3) — the negative memo was written and never read

**Diagnosed the stalled arc `4->0` build**, after adding the progress reporting whose absence
made it undiagnosable. The first attempt printed only between entry states and emitted nothing
in seven minutes, which was itself the finding: the run was stuck inside the leader loop for a
single entry state. Reporting from inside the search showed why.

At 291 s it was still on **entry state 1**, having done 3,766 admits, each exhausting a fresh
65k–550k pair component. Nothing hit the 2M budget and the heap was churning but not exhausted,
so neither the budget nor memory was the constraint. The cost is
`entries × leaders × component size` — one entry state matched thousands of leaders, each
spawning its own search, across ~59 entry states. That product is about 1e13.

**The defect: `rejected` was written and never read.** It was consulted only for the *start*
pair, never inside the BFS, so 6.2 million cached rejections pruned nothing and every search
re-expanded ground a previous one had already proved barren. One line — skip a polled pair
already known rejected — and the arc went from **unfinished after 2h46m to 64 s**.

That in turn exposed searches genuinely larger than the 2M probe budget, since the prune lets
them get much further. `BUDGET` raised 2M → 12M; biggest component seen, 2,489,608. The store's
refusal to write a table that hit its budget worked exactly as intended in between: it completed
the analysis, noticed entries were missing, and declined to persist it.

**First figures for arc `4->0`**, 40 plans, 982,752 decisions: **355 exits, 175 under an
override, 160 with a leader under the true constants, 0 needing the diluted model, 20
unexplained (5.6%).** Causes are alignment-and-cohesion dominated — L0 103, L1 38, L2 SEP 17.
The 5.6% residue is an order of magnitude worse than arc `2->1`'s 0.36% and is uninvestigated.

**Two proposed optimisations, judged against the measurement.** Precomputing the physics on
`(dx, dy, d, d_leader)` is not the lever — a table would be ~290 MB per model and buys a constant
factor against a 1e13 product. Early termination at the first settled state is closer, but the
measured shape says most admits *fail*, and a failure has to exhaust its component to be a
failure; the win was in not re-exhausting components already known barren.

---

## 2026-08-29 (end, 2) — correction: the arc 4->0 table was never built

**A claim in the previous session report was wrong.** Arc `4->0`'s envelope table under the
two-model boid did *not* finish. The background build ran to **2h46m of CPU and 8.3 GB of a
10 GB heap** without emitting its "tables built" line, and was killed. Only two `.bin` tables
exist, for arcs `2->1` and `5->6`; the store's `.partial` rename meant nothing half-written was
left behind.

Single-model with grace pruning, that arc took 754 s. Two models roughly tripled the other arcs,
which would predict something near 40 minutes — it ran more than four times that and was still
going. Edge 4 is the worst case for admission breadth (deepest unsteered chains, only 19% of it
settled), and the two-model step doubles the branching on top of that.

**Every corpus figure in this log is from arcs `2->1` and `5->6` only**, which is what was
stated at the time and remains true. Arc `4->0`'s residue under a psyboid is still unmeasured.

**Two things this exposed.** `CriticalEnvelope.analyse` emits no progress at all, so a long
build is indistinguishable from a hung one — there was no way to judge how close it was, which
is why killing it was the only defensible call. And the practical ceiling on that arc argues the
out-of-range collapse in `ROADMAP.md` §1 is not an optimisation but the thing that makes it
runnable.

---

## 2026-08-29 (end) — first real commit, and the tree tidied

**Pushed.** The session landed as two commits on `main` — 103 files, +10,306/−1,042 — against a
history that until now was twenty-five commits all named "Periodic check-in". **Seven source
files had never reached GitHub at all** (`MapStore`, `PsyboidBits`, `PsyboidCorpus`,
`SceneRender`, plus the three written today); that gap was recorded in `ROADMAP.md` and is now
closed.

Git works directly from the shell here, contrary to an old note claiming otherwise: identity,
remote and `main → origin/main` tracking all read correctly and `push` needs no prompt.

**Deleted by the user:** `cases/` and `transcript.pdf` (27th), then `routes/`,
`psyboid-packet.zip`, `Proposal.txt` and `ANSWER-KEY.txt` (29th).

**`.gitignore`:** `areas/scratch/` added — maps mid-edit are not worth history, since a map only
becomes real once ingested. The stale comment on `analysis/` naming a producer that no longer
exists was corrected.

**Staging needs care in this repo.** Several large or retired paths are untracked but *not*
ignored, so `git add -A` sweeps them in. Stage by explicit path and read
`git diff --cached --stat` first. Note that `ingests/*/{map.png,display.png,meta.txt}` look like
build output and must be committed — a frozen map is what an old label replays against and
cannot be recovered once the source PNG moves on.

---

## 2026-08-29 (close, 2) — `SolverScore` replaces the `3·TP + TN` tally

**Verified before building.** The proposed score — a Brier score over the K-weighted psyboid rate
implied by each answered class — collapses to
`g(K·FN, TN) + g(K·TP, FP)` where `g(a,b) = ab/(a+b)`, verified against the unsimplified form to
**2.8e-14 over 200,000 random cases**. Both terms of a class telescope:
`TN·Hn² + K·FN·(1−Hn)² = ab/(a+b)` with `a = K·FN, b = TN`.

Three properties follow from `g` being concave and homogeneous of degree one, hence
superadditive — checked over 300,000 random assignments:

- **abstaining is the worst attainable score** (0 splits beat it);
- **every uninformative split ties with it**, exactly when `FN·FP = TP·TN` (2,023 ties, all
  proportional, none otherwise);
- **zero only for a perfect or a perfectly inverted answer** (5,234 / 5,170 / 0 other).

**The swap symmetry is worse than a curiosity.** It makes the score *non-monotone*: at
`TP=30, FN=70, TN=100, FP=200` both marginals are negative, so correcting a mistake raises the
penalty. For a regression detector that silently reverses sign below chance, which is the one
thing it must not do.

**Caps fix it, but they have to be the population rates.** The proposed `K/(K+1)` and `1/(K+1)`
give monotonicity (0 violations in 400,000) and kill the inverted zero — but they **tilt the
plateau**: uninformative splits then run 150.00 to 187.50 purely with the size of the answered
class, so a solver could gain by answering PSYBOID less often, which is the second goal's own
failure mode mirrored. A flat `1/2` cap has the same defect at a different class balance.

The general rule is to cap each hedge at its **population value** —
`capN = K·Np/(K·Np+Nb)`, `capP = Nb/(K·Np+Nb)`, summing to 1. The proposed caps are the special
case `Np = Nb` and the `1/2` caps the special case `Nb = K·Np`, which is why each looked flat on
one example. At the population rates the caps bind exactly at the abstain point and nowhere on
the uninformative manifold: **plateau spread 0.000000 at every base rate and K tried, 0
monotonicity violations, 0 splits worse than abstaining, and zero reached only by a perfect
answer.**

**Changed.** New `SolverScore` — closed form and the properties in the javadoc, piecewise
unsimplified form in the code, plus `abstaining` and `normalised`. Replaced both `3·TP + TN`
sites: `SimTest.grade` now reports penalty and normalised score, `SimTest.graded` accumulates
the four counts per plan and averages the *normalised* figure across plans, since a raw penalty
scales with scene count. `GLOSSARY.md` gained *the grade* (rewritten) and *hedge cap*;
`PIPELINE.md` §14 rewritten.

**Not done.** No corpus figures produced — the score has no baseline yet, and any number from it
would be the first of its kind rather than a comparison.

---

## 2026-08-29 (close) — critical-envelope tables persisted and made shareable

**Built.** `CriticalEnvelopeStore`, content-addressed on the map's dimensions and step table,
the live set, the edge labelling, the arc, both flocking models and the out-of-range flag, plus
its own `FORMAT` — the same rule `EdgeMetricStore` and the map ingests follow. Written to
`<ingest>/envelope/envelope-<key>.bin` via a `.partial` rename, so a run killed mid-write leaves
no file that looks complete. **A table that hit its admission budget is refused rather than
stored**: an incomplete one would be indistinguishable from a complete one afterwards and every
later run would inherit the gap silently.

**`ExitAudit` split into `Tables` plus a per-run instance.** The tables are immutable after
construction, hold no per-run state, and are shared; an audit is a cheap wrapper that allocates
only its own bookkeeping. That is the requirement behind the lookup-only design — the audit is
meant to be called a great many times from a great many threads, and rebuilding minutes of
analysis in its constructor made both impossible.

**Measured: 101 s to build two arcs, 0 s to load them**, and the corpus result is bit-identical
across the two runs (553 exits, 300/250/1/2), so the round-trip is lossless.

---

## 2026-08-29 (last, addendum) — the residue is a baton pass, and is genuinely multi-leader

**Built.** `SimTest.steeringHistory` / `replayHistory` replay a plan and print, for each tick of
a window ending at envelope entry, the suspect's request, its post-veto move, and — for **every**
neighbour, not one chosen leader — what that neighbour alone asks for under both physics and
whether it reproduces the move actually made.

**Both remaining exits are the same structure.** Coverage over the 24 ticks ending at entry, for
the exit at tick 8303:

| ticks | accounts |
| --- | --- |
| 8278–8291 | `{0, 1, 3}` |
| 8292–8294 | `{0}` |
| 8295–8297 | `{0, 1}` — the handover |
| 8298–8301 | `{1}`, entry at 8301 |

Boid 0 accounts for 20 of 24, boid 1 for 21, boid 3 for 14. **No single boid accounts for the
whole window.** The exit at tick 11154 has the identical shape and the identical 20/21/14 split.

**It is an alignment-to-separation baton pass.** Boid 0 sits at **48–52 px against `rSep` = 50**,
mostly just outside separation: it accounts under the true constants and contributes nothing
under the diluted model, which is what being outside `rSep` looks like with alignment and
cohesion zeroed. Boid 1 sits at **~8 px**, deep inside separation, and needs the diluted model to
account for the late ticks. Early ticks by alignment and cohesion from one boid, late ticks by
separation from another.

**Why admission cannot reach it.** Boid 1's coverage is not contiguous — 8278–8291, a gap at
8292–8294, then 8295–8301 — so a backward walk from the entry holding boid 1 fixed reaches 8295
and stops. Boid 0's coverage *is* contiguous back through the settled ticks, but boid 0 does not
cover 8298–8301, so a boid-0 history cannot start at the entry. A pairwise table holds one leader
for a whole history and has no way to express a handover.

**Verdict: these are unclassified, and correctly so.** Not a cover that is too narrow — a window
that exists only in three-boid space, which no setting of any two-boid constants reaches. They
stay unclassified until multi-boid classification exists. The residue is **2 of 553 (0.36%)** and
is now a characterised limit rather than an unexplained remainder.

**For whenever multi-boid classification is taken up:** the two coverages *overlap* for three
ticks, so the handover needs no unexplained step. "The leader may change at a tick where both
account" is a far tighter rule than free switching, and both of these fall to it.

---

## 2026-08-29 (last) — two physics the boid may choose between

**Built.** `Flocking.diluted()` — alignment and cohesion at zero, separation doubled — and
`CriticalEnvelope.analyse(..., f, alt)`, in which the exiting boid picks **either model
independently on every tick**, at the entry and at every step of its history. `Entry` carries a
`diluted` flag saying which model produced the entry turn, so one table serves both levels and
`ExitAudit` no longer builds two.

**This replaces the half-straight-bias second run**, and the reasoning is the user's: a table
built wholly under widened constants turns every marginal straight into a turn along the whole
backward path, when a real crowd tips some precise subset. Wrong shape, not just wrong size.
And zeroing alignment and cohesion is the faithful model of a crowd where halving the straight
bias is the opposite one — extra boids *dilute* those two terms, while halving the bias
amplifies them.

**Result on the same corpus** — 40 plans, 245,688 ticks, arcs `2->1` and `5->6`:

| | one model | two models |
| --- | --- | --- |
| under an override | 300 | 300 |
| leader, true constants | 245 | **250** |
| leader, diluted model only | 0 | **1** |
| **unexplained** | **8** | **2** |
| psyboid exits that also had a leader | 5 | 18 |
| table build, two arcs | 37 s | 101 s |

**Five of the six recovered exits went to `ENVELOPE`, not `ENVELOPE_WIDENED`.** Their entries
were always admissible under the true constants; it was the *history* that needed the second
model at some step. That is precisely the failure the previous entry identified — admission
being stricter than the physics — and it is fixed without loosening what counts as an entry.
Only one exit in 553 needed the diluted model at the entry itself.

**The two survivors are one arrangement, found by two different plans.** Suspect at
`(104,246,22)`, one neighbour at ~10 px and a second at **48.3 px against a separation radius of
50** — so both are inside `rSep`, but the far one's falloff is `(50 − 48.3)/50 ≈ 0.034` and it
contributes almost nothing in magnitude while still tilting the normalised direction. The close
one alone produces the turn and its pairing is still rejected by admission. Residue is
**2 of 553, 0.36%**.

---

## 2026-08-29 (later still) — the eight unexplained exits, drawn and decomposed

**Built.** `MovementLogic.decompose` returns the three rules' contributions separately, and
`calculate` is now that plus an assignment, so there is one implementation rather than a
diagnostic copy. `ExitAudit.Exit` carries the arrangement at envelope entry.
`ExitAudit.lookup` / `isEntryState` say whether a pairing was enumerated and whether it was
admitted. `SimTest.renderUnexplained` draws each unexplained exit and prints its decomposition.
Images in `render/unexplained/`.

**The residue is not what the previous entry said.** It splits, and mostly the other way:

| | count |
| --- | --- |
| suspect's entry state present in the tables | **8 of 8** |
| at least one neighbour that alone produces the turn, pairing **rejected by admission** | **7 of 8** |
| no single neighbour suffices — genuine superposition | **1 of 8** (case 04) |

So the envelope and its entry enumeration are working: every entry state was found. What fails
is **admission**, which asks whether *those two boids alone* could have reached the arrangement.
**A four-boid run reaches arrangements no two-boid history can**, and seven of eight sit in
exactly that gap. This is a structural limit of a pairwise table, not a bug and not a tolerance.

**The geometry is separation-dominated, and it is two close boids, not one.** Seven of eight
have **two** neighbours inside `rSep`; the eighth has one inside and one at 50.4 against a
radius of 50. Across-heading components at entry: separation +90 to +117, cohesion −25 to +13,
alignment −42 to +30, against a straight bias of **3.4375**. The far-field terms are noise here;
separation is carrying every one of these by one to two orders of magnitude.

**Why two close boids defeat a pairwise table.** Separation sums its neighbours' pushes *before*
normalising, then emits one unit vector at `W_SEP`. Two close boids therefore produce a single
120-magnitude push in the **vector mean** of their directions, which is not either individual
direction — which is how case 01 arises, where boid 0 alone says `+0`, boid 2 alone says `+1`,
and the pair together says `+1` from a state neither pairing admits.

**Open.** Whether to relax admission (three-boid histories, or admitting any arrangement the
corpus actually exhibits) or to accept a bounded residue and record it. Not a tolerance to tune.

---

## 2026-08-29 (later) — grace-period pruning, and the audit against a psyboid corpus

**Grace-period pruning.** The hard out-of-range cutoff is replaced by a rule that prunes only
when the pair is too far apart to have been together recently, combining the gap with how fast
it is opening: prune when `speed * pi/64 * (d_leader - d_boid)^2 + (distance - rFlock) >
2 * speed`. The shell is about eight pixels deep for two boids flying parallel and closes
entirely once the headings differ by seven steps — which is the case the hard cutoff got wrong,
two boids running just off parallel and staying near each other for a long time.
`CriticalEnvelope.unrecoverable`.

**It recovers real histories, and it does cost.** Arc `2->1`: **7.0 s → 15.3 s**, admitted pairs
**291,337 → 389,302**, entry states **52 → 67**. Arc `4->0`: **186 s → 753.7 s**, admitted pairs **85,969 → 117,946**, entry states **40 → 59**,
893M pairs probed.
Edge 4 is the worst case for this — deepest unsteered chains (max 48, mean 7.12) and the
smallest settled fraction (2,371 of 12,471, 19%), so the grace shell adds leader states at every
step of a long walk. Roughly thirteen minutes for all three arcs, against three and a half — it
does not balloon, but the tables are not persisted, so every run pays it again.

**Audit against the psyboid corpus** — 40 plans from `ingests/609cffdb84be218c/psyboid/plans.tsv`,
245,688 ticks, arcs `2->1` and `5->6`, level 3 unchecked:

- **553 exits** in 982,752 decisions, against 15 for plain flocking over a comparable run. A
  psyboid is what makes exits happen, exactly as expected.
- **300 under an override, 245 by a leader, 8 unexplained (1.4%), 0 without an envelope entry.**
  Completeness holds across 553 real exits.
- **5 of the 300 psyboid exits also had a sufficient leader** — the psyboid took an exit it did
  not need the override for. That is the case the reason list exists to keep visible.
- Causes shift completely once a psyboid is herding: plain flocking gave `2->1` as L7 SEPARATION
  in every case, the corpus gives L1 ALIGNMENT_AND_COHESION 194, L8 ALIGN 24, L7 SEP 19,
  L3 ALIGN 8.
- `5->6` produced no exits at all, consistent with it being cold-start-only.
- **All 8 unexplained had 2 or 3 other boids in flocking vision** (of 3 possible). ~~That is the
  multi-boid superposition signature rather than a hole in the envelope.~~ **Wrong — see the
  next entry.** That was inferred from the neighbour count alone, and drawing them showed it is
  true of one of the eight, not all.

**Open.** Persisting the tables. The 8-exit residue. Level 3. Arc `4->0` was not included in the
corpus audit, so its residue under a psyboid is unmeasured.

---

## 2026-08-29 — critical-envelope analysis built; `ExitAudit` rebuilt on it

**Built.** `CriticalEnvelope` (envelope, settled set, admitted pairs with cause and leader
path), `SimTest.envelope` and `SimTest.chains` to drive and diagnose it, and a rewritten
`ExitAudit` that is pure table lookup with no geometry. `SimTest.census` is now the corpus test.
`PsyboidCorpus.fidelity` no longer runs its audit comparison — it was measured against the old
audit and wants re-establishing deliberately.

**Envelope, on dabeone `609cffdb84be218c`.** Tiny, and the front reproduces a known number from
a completely different construction:

| arc | envelope on edge | one tick onto exit edge | front | settled on edge |
| --- | --- | --- | --- | --- |
| 2→1 | 34 | 20 | 20 | 4,547 of 15,361 |
| 4→0 | 27 | 16 | 16 | 2,371 of 12,471 |
| 5→6 | 49 | 18 | 18 | 4,219 of 12,471 |

The front — states whose *straight* successor is on the exit edge — comes out at **20 / 16 / 18**,
exactly the documented follow-through counts for edges 2, 4 and 5. Independent arrival at the
same numbers. The entry-states-are-not-in-the-envelope assertion passed on all three arcs, so
the completeness argument holds on this map.

**Defect found and fixed: the downstream part was seeded from straight successors only.** A boid
whose coasting successor stays on the edge can turn once and be on the exit edge immediately,
never standing on an envelope state of its own edge — so the exit escaped the net entirely. Four
of six arc-`2→1` exits were escaping. Seeding from every one-tick crossing fixed it; the front
still comes from the straight ones. Recorded in `ROADMAP.md` §1.

**Checked, on request: do unsteered backward chains terminate or hit settled ground?** They
terminate, quickly — longest 25 / 48 / 35 ticks on edges 2 / 4 / 5, mean 2.92 / 7.12 / 7.19, and
a fifth of unsettled states have no unsteered predecessor at all. **But they never reach a
settled state: 0 of 10,814, 0 of 10,100, 0 of 8,252.** That is structural rather than
incidental — *settled* is built as an unsteered forward closure, so it is closed under coasting,
so its complement is closed under coasting backwards. A boid off the coasting tube cannot rejoin
it by coasting. **Consequence: only a steered step can reach settled ground, so every admitted
history needs the leader in range at the moment of every steered step.**

**Why the search is expensive anyway.** Backward closure over *all* predecessors covers the
whole edge — 15,361 of 15,361 and 12,471 of 12,471 — so the pair space is 1.7–2.1e9. Memoisation
is a large win and is not sufficient on its own: unpruned, arc `4→0` had not finished after
fifteen minutes at 8.2 GB. Two-pass component solving (exhaust the component, then propagate
*reaches settled* back from the terminals) made the memo complete rather than witness-only, and
is kept. `CriticalEnvelope.pruneOutOfRangeLeaders` is an opt-in approximation, off by default,
with the exact fix specified beside it.

**Corpus test — 8 seeds x 20,000 ticks, warmed 1,000, no psyboid, level 3 not checked.**

- 15 exits in 640,000 decisions. **Exit 1 (`2→1`) 6, exit 2 (`4→0`) 9.**
- 13 explained by the envelope tables, **2 unexplained**, and **0 with no envelope entry** —
  which is the completeness property holding on real flights.
- Both unexplained exits are boid 0, arc `4→0`, from the same state 3235441 that six explained
  exits also cross from. So the entry state is in the table and the particular leader pairing is
  not. Either multi-boid superposition, or the range pruning dropping a real history.
- **The lag between envelope entry and crossing is 0 on 13 of 15 exits.** The cost to leave is
  essentially always exactly 1 on these arcs, which is what makes the downstream part of the
  envelope load-bearing rather than a corner case.
- Causes: `2→1` is SEPARATION with the leader on edge 7 in every case. `4→0` splits between
  leader edge 1 and edge 2, mostly SEPARATION.

**Open.** The two unexplained exits. The half-straight-bias tables for level 3 were not built.
The out-of-range collapse in `ROADMAP.md` §1 is specified, not built.

---

## 2026-08-28 — critical-envelope analysis respecified

**Attempted.** Recording a redesign of the critical envelope, given before any code was
written. Frontier-first, per `CLAUDE.md`.

**The design.** The envelope becomes the set of states that **reverse-navigate from the exit
edge**, replacing the band around the unsteered path. It terminates because unsteered travel
from the start of an edge never leaves it by a non-straight transition, and it is complete
because every boid that exits was steered onto it. Attribution moves from the crossing tick to
the **envelope-entry tick**. Output is annotated paired-state tables — boid state before entry
→ set of consistent leader states — admitted only when the pair can reverse-navigate back to a
*settled* state for the exiting boid under `TwoBoid` forward rules. Cause and the leader's
minimal edge path are recorded per pair. CEA runs twice, the second time at half straight bias,
for the level-3 fallback.

**Corrections to yesterday's record.** CEA generates its own two-boid data and is **not** driven
by `TwoBoid` — using `TwoBoid` as the leader domain would over-restrict where a leader could
have been. And `UnstableEdgeClue.PAYING` is empty **by design**, not because windows were
measured harmful: solver windows are meant to be populated only from a training corpus, over
ranges seen in it, because several derivable windows require counterproductive psyboid
behaviour and will never appear.

**Changed.** `ROADMAP.md` §1 replaced with the full specification; §2
anchoring split into report-at-crossing / attribute-at-entry; the solver section rewritten
around corpus-derived windows. `GLOSSARY.md` gained *envelope entry* and *settled* — the latter
named to avoid colliding with the `stable[e]` edge property — and the cause measure.
`EDGES.md` §7 marked as describing what exists today, with a pointer forward.

**Resolved same day.** All six open questions answered; `ROADMAP.md` §1 is now a complete
specification.

- The closure is over **unsteered predecessors**, where steering means the intended direction
  *before* the veto. `NavMap` already has the helpers.
- **Entry onto the envelope is always a steered move** — unsteered travel into the envelope
  implies you were already in it — so detection is a membership flip. But entry is *not*
  commitment: a boid can be steered back out, and a psyboid always can. **The commit boundary
  is the edge boundary**, which the edges were designed to force.
- **The backward pair search needs no depth bound.** Every state by which a boid enters an edge
  is settled, so the search terminates within one edge.
- The settled construction is **`{closure, partial tick, closure}`**; partial ticks turn as
  normal and advance partially, applied exactly once to prevent 45° strafing. Their purpose is
  **phase alignment** — boids clump in tau modulo the step length and states on the general
  unsteered path get crossed between ticks.
- **Level 3 is the half-straight-bias run.** Equivalent in effect to doubled `wSep`, but a
  better framing because it goes to the intended travel angle — and it is an axis worth
  sampling more than once, to show window robustness and near-misses.
- Cause is measured at envelope entry, by larger signed orthogonal component matching the turn.

**Also changed.** **Every grading figure has been deleted**, from the docs and from
`UnstableEdgeClue`'s javadoc — they assumed no bugs and a sound corpus, and both assumptions
were false. What remains is the structural fact that a window can only ever spend true
negatives. The five windows in the enum are now labelled as hand-read placeholders.

**Open.** Nothing blocking. Nothing built yet.

---

## 2026-08-27/28 — documentation reorganisation

**Attempted.** A full survey of the project — every source file and every document — to
establish what exists, what is documented, and why sessions kept reinventing analysis that was
already present.

**Found.**

- **The documents stopped at 2026-08-25 and the code did not.** `ROADMAP.md`, `PIPELINE.md`
  and `HINTS.md` were last written 2026-08-25 16:02–16:03; eleven source files were written
  after. Eight classes — `Solver`, `SolverFacts`, `SolverStore`, `Clue`, `UnstableEdgeClue`,
  `PsyboidBits`, `PsyboidCorpus`, `SceneRender` — appeared in **no** markdown file at all,
  while `ROADMAP.md` asserted that the two halves of the argument were "not yet joined". They
  were joined. That is the mechanism behind the reinvention problem: the authoritative
  documents asserted something false with total confidence, so checking first led to the same
  wrong answer.
- **`ExitAudit` is unsound in both forms.** Working tree: `inWindow` reads
  `SolverFacts.windowsInto`, so the classifier consults the windows it exists to validate, and
  `census` cannot disagree with itself. Committed: `alone()` used `EdgeInfluence.steer`, which
  returns `0` out of range and behind the FOV, so once a suspect was committed **every distant
  boid passed as a sufficient leader** — the "85 of 89 led" figure over-counts. Collateral:
  `widened` and `separating` are never incremented, so `coveredByWidening()` is permanently
  false, `widenedBy()` is a no-op, and `census` labels everything `ALIGN`.
- **Gates are not leaking into analysis.** Checked every use: decomposition bootstrap, plus
  `SolverFacts.Gate` carried for provenance and read only by `toString` and serialisation. The
  proxy-instead-of-definition bug that was suspected was real but was `straightTo` /
  `edge[straight]` in `ExitAudit`, since fixed by `EdgeNavigation.exitTurns`.
- **`tick` meant two things**, including inside one record (`ExitAudit.Exit` carries both
  simulation time and position along an edge). Resolved: **tau** for position, **tick** for
  time.
- **Two distinct two-boid analyses were being conflated.** `EdgeInfluence` is the
  single-neighbour closed form and is what every window comes from; `TwoBoid` is the exhaustive
  pair enumeration and only told us which arcs are steerable.
- **Documented figures are trustworthy.** Spot-checked dead-pixel counts against `meta.txt`:
  dab 1,565, dabnt 2,123, blossom 7,428, wormway 17,777 — all exact.
- **`analysis/` is orphaned** — its producer, `Analyze`, no longer exists in `src`.

**Changed.** Added `CLAUDE.md`, `README.md`, `GLOSSARY.md`, `SESSION-LOG.md`. Rewrote
`EDGES.md` around the relative definition of an edge in `(x, y, d)`, retiring the named route
edges, the hand-drawn region annotation and the five circuits. Rewrote `ROADMAP.md` with the
`ExitAudit` specification and the critical-envelope concern as priority one. Marked the stale
sections of `CONTRACTS.md` and `BACKLOG.md`. Removed the answer-key-leakage framing.

**Deleted by the user this session:** `cases/`, `transcript.pdf`. Queued for deletion:
`routes/`, `packet/`, `psyboid-packet.zip`, `analysis/`, `data/`, `out/production/`,
`ingests/48b46d3d06e54c75/routes/`, `ingests/609cffdb84be218c/twoboid/` (254 MB, rebuilds in
~17 s).

**Open.** Priority one is whether the critical envelope covers the earliest possible influence
(`ROADMAP.md` §1). Then `ExitAudit` to the new specification, then re-establishing the corpus.
Two schema gaps block the spec: cause is not a field on any band, and modified-physics
separation windows have nowhere to be stored. No solver tuning figure should be believed until
those land.

---

## Before 2026-08-27

Not logged contemporaneously. `PROMPTS.md` holds every prompt through 2026-08-25 and
`transcripts/` holds seven raw session logs to the same date; the sessions of 2026-08-26 and
2026-08-27, in which the whole solver layer was written, are recorded only in javadoc.
