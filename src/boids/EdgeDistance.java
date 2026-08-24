package boids;

import java.util.ArrayList;
import java.util.List;

/**
 * How long the clock says it takes to get from one state to another.
 * <p>
 * A tick value locates a state along its own edge and says nothing about any other edge, so
 * two states on different edges cannot be subtracted directly. What bridges them is the edge
 * graph: a route from one edge to the other contributes the length of every edge it leaves,
 * and the two endpoints contribute their own positions. Leaving an edge at tick {@code t} and
 * arriving on the next puts the boid at {@code t + 1 - length}, so a whole route collapses to
 * {@code tick(end) - tick(start)} plus the lengths of every edge on the way except the last.
 * <p>
 * There is usually more than one route, and they are genuinely different journeys rather than
 * different measurements of one — a boid that went the long way round really did take longer.
 * So this returns every route within a bound rather than picking one, and the caller decides
 * what to do with them.
 */
public final class EdgeDistance {
    private EdgeDistance() {}

    /**
     * Every distance the clock allows between two states, one per distinct route.
     *
     * @param cap ignore routes whose accumulated length already exceeds this, which is what
     *            keeps a graph full of cycles from offering infinitely many answers
     */
    public static double[] between(int[] edge, EdgeMetric.Metric m, int from, int to, double cap) {
        int a = edge[from], b = edge[to];
        if (a < 0 || b < 0 || Double.isNaN(m.tick()[from]) || Double.isNaN(m.tick()[to])) {
            return new double[0];
        }
        int edges = m.length().length;
        List<Double> sums = new ArrayList<>();
        walk(m, arcs(m, edges), edges, a, b, 0, cap, sums, 0);
        double base = m.tick()[to] - m.tick()[from];
        double[] out = new double[sums.size()];
        for (int i = 0; i < out.length; i++) out[i] = base + sums.get(i);
        return out;
    }

    /** Which edges each edge can step to, read off the crossing states the metric found. */
    private static boolean[][] arcs(EdgeMetric.Metric m, int edges) {
        boolean[][] arcs = new boolean[edges][edges];
        for (int e = 0; e < edges; e++) {
            for (int f = 0; f < edges; f++) arcs[e][f] = m.exitTo()[e][f] >= 0;
        }
        return arcs;
    }

    private static void walk(EdgeMetric.Metric m, boolean[][] arcs, int edges, int at, int target,
                             double sum, double cap, List<Double> out, int depth) {
        if (at == target) out.add(sum);
        if (sum > cap || depth > edges * 2) return;
        for (int f = 0; f < edges; f++) {
            if (arcs[at][f]) walk(m, arcs, edges, f, target, sum + m.length()[at], cap, out, depth + 1);
        }
    }

    /** The route that best matches an observed journey, and how far off it is. */
    public static double closest(double[] candidates, double actual) {
        double best = Double.NaN, error = Double.MAX_VALUE;
        for (double v : candidates) {
            if (Math.abs(v - actual) < error) { error = Math.abs(v - actual); best = v; }
        }
        return best;
    }
}
