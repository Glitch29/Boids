# What is being built now

**Status:** 2026-08-29. `README.md` has the inventory; this file has the work in front of us
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

> **Arc `4->0` has no table under the two-model boid.** A build was killed after 2h46m of CPU
> and 8.3 GB of a 10 GB heap without finishing — more than four times what tripling the
> single-model 754 s would predict. Edge 4 is the worst case for admission breadth, and the
> second model doubles the branching on top of it. **Every corpus figure so far is arcs `2->1`
> and `5->6` only.** The out-of-range collapse below is what makes that arc runnable, not merely
> faster.
>
> `CriticalEnvelope.analyse` also emits no progress, so a long build cannot be told from a hung
> one. Worth fixing before the next long run.

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
