# Session log

What each session attempted, what the numbers were, what changed, and what is now known
broken. **Newest entry at the top. Append before the session ends.**

Conclusions belong in the document that owns them — `README.md`, `EDGES.md`, `ROADMAP.md`,
`HINTS.md`. This is the audit trail, not the state. If an entry here is the only record of a
finding, it is in the wrong place.

Format: date, what was attempted, what came out, what changed on disk, what is open.

---

## 2026-09-07 — why the searches lose to `route` on plait: found, and not worth fixing here

The user's framing: `route` is a policy the search could pick, so either it is never considered or
it is wrongly pruned, and either is a bug. **Never considered.**

`PsyboidBits.walk` forks on a **change of edge**, with `was` seeded to the edge the root is already
on. After committing, `search` advances the root past the override — and on plait, whose branch
edge is 756 ticks against a 1,597-tick spread, that routinely lands mid-edge-1. The decision the
psyboid is in the middle of is invisible; the boid coasts through the branch and the plan records a
decline it never considered. Dabeone's ~100-tick edges mean the root seldom occupies one branch
edge for a whole advance, which is why the gap does not bite there.

**Tried the obvious fix and reverted it.** Seeding `was = -1` at the top level (recursive calls
keep the forked edge, or the same visit re-forks every tick and the tree explodes):

| | before | after |
| --- | --- | --- |
| plait-4 `bits` | 0.003970 | 0.004818, +21% and still under half of `route`'s 0.010095 |
| dabnt-4 `bits` | 0.025068 | 0.022223, **−11%** |
| plait-4 compute | 0.0057 s/1000t | **3.55, over the budget of 1** |

A root fork fires on most calls, so the outer loop commits far more often and each commit pays for
a whole tree. Reverted; the gap is documented at the line that causes it and in `ROADMAP.md` §0h.
The branch structure wants respecifying rather than patching, which the user is doing.

**Baseline confirmed unchanged** after the revert — plait-4 reproduces 0.003970 / 0.004172 /
0.003832 / 0.007983 exactly. Also added a `-Donly=<scenario>` filter to the benchmark driver.

---

## 2026-09-06 (latest) — "bad aim" was a bug, and one step of lookahead is all of navigation

The user rejected the previous session's finding 3. The claim: the edge axiom makes a missed exit
impossible for anything that simply avoids stepping onto a wrong edge, so **bad aim is not an
available explanation** — if an exit is missed, either the decomposition is broken or the code is.
Both halves were checked and the user was right on both.

**The decomposition is fine.** Added `Pipeline.checkNavigable`: from every live state of every
edge, does some single turn stay on the edge or reach the chosen exit? **It holds on dabnt,
dabeone and plait — every arc, every state, under 0.05 s a map.** It now runs on every build and
throws on violation.

**The pilot was wrong.** `EdgePilot` carried a 0-1 BFS per edge for the fewest non-straight ticks
to the exit and steered only when that number said to. Two faults, one fatal: it guarded against
*failing to reach the target* and **not** against being pushed onto some third edge, and its "no
route from here" case **returned silently**. Replaced by the rule itself — leave the flock's
request alone if its successor stays on the edge or reaches the target, else take the first turn
that does, else throw.

| | occPsy before | after | of the price gain |
| --- | --- | --- | --- |
| dabnt-4 `route` | 0.059910 | **0.096660** | 54% → **88%** |
| dabeone-4 `route` | 0.082070 | **0.097140** | 77% → **91%** |
| plait-4 `route` | 0.032300 | 0.032290 | 69%, unchanged |

**A third of the psyboid's score on the dab-like maps was going to that bug**, and compute fell 14x
with the BFS gone — `route` now runs at 0.0004 s per thousand ticks, two and a half thousand times
under budget.

**Standings changed.** `route` now matches the searches on the psyboid's own score everywhere,
wins dabnt and both plaits outright, and is beaten only on dabeone — where the searches earn it in
`occOthers`, 0.0447 against 0.0310, which is real herding rather than better flying. Full table in
`ROADMAP.md` §0h. `piloted` is the most efficient entrant on all four scenarios and the
highest-scoring on none.

**Recorded.** `EDGES.md` §7 gains the invariant with its proof and the verification;
`HINTS.md` §10h gains the general form — a held turn is not navigation, because the axiom promises
nothing about always-left, always-right or always-straight, and all three constant policies can
lead to the same wrong edge.

**Open.** The searches' remaining advantage on dabeone is bought with 1.7x the override ticks, and
`piloted` still declines decisions that `route` takes — once execution was equalised, the search's
declines look like mistakes. Whether that is the value function or the branch set is the next
question.

---

## 2026-09-06 (very late) — the benchmark, and three findings that point different ways

The user asked for metrics on the best psyboid alongside named controls. Built `Bench` and ran it:
four scenarios, 20 seeds x 5,000 ticks each, every entrant on the same seeds.

**dabnt is available.** It takes **dabeone's gate unchanged** — `x=202, y=[174,191]` decreasing,
9 edges, ingest `48b46d3d06e54c75` — found by trying that first and then sweeping vertical lines;
nothing else decomposed sanely. Same corridors with and without a trap, so a gate that cuts every
cycle in one cuts every cycle in the other.

**The lookahead is derived now, not inherited.** 320 ticks at alpha 0.95 per second were fitted on
a 506-tick lap; on plait's 1,738-tick lap they discount every decision's payoff to 0.006 of face
value. Both come from the map: lookahead is one optimal scoring lap and
`alpha = 0.4 ^ (SECOND / lap)`, so a point at the end of the horizon is worth 0.4.

