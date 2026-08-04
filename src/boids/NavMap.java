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
 */
public final class NavMap {

    /**
     * A half-open arc of prohibited headings, {@code [ccwDeg, cwDeg)}.
     * <p>
     * Both bounds are in {@code [0,360)}, except for the degenerate full circle,
     * which is {@code (0, 360)}.
     */
    public record Range(double ccwDeg, double cwDeg) {

        public double lengthDeg() {
            double d = cwDeg - ccwDeg;
            return d < 0 ? d + 360.0 : d;
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
    private final List<List<Range>> ranges;

    NavMap(int width, int height, int radius, boolean[] oob, List<List<Range>> ranges) {
        this.width = width;
        this.height = height;
        this.radius = radius;
        this.oob = oob;
        this.ranges = ranges;
    }

    public int width() { return width; }
    public int height() { return height; }
    public int radius() { return radius; }

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
        return d < 0 ? d + 360.0 : d;
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
        List<double[]> spans = new ArrayList<>(in.size());
        for (Range r : in) {
            double len = r.lengthDeg();
            if (len <= 0) continue;
            if (len >= 360.0) return List.of(new Range(0.0, 360.0));
            double lo = norm(r.ccwDeg());
            spans.add(new double[]{lo, lo + len});
        }
        if (spans.isEmpty()) return List.of();

        spans.sort(Comparator.comparingDouble(s -> s[0]));

        List<double[]> merged = new ArrayList<>(spans.size());
        for (double[] cur : spans) {
            if (merged.isEmpty()) {
                merged.add(cur);
                continue;
            }
            double[] last = merged.get(merged.size() - 1);
            if (cur[0] <= last[1]) last[1] = Math.max(last[1], cur[1]);
            else merged.add(cur);
        }

        // The last span may run past 360 and rejoin the first.
        if (merged.size() > 1) {
            double[] first = merged.get(0);
            double[] last = merged.get(merged.size() - 1);
            if (last[1] - 360.0 >= first[0]) {
                first[0] = last[0] - 360.0;
                first[1] = Math.max(first[1], last[1] - 360.0);
                merged.remove(merged.size() - 1);
            }
        }

        List<Range> out = new ArrayList<>(merged.size());
        for (double[] m : merged) {
            double len = m[1] - m[0];
            if (len >= 360.0 - 1e-9) return List.of(new Range(0.0, 360.0));
            out.add(new Range(norm(m[0]), norm(m[0] + len)));
        }
        out.sort((a, b) -> Double.compare(b.lengthDeg(), a.lengthDeg()));
        return out;
    }
}
