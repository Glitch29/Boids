package boids;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * What a position is worth, in {@code (edge, tau)} space and nothing else.
 * <p>
 * <b>The problem this exists for.</b> A psyboid search that values a decision by what it scores
 * over the next few hundred ticks can only find decisions that pay within a few hundred ticks.
 * Plait was designed to defeat exactly that: both of its long edges are about 756 ticks, each has
 * a bypass of about 55, and a turn taken at the end of edge 1 does not reach a scoring pixel until
 * most of edge 0 and then edge 2 have gone by — some 890 ticks later. Under a per-second discount
 * of 0.95 that payoff is worth 0.006 of its face value, so the search sees two identical futures,
 * ties, and declines. <b>The map hides the value of a choice, and the fix is to compute the value
 * rather than to look for it.</b>
 * <p>
 * <b>What makes it computable.</b> Between decisions a boid has no choice: it coasts, and its
 * whole future is fixed by which edge it is on. So the map collapses to a small directed graph —
 * six nodes on plait, nine on dabeone — with a traversal time and a scoring yield on each arc.
 * That is a graph problem, not a search problem.
 *
 * <h2>Average reward, not discounted</h2>
 * The objective is score per tick over an unbounded run, so the right formulation is the
 * average-reward one: a <b>gain</b> {@code lambda*}, the best score-per-tick any cycle achieves,
 * and a <b>bias</b> {@code h(e)} measuring how much better than average a position is. They
 * satisfy
 * <pre>
 *   h(e) = max over exits g of [ score(e) - lambda* * length(e) + h(g) ]
 * </pre>
 * and the policy that is greedy on {@code h} is optimal. <b>A discount would reintroduce the
 * horizon the map was built to exploit</b>; the gain has no horizon at all.
 *
 * <h2>Ticks are coasted, not read off the clock</h2>
 * Lengths here are counted by walking {@link NavMap#successor} with no turn and seeing how many
 * ticks pass, because that is what a boid actually experiences. The clock's lengths are a fitted
 * metric — good to about 1.65% and excellent for comparing distances — but a price function is
 * summing hundreds of them and would inherit the fit. Both are reported so the two can be checked
 * against each other.
 */
public final class EdgePrice {
    private EdgePrice() {}

    /**
     * One edge as the price function sees it.
     *
     * @param ticks   ticks a coasting traversal takes, averaged over entry states
     * @param scoring how many of those ticks are spent in a scoring region
     * @param spread  worst disagreement in {@code ticks} between entry states. <b>Large means the
     *                edge is not really one trajectory</b>, and everything here is a mean over
     *                several
     * @param exits   edges a coasting or single-held-turn traversal can leave for
     */
    public record Arc(int edge, double ticks, double scoring, int spread, int[] exits,
                      double clockLength) {

        /** Score per tick of traversing this edge alone. */
        public double rate() { return ticks <= 0 ? 0 : scoring / ticks; }
    }

    /** A simple cycle through the exit graph, and what it is worth per tick. */
    public record Cycle(int[] edges, double ticks, double scoring) {

        public double rate() { return ticks <= 0 ? 0 : scoring / ticks; }

        @Override
        public String toString() {
            return Arrays.toString(edges) + String.format(" %.1ft %.1fs %.5f/t", ticks, scoring,
                    rate());
        }
    }

    /**
     * The whole price function.
     *
     * @param gain     {@code lambda*}: the best score per tick any cycle sustains. <b>The ceiling
     *                 for one boid</b>, and the number a solo psyboid should be measured against
     * @param bias     {@code h(e)}, the value of standing at the start of edge {@code e} relative
     *                 to the average. Differences are meaningful; the level is not
     * @param exit     the exit a price-optimal boid takes from each edge, or -1 where the edge has
     *                 only one
     * @param best     the cycle achieving the gain
     */
    public record Price(Arc[] arcs, Cycle[] cycles, double gain, double[] bias, int[] exit,
                        Cycle best) {

        /** How much better than the gain-rate baseline it is to stand here rather than there. */
        public double advantage(int from, int to) { return bias[to] - bias[from]; }

        /**
         * The bias at a position part way along an edge, in points.
         * <p>
         * <b>The bias is defined at an edge's start; a search needs it anywhere.</b> Advancing tau
         * ticks along {@code e} spends {@code tau} ticks and collects whatever scores in them, so
         * by the same Bellman relation the value there is
         * {@code bias(e) + lambda* * tau - score collected}. It is continuous across a boundary by
         * construction: at {@code tau = length(e)} it equals {@code bias(next)}, which is what
         * makes it safe to compare two boids on different edges.
         * <p>
         * The score collected is prorated along the edge rather than profiled exactly. That is a
         * heuristic, and the one approximation here: on an edge whose scoring region is bunched at
         * one end it misplaces up to {@code score(e)} of value within that edge. It cancels
         * between siblings compared at the same instant, which is the only comparison made of it.
         */
        public double at(int edge, double tau) {
            if (edge < 0 || edge >= bias.length) return 0;
            Arc a = arcs[edge];
            double along = a.ticks() <= 0 ? 0 : Math.min(1, Math.max(0, tau / a.ticks()));
            return bias[edge] + gain * tau - a.scoring() * along;
        }
    }

    /**
     * What a whole flock's position is worth, as a potential.
     * <p>
     * <b>This is what lets a search see herding.</b> Scoring a line by the points it collects
     * cannot value inducing an exit, because the boid that was induced does not score for another
     * lap; scoring it by the sum of every boid's bias values the induced exit <em>the moment it
     * happens</em>, since that boid's bias jumps by the difference between the route it was on and
     * the route it is on now. On plait that difference is 37.5 points, half a scoring pass, and it
     * is invisible to any lookahead shorter than 900 ticks.
     * <p>
     * Boids on no edge contribute nothing rather than being skipped, so the total is comparable
     * between arrangements with different numbers of boids off the decomposition.
     */
    public static double potential(Price price, SolverFacts f, Sim.State s) {
        double total = 0;
        for (int i = 0; i < s.n; i++) {
            int e = f.edgeAt(s.x[i], s.y[i], s.h[i]);
            if (e < 0) continue;
            total += price.at(e, f.tickAt(s.x[i], s.y[i], s.h[i]));
        }
        return total;
    }

    /**
     * Derives the price function from the map and its decomposition. Reads no simulation.
     *
     * @param steerableOnly when true an exit counts only if some single held turn reaches it from
     *                      a critical state — the choices a psyboid can actually take. When false
     *                      every arc counts, which is what the flock can do
     */
    public static Price of(NavMap map, SolverFacts f, boolean steerableOnly) {
        int n = f.edges();
        Arc[] arcs = new Arc[n];
        for (int e = 0; e < n; e++) arcs[e] = traverse(map, f, e, steerableOnly);

        List<Cycle> cycles = new ArrayList<>();
        for (int start = 0; start < n; start++) {
            walk(arcs, start, start, new ArrayList<>(), new boolean[n], cycles);
        }
        cycles.sort((a, b) -> Double.compare(b.rate(), a.rate()));

        double gain = 0;
        Cycle best = null;
        for (Cycle c : cycles) {
            if (c.rate() > gain) { gain = c.rate(); best = c; }
        }

        // The bias as a longest path, not by value iteration.
        //
        // Relative value iteration does not converge here and the reason is structural: the
        // transition graph is deterministic, so its recurrent class is a bare cycle and therefore
        // perfectly periodic. Synchronous sweeps then oscillate with the cycle's own period
        // forever. On plait that left the values four sweeps out of phase while the policy was
        // already right, which is the worst way to be wrong — it looks converged.
        //
        // Once lambda* is fixed, give each arc the weight `score - lambda* * ticks`. Every cycle
        // then has weight at most zero, because lambda* is the largest mean any cycle achieves,
        // so longest paths are well defined and Bellman-Ford settles in at most n rounds. Pin the
        // optimal cycle, whose weights sum to exactly zero, and relax everything else onto it.
        double[] bias = new double[n];
        int[] exit = new int[n];
        Arrays.fill(bias, Double.NEGATIVE_INFINITY);
        Arrays.fill(exit, -1);
        if (best != null) {
            int[] loop = best.edges();
            // h(e) = w(e) + h(next), so walk the cycle backwards from a pinned node.
            int at = 0;
            bias[loop[at]] = 0;
            for (int k = 1; k <= loop.length - 1; k++) {
                int prev = loop[(loop.length - k) % loop.length];
                int next = loop[(loop.length - k + 1) % loop.length];
                bias[prev] = weight(arcs[prev], gain) + bias[next];
            }
        }
        for (int round = 0; round < n + 1; round++) {
            for (int e = 0; e < n; e++) {
                for (int g : arcs[e].exits()) {
                    if (bias[g] == Double.NEGATIVE_INFINITY) continue;
                    double v = weight(arcs[e], gain) + bias[g];
                    if (v > bias[e] + 1e-12) { bias[e] = v; exit[e] = g; }
                    else if (exit[e] < 0 && v >= bias[e] - 1e-12) exit[e] = g;
                }
            }
        }
        return new Price(arcs, cycles.toArray(new Cycle[0]), gain, bias, exit, best);
    }

    /** What traversing an edge is worth once the gain is charged for the time it takes. */
    private static double weight(Arc a, double gain) {
        return a.scoring() - gain * a.ticks();
    }

    /**
     * Coasts across one edge from every state that enters it, and averages what happens.
     * <p>
     * <b>Entry states, not all states.</b> A traversal is a thing that starts at one end, and
     * starting anywhere else measures a fragment. An entry state is one whose unsteered
     * predecessor lies on another edge — where a boid coasting into this edge first lands.
     */
    private static Arc traverse(NavMap map, SolverFacts f, int e, boolean steerableOnly) {
        int turns = Params.TURNS, w = map.width();
        short[] edgeOf = f.edgeOf();

        List<Integer> entries = new ArrayList<>();
        int[] back = new int[3];
        for (int s = 0; s < edgeOf.length; s++) {
            if (edgeOf[s] != e) continue;
            int d = s % turns, cell = s / turns, x = cell % w, y = cell / w;
            if (!map.alive(x, y, d)) continue;
            // An entry is a live state some other edge coasts into.
            int count = map.unsteeredPredecessors(s, back);
            for (int i = 0; i < count; i++) {
                int p = back[i];
                if (p >= 0 && edgeOf[p] >= 0 && edgeOf[p] != e) { entries.add(s); break; }
            }
        }
        // A cycle with no entrance — nothing coasts into it — is walked from its own states, so a
        // measurement exists rather than a hole.
        if (entries.isEmpty()) {
            for (int s = 0; s < edgeOf.length; s++) {
                if (edgeOf[s] != e) continue;
                int d = s % turns, cell = s / turns, x = cell % w, y = cell / w;
                if (map.alive(x, y, d)) entries.add(s);
                if (entries.size() >= 64) break;
            }
        }

        long totalTicks = 0, totalScore = 0;
        int min = Integer.MAX_VALUE, max = 0, counted = 0;
        for (int s : entries) {
            int at = s, ticks = 0, score = 0;
            for (int k = 0; k < 8192; k++) {
                int u = map.successor(at, 0);
                if (u < 0) break;
                if (edgeOf[u] != e) break;
                int cell = u / turns, x = cell % w, y = cell / w;
                if (map.score(x, y) > 0) score++;
                ticks++;
                at = u;
            }
            if (ticks == 0) continue;
            totalTicks += ticks;
            totalScore += score;
            min = Math.min(min, ticks);
            max = Math.max(max, ticks);
            counted++;
        }

        List<Integer> exits = new ArrayList<>();
        for (int g = 0; g < f.edges(); g++) {
            if (g == e) continue;
            if (f.straightTo()[e] == g) { exits.add(g); continue; }
            if ((f.arcs()[e] & (1L << g)) == 0) continue;
            if (!steerableOnly || PsyboidBits.reaches(map, f, e, g).reached()) exits.add(g);
        }

        int[] out = new int[exits.size()];
        for (int i = 0; i < out.length; i++) out[i] = exits.get(i);
        double ticks = counted == 0 ? 0 : totalTicks / (double) counted;
        double score = counted == 0 ? 0 : totalScore / (double) counted;
        return new Arc(e, ticks, score, counted == 0 ? 0 : max - min, out, f.length()[e]);
    }

    /** Every simple cycle from {@code start}, by depth-first walk over the exit graph. */
    private static void walk(Arc[] arcs, int start, int at, List<Integer> path, boolean[] on,
                             List<Cycle> out) {
        path.add(at);
        on[at] = true;
        for (int g : arcs[at].exits()) {
            if (g == start && path.size() > 0) {
                int[] edges = new int[path.size()];
                double ticks = 0, score = 0;
                for (int i = 0; i < edges.length; i++) {
                    edges[i] = path.get(i);
                    ticks += arcs[edges[i]].ticks();
                    score += arcs[edges[i]].scoring();
                }
                // One representative per cycle: keep it only when it starts at its own least
                // edge, or the same loop is reported once per node on it.
                int least = edges[0];
                for (int x : edges) least = Math.min(least, x);
                if (least == start) out.add(new Cycle(edges, ticks, score));
            } else if (!on[g] && g > start) {
                walk(arcs, start, g, path, on, out);
            }
        }
        on[at] = false;
        path.remove(path.size() - 1);
    }

    /** The price function, as a table. */
    public static void report(PresetScenarioParameter preset, Price p) {
        System.out.printf("%n=== %s @%s: the price function ===%n", preset.name(),
                preset.ingest().hash());
        System.out.printf("%-5s %9s %9s %8s %9s %8s   %s%n", "edge", "ticks", "clock", "spread",
                "scoring", "rate", "exits");
        for (Arc a : p.arcs()) {
            System.out.printf("%-5d %9.1f %9.2f %8d %9.1f %8.5f   %s%n", a.edge(), a.ticks(),
                    a.clockLength(), a.spread(), a.scoring(), a.rate(), Arrays.toString(a.exits()));
        }

        System.out.printf("%n%d simple cycle(s), best first:%n", p.cycles().length);
        for (Cycle c : p.cycles()) System.out.printf("  %s%n", c);

        System.out.printf("%ngain lambda* = %.6f per tick, on %s%n", p.gain(),
                p.best() == null ? "no cycle" : Arrays.toString(p.best().edges()));
        System.out.printf("%-5s %10s %8s%n", "edge", "bias", "exit");
        for (int e = 0; e < p.bias().length; e++) {
            System.out.printf("%-5d %10.2f %8d%n", e, p.bias()[e], p.exit()[e]);
        }
    }
}