> ⚠ **A harness bug caught before any figure was quoted.** The search commits decisions for as long
> as its `run` asks, and the first version passed 100,000 while flying 5,000 — twenty times the
> planning actually used, charged to the algorithm's compute budget. Measured cost 0.38 s per
> thousand ticks against a true 0.009, a factor of forty-two, with nothing else about the output
> looking wrong. In `HINTS.md` §10g.

**Full table in `ROADMAP.md` §0h.** Three findings:

**1. The potential works and buys a budget increase for free.** On dabeone `priced/640` reaches
**0.057308** against `bits-ultra/1280`'s 0.057203 — the same score at **one sixth the compute** —
and herds hardest of anything measured, `occOthers` 0.044697.

**2. On plait every search loses to a psyboid that never searches, by 2.5x**, and the gap is in the
psyboid's own score rather than in herding: `route` 0.0317 against 0.0134. **The searches are not
failing to decide; they are failing to execute.** A held turn is a schedule, and on plait the
prediction runs up to 775 ticks ahead against eight ticks of margin, so the turn misses. The
derived lookahead fixed the search's judgement there; nothing had fixed its aim.

**3. The route pilot underperforms its own ceiling in a flock and the scheduled search does not.**
`route` flies 56% of gain on dabnt and 76% on dabeone where `bits` flies 91%, while the same pilot
alone flies 94-100%. **Being inert on 99.65% of ticks was sold as a virtue and is partly a defect**
— it waits until steering is strictly necessary, and arrives at "strictly necessary" already
displaced.

> ⚠ **Finding 3 is wrong and was superseded the same day.** The cause was a bug in the pilot, not a
> property of piloting: it guarded against failing to reach its target and not against being
> pushed onto a third edge, and gave up silently. Fixed, `route` flies 88-91% of gain. See the next
> entry up and `EDGES.md` §7.

**Built in response: `piloted`** — the search decides, a pilot executes, each committed decision
becoming an `EdgePilot` over its own stretch of timeline carrying the route rather than the tick.
On plait it doubles the search's psyboid score (0.0271 against 0.0134) and lifts flock occupancy
from 0.0040 to 0.0080. On dabeone it lands *below* `route`, which isolates a fourth thing: once
execution is equalised, **the search's declines are mostly mistakes** — `piloted` is `route` minus
the decisions the search chose not to take, and it is worse for them.

**And then the pilot fix that finding 3 asks for**: spend the turns *immediately* rather than at
the last moment. The cost is the fewest non-straight ticks still needed and some turn always
reduces it by one, so they can be spent up front at no extra cost in ticks, leaving the rest of the
edge as slack for the flock to push the boid around in.

**Compute is not the constraint anywhere.** The most expensive entrant runs at 0.06 s per thousand
ticks against a budget of 1.

---

## 2026-09-06 (late night) — distance and rebasing in (edge, tau), and the phase ledger

Three corrections and two builds from the user, all landed.

**Corrections taken.** EV is for valuing a terminal node; earlier in the tree, where compute is
available, **branching answers the question about an exit definitively** — so branch and prune
rather than estimating. Windows are recorded against the *led* boid's edge and name a leader
elsewhere, so cross-edge comparison needs a helper. And override ticks will eventually be budgeted,
so phase shifts want ranking per tick spent.

**Built `EdgeReach`** — forward distance and forward rebasing. `advance` is the rebase everything
else is built from; `unsteered` is coasting ticks and is **absent when coasting never arrives**,
which is the normal case for a window on an unstable edge; `steered` is Dijkstra over the edge
graph; `willLead` does all three rebasings in one call.

Checked against something known independently: **dabeone's circuit back to edge 1 reads 528.3
steered against 528.3 from summing the optimal cycle's clock lengths.** Absence works too — plait
`1->5` and dabeone `2->1` both return absent, since coasting from a stable edge never leaves the
stable cycle. And plait's shortest circuit back to edge 0 is **811.5, the bypass, not the 1,703-tick
scoring loop**: shortest is not the route the psyboid wants, which is worth remembering at the call
site.

> A bug found and fixed on the way: asking for the position you already occupy returned **0**
> rather than a circuit, because the same-edge shortcut tested `tau >= tau`. "When am I next here"
> has an answer; "how far to where I am" does not.

**Built `PhaseShift`** — one dynamic program per edge over `(state, budget)`, for the fewest and
most ticks to leave **by the same exit coasting takes**. Same exit is what makes it a phase change
rather than a route change. Under half a second for both maps.

**Both of the user's predictions came out, which was the test.**

| plait | span | hurry | dawdle | per tick |
| --- | --- | --- | --- | --- |
| **edge 0** | **64** | **53** | 11 | **2.21** — 3x the next edge |
| edge 1 | 30 | 11 | 19 | 0.79 |

**The bulb popped out**, with the sign the other way round from expected: coasting on edge 0 sits
**83% of the way up its own range**, so the coasting line already takes the wide way round and the
53-tick longcut is the one the boid is already on. Measured against coasting it prints as hurry.
Operationally the useful form is that a psyboid there can arrive **53 ticks early and only 11
late**.

| dabeone | span | hurry | dawdle | per tick |
| --- | --- | --- | --- | --- |
| edge 5 | 27 | **18** | 9 | 0.75 — the one real shortcut, coasting 67% up |
| **edge 7** | **10** | 5 | 5 | **0.33** — lowest on the map |

**Edge 7's "modest difference" is 10 ticks**, exactly as predicted, and dabeone is on rails for
hurrying: 63 ticks of hurry map-wide against 133 of dawdle, with every edge but 5 having coasting
low in its own range.

**Vacuity filter dropped from the conversion trial.** `SolverFacts.VACUOUS` was live only in
code written this week plus one render entry point. Including every band moves the headline
conversions by under a point — 30.0 / 13.2 / 27.5 / 41.2 / 11.0% against 29.3 / 14.1 / 26.6 / 41.9
/ 11.2% — so it was near-inert, while the per-width table says the real thing: plait's bands over
300 ticks convert at **2.2%**. The constant is left alone rather than retuned; a measured
conversion is a better answer than a fraction of an edge.

