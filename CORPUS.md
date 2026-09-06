# Corpora

Canonical for psyboid corpora: what one is, how it is generated, how it is addressed, and which
numbers are worth reading off it.

**Status:** 2026-09-05, physics 3. Figures measured on dabeone ingest `609cffdb84be218c`,
structure `b65011999ad52a55`, behaviour `cc1ab3e9a4831bfd`.

---

## What a corpus is for

**Psyboid compute is expensive; replay is cheap.** A corpus runs a psyboid algorithm once over a
set of seeds and records enough to reproduce every timeline exactly, so everything downstream —
classification, grading, case packets — pays the search cost once.

That works because the physics is deterministic and because a plan is fully described by its
**label**: a seed, plus every committed override in the form `PsyboidOverride.parse` reads back. A
row is verified by replay before it is written, matched position for position; a corpus that did
not reproduce would be worse than none.

## Addressing

```
ingests/<map>/structure/<structure>/behaviour/<behaviour>/psyboid/<preset>-<hash>/
    plans.tsv
    floor.tsv
    meta.txt
```

A corpus inherits the map, the gate, the weighting scheme, the physics version, the flocking
constants and the aggregation from the tiers above it — see `README.md`'s artifact index. What the
corpus level adds is the **recipe**: seeds, warm-up, run length, and the search's spread,
lookahead and discount.

**The recipe is named, not passed.** `CorpusPreset` is an enum in the same spirit as
`PresetScenarioParameter`, and `Derived.corpus` hashes it into the path. Corpora used to record
their recipe in a header comment, where nothing could act on it — two recipes collided at one
`plans.tsv` and the second silently replaced the first. That is the under-labelling problem, and
naming the recipe is the fix.

`PsyboidCorpus.labels(Derived.Behaviour)` reads the single corpus under a behaviour and **refuses
when there is more than one**, listing the choices. A reader that does not care which recipe it
gets is usually one that has only ever seen one.

## Seeds

Seeds are always **0 to N−1**, so a preset is reproducible from its name and a larger preset is a
superset of a smaller one. Augmenting an existing sample, or drawing a held-out set for a packet,
is a different job and wants its own preset rather than an offset hidden in a call.

## Warm-up

Warm-ups exist to let boids flush out of places they would not ordinarily occupy. The ideal is

> the **minimal** number of ticks such that no inference can be made about any boid's spawn
> location.

**Minimal is load-bearing.** Letting the flock settle into a stable orbit is not the goal and is
actively unwanted: it homogenises the seeds and throws away the slightly-unstable multi-boid
configurations that are the interesting ones.

A prima facie reasonable figure is **the total length of all edges**, since the clock measures edge
length in ticks. On dabeone that is **940 ticks** (158.14 + 158.62 + 100.59 + 100.97 + 93.73 +
102.59 + 71.84 + 80.97 + 72.76).

> ⚠ **The current `PsyboidBits.WARM` is 5,000, and the two rationales disagree by 5×.** WARM was
> chosen by asking when unsteered scoring stops depending on when you started watching — which is
> a *settling* criterion, exactly the thing the minimality argument says not to optimise for. Its
> javadoc records the measurement honestly (40 of 40 seeds score unsteered from tick 0, 5 from
> tick 5,000), so the number is not arbitrary; it is answering a different question.
>
> **Evidence that 5,000 over-warms:** across all 40 plans of `PLANS_40` the control occupancy is
> **0.0000** — identically zero. Control is supposed to be the warm-up diagnostic, and at this
> warm-up it carries no information at all, because there is nothing left to decay.
>
> **Edge-occupancy decay has now been measured, and it says 500.** See below. `WARM` is still
> 5,000 — the two criteria disagree by ten-fold and the choice between them is a specification
> question, not a measurement one.

### Edge-occupancy decay, measured

`EdgeOccupancy`, 2,000 psyboid-free seeds on dabeone, 50-tick windows, long run `[40,000,
60,000)`. The quantity is the distance between the cross-seed mean occupancy over edges in each
window and the long-run occupancy, against **sigma = 0.477**, the spread of *one* seed's window
about that long run.

