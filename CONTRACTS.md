# Play area contracts

These are guarantees the **play area designer** makes. They are deliberately **not
verified by the code**. Their purpose is to define the boundary of the specification:
any corner case that can only arise when one of these is violated has *arbitrary*
behaviour, and code is not expected to detect, report, or degrade gracefully in
those cases.

Distances here follow the convention that the circle a boid traverses at full turn
rate has **radius 1**. In pixels that circle has radius `r`, so "an r=2 disk" below
means a disk of pixel radius `2r`.

---

## C1 — Traversability

> For any boundary point B, there exists at least one circle O such that B is a
> point on O, and the open interior of O lies entirely within the play area.

The main consequence is a limit on the **concave curvature of out-of-bounds
regions**: the play area may not contain a notch or corner tighter than the boid's
turning circle. It also restricts how closely two obstacles may be placed.

This is slightly stronger than strictly necessary. The weaker version requires
asserting the existence of a 2-colouring of the out-of-bounds regions with certain
properties, which is more trouble than it is worth.

## C2 — Complexity

> The intersection of any r=2 disk with the out-of-bounds area contains at most 2
> distinct contiguous regions.

This is what bounds the per-pixel result to **at most 2 angular ranges**.

## C3 — Safety at tangents

> Any boid flying tangent to the border has at least one available path that remains
> within the play area indefinitely.

## C4 — Small obstacles are convex

> Any interior out-of-bounds region with an area smaller than `π(2r)²` must be convex.

Regions that small are invisible to both circle sweeps — the radius-r circle about a
pixel inside one lies wholly within the dilation, while the radius-2r circle clears
the dilation entirely, so neither ever crosses the border. No sweep radius rescues
this: at the centre of a symmetric blob every circle is uniformly inside or outside.
Such regions are handled by fitting an ellipse instead, which assumes convexity.

---

# Derived: the navigability map

A play area is a PNG. `#000000` is out of bounds; every other colour is traversable.
It is preprocessed into a lattice of **prohibited heading ranges**: for each
traversable pixel, the set of headings from which the boid inevitably leaves the play
area regardless of how it turns.

## Angle convention

Matches the simulation. Screen coordinates, y increasing **downward**. A heading θ
means direction `(cos θ, sin θ)`. **Increasing θ therefore appears clockwise on
screen.** Left of the boid is `θ − 90°`, right is `θ + 90°`.

## Range representation

Each range is stored as `[anticlockwise_bound, clockwise_bound)` in degrees. Because
increasing angle is clockwise, this is an ordinary increasing interval, taken modulo
360:

```
length = (clockwise_bound − anticlockwise_bound) mod 360
```

Per pixel the ranges are merged where they overlap, zero-length ranges are dropped,
and the remainder are **ordered by length, longest first**. C2 guarantees at most two
survive.

A full circle is the one exception to both bounds being in `[0,360)`: it is stored as
`[a, a+360)` so that its length is 360 rather than collapsing to zero. That keeps the
midpoint meaningful, which matters precisely because the midpoint is the only thing
left to steer by when every heading is prohibited.

---

# ⚠ Before changing range arithmetic, refactor it to integers

**This is the first thing to do if any range calculation ever needs fixing again.
Do not debug the floating-point version further — port it and then fix the bug.**

Angles have no business being `double` here. There are only 64 headings. Counting
midpoints between adjacent headings that is 128 canonical directions, and the
intermediate values (quarter-turn offsets, arc endpoints, half-lengths) need at most a
few hundred more. A whole turn divided into a fixed integer number of units — 512 is
comfortable — represents every value this code can produce, *exactly*.

The cost of not doing so is not hypothetical. Of the four defects found in range
handling, three were pure floating-point boundary artefacts and would have been
impossible in integers:

- `norm` returned exactly `360.0` for a tiny negative input, because `−ε + 360`
  rounds to `360`. That broke the `[0,360)` invariant every caller assumes, and the
  visible symptom was the worst available one: a **full-circle range failed to contain
  a heading**, so a boid on a wholly prohibited pixel received no forced turn at all.
- `lengthDeg` reported `360` for a wrap whose ends coincided to within rounding — a
  zero-width range reading as total coverage.
- Comparisons exactly on a range endpoint flipped on a one-ulp difference, because the
  endpoints are reconstructed through `norm` on the way out. Harmless in the
  simulation, but it made the differential test need an endpoint tolerance, which
  means the test is *not* checking the boundaries — exactly where the bugs live.

In integer units, equality is meaningful, `norm` cannot overshoot, no epsilon appears
anywhere, and the differential test can check every value including the endpoints.