**Open.** The search itself: fork on a trigger — a boid entering a window's tau range with the
psyboid able to reach a leader edge — fly both branches, prune on what happened, and fall back on
the bias only at leaves. The three inputs are now in place. **Headspace** remains untouched; the
user has taken the gate as a personal to-do and confirmed a radius of 40 for every map but
hamburger, and that a scoring region can serve as the gate.

---

## 2026-09-06 (night) — windows convert, and the conversion table is the estimator

The user asked two things: whether plait actually has usable windows, and — the sharper one —
noted that **the ability of a psyboid to convert a window into an exit has never been tested.**
Everything so far has attributed exits that happened. Both answered.

**Built `Herding`.** An inventory of every non-vacuous band with the psyboid's cost to stand in
it, and `trial`, which puts a leader inside a band, flies two boids, and counts exits — against a
leader placed outside the band on the same edge, and against no leader at all. **About a second
per map.**

**Windows are levers, not descriptions.** First forward test in the project, and it passes:

| arc | leader in band | outside, same edge | alone |
| --- | --- | --- | --- |
| plait `0->3` | **29.3%** | 2.1% | 0.0% |
| plait `1->5` | **14.1%** | 1.7% | 0.8% |
| dabeone `2->1` | **26.6%** | 1.5% | 1.6% |
| dabeone `4->0` | **41.9%** | 0.0% | 0.0% |
| dabeone `5->6` | **11.2%** | 0.3% | 0.0% |

**Conversion is dominated by the leader's edge, not the band's width.** Dabeone's edge 0 carries a
16.1-tick band on `2->1` converting at **1.0%** — below its own 2.4% control — and a 33.3-tick
band on `4->0` converting at **86.9%**. Width is a confounder. The per-edge table is therefore the
estimator itself, and it is cheap enough to compute per map at pipeline time.

Best converters: dabeone `4->0` from edge 0 (86.9%) and edge 1 (69.8%); `2->1` from edge 1
(66.2%) and edge 6 (45.1%). Plait `1->5` from edge 3 (26.7%), edge 5 (24.2%), edge 2 (17.8%).

**Plait is not boring.** Its windows convert, and for the arc that matters — `1->5`, which pulls a
boid off the non-scoring stable cycle onto the scoring route — the best on-cycle leader edge is
**5, at 24.2%**, which is exactly where the psyboid stands immediately after taking that exit
itself. **The natural configuration is the productive one: exit first and the boid behind may
follow.** Leading costs phase rather than a detour. `0->3` is the mirror image and a hazard: it
pulls a boid off edge 0 onto the bypass, away from scoring, and converts at 29.3%.

**Found: `SolverFacts.VACUOUS = 0.9` is far too permissive on a long edge.** Plait's bands wider
than 300 ticks convert at **1.7%** against an overall control of 2.1% — no better than nothing —
yet they pass, because 439 ticks is only 58% of a 756-tick edge. Half of a long edge is not a
constraint. **Not fixed**: tightening the constant changes every window on both maps, and the
right replacement is a conversion measurement rather than another fraction. In `EDGES.md` §7.

**Also checked:** a coincident second boid is invisible to `MovementLogic.perceived` (`d2 == 0`
returns "cannot see"), so the alone control is genuinely alone rather than maximally repelled.

**Open.** The obvious next move is to branch on the conversion table: hold the pilot's route as
the default and fork only where a boid is inside a window's tau range and the psyboid can reach a
leader edge worth the detour. The bias gives the exchange rate (`h(5) - h(4) = 37.46` on plait) and
the table gives the probability, so the two multiply into an expected value a branch can be scored
on. **Headspace** is untouched — it needs a turning radius and a gate, both judgement calls.

---

## 2026-09-06 (evening) — the price function, and a psyboid that flies a route

The user set the project for the next several turns: **maximum corpus score on plait, under one
second per thousand ticks**, with no training on full plait simulations. And named plait's failure
correctly — it is a **success of map design**, built to obfuscate the score implied by edge
occupancy and so the value of a pathing decision.

**Diagnosed, and it is deeper than §0g's spread.** A turn at the end of plait's edge 1 does not
reach a scoring pixel for ~890 ticks. At `alpha` 0.95 per eight ticks that is worth 0.006 of face
value, so the search compares two identical futures, ties, and declines. **The spread floor let it
see the turn happen; it still could not see the turn pay.**

**Built `EdgePrice`.** Per edge, coasted traversal ticks and how many of them score; every simple
cycle; then the average-reward gain and bias. No simulation anywhere in it.

| | plait | dabeone |
| --- | --- | --- |
| best cycle | `[0,2,1,5]` 1,738.4t, 80.8 score | `[1,5,8,4,2]` 505.7t, 53.9 |
| **gain** | **0.046471** | **0.106634** |
| bypasses | `[0,3]` 821.6t, `[1,4]` 806.1t, both scoring nothing | `[2,7,4]`, `[3,5,6]` |

> **Value iteration does not converge here and fails quietly.** The transition graph is
> deterministic, so the recurrent class is a bare cycle and perfectly periodic; synchronous sweeps
> oscillate with that period forever. The first run had the *policy* right and the *values* four
> sweeps out of phase, so `exit[1]` pointed at the bypass when the exit was worth 75 more. Fixed
> by charging the gain into each arc's weight and taking longest paths — every cycle then weighs
> at most zero, and Bellman-Ford settles in `n` rounds. In `HINTS.md` §10d.

**Abstracted the override.** `PsyboidOverride` is now an interface with `from`/`to`/`asks`/
`actsAt` — every question any of the twelve call sites actually asked. `HeldTurn` is the old
concrete kind. A latent design limit is now explicit: a held turn is a *schedule*, so a plan must
know in advance when the boid arrives somewhere, and on 750-tick edges the slip exceeds the
window.

