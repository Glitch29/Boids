package boids;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * How long a flock takes to forget where it was spawned, measured over the edges.
 * <p>
 * <b>What a warm-up is for.</b> Boids are placed uniformly over the play area, which is not where
 * a flock is normally found; the warm-up exists to let them flush out of places they would not
 * ordinarily occupy. `CORPUS.md` states the ideal as the <em>minimal</em> number of ticks such
 * that no inference can be made about any boid's spawn location, and minimal is load-bearing —
 * letting the flock settle into a stable orbit homogenises the seeds and throws away the
 * slightly-unstable configurations that are the interesting ones.
 * <p>
 * <b>The proxy.</b> The distribution of the flock over edges, averaged over seeds in windows of a
 * few dozen ticks, against the same distribution in the long run. Edges rather than pixels
 * because an edge is the unit a route is made of, and the whole question is whether a boid is
 * somewhere its history explains.
 * <p>
 * <b>Distance against noise, not against zero.</b> A single seed's occupancy over a 50-tick window
 * never equals the long-run figure — four boids in fifty ticks is a small sample of a distribution
 * over nine edges. So the bias in the seed average is compared against {@link Decay#sigma()}, the
 * spread of one seed's window around the long-run mean, and warm-up is done when the systematic
 * part is small relative to the part no amount of waiting removes.
 *
 * <h2>Spawn rules</h2>
 * The warm-up is a correction for the spawn rule, so the other half of the question is whether a
 * better rule needs less correcting. {@link Spawn} holds three, and the comparison is what says
 * whether 5,000 ticks is buying anything a spawn could give for free.
 */
public final class EdgeOccupancy {
    private EdgeOccupancy() {}

    /** Where the flock starts. */
    public enum Spawn {
        /**
         * What the simulation does: uniform over the play area by rejection, redrawing until the
         * state is live. Every dead end and every stretch of map a flock never visits is as
         * likely as the route it spends its life on, which is exactly what a warm-up is paying
         * to undo.
         */
        UNIFORM("uniform over live states, the simulation's own rule"),

        /**
         * Uniform over <b>stable+</b>: the states ordinary multi-boid traffic reaches. Not the
         * same as uniform along the route — stable+ is denser where the straight-travel loop and
         * its jostling happen to put more states, and that density is a fact about the expansion
         * rather than about where boids are found.
         */
        STABLE_PLUS("uniform over the stable+ set"),

        /**
         * Uniform <b>by tau</b> along the stable edges, then matched into stable+.
         * <p>
         * Per boid: an edge is drawn in proportion to its length and a tau uniformly along it,
         * which puts the target uniformly along the route by distance rather than by state count.
         * The spawn is then a stable+ state on that edge <em>or one adjacent to it</em> whose own
         * tau, rebased into the chosen edge's frame, is nearest the target. The adjacency is what
         * makes the coordinate continuous across a vertex, so a target near an edge's end is not
         * forced back inside it.
         */
        TAU_UNIFORM("uniform by tau along the stable edges, matched into stable+");

        private final String describes;

        Spawn(String describes) { this.describes = describes; }

        public String describes() { return describes; }
    }

    /**
     * One rule's decay curve and the scales it should be read against.
     *
     * @param early    per window, the mean occupancy vector across seeds. Index 0 is ticks
     *                 {@code [0, window)}
     * @param longrun  the occupancy the same flocks settle to, over both halves of the long
     *                 window and every seed
     * @param sigma    RMS distance of <b>one seed's</b> long-run window from {@code longrun}. The
     *                 noise a warm-up cannot remove, and the unit the curve is read in
     * @param between  RMS distance of one seed's <b>long-run mean</b> from {@code longrun}. If
     *                 this is not far below {@code sigma}, seeds are keeping something of their
     *                 start forever and no warm-up fixes it
     * @param drift    distance between the two halves of the long window. The stationarity check:
     *                 a long run still moving is not a long run
     * @param spawned  how many boids each rule placed on each edge, for the console
     * @param offEarly per early window, how many seeds had <b>any</b> boid off the stable edges
     *                 during it. <b>Not the same question as the mean occupancy off them</b>, and
     *                 the one that matters: a flock confined to the stable edges cannot score, so
     *                 this is the fraction of seeds that could have scored in that window
     * @param offLate  the same over the long-run windows, which is the rate it settles to
     */
    public record Decay(Spawn rule, int seeds, int window, int flock, double[][] early,
                        double[] longrun, double sigma, double between, double drift,
                        int[] spawned, int[] offEarly, double offLate) {

        /** Fraction of seeds with a boid off the stable edges during window {@code w}. */
        public double offRate(int w) { return offEarly[w] / (double) seeds; }

        /** Distance from the long-run occupancy at window {@code w}. */
        public double bias(int w) { return distance(early[w], longrun); }

        /** The squared distance, which is what the request was phrased in. */
        public double biasSquared(int w) {
            double d = bias(w);
            return d * d;
        }

        /**
         * The squared distance with the sampling noise taken out, which is the one to read.
         * <p>
         * A mean of {@code seeds} draws sits at squared distance
         * {@code ||true bias||² + sigma²/seeds} from what it estimates, so the raw curve does not
         * decay to zero however long the flock flies — it decays to {@link #floor}<sup>2</sup> and
         * then rattles around there. Subtracting that pedestal gives an unbiased estimate of the
         * squared bias itself, and it is what makes the difference between "the spawn is still
         * showing" and "the measurement has run out of seeds" visible.
         */
        public double biasSquaredAdjusted(int w) {
            return Math.max(0, biasSquared(w) - sigma * sigma / seeds);
        }

        public double biasAdjusted(int w) { return Math.sqrt(biasSquaredAdjusted(w)); }

        /** Ticks at the far end of window {@code w}. */
        public int at(int w) { return (w + 1) * window; }

        /**
         * The noise floor on {@link #bias}: averaging {@code seeds} seeds cannot resolve a bias
         * below this, so a crossing at or under it says only that the measurement ran out.
         */
        public double floor() { return sigma / Math.sqrt(seeds); }

        /** The first window whose bias is within {@code k} sigma, or -1 if it never is. */
        public int within(double k) {
            for (int w = 0; w < early.length; w++) if (bias(w) <= k * sigma) return w;
            return -1;
        }

        /**
         * The bias over one block of windows, as an RMS — the curve's <b>envelope</b>.
         * <p>
         * <b>The per-window curve does not decay to zero and is not supposed to.</b> Boids move
         * one step a tick, so a boid's position along its route at tick {@code t} is its position
         * at tick 0 advanced by {@code t}: the flock's distribution over phase is very nearly
         * conserved, and the mean occupancy at a given tick therefore oscillates with the lap
         * more or less forever. A single window is a sample of that oscillation. The envelope
         * over a block wider than the lap is what actually decays.
         */
        public double envelope(int from, int windows) {
            double sum = 0;
            int n = Math.min(windows, early.length - from);
            for (int w = from; w < from + n; w++) sum += biasSquaredAdjusted(w);
            return Math.sqrt(sum / n);
        }

        /** The first block whose envelope is within {@code k} sigma, or -1 if none is. */
        public int settledWithin(double k, int block) {
            for (int b = 0; (b + 1) * block <= early.length; b++) {
                if (envelope(b * block, block) <= k * sigma) return b;
            }
            return -1;
        }

        /**
         * The level the envelope settles to: its RMS over the last quarter of the curve.
         * <p>
         * <b>Not zero, and not reachable by waiting.</b> A boid advances one step per tick along a
         * loop of fixed length, so its phase at tick {@code t} is its spawn phase plus {@code t}
         * and the flock's distribution over phase is conserved. Whatever non-uniformity the spawn
         * puts into that distribution is carried for as long as the run lasts, and shows up as an
         * oscillation of the mean occupancy at the lap period. A warm-up is a time shift, and a
         * time shift cannot flatten a periodic function.
         */
        public double plateau() {
            int from = early.length - early.length / 4;
            return envelope(from, early.length - from);
        }

        /**
         * The first block whose envelope is within {@code factor} of the plateau — <b>the warm-up
         * this rule actually needs.</b>
         * <p>
         * Past it a longer warm-up buys nothing: what is left is the residual the spawn wrote into
         * the phase distribution, and it is still there tens of thousands of ticks later.
         */
        public int reaches(double factor, int block) {
            double level = factor * plateau();
            for (int b = 0; (b + 1) * block <= early.length; b++) {
                if (envelope(b * block, block) <= level) return b;
            }
            return -1;
        }
    }

    private static double distance(double[] a, double[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) sum += (a[i] - b[i]) * (a[i] - b[i]);
        return Math.sqrt(sum);
    }

    /**
     * Flies {@code seeds} psyboid-free flocks and measures how fast the edge distribution forgets
     * the spawn.
     *
     * @param through  last tick of the decay curve; windows tile {@code [0, through)}
     * @param longFrom first tick counted as long-run. Far enough out that nothing of the spawn
     *                 survives, which is the assumption {@link Decay#drift} checks
     */
    public static Decay measure(PresetScenarioParameter preset, SolverFacts f, StateSet plus,
                                Spawn rule, int seeds, int window, int through, int longFrom,
                                int longTo) throws IOException {
        Boids2DEngine engine = new Boids2DEngine(preset);
        NavMap map = NavMapBuilder.buildFromPng(preset.mapPath(),
                Math.round(preset.turningRadius()));
        Targets targets = rule == Spawn.TAU_UNIFORM ? Targets.of(f, plus) : null;
        int[] pool = rule == Spawn.STABLE_PLUS ? plus.toArray() : null;

        int buckets = f.edges() + 1;
        int earlyWindows = through / window;
        int longWindows = (longTo - longFrom) / window;
        double[][] early = new double[earlyWindows][buckets];
        // Every long-run window of every seed is kept, because sigma is their spread about a mean
        // none of them knows yet. Ten buckets a window is nothing next to the flight itself.
        double[][][] late = new double[seeds][longWindows][buckets];
        int[] spawned = new int[buckets];
        int[] offEarly = new int[earlyWindows];
        long offLate = 0;

        for (int s = 0; s < seeds; s++) {
            Sim.State state = start(engine, map, preset.flockSize(), pool, targets, rule, s);
            for (int i = 0; i < state.n; i++) spawned[bucket(f, state, i)]++;

            boolean[] offHere = new boolean[earlyWindows];
            boolean[] offThere = new boolean[longWindows];
            while (state.tick < longTo) {
                int t = (int) state.tick;
                double[] into = null;
                boolean[] flag = null;
                int at = -1;
                if (t < through) {
                    at = t / window;
                    into = early[at];
                    flag = offHere;
                } else if (t >= longFrom && t < longTo) {
                    at = (t - longFrom) / window;
                    into = late[s][at];
                    flag = offThere;
                }
                if (into != null) {
                    for (int i = 0; i < state.n; i++) {
                        int b = bucket(f, state, i);
                        into[b]++;
                        // Off the stable edges is the whole of what "could have scored" means:
                        // the stable cycle holds no scoring state, so a flock that stays on it
                        // scores nothing whatever else it does.
                        if (b == f.edges() || !f.stable(b)) flag[at] = true;
                    }
                }
                state = engine.tick(state);
            }
            for (int w = 0; w < earlyWindows; w++) if (offHere[w]) offEarly[w]++;
            for (boolean b : offThere) if (b) offLate++;
        }

        int flock = preset.flockSize();
        normalise(early, (double) seeds * window * flock);
        for (double[][] seed : late) normalise(seed, (double) window * flock);

        double[] longrun = new double[buckets];
        double[] firstHalf = new double[buckets], secondHalf = new double[buckets];
        for (double[][] seed : late) {
            for (int w = 0; w < longWindows; w++) {
                for (int e = 0; e < buckets; e++) {
                    longrun[e] += seed[w][e];
                    (w < longWindows / 2 ? firstHalf : secondHalf)[e] += seed[w][e];
                }
            }
        }
        scale(longrun, 1.0 / (seeds * (double) longWindows));
        scale(firstHalf, 1.0 / (seeds * (double) (longWindows / 2)));
        scale(secondHalf, 1.0 / (seeds * (double) (longWindows - longWindows / 2)));

        double varWindow = 0, varBetween = 0;
        for (double[][] seed : late) {
            double[] mean = new double[buckets];
            for (double[] w : seed) {
                double d = distance(w, longrun);
                varWindow += d * d;
                for (int e = 0; e < buckets; e++) mean[e] += w[e] / longWindows;
            }
            double d = distance(mean, longrun);
            varBetween += d * d;
        }
        double sigma = Math.sqrt(varWindow / (seeds * (double) longWindows));
        double between = Math.sqrt(varBetween / seeds);
        return new Decay(rule, seeds, window, flock, early, longrun, sigma, between,
                distance(firstHalf, secondHalf), spawned, offEarly,
                offLate / (double) (seeds * (long) longWindows));
    }

    private static void normalise(double[][] rows, double by) {
        for (double[] row : rows) scale(row, 1.0 / by);
    }

    private static void scale(double[] row, double by) {
        for (int i = 0; i < row.length; i++) row[i] *= by;
    }

    /** Which bucket a boid counts in: its edge, or the last bucket where it has none. */
    private static int bucket(SolverFacts f, Sim.State s, int i) {
        int e = f.edgeAt(s.x[i], s.y[i], s.h[i]);
        return e < 0 ? f.edges() : e;
    }

    /**
     * A flock at tick 0 under one spawn rule.
     * <p>
     * {@code UNIFORM} defers to {@link Boids2DEngine#init} rather than reproducing it, so the
     * baseline in this comparison is the simulation's own rule and not a second implementation of
     * it that might differ.
     */
    private static Sim.State start(Boids2DEngine engine, NavMap map, int n, int[] pool,
                                   Targets targets, Spawn rule, long seed) throws IOException {
        if (rule == Spawn.UNIFORM) return engine.init(seed);

        int[] x = new int[n], y = new int[n], h = new int[n];
        Random rng = new Random(seed);
        for (int i = 0; i < n; i++) {
            int state = rule == Spawn.STABLE_PLUS ? pool[rng.nextInt(pool.length)]
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
        return new Sim.State(n, x, y, h, 0L, 0L, new long[n], "seed" + seed);
    }

    /**
     * Stable+ indexed by position along the stable edges, so a tau can be turned into a state.
     * <p>
     * <b>One coordinate per stable edge, running past both its ends.</b> A state's position in
     * edge {@code e}'s frame is the clock distance from the start of {@code e}, which
     * {@link EdgeDistance} defines for a state on {@code e} and extends by one edge length in
     * either direction for a state on an edge adjacent to it. That is what lets a target near a
     * vertex be matched by a state on the other side of it, rather than being dragged back inside
     * an edge that has no stable+ states left at that end.
     */
    private record Targets(int[] edge, double[] length, double total, int[][] states,
                           double[][] position) {

        static Targets of(SolverFacts f, StateSet plus) {
            List<Integer> stable = new ArrayList<>();
            for (int e = 0; e < f.edges(); e++) if (f.stable(e)) stable.add(e);

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

                List<int[]> keep = new ArrayList<>();
                List<Double> where = new ArrayList<>();
                for (int s : all) {
                    int g = edgeOf[s];
                    if (g < 0 || Double.isNaN(tickOf[s])) continue;
                    double p = position(f, arcs, e, g, tickOf[s]);
                    if (Double.isNaN(p)) continue;
                    keep.add(new int[]{s});
                    where.add(p);
                }
                states[i] = new int[keep.size()];
                pos[i] = new double[keep.size()];
                Integer[] order = new Integer[keep.size()];
                for (int k = 0; k < order.length; k++) order[k] = k;
                Arrays.sort(order, (a, b) -> Double.compare(where.get(a), where.get(b)));
                for (int k = 0; k < order.length; k++) {
                    states[i][k] = keep.get(order[k])[0];
                    pos[i][k] = where.get(order[k]);
                }
            }
            Targets t = new Targets(edges, len, total, states, pos);
            System.out.printf("  tau targets over %d stable edges, total length %.1f%n",
                    edges.length, total);
            for (int i = 0; i < edges.length; i++) {
                System.out.printf("    edge %d: length %6.2f, tau %6.2f..%-6.2f, %,6d stable+ "
                                + "states in frame, positions %7.2f..%-7.2f%n", edges[i], len[i],
                        f.tickLo()[edges[i]], f.tickHi()[edges[i]], states[i].length,
                        pos[i][0], pos[i][pos[i].length - 1]);
            }
            return t;
        }

        /**
         * Where a state on edge {@code g} sits in edge {@code e}'s frame, or NaN if {@code g} is
         * neither {@code e} nor adjacent to it.
         * <p>
         * Signed clock distance from the start of {@code e}, by {@link EdgeDistance}'s rule that
         * a route contributes the length of every edge it leaves: on {@code e} itself that is tau
         * outright, one step downstream it is tau plus {@code e}'s length, and one step upstream
         * tau less the upstream edge's.
         * <p>
         * <b>Tau is already zero-based and {@code tickLo} is not its zero.</b> An edge's observed
         * tau overruns both ends — dabeone's edge 2 runs -16.10 to 101.70 against a length of
         * 100.59 — because follow-through and arrival states sit on an edge before its start and
         * after its end. Rebasing on {@code tickLo} therefore shifts each edge's frame by a
         * different amount, which silently skewed this rule's spawn distribution across the
         * vertices before it was caught.
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
         * <b>Nearest, then uniform among ties within half a tick.</b> Many states share a tau —
         * an edge is a bundle of trajectories, not a line — so taking the single nearest would
         * spawn from a fixed thread through the bundle. Half a tick is well inside the clock's own
         * 1.65% error, so everything inside it is the same position as far as anything here can
         * tell.
         */
        int draw(Random rng) {
            double u = rng.nextDouble() * total;
            int i = 0;
            while (i < length.length - 1 && u > length[i]) { u -= length[i]; i++; }
            double target = u;

            double[] where = position[i];
            int lo = Arrays.binarySearch(where, target);
            if (lo < 0) lo = -lo - 1;
            // Nearest first, then everything within half a tick of it, so a target with no state
            // at all nearby still lands on the closest thing rather than failing.
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

    /**
     * How many seeds score with no psyboid at all, over a run of a given length starting at a
     * given tick — the measurement {@link PsyboidBits#WARM} was chosen on.
     *
     * @param scored   seeds accruing any score at all in the window
     * @param wentOff  seeds putting any boid off the stable edges in it. <b>The same event seen
     *                 one step earlier</b>: a flock on the stable cycle cannot score, so leaving
     *                 it is what scoring is downstream of, and the gap between the two counts is
     *                 excursions that left and came back without reaching a scoring region
     * @param occOff   mean fraction of boid-ticks spent off the stable edges
     */
    public record Warmup(int start, int run, int seeds, int scored, int wentOff, double occOff,
                         double perTick) {

        public double scoredRate() { return scored / (double) seeds; }

        public double wentOffRate() { return wentOff / (double) seeds; }
    }

    /**
     * Re-measures the warm-up's original criterion at several candidate warm-ups at once.
     * <p>
     * <b>One flight per seed, not one per candidate.</b> Every window is a stretch of the same
     * timeline, so they are all accumulated in a single pass; the cost is the longest candidate
     * plus the run, whatever the number of candidates.
     */
    public static List<Warmup> warmupScoring(PresetScenarioParameter preset, SolverFacts f,
                                             int seeds, int run, int[] starts) throws IOException {
        Boids2DEngine engine = new Boids2DEngine(preset);
        int[] scored = new int[starts.length], wentOff = new int[starts.length];
        long[] off = new long[starts.length], points = new long[starts.length];
        int last = 0;
        for (int s : starts) last = Math.max(last, s + run);

        for (long seed = 0; seed < seeds; seed++) {
            Sim.State state = engine.init(seed);
            long[] base = new long[starts.length], end = new long[starts.length];
            boolean[] anyOff = new boolean[starts.length];
            while (state.tick < last) {
                int t = (int) state.tick;
                for (int i = 0; i < starts.length; i++) {
                    if (t < starts[i] || t >= starts[i] + run) continue;
                    if (t == starts[i]) base[i] = state.score;
                    for (int b = 0; b < state.n; b++) {
                        int e = f.edgeAt(state.x[b], state.y[b], state.h[b]);
                        if (e < 0 || !f.stable(e)) { off[i]++; anyOff[i] = true; }
                    }
                }
                state = engine.tick(state);
                for (int i = 0; i < starts.length; i++) {
                    if (t == starts[i] + run - 1) end[i] = state.score;
                }
            }
            for (int i = 0; i < starts.length; i++) {
                if (end[i] > base[i]) scored[i]++;
                if (anyOff[i]) wentOff[i]++;
                points[i] += end[i] - base[i];
            }
        }

        List<Warmup> out = new ArrayList<>();
        double boidTicks = (double) run * preset.flockSize();
        for (int i = 0; i < starts.length; i++) {
            out.add(new Warmup(starts[i], run, seeds, scored[i], wentOff[i],
                    off[i] / (boidTicks * seeds), points[i] / ((double) run * seeds)));
        }
        return out;
    }

    /**
     * Every rule, measured and reported side by side.
     * <p>
     * Side by side and not one at a time, because the number that matters is not any one curve
     * but whether a different spawn removes the need for the warm-up the first one needs.
     */
    public static void run(PresetScenarioParameter preset, SolverFacts f, StateSet plus,
                           Derived.Behaviour where, int seeds, int window, int through,
                           int block, int longFrom, int longTo) throws IOException {
        System.out.printf("%n=== %s @%s: edge-occupancy decay, %d seeds, %d-tick windows ===%n",
                preset.name(), preset.ingest().hash(), seeds, window);
        System.out.printf("curve over [0, %,d); long run [%,d, %,d); stable+ %,d states%n",
                through, longFrom, longTo, plus.size());

        // Stated rather than assumed, because everything below reads "off the stable edges" as
        // "could have scored", and that equivalence is a property of this map's decomposition.
        StringBuilder stable = new StringBuilder(), scoring = new StringBuilder();
        for (int e = 0; e < f.edges(); e++) {
            if (f.stable(e)) stable.append(stable.isEmpty() ? "" : ",").append(e);
            if (f.scoring(e)) scoring.append(scoring.isEmpty() ? "" : ",").append(e);
        }
        System.out.printf("stable edges %s; scoring edges %s — no edge is both, so a flock that "
                + "stays on the stable cycle cannot score%n", stable, scoring);

        List<Decay> all = new ArrayList<>();
        for (Spawn rule : Spawn.values()) {
            System.out.printf("%n-- %s: %s --%n", rule, rule.describes());
            long began = System.nanoTime();
            Decay d = measure(preset, f, plus, rule, seeds, window, through, longFrom, longTo);
            all.add(d);
            System.out.printf("%,d seeds in %.0fs%n", seeds, (System.nanoTime() - began) / 1e9);
            System.out.printf("spawned on edges  %s%n", counts(d.spawned()));
            System.out.printf("long run          %s%n", occupancy(d.longrun()));
            System.out.printf("sigma %.5f (one seed, one window) · between-seed %.5f · "
                            + "floor %.5f · half-to-half drift %.5f%n",
                    d.sigma(), d.between(), d.floor(), d.drift());
            for (double k : new double[]{1.0, 0.5, 0.1}) {
                int w = d.within(k), settled = d.settledWithin(k, block);
                System.out.printf("  within %.1f sigma (%.4f): one window from tick %-9s | "
                                + "envelope over %,d ticks from %s%n", k, k * d.sigma(),
                        w < 0 ? "never" : String.format("%,d", d.at(w)), block * window,
                        settled < 0 ? "never, inside " + through
                                : String.format("%,d", settled * block * window));
            }
            System.out.printf("  plateau %.5f (%.2f sigma) — the residual no warm-up removes%n",
                    d.plateau(), d.plateau() / d.sigma());
            System.out.printf("  seeds with a boid off the stable edges: %.1f%% in the first "
                            + "window, %.1f%% at tick %,d, %.1f%% in the long run%n",
                    100 * d.offRate(0), 100 * d.offRate(d.early().length - 1),
                    d.at(d.early().length - 1), 100 * d.offLate());
            for (double factor : new double[]{1.5, 1.1}) {
                int b = d.reaches(factor, block);
                System.out.printf("  reaches %.0f%% of the plateau at tick %s%n", factor * 100,
                        b < 0 ? "never, inside " + through
                                : String.format("%,d", b * block * window));
            }
        }

        int rows = Math.min(40, all.get(0).early().length);
        System.out.printf("%n-- per window, first %,d ticks: bias with the sampling noise taken "
                + "out --%n%8s", rows * window, "tick");
        for (Decay d : all) System.out.printf("  %24s", d.rule());
        System.out.printf("%n%8s", "");
        for (int i = 0; i < all.size(); i++) {
            System.out.printf("  %11s %7s %5s", "d^2 adj", "d adj", "/sig");
        }
        System.out.println();
        for (int w = 0; w < rows; w++) {
            System.out.printf("%8d", all.get(0).at(w));
            for (Decay d : all) {
                System.out.printf("  %11.8f %7.4f %5.2f", d.biasSquaredAdjusted(w),
                        d.biasAdjusted(w), d.biasAdjusted(w) / d.sigma());
            }
            System.out.println();
        }

        System.out.printf("%n-- the envelope over each %,d ticks, and the %% of seeds with a boid "
                + "off the stable edges in it --%n%16s", block * window, "ticks");
        for (Decay d : all) System.out.printf("  %22s", d.rule());
        System.out.printf("%n%16s", "");
        for (int i = 0; i < all.size(); i++) System.out.printf("  %8s %5s %6s", "rms", "/sig", "off%");
        System.out.println();
        for (int b = 0; (b + 1) * block <= all.get(0).early().length; b++) {
            System.out.printf("%7d-%-8d", b * block * window, (b + 1) * block * window);
            for (Decay d : all) {
                double e = d.envelope(b * block, block);
                int offAny = 0;
                for (int w = b * block; w < (b + 1) * block; w++) offAny += d.offEarly()[w];
                System.out.printf("  %8.5f %5.2f %5.1f%%", e, e / d.sigma(),
                        100.0 * offAny / (d.seeds() * (double) block));
            }
            System.out.println();
        }

        Path dir = where.at("occupancy");
        for (Decay d : all) {
            StringBuilder tsv = new StringBuilder("# " + preset.name() + " @"
                    + preset.ingest().hash() + " " + d.rule() + ": " + d.rule().describes()
                    + System.lineSeparator());
            tsv.append("# sigma=").append(String.format("%.6f", d.sigma()))
                    .append(" between=").append(String.format("%.6f", d.between()))
                    .append(" floor=").append(String.format("%.6f", d.floor()))
                    .append(" drift=").append(String.format("%.6f", d.drift()))
                    .append(" seeds=").append(d.seeds()).append(" window=").append(d.window())
                    .append(System.lineSeparator());
            tsv.append("tick\tdistanceSquared\tdistance\tsigmas\tadjSquared\tadj\tadjSigmas");
            for (int e = 0; e < d.longrun().length; e++) {
                tsv.append("\te").append(e == f.edges() ? "None" : String.valueOf(e));
            }
            tsv.append(System.lineSeparator());
            tsv.append("longrun\t\t\t\t\t\t");
            for (double v : d.longrun()) tsv.append('\t').append(String.format("%.6f", v));
            tsv.append(System.lineSeparator());
            for (int w = 0; w < d.early().length; w++) {
                tsv.append(d.at(w)).append('\t')
                        .append(String.format("%.8f", d.biasSquared(w))).append('\t')
                        .append(String.format("%.6f", d.bias(w))).append('\t')
                        .append(String.format("%.3f", d.bias(w) / d.sigma())).append('\t')
                        .append(String.format("%.8f", d.biasSquaredAdjusted(w))).append('\t')
                        .append(String.format("%.6f", d.biasAdjusted(w))).append('\t')
                        .append(String.format("%.3f", d.biasAdjusted(w) / d.sigma()));
                for (double v : d.early()[w]) tsv.append('\t').append(String.format("%.6f", v));
                tsv.append(System.lineSeparator());
            }
            Path file = dir.resolve(String.format("decay-%s-%ds%dw.tsv",
                    d.rule().name().toLowerCase(java.util.Locale.ROOT), seeds, window));
            Files.writeString(file, tsv.toString());
            System.out.printf("wrote %s%n", file);
        }
    }

    private static String counts(int[] v) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < v.length; i++) {
            if (v[i] == 0) continue;
            b.append(b.isEmpty() ? "" : " ").append(i == v.length - 1 ? "none" : String.valueOf(i))
                    .append(':').append(v[i]);
        }
        return b.toString();
    }

    private static String occupancy(double[] v) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < v.length; i++) {
            if (v[i] < 0.00005) continue;
            b.append(b.isEmpty() ? "" : " ").append(i == v.length - 1 ? "none" : String.valueOf(i))
                    .append(':').append(String.format("%.4f", v[i]));
        }
        return b.toString();
    }
}
