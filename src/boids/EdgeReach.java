package boids;

import java.util.Arrays;
import java.util.OptionalDouble;

/**
 * Forward distance and forward rebasing in {@code (edge, tau)} space.
 * <p>
 * <b>The bookkeeping this exists to stop being a pain.</b> A leader window is recorded against the
 * edge the <em>led</em> boid is on, and the leader it names stands on some other edge entirely.
 * Asking "will my psyboid be in that band when that boid gets there" therefore means holding two
 * positions on two edges and a duration, and rebasing all three onto a common frame. Done by hand
 * at each call site that is an invitation to get a sign wrong, and the errors are silent — a
 * distance that is one edge-length out still looks like a distance.
 * <p>
 * Everything here is <b>forward only</b>. Boids do not go backwards, tau is monotone along an
 * edge, and a "distance" that could run either way would not be a time. <b>Asking for the same
 * position you are already at therefore means a full circuit</b>, not zero — "when am I next
 * here" is a question with an answer and "how far to where I am" is not.
 *
 * <h2>Two flavours, because they answer different questions</h2>
 * <b>{@link #unsteered}</b> is where a boid ends up if nothing acts on it, so it is the one to ask
 * about the boid being led — and it is <b>absent</b> whenever coasting never arrives, which is the
 * common case for a destination on an unstable edge: unsteered travel from the stable cycle stays
 * on the stable cycle forever.
 * <p>
 * <b>{@link #steered}</b> is the fewest ticks with steering allowed, so it is the one to ask about
 * the psyboid, which can steer. It exists far more often, and where the two differ the difference
 * is exactly what an override buys.
 *
 * <h2>The convention</h2>
 * Tau is measured from the start of its own edge, and {@link EdgeDistance}'s rule holds: a route
 * contributes the length of every edge it <em>leaves</em>, so
 * {@code distance = tau(end) - tau(start) + sum of lengths left}. Lengths are the clock's, because
 * tau is the clock's; a coasted tick count would be a different unit.
 */
public final class EdgeReach {
    private EdgeReach() {}

    /** A position along the macro space: which edge, and how far along it. */
    public record At(int edge, double tau) {

        @Override
        public String toString() { return String.format("(%d, %.1f)", edge, tau); }
    }

    /**
     * Where coasting puts a boid {@code ticks} from now.
     * <p>
     * The forward rebase, and the operation every other question here is built from: it is what
     * turns "that boid is 300 ticks from its window" into a place the psyboid can be compared
     * against.
     */
    public static At advance(SolverFacts f, At from, double ticks) {
        return advance(f, from, ticks, f.straightTo());
    }

    /**
     * The same, following a route rather than coasting.
     *
     * @param route one target edge per edge, as {@link EdgePrice.Price#exit} produces. An entry of
     *              -1 falls back to coasting from that edge
     */
    public static At advance(SolverFacts f, At from, double ticks, int[] route) {
        int edge = from.edge();
        double tau = from.tau(), left = ticks;
        // Bounded rather than while(true): a route with a very short cycle and a large tick count
        // would otherwise spin, and a wrong answer is better returned than not returned at all.
        for (int hop = 0; hop < 100_000; hop++) {
            double remaining = f.length()[edge] - tau;
            if (left < remaining) return new At(edge, tau + left);
            left -= remaining;
            int next = route[edge] >= 0 ? route[edge] : f.straightTo()[edge];
            if (next < 0) return new At(edge, f.length()[edge]);
            edge = next;
            tau = 0;
        }
        return new At(edge, tau);
    }

    /**
     * Ticks of coasting from one position to another, or empty if coasting never arrives.
     * <p>
     * <b>Empty is a real answer and the interesting one.</b> Unsteered travel follows a functional
     * graph, so from any edge it enters one cycle and stays there; a destination off that cycle is
     * simply never reached, which is what "the destination is on an unstable edge" means in
     * practice.
     */
    public static OptionalDouble unsteered(SolverFacts f, At from, At to) {
        if (from.edge() == to.edge() && to.tau() > from.tau()) {
            return OptionalDouble.of(to.tau() - from.tau());
        }
        double sum = 0;
        int at = from.edge();
        for (int hop = 0; hop <= f.edges(); hop++) {
            sum += f.length()[at];
            int next = f.straightTo()[at];
            if (next < 0) return OptionalDouble.empty();
            if (next == to.edge()) return OptionalDouble.of(sum + to.tau() - from.tau());
            at = next;
        }
        return OptionalDouble.empty();
    }