**Built `EdgePilot`** — an override carrying a **route**. Each tick it reads where the boid is,
looks up `EdgeNavigation.steerCostTo`, and asks for nothing when coasting already takes the exit.
Self-correcting, and **inert on 99.65% of ticks** (138 steers in 40,000). Cost tables are built
only where the route differs from `straightTo`, which on both maps is one edge.

**The price function describes the physics.** One boid, no flocking, flying the route:

| | flown | gain | |
| --- | --- | --- | --- |
| plait | **0.046575** | 0.046471 | **100.2%** |
| dabeone | 0.100440 | 0.106634 | 94.2% |

Coasting scores exactly zero on both. Dabeone's shortfall is the estimate, not the physics: a lap
flies in 533 ticks against the 505.7 a coasting traversal predicts, and 0.100440 sits on the
independently measured solo floor of 0.101313. **A price function built on coasting runs about 5%
optimistic.**

**In a flock (4 boids, 20 seeds, 5,000 ticks, `TAU_UNIFORM`, pilot on boid 0):**

| | control | piloted | psyboid | each other | previous corpus |
| --- | --- | --- | --- | --- | --- |
| plait | 0.000810 | **0.044500** | 0.040440 | 0.001353 | 0.00630 |
| dabeone | 0.000550 | 0.170510 | 0.078680 | 0.030610 | 0.20100 |

**Plait is 7x the corpus it replaces, and it is entirely selfishness** — each other boid gets
0.001353 against a control share of 0.000203. The flock ceiling if all four flew the route is
0.185886, so this reaches 24% of it. On dabeone the naive pilot is *worse* than `PsyboidBits`
(0.171 against 0.201), which is the same fact seen from the other side: a search that watches the
flock finds herding; a pilot that only reads the map does not.

**Compute: 0.001 s per 1,000 ticks — a thousandfold under budget.** Everything left is a question
of what to search for, not of how much searching is affordable.

**Open, and the next move.** Herding. `SolverFacts.Window` already gives plait leader bands for
both arcs (`0->3` opening at tau 756, `1->5` at 748), and the bias gives the exchange rate:
`h(5) - h(4) = 37.46` says moving one boid off the bypass onto the scoring cycle is worth 37 ticks
of gain, so a psyboid should trade most of a lap for it. That is now a number rather than a guess.

---

EOF
## 2026-09-06 (later) — end-to-end wiring, plait from its PNG, and the spread that failed silently

The user closed §0f: **`TAU_UNIFORM` and total edge length over speed are the defaults**, the goal
is a sufficiently obfuscated history rather than equilibrium, and the mechanism behind the endless
decline is that **exits add entropy to phase offsets while non-exits do not** — non-exiting
configurations lock in, exiting ones re-randomise. That is an absorbing dynamic, which is why
there is no floor. Recorded in `ROADMAP.md` §0g.

Then: bring plait up to those choices, and get as far as a function that makes a corpus from a map.

**Built.**

- **`Spawn`** — the rules promoted out of `EdgeOccupancy` and threaded through every replay path,
  because a replay that spawns differently does not reproduce the timeline whatever the label
  says. Carries a bound engine, so nothing rebuilds a navmap per seed.
- **`CorpusPreset.Warmup`** — a policy, not a number, since the value is a function of the map.
  `TOTAL_EDGE_LENGTH` is the sum of the clock's lengths: **941 on dabeone, 1,814 on plait**.
- **`CorpusPreset` gains the spawn rule**, so both are in the recipe and so in the address.
  `SMOKE` and `PLANS_40` take the new defaults; **`LEGACY_40`** is the old recipe, kept so every
  pre-2026-09-06 figure stays reproducible.
- **`Pipeline`** — a map and a gate in, a verified corpus out, every tier derived on the way.
- **plait ingested from its PNG**, `46f880d41d2c1e4e`, 6 edges, stable `{1,4}`, scoring
  `{0,2,3,5}`, total edge length 1,813.57.

**Both maps now run end to end from map + gate.** Dabeone under the new defaults: 40 plans, 40
score, 40 beat control, 252/252 fidelity, flock occupancy 0.0502, control 0.0004. Plait: 40 plans,
9 score, 8 beat control, 8/8 fidelity, 2.0 impactful ticks per plan.

**The finding: the spread was a dabeone constant and it failed silently.** Plait's first corpus had
*zero turns asked* despite having a searchable branch. The search forks when the psyboid arrives on
a branching edge and the override fires a further *coast* ticks later; plait's branch edge is 756
ticks long so the coast runs to 775, and a walk that stops 640 ticks after its root never sees the
branch taken. Both children of the fork tie, and **the tie goes to declining** — so the plan
records a decision it never really had. Measured over eight plait seeds: **0 turns at 640, 960 and
1,280; 2 at 1,600; 9 at 2,400.**

`PsyboidBits.minimumSpread` now derives it as one stable lap + the furthest a branch edge's
critical state can be + the longest hold: **1,597 on plait, 398 on dabeone.** The recipe takes the
larger of that and its named 640, so the dabeone measurement stands where it was measured. This is
a change to a measured constant and is flagged as one: 640 remains correct *for dabeone*, and what
was wrong was treating it as a property of the search rather than of the map.

**The blockers, now named rather than suspected.**

1. **The gate.** Human judgement, silent when wrong.
2. **`PsyboidBits` only tries holding right**, and drops everything else without a word. Added
   `PsyboidBits.reaches`, which tries both: **plait `0->3` needs a left hold of 8 ticks** — half of
   plait's psyboid is invisible — and **dabeone `5->6` needs a left hold of 14**. The latter makes
   `PsyboidBits`' javadoc claim that no hold reaches it **wrong**; the cold-start argument for
   dropping `5->6` is separate and may still stand. Reported, not fixed: searching both holds is a
   change to the psyboid algorithm, which has not been chosen.
