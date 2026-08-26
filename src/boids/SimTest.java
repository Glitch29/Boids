package boids;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.Random;

/**
 * One-off runs against {@link Sim}. Nothing here is part of the simulation; this is
 * where a specific question gets asked and its numbers or frames get written.
 */
public final class SimTest {

    /** Discarded before measuring, so every sample comes from an organised flock. */
    private static final int WARMUP = 500;
    /** Seeds run when none are given on the command line. */
    private static final long[] DEFAULT_SEEDS;
    static {
        DEFAULT_SEEDS = new long[500];
        for (int i = 0 ; i < DEFAULT_SEEDS.length; i++) {
            DEFAULT_SEEDS[i]=i;
        }
    }

    /**
     * Sweeps branch shapes and discount rates against the same warmed timelines, to see
     * how a fixed concurrency budget is best spent: wide and greedy, or narrow and deep.
     */
    /** Widths the enumerated tail may use, largest first so recursion stays non-increasing. */
    private static final int[] TAIL_VALUES = {16, 8, 4, 2, 1};
    /** Raised alongside the budget, so deeper tails can compete for it. */
    private static final int MAX_TAIL_LENGTH = 10;

    private static void buildTails(int[] prefix, int length, int startValue, List<int[]> out) {
        if (length == MAX_TAIL_LENGTH) return;
        for (int i = startValue; i < TAIL_VALUES.length; i++) {
            prefix[length] = TAIL_VALUES[i];
            out.add(java.util.Arrays.copyOf(prefix, length + 1));
            buildTails(prefix, length + 1, i, out);
        }
    }

    /** A warmed timeline with its score zeroed and no override installed. */
    private static Sim.State warmedRoot(Engine engine, long seed) {
        return warmedRoot(engine, seed, WARMUP);
    }

    /**
     * As above, but warmed for a stated number of ticks.
     * <p>
     * Length matters on maps where an unsteered flock scores while it settles: a longer
     * warm-up discards that transient, so the control rate over the measured window
     * reflects the settled orbit rather than the arrival at it.
     */
    private static Sim.State warmedRoot(Engine engine, long seed, int warmup) {
        Sim.State s = engine.init(seed);
        while (s.tick < warmup) s = engine.tick(s);
        return new Sim.State(s.n, s.x, s.y, s.h, s.tick, 0L, new long[s.n], s.label);
    }

    // ---- Dilution and budget grid ------------------------------------------

    /** Canonical ticks each run is measured over, regardless of how long a commit is. */
    private static final int GRID_TICKS = 3200;
    private static final int GRID_SEEDS = 30;

