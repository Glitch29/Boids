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
> Not changed. **Edge-occupancy decay is the better proxy** — how long until the distribution of
> boids over edges stops moving — and measuring it is the thing to do before touching WARM.

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