3. **No generic psyboid override-generating algorithm**, as the user already had it.
4. **Run length is a dabeone number too.** `SMOKE`'s 600 ticks is shorter than plait's 811-tick
   lap, so it yields three replayable plans and zero turns. Noted, not given a floor.

Nothing else blocks it: ingest, navmap, decomposition, clock, per-edge navigation, solver facts,
stable+, warm-up, spawn and corpus all derive from the map.

**Javadocs corrected where they relied on scoring implying psyboid.** That was always a
correlation and it looked like a rule only because the 5,000-tick warm-up had settled the flock
out of steering itself: control was identically 0.0000, and at a map-derived warm-up it is 0.0004.
`GLOSSARY.md`'s *control* entry, `PsyboidCorpus`'s `Corpus` and `occupancy` javadocs, `CORPUS.md`
and `HINTS.md` §9a now say to read excess over control, and to prefer `lifted` over `scoring`.

**A latent bug fixed on the way.** `PsyboidCorpus.fidelity` flew `PsyboidBits.WARM` rather than the
recipe's warm-up. Harmless while every recipe used 5,000; wrong the moment one did not.

**Open.** Six `SimTest` audit entry points still replay corpus plans at `PsyboidBits.WARM` instead
of at the corpus's own warm-up — fine for `LEGACY_40`, wrong for anything else, and they should
take the warm from the corpus they read.

---

## 2026-09-06 — the warm-up's own criterion, re-measured: it has no floor and never terminates

The user pointed out that non-scoring is equivalent to all edge occupancy sitting on `{2, 4, 7}`,
so `PsyboidBits.WARM`'s criterion should be inferable from yesterday's data, and that the javadoc
carrying it looked stale. **Both right, and the second more so than expected.**

**The equivalence, confirmed from the decomposition rather than assumed.** Dabeone's stable edges
are `{2, 4, 7}` and its scoring edges `{0, 1, 3, 5, 6, 8}` — disjoint and exhaustive, so a flock
that stays on the stable cycle cannot score. `EdgeOccupancy.run` now prints both sets, since
everything downstream reads "off the stable edges" as "could have scored".

**What was missing and is now measured.** Yesterday's run stored cross-seed *mean* occupancy, not
a per-seed indicator, so it could not answer "what % of seeds had a boid off those edges". Added:
`Decay.offEarly` / `offRate` per window and `offLate` for the long run, plus
`EdgeOccupancy.warmupScoring`, which flies each seed once and reports, per candidate warm-up,
the fraction of seeds that score and the fraction that leave the cycle.

**2,000 seeds, physics 3, 4,000-tick run from each start:**

| start | 0 | 500 | 1,000 | 2,000 | 5,000 | 10,000 |
| --- | --- | --- | --- | --- | --- | --- |
| scores unsteered | 98.8% | **16.4%** | 8.5% | 7.0% | **4.8%** | **2.7%** |
| leaves the stable cycle | 99.0% | 17.3% | 8.9% | 7.8% | 5.1% | 2.8% |
| *javadoc, physics 2, 40 seeds* | *100%* | *45%* | — | *25%* | *12.5%* | *12.5%* |

**Every level was about 2.7x high, and the claimed floor does not exist.** The javadoc read 5/40
at both 5,000 and 10,000 and concluded those were seeds that score "however long you wait, a
property of the map rather than of the warmup". The rate is still falling at 10,000 and reaches
**0.2% of 250-tick windows by tick 20,000**. Five of forty was the resolution limit of forty
samples, not a floor.

**The consequence is the finding.** This criterion never terminates — longer is always better on
it — so it cannot pick a warm-up, and 5,000 is a place someone stopped rather than a value it
implies. What it is buying is the flock tightening into a formation whose members stop pushing
each other off the cycle, which is the homogenisation `CORPUS.md`'s minimality argument says not
to optimise for, and the reason control is identically zero. **Edge-occupancy decay does
terminate, at 500.** That asymmetry, rather than either level, is the argument for using it.

**A correction to yesterday's own entry.** `ROADMAP.md` §0f said dropping to 500 would make
`occControl` non-zero for "roughly half the corpus". That came from the stale 18/40 and is wrong:
the real figure is 16.4% over 4,000 ticks and lower over the corpus's 2,739, so about **five plans
in forty**, not eighteen. The two measurements also reconcile — at `WARM = 5,000` some 3-5% of
seeds score unsteered, so 0 of 40 plans doing so is ordinary luck rather than evidence of a hard
floor.

**Two timescales, and they are different relaxations.** Occupancy bias plateaus at tick 500 and
then oscillates forever (phase is conserved). The excursion rate falls right through: 95% of seeds
per 250-tick window at the start, 5.9% by 750, 1.3% at 2,000, 0.7% at 4,000, 0.3% at 10,000, 0.2%
at 20,000. The second governs `occControl`. Also visible: **a tidily-spawned flock gets untidier
before it settles** — both stable+ rules start at 0.4-0.5% and rise to 1.0-1.5% by tick
1,000-2,000 before falling back, so a low excursion rate at tick 0 is not by itself a good spawn.

**Changed on disk.** `PsyboidBits.WARM`'s javadoc carries the re-measurement and names the
superseded figures as superseded; the constant is **unchanged**. `EdgeOccupancy` gains
`warmupScoring`, the off-stable-edge seed counts, and a printed statement of the stable/scoring
edge sets. `CORPUS.md` gains two sections and its warm-up warning is rewritten. `ROADMAP.md` §0f
gains the re-measurement and corrects its own claim. `HINTS.md` §8a gains the non-terminating
criterion. `GLOSSARY.md` and `README.md` updated.