    /**
     * For every state, which landmark could be the first one it crosses.
     * <p>
     * This is what an exit window should have been. Sweeping override onsets measures the
     * override vocabulary as much as the map: the earliest onset that still exits is the
     * true commit point shifted back by however many ticks the override happens to run,
     * so halving the duration moves every window. What actually matters is a property of
     * the state graph alone â€” from here, what can still happen.
     * <p>
     * Computed as a least fixed point over turn choices. A transition that crosses a gate
     * contributes that gate; one that does not contributes whatever its destination could
     * reach. A state whose set holds both an exit and a continue landmark has not yet
     * decided; one holding only an exit is committed to exiting however it turns; one
     * holding only a continue landmark can no longer exit at all.
     *
     * @return one bitmask per state, indexed as {@code (x + y * width) * TURNS + heading}
     */
    /**
     * States from which a boid can fly forever without ever scoring.
     * <p>
     * The viability kernel again, over a map where the scoring region counts as wall. A
     * live state outside this set cannot avoid scoring however it turns â€” which is the
     * landmark-free definition of having exited. Nothing here depends on a drawn line:
     * the scoring region is part of the map, and every exit is defined by leading to it.
     */
    private static NavMap avoidScoring(PresetScenarioParameter preset) throws IOException {
        BufferedImage img = javax.imageio.ImageIO.read(preset.ingest().png().toFile());
        int w = img.getWidth(), h = img.getHeight();
        boolean[] blocked = new boolean[w * h];
        int[] score = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = img.getRGB(x, y) & 0xFFFFFF;
                blocked[x + y * w] = rgb == 0x000000 || rgb == 0xFF7F27;
            }
        }
        // Forward only: this asks what the boid can still do, not how it got here.
        return NavMapBuilder.build(blocked, score, w, h, Math.round(preset.turningRadius()),
                NavMapBuilder.Navigability.FORWARD);
    }

    /**
     * States that can still get back to a known point of route A without scoring.
     * <p>
     * Taking an exit is irreversible with respect to A: a boid that has exited can avoid
     * scoring indefinitely by way of X, but it can never rejoin an A edge without scoring
     * first. So "has exited" needs no drawn line and no direction convention â€” it is
     * exactly "can no longer reach A without scoring", and a single anchor state anywhere
     * on route A is enough to define it.
     * <p>
     * Backward breadth-first over the real transition relation, keeping only steps whose
     * segment misses the scoring region. A boid may legally fly into scoring; those steps
     * are excluded here because crossing one is the event being measured.
     */
    private static boolean[] canReachA(NavMap map, NavMap free, int ax, int ay, int ad) {
        int w = map.width(), h = map.height(), turns = Params.TURNS;
        boolean[] reach = new boolean[w * h * turns];
        int[] stack = new int[1 << 16];
        int top = 0;
        int anchor = (ax + ay * w) * turns + ad;
        reach[anchor] = true;
        stack[top++] = anchor;

        while (top > 0) {
            int s = stack[--top];
            int d = s % turns, cell = s / turns, x = cell % w, y = cell / w;
            int px = x - map.stepX(d), py = y - map.stepY(d);
            if (px < 0 || py < 0 || px >= w || py >= h || map.oob(px, py)) continue;
            if (!free.passable(px, py, d)) continue;      // that step would have scored
            for (int t = -1; t <= 1; t++) {
                int pd = Math.floorMod(d - t, turns);
                if (!map.alive(px, py, pd)) continue;
                if (map.constrainTurn(px, py, pd, t) != t) continue;
                int p = (px + py * w) * turns + pd;
                if (reach[p]) continue;
                reach[p] = true;
                if (top == stack.length) stack = grow(stack);
                stack[top++] = p;
            }
        }
        return reach;
    }

    /**
     * Decomposes a map into edges from a single gate, by the validity axiom.
     * <p>
     * A decomposition is valid when every live point lies on exactly one edge and all
     * points of an edge agree on the set of edges they can go to next. That admits many
     * decompositions â€” every point its own edge is valid, as is one edge for everything â€”
     * so the construction picks a particular one out of a single gate:
     * <ol>
     *   <li>{@code O} is the points that can return to themselves without crossing the
     *       gate: the union of cycles in the gate-cut graph. Its strongly connected
     *       components are the orbits, one edge each.</li>
     *   <li>The complement splits into components connected by forward or backward travel
     *       without leaving the complement. Those are candidate edges.</li>
     *   <li>Refinement splits any edge whose points disagree about where they can go next,
     *       repeated to a fixed point.</li>
     * </ol>
     * "Next edge" means the first <em>different</em> edge reachable, not whatever is one
     * step away â€” one-step would shatter every path edge, since its interior steps within
     * itself and only its last point steps out.
     */
    /** A decomposition's result, for analyses that want to run on top of one. */
    record Labelling(NavMap map, int[] live, int liveCount, int[] edge, int edges) {}

    /**
     * Where a second boid would have to be to stop a boid on {@code from} taking the wrong
     * way out of it.
     */
    /**
     * Two pictures of how well the clock holds, per pixel.
     * <p>
     * The first asks whether time passes at the right rate: a state's successors should read
     * one tick ahead and its predecessors one behind, so the gap between the two averages
     * should be two. More than two is a stretch, less is a squeeze.
     * <p>
     * The second asks the same of unsteered travel alone, and takes the worst heading at each
     * pixel rather than the average, because one bad step is enough to make a distance
     * measured through that pixel wrong.
     */
    public static void tickField(PresetScenarioParameter preset, boolean horizontal, int line,
                                 int lo, int hi, int dir) throws IOException {
        tickField(preset, horizontal, line, lo, hi, dir, EdgeWeights.Scheme.UNIFORM, null,
                "uniform");
    }

    /** @param tag names the written images, so one map's schemes do not overwrite each other */
    public static void tickField(PresetScenarioParameter preset, boolean horizontal, int line,
                                 int lo, int hi, int dir, EdgeWeights.Scheme scheme,
                                 double[][] chain, String tag) throws IOException {
        Labelling l = label(preset, horizontal, line, lo, hi, dir);
        EdgeMetric.Metric m = EdgeMetricStore.of(preset.ingest().outputDir("metric"),
                l.map(), l.edge(), l.live(), l.liveCount(), l.edges(), scheme, chain);
        NavMap map = l.map();
        int[] edge = l.edge();
        int w = map.width(), h = map.height(), turns = Params.TURNS;
        double[] tick = m.tick();
        double[] length = m.length();

        double[] spread = new double[w * h], slowest = new double[w * h];
        boolean[] hasSpread = new boolean[w * h], hasSlowest = new boolean[w * h];
        int[] count = new int[w * h];
        int[] preds = new int[3], succs = new int[3];
        java.util.Arrays.fill(slowest, Double.MAX_VALUE);

        for (int i = 0; i < l.liveCount(); i++) {
            int s = l.live()[i];
            int home = edge[s];
            if (home < 0) continue;
            int cell = s / turns;

            double sumP = 0, sumS = 0;
            int nP = 0, nS = 0;
            int np = map.steeredPredecessors(s, preds);
            for (int k = 0; k < np; k++) {
                int p = preds[k];
                if (edge[p] < 0) continue;
                sumP += tick[p] - (edge[p] != home ? length[edge[p]] : 0);
                nP++;
            }
            int ns = map.steeredSuccessors(s, succs);
            for (int k = 0; k < ns; k++) {
                int u = succs[k];
                if (edge[u] < 0) continue;
                sumS += tick[u] + (edge[u] != home ? length[home] : 0);
                nS++;
            }
            if (nP > 0 && nS > 0) {
                spread[cell] += sumS / nS - sumP / nP;
                count[cell]++;
                hasSpread[cell] = true;
            }

            int straight = map.successor(s, 0);
            if (straight >= 0 && edge[straight] >= 0) {
                double ahead = tick[straight] + (edge[straight] != home ? length[home] : 0);
                slowest[cell] = Math.min(slowest[cell], ahead - tick[s]);
                hasSlowest[cell] = true;
            }
        }
        for (int i = 0; i < w * h; i++) if (count[i] > 0) spread[i] /= count[i];

        System.out.printf("%n=== %s @%s: how well the clock holds, weighting %s ===%n",
                preset.name(), preset.ingest().hash(), scheme);
        System.out.printf("  %s%n", EdgeMetric.lastSolve());
        report("successors minus predecessors (want 2)", spread, hasSpread, 2);
        report("unsteered step, worst per pixel (want 1)", slowest, hasSlowest, 1);
        advance(map, l, m);

        Path a = preset.ingest().output("edges", "tick_rate_" + tag + ".png");
        Path b = preset.ingest().output("edges", "tick_unsteered_" + tag + ".png");
        NavMapRender.writeField(map, spread, hasSpread, 2, 0.25, 0xD2382C, 0x2F6FD0, 2, a);
        NavMapRender.writeField(map, slowest, hasSlowest, 1, 0.25, 0xE8C21E, 0x35A853, 2, b);
        System.out.printf("wrote %s%nwrote %s%n", a, b);
    }

    /**
     * How far the clock actually moves in one tick, over every transition there is.
     * <p>
     * The quantity the whole fit is trying to make equal to one, reported raw rather than as a
     * residual. Its <em>mean</em> is the scale of the clock: at 0.98 a hundred ticks of flying
     * read as ninety-eight, and no amount of internal consistency recovers that. Its
     * <em>spread</em> is the other half — a clock that averages one but does it by running at
     * 0.8 down one corridor and 1.2 down the next measures neither corridor.
     * <p>
     * Split by whether the boid was steering, because the two are not the same claim. Every
     * state has exactly one unsteered successor and that is the transition a boid takes when
     * nothing is pushing it, so the unsteered mean is the speed of ordinary travel; the
     * steered figures include turns the map had to veto and paths hardly anything flies.
     */
    private static void advance(NavMap map, Labelling l, EdgeMetric.Metric m) {
        double[] tick = m.tick();
        double[] length = m.length();
        int[] edge = l.edge();
        List<Double> all = new ArrayList<>(), plain = new ArrayList<>();
        int[] succs = new int[3];
        for (int i = 0; i < l.liveCount(); i++) {
            int s = l.live()[i];
            int home = edge[s];
            if (home < 0) continue;
            int straight = map.successor(s, 0);
            int ns = map.steeredSuccessors(s, succs);
            for (int k = 0; k < ns; k++) {
                int u = succs[k];
                if (edge[u] < 0) continue;
                double step = tick[u] - tick[s] + (edge[u] != home ? length[home] : 0);
                all.add(step);
                if (u == straight) plain.add(step);
            }
        }
        step("clock advance per tick, every transition", all);
        step("clock advance per tick, unsteered only", plain);
    }

    private static void step(String what, List<Double> values) {
        if (values.isEmpty()) return;
        java.util.Collections.sort(values);
        double sum = 0;
        for (double v : values) sum += v;
        double mean = sum / values.size();
        double var = 0;
        for (double v : values) var += (v - mean) * (v - mean);
        System.out.printf("%-42s %d transitions%n", what, values.size());
        double[] at = {0, 0.01, 0.25, 0.5, 0.75, 0.99, 1};
        StringBuilder line = new StringBuilder("   ");
        for (double q : at) {
            int k = (int) Math.min(values.size() - 1, Math.round(q * (values.size() - 1)));
            line.append(String.format("%s=%.3f  ", q == 0 ? "min" : q == 1 ? "max"
                    : String.format("p%02d", (int) (q * 100)), values.get(k)));
        }
        System.out.printf("%s%n   mean=%.4f  sd=%.4f%n", line,
                mean, Math.sqrt(var / Math.max(1, values.size() - 1)));
    }

    private static void report(String what, double[] value, boolean[] has, double centre) {
        java.util.List<Double> all = new ArrayList<>();
        for (int i = 0; i < value.length; i++) if (has[i]) all.add(value[i]);
        java.util.Collections.sort(all);
        if (all.isEmpty()) return;
        System.out.printf("%-42s %d pixels%n", what, all.size());
        double[] at = {0, 0.01, 0.25, 0.5, 0.75, 0.99, 1};
        StringBuilder line = new StringBuilder("   ");
        for (double q : at) {
            int k = (int) Math.min(all.size() - 1, Math.round(q * (all.size() - 1)));
            line.append(String.format("%s=%.2f  ", q == 0 ? "min" : q == 1 ? "max"
                    : String.format("p%02d", (int) (q * 100)), all.get(k)));
        }
        int off = 0;
        for (double v : all) if (Math.abs(v - centre) > 0.1) off++;
        System.out.println(line + String.format("| %d pixels off by >0.1", off));
    }

    /**
     * As-flown journeys of a fixed length, against what the clock says they should have been.
     * <p>
     * Short runs from a cold start rather than long ones from a settled flock, because a
     * settled flock is a few orbits repeated and the point is to sample the whole map. Every
     * boid contributes one journey, so the number of runs falls as the flock grows and the
     * corpus stays the same size — which keeps the comparison between flock sizes fair rather
     * than letting the crowded cases dominate on volume.
     * <p>
     * Flock size is the variable of interest. One boid is the unsteered dynamics on their own
     * and should match the clock closely; more boids means more time spent doing what the
     * flock wants rather than what the map affords, and the gap between flown and estimated is
     * exactly the error a weighting scheme would have to model.
     */
    public static void corpus(PresetScenarioParameter preset, boolean horizontal, int line,
                              int lo, int hi, int dir, int[] flockSizes, int perSize, int ticks)
            throws IOException {
        corpus(preset, horizontal, line, lo, hi, dir, flockSizes, perSize, ticks,
                EdgeWeights.Scheme.UNIFORM);
    }

    public static void corpus(PresetScenarioParameter preset, boolean horizontal, int line,
                              int lo, int hi, int dir, int[] flockSizes, int perSize, int ticks,
                              EdgeWeights.Scheme scheme) throws IOException {
        corpus(preset, horizontal, line, lo, hi, dir, flockSizes, perSize, ticks, scheme, null);
    }

    public static void corpus(PresetScenarioParameter preset, boolean horizontal, int line,
                              int lo, int hi, int dir, int[] flockSizes, int perSize, int ticks,
                              EdgeWeights.Scheme scheme, double[][] chain) throws IOException {
        Labelling l = label(preset, horizontal, line, lo, hi, dir);
        EdgeMetric.Metric m = EdgeMetricStore.of(preset.ingest().outputDir("metric"),
                l.map(), l.edge(), l.live(), l.liveCount(), l.edges(), scheme, chain);
        NavMap map = l.map();
        int[] edge = l.edge();

        System.out.printf("%n=== %s @%s: %d-tick journeys, weighting %s ===%n",
                preset.name(), preset.ingest().hash(), ticks, scheme);
        System.out.printf("  %s%n", EdgeMetric.lastSolve());
        // Two accuracies, not one. sd/mean asks whether journeys agree with each other and
        // forgives a clock that runs uniformly fast or slow; rms/100 asks whether they agree
        // with the truth and does not. The second matters wherever the map fixes the scale
        // for us — a stretch with one way in and one way out has to advance the clock by
        // exactly one a tick, so a scheme whose scale is off arrives at such a place already
        // wrong and has to absorb the difference somewhere.
        System.out.printf("%-7s %8s %9s %9s %10s %9s %9s %8s %9s%n", "boids", "journeys",
                "mean", "sd", "sd/mean", "rms-100", "rms/100", "routes", "shortest");
        List<Double> spreads = new ArrayList<>();
        Path file = preset.ingest().output("corpus", "journeys_" + ticks + ".tsv");
        StringBuilder rows = new StringBuilder("boids\tseed\tboid\tfx\tfy\tfd\ttx\tty\ttd"
                + "\tfromEdge\ttoEdge\tfromTick\ttoTick\testimate\terror\tshortestWasBest\n");

        // A route's estimate is the tick difference between the endpoints plus the lengths it
        // crosses, and the difference alone can be most of an edge either way — a boid that
        // goes right round and comes back to the edge it started on reads as almost a whole
        // edge backwards until the lap it actually flew is added back. So the search has to
        // reach at least that far, which is a bound on the estimate rather than on the sum.
        double total = 0;
        for (double len : m.length()) total += len;
        double cap = ticks + total;
        for (int boids : flockSizes) {
            int runs = Math.max(1, perSize / boids);
            Engine engine = new Boids2DEngine(withFlockSize(preset, boids));
            List<Double> errors = new ArrayList<>();
            long routeTotal = 0;
            int unusable = 0, journeys = 0, shortestBest = 0;
            for (long seed = 0; seed < runs; seed++) {
                Sim.State s = engine.init(seed);
                int[] x0 = s.x.clone(), y0 = s.y.clone(), h0 = s.h.clone();
                for (int t = 0; t < ticks; t++) s = engine.tick(s);
                for (int i = 0; i < s.n; i++) {
                    if (!map.alive(x0[i], y0[i], h0[i]) || !map.alive(s.x[i], s.y[i], s.h[i])) {
                        unusable++;
                        continue;
                    }
                    int from = map.index(x0[i], y0[i], h0[i]);
                    int to = map.index(s.x[i], s.y[i], s.h[i]);
                    double[] candidates = EdgeDistance.between(edge, m, from, to, cap);
                    if (candidates.length == 0) { unusable++; continue; }
                    routeTotal += candidates.length;
                    journeys++;
                    double best = EdgeDistance.closest(candidates, ticks);
                    // Is the best route also the shortest? Only worth trusting the shortest as
                    // a stand-in for the best if it usually is, and picking the best of a dozen
                    // candidates against the known answer flatters the estimator either way.
                    double shortest = Double.MAX_VALUE;
                    for (double v : candidates) shortest = Math.min(shortest, v);
                    boolean shortestWins = Math.abs(shortest - best) < 1e-9;
                    if (shortestWins) shortestBest++;
                    errors.add(best - ticks);
                    rows.append(boids).append('\t').append(seed).append('\t').append(i)
                            .append('\t').append(x0[i]).append('\t').append(y0[i]).append('\t').append(h0[i])
                            .append('\t').append(s.x[i]).append('\t').append(s.y[i]).append('\t').append(s.h[i])
                            .append('\t').append(edge[from]).append('\t').append(edge[to])
                            .append('\t').append(String.format("%.4f", m.tick()[from]))
                            .append('\t').append(String.format("%.4f", m.tick()[to]))
                            .append('\t').append(String.format("%.4f", best))
                            .append('\t').append(String.format("%.4f", best - ticks))
                            .append('\t').append(shortestWins).append('\n');
                }
            }
            if (unusable > 0) System.out.printf("  (%d journeys unusable)%n", unusable);
            if (errors.isEmpty()) { System.out.printf("%-7d  no usable journeys%n", boids); continue; }
            // Mean and spread of the estimate itself, not of its error against the interval.
            // A clock that runs uniformly fast or slow everywhere still measures one distance
            // against another perfectly well, so only the spread is a fault; the mean just
            // says what a tick turned out to be worth.
            double sum = 0;
            for (double e : errors) sum += e + ticks;
            double mean = sum / errors.size();
            double var = 0;
            for (double e : errors) var += (e + ticks - mean) * (e + ticks - mean);
            double sd = Math.sqrt(var / Math.max(1, errors.size() - 1));
            double square = 0;
            for (double e : errors) square += e * e;
            double rms = Math.sqrt(square / errors.size());
            System.out.printf("%-7d %8d %9.2f %9.3f %9.3f%% %9.3f %8.3f%% %8.1f %8.1f%%%n",
                    boids, journeys, mean, sd, 100 * sd / mean, rms, rms,
                    routeTotal / (double) journeys, 100.0 * shortestBest / journeys);
            spreads.add(100 * sd / mean);
        }
        double lo2 = Double.MAX_VALUE, hi2 = -Double.MAX_VALUE;
        for (double v : spreads) { lo2 = Math.min(lo2, v); hi2 = Math.max(hi2, v); }
        System.out.printf("  spread across flock sizes: %.3f%% to %.3f%% (range %.3f)%n",
                lo2, hi2, hi2 - lo2);

        Files.createDirectories(file.getParent());
        Files.writeString(file, rows.toString());
        System.out.printf("%nwrote %s (%d journeys)%n", file,
                rows.chars().filter(c -> c == '\n').count() - 1);
    }

    /**
     * The same journeys, flown by a boid with no neighbours and only a habit.
     * <p>
     * The corpus is expensive in the way that matters: it needs the simulation, and the
     * simulation needs the map, so an estimate of how far a boid travels cannot be had
     * without running the thing being estimated. This replaces the flock with a three-state
     * Markov chain over {left, straight, right} — the only thing carried forward from the
     * corpus, and map-independent by construction. If the journeys it produces have the same
     * mean and spread as the flown ones, then whatever the flock is doing to travel distance
     * is captured by "boids do not change their minds often", and nothing about any
     * particular map is needed to say how far one gets.
     * <p>
     * The chain runs on the intention, not on the executed turn: a boid that wants to keep
     * turning into a wall goes on wanting it, and the map vetoes the move each tick without
     * changing its mind. That matches how the chain was measured, and it is the only coupling
     * to the map anywhere in the walk.
     *
     * @param chain    {@code [from][to]} over left, straight, right; rows need not be
     *                 normalised. The first steering direction is drawn from its equilibrium,
     *                 since a boid dropped into an established flock is not starting fresh
     * @param journeys how many walks to fly, each from its own uniformly drawn live state
     */
    public static void synthetic(PresetScenarioParameter preset, boolean horizontal, int line,
                                 int lo, int hi, int dir, double[][] chain, int journeys,
                                 int ticks, long[] seeds, EdgeWeights.Scheme[] schemes)
            throws IOException {
        Labelling l = label(preset, horizontal, line, lo, hi, dir);
        NavMap map = l.map();
        int[] edge = l.edge();

        double[][] step = new double[3][3];
        for (int a = 0; a < 3; a++) {
            double sum = 0;
            for (int b = 0; b < 3; b++) sum += chain[a][b];
            for (int b = 0; b < 3; b++) step[a][b] = chain[a][b] / sum;
        }
        double[] start = equilibrium(step);

        System.out.printf("%n=== %s @%s: %d synthetic %d-tick journeys x %d seeds ===%n",
                preset.name(), preset.ingest().hash(), journeys, ticks, seeds.length);
        System.out.printf("  chain %s / %s / %s, equilibrium %.3f %.3f %.3f%n",
                row(step[0]), row(step[1]), row(step[2]), start[0], start[1], start[2]);

        // Flown once per seed and then measured by every clock, because the walk does not
        // depend on the weighting -- only the reading of it does. That makes the comparison
        // between schemes paired on the identical journeys, which is what makes a difference
        // of a few hundredths of a percent mean anything at all.
        int[][] from = new int[seeds.length][journeys], to = new int[seeds.length][journeys];
        long vetoed = 0, requests = 0;
        for (int s = 0; s < seeds.length; s++) {
            Random rng = new Random(seeds[s]);
            for (int j = 0; j < journeys; j++) {
                int state = l.live()[rng.nextInt(l.liveCount())];
                from[s][j] = state;
                int want = draw(rng, start) - 1;
                for (int t = 0; t < ticks; t++) {
                    int next = map.successor(state, want);
                    if (next < 0) break;
                    // How often the map got its way instead. The one coupling between the
                    // walk and the map, so worth reporting rather than assuming it is small.
                    int turned = Math.floorMod(next % Params.TURNS - state % Params.TURNS,
                            Params.TURNS);
                    requests++;
                    if ((turned == Params.TURNS - 1 ? -1 : turned) != want) vetoed++;
                    state = next;
                    want = draw(rng, step[want + 1]) - 1;
                }
                to[s][j] = state;
            }
        }
        System.out.printf("  %.1f%% of steer requests were overruled by the map%n",
                100.0 * vetoed / Math.max(1, requests));

        System.out.printf("%-13s %6s %8s %9s %9s %10s %8s %9s%n", "scheme", "seed", "journeys",
                "mean", "sd", "sd/mean", "routes", "shortest");
        double[][] spread = new double[schemes.length][seeds.length];
        for (int c = 0; c < schemes.length; c++) {
            EdgeMetric.Metric m = EdgeMetricStore.of(preset.ingest().outputDir("metric"),
                    l.map(), l.edge(), l.live(), l.liveCount(), l.edges(), schemes[c]);
            double total = 0;
            for (double len : m.length()) total += len;
            double cap = ticks + total;
            for (int s = 0; s < seeds.length; s++) {
                List<Double> estimates = new ArrayList<>();
                long routeTotal = 0;
                int unusable = 0, shortestBest = 0;
                for (int j = 0; j < journeys; j++) {
                    if (edge[from[s][j]] < 0 || edge[to[s][j]] < 0) { unusable++; continue; }
                    double[] candidates = EdgeDistance.between(edge, m, from[s][j], to[s][j], cap);
                    if (candidates.length == 0) { unusable++; continue; }
                    routeTotal += candidates.length;
                    double best = EdgeDistance.closest(candidates, ticks);
                    double shortest = Double.MAX_VALUE;
                    for (double v : candidates) shortest = Math.min(shortest, v);
                    if (Math.abs(shortest - best) < 1e-9) shortestBest++;
                    estimates.add(best);
                }
                double sum = 0;
                for (double e : estimates) sum += e;
                double mean = sum / estimates.size();
                double var = 0;
                for (double e : estimates) var += (e - mean) * (e - mean);
                double sd = Math.sqrt(var / Math.max(1, estimates.size() - 1));
                spread[c][s] = 100 * sd / mean;
                System.out.printf("%-13s %6d %8d %9.2f %9.3f %9.3f%% %8.1f %8.1f%%%s%n",
                        schemes[c], seeds[s], estimates.size(), mean, sd, spread[c][s],
                        routeTotal / (double) estimates.size(),
                        100.0 * shortestBest / estimates.size(),
                        unusable > 0 ? "  (" + unusable + " unusable)" : "");
            }
        }

        // Paired against the first scheme listed, seed by seed. Reported as a range as well as
        // a mean, because a change worth adopting has to beat its own seed-to-seed wobble.
        for (int c = 1; c < schemes.length; c++) {
            double sum = 0, lo2 = Double.MAX_VALUE, hi2 = -Double.MAX_VALUE;
            for (int s = 0; s < seeds.length; s++) {
                double d = spread[c][s] - spread[0][s];
                sum += d;
                lo2 = Math.min(lo2, d);
                hi2 = Math.max(hi2, d);
            }
            System.out.printf("  %s -> %s: sd/mean %+.4f points on average (%+.4f to %+.4f)%n",
                    schemes[0], schemes[c], sum / seeds.length, lo2, hi2);
        }
    }

    /** Where the chain settles, by iteration: general, so a non-symmetric chain works too. */
    private static double[] equilibrium(double[][] step) {
        double[] at = {1 / 3.0, 1 / 3.0, 1 / 3.0};
        for (int pass = 0; pass < 10_000; pass++) {
            double[] next = new double[3];
            for (int a = 0; a < 3; a++)
                for (int b = 0; b < 3; b++) next[b] += at[a] * step[a][b];
            double move = 0;
            for (int b = 0; b < 3; b++) move = Math.max(move, Math.abs(next[b] - at[b]));
            at = next;
            if (move < 1e-15) break;
        }
        return at;
    }

    private static int draw(Random rng, double[] weights) {
        double r = rng.nextDouble(), at = 0;
        for (int i = 0; i < weights.length; i++) {
            at += weights[i];
            if (r < at) return i;
        }
        return weights.length - 1;
    }

    private static String row(double[] p) {
        return String.format("%.0f/%.0f/%.0f", 100 * p[0], 100 * p[1], 100 * p[2]);
    }

    /**
     * Every steering decision the corpus flocks made, recorded where it changes.
     * <p>
     * The clock currently weights transitions between states. A boid is not a state
     * though — it is a state plus a habit, because what it wanted last tick is most of
     * what it will want this tick. If that is true the weights belong on
     * {@code (state, last turn)} rather than on {@code state}, and this is the
     * measurement that decides whether it is worth the sixty-fourfold... threefold
     * expansion of the transition space.
     * <p>
     * Pre-veto throughout. The turn a boid executes is its intention crossed with the
     * wall in front of it; only the intention is a property of the flock, and only the
     * intention is the thing claimed to persist.
     * <p>
     * Rows are change points rather than ticks, since a decision that repeats for
     * eleven ticks is one fact about the flock and not eleven. {@code held} carries the
     * length of the run so nothing is lost by the compression, and {@code truncated}
     * marks the runs still going when the sim ended — their true length is unknown and
     * only bounded below, so averaging them in unmodified biases every run length down.
     */
    public static void steering(PresetScenarioParameter preset, int[] flockSizes, int perSize,
                                int ticks) throws IOException {
        Path file = preset.ingest().output("corpus", "steering_" + ticks + ".tsv");
        Files.createDirectories(file.getParent());
        // Built here rather than borrowed from the engine so that what counts a boid's
        // neighbours is visibly the same class that acts on them, configured the same way.
        MovementLogic rules = new MovementLogic(preset.turningRadius());

        System.out.printf("%n=== %s @%s: %d-tick steering trace ===%n",
                preset.name(), preset.ingest().hash(), ticks);
        System.out.printf("%-6s %10s %9s %7s %7s %7s %8s %8s %8s%n", "boids", "decisions",
                "changes", "left", "straight", "right", "hold", "run", "seen");

        // Runs by direction, as a histogram rather than a list: bounded by the horizon,
        // exact, and it makes the median as cheap as the mean.
        long[][] runsBy = new long[3][ticks + 2];
        long[][][] moves = new long[flockSizes.length][3][3];

        try (BufferedWriter out = Files.newBufferedWriter(file)) {
            out.write("boids\tseed\tboid\ttick\tx\ty\td\tturn\tseen\tclose\theld\ttruncated\n");
            for (int f = 0; f < flockSizes.length; f++) {
                int boids = flockSizes[f];
                int runCount = Math.max(1, perSize / boids);
                Boids2DEngine engine = new Boids2DEngine(withFlockSize(preset, boids));

                byte[][] turn = new byte[boids][ticks];
                int[][] seen = new int[boids][ticks], close = new int[boids][ticks];
                int[][] px = new int[boids][ticks], py = new int[boids][ticks];
                int[][] ph = new int[boids][ticks];
                engine.trace((tick, i, arr, want) -> {
                    int t = (int) tick;
                    turn[i][t] = (byte) want;
                    MovementLogic.Vision v = rules.vision(arr, i);
                    seen[i][t] = v.seen();
                    close[i][t] = v.close();
                    px[i][t] = arr.x()[i];
                    py[i][t] = arr.y()[i];
                    ph[i][t] = arr.h()[i];
                });

                long[] byTurn = new long[3];
                long changes = 0, held = 0, complete = 0, seenTotal = 0;
                long[][] move = moves[f];
                for (long seed = 0; seed < runCount; seed++) {
                    Sim.State s = engine.init(seed);
                    for (int t = 0; t < ticks; t++) s = engine.tick(s);

                    for (int i = 0; i < boids; i++) {
                        for (int t = 0; t < ticks; t++) {
                            byTurn[turn[i][t] + 1]++;
                            seenTotal += seen[i][t];
                            if (t + 1 < ticks) move[turn[i][t] + 1][turn[i][t + 1] + 1]++;
                        }
                        int t = 0;
                        while (t < ticks) {
                            int run = 1;
                            while (t + run < ticks && turn[i][t + run] == turn[i][t]) run++;
                            boolean truncated = t + run >= ticks;
                            if (t > 0) changes++;
                            if (!truncated) { runsBy[turn[i][t] + 1][run]++; held += run; complete++; }
                            out.write(boids + "\t" + seed + "\t" + i + "\t" + t + "\t"
                                    + px[i][t] + "\t" + py[i][t] + "\t" + ph[i][t] + "\t"
                                    + "LSR".charAt(turn[i][t] + 1) + "\t"
                                    + seen[i][t] + "\t" + close[i][t] + "\t"
                                    + run + "\t" + truncated + "\n");
                            t += run;
                        }
                    }
                }
                engine.trace(null);
                long decisions = (long) runCount * boids * ticks;
                long steps = (long) runCount * boids * (ticks - 1);
                System.out.printf("%-6d %10d %9d %6.1f%% %7.1f%% %6.1f%% %7.1f%% %8.2f %8.2f%n",
                        boids, decisions, changes,
                        100.0 * byTurn[0] / decisions, 100.0 * byTurn[1] / decisions,
                        100.0 * byTurn[2] / decisions, 100.0 * (steps - changes) / steps,
                        complete == 0 ? Double.NaN : held / (double) complete,
                        seenTotal / (double) decisions);
            }
        }

        // The whole question in one table: given what a boid wanted last tick, what does it
        // want now? Momentum shows up as a heavy diagonal, and specifically as L->L and R->R
        // being far above the share of L and R overall — a boid that is turning keeps turning.
        System.out.printf("%n  tick-to-tick steering, %% of rows (L/S/R now, given L/S/R last)%n");
        System.out.printf("  %-6s %17s %17s %17s%n", "boids", "was left", "was straight",
                "was right");
        for (int f = 0; f < flockSizes.length; f++) {
            StringBuilder line = new StringBuilder(String.format("  %-6d", flockSizes[f]));
            for (int a = 0; a < 3; a++) {
                long total = moves[f][a][0] + moves[f][a][1] + moves[f][a][2];
                for (int b = 0; b < 3; b++) {
                    line.append(String.format(" %5.1f", total == 0 ? Double.NaN
                            : 100.0 * moves[f][a][b] / total));
                }
                line.append("  ");
            }
            System.out.println(line);
        }

        System.out.printf("%n  completed runs of one steering direction (ticks)%n");
        System.out.printf("  %-9s %9s %8s %8s %8s %8s%n", "direction", "runs", "mean",
                "median", "p90", "max");
        for (int a = 0; a < 3; a++) {
            long n = 0, sum = 0;
            int max = 0;
            for (int r = 0; r < runsBy[a].length; r++) {
                n += runsBy[a][r];
                sum += runsBy[a][r] * (long) r;
                if (runsBy[a][r] > 0) max = r;
            }
            if (n == 0) { System.out.printf("  %-9s %9d%n", "LSR".charAt(a), 0); continue; }
            System.out.printf("  %-9s %9d %8.2f %8d %8d %8d%n", "LSR".charAt(a), n,
                    sum / (double) n, quantile(runsBy[a], n, 0.5), quantile(runsBy[a], n, 0.9),
                    max);
        }
        System.out.printf("%nwrote %s%n", file);
    }

    /** The smallest run length at or below which {@code q} of the mass sits. */
    private static int quantile(long[] histogram, long n, double q) {
        long want = (long) Math.ceil(q * n), at = 0;
        for (int r = 0; r < histogram.length; r++) {
            at += histogram[r];
            if (at >= want) return r;
        }
        return histogram.length - 1;
    }

    /** Canonical paths, edge lengths and per-state ticks for a decomposed map. */
    public static void metric(PresetScenarioParameter preset, boolean horizontal, int line,
                              int lo, int hi, int dir) throws IOException {
        Labelling l = label(preset, horizontal, line, lo, hi, dir);
        EdgeMetric.Metric m = EdgeMetricStore.of(preset.ingest().outputDir("metric"),
                l.map(), l.edge(), l.live(), l.liveCount(), l.edges());
        int w = l.map().width(), turns = Params.TURNS;
        System.out.printf("%n=== %s @%s: canonical metric ===%n",
                preset.name(), preset.ingest().hash());
        System.out.printf("%-5s %7s %9s  %s%n", "edge", "length", "states", "tick range");
        for (int e = 0; e < l.edges(); e++) {
            double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
            int n = 0;
            for (int i = 0; i < l.liveCount(); i++) {
                int s = l.live()[i];
                if (l.edge()[s] != e) continue;
                n++;
                min = Math.min(min, m.tick()[s]);
                max = Math.max(max, m.tick()[s]);
            }
            System.out.printf("%-5d %7.2f %9d  %.2f to %.2f%n", e, m.length()[e], n, min, max);
        }
        System.out.printf("%nways through with slack: %d; with no route at all: %d%n", m.slack(), m.broken());
        System.out.printf("local-ordering violations: %d of %d states, worst by %.3f ticks%n", m.band(), l.liveCount(), EdgeMetric.lastWorstBand());
        System.out.printf("  spread: %s%n", EdgeMetric.lastBandSpread());
        System.out.printf("worst step along a canonical path: %.4f ticks (want 1.0000 exactly)%n", m.step());
        System.out.printf("mean squared error from one tick per transition: %.4f%n", m.cost());
        System.out.printf("solve: %s%n", EdgeMetric.lastSolve());

        // Lengths add: going once round any cycle of the edge graph must total the same
        // whichever way it is measured, or a distance carried across a boundary is a lie.
        System.out.printf("%ncycle check: %s%n", cycleCheck(l, m));
    }

    /**
     * Confirms the clock is consistent with the transitions that cross edge boundaries: for
     * every such step the shifted tick must advance by exactly one.
     */
    private static String cycleCheck(Labelling l, EdgeMetric.Metric m) {
        double worst = 0;
        int checked = 0;
        for (int i = 0; i < l.liveCount(); i++) {
            int s = l.live()[i];
            for (int t = -1; t <= 1; t++) {
                int u = l.map().successor(s, t);
                if (u < 0 || l.edge()[u] < 0 || l.edge()[u] == l.edge()[s]) continue;
                double r = m.tick()[u] + m.length()[l.edge()[s]] - m.tick()[s] - 1;
                worst = Math.max(worst, Math.abs(r));
                checked++;
            }
        }
        return String.format("%d boundary steps, worst tick error %.4f", checked, worst);
    }

    /**
     * Leader positions at a fixed distance along the followed edge, rather than at whichever
     * boundary the envelope happened to be cut on.
     */
    public static void slice(PresetScenarioParameter preset, boolean horizontal, int line,
                             int lo, int hi, int dir, int from, int keep) throws IOException {
        Labelling l = label(preset, horizontal, line, lo, hi, dir);
        EdgeMetric.Metric m = EdgeMetricStore.of(preset.ingest().outputDir("metric"),
                l.map(), l.edge(), l.live(), l.liveCount(), l.edges());
        EdgeInfluence.Envelope env = EdgeInfluence.envelope(l.map(), l.edge(), l.live(),
                l.liveCount(), from, keep);
        EdgeInfluence.Lead lead = EdgeInfluence.lead(l.map(), l.edge(), l.live(), l.liveCount(),
                from, keep, Flocking.of(preset.turningRadius()), env);

        double first = Double.MAX_VALUE, last = -Double.MAX_VALUE;
        for (int s : env.envelope()) {
            if (l.edge()[s] != from || Double.isNaN(m.tick()[s])) continue;
            first = Math.min(first, m.tick()[s]);
            last = Math.max(last, m.tick()[s]);
        }
        System.out.printf("%n=== %s: edge %d spans ticks %.1f to %.1f inside the envelope, "
                        + "edge length %.2f ===%n", preset.name(), from, first, last, m.length()[from]);
        System.out.printf("%n%-8s %-10s %-6s %s%n", "tau", "followers", "edge", "leader band along that edge");
        for (double tau = Math.ceil(first); tau <= last; tau += Math.max(1, (last - first) / 8)) {
            EdgeSlice.Slice s = EdgeSlice.at(l.map(), l.edge(), m, lead, from, tau, true, l.edges());
            if (s.followers() == 0) continue;
            boolean firstRow = true;
            for (EdgeSlice.Band b : s.bands()) {
                System.out.printf("%-8.1f %-10s %-6d %.1f to %.1f  (width %.1f ticks, %d states)%n",
                        firstRow ? tau : Double.NaN, firstRow ? String.valueOf(s.followers()) : "",
                        b.edge(), b.lo(), b.hi(), b.width(), b.count());
                firstRow = false;
            }
        }

        double mid = Math.rint((first + last) / 2);
        EdgeSlice.Slice s = EdgeSlice.at(l.map(), l.edge(), m, lead, from, mid, true, l.edges());
        Path out = preset.ingest().output("edges", "leaders_at_tick.png");
        NavMapRender.write(l.map(), s.states(), 0x101318, 0xFFFFFF, 2, out);
        System.out.printf("%nat tau=%.0f: %d followers, %d leader states drawn%n",
                mid, s.followers(), count(s.states()));
        System.out.printf("wrote %s%n", out);
    }

    /**
     * Every window on one edge: where a leader must be, tick by tick, to take a boid out the
     * wrong way.
     * <p>
     * The same measurement {@link #slice} draws a single frame of, run across the whole edge
     * instead of at eight sample points, and reported as numbers rather than as a picture.
     * A window is one leader edge's band followed through {@code tau}: it opens somewhere,
     * drifts along at roughly the rate the boid travels, widens as the leader accumulates
     * slack, and closes. Those four facts are what identifies it.
     * <p>
     * <b>The widths are upper bounds, not operating tolerances.</b> The search allows the
     * leader any legal flying, including weaving to hold station relative to the boid, which
     * a leader with anywhere else to be would never spend ticks doing. What the width bounds
     * is the drift a window can absorb before it stops being the same window, which is what a
     * cover needs; it is not how much room a leader actually has.
     *
     * @param from the edge the boid is on
     * @param keep the exit it is being led out by
     */
    public static void windows(PresetScenarioParameter preset, boolean horizontal, int line,
                               int lo, int hi, int dir, int from, int keep,
                               EdgeWeights.Scheme scheme, double[][] chain, Flocking flock)
            throws IOException {
        Labelling l = label(preset, horizontal, line, lo, hi, dir);
        EdgeMetric.Metric m = EdgeMetricStore.of(preset.ingest().outputDir("metric"),
                l.map(), l.edge(), l.live(), l.liveCount(), l.edges(), scheme, chain);
        EdgeInfluence.Envelope env = EdgeInfluence.envelope(l.map(), l.edge(), l.live(),
                l.liveCount(), from, keep);
        EdgeInfluence.Lead lead = EdgeInfluence.lead(l.map(), l.edge(), l.live(), l.liveCount(),
                from, keep, flock, env);

        double first = Double.MAX_VALUE, last = -Double.MAX_VALUE;
        for (int s : env.envelope()) {
            if (l.edge()[s] != from || Double.isNaN(m.tick()[s])) continue;
            first = Math.min(first, m.tick()[s]);
            last = Math.max(last, m.tick()[s]);
        }
        System.out.printf("%n=== %s: window %d -> %d, envelope spans tick %.1f to %.1f "
                + "of edge %d (length %.2f) ===%n", preset.name(), from, keep, first, last,
                from, m.length()[from]);

        int edges = l.edges();
        double[] firstLo = new double[edges], firstHi = new double[edges];
        double[] lastLo = new double[edges], lastHi = new double[edges];
        double[] tauOpen = new double[edges], tauShut = new double[edges];
        double[] widest = new double[edges];
        int[] seen = new int[edges];

        Path file = preset.ingest().output("windows",
                String.format("window_%d_%d.tsv", from, keep));
        StringBuilder rows = new StringBuilder("tau\tfollowers\tleaderEdge\tlo\thi\twidth\tstates\n");
        for (double tau = Math.ceil(first); tau <= last; tau += 1) {
            EdgeSlice.Slice s = EdgeSlice.at(l.map(), l.edge(), m, lead, from, tau, true, edges);
            if (s.followers() == 0) continue;
            for (EdgeSlice.Band b : s.bands()) {
                rows.append(String.format("%.0f\t%d\t%d\t%.3f\t%.3f\t%.3f\t%d%n",
                        tau, s.followers(), b.edge(), b.lo(), b.hi(), b.width(), b.count()));
                int e = b.edge();
                if (seen[e]++ == 0) { firstLo[e] = b.lo(); firstHi[e] = b.hi(); tauOpen[e] = tau; }
                lastLo[e] = b.lo();
                lastHi[e] = b.hi();
                tauShut[e] = tau;
                widest[e] = Math.max(widest[e], b.width());
            }
        }
        Files.writeString(file, rows.toString());

        System.out.printf("%-7s %8s %10s %22s %22s %8s %9s%n", "leader", "ticks", "at tau",
                "band when it opens", "band when it closes", "widest", "drift/tick");
        for (int e = 0; e < edges; e++) {
            if (seen[e] == 0) continue;
            double span = tauShut[e] - tauOpen[e];
            System.out.printf("edge %-2d %8d %5.0f..%-4.0f %10.1f to %-10.1f %10.1f to %-10.1f"
                            + " %8.1f %9s%n", e, seen[e], tauOpen[e], tauShut[e],
                    firstLo[e], firstHi[e], lastLo[e], lastHi[e], widest[e],
                    span <= 0 ? "-" : String.format("%+.2f", (lastLo[e] - firstLo[e]) / span));
        }
        System.out.printf("wrote %s%n", file);
    }

    /**
     * Builds a map's solver facts if they are missing, then measures the solver against a
     * truth only the harness knows.
     * <p>
     * Three populations, because they ask different questions. A plain flock has no psyboid at
     * all, so every boid should survive and anything narrower is a false alarm. A flock with an
     * overridden boid <b>on an unstable edge</b> is the case the solver exists for: the scene
     * carries evidence about the psyboid and the question is whether it gets named. A flock
     * whose psyboid sits on a stable edge carries no evidence about it at all — those are
     * counted separately rather than mixed in, because scoring them together makes a solver
     * look wrong for being silent about something invisible.
     *
     * @param flock the constants the windows are drawn at, if they have to be built
     */
    public static void solve(PresetScenarioParameter preset, boolean horizontal, int line,
                             int lo, int hi, int dir, EdgeWeights.Scheme scheme,
                             double[][] chain, Flocking flock, int seeds, int warm)
            throws IOException {
        SolverFacts f = SolverStore.prepare(preset,
                new SolverFacts.Gate(horizontal, line, lo, hi, dir), scheme, chain, flock);
        Boids2DEngine engine = new Boids2DEngine(preset);
        UnstableEdgeClue clue = new UnstableEdgeClue();

        List<Sim.State> plain = new ArrayList<>();
        List<Sim.State> shown = new ArrayList<>(), hidden = new ArrayList<>();
        List<Integer> shownWho = new ArrayList<>(), hiddenWho = new ArrayList<>();
        for (long seed = 0; seed < seeds; seed++) {
            Sim.State s = engine.init(seed);
            for (int t = 0; t < warm; t++) s = engine.tick(s);
            Sim.State from = s;
            for (int t = 0; t < 3000; t++) {
                s = engine.tick(s);
                if (t % 13 == 0 && owing(f, s) > 0) plain.add(s);
            }
            for (int who = 0; who < from.n; who++) {
                for (int turn : new int[]{-1, 1}) {
                    Sim.State a = withOverrides(from,
                            new PsyboidOverride((int) from.tick, 40, turn, who));
                    for (int t = 0; t < 200; t++) {
                        a = engine.tick(a);
                        if (t < 40 || t % 13 != 0 || owing(f, a) == 0) continue;
                        if (f.stable(f.edgeAt(a.x[who], a.y[who], a.h[who]))) {
                            hidden.add(a);
                            hiddenWho.add(who);
                        } else {
                            shown.add(a);
                            shownWho.add(who);
                        }
                    }
                }
            }
        }

        System.out.printf("%n=== %s @%s: solver over %d seeds ===%n", preset.name(),
                preset.ingest().hash(), seeds);
        System.out.printf("%-32s %7s %8s %8s %9s %8s %8s %8s%n", "scenes with something to "
                + "explain", "count", "answered", "toodeep", "unexplained", "kept", "alone",
                "excluded");
        score(f, clue, "no psyboid", plain, null);
        score(f, clue, "psyboid on an unstable edge", shown, shownWho);
        score(f, clue, "psyboid on a stable edge", hidden, hiddenWho);
    }

    /** How many boids are somewhere unsteered travel would not have left them. */
    private static int owing(SolverFacts f, Sim.State s) {
        int owing = 0;
        for (int i = 0; i < s.n; i++) {
            int e = f.edgeAt(s.x[i], s.y[i], s.h[i]);
            if (e >= 0 && !f.stable(e)) owing++;
        }
        return owing;
    }

    private static void score(SolverFacts f, UnstableEdgeClue clue, String label,
                              List<Sim.State> scenes, List<Integer> psyboid) {
        int deep = 0, unexplained = 0, kept = 0, alone = 0, excluded = 0, wide = 0;
        for (int k = 0; k < scenes.size(); k++) {
            Sim.State s = scenes.get(k);
            try {
                boolean[] c = clue.solve(f, s).candidate();
                int size = 0;
                for (boolean b : c) if (b) size++;
                if (size == s.n) wide++;
                if (psyboid == null) continue;
                if (c[psyboid.get(k)]) {
                    kept++;
                    if (size == 1) alone++;
                } else {
                    excluded++;
                }
            } catch (Solver.TooDeep e) {
                deep++;
            } catch (Solver.Unexplained e) {
                unexplained++;
            }
        }
        int answered = scenes.size() - deep - unexplained;
        System.out.printf("%-32s %7d %8d %8d %9d", label, scenes.size(), answered, deep,
                unexplained);
        if (psyboid == null) {
            System.out.printf("   %d of %d over-narrowed, with nothing there to find%n",
                    answered - wide, answered);
        } else {
            System.out.printf(" %8d %8d %8d%n", kept, alone, excluded);
        }
    }

    /** The same state with overrides attached, since a fresh one comes with none. */
    static Sim.State withOverrides(Sim.State s, PsyboidOverride... overrides) {
        return new Sim.State(s.n, s.x, s.y, s.h, s.tick, s.score, s.boidScore, s.label,
                overrides);
    }

    /** Sizes of the sets the leader search runs over, before running it. */
    public static void envelopeReport(PresetScenarioParameter preset, boolean horizontal,
                                      int line, int lo, int hi, int dir, int from, int keep)
            throws IOException {
        Labelling l = label(preset, horizontal, line, lo, hi, dir);
        EdgeInfluence.Envelope e = EdgeInfluence.envelope(l.map(), l.edge(), l.live(),
                l.liveCount(), from, keep);
        System.out.printf("%n=== %s: edge %d, keeping exit %d ===%n", preset.name(), from, keep);
        System.out.printf("critical %d, envelope %d, terminal %d, source %d, live %d%n",
                e.critical(), e.envelope().length, e.terminal().length, e.source().length,
                l.liveCount());
        int missing = EdgeInfluence.unreachableTerminals(l.map(), e);
        System.out.printf("terminals no source reaches: %d%s%n", missing,
                missing == 0 ? "  (every terminal is on a route from a source)" : "  <-- PHASE GAP");
        EdgeInfluence.SourceKinds k = EdgeInfluence.sourceKinds(l.map(), e);
        System.out.printf("sources %d: %d have a steered predecessor, %d an unsteered one; "
                        + "inside the envelope %d and %d%n", k.total(), k.withSteered(),
                k.withUnsteered(), k.steeredInEnvelope(), k.unsteeredInEnvelope());

        EdgeInfluence.Lead lead = EdgeInfluence.lead(l.map(), l.edge(), l.live(), l.liveCount(),
                from, keep, Flocking.of(preset.turningRadius()), e);
        System.out.printf("%d of %d sources can be led out, %d of %d terminals could have "
                        + "been led there%n", lead.sourcesLed(), e.source().length,
                lead.terminalsLed(), e.terminal().length);
        System.out.printf("leaders at a source that can finish: %d states%n",
                count(lead.atSources()));
        System.out.printf("leaders at a terminal that could have led: %d states%n",
                count(lead.atTerminals()));

        Path a = preset.ingest().output("edges", "leaders_source.png");
        Path b = preset.ingest().output("edges", "leaders_terminal.png");
        NavMapRender.write(l.map(), lead.atSources(), 0x101318, 0xFFFFFF, 2, a);
        NavMapRender.write(l.map(), lead.atTerminals(), 0x101318, 0xFFFFFF, 2, b);
        System.out.printf("wrote %s%nwrote %s%n", a, b);
    }

    private static int count(long[] bits) {
        int c = 0;
        for (long v : bits) c += Long.bitCount(v);
        return c;
    }

    public static void influence(PresetScenarioParameter preset, boolean horizontal, int line,
                                 int lo, int hi, int dir, int from, int keep) throws IOException {
        Labelling l = label(preset, horizontal, line, lo, hi, dir);
        EdgeInfluence.Result r = EdgeInfluence.analyse(l.map(), l.edge(), l.live(),
                l.liveCount(), from, keep, Flocking.of(preset.turningRadius()));

        System.out.printf("%n=== %s @%s: holding edge %d away from anything but edge %d ===%n",
                preset.name(), preset.ingest().hash(), from, keep);
        System.out.printf("%d critical states (one straight tick commits them elsewhere)%n",
                r.critical());
        System.out.printf("%d of those a turn still saves: %d by left, %d by right%n",
                r.rescuable(), r.byTurn()[0], r.byTurn()[1]);
        System.out.printf("%d states a second boid could hold to induce one; %d of those are "
                        + "navigable, which is %.1f%% of the %d navigable states on the map%n",
                r.influenceCount(), r.navigable(),
                100.0 * r.navigable() / Math.max(1, l.liveCount()), l.liveCount());

        long[] reachable = new long[r.influence().length];
        for (int y = 0; y < l.map().height(); y++) {
            for (int x = 0; x < l.map().width(); x++) {
                for (int d = 0; d < Params.TURNS; d++) {
                    int at = (x + y * l.map().width()) * Params.TURNS + d;
                    if ((r.influence()[at >>> 6] & (1L << (at & 63))) == 0) continue;
                    if (l.map().alive(x, y, d)) reachable[at >>> 6] |= 1L << (at & 63);
                }
            }
        }
        // White for a pixel that works from every heading — the opposite reading to the red
        // a navmap uses for a pixel that works from none.
        Path dir1 = preset.ingest().output("edges", "influence.png");
        NavMapRender.write(l.map(), r.influence(), 0x101318, 0xFFFFFF, 2, dir1);
        Path dir2 = preset.ingest().output("edges", "influence_navigable.png");
        NavMapRender.write(l.map(), reachable, 0x101318, 0xFFFFFF, 2, dir2);
        System.out.printf("%d pixels induce a saving turn from every heading, %d of them "
                        + "with every heading navigable%n",
                allHeadings(l.map(), r.influence()), allHeadings(l.map(), reachable));
        System.out.printf("wrote %s%nwrote %s%n", dir1, dir2);
    }

    public static void decompose(PresetScenarioParameter preset, boolean horizontal,
                                 int line, int lo, int hi, int dir) throws IOException {
        Labelling l = label(preset, horizontal, line, lo, hi, dir);
        describe(preset, horizontal, line, lo, hi, dir, l);
    }

    static Labelling labelFor(PresetScenarioParameter preset, boolean horizontal, int line,
                              int lo, int hi, int dir) throws IOException {
        return label(preset, horizontal, line, lo, hi, dir);
    }

    private static Labelling label(PresetScenarioParameter preset, boolean horizontal,
                                   int line, int lo, int hi, int dir) throws IOException {
        NavMap map = NavMapBuilder.buildFromPng(preset.mapPath(),
                Math.round(preset.turningRadius()));
        int w = map.width(), h = map.height(), turns = Params.TURNS, n = w * h * turns;

        int[] edge = new int[n];
        Arrays.fill(edge, -1);
        int[] live = new int[n];
        int liveCount = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (map.oob(x, y)) continue;
                for (int d = 0; d < turns; d++) {
                    if (map.alive(x, y, d)) live[liveCount++] = (x + y * w) * turns + d;
                }
            }
        }
        System.out.printf("%n=== %s @%s: decomposition from gate %s=%d, %s=[%d,%d], %s ===%n",
                preset.name(), preset.ingest().hash(), horizontal ? "y" : "x", line,
                horizontal ? "x" : "y", lo, hi,
                dir == 0 ? "both ways" : dir > 0 ? "increasing" : "decreasing");
        System.out.printf("%d live states%n", liveCount);

        // Successor lists, with the gate-crossing ones flagged.
        int[] succ = new int[n * 3];
        byte[] degree = new byte[n];
        boolean[] cuts = new boolean[n * 3];
        int crossings = 0;
        for (int i = 0; i < liveCount; i++) {
            int s = live[i];
            int d = s % turns, cell = s / turns, x = cell % w, y = cell / w;
            for (int t = -1; t <= 1; t++) {
                if (map.constrainTurn(x, y, d, t) != t) continue;
                int nd = Math.floorMod(d + t, turns);
                int nx = x + map.stepX(nd), ny = y + map.stepY(nd);
                if (nx < 0 || ny < 0 || nx >= w || ny >= h || map.oob(nx, ny)) continue;
                if (!map.alive(nx, ny, nd)) continue;
                // The gate is a line segment; a transition cuts it when the step crosses
                // that line while inside the segment's span. dir 0 counts either way.
                int from = horizontal ? y : x, to = horizontal ? ny : nx;
                int alongFrom = horizontal ? x : y, alongTo = horizontal ? nx : ny;
                boolean inSpan = (alongFrom >= lo && alongFrom <= hi)
                        || (alongTo >= lo && alongTo <= hi);
                boolean forward = from < line && to >= line;
                boolean backward = from > line && to <= line;
                boolean cut = inSpan && (dir > 0 ? forward : dir < 0 ? backward
                        : forward || backward);
                if (cut) crossings++;
                int k = s * 3 + degree[s];
                succ[k] = (nx + ny * w) * turns + nd;
                cuts[k] = cut;
                degree[s]++;
            }
        }
        System.out.printf("%d transitions cross the gate%n", crossings);

        // Reverse adjacency, so refinement can look backwards as well as forwards. Every
        // predecessor of a state sits at one pixel on one of three headings, so three
        // slots is always enough.
        byte[] predDegree = new byte[n];
        int[] pred = new int[n * 3];
        for (int i = 0; i < liveCount; i++) {
            int s = live[i];
            for (int j = 0; j < degree[s]; j++) {
                int u = succ[s * 3 + j];
                pred[u * 3 + predDegree[u]++] = s;
            }
        }

        int edges = orbits(live, liveCount, succ, degree, cuts, edge);
        System.out.printf("orbits (SCCs of the gate-cut graph): %d%n", edges);
        int distinct = mergePhases(map, live, liveCount, edge, edges);
        System.out.printf("after collapsing orbit phases: %d -> %d orbits%n", edges, distinct);
        edges = distinct;
        int orbitCount = edges;
        edges = complementComponents(map, live, liveCount, succ, degree, edge, edges);
        System.out.printf("plus complement components: %d edges before refinement%n", edges);
        int alongside = mergeAlongside(map, live, liveCount, edge, edges, orbitCount);
        System.out.printf("after merging edges that run alongside: %d -> %d edges%n",
                edges, alongside);
        edges = alongside;
        edges = refine(live, liveCount, succ, degree, pred, predDegree, edge, edges);
        System.out.printf("after refinement: %d edges%n", edges);
        edges = splitOrbits(map, live, liveCount, succ, degree, pred, predDegree, edge, edges);
        System.out.printf("%nafter cutting orbits: %d edges%n", edges);
        return new Labelling(map, live, liveCount, edge, edges);
    }

    /**
     * Which edges a boid can be left on indefinitely, and which of them have already scored.
     * <p>
     * The same classification {@link #describe} draws, reachable on its own so that something
     * only wanting to know whether an edge is stable does not have to render a graph to find
     * out.
     */
    static EdgeNavigation.Properties properties(PresetScenarioParameter preset, Labelling l)
            throws IOException {
        EdgeStats stats = report(preset, l.map(), l.live(), l.liveCount(), l.edge(), l.edges());
        EdgeNavigation.EdgeNav[] navs =
                EdgeNavigation.analyse(l.map(), l.live(), l.liveCount(), l.edge(), l.edges());
        int[] straightTo = new int[l.edges()];
        for (int e = 0; e < l.edges(); e++) straightTo[e] = navs[e].straight().to();
        return EdgeNavigation.classify(stats.scoring(), straightTo, stats.outTo(), l.edges());
    }

    /** Everything a decomposition reports and draws, once the labelling exists. */
    private static void describe(PresetScenarioParameter preset, boolean horizontal, int line,
                                 int lo, int hi, int dir, Labelling l) throws IOException {
        NavMap map = l.map();
        int[] live = l.live(), edge = l.edge();
        int liveCount = l.liveCount(), edges = l.edges();

        EdgeStats stats = report(preset, map, live, liveCount, edge, edges);
        EdgePairing pairing = renderEdges(preset, map, live, liveCount, edge, edges, 3);
        EdgeNavigation.EdgeNav[] navs = EdgeNavigation.analyse(map, live, liveCount, edge, edges);
        EdgeNavigation.report(navs);
        int[] straightTo = new int[edges];
        for (int e = 0; e < edges; e++) straightTo[e] = navs[e].straight().to();

        EdgeNavigation.Properties props =
                EdgeNavigation.classify(stats.scoring(), straightTo, stats.outTo(), edges);
        System.out.printf("%nstable: %s%nscoring: %s%n",
                which(props.stable()), which(props.scoring()));

        List<EdgeGraphRender.Node> graph = new ArrayList<>();
        for (int e = 0; e < edges; e++) {
            if (stats.size()[e] == 0) continue;              // merged away, nothing to draw
            EdgeNavigation.EdgeNav nav = navs[e];
            graph.add(new EdgeGraphRender.Node(e, stats.size()[e], stats.scoring()[e],
                    stats.reachesA()[e], stats.minX()[e], stats.maxX()[e],
                    stats.minY()[e], stats.maxY()[e],
                    pairing.inverse()[e], pairing.purity()[e], pairing.colourOf()[e],
                    nav.inbound(), hold(nav.left()), hold(nav.straight()), hold(nav.right()),
                    exits(nav), props.stable()[e], props.scoring()[e]));
        }
        String title = "%s @%s  gate %s=%d %s=[%d,%d] %s".formatted(preset.name(),
                preset.ingest().hash(), horizontal ? "y" : "x", line,
                horizontal ? "x" : "y", lo, hi,
                dir == 0 ? "both ways" : dir > 0 ? "increasing" : "decreasing");
        EdgeGraphRender.write(title, graph, stats.outTo(), EDGE_PALETTE,
                preset.ingest().output("edges", "graph.html").getParent());
    }

    /** Strongly connected components of the gate-cut graph; each cycle-bearing one is an edge. */
    private static int orbits(int[] live, int liveCount, int[] succ, byte[] degree,
                              boolean[] cuts, int[] edge) {
        int n = edge.length;
        int[] index = new int[n], low = new int[n], comp = new int[n];
        Arrays.fill(index, -1);
        Arrays.fill(comp, -1);
        boolean[] onStack = new boolean[n];
        int[] tarjan = new int[liveCount + 1], frame = new int[liveCount + 1];
        int[] stack = new int[liveCount + 1];
        int counter = 0, sp = 0, tp = 0, nextComp = 0;
        int[] compSize = new int[liveCount + 1];
        boolean[] compCyclic = new boolean[liveCount + 1];

        for (int i = 0; i < liveCount; i++) {
            int root = live[i];
            if (index[root] >= 0) continue;
            tarjan[tp] = root; frame[tp] = 0; tp++;
            index[root] = low[root] = counter++;
            stack[sp++] = root; onStack[root] = true;
            while (tp > 0) {
                int v = tarjan[tp - 1];
                if (frame[tp - 1] < degree[v]) {
                    int k = v * 3 + frame[tp - 1]++;
                    if (cuts[k]) continue;                 // gate-crossing edges are removed
                    int u = succ[k];
                    if (index[u] < 0) {
                        index[u] = low[u] = counter++;
                        stack[sp++] = u; onStack[u] = true;
                        tarjan[tp] = u; frame[tp] = 0; tp++;
                    } else if (onStack[u]) {
                        low[v] = Math.min(low[v], index[u]);
                    }
                } else {
                    tp--;
                    if (tp > 0) low[tarjan[tp - 1]] = Math.min(low[tarjan[tp - 1]], low[v]);
                    if (low[v] == index[v]) {
                        int size = 0;
                        boolean self = false;
                        int mark = sp;
                        int u;
                        do {
                            u = stack[--sp];
                            onStack[u] = false;
                            comp[u] = nextComp;
                            size++;
                        } while (u != v);
                        if (size == 1) {                   // a lone state is an orbit only
                            for (int j = 0; j < degree[v]; j++) {   // if it loops to itself
                                if (!cuts[v * 3 + j] && succ[v * 3 + j] == v) self = true;
                            }
                        }
                        compSize[nextComp] = size;
                        compCyclic[nextComp] = size > 1 || self;
                        nextComp++;
                    }
                }
            }
        }

        int[] remap = new int[nextComp];
        Arrays.fill(remap, -1);
        int edges = 0;
        for (int c = 0; c < nextComp; c++) if (compCyclic[c]) remap[c] = edges++;
        for (int i = 0; i < liveCount; i++) {
            int s = live[i];
            if (comp[s] >= 0 && remap[comp[s]] >= 0) edge[s] = remap[comp[s]];
        }
        return edges;
    }

    /**
     * Components of the complement, joined by travel in either direction within it — and
     * by direct adjacency in {@code (x,y,d)}.
     * <p>
     * Movement is a fixed ~4px step, so a boid cannot drift between phases: where an edge
     * connecting two orbits has tight tolerances, the four phase offsets never mix and
     * come out as four separate branches. That is an artifact of discretisation, not
     * structure, and it compounds — sixteen combinations of four branches, then
     * combinations of those, fragmenting everything upstream.
     * <p>
     * Treating neighbouring states as connected collapses the phases back together. At
     * worst it under-splits, which the refinement immediately puts right.
     */
    private static int complementComponents(NavMap map, int[] live, int liveCount, int[] succ,
                                            byte[] degree, int[] edge, int edges) {
        int[] parent = new int[edge.length];
        Arrays.fill(parent, -1);
        for (int i = 0; i < liveCount; i++) if (edge[live[i]] < 0) parent[live[i]] = live[i];
        int[] nb = new int[MAX_PHASE_NEIGHBOURS];
        for (int i = 0; i < liveCount; i++) {
            int s = live[i];
            if (edge[s] >= 0) continue;
            for (int j = 0; j < degree[s]; j++) {
                int u = succ[s * 3 + j];
                if (edge[u] < 0) union(parent, s, u);
            }
            int n = phaseNeighbours(map, s, nb);
            for (int k = 0; k < n; k++) if (edge[nb[k]] < 0) union(parent, s, nb[k]);
        }
        Map<Integer, Integer> label = new java.util.HashMap<>();
        for (int i = 0; i < liveCount; i++) {
            int s = live[i];
            if (edge[s] >= 0) continue;
            edge[s] = label.computeIfAbsent(find(parent, s), k -> label.size() + edges);
        }
        return edges + label.size();
    }

    /**
     * The states that are the same motion as {@code s}, differing only in where within a
     * step the boid happens to have been sampled.
     * <p>
     * Two boids one pixel apart on the same heading take identical turn sequences and stay
     * one pixel apart forever, so where the play area is wide enough to hold both copies
     * but too tight for a turn to cross between them, one manoeuvre appears as several. The
     * step is the reason: at ~4px a tick, a pixel and the pixel four along are the same
     * trajectory sampled a tick apart, while the three pixels between them belong to
     * trajectories that never touch it.
     * <p>
     * So the neighbourhood is one pixel and one heading either side. Following the step's
     * own sample set instead — {@link NavMap#stepPath}, the partial steps the collision test
     * uses — was tried on plait and dabeone and changed nothing either way: copies that are
     * a whole step apart are already joined by travel, so the only ones needing a kernel are
     * the ones sitting side by side.
     */
    private static int phaseNeighbours(NavMap map, int s, int[] out) {
        int w = map.width(), turns = Params.TURNS;
        int d = s % turns, cell = s / turns, x = cell % w, y = cell / w;
        int n = 0;
        for (int k = 0; k < 6; k++) {
            int nx = x + ADJ_X[k], ny = y + ADJ_Y[k], nd = Math.floorMod(d + ADJ_D[k], turns);
            if (map.alive(nx, ny, nd)) out[n++] = (nx + ny * w) * turns + nd;
        }
        return n;
    }

    private static final int[] ADJ_X = {1, -1, 0, 0, 0, 0};
    private static final int[] ADJ_Y = {0, 0, 1, -1, 0, 0};
    private static final int[] ADJ_D = {0, 0, 0, 0, 1, -1};

    /** One pixel or one heading either side. */
    private static final int MAX_PHASE_NEIGHBOURS = 6;

    /** Refinement carries each state's successor edges in a {@code long}, so 63 is the ceiling. */
    private static final int MASK_LIMIT = 63;

    /**
     * Merges complement edges that run alongside one another, whichever way they are flown.
     * <p>
     * Two edges occupying the same corridor are one piece of the map, and keeping them apart
     * makes every state upstream distinguishable by which of the two it reaches — the same
     * fragmentation {@link #mergePhases} takes out of the orbits, one level up. On plait a
     * pair of 330-state edges around the bulb refined the other 351,000 states into 63 edges
     * and then 1281; joining that one pair brings the whole map down to nine.
     * <p>
     * Alongside means within a tick's travel in {@code (x, y)} and within one heading
     * <em>modulo half a turn</em>, so a corridor flown one way and the same corridor flown
     * the other way count as the same corridor. That is the single place in the
     * decomposition where direction is deliberately ignored; everywhere else {@code d} and
     * {@code d + TURNS/2} are as far apart as two states get.
     * <p>
     * Orbits are excluded — they are labelled before this step and have already been through
     * {@link #mergePhases} with direction intact, and the two counter-rotating loops that
     * carry most of a map would otherwise be joined on the first comparison.
     * <p>
     * One arbitrary point per edge is enough to test with. The theoretically right measure is
     * the Hausdorff distance, but edges lying alongside anywhere lie alongside throughout,
     * and anything wrongly joined is separated again by refinement, which stops seeing the
     * two halves as agreeing about where they can go next.
     */
    private static int mergeAlongside(NavMap map, int[] live, int liveCount, int[] edge,
                                      int edges, int firstComplement) {
        int w = map.width(), turns = Params.TURNS, half = turns / 2;
        double reach = Params.speed(map.radius());
        double reach2 = reach * reach;

        int[] rep = new int[edges];
        Arrays.fill(rep, -1);
        for (int i = 0; i < liveCount; i++) {
            int s = live[i], e = edge[s];
            if (e >= firstComplement && rep[e] < 0) rep[e] = s;
        }

        int[] parent = new int[edges];
        for (int i = 0; i < edges; i++) parent[i] = i;
        for (int i = 0; i < liveCount; i++) {
            int s = live[i], e = edge[s];
            if (e < firstComplement) continue;
            int sd = s % turns, sc = s / turns, sx = sc % w, sy = sc / w;
            for (int a = firstComplement; a < edges; a++) {
                if (rep[a] < 0 || find(parent, a) == find(parent, e)) continue;
                int r = rep[a], rd = r % turns, rc = r / turns;
                int dx = sx - rc % w, dy = sy - rc / w;
                if (dx * dx + dy * dy > reach2) continue;
                int dd = Math.floorMod(sd - rd, half);
                if (Math.min(dd, half - dd) > 1) continue;
                union(parent, a, e);
            }
        }

        Map<Integer, Integer> label = new java.util.HashMap<>();
        for (int i = 0; i < liveCount; i++) {
            int s = live[i];
            if (edge[s] < 0) continue;
            edge[s] = label.computeIfAbsent(find(parent, edge[s]), k -> label.size());
        }
        return label.size();
    }


    /**
     * Collapses labelled components that differ only by phase, and renumbers from zero.
     * <p>
     * Run on the orbits, where the artifact does its real damage: an orbit is found by
     * strong connectivity, and phase copies of one loop are not strongly connected to each
     * other, so each copy becomes an edge of its own. Plait's tapering wedge holds two
     * minimum-radius circles a pixel apart, each flown both ways, and the four came out as
     * four edges — after which every state upstream split by which of them it could reach,
     * turning six edges into a hundred and four.
     * <p>
     * Merging across a real boundary is not much of a risk here: refinement splits any edge
     * whose points disagree about where they can go next, so an over-merge is undone on the
     * next pass. Only the phase copies, which agree by construction, survive it.
     */
    private static int mergePhases(NavMap map, int[] live, int liveCount, int[] edge, int edges) {
        int[] parent = new int[edges];
        for (int i = 0; i < edges; i++) parent[i] = i;
        int[] nb = new int[MAX_PHASE_NEIGHBOURS];
        for (int i = 0; i < liveCount; i++) {
            int s = live[i];
            if (edge[s] < 0) continue;
            int n = phaseNeighbours(map, s, nb);
            for (int k = 0; k < n; k++) {
                if (edge[nb[k]] >= 0) union(parent, edge[s], edge[nb[k]]);
            }
        }
        Map<Integer, Integer> label = new java.util.HashMap<>();
        for (int i = 0; i < liveCount; i++) {
            int s = live[i];
            if (edge[s] < 0) continue;
            edge[s] = label.computeIfAbsent(find(parent, edge[s]), k -> label.size());
        }
        return label.size();
    }

    private static int find(int[] parent, int x) {
        while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x]; }
        return x;
    }

    private static void union(int[] parent, int a, int b) {
        int ra = find(parent, a), rb = find(parent, b);
        if (ra != rb) parent[ra] = rb;
    }

    /**
     * Splits any edge whose points disagree on the edges either side of them.
     * <p>
     * The axiom is symmetric: all points of an edge must share both their successor edges
     * and their predecessor edges. Forward alone finds branches but is blind to merges,
     * since arriving from somewhere new creates no forward distinction â€” so an A loop with
     * two branches and two merges comes out as two segments instead of four.
     */
    private static int refine(int[] live, int liveCount, int[] succ, byte[] degree,
                              int[] pred, byte[] predDegree, int[] edge, int edges) {
        for (int round = 1; round <= 12; round++) {
            if (edges > MASK_LIMIT) { System.out.println("  too many edges to mask"); return edges; }
            long[] next = new long[edge.length], back = new long[edge.length];
            boolean changed = true;
            while (changed) {                       // fixed point: first different edge
                changed = false;
                for (int i = 0; i < liveCount; i++) {
                    int s = live[i];
                    long acc = 0;
                    for (int j = 0; j < degree[s]; j++) {
                        int u = succ[s * 3 + j];
                        acc |= edge[u] != edge[s] ? 1L << edge[u] : next[u];
                    }
                    if (acc != next[s]) { next[s] = acc; changed = true; }
                    long bcc = 0;
                    for (int j = 0; j < predDegree[s]; j++) {
                        int p = pred[s * 3 + j];
                        bcc |= edge[p] != edge[s] ? 1L << edge[p] : back[p];
                    }
                    if (bcc != back[s]) { back[s] = bcc; changed = true; }
                }
            }
            Map<String, Integer> groups = new java.util.HashMap<>();
            int[] fresh = new int[liveCount];
            for (int i = 0; i < liveCount; i++) {
                fresh[i] = groups.computeIfAbsent(
                        edge[live[i]] + "/" + next[live[i]] + "/" + back[live[i]],
                        k -> groups.size());
            }
            int split = groups.size();
            for (int i = 0; i < liveCount; i++) edge[live[i]] = fresh[i];
            System.out.printf("  refinement round %d: %d -> %d edges%n", round, edges, split);
            if (split == edges) return edges;
            edges = split;
        }
        return edges;
    }

    /**
     * Cuts each orbit with a gate of its own, then heals the artificial seam.
     * <p>
     * An orbit is strongly connected, so the axiom cannot split it: every point agrees
     * about where it can go next, and the loop stays one edge. Cutting it at a single
     * state breaks that symmetry and lets refinement find the real structure â€” but the cut
     * itself is arbitrary, so it leaves a false boundary. Merging the cut state with the
     * edges either side of it removes the artifact; if the merged edge then satisfies the
     * axiom, the cut was placed somewhere harmless.
     * <p>
     * The cut goes as far as possible, in graph steps, from any state adjacent to another
     * edge. A cut sitting on a real boundary would merge across it and destroy structure.
     */
    private static int splitOrbits(NavMap map, int[] live, int liveCount, int[] succ,
                                   byte[] degree, int[] pred, byte[] predDegree,
                                   int[] edge, int edges) {
        // Refinement renumbers every edge, so the orbits have to be found again each pass
        // rather than listed once. Edges that resist cutting are remembered by their
        // lowest-numbered state, which survives renumbering.
        Set<Integer> skip = new java.util.HashSet<>();
        for (int pass = 1; pass <= 8; pass++) {
            Map<Integer, List<Integer>> byEdge = new java.util.HashMap<>();
            for (int i = 0; i < liveCount; i++) {
                byEdge.computeIfAbsent(edge[live[i]], k -> new ArrayList<>()).add(live[i]);
            }
            int orbit = -1, rep = -1;
            List<Integer> states = null;
            for (var entry : byEdge.entrySet()) {
                int low = Integer.MAX_VALUE;
                for (int s : entry.getValue()) low = Math.min(low, s);
                if (skip.contains(low)) continue;
                // An orbit is any edge some point of which can forward-navigate back to
                // itself â€” a property of the edge, not of which stage produced it.
                if (!cyclic(entry.getValue(), succ, degree, edge, entry.getKey(), null)) continue;
                orbit = entry.getKey();
                states = entry.getValue();
                rep = low;
                break;
            }
            if (orbit < 0) {
                System.out.printf("  pass %d: no orbits left to cut%n", pass);
                break;
            }
            boolean[] inO = new boolean[edge.length];
            for (int s : states) inO[s] = true;

            // Distance from the orbit's own boundary, so the cut lands as far from any
            // real edge border as the loop allows.
            int[] dist = new int[edge.length];
            Arrays.fill(dist, Integer.MAX_VALUE);
            ArrayDeque<Integer> q = new ArrayDeque<>();
            // Both directions of traffic across the boundary count: a gate near where the
            // complement flows back in is as bad as one near where the orbit flows out.
            // The busiest junctions are exactly where several edges meet, and a gate there
            // cuts raggedly and shatters the refinement.
            for (int s : states) {
                boolean border = false;
                for (int j = 0; j < degree[s]; j++) if (!inO[succ[s * 3 + j]]) border = true;
                for (int j = 0; j < predDegree[s]; j++) if (!inO[pred[s * 3 + j]]) border = true;
                if (border) { dist[s] = 0; q.add(s); }
            }
            while (!q.isEmpty()) {
                int s = q.poll();
                for (int j = 0; j < degree[s]; j++) {
                    int u = succ[s * 3 + j];
                    if (inO[u] && dist[u] == Integer.MAX_VALUE) { dist[u] = dist[s] + 1; q.add(u); }
                }
                for (int j = 0; j < predDegree[s]; j++) {
                    int u = pred[s * 3 + j];
                    if (inO[u] && dist[u] == Integer.MAX_VALUE) { dist[u] = dist[s] + 1; q.add(u); }
                }
            }
            // Candidates furthest from the border first; a gate on a real boundary would
            // merge across it and destroy structure.
            List<Integer> candidates = new ArrayList<>(states);
            candidates.sort((a, b) -> Integer.compare(
                    dist[b] == Integer.MAX_VALUE ? -1 : dist[b],
                    dist[a] == Integer.MAX_VALUE ? -1 : dist[a]));

            boolean[] arrival = null;
            int chosen = -1;
            for (int attempt = 0; attempt < 8 && attempt < candidates.size(); attempt++) {
                int c = candidates.get(attempt * Math.max(1, candidates.size() / 64));
                boolean[] hits = gateArrivals(map, c, states, succ, degree, edge, orbit);
                if (hits == null) continue;
                // A gate is only a gate if every cycle in the orbit crosses it.
                if (cyclic(states, succ, degree, edge, orbit, hits)) continue;
                arrival = hits;
                chosen = c;
                break;
            }
            if (arrival == null) {
                System.out.printf("  orbit %d (%d states): no gate cut every cycle, skipping%n",
                        orbit, states.size());
                skip.add(rep);
                continue;
            }
            int d = chosen % Params.TURNS, cell = chosen / Params.TURNS;
            int arrivals = 0;
            for (int s : states) if (arrival[s]) arrivals++;
            System.out.printf("  orbit %d (%d states): gate through (%d,%d,%d), %d steps from "
                            + "its border, %d arrival states%n", orbit, states.size(),
                    cell % map.width(), cell / map.width(), d,
                    dist[chosen] == Integer.MAX_VALUE ? -1 : dist[chosen], arrivals);

            int gPrime = edges++;
            for (int s : states) if (arrival[s]) edge[s] = gPrime;
            edges = refine(live, liveCount, succ, degree, pred, predDegree, edge, edges);

            int cut = chosen;

            // Heal: merge the cut with whatever now sits either side of it inside O.
            int sample = -1;
            for (int s : states) if (arrival[s]) { sample = s; break; }
            int gs = edge[sample], before = -1, after = -1;
            for (int s : states) {
                if (edge[s] == gs) {
                    for (int j = 0; j < degree[s]; j++) {
                        int u = succ[s * 3 + j];
                        if (inO[u] && edge[u] != gs) after = edge[u];
                    }
                } else {
                    for (int j = 0; j < degree[s]; j++) {
                        if (inO[succ[s * 3 + j]] && edge[succ[s * 3 + j]] == gs) before = edge[s];
                    }
                }
            }
            System.out.printf("    merging %d + %d + %d%n", before, gs, after);
            for (int i = 0; i < liveCount; i++) {
                if (edge[live[i]] == before || edge[live[i]] == after) edge[live[i]] = gs;
            }
            // The healed edge must survive the axiom as it stands. Splitting later through
            // cascading refinement is fine; splitting immediately means the gate landed on
            // a real boundary and merged across it, destroying structure. Cut selection is
            // good enough that this is a hard error rather than a retry.
            int parts = groupsWithin(live, liveCount, succ, degree, pred, predDegree, edge, gs);
            if (parts > 1) {
                // Above the mask limit refinement returns without having run, so the heal
                // merged into a labelling that was never refined and the split says nothing
                // about where the gate landed. Distinguishing the two matters: the second
                // reading sent a whole afternoon looking for a bad gate on plait, where the
                // cause was a braid that genuinely refines into more edges than fit a long.
                throw new IllegalStateException((edges > MASK_LIMIT
                        ? "gate through (%d,%d,%d) for orbit %d left a merged edge that splits "
                          + "%d ways under the axiom — but refinement had already given up above "
                          + "%6$d edges, so this is unrefined rather than misplaced"
                        : "gate through (%d,%d,%d) for orbit %d left a merged edge that splits "
                          + "%d ways under the axiom — it sits on a real edge boundary")
                        .formatted(cell % map.width(), cell / map.width(), d, orbit, parts,
                                MASK_LIMIT));
            }
            edges = refine(live, liveCount, succ, degree, pred, predDegree, edge, edges);
            skip.add(rep);
        }
        return edges;
    }

    /** How many ways one edge would split if the axiom were applied right now. */
    private static int groupsWithin(int[] live, int liveCount, int[] succ, byte[] degree,
                                    int[] pred, byte[] predDegree, int[] edge, int target) {
        long[] next = new long[edge.length], back = new long[edge.length];
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 0; i < liveCount; i++) {
                int s = live[i];
                long acc = 0;
                for (int j = 0; j < degree[s]; j++) {
                    int u = succ[s * 3 + j];
                    acc |= edge[u] != edge[s] ? 1L << edge[u] : next[u];
                }
                if (acc != next[s]) { next[s] = acc; changed = true; }
                long bcc = 0;
                for (int j = 0; j < predDegree[s]; j++) {
                    int p = pred[s * 3 + j];
                    bcc |= edge[p] != edge[s] ? 1L << edge[p] : back[p];
                }
                if (bcc != back[s]) { back[s] = bcc; changed = true; }
            }
        }
        Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < liveCount; i++) {
            int s = live[i];
            if (edge[s] == target) seen.add(next[s] + "/" + back[s]);
        }
        return seen.size();
    }

    /**
     * Can any point of this edge forward-navigate back to itself, staying inside it?
     * Transitions arriving at {@code blocked} states are removed first, which is how a
     * candidate gate is tested: if cycles survive it, it is not a cut.
     */
    private static boolean cyclic(List<Integer> states, int[] succ, byte[] degree,
                                  int[] edge, int self, boolean[] blocked) {
        Map<Integer, Integer> colour = new java.util.HashMap<>();
        for (int s : states) colour.put(s, 0);
        ArrayDeque<int[]> stack = new ArrayDeque<>();
        for (int root : states) {
            if (colour.get(root) != 0) continue;
            stack.push(new int[]{root, 0});
            colour.put(root, 1);
            while (!stack.isEmpty()) {
                int[] top = stack.peek();
                int v = top[0];
                if (top[1] < degree[v]) {
                    int u = succ[v * 3 + top[1]++];
                    if (edge[u] != self) continue;
                    if (blocked != null && blocked[u]) continue;   // the gate cuts this
                    Integer c = colour.get(u);
                    if (c == null) continue;
                    if (c == 1) return true;                       // back edge: a cycle
                    if (c == 0) { colour.put(u, 1); stack.push(new int[]{u, 0}); }
                } else {
                    colour.put(v, 2);
                    stack.pop();
                }
            }
        }
        return false;
    }

    /**
     * The states arrived at by crossing a line drawn through {@code at}, perpendicular to
     * its heading and grown both ways until it meets wall.
     * <p>
     * A gate has to be a cut of the whole corridor, not a single state: an orbit is
     * strongly connected, so removing one state leaves it strongly connected by some other
     * path and the axiom finds nothing to split on.
     */
    private static boolean[] gateArrivals(NavMap map, int at, List<Integer> states,
                                          int[] succ, byte[] degree, int[] edge, int self) {
        int turns = Params.TURNS, w = map.width();
        int d = at % turns, cell = at / turns, cx = cell % w, cy = cell / w;
        double nx = map.stepX(d), ny = map.stepY(d);
        double len = Math.hypot(nx, ny);
        if (len == 0) return null;
        nx /= len; ny /= len;
        double px = -ny, py = nx;                     // along the gate line

        // Grow to the walls on both sides, so nothing slips past an end.
        double half = 0;
        while (half < 200) {
            int ax = (int) Math.round(cx + px * (half + 1)), ay = (int) Math.round(cy + py * (half + 1));
            int bx = (int) Math.round(cx - px * (half + 1)), by = (int) Math.round(cy - py * (half + 1));
            if (!map.traversable(ax, ay) && !map.traversable(bx, by)) break;
            half++;
        }
        final double h2 = half + 2, ox = cx + 0.5, oy = cy + 0.5, fnx = nx, fny = ny;

        boolean[] arrival = new boolean[edge.length];
        boolean any = false;
        for (int s : states) {
            int sd = s % turns, sc = s / turns, sx = sc % w, sy = sc / w;
            for (int j = 0; j < degree[s]; j++) {
                int u = succ[s * 3 + j];
                if (edge[u] != self) continue;
                int uc = u / turns, ux = uc % w, uy = uc / w;
                double f0 = (sx - ox) * fnx + (sy - oy) * fny;
                double f1 = (ux - ox) * fnx + (uy - oy) * fny;
                if (!(f0 < 0 && f1 >= 0)) continue;               // must cross forwards
                double t = f1 == f0 ? 0 : -f0 / (f1 - f0);
                double hitX = sx + (ux - sx) * t - ox, hitY = sy + (uy - sy) * t - oy;
                if (Math.abs(hitX * -fny + hitY * fnx) > h2) continue;   // past the ends
                arrival[u] = true;
                any = true;
            }
        }
        return any ? arrival : null;
    }

    /** What the decomposition found, and how it lines up with what is already known. */
    /** Pixels where every heading is in the set: stand here and facing does not matter. */
    private static int allHeadings(NavMap map, long[] marked) {
        int count = 0, w = map.width();
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < w; x++) {
                if (map.oob(x, y)) continue;
                boolean all = true;
                for (int d = 0; d < Params.TURNS && all; d++) {
                    int at = (x + y * w) * Params.TURNS + d;
                    all = (marked[at >>> 6] & (1L << (at & 63))) != 0;
                }
                if (all) count++;
            }
        }
        return count;
    }

    /** The edges a flag is set on, for the console. */
    private static String which(boolean[] flag) {
        StringBuilder b = new StringBuilder();
        for (int e = 0; e < flag.length; e++) {
            if (flag[e]) b.append(b.length() > 0 ? ", " : "").append(e);
        }
        return b.length() == 0 ? "none" : b.toString();
    }

    private static EdgeGraphRender.Hold hold(EdgeNavigation.Hold h) {
        return new EdgeGraphRender.Hold(h.to(), h.pure());
    }

    private static List<EdgeGraphRender.Exit> exits(EdgeNavigation.EdgeNav nav) {
        List<EdgeGraphRender.Exit> out = new ArrayList<>();
        for (EdgeNavigation.Exit x : nav.exits()) {
            out.add(new EdgeGraphRender.Exit(x.to(), x.minTicks(), x.maxTicks(), x.unreachable()));
        }
        return out;
    }

    /** What {@link #report} measured, kept so the graph view need not measure it again. */
    private record EdgeStats(int[] size, int[] scoring, int[] reachesA,
                             int[] minX, int[] maxX, int[] minY, int[] maxY, long[] outTo) {}

    private static EdgeStats report(PresetScenarioParameter preset, NavMap map, int[] live,
                                    int liveCount, int[] edge, int edges) throws IOException {
        int w = map.width(), turns = Params.TURNS;
        NavMap free = avoidScoring(preset);
        Engine solo = new Boids2DEngine(withFlockSize(preset, 1));
        Sim.State orbit = solo.init(0);
        for (int t = 0; t < 4000; t++) orbit = solo.tick(orbit);
        boolean[] onA = canReachA(map, free, orbit.x[0], orbit.y[0], orbit.h[0]);

        int[] size = new int[edges], scoring = new int[edges], reachesA = new int[edges];
        int[] minX = new int[edges], maxX = new int[edges], minY = new int[edges], maxY = new int[edges];
        Arrays.fill(minX, 9999); Arrays.fill(minY, 9999);
        Arrays.fill(maxX, -1); Arrays.fill(maxY, -1);
        for (int i = 0; i < liveCount; i++) {
            int s = live[i], e = edge[s];
            int cell = s / turns, x = cell % w, y = cell / w;
            size[e]++;
            if (map.score(x, y) > 0) scoring[e]++;
            if (onA[s]) reachesA[e]++;
            minX[e] = Math.min(minX[e], x); maxX[e] = Math.max(maxX[e], x);
            minY[e] = Math.min(minY[e], y); maxY[e] = Math.max(maxY[e], y);
        }
        long[] outTo = EdgeNavigation.arcs(map, live, liveCount, edge, edges);

        System.out.printf("%n%-5s %8s %8s %8s  %-22s %s%n",
                "edge", "states", "scoring", "onA", "bounds", "goes to");
        for (int e = 0; e < edges; e++) {
            StringBuilder to = new StringBuilder();
            for (int f = 0; f < edges; f++) {
                if ((outTo[e] & (1L << f)) != 0) to.append(to.length() > 0 ? "," : "").append(f);
            }
            System.out.printf("%-5d %8d %8d %8d  x %3d-%3d y %3d-%3d   %s%n", e, size[e],
                    scoring[e], reachesA[e], minX[e], maxX[e], minY[e], maxY[e], to);
        }
        return new EdgeStats(size, scoring, reachesA, minX, maxX, minY, maxY, outTo);
    }

    private static final int[] EDGE_PALETTE = {
            0xE6194B, 0x3CB44B, 0x4363D8, 0xFFE119, 0xF58231,
            0x911EB4, 0x46F0F0, 0xF032E6, 0xBCF60C, 0x008080,
    };

    /**
     * Paints the decomposition, one colour per edge and its inverse.
     * <p>
     * Every edge has an inverse — the same pixels flown the other way — and the two always
     * overlap exactly, so a separate colour for each would only ever be half-visible. Some
     * edges are their own inverse. Where two or more colours land on the same pixel the
     * choice is dithered in 2x2 blocks, {@code (x/2 + y/2) % N}, so an overlap reads as a
     * weave of its constituents rather than as whichever edge happened to be drawn last.
     */
    /** How each edge pairs with the one that undoes it, and how the pairs share colours. */
    private record EdgePairing(int[] inverse, double[] purity, int[] colourOf, int colours) {}

    private static EdgePairing renderEdges(PresetScenarioParameter preset, NavMap map, int[] live,
                                           int liveCount, int[] edge, int edges, int scale)
            throws IOException {
        int w = map.width(), hh = map.height(), turns = Params.TURNS;

        // Reversing a movement, not a heading: the state that undoes (x,y,d) sits where
        // that step lands, facing back down it. Pairing at the same pixel is wrong by
        // exactly one step. Taken by majority anyway, and trusted only when mutual.
        int[][] tally = new int[edges][edges];
        for (int i = 0; i < liveCount; i++) {
            int s = live[i];
            int d = s % turns, cell = s / turns, x = cell % w, y = cell / w;
            int back = (d + turns / 2) % turns;
            int rx = x + map.stepX(back), ry = y + map.stepY(back);
            if (rx < 0 || ry < 0 || rx >= w || ry >= hh || map.oob(rx, ry)) continue;
            if (!map.alive(rx, ry, back)) continue;
            tally[edge[s]][edge[(rx + ry * w) * turns + back]]++;
        }
        int[] inverse = new int[edges];
        double[] purity = new double[edges];
        for (int e = 0; e < edges; e++) {
            int best = -1, total = 0;
            for (int f = 0; f < edges; f++) {
                total += tally[e][f];
                if (best < 0 || tally[e][f] > tally[e][best]) best = f;
            }
            inverse[e] = total == 0 ? -1 : best;
            purity[e] = total == 0 ? 0 : (double) tally[e][best] / total;
        }

        int[] colourOf = new int[edges];
        Arrays.fill(colourOf, -1);
        int colours = 0;
        for (int e = 0; e < edges; e++) {
            if (colourOf[e] >= 0) continue;
            colourOf[e] = colours;
            int f = inverse[e];
            if (f >= 0 && f != e && inverse[f] == e) colourOf[f] = colours;   // mutual only
            colours++;
        }
        System.out.printf("%n%d edges -> %d colours (%d self-inverse)%n",
                edges, colours, countSelfInverse(inverse));
        for (int e = 0; e < edges; e++) {
            System.out.printf("  edge %-3d inverse %-4s %5.1f%% pure   colour %d%n", e,
                    inverse[e] < 0 ? "none" : String.valueOf(inverse[e]),
                    100 * purity[e], colourOf[e]);
        }

        BufferedImage img = new BufferedImage(w * scale, hh * scale, BufferedImage.TYPE_INT_RGB);
        int[] here = new int[colours];
        for (int y = 0; y < hh; y++) {
            for (int x = 0; x < w; x++) {
                int rgb;
                if (map.oob(x, y)) {
                    rgb = 0x000000;
                } else {
                    int n = 0;
                    Arrays.fill(here, 0);
                    for (int d = 0; d < turns; d++) {
                        if (!map.alive(x, y, d)) continue;
                        int c = colourOf[edge[(x + y * w) * turns + d]];
                        if (here[c] == 0) { here[c] = 1; n++; }
                    }
                    if (n == 0) {
                        rgb = 0x202020;                 // in play, but no live heading
                    } else {
                        int pick = ((x / 2) + (y / 2)) % n, seen = 0, chosen = 0;
                        for (int c = 0; c < colours; c++) {
                            if (here[c] == 0) continue;
                            if (seen++ == pick) { chosen = c; break; }
                        }
                        rgb = EDGE_PALETTE[chosen % EDGE_PALETTE.length];
                    }
                }
                for (int sy = 0; sy < scale; sy++) {
                    for (int sx = 0; sx < scale; sx++) img.setRGB(x * scale + sx, y * scale + sy, rgb);
                }
            }
        }
        Path out = preset.ingest().output("edges", "decomposition.png");
        javax.imageio.ImageIO.write(img, "png", out.toFile());
        System.out.printf("wrote %s%n", out);
        return new EdgePairing(inverse, purity, colourOf, colours);
    }

    private static int countSelfInverse(int[] inverse) {
        int n = 0;
        for (int e = 0; e < inverse.length; e++) if (inverse[e] == e) n++;
        return n;
    }

    private static int h(NavMap map) { return map.height(); }

    private static int[] grow(int[] stack) {
        int[] bigger = new int[stack.length * 2];
        System.arraycopy(stack, 0, bigger, 0, stack.length);
        return bigger;
    }


    /**
     * The edges, named by the routes that traverse them, in canonical order.
     * <p>
     * X is the exception: it splits off CE and merges into BC, no route uses it, and it
     * runs back through A's corridor the opposite way. Reachable but so unlikely that it
     * was not in the original list â€” and leaving it out was what scattered A's
     * reverse-heading states across other edges, since forward propagation had to give
     * them some label and none of the nine fitted.
     */
    private static final String[] EDGES =
            {"ABCDE", "ADE", "A", "BC", "DE", "BCDE", "BD", "CE", "ABD", "X"};

    private static String key(int region, boolean red, int d) {
        String half = red ? ((d > 16 && d < 48) ? "W" : "E") : ((d > 0 && d < 32) ? "S" : "N");
        return region + "/" + half;
    }

    /** Position and heading of every boid â€” the whole of the state the dynamics see. */
    private static String key(Sim.State s) {
        StringBuilder sb = new StringBuilder(s.n * 12);
        for (int i = 0; i < s.n; i++) {
            sb.append(s.x[i]).append(',').append(s.y[i]).append(',').append(s.h[i]).append(';');
        }
        return sb.toString();
    }

    private static void write(Path file, List<int[]> path) throws IOException {
        StringBuilder sb = new StringBuilder("tick,x,y,d\n");
        for (int[] p : path) {
            if (p[0] < 0) continue;
            sb.append(p[0]).append(',').append(p[1]).append(',')
                    .append(p[2]).append(',').append(p[3]).append('\n');
        }
        Files.writeString(file, sb.toString());
    }

    // ---- Conditional-logit psyboid estimator --------------------------------

    /** Feature order everywhere below: out-degree, in-degree, influence, headroom, own. */
    private static final int LEV_F = 5;

    /**
     * The constraints a solver is entitled to assume, stated in every case.
     * <p>
     * Shipped deliberately. The strongest inference available is to roll the flocking rules
     * forward and find the boid whose next position does not match, and that is only valid
     * if the psyboid is known to be constrained identically. Withholding it would not make
     * the task harder, only ill-posed.
     */
    private static final String PHYSICS_SECTION = """
## What every boid is constrained by

These limits apply to **every** boid, the psyboid included:

- **One step of turn per tick.** A boid may turn one step left, hold straight, or turn one
  step right - nothing else. There are 64 headings, so one step is 5.625 degrees. No boid
  can turn faster than this, ever, for any reason.
- **Constant speed.** Every boid advances the same fixed distance along its heading each
  tick, set by the scenario's turning radius.
- **The same wall veto.** Once a turn has been chosen - by the flocking rules, or by
  anything overriding them - the navigation map rejects it if it would take the boid out
  of the play area, substituting the nearest turn that survives. The veto does not know
  what proposed the turn and does not treat any boid differently.

You can see this in `code/`: `Boids2DEngine.tick` runs the flocking rules, then any
steering, then clamps the result to a single step, then hands it to
`NavMap.constrainTurn`. A boid being steered gets exactly the choice an ordinary boid
gets. The psyboid differs from its flockmates only in *which* of the three turns it
picks, never in what is available to it.

""";

    /** Common colour names for the rendered palette, indexed by boid. */
    private static final String[] COLOUR_NAMES = {
            "SALMON", "AMBER", "LEMON", "GREEN", "MINT",
            "TEAL", "SKY", "INDIGO", "ORCHID", "MAGENTA"
    };

    /** Feature names in estimator order; the first five are the rollout metrics. */
    private static final String[] FEATURE_NAMES = {
            "out-degree", "in-degree", "influence", "headroom", "own",
            "zone-dist", "in-zone", "centroid-dist", "heading-dev", "nn-dist"};

    /** Panel number, drawn light-on-dark so it reads over any part of a play area. */
    private static void label(BufferedImage img, String text) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setFont(new Font("SansSerif", Font.BOLD, 28));
        FontMetrics fm = g.getFontMetrics();
        int w = fm.stringWidth(text) + 16;
        g.setColor(new Color(0, 0, 0, 200));
        g.fillRect(0, 0, w, fm.getHeight() + 8);
        g.setColor(Color.WHITE);
        g.drawString(text, 8, fm.getAscent() + 4);
        g.dispose();
    }

    public static void main(String[] args) throws IOException {
        // Output goes inside the map's own ingest, so a route trace or an edge map can
        // never be read against a dabnt that has been edited since it was produced.
        decompose(PresetScenarioParameter.PLAIT, true, 360, 335, 350, 0);
        if (true) return;
    }

    private static ScenarioParameter withFlockSize(ScenarioParameter base, int boids) {
        return new ScenarioParameter() {
            public Path mapPath() { return base.mapPath(); }
            public float turningRadius() { return base.turningRadius(); }
            public int flockSize() { return boids; }
        };
    }

}