    /**
     * The fewest ticks to get there with steering allowed, or empty if no route exists.
     * <p>
     * Dijkstra over the edge graph, an arc out of edge {@code x} costing {@code length(x)},
     * because that is the ground a boid covers by leaving it. <b>The turn itself is free here</b>,
     * which is right for a distance and wrong for a cost: what a turn costs is override ticks, and
     * {@link PhaseShift} is where those are counted.
     */
    public static OptionalDouble steered(SolverFacts f, At from, At to) {
        if (from.edge() == to.edge() && to.tau() > from.tau()) {
            return OptionalDouble.of(to.tau() - from.tau());
        }
        double[] best = hops(f, from.edge());
        if (best[to.edge()] == Double.POSITIVE_INFINITY) return OptionalDouble.empty();
        return OptionalDouble.of(best[to.edge()] + to.tau() - from.tau());
    }

    /**
     * Least length left behind on a route from {@code start} to each edge, counting the length of
     * every edge departed and not that of the edge arrived at.
     * <p>
     * {@code start} itself gets the cost of a full circuit back to it rather than zero, since
     * arriving at the start of an edge you are already part way along means going round.
     */
    public static double[] hops(SolverFacts f, int start) {
        int n = f.edges();
        double[] best = new double[n];
        Arrays.fill(best, Double.POSITIVE_INFINITY);
        boolean[] done = new boolean[n];

        // Seeded from start's successors rather than from start, so that coming back round to
        // start is a real distance instead of zero.
        for (int g = 0; g < n; g++) {
            if (g != start && arc(f, start, g)) best[g] = f.length()[start];
        }
        if (arc(f, start, start)) best[start] = f.length()[start];

        for (int round = 0; round < n; round++) {
            int at = -1;
            for (int e = 0; e < n; e++) {
                if (!done[e] && best[e] < Double.POSITIVE_INFINITY
                        && (at < 0 || best[e] < best[at])) {
                    at = e;
                }
            }
            if (at < 0) break;
            done[at] = true;
            for (int g = 0; g < n; g++) {
                if (!arc(f, at, g)) continue;
                double through = best[at] + f.length()[at];
                if (through < best[g]) best[g] = through;
            }
        }
        return best;
    }

    /** Whether a boid on {@code a} can reach {@code b} next, by coasting or by steering. */
    private static boolean arc(SolverFacts f, int a, int b) {
        return f.straightTo()[a] == b || (b >= 0 && b < f.edges() && (f.arcs()[a] & (1L << b)) != 0);
    }

    /**
     * Whether a boid at {@code leader} will be inside {@code [lo, hi]} on {@code band} at the
     * moment a boid at {@code led} arrives at {@code tau} on its own edge.
     * <p>
     * The question a psyboid actually asks about a window, with all three rebasings done in one
     * place. Both boids are advanced by the same number of ticks — the time the led boid takes to
     * get there — which is the whole of what makes phase comparable.
     *
     * @param route the leader's route; coasting where an entry is -1
     * @return how far inside the band the leader lands, in ticks, or empty if the led boid never
     *         arrives or the leader lands outside
     */
    public static OptionalDouble willLead(SolverFacts f, At led, double tau, At leader, int band,
                                          double lo, double hi, int[] route) {
        OptionalDouble when = unsteered(f, led, new At(led.edge(), tau));
        if (when.isEmpty()) return OptionalDouble.empty();
        At then = advance(f, leader, when.getAsDouble(), route);
        if (then.edge() != band || then.tau() < lo || then.tau() > hi) return OptionalDouble.empty();
        return OptionalDouble.of(Math.min(then.tau() - lo, hi - then.tau()));
    }
}
