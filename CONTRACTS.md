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

- **C entirely inside D** (every turning circle blocked). Treated as *zero*
  intersections, so no range is emitted and the pixel reads as fully navigable. This
  requires a concave feature tighter than the turning circle, which C1 forbids.
- **More than 2 intersections between C and a single region's border**, from a border
  coming within r of itself. The implementation samples the arc structure rather than
  extracting an oriented loop, so it finds every maximal in-D arc and treats each
  independently. This is the behaviour a polygonal-chain treatment would give, but it
  is a consequence of the method, not a guarantee.
- **More than 2 surviving ranges at a pixel.** Merging and ordering still work; only
  C2's bound is lost. The visualiser keeps halving saturation per extra range.
