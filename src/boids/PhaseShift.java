package boids;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * What a boid can buy with an override tick, measured in phase.
 * <p>
 * <b>Why phase is the currency.</b> A boid advances one step per tick along a route of fixed
 * length, so two coasting boids hold their tau difference forever — that is the conservation §0f
 * measured. It follows that a psyboid cannot get itself into a leader window by waiting: waiting
 * moves it and the boid it wants to lead by the same amount. The only way to change a phase
 * relationship is to spend ticks travelling a route through the edge that is longer or shorter
 * than the one coasting takes.
 * <p>
 * <b>A shortcut cuts a corner; a longcut takes the wide way round.</b> Both are ordinary flying —
 * every state on the path is one the boid could occupy anyway, and the edge it leaves by is the
 * same one coasting leaves by. What they cost is <b>override ticks</b>: the ticks on which the
 * psyboid asked for something other than what the flock wanted. Those are free today and will not
 * be forever, so what matters is not the largest shift available but the shift <em>per override
 * tick</em>, which is what this ranks.
 *
 * <h2>The computation</h2>
 * Tau is monotone along an edge, so an edge's internal transition graph is a DAG and the whole
 * thing is one dynamic program over {@code (state, budget)}: the fewest and the most ticks to
 * leave the edge <b>by the same exit coasting takes</b>, spending at most {@code budget}
 * non-straight ticks. Requiring the same exit is what makes this a phase change rather than a
 * route change — leaving somewhere else is a different decision priced by {@link EdgePrice}.
 */
public final class PhaseShift {
    private PhaseShift() {}

    private static final int UNREACHABLE = Integer.MAX_VALUE / 4;

    /**
     * What one edge offers, from one entry state.
     *
     * @param coast    ticks a coasting traversal takes
     * @param fastest  fewest ticks achievable, and {@code fastAt} the override ticks it costs
     * @param slowest  most ticks achievable, and {@code slowAt} the override ticks it costs
     */
    public record Offer(int edge, int entry, int coast, int fastest, int fastAt, int slowest,
                        int slowAt) {

        /** Ticks of phase gained by hurrying. Positive means arriving earlier. */
        public int gain() { return coast - fastest; }

        /** Ticks of phase given up by dawdling. */
        public int loss() { return slowest - coast; }

        /** The better of the two, per override tick spent — how efficient this edge is. */
        public double efficiency() {
            double up = fastAt == 0 ? 0 : gain() / (double) fastAt;
            double down = slowAt == 0 ? 0 : loss() / (double) slowAt;
            return Math.max(up, down);
        }
    }

    /** Every edge's offer, taken over its entry states. */
    public record Ledger(Offer[] best, int budget) {}

    /**
     * Runs the dynamic program on every edge.
     *
     * @param budget most override ticks a traversal may spend. Beyond about a quarter of an
     *               edge's length the answers stop moving, because the useful detours are local
     */
    public static Ledger of(NavMap map, SolverFacts f, Pipeline.Labelling l, int budget) {
        Offer[] best = new Offer[f.edges()];
        int[] local = new int[f.edgeOf().length];
        Arrays.fill(local, -1);

        for (int e = 0; e < f.edges(); e++) {
            best[e] = edge(map, f, l, e, budget, local);
        }
        return new Ledger(best, budget);
    }

