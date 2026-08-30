# Backlog

> **Ageing, and the freeze is lifted.** This file was written under a deadline rush that no
> longer applies; the "nothing gets worked on before the MVP" rule below is **no longer in
> force**. Many items are stale — the case packet they refer to has been deleted, and the
> answer-key-secrecy framing several of them carried was never a real concern (the repository
> is private). Treat everything here as a candidate to re-derive rather than a live plan, and
> check it against `ROADMAP.md` before picking anything up.
>
> Last reviewed 2026-08-28.

Ideas parked until there is a minimum viable product: a Cases folder with real cases in it
that an agent can attempt.

~~**Nothing on this list gets worked on before then.**~~ Each item is here precisely because it
is a genuine improvement, which is what makes it dangerous to pick up early. If something
here turns out to block current work, it stops being a backlog item and gets promoted
deliberately — not discovered mid-task.

---

## 1. QoL for the solver

Things that make the case easier to read for the agent attempting it, without changing what
is being asked.

### Render layer decoupled from the play-area PNG
The PNG defines the play area. It should not also dictate what the case looks like. Every
aesthetic decision wants to live in a render layer that consumes the map rather than
inheriting from it:

- **Checkerboard-textured scoring region** instead of a flat colour fill. Removes the
  "boid-coloured-like-the-zone" problem entirely and reads as a distinct surface rather
  than a distinct hue.
- **Automatic scaling.** Render resolution should be chosen for legibility, independent of
  the authored map's pixel dimensions.

### Boid render size
Current sizing is derived from flock spread (`Boids2DRenderer.boidLength` — proportional to
`sqrt(variance / n)`). Whatever replaces it should be chosen for how well a boid's heading
reads at the shipped resolution, since heading is half the state an observer gets.

### Legend in the render
A key mapping each boid's colour to its name, drawn into the image. Paired with **an extra
shade of untraversable area** used as a reserved region so the legend has a consistent,
findable home rather than floating over play area.

### Palette generation
Replace `Boids2DRenderer.colorOf`'s HSB hue sweep. Sketch, for whenever this comes up:
low-discrepancy R₂ sequence (plastic number ρ ≈ 1.3247, α = (1/ρ, 1/ρ²)) over **hue and
lightness** — not saturation, which washes out at sprite size — in OKLCh, chroma at the
sRGB gamut edge. Reject candidates perceptually near any colour the map itself uses, and
near any already-accepted colour.

Deliberately deprioritised: the solver is an AI agent and can sample pixels precisely, so
it does not need a perceptually-uniform palette. This is polish, not correctness. Colour
names get assigned once colours are fixed; single common names carry no copyright surface,
unlike a curated palette.

---

## 2. Programmatically ensures solvability

Checks that run before a case ships, so that "this case has an answer" is a property of the
build rather than a belief about it.

### Coincidence check
No two boids sharing a position **and** heading in any shipped frame. Two identically
placed candidates make a case ambiguous, and if one of them is the psyboid the case has two
defensible answers.

Sequential updates removed the *permanent* form of this (fusion), but transient
coincidences remain: measured 56–188 coincident pair-ticks per 16,000-tick run at 20 boids,
longest unbroken run 37–97 ticks. Rare, but a shipped frame can still land on one.

### Uniqueness check
Beyond "the psyboid is identifiable" — confirm no *other* boid's trajectory is equally
consistent with the shipped artifacts. A case with two defensible answers scores a correct
solver wrong.

### Answer-file specification
Pin the ambiguities in the boilerplate rather than leaving them to interpretation:

- `out/psyboids.txt` relative to the agent's project root.
- Exactly one line per case; if repeated, last occurrence wins.
- Colour name uppercase, exactly as written in that case's map.
- **A guess is mandatory.** No abstention, no "insufficient evidence" — otherwise a hard
  case and a cautious solver are indistinguishable in the results.

### Whitelist-based packaging
A packet must contain exactly what was intended and nothing else, because a stray file
changes what the evaluation is measuring. Have packaging **whitelist** `Case ####/**` rather
than blacklist the build notes. A blacklist fails silently the first time a new kind of file
appears; a whitelist fails loudly. Build notes stay outside the shipped tree, and the packet's
contents should be a deliberate written list rather than whatever the directory happens to
hold.

### Map snapshot beside every label
A label replays against whatever map file is on disk *now*. `outlooped.png` changed four
times in one afternoon (in-play area 48,909 → 63,247 → 65,022 → 65,020), which silently
made every earlier outlooped label unreproducible. Copy the map PNG next to each saved
label.

### Engine version stamp in the label
Same failure, different cause: the sequential-update change invalidated all 25 saved labels
at once, silently. A version stamp in the canonical label makes a stale label fail loudly
instead of replaying to a plausible-looking wrong timeline.

---

## 3. Theoretical and low-probability — visual rejection sampling

Concerns real enough to name, rare enough that eyeballing candidate cases and discarding
bad ones is a proportionate response. Revisit only if one actually shows up.

- A boid sprite visually overlapping a wall at render scale, implying an illegal position.
- Two boids close enough to be ambiguous to a reader without being coincident (so the
  coincidence check above passes and the case is still unclear).
- A boid rendered on top of the legend, or clipped at the image edge.
- Colour aliasing at sprite size making two boids hard to tell apart — largely moot given
  the solver samples pixels, but it would still degrade a human reviewing the case.
- A case where the psyboid's deviation happens to be indistinguishable from ordinary
  flocking noise, despite the case being formally solvable.

---

## 4. Map authoring and engine cleanup

### Physics version 2 — LANDED, packet rebuild pending
`Params.PHYSICS = 2`. Three changes, all invalidating every saved label:

