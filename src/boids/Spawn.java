package boids;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Where a flock starts, as a rule bound to one map.
 * <p>
 * <b>A spawn rule is part of a corpus's recipe, not an incidental of how it was generated.</b>
 * Two corpora flown from the same seeds under different rules are different bodies of evidence,
 * and a replay that does not reproduce the spawn does not reproduce the timeline — so
 * {@link CorpusPreset} carries the rule and {@link Derived.Corpus} hashes it into the path.
 * <p>
 * <b>Why the simulation's own rule is not the default any more.</b>
 * {@link Boids2DEngine#init} places boids uniformly over the play area by rejection, which makes
 * every dead end and every stretch of map a flock never visits as likely as the route it spends
 * its life on. A warm-up then exists to undo that. Measured on dabeone — `CORPUS.md` — the uniform
 * rule needs 500 ticks to reach a residual that {@link Rule#TAU_UNIFORM} is already below at tick
 * zero, so a better spawn beats any warm-up.
 *
 * <h2>Determinism</h2>
 * A spawn is a pure function of the rule, the map and the seed. It draws from its own
 * {@link Random}, seeded with the seed, so a replay reconstructs the same flock without storing
 * one — which is what lets a plan's label stay just a seed and a list of overrides.
 */
public final class Spawn {

    /** How a flock is placed. Named, because the name goes in a corpus's address. */
    public enum Rule {
        /**
         * What the simulation does: uniform over live states by rejection.
         * <p>
         * Kept as the baseline and as the way to reproduce anything generated before spawn rules
         * existed, not because it is a good place to start a flock.
         */
        UNIFORM("uniform over live states, the simulation's own rule"),

        /**
         * Uniform over <b>stable+</b>: the states ordinary multi-boid traffic reaches.
         * <p>
         * <b>Worse than {@link #UNIFORM} after warming, and instructively so.</b> Stable+ is a
         * set, not a measure — its density along the route is a fact about how the expansion came
         * out, and on dabeone it falls 0.464 / 0.194 / 0.341 over the stable edges against a true
         * 0.356 / 0.328 / 0.316. Because a boid's phase is conserved that error never washes out.
         * Sampling a set uniformly is not sampling the route uniformly.
         */
        STABLE_PLUS("uniform over the stable+ set"),

        /**
         * Uniform <b>by tau</b> along the stable edges, then matched into stable+. <b>The
         * default.</b>
         * <p>
         * Per boid: an edge drawn in proportion to its length and a tau uniform along it, which
         * puts the target uniformly along the route by distance rather than by state count. The
         * spawn is then a stable+ state on that edge <em>or one adjacent to it</em> whose own tau,
         * rebased into the chosen edge's frame, is nearest the target — the adjacency being what
         * makes the coordinate continuous across a vertex, so a target near an edge's end is not
         * dragged back inside it.
         */
        TAU_UNIFORM("uniform by tau along the stable edges, matched into stable+");

        private final String describes;

        Rule(String describes) { this.describes = describes; }

        public String describes() { return describes; }
    }

    private final ScenarioParameter scenario;
    private final Rule rule;
    private final NavMap map;
    private final Boids2DEngine engine;
    private final int flock;
    private final int[] pool;
    private final Targets targets;

    private Spawn(ScenarioParameter scenario, Rule rule, NavMap map, Boids2DEngine engine,
                  int flock, int[] pool, Targets targets) {
        this.scenario = scenario;
        this.rule = rule;
        this.map = map;
        this.engine = engine;
        this.flock = flock;
        this.pool = pool;
        this.targets = targets;
    }

    /**
     * Binds a rule to a map, doing the per-map work once.
     *
     * @param plus stable+, needed by every rule but {@link Rule#UNIFORM}. May be null for that one
     */
    public static Spawn of(ScenarioParameter scenario, SolverFacts f, StateSet plus, Rule rule)
            throws IOException {
        NavMap map = NavMapBuilder.buildFromPng(scenario.mapPath(),
                Math.round(scenario.turningRadius()));
        Boids2DEngine engine = new Boids2DEngine(scenario);
        if (rule != Rule.UNIFORM && plus == null) {
            throw new IllegalArgumentException(rule + " needs stable+ and was given none");
        }
        return new Spawn(scenario, rule, map, engine, scenario.flockSize(),
                rule == Rule.STABLE_PLUS ? plus.toArray() : null,
                rule == Rule.TAU_UNIFORM ? Targets.of(f, plus) : null);
    }

    /** The same rule on a flock of a different size — how a solo run is set up. */
    public Spawn resized(int boids) throws IOException {
        ScenarioParameter sized = new ScenarioParameter() {
            public java.nio.file.Path mapPath() { return scenario.mapPath(); }
            public float turningRadius() { return scenario.turningRadius(); }
            public int flockSize() { return boids; }
        };
        return new Spawn(sized, rule, map, new Boids2DEngine(sized), boids, pool, targets);
    }

    public Rule rule() { return rule; }

    public ScenarioParameter scenario() { return scenario; }

    /** The engine this spawn's flocks are flown on. Shared, so nothing rebuilds the navmap. */
    public Boids2DEngine engine() { return engine; }

    /** A flock at tick 0. */
    public Sim.State at(long seed) {
        if (rule == Rule.UNIFORM) return engine.init(seed);

        int[] x = new int[flock], y = new int[flock], h = new int[flock];
        Random rng = new Random(seed);
        for (int i = 0; i < flock; i++) {
            int state = rule == Rule.STABLE_PLUS ? pool[rng.nextInt(pool.length)]
                    : targets.draw(rng);
            int d = state % Params.TURNS, cell = state / Params.TURNS;
            x[i] = cell % map.width();
            y[i] = cell / map.width();
            h[i] = d;
            if (!map.alive(x[i], y[i], h[i])) {
                throw new IllegalStateException(rule + " placed boid " + i + " at a dead state "
                        + x[i] + "," + y[i] + " heading " + h[i] + " — the spawn set is not a "
                        + "subset of the viability kernel, which every set here is built from");
            }
        }
        return new Sim.State(flock, x, y, h, 0L, 0L, new long[flock], "seed" + seed);
    }

    /** A flock warmed for {@code warm} ticks, which is how every caller actually wants it. */
    public Sim.State warmed(long seed, int warm) {
        Sim.State s = at(seed);
        for (int t = 0; t < warm; t++) s = engine.tick(s);
        return s;
    }

    /**
     * Stable+ indexed by position along the stable edges, so a tau can be turned into a state.
     * <p>
     * <b>One coordinate per stable edge, running past both its ends.</b> A state's position in
     * edge {@code e}'s frame is the clock distance from the start of {@code e}, which
     * {@link EdgeDistance} defines for a state on {@code e} and extends by one edge length in
     * either direction for a state on an edge adjacent to it.
     */
    private record Targets(int[] edge, double[] length, double total, int[][] states,
                           double[][] position) {

        static Targets of(SolverFacts f, StateSet plus) {
            List<Integer> stable = new ArrayList<>();
            for (int e = 0; e < f.edges(); e++) if (f.stable(e)) stable.add(e);
            if (stable.isEmpty()) {
                throw new IllegalStateException("TAU_UNIFORM needs at least one stable edge and "
                        + "this map has none; unsteered travel returns to no edge without "
                        + "scoring, so there is no orbit to spread a flock along");
            }

            int[] edges = new int[stable.size()];
            double[] len = new double[stable.size()];
            int[][] states = new int[stable.size()][];
            double[][] pos = new double[stable.size()][];
            double total = 0;
            int[] all = plus.toArray();
            short[] edgeOf = f.edgeOf();
            double[] tickOf = f.tickOf();
            long[] arcs = f.arcs();

            for (int i = 0; i < edges.length; i++) {
                int e = stable.get(i);
                edges[i] = e;
                len[i] = f.length()[e];
                total += len[i];

                List<Integer> keep = new ArrayList<>();
                List<Double> where = new ArrayList<>();
                for (int s : all) {
                    int g = edgeOf[s];
                    if (g < 0 || Double.isNaN(tickOf[s])) continue;
                    double p = position(f, arcs, e, g, tickOf[s]);
                    if (Double.isNaN(p)) continue;
                    keep.add(s);
                    where.add(p);
                }
                if (keep.isEmpty()) {
                    throw new IllegalStateException("no stable+ state lies on edge " + e
                            + " or anything adjacent to it, so nothing can be spawned along it");
                }
                Integer[] order = new Integer[keep.size()];
                for (int k = 0; k < order.length; k++) order[k] = k;
                Arrays.sort(order, (a, b) -> Double.compare(where.get(a), where.get(b)));
                states[i] = new int[keep.size()];
                pos[i] = new double[keep.size()];
                for (int k = 0; k < order.length; k++) {
                    states[i][k] = keep.get(order[k]);
                    pos[i][k] = where.get(order[k]);
                }
            }
            return new Targets(edges, len, total, states, pos);
        }

        /**
         * Where a state on edge {@code g} sits in edge {@code e}'s frame, or NaN if {@code g} is
         * neither {@code e} nor adjacent to it.
         * <p>
         * Signed clock distance from the start of {@code e}, by {@link EdgeDistance}'s rule that a
         * route contributes the length of every edge it leaves: on {@code e} itself that is tau
         * outright, one step downstream it is tau plus {@code e}'s length, and one step upstream
         * tau less the upstream edge's.
         * <p>
         * <b>Tau is already zero-based and {@code tickLo} is not its zero.</b> An edge's observed
         * tau overruns both ends — dabeone's edge 2 runs -16.10 to 101.70 against a length of
         * 100.59 — because follow-through and arrival states sit on an edge before its start and
         * after its end. Rebasing on {@code tickLo} therefore shifts each edge's frame by a
         * different amount, which silently skewed this rule's spawn across the vertices before it
         * was caught.
         */
        private static double position(SolverFacts f, long[] arcs, int e, int g, double tau) {
            if (g == e) return tau;
            if ((arcs[e] & (1L << g)) != 0) return tau + f.length()[e];
            if ((arcs[g] & (1L << e)) != 0) return tau - f.length()[g];
            return Double.NaN;
        }

        /**
         * One spawn: an edge in proportion to its length, a tau uniform along it, and the nearest
         * stable+ state to that target in the edge's own frame.
         * <p>
         * <b>Nearest, then uniform among ties within half a tick.</b> Many states share a tau — an
         * edge is a bundle of trajectories, not a line — so taking the single nearest would spawn
         * from a fixed thread through the bundle. Half a tick is well inside the clock's own 1.65%
         * error, so everything inside it is the same position as far as anything here can tell.
         */
        int draw(Random rng) {
            double u = rng.nextDouble() * total;
            int i = 0;
            while (i < length.length - 1 && u > length[i]) { u -= length[i]; i++; }
            double target = u;

            double[] where = position[i];
            int lo = Arrays.binarySearch(where, target);
            if (lo < 0) lo = -lo - 1;
            int best = lo;
            if (lo >= where.length || (lo > 0
                    && Math.abs(where[lo - 1] - target) < Math.abs(where[lo] - target))) {
                best = lo - 1;
            }
            double near = Math.abs(where[best] - target);
            int from = best, to = best;
            while (from > 0 && Math.abs(where[from - 1] - target) <= near + 0.5) from--;
            while (to < where.length - 1 && Math.abs(where[to + 1] - target) <= near + 0.5) to++;
            return states[i][from + rng.nextInt(to - from + 1)];
        }
    }
}
