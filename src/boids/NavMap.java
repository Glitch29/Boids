package boids;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Per-pixel prohibited heading ranges for one play area at one turning radius.
 * <p>
 * See CONTRACTS.md for the angle convention and the derivation. In brief: angles are
 * degrees in the simulation's screen convention, where increasing angle is clockwise
 * on screen, so a range is an ordinary increasing interval taken modulo 360.
 * <p>
 * <b>These angles should not be {@code double}, and if range arithmetic ever needs
 * fixing again the port to integers comes first — before the fix.</b> There are only
 * 64 headings; counting midpoints that is 128 canonical directions, and a whole turn
 * split into a fixed number of integer units represents every value this code can
 * produce exactly. Three of the four defects found in range handling so far were
 * floating-point boundary artefacts that integers make unrepresentable, including one
 * where a full-circle range failed to contain a heading and left a boid unsteered.
 * The reasoning is written out under "Before changing range arithmetic" in
 * CONTRACTS.md.
 */
public final class NavMap {

    /**
     * A half-open arc of prohibited headings, {@code [ccwDeg, cwDeg)}.
     * <p>
     * Both bounds are in {@code [0,360)} and the arc wraps whenever {@code cwDeg} is
     * the smaller. The one exception is a full circle, stored as {@code [a, a+360)} so
     * that it has a length of 360 rather than collapsing to zero — that form keeps the
     * midpoint meaningful, which matters because the midpoint is the only thing left
     * to steer by when every heading is prohibited.
     */
    public record Range(double ccwDeg, double cwDeg) {

        public double lengthDeg() {
            double d = cwDeg - ccwDeg;
            if (d >= 0) return d;            // includes the full circle, where cw is ccw + 360
            d += 360.0;
            // A wrap whose ends coincide to within rounding lands on a whole turn when
            // the turn is added back. That is a zero-width range, not a full circle.
            return d >= 360.0 ? 0.0 : d;
        }

        /** The heading at the centre of the range. */
        public double midDeg() {
            return norm(ccwDeg + lengthDeg() / 2.0);
        }
    }

    private final int width;
    private final int height;
    private final int radius;
    private final boolean[] oob;
    private final int[] score;
    private final List<List<Range>> ranges;

    NavMap(int width, int height, int radius, boolean[] oob, int[] score, List<List<Range>> ranges) {
        this.width = width;
        this.height = height;
        this.radius = radius;
        this.oob = oob;
        this.score = score;
        this.ranges = ranges;
    }

    public int width() { return width; }
    public int height() { return height; }
    public int radius() { return radius; }

    public int score(int x, int y) { return score[x + y * width]; }

    public boolean oob(int x, int y) { return oob[x + y * width]; }

    /** Merged, zero-length-free, ordered longest first. Empty if the pixel is free. */
    public List<Range> ranges(int x, int y) { return ranges.get(x + y * width); }

    /** Anything off the image counts as out of bounds; the play area is the image. */
    public boolean traversable(double x, double y) {
        int px = (int) Math.floor(x);
        int py = (int) Math.floor(y);
        if (px < 0 || py < 0 || px >= width || py >= height) return false;
        return !oob[px + py * width];
    }

    /**
     * The compulsory turn for a boid standing on this pixel with this heading.
     * <p>
     * If the heading falls inside a prohibited range, the boid turns away from that
     * range's midpoint — continuing in whichever rotational direction already carries
     * it further from the obstacle. Ranges are disjoint after merging, so at most one
     * can contain the heading.
     *
     * @return {@code -1} or {@code +1} to force a turn, or {@code 0} when the heading
     *         is unrestricted. Zero is unambiguous as a sentinel because turning away
     *         from a midpoint is never "hold straight".
     */
    public int forcedTurn(double x, double y, double headingDeg) {
        int px = (int) Math.floor(x);
        int py = (int) Math.floor(y);
        if (px < 0 || py < 0 || px >= width || py >= height) return 0;

        for (Range r : ranges.get(px + py * width)) {
            if (norm(headingDeg - r.ccwDeg()) < r.lengthDeg()) {
                double offset = norm(headingDeg - r.midDeg() + 180.0) - 180.0;
                return offset >= 0 ? +1 : -1;
            }
        }
        return 0;
    }

    static double norm(double deg) {
        double d = deg % 360.0;
        if (d < 0) d += 360.0;
        // A tiny negative input rounds to exactly 360 once the turn is added back,
        // which breaks the [0,360) invariant every caller relies on. In particular it
        // made a full-circle range fail to contain a heading, so a boid on a wholly
        // prohibited pixel got no forced turn at all.
        return d >= 360.0 ? 0.0 : d;
    }