1. Segment sweeps are symmetric under D₄ and under reversal (below).
2. Sweep sampling is exact integer arithmetic, taking **both** pixels where the line passes
   exactly between two. That tie rule is what makes reversal symmetry achievable at all —
   no single-pixel choice can be midpoint-symmetric — and it is also the conservative
   reading, since a boid should not squeeze through a corner the line only grazes.
3. Navigability requires an infinite past as well as an infinite future.

Only the first octant is computed from trigonometry; the rest is derived by reflection and
quarter-turn rotation, so the symmetry is structural rather than tested for. All eight D₄
elements verified exact on step vectors and swept paths at radius 20 and 40.

#### The defect this fixed
`NavMapBuilder.segmentOffsets` sampled each heading independently at `k = 1..steps`,
excluding the origin and including the destination, and `round` breaks .5 ties away from
zero. The sampled path therefore bulged away from whichever end it started at, so the same
physical segment got different pixels depending on direction of travel. **24 of the 64
headings hit such a tie, and exactly those 24 swept asymmetrically** — 2,262 segments on
`dabnt` were flyable one way and blocked the other. Wall collision was direction-dependent.

Fixed by building only the near half and defining each far-half heading as its antipode's
path walked backwards. Live states on `dabnt` went 394,255 → 395,076 (+821, 0.2%).

**Consequence: every shipped case label replays to a different timeline.** All 21 digests
differ under the new build (checked over ticks 0–4096 of each run).

> **Resolved by deletion, 2026-08-27.** The packet, the cases and the route traces were
> retired rather than regenerated — they had no value beyond the labels, and the labels no
> longer reconstruct anything. Case generation is a late step of the project and will start
> from current physics. See `ROADMAP.md`. The open question below is therefore moot.

~~Not yet measured: whether divergence lands before or after each case's captured frames,
which decides whether the shipped photographs survive.~~

### Make Sim blind to the psyboid (high priority, post-shipment)
`Sim` currently knows what a psyboid is. It holds a `PSYBOID` constant, constructs overrides
in `randomOverride` and `segmentedOverride`, and splits timelines in `splitByOverrides`.
That is why the shipped `/code` packet leaks psyboid detail it should not: `Sim.State` has
to go in, because the engine's whole signature rests on it, and the override constructors
ride along with it.

The fix is to put whatever decides *why* a timeline branches or gets trimmed behind an
interface and inject it, so `Sim` sees only "here are the branches to run" and "here is how
to rank them" without knowing that a psyboid is the reason. Exact pattern is open — the
requirement is that `Sim` compiles and runs with no reference to psyboids at all, which
also means the packet copy stops needing an exception.

Splitting `Sim.State` into its own file is probably part of this, since the state container
and the timeline driver are separate concerns that currently share a file.

### ~~PNG cleanup script~~ — DROPPED 2026-08-16
Automatic pixel repair on the map PNG (snap near-miss colours, remove dead pixels) is not
worth a maintained list of what counts as repairable. The user has a reliable way to stop
MS Paint antialiasing at the source, which is a better place to fix it than downstream.
Dead-pixel removal was the other half of this and is handled separately, at ingest, without
touching the source — see the trap blanking note below.

### ~~Separate traversable from spawnable~~ — LANDED 2026-08-16
Navmaps now require an infinite past as well as an infinite future
(`NavMapBuilder.Navigability.BIDIRECTIONAL`, the default), so every live state is one a
boid could actually have arrived at and spawning anywhere alive is automatically correct.
`FORWARD` remains available; the only reason to want it is precisely to widen spawning.

It costs a single sweep, not a fixed-point iteration, because the property reads straight
off the forward kernel — flip a half turn, take the step, ask whether that state is alive:

```java
public boolean reachable(int x, int y, int heading) {
    int back = (heading + Params.TURNS / 2) % Params.TURNS;
    return passable(x, y, back) && alive(x + stepX[back], y + stepY[back], back);
}
```

It works because a boid's motion is `(turn + move)*`, so walking backwards is
`(inverse_move + inverse_turn)*`; `inverse_turn` is just a turn and `inverse_move` is
`flip + move + flip`, and since flip commutes with turn and is its own inverse the whole
chain collapses to `flip + move + (turn + move)*` — and `(turn + move)*` is exactly what
the kernel already computes as `alive`. Verified against the peeled fixed point: 0 wrong,
and afterwards 0 peeling rounds remain, since the surviving set is closed both ways.

### ~~Trap count on outlooped~~ — RESOLVED 2026-08-16
Not a geometry artifact. The source map simply has unnavigable areas, and every map has
them: dead pixels form a band hugging each wall, where a boid is still in play but can no
longer turn away in time. dabnt has 2,123, blossom 7,428, wormway 17,777.

They are blanked to out-of-bounds at ingest so renders and analysis show them as the walls
they effectively are — but into `display.png` only, never `map.png`. Blanking them is
**not** navigation-preserving, which was worth checking and turned out to matter a lot: a
dead pixel routinely lies on the swept path of a live one, so a boid flying along a wall
sweeps its four-pixel step through the dead band. Rebuilding a navmap from the blanked
image loses 19,422 live states on dab (a tenth of the kernel), 27,864 on blossom. Hence the
ordering — navigability first, blanking second, and the blanked image is display-only.

### CONTRACTS.md is stale
Still documents C1/C2/C4 and the circle-sweep range machinery, all of which the viability
kernel made unnecessary and which has since been deleted. The emphatic
"⚠ refactor range arithmetic to integers before fixing any range bug" note refers to code
that no longer exists.
