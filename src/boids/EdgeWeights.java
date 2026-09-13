package boids;

import java.util.Arrays;

/**
 * How much each transition counts when the clock is fitted: the <b>lifted memoryless flow</b>,
 * which is the one weighting left.
 * <p>
 * Fitting every transition equally says a boid is as likely to take any of them, which is not
 * true and shows up as bias: the lanes boids rarely fly pull the clock as hard as the ones
 * they always fly. What the weight should be is how often the transition is actually taken.
 * The one property worth insisting on first is conservation — a weight is a rate of traffic,
 * and traffic does not appear or vanish at a state — and then the traffic has to be seeded
 * the way the simulation actually produces it: <b>one unit per request</b>, not per distinct
 * successor. Where the collision veto collapses two requests onto the same successor the
 * simulation takes that successor twice as often, and a weight indexed by state alone cannot
 * say so. Hence the lift: the flow is solved on the graph of {@code (state, last request)}
 * nodes, where a request is a thing, and summed back down onto the transitions the fit uses.
 * The lift is only a device for computing the weights; nothing downstream sees it.
 *
 * <h2>What was here before, and why it is gone</h2>
 * Five schemes were surveyed — all-ones, a conserving flow on the flat graph, that flow with
 * unsteered travel counted double and quadruple, and the lifted flow driven by a measured
 * steering chain at strengths 0, ½ and 1. The table is in {@code HINTS.md} §5. The lifted flow
 * at strength 0 — memoryless, which is this — won on both maps by more than the flat flow beat
 * all-ones; giving it memory made it monotonically worse. The flat flow had once been declared
 * the winner, before a bug found afterwards invalidated that run, which is how the wrong name
 * survived in the transcripts. Settled 2026-09-13; the losers were removed the same day. The
 * memoryless chain is uniform, a third per request, so the seed is a constant and there is no
 * chain to pass.
 */
public final class EdgeWeights {
    private EdgeWeights() {}

    /**
     * What the artifact hashes carry for the weighting: the name and the chain it had when every
     * stored clock, structure and behaviour tier was built.
     * <p>
     * The weighting did not change when the survey's losers were removed, so nothing keyed on
     * it may move — a hash that changes without the meaning changing is a rebuild for nothing,
     * and a hash that fails to change when the meaning does is the wrong answer without a word.
     * These are the bytes the scheme fed the digests under its old name, kept verbatim.
     */
    static final String HASH_NAME = "MOMENTUM";

    static final double[][] HASH_CHAIN = {
            {1.0 / 3, 1.0 / 3, 1.0 / 3}, {1.0 / 3, 1.0 / 3, 1.0 / 3}, {1.0 / 3, 1.0 / 3, 1.0 / 3}};

    /** The seed on every lifted arc: a memoryless walk asks for each turn a third of the time. */
    private static final double SEED = 1.0 / 3;

    /** A weight per transition, averaging one. Applied as the square root by the caller. */
    public static double[] lifted(NavMap map, int[] live, int liveCount, int[] index,
                                  int[] rowOf, int rows) {
        // Where each request goes, which does not depend on what was asked for last tick.
        int[] next = new int[liveCount * 3];
        for (int i = 0; i < liveCount; i++) {
            for (int b = 0; b < 3; b++) {
                int u = map.successor(live[i], b - 1);
                next[i * 3 + b] = u < 0 || rowOf[i * 3 + b] < 0 ? -1 : index[u];
            }
        }

        // Arc (i, a, b): in state i having last asked for a, now asking for b.
        boolean[] on = circulable(next, liveCount);
        double[] flow = new double[liveCount * 9];
        for (int i = 0; i < liveCount; i++) {
            for (int a = 0; a < 3; a++) {
                for (int b = 0; b < 3; b++) {
                    int u = next[i * 3 + b];
                    flow[(i * 3 + a) * 3 + b] = u < 0 || !on[i * 3 + a] || !on[u * 3 + b] ? 0 : SEED;
                }
            }
        }
        balanceLifted(flow, next, on, liveCount);

        double[] w = new double[rows];
        for (int i = 0; i < liveCount; i++) {
            for (int a = 0; a < 3; a++) {
                for (int b = 0; b < 3; b++) {
                    int r = rowOf[i * 3 + b];
                    if (r >= 0) w[r] += flow[(i * 3 + a) * 3 + b];
                }
            }
        }

        // Rescaled to average one, so the fit's conditioning does not depend on the seed.
        double sum = 0;
        for (double v : w) sum += v;
        double scale = sum > 0 ? rows / sum : 1;
        clamped = 0;
        low = Double.MAX_VALUE;
        high = -Double.MAX_VALUE;
        for (int k = 0; k < rows; k++) {
            w[k] *= scale;
            low = Math.min(low, w[k]);
            high = Math.max(high, w[k]);
            // A weight at zero would leave two states unconstrained relative to each other;
            // it is floored rather than allowed to turn the fit singular there.
            if (w[k] < 1e-6) { w[k] = 1e-6; clamped++; }
        }
        return w;
    }

    private static int clamped;
    private static double low, high;

    public static String lastWeights() {
        return String.format("weights %.4f to %.4f, %d clamped", low, high, clamped);
    }

