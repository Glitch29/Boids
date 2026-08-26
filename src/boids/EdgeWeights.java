package boids;

import java.util.Arrays;

/**
 * How much each transition counts when the clock is fitted.
 * <p>
 * Fitting every transition equally says a boid is as likely to take any of them, which is not
 * true and shows up as bias: the lanes boids rarely fly pull the clock as hard as the ones
 * they always fly. What the weight should be is how often the transition is actually taken,
 * and the schemes here are successively better guesses at that.
 * <p>
 * The one property worth insisting on before any guess is conservation. A weight is a rate of
 * traffic, and traffic does not appear or vanish at a state: whatever flows in flows out. All
 * weights equal fails that wherever a state has more ways in than out, and that failure is
 * exactly the {@code (in - out) / (in + out)} bias that had to be patched out by hand
 * elsewhere. Enforcing it removes the cause instead of the symptom.
 */
public final class EdgeWeights {
    private EdgeWeights() {}

    public enum Scheme {
        /** Every transition counts the same. What the clock did before any of this. */
        UNIFORM,
        /** The nearest thing to uniform that conserves traffic at every state. */
        CIRCULATION,
        /** Conserving, then unsteered travel counted double. */
        CIRCULATION_X2,
        /** Conserving, then unsteered travel counted quadruple. */
        CIRCULATION_X4,
        /**
         * Conserving, on a walk that remembers what it last asked for.
         * <p>
         * Needs a steering chain; see {@link #momentum}. Every other scheme treats a state's
         * outgoing transitions as interchangeable, which says a boid arriving mid-turn is as
         * likely to straighten as to continue. It is not, by a factor of about eight.
         */
        MOMENTUM;

        double boost() {
            return this == CIRCULATION_X2 ? 2 : this == CIRCULATION_X4 ? 4 : 1;
        }
    }

    /**
     * A steering chain applied at strength {@code gamma}: the chain itself at 1, and
     * memorylessness at 0.
     * <p>
     * Interpolated toward the chain's own equilibrium rather than toward anything else,
     * because that is the matrix whose every row is the same — a walk that has forgotten
     * what it last did but still turns as often overall. So the blend changes only how much
     * the last decision predicts the next one, and leaves the share of ticks spent turning
     * where the measurement put it. Blending toward a uniform matrix instead would move both
     * at once and there would be no telling which one mattered.
     */
    public static double[][] blend(double[][] measured, double gamma) {
        double[][] m = normalise(measured);
        double[] rest = equilibrium(m);
        double[][] out = new double[3][3];
        for (int a = 0; a < 3; a++) {
            for (int b = 0; b < 3; b++) out[a][b] = gamma * m[a][b] + (1 - gamma) * rest[b];
        }
        return out;
    }

    private static double[][] normalise(double[][] chain) {
        double[][] out = new double[3][3];
        for (int a = 0; a < 3; a++) {
            double sum = 0;
            for (int b = 0; b < 3; b++) sum += chain[a][b];
            for (int b = 0; b < 3; b++) out[a][b] = chain[a][b] / sum;
        }
        return out;
    }

    /** Where a chain settles, by iteration; general, so a non-symmetric chain works too. */
    public static double[] equilibrium(double[][] step) {
        double[] at = {1 / 3.0, 1 / 3.0, 1 / 3.0};
        for (int pass = 0; pass < 100_000; pass++) {
            double[] next = new double[3];
            for (int a = 0; a < 3; a++) {
                for (int b = 0; b < 3; b++) next[b] += at[a] * step[a][b];
            }
            double move = 0;
            for (int b = 0; b < 3; b++) move = Math.max(move, Math.abs(next[b] - at[b]));
            at = next;
            if (move < 1e-15) break;
        }
        return at;
    }

    /**
     * A weight per transition under the given scheme.
     * <p>
     * Conservation is a linear condition, so the conserving weights nearest all-ones are an
     * orthogonal projection with a closed form. That is not what happens here, and the reason
     * is in {@link #balance}: the projection sends about a tenth of the weights negative, and
     * a negative weight is not a small error to be clamped away but a transition the fit
     * would rather run backwards. Scaling is used instead, which cannot leave the positives.
     * <p>
     * The boost then multiplies whichever transition is the unsteered one. That breaks
     * conservation, but only in the small: every state has exactly one unsteered successor and,
     * averaged over the map, one unsteered predecessor, so what it breaks is the guarantee
     * rather than the expectation.
     *
     * @param from  per transition, the live index it leaves
     * @param to    per transition, the live index it arrives at
     * @param plain per transition, whether it is the unsteered one
     */
    public static double[] of(Scheme scheme, int[] from, int[] to, boolean[] plain, int rows,
                              int liveCount) {
        double[] w = new double[rows];
        Arrays.fill(w, 1);
        // Cleared even when nothing balances, so an unbalanced count cannot be left over from
        // whichever scheme ran before this one.
        unbalanced = 0;
        if (scheme != Scheme.UNIFORM) balance(w, from, to, rows, liveCount);
        double boost = scheme.boost();
        if (boost != 1) {
            for (int k = 0; k < rows; k++) if (plain[k]) w[k] *= boost;
        }
        // A negative weight would turn the fit inside out at that transition, preferring the
        // clock to run backwards there. The projection is not guaranteed to avoid it, so it is
        // checked rather than assumed — and on these maps it is not avoided.
        clamped = 0;
        low = Double.MAX_VALUE;
        high = -Double.MAX_VALUE;
        for (int k = 0; k < rows; k++) {
            low = Math.min(low, w[k]);
            high = Math.max(high, w[k]);
            if (w[k] < 1e-6) { w[k] = 1e-6; clamped++; }
        }
        return w;
    }

