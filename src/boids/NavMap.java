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