**Open, unchanged.** `WARM` still needs a decision — `ROADMAP.md` §0f has three options, and
option 3 (keep 5,000 deliberately) is harder to defend now that the scoring criterion has no
natural stopping point either.

---

## 2026-09-05 (late) — edge-occupancy decay: 500 is enough, and phase is conserved

Asked to sanity-check dropping `WARM` from 5,000 to 500–1,000 by measuring edge-occupancy decay,
and to try two alternative spawn rules alongside. **500 is enough. The bigger finding is why no
warm-up could ever do more.**

**Built `EdgeOccupancy`** — a new class rather than more `SimTest` — with three `Spawn` rules,
driven from `SimTest.main`, writing `<behaviour>/occupancy/decay-<rule>-<seeds>s<window>w.tsv`.
2,000 psyboid-free seeds, 50-tick windows, curve to tick 20,000, long run `[40,000, 60,000)`.
About 25 s a rule.

**The long run is the same under all three rules to four decimals** — `2:0.3556 4:0.3285 7:0.3156`,
everything else at or below `0.0002` — with a half-to-half drift of `0.00005`. That is an
ergodicity check nothing asked for and it passed. Between-seed spread `0.0138` against a
within-seed `0.477`, so a seed keeps nothing individual about where it started.

**The answer as asked, `UNIFORM`:** within 1.0σ at tick 50, within 0.5σ at tick 350, **within 0.1σ
never**. Envelope over 250-tick blocks `0.430, 0.185, 0.120, 0.111`, then flat — `0.117` at 2,750
and `0.109` at 19,000. **The uniform spawn is at its permanent plateau by tick 500, and 5,000 buys
a 6% reduction in a residual that is not going anywhere.**

**Why 0.1σ is unreachable, and this is the finding.** A boid advances exactly one step per tick
along a loop of fixed length, so its phase at tick `t` is its spawn phase plus `t` — **the flock's
distribution over phase is carried, not mixed.** Mean occupancy at a given tick is therefore
periodic, not convergent: autocorrelation of edge 2's deviation is **0.99 at lag 550 ticks = two
stable cycles (2 x 275.29), still holding at tick 20,000**, which is 73 laps. A warm-up is a time
shift and cannot flatten a periodic function. What it does fix is the non-phase part of the spawn
— boids on the six edges a warm flock never occupies — and that is over at tick 500. The residual
decays on a time constant of order 2 x 10^5 ticks.

**The spawn rules, plateau values:** `UNIFORM` 0.1098, `STABLE_PLUS` **0.1445**, `TAU_UNIFORM`
**0.0793**.

- **`TAU_UNIFORM` is below `UNIFORM`'s plateau from tick 0** and reaches its own within 10% by
  tick 250. A better spawn beats any warm-up.
- **`STABLE_PLUS` is worse than the rule it replaces, permanently.** Stable+ is a set, not a
  measure: `0.464 / 0.194 / 0.341` over the stable edges against a true `0.356 / 0.328 / 0.316`,
  so edge 4 is under-sampled by 40% — and phase conservation makes that error permanent instead
  of transient. Worth knowing before anything else samples a state set uniformly.

**Two measurement bugs found and fixed, both of which produced plausible wrong answers.**

- **Rebasing tau on `tickLo` is wrong.** An edge's observed tau overruns both ends — edge 2 runs
  `-16.10` to `101.70` against a length of `100.59` — because follow-through and arrival states
  sit on it outside its span. Tau is already zero-based; subtracting `tickLo` shifts each edge's
  frame by a different amount, which skewed `TAU_UNIFORM`'s spawn across the vertices.
- **The raw distance curve does not decay to zero and never could.** A mean of N seeds sits at
  `bias² + σ²/N` from what it estimates, so the curve decays to the noise floor and rattles there.
  Subtracting the pedestal is what separates "the spawn is still showing" from "the measurement
  ran out of seeds".

**`PsyboidBits.WARM` is NOT changed, and this is deliberate.** Two criteria disagree ten-fold and
choosing between them is a specification question. Edge occupancy says 500. Unsteered scoring says
otherwise — `WARM`'s own javadoc records 18 of 40 seeds still scoring unsteered from tick 500
against 5 from 5,000, so dropping to 500 makes `occControl` non-zero for about half the corpus, and
the psyboid/others split rests on a control of exactly zero. **Warming until the flock stops
scoring on its own is warming until it stops steering itself off the stable cycle**, which is
exactly the homogenisation `CORPUS.md`'s minimality argument says to avoid. `ROADMAP.md` §0f lists
three coherent options. Changing `WARM` also invalidates every corpus address, since it is in the
`CorpusPreset` fingerprint.

**Also corrected:** stable+ at quorum 5 is **20,008 states** (`2:9,284 4:3,874 7:6,819 8:31`) under
physics 3, against the **19,861** recorded in `ROADMAP.md` — that figure was taken 2026-08-30,
before physics 3 shipped, and stable+ depends on the aggregation through `expandByQuorum`. Noted,
not silently overwritten.

**Docs.** `CORPUS.md` gains three sections and is canonical for the warm-up question.
`ROADMAP.md` §0f goes from in-flight to measured with the three options. `HINTS.md` gains §8a on
phase conservation — the most general fact in the file. `GLOSSARY.md` gains phase, edge occupancy
and spawn rule, plus the analysis-table row. `EDGES.md` §9 gains warm edge occupancy.
`PIPELINE.md` step 13a gains the invocation. `README.md` updated.

**Open.** Spawning from the measured long-run occupancy should beat all three rules and has not
been tried. Neither `STABLE_PLUS` nor `TAU_UNIFORM` is wired into anything that generates a
corpus — they exist to answer this question.

---

