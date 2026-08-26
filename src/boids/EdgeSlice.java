package boids;

import java.util.ArrayList;
import java.util.List;

/**
 * Where a leader has to be when the boid it is leading is at one particular distance along
 * its edge.
 * <p>
 * The two leader maps answer different questions and neither answer is stable. One shows
 * leaders standing at a source, the other leaders standing at a terminal — but source and
 * terminal are wherever the critical envelope happened to be cut, and the envelope can be
 * widened or narrowed without changing anything the map actually does. Overlaying the two
 * therefore mixes leaders that belong to different moments, and moving the cut moves the
 * picture without moving the conclusion.
 * <p>
 * A clock removes the arbitrariness. Fix a distance rather than a boundary: take the boid at
 * tick {@code tau} along the edge and ask where its leader can be. The leaders come back
 * spread over a stretch of their own edges, and that stretch is the answer — <em>how much
 * slack the leader has</em>. Where the stretch sits is arbitrary, because {@code tau} was, but
 * <b>how long it is</b> is not: it is the tolerance in the leader's position, and it does not
 * move when the envelope does.
 * <p>
 * The output is a band per leader edge rather than a set of states, which is the point. A set
 * of states carries the phase it was sampled on; a band carries only distance, so bands taken
 * at different {@code tau} can be compared and shifted. Turning a band back into states for
 * drawing is a last step, and lossy on purpose.
 */
public final class EdgeSlice {
    private EdgeSlice() {}

    /**
     * @param edge  which edge the leaders lie on
     * @param lo    earliest tick along that edge a leader can be at
     * @param hi    latest
     * @param count how many leader states fall in the band
     */
    public record Band(int edge, double lo, double hi, int count) {
        public double width() { return hi - lo; }
    }

    /**
     * @param followers how many envelope states sit at the chosen distance
     * @param bands     one per edge the leaders occupy, widest first
     * @param states    the band read back as states, for drawing
     */
    public record Slice(double tau, int followers, List<Band> bands, long[] states) {}

    /**
     * @param backwards true to ask which leaders can still see the boid out from here, false
     *                  to ask which could have led it here from a source
     */
    public static Slice at(NavMap map, int[] edge, EdgeMetric.Metric metric,
                           EdgeInfluence.Lead lead, int followerEdge, double tau,
                           boolean backwards, int edges) {
        int turns = Params.TURNS;
        long[][] sets = backwards ? lead.backward() : lead.forward();

        // The boid is somewhere in a one-tick slice of its edge. Anything wider would mix
        // moments; anything narrower would miss the phases that fall between the samples.
        long[] leaders = new long[sets.length == 0 ? 0 : sets[0].length];
        int followers = 0;
        for (int i = 0; i < lead.envelope().length; i++) {
            int s = lead.envelope()[i];
            if (edge[s] != followerEdge) continue;
            double t = metric.tick()[s];
            if (Double.isNaN(t) || t < tau || t >= tau + 1) continue;
            followers++;
            for (int q = 0; q < leaders.length; q++) leaders[q] |= sets[i][q];
        }

        // Read the leaders back as distances along whatever edges they are on.
        double[] lo = new double[edges], hi = new double[edges];
        int[] count = new int[edges];
        java.util.Arrays.fill(lo, Double.MAX_VALUE);
        java.util.Arrays.fill(hi, -Double.MAX_VALUE);
        for (int pi = 0; pi < lead.liveOf().length; pi++) {
            if ((leaders[pi >>> 6] & (1L << (pi & 63))) == 0) continue;
            int p = lead.liveOf()[pi];
            int e = edge[p];
            if (e < 0 || Double.isNaN(metric.tick()[p])) continue;
            lo[e] = Math.min(lo[e], metric.tick()[p]);
            hi[e] = Math.max(hi[e], metric.tick()[p]);
            count[e]++;
        }

        List<Band> bands = new ArrayList<>();
        for (int e = 0; e < edges; e++) if (count[e] > 0) bands.add(new Band(e, lo[e], hi[e], count[e]));
        bands.sort((a, b) -> Double.compare(b.width(), a.width()));

        // Back to states, by distance alone. Every state of the edge whose tick falls in the
        // band is included, whether or not it was one of the leaders that produced it — that
        // is what makes the picture a statement about distance rather than about phase.
        long[] states = new long[(map.width() * map.height() * turns + 63) >>> 6];
        for (Band band : bands) {
            for (int s = 0; s < edge.length; s++) {
                if (edge[s] != band.edge()) continue;
                double t = metric.tick()[s];
                if (Double.isNaN(t) || t < band.lo() || t > band.hi()) continue;
                states[s >>> 6] |= 1L << (s & 63);
            }
        }
        return new Slice(tau, followers, bands, states);
    }
}