    /**
     * Merges overlapping ranges, drops zero-length ones, and orders the rest by
     * length, longest first.
     * <p>
     * Written for any number of inputs even though contract C2 bounds the result at
     * two, because the bound is a design promise rather than something the code
     * checks.
     */
    static List<Range> merge(List<Range> in) {
        // Unroll onto a double cover so the merge itself is plain interval arithmetic
        // with no modular reasoning anywhere. A range that already runs low to high is
        // taken as is; one that wraps is entered twice, a turn apart, so that whichever
        // copy its neighbours meet is available to merge with.
        List<double[]> spans = new ArrayList<>(in.size() * 2);
        for (Range r : in) {
            double len = r.lengthDeg();
            if (len <= 0) continue;
            if (len >= 360.0) return fullCircle(in);

            double a = r.ccwDeg();
            double b = r.cwDeg();
            if (a <= b) {
                spans.add(new double[]{a, b});
            } else {
                spans.add(new double[]{a - 360.0, b});
                spans.add(new double[]{a, b + 360.0});
            }
        }
        if (spans.isEmpty()) return List.of();

        List<double[]> merged = mergeSpans(spans);

        // Every span starts below a full turn, so once merged and disjoint at most one
        // can reach or pass it. Drop that one down by a turn and merge again: this is
        // what lets a span running over the top of the circle join whatever it wraps
        // into at the bottom, and what collapses the two copies of a range that was
        // unrolled above. The bound must be inclusive — a range ending exactly at zero
        // has an upper copy that stops precisely at 360 and would otherwise never
        // rejoin its twin, surfacing as a duplicated range.
        for (int i = 0; i < merged.size(); i++) {
            double[] s = merged.get(i);
            if (s[1] >= 360.0) {
                s[0] -= 360.0;
                s[1] -= 360.0;
                merged = mergeSpans(new ArrayList<>(merged));
                break;
            }
        }

        for (double[] s : merged) {
            if (s[1] - s[0] >= 360.0 - 1e-9) return fullCircle(in);
        }

        List<Range> out = new ArrayList<>(merged.size());
        for (double[] s : merged) out.add(new Range(norm(s[0]), norm(s[1])));
        out.sort((a, b) -> Double.compare(b.lengthDeg(), a.lengthDeg()));
        return out;
    }

    /** Sorts, then merges touching or overlapping spans. Inputs are not aliased out. */
    private static List<double[]> mergeSpans(List<double[]> spans) {
        spans.sort(Comparator.comparingDouble(s -> s[0]));
        List<double[]> merged = new ArrayList<>(spans.size());
        for (double[] cur : spans) {
            if (merged.isEmpty()) {
                merged.add(cur.clone());
                continue;
            }
            double[] last = merged.get(merged.size() - 1);
            if (cur[0] <= last[1]) last[1] = Math.max(last[1], cur[1]);
            else merged.add(cur.clone());
        }
        return merged;
    }

    /**
     * Every heading is prohibited, so the interval bounds carry no information and only
     * the ejection direction is left to preserve.
     * <p>
     * A range wider than a half turn has its two endpoints straddling the gap it leaves
     * free, so the average of those endpoints as unit vectors points out of the
     * obstacle. Averaging over every such contributing range and negating gives the
     * midpoint to steer away from. Narrower ranges are ignored — their endpoints
     * average to a direction inside the range rather than outside it, which would pull
     * the answer the wrong way.
     */
    private static List<Range> fullCircle(List<Range> in) {
        double ex = 0, ey = 0;
        for (Range r : in) {
            if (r.lengthDeg() < 180.0) continue;
            for (double end : new double[]{r.ccwDeg(), r.cwDeg()}) {
                double rad = Math.toRadians(end);
                ex += Math.cos(rad);
                ey += Math.sin(rad);
            }
        }

        double mid;
        if (ex != 0.0 || ey != 0.0) {
            mid = norm(Math.toDegrees(Math.atan2(-ey, -ex)));
        } else {
            // Nothing wide enough to imply an ejection, or the endpoints cancelled
            // exactly. Fall back to the widest contributor's own midpoint.
            Range widest = null;
            for (Range r : in) {
                if (widest == null || r.lengthDeg() > widest.lengthDeg()) widest = r;
            }
            mid = widest == null ? 180.0 : widest.midDeg();
        }

        double lo = norm(mid - 180.0);
        return List.of(new Range(lo, lo + 360.0));
    }
}