The fourth defect — two copies of an unrolled range failing to rejoin when one ended
exactly at zero — was a logic boundary and would have existed in integers too.

---

## How the ranges are derived

The circle C of radius r centred on a pixel P is exactly the locus of centres of the
boid's two maximal turning circles: heading θ puts the left centre at angle `θ − 90°`
on C and the right centre at `θ + 90°`. A turning circle is blocked exactly when its
centre lies within r of the obstacle — that is, inside the obstacle dilated by r,
written D.

So for each out-of-bounds region, taking the arc of C that lies inside D as
`[arcStart, arcEnd]` **in increasing angle order**:

```
prohibited = [arcStart + 90°,  arcEnd − 90°]
length     = arcLength − 180°
```

kept only when `arcLength > 180°`; otherwise the range is negative-sized and is
discarded. Each region is processed separately and the results are merged.

That sweep only speaks for pixels on the play side of the border. Every pixel is
classified by how its radius-r circle meets the dilation, and there are three cases:

| Radius-r circle | Meaning | Treatment |
|---|---|---|
| Crosses the border | On the play side, within reach | The construction above. Length 0–180°. |
| Wholly inside the dilation | Beyond the border: out of bounds, or in a pocket too tight for the turning circle | Sweep at radius 2r and take the inside arc **without** the quarter turns. Length 180–360°. |
| Wholly outside | Out of reach | Nothing. |

Arcs shorter than 90° from the radius-2r sweep are discarded. The geometry says that
sweep's answer is always between 180° and 360°, so anything narrower is an artefact —
typically speckle where the circle runs tangent to the dilation edge and consecutive
samples round to pixels on opposite sides of the threshold, producing a rash of
one-sample slivers. The cut-off sits well clear of 180° so a genuine range roughed up
by pixelation still survives. **The radius-r sweep keeps its narrow ranges**: there
they are real, and they are exactly the case of a boid pointed straight at a wall.

Regions covered by C4 use neither sweep. An ellipse is fitted to the region by second
moments, and each of its pixels is prohibited over the half circle centred opposite
the outward normal of the similar ellipse through that pixel — the direction that
leaves the obstacle soonest. Scale is discarded; only the centre, the orientation of
the principal axes and the ratio of their extents matter. An ellipse rather than a
circle because it keeps C4 clean: a pencil-shaped wall modelled as a circle would
eject in nearly arbitrary directions.

The increasing-angle ordering of the arc is what encodes which side the obstacle is
on, so the resulting range covers headings *into* the obstacle and not headings away
from it. Two checks against closed-form solutions:

| Case | Predicted | Direct computation |
|---|---|---|
| Flat wall, boid at p = r/2 | 60° centred on the heading into the wall | doomed iff `|cos θ| ≤ 1/2`, i.e. `[60°,120°] ∪ [240°,300°]`; the formula selects the into-the-wall half |
| Disk R=15, r=10, boid at d=20 | 36.4° centred on the heading toward the centre | same |

## Precision

Slightly fuzzy outcomes are acceptable. Calculations should be correct on average;
no blended-pixel or subpixel reconstruction is done to chase pixel-exact boundaries.
A boid clipping out of bounds by a few pixels is undesirable, but less undesirable
than the code complexity of preventing it. A later wall-aversion term will hold boids
several pixels clear of the physical bounds regardless.

## Corner cases with arbitrary behaviour

These can only arise under a contract violation. The behaviour listed is what the
code happens to do; none of it should be relied on.

- **Both sweeps degenerate** — the radius-r circle wholly inside the dilation and the
  radius-2r circle wholly outside it. That is an obstacle smaller than the turning
  circle, which C4 routes to the ellipse fit instead; reaching this state means the
  region was classified as exterior or large when it is neither. No range is emitted
  and the pixel reads as navigable.
- **A small interior region that is not convex.** C4 forbids it. The ellipse fit still
  produces an answer, but the ejection direction it gives need not point out of the
  region by the shortest path, or out of it at all.
- **More than 2 intersections between C and a single region's border**, from a border
  coming within r of itself. The implementation samples the arc structure rather than
  extracting an oriented loop, so it finds every maximal in-D arc and treats each
  independently. This is the behaviour a polygonal-chain treatment would give, but it
  is a consequence of the method, not a guarantee.
- **More than 2 surviving ranges at a pixel.** Merging and ordering still work; only
  C2's bound is lost. The visualiser keeps halving saturation per extra range, so the
  symptom is pixels washing out toward grey.