    /**
     * Which lifted nodes traffic can actually circulate through.
     * <p>
     * The lift creates nodes that cannot occur. {@code (state, left)} means a boid sitting in
     * that state having asked to go left on the way in, and if no state that can reach this one
     * reaches it by a left request, then no boid is ever in it. Such a node has no inflow and
     * some outflow, which is not a small error: a conserving flow through it does not exist, so
     * the balancing can only drive it toward zero geometrically and never gets there. Left in,
     * it stalls the balance short of conservation everywhere, which is exactly what it did —
     * worst node flow 1.95 on dabeone and 5.80 on plait after two thousand passes.
     * <p>
     * Removing them is a fixed point rather than a single sweep, since taking a node out can
     * leave its neighbour without an only source. What survives is the part of the lifted graph
     * every arc of which lies on a cycle, which is where a circulation lives.
     */
    private static boolean[] circulable(int[] next, int liveCount) {
        int nodes = liveCount * 3;
        boolean[] on = new boolean[nodes];
        Arrays.fill(on, true);
        int[] ins = new int[nodes], outs = new int[nodes];
        pruned = 0;
        for (;;) {
            Arrays.fill(ins, 0);
            Arrays.fill(outs, 0);
            for (int i = 0; i < liveCount; i++) {
                for (int a = 0; a < 3; a++) {
                    if (!on[i * 3 + a]) continue;
                    for (int b = 0; b < 3; b++) {
                        int u = next[i * 3 + b];
                        if (u < 0 || !on[u * 3 + b]) continue;
                        outs[i * 3 + a]++;
                        ins[u * 3 + b]++;
                    }
                }
            }
            int killed = 0;
            for (int v = 0; v < nodes; v++) {
                if (on[v] && (ins[v] == 0 || outs[v] == 0)) { on[v] = false; killed++; }
            }
            if (killed == 0) break;
            pruned += killed;
        }
        return on;
    }

    /** Lifted nodes no traffic can pass through, dropped before balancing. */
    private static int pruned;

    /**
     * Scales the flow until traffic balances at every lifted node, multiplicatively.
     * <p>
     * The obvious way to do this is the wrong one. Conservation is linear, so the nearest
     * balanced weights in a least-squares sense are a projection with a closed form — and that
     * projection sends about a tenth of them negative, because nothing in it knows a weight
     * cannot be less than nothing. Clamping afterwards undoes the conservation it was there to
     * create and leaves a weight ratio of millions, which the fit then has to be dragged
     * through. Scaling instead keeps every weight on the right side of zero by construction:
     * each pass shrinks a node's outflow and its neighbours' inflow toward the geometric mean of
     * the two, which is a fixed point exactly where they are equal.
     * <p>
     * An arc's head depends only on what is being asked for now, not on what was asked before,
     * so the whole of a state's traffic can be gathered without a per-arc lookup, and the two
     * scalings fold into one square root — which matters at three million arcs a pass.
     */
    private static void balanceLifted(double[] flow, int[] next, boolean[] on, int liveCount) {
        int nodes = liveCount * 3;
        double[] in = new double[nodes], out = new double[nodes];
        int pass = 0;
        double worst = 0;
        for (; pass < LIFTED_PASSES; pass++) {
            Arrays.fill(in, 0);
            Arrays.fill(out, 0);
            for (int i = 0; i < liveCount; i++) {
                for (int a = 0; a < 3; a++) {
                    for (int b = 0; b < 3; b++) {
                        double f = flow[(i * 3 + a) * 3 + b];
                        if (f == 0) continue;
                        out[i * 3 + a] += f;
                        in[next[i * 3 + b] * 3 + b] += f;
                    }
                }
            }
            worst = 0;
            for (int v = 0; v < nodes; v++) if (on[v]) worst = Math.max(worst, Math.abs(in[v] - out[v]));
            if (worst < 1e-12) break;
            for (int i = 0; i < liveCount; i++) {
                for (int a = 0; a < 3; a++) {
                    int tail = i * 3 + a;
                    double ta = out[tail], tb = in[tail];
                    for (int b = 0; b < 3; b++) {
                        int at = (i * 3 + a) * 3 + b;
                        if (flow[at] == 0) continue;
                        int head = next[i * 3 + b] * 3 + b;
                        double hc = in[head], hd = out[head];
                        if (ta > 0 && tb > 0 && hc > 0 && hd > 0) {
                            flow[at] *= Math.sqrt(tb * hd / (ta * hc));
                        }
                    }
                }
            }
        }
        lifted = String.format("lifted balance: %d passes, %d nodes pruned, worst node flow %.3g",
                pass, pruned, worst);
    }

    /**
     * Enough to get the lifted flow most of the way to conserving without the run becoming
     * the bottleneck. The residual is reported rather than assumed, and the honest thing is to
     * say how far it got.
     */
    private static final int LIFTED_PASSES = 2000;

    private static String lifted = "";

    /** What the last lifted balance managed. */
    public static String lastLifted() { return lifted; }

    /** How badly a set of weights fails to conserve traffic, worst and mean over states. */
    public static double[] imbalance(double[] w, int[] from, int[] to, int rows, int liveCount) {
        double[] net = new double[liveCount];
        for (int k = 0; k < rows; k++) { net[to[k]] += w[k]; net[from[k]] -= w[k]; }
        double worst = 0, sum = 0;
        for (double v : net) { worst = Math.max(worst, Math.abs(v)); sum += Math.abs(v); }
        return new double[]{worst, sum / Math.max(1, liveCount)};
    }
}