## 2026-09-05 (evening) — the corpus regenerated, and its scoring floor found and confirmed

Asked to generate a corpus for dabeone and check that it scores well, against a stated
expectation: consistent across seeds, with a clearly defined lower bound equal to the score per
tick of a single psyboid simulation. **The expectation is right, and the bound turned out to be
exact rather than approximate.**

**The corpus regenerates byte-identical.** `PLANS_40` rebuilt under physics 3: 40 plans in 6 s,
all 40 score, all 40 beat their own control, mean 0.23242 per tick, fidelity 248 of 248 turns
crossed with 0 unbid, 40 of 40 plans matching branch for branch. Diffed against the copy on disk —
identical apart from the timestamp comment. That is the check the recipe addressing was built to
make possible, and it had not been run before.

**Built `SimTest.scoringFloor`** (+ `SimTest.scoringLaps` for one seed at a time), writing
`floor.tsv` beside `plans.tsv`. The one code change it needed was widening `PsyboidBits.search` to
take a `ScenarioParameter` rather than a `PresetScenarioParameter`, so the same search can be flown
on a flock of one.

**The comparison is exactly paired**, which is what makes the numbers worth anything:
`Boids2DEngine.init` draws boid 0 first and boid 0 is the psyboid, so a seed at flock size one
starts the psyboid in the same place and heading as the same seed with the whole flock. Each row
is one timeline with and without company.

**The floor, dabeone `609cffdb84be218c`:**

| | |
| --- | --- |
| scoring pass | **54 ticks**, sd **0.00** over 40 seeds |
| period | **533 ticks**, sd **0.00** |
| solo rate | **54 / 533 = 0.101313** per tick |
| unsteered, alone | **0.000000** |

Forty seeds, forty identical numbers. The lone psyboid flies `4 2 1 5 8` and scores on the same 54
ticks of it every lap. Against the clock's 528.30 for that loop this is 0.9% out, inside the
clock's own 1.65% `sd/mean` — a third independent check on the metric.

**The corpus respects it.** Psyboid settled rate 0.10113 ± 0.00081 (cv **0.8%**), **0.998x the
solo rate on average and 0.980x at worst**, so the flock costs a psyboid under 2% of its own
scoring. Lowest whole plan 0.10019 per tick, 0.989x the floor; lowest flock occupancy 0.02505
against a floor of 0.025328. Both are the two parked plans.

**Correction to how every corpus occupancy figure should be read.** The usable window is 2,739
ticks against a 533-tick lap — 5.14 laps — so it catches five passes or six depending where its
edges fall. Measured that way the *solo* psyboid, whose behaviour is identical in every seed,
reads 0.11350 with a **4.6% cv**. So the seed-to-seed spread in the psyboid's own rate is not
variance, it is the fifth-or-sixth-pass boundary, and **flock 0.0581 / psyboid 0.1125 / others
0.0400 are all windowed and about 12% high as asymptotic rates.** They remain the right numbers
for what a case is drawn from. Whether the others' 0.0400 carries the same bias is untested,
since their scoring has no reason to be periodic.

**One measurement bug found and fixed on the way, which is worth recording because it produced a
plausible false finding.** Counting a scoring run already under way when the window opens as a
pass gave seed 8 a mean pass of 44.6 and a period of 486 against everyone else's 54 and 533 — and
seven seeds showed the same, which read exactly like the discovery of a second, shorter scoring
loop. `scoringLaps` on that one seed showed the truth in one line: its window opens seven ticks
before the boid leaves a scoring region. `Passes` now discards any pass in progress at the window
edge and measures between the first and last pass *start*; the seven anomalies went to zero.

**The variance that is real is herding**, and it is large: `occOthers` cv 49.5% and `occFlock`
26.6% against the psyboid's 0.8%, on a control of exactly zero. So a total-score measure buries a
50%-varying signal under a constant four times its size — which is the argument for the psyboid /
others split, made quantitative.

**Docs.** `CORPUS.md` gains "The scoring floor" and is canonical for it. `GLOSSARY.md` gains
scoring pass, scoring floor and settled rate, plus the analysis table row. `EDGES.md` records the
flown 533/54 lap in §6 and §9 and gains the `**Status:**` line it was missing. `HINTS.md` gains
§9a. `PIPELINE.md` gains step 13a. `README.md` and `ROADMAP.md` §0e updated.

**Open.** The settled-versus-windowed distinction is not applied to the *others'* occupancy, and
`PsyboidBits.WARM` is still 5,000 with the two rationales disagreeing by 5x — untouched, and still
the next real piece of corpus work.

---

## 2026-09-05 — corpus addressing built, and a corpus smoke test under physics 3

Finished the one thing §0d had specified and not built, wrote `CORPUS.md`, and ran the corpus end
to end. **Nothing is on fire.**

**Corpora are addressed now.** `CorpusPreset` names a recipe — seeds, warm-up, run, spread,
lookahead, alpha, settle — and `Derived.Corpus` hashes it into
`<behaviour>/psyboid/<preset>-<hash>/`, with a `meta.txt`. Seeds are always `0 .. N-1`.
`PsyboidCorpus.labels(Derived.Behaviour)` **refuses when more than one corpus exists** and lists
them, rather than picking; naming a preset is a fix the caller has to make deliberately.

**New columns**, all four the notes asked for plus the spend metric: `occFlock`, `occPsy`,
`occOthers`, `occControl`, `impactful`. Occupancy rather than raw score, because one point is one
boid in a zone for one tick and dividing by flock size gives a number that means the same thing
across flock sizes and run lengths. `impactful` counts ticks where the psyboid's turn **after the
veto** differed from what flocking alone would have produced — an override the map refuses, or one
the flock would have obeyed anyway, costs nothing.

