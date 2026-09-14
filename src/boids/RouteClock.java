package boids;

import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * The clock refitted on one route alone: its edges, its own crossings and no others, the walk's
 * weights recomputed on the route.
 * <p>
 * A route is a simple loop of edges. Its <b>corridor</b> is, on each edge, the states reachable
 * from the crossing in from the previous edge that can also reach the crossing out to the next —
 * which on every route measured is the whole of each edge, by the axiom in both directions
 * ({@code EDGES.md} §5). So the route alone removes the off-route crossings and nothing else, and
 * the fit differs from the map-wide clock by a constant per edge, to within 0.7 ticks on dabeone.
 *
 * <h2>One coordinate round the loop</h2>
 * The fit is done edge by edge, with a length per edge, and on a lone cycle the split of the
 * total between those lengths is gauge — a free choice the regauge makes by convention. So each
 * edge's own tau has an arbitrary zero and is the wrong thing to read a position off. What is not
 * arbitrary is the sum: {@code T = tau_e + Σ lengths of the edges before e} is the same whatever
 * the gauge, up to one global constant, and it runs continuously round the route, wrapping by
 * the lap length at one cut — the crossing from the last edge back into the first. That is the
 * clock the user described: one cut placed anywhere, one distance added to every transition
 * crossing it, and no edge boundaries at all. The residuals are identical, so it is the same
 * least squares reparametrised, not a different fit. {@link Fit#tau} is {@code T}.
 * <p>
 * Built for {@code SimTest.routeClock}, which compares it with the map-wide clock, and for
 * {@link SubpathSearch}, which the user wants run on a route's own tau.
 */
public final class RouteClock {
    private RouteClock() {}

    /**
     * @param route  the loop, as map edge ids
     * @param edgeOf per state, its map edge id if on the route, else -1
     * @param tau    per state, its position round the route, {@code T}: continuous, gauge-free,
     *               wrapping by {@code lap} at the cut; NaN off the route
     * @param lo     per map edge id, where the edge's stretch of {@code T} begins — the sum of the
     *               lengths before it; NaN off the route
     * @param length per map edge id, the route's fitted length; NaN off the route. Gauge, apart
     *               from the sum
     * @param lap    the total, which is not gauge
     * @param alone  the fit itself, numbered by position in {@code route}, in its own per-edge tau
     */
    public record Fit(int[] route, int[] edgeOf, int[] live, int liveCount, double[] tau,
                      double[] lo, double[] length, double lap, EdgeMetric.Metric alone) {

        /** Position of a map edge in the route, or -1. */
        public int slot(int e) {
            for (int i = 0; i < route.length; i++) if (route[i] == e) return i;
            return -1;
        }
    }

    public static Fit of(NavMap map, EdgeDecomposition.Labelling l, int[] route) {
        int m = route.length;
        int[] edgeOf = l.edge();
        int[] local = new int[edgeOf.length];
        Arrays.fill(local, -1);
        int[] out = new int[3];
        for (int i = 0; i < m; i++) {
            int e = route[i], prev = route[(i + m - 1) % m], next = route[(i + 1) % m];
            boolean[] fromIn = new boolean[edgeOf.length], toOut = new boolean[edgeOf.length];
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            for (int k = 0; k < l.liveCount(); k++) {
                int s = l.live()[k];
                if (edgeOf[s] != e) continue;
                int c = map.steeredPredecessors(s, out);
                for (int j = 0; j < c; j++) if (edgeOf[out[j]] == prev) { fromIn[s] = true; queue.add(s); break; }
            }
            while (!queue.isEmpty()) {
                int s = queue.poll();
                int c = map.steeredSuccessors(s, out);
                for (int j = 0; j < c; j++) if (edgeOf[out[j]] == e && !fromIn[out[j]]) { fromIn[out[j]] = true; queue.add(out[j]); }
            }
            for (int k = 0; k < l.liveCount(); k++) {
                int s = l.live()[k];
                if (edgeOf[s] != e) continue;
                int c = map.steeredSuccessors(s, out);
                for (int j = 0; j < c; j++) if (edgeOf[out[j]] == next) { toOut[s] = true; queue.add(s); break; }
            }
            while (!queue.isEmpty()) {
                int s = queue.poll();
                int c = map.steeredPredecessors(s, out);
                for (int j = 0; j < c; j++) if (edgeOf[out[j]] == e && !toOut[out[j]]) { toOut[out[j]] = true; queue.add(out[j]); }
            }
            for (int k = 0; k < l.liveCount(); k++) {
                int s = l.live()[k];
                if (fromIn[s] && toOut[s]) local[s] = i;
            }
        }
        int count = 0;
        for (int k = 0; k < l.liveCount(); k++) if (local[l.live()[k]] >= 0) count++;
        int[] live = new int[count];
        for (int k = 0, at = 0; k < l.liveCount(); k++) if (local[l.live()[k]] >= 0) live[at++] = l.live()[k];

        EdgeMetric.Metric alone = EdgeMetric.compute(map, local, live, count, m);

        int[] onRoute = new int[edgeOf.length];
        Arrays.fill(onRoute, -1);
        double[] tau = new double[edgeOf.length];
        Arrays.fill(tau, Double.NaN);
        double[] length = new double[l.edges()], lo = new double[l.edges()];
        Arrays.fill(length, Double.NaN);
        Arrays.fill(lo, Double.NaN);
        double before = 0;
        for (int i = 0; i < m; i++) {
            length[route[i]] = alone.length()[i];
            lo[route[i]] = before;
            before += alone.length()[i];
        }
        for (int s : live) { onRoute[s] = route[local[s]]; tau[s] = alone.tick()[s] + lo[onRoute[s]]; }
        return new Fit(route.clone(), onRoute, live, count, tau, lo, length, before, alone);
    }
}