    /**
     * One edge: order its states by tau, solve fastest and slowest to the coasting exit under
     * every budget, then report the entry state with the most on offer.
     */
    private static Offer edge(NavMap map, SolverFacts f, Pipeline.Labelling l, int e, int budget,
                              int[] local) {
        int exit = f.straightTo()[e];
        if (exit < 0) return new Offer(e, -1, 0, 0, 0, 0, 0);

        List<Integer> states = new ArrayList<>();
        for (int i = 0; i < l.liveCount(); i++) {
            int s = l.live()[i];
            if (l.edge()[s] == e && !Double.isNaN(f.tickOf()[s])) states.add(s);
        }
        if (states.isEmpty()) return new Offer(e, -1, 0, 0, 0, 0, 0);

        // Descending tau: a state's successors are further along, so they are solved first.
        states.sort((a, b) -> Double.compare(f.tickOf()[b], f.tickOf()[a]));
        for (int i = 0; i < states.size(); i++) local[states.get(i)] = i;

        int n = states.size(), w = budget + 1;
        int[] fast = new int[n * w], slow = new int[n * w];
        Arrays.fill(fast, UNREACHABLE);
        Arrays.fill(slow, -UNREACHABLE);

        for (int i = 0; i < n; i++) {
            int s = states.get(i);
            for (int b = 0; b <= budget; b++) {
                int lo = UNREACHABLE, hi = -UNREACHABLE;
                for (int turn = -1; turn <= 1; turn++) {
                    int spend = turn == 0 ? 0 : 1;
                    if (b < spend) continue;
                    int u = map.successor(s, turn);
                    if (u < 0) continue;
                    int on = l.edge()[u];
                    if (on == exit) { lo = Math.min(lo, 1); hi = Math.max(hi, 1); continue; }
                    if (on != e) continue;             // left by the wrong exit: not a phase move
                    int at = local[u];
                    if (at < 0) continue;
                    int f2 = fast[at * w + (b - spend)];
                    int s2 = slow[at * w + (b - spend)];
                    if (f2 < UNREACHABLE) lo = Math.min(lo, 1 + f2);
                    if (s2 > -UNREACHABLE) hi = Math.max(hi, 1 + s2);
                }
                fast[i * w + b] = lo;
                slow[i * w + b] = hi;
            }
        }

        // Entry states are where a traversal starts, so they are where an offer is real.
        int[] back = new int[3];
        Offer pick = null;
        for (int i = 0; i < n; i++) {
            int s = states.get(i);
            boolean entry = false;
            int count = map.unsteeredPredecessors(s, back);
            for (int k = 0; k < count; k++) {
                if (back[k] >= 0 && l.edge()[back[k]] >= 0 && l.edge()[back[k]] != e) entry = true;
            }
            if (!entry) continue;
            int coast = fast[i * w];                   // budget 0 leaves only the coasting path
            if (coast >= UNREACHABLE) continue;
            int fastest = coast, fastAt = 0, slowest = coast, slowAt = 0;
            for (int b = 1; b <= budget; b++) {
                int fv = fast[i * w + b], sv = slow[i * w + b];
                if (fv < fastest) { fastest = fv; fastAt = b; }
                if (sv > slowest && sv > -UNREACHABLE) { slowest = sv; slowAt = b; }
            }
            Offer here = new Offer(e, s, coast, fastest, fastAt, slowest, slowAt);
            if (pick == null || here.gain() + here.loss() > pick.gain() + pick.loss()) pick = here;
        }
        for (int s : states) local[s] = -1;
        return pick == null ? new Offer(e, -1, 0, 0, 0, 0, 0) : pick;
    }

    /** The ledger, as a table, ordered by what each edge is worth per override tick. */
    public static void report(PresetScenarioParameter preset, Ledger ledger, SolverFacts f) {
        System.out.printf("%n=== %s @%s: what an override tick buys in phase (budget %d) ===%n",
                preset.name(), preset.ingest().hash(), ledger.budget());
        System.out.printf("%-5s %8s %8s %9s %8s %9s %9s %10s   %s%n", "edge", "length", "coast",
                "fastest", "cost", "slowest", "cost", "per tick", "what it is");

        Offer[] sorted = ledger.best().clone();
        Arrays.sort(sorted, (a, b) -> Double.compare(b.efficiency(), a.efficiency()));
        for (Offer o : sorted) {
            if (o.entry() < 0) continue;
            int span = o.slowest() - o.fastest();
            // Where coasting sits inside the range is the operational fact: an edge whose coast
            // is already near the slow end offers hurry and no dawdle, and vice versa.
            String what = span == 0 ? "on rails"
                    : String.format("span %d, coast %d%% of the way up; hurry %d, dawdle %d",
                            span, (int) Math.round(100.0 * (o.coast() - o.fastest()) / span),
                            o.gain(), o.loss());
            System.out.printf("%-5d %8.1f %8d %9d %8d %9d %9d %10.2f   %s%n", o.edge(),
                    f.length()[o.edge()], o.coast(), o.fastest(), o.fastAt(), o.slowest(),
                    o.slowAt(), o.efficiency(), what);
        }

        double totalGain = 0, totalLoss = 0;
        for (Offer o : ledger.best()) { totalGain += o.gain(); totalLoss += o.loss(); }
        System.out.printf("over one circuit of every edge: up to %.0f ticks of hurry and %.0f of "
                + "dawdle available%n", totalGain, totalLoss);
    }
}