    /** How many weights the projection pushed below zero, and the range it produced. */
    private static int clamped;
    private static double low, high;

    public static String lastWeights() {
        return String.format("weights %.4f to %.4f, %d clamped, %d states unbalanced",
                low, high, clamped, unbalanced);
    }

    /**
     * Scales the weights until traffic balances at every state, multiplicatively.
     * <p>
     * The obvious way to do this is the wrong one. Conservation is linear, so the nearest
     * balanced weights in a least-squares sense are a projection with a closed form — and that
     * projection sends about a tenth of them negative, because nothing in it knows a weight
     * cannot be less than nothing. Clamping afterwards undoes the conservation it was there to
     * create and leaves a weight ratio of millions, which the fit then has to be dragged
     * through.
     * <p>
     * Scaling instead of shifting keeps every weight on the right side of zero by
     * construction. Each pass shrinks a state's outflow and its neighbours' inflow toward the
     * geometric mean of the two, which is a fixed point exactly where they are equal.
     */
    private static void balance(double[] w, int[] from, int[] to, int rows, int liveCount) {
        double[] in = new double[liveCount], out = new double[liveCount];
        for (int pass = 0; pass < 4000; pass++) {
            Arrays.fill(in, 0);
            Arrays.fill(out, 0);
            for (int k = 0; k < rows; k++) { out[from[k]] += w[k]; in[to[k]] += w[k]; }
            double worst = 0;
            for (int i = 0; i < liveCount; i++) worst = Math.max(worst, Math.abs(in[i] - out[i]));
            if (worst < 1e-9) break;
            for (int k = 0; k < rows; k++) {
                double a = out[from[k]], b = in[from[k]];
                double c = in[to[k]], d = out[to[k]];
                if (a > 0 && b > 0) w[k] *= Math.sqrt(b / a);
                if (c > 0 && d > 0) w[k] *= Math.sqrt(d / c);
            }
        }
        unbalanced = 0;
        Arrays.fill(in, 0);
        Arrays.fill(out, 0);
        for (int k = 0; k < rows; k++) { out[from[k]] += w[k]; in[to[k]] += w[k]; }
        for (int i = 0; i < liveCount; i++) unbalanced += Math.abs(in[i] - out[i]) > 1e-6 ? 1 : 0;
    }

    /** States still out of balance after scaling. */
    private static int unbalanced;

    /**
     * Weights from a walk that remembers what it last asked for.
     * <p>
     * The traffic on a transition is not a function of the state alone. A boid part-way
     * through a left turn goes on wanting to turn left about eight times out of nine, so the
     * left transition out of that state carries far more traffic than the straight one — and
     * every other scheme here weights them the same, because a weight indexed by state has
     * nowhere to record which way the boid came in wanting to go.
     * <p>
     * So the flow is solved on the graph of {@code (state, last request)} instead, where that
     * fact does fit, and then summed back down onto the transitions the fit actually uses. The
     * lift is only a device for computing the weights: nothing downstream sees it, and the
     * clock still has one tick per state.
     * <p>
     * Balanced exactly as {@link #balance} balances the flat graph, from seed weights taken
     * from the chain rather than from all-ones. That is the whole difference, which is what
     * makes {@code gamma = 0} — a chain whose rows are all its own equilibrium — the control
     * that says how much of any gain is the momentum and how much is merely the lifting.
     *
     * @param index per state id, its live index, or -1
     * @param rowOf per {@code (live index, request + 1)}, which row of the fit that request
     *              lands on, or -1 where the request leads nowhere the fit covers
     * @param chain {@code [last][next]} over left, straight, right; rows must sum to 1
     */
    public static double[] momentum(NavMap map, int[] live, int liveCount, int[] index,
                                    int[] rowOf, double[][] chain, int rows) {
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
                    flow[(i * 3 + a) * 3 + b] =
                            u < 0 || !on[i * 3 + a] || !on[u * 3 + b] ? 0 : chain[a][b];
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

        // Rescaled to average one, so the numbers are readable next to the other schemes and
        // the fit's conditioning does not depend on how the chain happened to be normalised.
        double sum = 0;
        for (double v : w) sum += v;
        double scale = sum > 0 ? rows / sum : 1;
        clamped = 0;
        unbalanced = 0;
        low = Double.MAX_VALUE;
        high = -Double.MAX_VALUE;
        for (int k = 0; k < rows; k++) {
            w[k] *= scale;
            low = Math.min(low, w[k]);
            high = Math.max(high, w[k]);
            if (w[k] < 1e-6) { w[k] = 1e-6; clamped++; }
        }
        return w;
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
     * The same geometric-mean rescaling as {@link #balance}, on the lifted graph.
     * <p>
     * Written separately because the lifted graph is nine arcs a state rather than three, and
     * because it can exploit its own structure: an arc's head depends only on what is being
     * asked for now, not on what was asked before, so the whole of a state's traffic can be
     * gathered without a per-arc lookup. The two scalings are also folded into one square
     * root, which matters at three million arcs a pass.
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
     * the bottleneck. The residual is reported rather than assumed, since the flat graph's
     * balance does not fully converge either and the honest thing is to say how far it got.
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