**Smoke test, `SMOKE` (3 seeds):** 3 plans, all verified by replay, fidelity 6 of 6 turns crossed,
3 of 3 plans matching branch for branch. Labels read back from the addressed directory.

**Standard corpus, `PLANS_40`, regenerated under physics 3:** 40 plans in 5 s, all 40 score, all
40 beat their own control, mean 0.23242 per tick, usable window 2,739 ticks per plan. Fidelity
**248 turns asked, 248 crossed, 0 crossings nobody asked for, 40 of 40 matching branch for
branch.**

| occupancy | |
| --- | --- |
| flock | 0.0581 |
| psyboid | 0.1125 |
| others | 0.0400 |
| control | **0.0000** |

**The psyboid/others split earned itself on its first run.** The psyboid scores at 2.8x the rate
of the boids it is herding — it does much of the work itself — but the others' 0.0400 against a
control of exactly zero means all non-psyboid scoring is psyboid-caused, which isolates the
herding effect for the first time. **Two of forty plans move nobody at all**, scoring purely by
parking. No total-score measure can see either fact.

> **A correction I nearly reported as a finding.** The 3-seed smoke test said *2 of 3* plans move
> nobody, which looked alarming; at 40 seeds it is 2 of 40. Its usable window is 249 ticks against
> 2,739, and short windows favour parking because herding takes time to pay. Occupancy must not be
> read off `SMOKE`.

**Flagged, not changed: `PsyboidBits.WARM` is 5,000 and the two rationales for it disagree by 5x.**
WARM was chosen by asking when unsteered scoring stops depending on when you started watching — a
*settling* criterion, which is the thing the minimality argument in `CORPUS.md` says not to
optimise for. Total edge length on dabeone is **940 ticks**. Concrete evidence of over-warming:
control occupancy is identically **0.0000** across all 40 plans, so the warm-up diagnostic carries
no information at all. Edge-occupancy decay is the better proxy and measuring it is the next move.

**Docs.** New `CORPUS.md`, canonical for corpora, registered in `README.md` and in `CLAUDE.md`'s
reading list and freshness table. `GLOSSARY.md` gains corpus tier, corpus preset, occupancy rate
and impactful tick, and its stale `aggregation` entry — which still named `CURRENT` — is fixed.
`ROADMAP.md` §0d goes from specified to built.

**Open.** Still nothing else regenerated under physics 3: no envelope tables beyond the smoke
path, no phase map, no two-boid enumeration. The warm-up question above is the first real piece of
corpus work.


---

## 2026-09-04 (night) — physics 3 shipped, and derived output re-addressed

Given free rein on storage, plus permission to archive `analysis/`, `ingests/`, `render/`,
`packet/`, `data/` and `out/`. Full account in `ROADMAP.md` §0c; the corpus proposal, which was
asked for as a consideration rather than a build, is §0d.

**Archived first, into `archive/2026-09-04/`:** 842 MB across the six folders, 55 of them tracked.
`archive/` is gitignored. Nothing was deleted, and every map is reproducible from `areas/`, which
stays versioned.

**Physics 3 is live.** `Params.PHYSICS = 3`; `Aggregation.SIMULATION` is the one place that says
what the flock flies; `MovementLogic` defaults to it and `Flocking.of` takes `sepFalloff` from it,
so the rules and the closed form cannot be set to disagree. `CURRENT` became `RULE_NORMALISE` —
it had stopped being current — and stays as the physics-2 record and the survey baseline.

> **`MovementLogic` lost its own copy of the rules.** It now goes through the aggregation like
> everything else, so the survey and the simulation run identical code. The fidelity check's worst
> vector gap fell from `7.1e-14` to **exactly zero**: that residue had been two transcriptions of
> one formula.

**The storage defect is fixed.** `Derived` addresses derived output by its whole input closure in
two tiers — structure (map geometry, gate, weighting scheme: decomposition and clock) and
behaviour nested inside it (physics, flocking, aggregation: envelope, windows, corpora, solver
facts, audits, influence renders). Each writes a `meta.txt` naming its inputs.
`MapStore.output`/`outputDir` are unreachable for derived output, so all **33 call sites** had to
name a tier — the compiler rather than a convention. `SolverStore` and `PsyboidCorpus` now take
the gate and constants they were already being handed instead of a fixed path, which was the
actual bug.

`edges/` had been mixing the decomposition and graph renders with flocking-dependent influence
renders; the latter moved to `behaviour/influence/`.

**Verified end to end.** A clean re-ingest from `areas/` produced the **same map hash**
(`609cffdb84be218c`) with `map.png` and `display.png` **byte-identical** — only `meta.txt` moved,
carrying the physics stamp. The clock's filename hash is unchanged too, confirming it is
physics-independent rather than merely believed to be. `SolverStore.prepare` rebuilt facts end to
end in 36s into
`ingests/609cffdb84be218c/structure/b65011999ad52a55/behaviour/cc1ab3e9a4831bfd/solver/`, and both
guards pass: the closed form agrees with the aggregation at one neighbour over 4,000 arrangements,
and `Aggregation.SIMULATION` agrees with `MovementLogic` over 20,000.

**The audit the user asked for.** `TwoBoid` drives the real `MovementLogic` on a two-element array
and documents why — no change needed. `CriticalEnvelope` reproduces nothing, reasoning through
`EdgeInfluence.steer`, the one deliberate reproduction, now asserted equivalent every run. The
only accidental copy was in `AggregationSurvey` from the night before; it is gone, and the survey
numbers did not move, which is what a faithful copy should do.

**Open.** Nothing has been regenerated beyond the smoke test — no envelope tables, no corpus, no
phase map, no two-boid enumeration. Every figure in the docs from before today is a **physics 2**
figure and is marked as such rather than carried forward. The diluted-model jump on `4->0` is
noted as not suspicious, per the user.


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