The long run is the same to four decimals under every spawn rule tried — `2:0.3556 4:0.3285
7:0.3156`, everything else at or below `0.0002` — with a half-to-half drift of `0.00005`. Two
things follow: the chain is ergodic, and 99.9% of a warm flock's time is on the three stable
edges. **Between-seed spread is 0.0138**, so a seed keeps nothing individual about where it
started.

| | UNIFORM (the simulation's rule) |
| --- | --- |
| within 1.0 sigma | tick 50 — immediately |
| within 0.5 sigma | tick 350 |
| within 0.1 sigma | **never** |
| at its permanent plateau | **tick 500** |

The envelope over 250-tick blocks: `0.430, 0.185, 0.120, 0.111`, and then flat — `0.117` at
2,750, `0.109` at 19,000. **Everything a warm-up can do is done by tick 500**, and the ten-fold
longer 5,000 buys a 6% reduction in a residual that is not going anywhere.

### Why 0.1 sigma is unreachable: the flock's phase is conserved

**A boid advances exactly one step per tick along a loop of fixed length, so its position along
the route at tick `t` is its spawn position plus `t`.** The flock's distribution over phase is
therefore carried, not mixed, and the mean occupancy at a given tick is *periodic* rather than
convergent. Measured: the autocorrelation of edge 2's occupancy deviation is **0.99 at a lag of
550 ticks — two stable cycles, `2 x 275.29` — still holding at tick 20,000**, which is 73 laps.

A warm-up is a time shift, and a time shift cannot flatten a periodic function. What warm-up
*does* fix is the part of the spawn that is not about phase: boids placed on the six edges a warm
flock never occupies have to migrate onto the stable cycle, and that is the 0.430 falling to
0.111. The residual decays on a time constant of order **2 x 10^5 ticks**, two orders of magnitude
past any warm-up worth running.

### A better spawn beats any warm-up

Both alternatives place boids from stable+ rather than over the whole map. Neither needs warming.

| rule | plateau | vs UNIFORM's plateau |
| --- | --- | --- |
| `UNIFORM` | 0.1098 | — |
| `STABLE_PLUS` — uniform over the stable+ set | **0.1445** | **worse** |
| `TAU_UNIFORM` — uniform by tau along the stable edges, matched into stable+ | **0.0793** | **28% better** |

**`STABLE_PLUS` is worse than the rule it replaces, and permanently.** Stable+ is a *set*, not a
measure: its states fall `2:9,284 4:3,874 7:6,819`, or `0.464 / 0.194 / 0.341`, against a long-run
`0.356 / 0.328 / 0.316`. Edge 4 is under-sampled by 40%, and because phase is conserved that
error is carried for the life of the run rather than washing out. Sampling a set uniformly is not
sampling the route uniformly.

**`TAU_UNIFORM` is below `UNIFORM`'s plateau from tick 0** and reaches its own within 10% by tick
250. Its residual is not zero either: uniform-by-tau is not quite the stationary distribution,
which sits at `0.356 / 0.328 / 0.316` against the length-proportional `0.365 / 0.341 / 0.294` —
edge 7 carries 2 points more occupancy than its clock length asks for. **Spawning from the
measured long-run occupancy** should beat all three, and has not been tried.

**Rebasing tau across a vertex needs the edge's own zero, not `tickLo`.** An edge's observed tau
overruns both its ends — dabeone's edge 2 runs `-16.10` to `101.70` against a length of `100.59` —
because follow-through and arrival states sit on it before its start and after its end. Tau is
already zero-based; `tickLo` is the fringe, and rebasing on it shifts each edge's frame by a
different amount. That skewed `TAU_UNIFORM`'s spawn across the vertices until it was caught.

## Canonical states

A state becomes **canonical** when every other state for that seed at the same tick has been
pruned. Only canonical overrides enter the corpus, which is what makes a label a complete record
of what was chosen rather than of what was explored.

## Control

**Control is a warm-up diagnostic, not a benchmark.** Per-seed control scores are recorded, and
mean control over time is a useful probe of warm-up behaviour; it need not have decayed to its
steady state, but it should be **flagged if it has not decayed to substantially below the excess
score**. Excess score is measured against the *mean* control, not the per-seed one.

On dabeone at WARM 5,000 the flock never scores unsteered, so every point of occupancy in a plan
is psyboid-caused. That is convenient and, per the section above, also a warning.

## Score

**Score is stored as an integer and read as an occupancy rate.** One point is one boid in a
scoring zone for one tick, so dividing by the flock size gives the mean fraction of the flock
scoring at any moment — a quantity that means the same thing across flock sizes and run lengths,
which raw score does not.

Four rates are worth having, and `plans.tsv` carries all four:

| column | what it is |
| --- | --- |
| `occFlock` | the whole flock |
| `occPsy` | the psyboid alone — already a rate, being one boid |
| `occOthers` | everyone else, which is what a psyboid is supposed to be moving |
| `occControl` | the same seed with no psyboid at all |

**The split is the point.** A psyboid can raise the flock's score by herding the others into a
scoring region or by flying into one itself, and total score cannot tell those apart. Measured on
`PLANS_40` under physics 3:

| | occupancy |
| --- | --- |
| flock | 0.0581 |
| psyboid | 0.1125 |
| others | 0.0400 |
| control | **0.0000** |

So the psyboid scores at about **2.8× the rate of the boids it is herding** — it does much of the
work itself — but the others' 0.0400 against a control of exactly zero means **all** non-psyboid
scoring is psyboid-caused. The herding effect is real and this isolates it. **Two of forty plans
move nobody at all**, scoring purely by parking; that tail is worth watching and is invisible to
any total-score measure.

> **A three-seed smoke test said 2 of 3 rather than 2 of 40.** Its usable window is 249 ticks
> against 2,739, and short windows favour parking because herding takes time to pay. Do not read
> occupancy off `SMOKE`; it exists to show the pipeline is intact.

**Watch for flock occupancy rising over a run.** On some maps psyboids lock into high-scoring
stable configurations, which is not bad in itself but means later ticks are more homogeneous
across seeds. Whether it is happening should be checked rather than assumed.

## The scoring floor

**A corpus that scores well should score predictably**, and the thing to check it against is the
score of **one psyboid alone on the map**. A psyboid in a flock can always fall back on flying the
scoring loop itself and ignoring everyone else, so one plan may be worse at *herding* than another
but none should be worse than a boid with nothing to herd.

`SimTest.scoringFloor` measures it, writing `floor.tsv` beside `plans.tsv`. The comparison is
**exactly paired**: `Boids2DEngine.init` draws boid 0 first and boid 0 is the psyboid, so a seed
flown at flock size one starts the psyboid in the same place and heading as the same seed flown
with the whole flock. Each row is one timeline with and without company, not two samples.

**On dabeone the floor is exact, and it is a map constant:**

| | |
| --- | --- |
| scoring pass | **54 ticks**, sd 0.00 over 40 seeds |
| period between passes | **533 ticks**, sd 0.00 |
| solo rate | **54 / 533 = 0.101313** per tick |
| unsteered, alone | **0.000000** — every point a solo psyboid scores is override-caused |
| flock of 4 floors at | **0.025328** occupancy |

Not approximately: forty seeds, forty identical numbers. The lone psyboid flies `4 2 1 5 8` and
scores on the same 54 ticks of it every lap. The flown 533 against the clock's 528.30 for loop
`[4, 2, 1, 5, 8]` is a 0.9% disagreement, inside the clock's own 1.65% `sd/mean` — see `EDGES.md`
§6.

**The corpus respects it.** Measured on `PLANS_40` in settled terms:

| | mean | sd | cv | min | max |
| --- | --- | --- | --- | --- | --- |
| psyboid rate | 0.10113 | 0.00081 | **0.8%** | 0.09925 | 0.10295 |
| psyboid pass | 53.85 | 0.40 | 0.7% | 53.00 | 54.50 |
| psyboid period | 532.50 | 1.80 | 0.3% | 526.50 | 535.20 |

**0.998x the solo rate on average, 0.980x at worst.** So the flock costs the psyboid under 2% of
its own scoring and never more — the boids it is herding do not get in its way. The lowest plan of
the forty scores 0.10019 per tick, 0.989x the floor, and 0.02505 occupancy against the floor's
0.025328; both are the two parked plans, where the psyboid scores and nobody else does.

### The window inflates every rate by 12%, and that is where the "spread" comes from

**Read off the usable window instead of off whole laps, the same solo psyboid reads 0.11350 per
tick with a 4.6% spread across seeds.** Both numbers are artifacts of the window: it is 2,739
ticks against a 533-tick lap, so it holds 5.14 laps and catches either five passes or six
depending on where its edges fall. Six passes in 2,784 ticks reads as one per 464 when the boid
is coming round every 533.

Two consequences worth carrying:

- **The seed-to-seed variation in the psyboid's own rate is not variation.** It is the fifth-or-
  sixth-pass boundary. Divided by whole laps the cv is 0.0% solo and 0.8% in a flock.
- **Every occupancy figure in the table above — flock 0.0581, psyboid 0.1125, others 0.0400 — is
  windowed**, and so is high by about this much. That is the right measurement for *what a case
  is drawn from*, and the wrong one to quote as an asymptotic rate. The 12% is measured for the
  psyboid's own scoring; whether the others' 0.0400 carries the same bias is untested, since
  their scoring has no reason to be periodic.

**A window edge cuts a pass in half, and the halves must not be counted.** Seed 8's solo window
opens seven ticks before the boid leaves a scoring region, and counting that fragment as a pass
drags its mean pass from 54 to 44.6 and its period from 533 to 486 — which reads exactly like a
second, shorter scoring loop that does not exist. `Passes` therefore discards any pass already
under way when the window opens, and measures between the first and last pass *start*.

**The variance that is real is herding**, and it is large: `occOthers` has a cv of 49.5% and
`occFlock` 26.6% against the psyboid's 0.8%. That is the axis a psyboid algorithm should be judged
on, and the only one where seeds genuinely differ.

## Impactful ticks

`impactful` counts ticks where the psyboid's turn **after the collision veto** differed from what
the flocking rules alone would have produced.

**This, not the override count, is what a psyboid spends.** An override the map refuses, or one
the flock would have obeyed anyway, costs nothing and changes nothing. `PLANS_40` averages **126.2
impactful ticks per plan** over a 2,739-tick usable window — under 5% of ticks.

## Length and midstream data

Decay into hyper-stable patterns is map- and psyboid-dependent, and where it happens it is an
upper bound on how long a seed keeps yielding useful data.

**Midstream** is the window worth sampling: it opens once the psyboid has had a chance to matter —
the same kind of criterion as the warm-up — and closes at the last canonical state. `plans.tsv`
records it as `usableFrom` and `usableTo`.

## Judging a psyboid algorithm

A psyboid algorithm not on the **Pareto frontier of (flock occupancy, −impactful ticks)** is not
useful: anything off it is beaten on both counts by something already known.

There is likely a natural exchange rate between the two that implies a total ordering; if there is
not, a tunable parameter should be introduced to make algorithms comparable rather than leaving
the frontier as the last word.

**The canonical psyboid is on the theoretical frontier.** Everything built here approximates it,
and should be described as an approximation rather than as a policy in its own right.

## Presets

| preset | seeds | warm | run | what for |
| --- | --- | --- | --- | --- |
| `SMOKE` | 3 | 5,000 | 600 | checking the pipeline is intact after a change. **Not a sample** |
| `PLANS_40` | 40 | 5,000 | 3,000 | the standard corpus |

Search settings are `PsyboidBits.standard`'s: spread 640, lookahead 320, alpha 0.95. Those are
measured rather than chosen — 640 is a floor at which the answers stop moving, not a free
parameter; see `PsyboidBits.Config`'s javadoc.

## Current state, physics 3

`PLANS_40`, dabeone `609cffdb84be218c`: 40 plans in 5 s, all 40 score, all 40 beat their own
control, mean 0.23242 per tick. Fidelity **248 turns asked, 248 crossed, 0 crossings nobody asked
for, 40 of 40 plans matching branch for branch.** Usable window 2,739 ticks per plan.

**Regenerated 2026-09-05 and byte-identical to the previous build** apart from the timestamp
comment, which is the reproducibility check the addressing exists to make possible. Against the
scoring floor: every psyboid within 2% of what it scores alone, and the two parked plans sitting
on the floor itself.
