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
     * Builds a map's solver facts if they are missing, then grades the solver against a truth
     * only the harness knows.
     * <p>
     * <b>Graded by {@link SolverScore}</b>, a penalty to be minimised rather than a tally of
     * correct answers. Not to be confused with a run's score, which is what the psyboid is trying
     * to maximise and has nothing to do with how well the solver reads it. A true positive is the
     * psyboid surviving; a true negative is another boid correctly ruled out. The property that
     * matters here is that abstaining — keeping every boid — is the <em>worst</em> attainable
     * score and every uninformative assignment ties with it, so the number moves only on
     * discrimination and cannot be inflated by being indiscriminately generous or indiscriminately
     * strict.
     * <p>
     * Split by whether the override actually changed the psyboid's route, which is a fact about
     * the corpus rather than an assumption about inference. A forty-tick override often ends
     * without the boid having crossed an edge it would not otherwise have crossed, and those
     * scenes hold no trace of it for anyone to find; mixing them in measures the case generator
     * rather than the solver. Both are reported, because which of them is worth working on is
     * not this method's decision.
     *
     * @param flock the constants the windows are drawn at, if they have to be built
     */
    public static void solve(PresetScenarioParameter preset, boolean horizontal, int line,
                             int lo, int hi, int dir, EdgeWeights.Scheme scheme,
                             double[][] chain, Flocking flock, int seeds, int warm)
            throws IOException {
        solve(preset, horizontal, line, lo, hi, dir, scheme, chain, flock, seeds, warm,
                new UnstableEdgeClue());
    }

    /** The same, with the windows to try named, so a candidate can be measured on its own. */
    public static void solve(PresetScenarioParameter preset, boolean horizontal, int line,
                             int lo, int hi, int dir, EdgeWeights.Scheme scheme,
                             double[][] chain, Flocking flock, int seeds, int warm,
                             UnstableEdgeClue clue)
            throws IOException {
        SolverFacts f = SolverStore.prepare(preset,
                new SolverFacts.Gate(horizontal, line, lo, hi, dir), scheme, chain, flock);
        Boids2DEngine engine = new Boids2DEngine(preset);

        List<Sim.State> plain = new ArrayList<>();
        List<Sim.State> marked = new ArrayList<>(), unmarked = new ArrayList<>();
        List<Integer> markedWho = new ArrayList<>(), unmarkedWho = new ArrayList<>();
        List<Integer> markedRun = new ArrayList<>(), unmarkedRun = new ArrayList<>();
        int runs = 0, effective = 0, inert = 0;
        for (long seed = 0; seed < seeds; seed++) {
            Sim.State s = engine.init(seed);
            for (int t = 0; t < warm; t++) s = engine.tick(s);
            // One override per onset per boid per direction, with the onsets spread along the
            // timeline. Forking repeatedly off one moment gives scenes that look independent
            // and are not: a single override resampled every few ticks is one event seen ten
            // times, and a grade averaged over those is really a grade over the events.
            Sim.State walk = s;
            for (int k = 0; k < ONSETS; k++) {
                for (int who = 0; who < walk.n; who++) {
                    for (int turn : new int[]{-1, 1}) {
                        int run = runs++;
                        Sim.State a = withOverrides(walk,
                                new PsyboidOverride((int) walk.tick, DURATION, turn, who));
                        int was = f.edgeAt(a.x[who], a.y[who], a.h[who]);
                        boolean mark = false, took = false;
                        for (int t = 0; t < HORIZON; t++) {
                            a = engine.tick(a);
                            int now = f.edgeAt(a.x[who], a.y[who], a.h[who]);
                            if (now != was) {
                                // A turn it would not have taken on its own is the only thing
                                // that leaves the position evidence this solver reads.
                                mark |= was >= 0 && f.straightTo()[was] != now;
                                was = now;
                            }
                            // Sampled from the first tick, the override still running. A scene
                            // taken only after it ends is a scene with no psyboid in it: the
                            // boid is flying the ordinary rules again and all that survives is
                            // where the override left it.
                            if (t % 13 != 0 || owing(f, a) == 0) continue;
                            took = true;
                            (mark ? marked : unmarked).add(a);
                            (mark ? markedWho : unmarkedWho).add(who);
                            (mark ? markedRun : unmarkedRun).add(run);
                        }
                        if (mark) effective++; else inert++;
                        if (!took) { /* nothing to explain anywhere in the horizon */ }
                    }
                }
                for (int t = 0; t < SPACING; t++) walk = engine.tick(walk);
            }
            for (int t = 0; t < 3000; t++) {
                s = engine.tick(s);
                if (t % 13 == 0 && owing(f, s) > 0) plain.add(s);
            }
        }

        System.out.printf("%n=== %s @%s: solver over %d seeds, windows %s ===%n", preset.name(),
                preset.ingest().hash(), seeds,
                clue.using().isEmpty() ? "none" : String.join(" + ", clue.using()));
        System.out.printf("%,d override runs: %,d changed the psyboid's route, %,d were inert%n",
                runs, effective, inert);
        System.out.printf("%-34s %7s %8s %8s %7s %7s %7s %8s %8s%n", "scenes with something to "
                + "explain", "count", "answered", "toodeep", "TP", "TN", "FN", "penalty");
        grade(f, clue, "no psyboid", plain, null, null);
        grade(f, clue, "override changed the route", marked, markedWho, markedRun);
        grade(f, clue, "override changed nothing", unmarked, unmarkedWho, unmarkedRun);
    }

    /**
     * How the synthetic override corpus is laid out.
     * <p>
     * There is no searched override corpus for any dab-like map — {@code data/searches.tsv}
     * covers blossom, daisy, plinko and hamburger and nothing else — so the psyboid runs a
     * solver is graded on have to be generated here, and how they are generated decides what
     * the grade means.
     * <p>
     * {@link #ONSETS} is the number that matters. Forking every override off a single moment
     * makes one event look like ten scenes, and a grade averaged over scenes is then a grade
     * over a handful of events with the sample size hidden. Spreading the onsets buys
     * independent events at the only price worth paying, which is simulation time.
     */
    private static final int ONSETS = 12;

    /** How long an override holds one turn. The searched corpus used 8 and 32. */
    private static final int DURATION = 40;

    /** How long after onset scenes are still taken. */
    private static final int HORIZON = 200;

    /** Ticks between one onset and the next, well beyond the horizon so runs do not overlap. */
    private static final int SPACING = 250;

    /** How many boids are somewhere unsteered travel would not have left them. */
    private static int owing(SolverFacts f, Sim.State s) {
        int owing = 0;
        for (int i = 0; i < s.n; i++) {
            int e = f.edgeAt(s.x[i], s.y[i], s.h[i]);
            if (e >= 0 && !f.stable(e)) owing++;
        }
        return owing;
    }

    private static void grade(SolverFacts f, UnstableEdgeClue clue, String label,
                              List<Sim.State> scenes, List<Integer> psyboid, List<Integer> run) {
        int deep = 0, kept = 0, ruledOut = 0, excluded = 0, wide = 0, answered = 0;
        int tp = 0, fn = 0, tn = 0, fp = 0;
        for (int k = 0; k < scenes.size(); k++) {
            Sim.State s = scenes.get(k);
            int[] odds;
            try {
                odds = clue.odds(f, s);
            } catch (Solver.TooDeep e) {
                deep++;
                continue;
            }
            answered++;
            int size = 0;
            for (int v : odds) if (v > 0) size++;
            if (size == s.n) wide++;
            if (psyboid == null) continue;
            int who = psyboid.get(k);
            boolean hit = odds[who] > 0;
            // Every boid ruled out that was not the psyboid. When the psyboid itself was ruled
            // out it is one of the excluded, and must not be counted as a correct exclusion.
            int negatives = (s.n - size) - (hit ? 0 : 1);
            if (hit) kept++; else excluded++;
            ruledOut += negatives;
            if (hit) tp++; else fn++;
            tn += negatives;
            fp += (s.n - 1) - negatives;
        }
        System.out.printf("%-34s %7d %8d %8d", label, scenes.size(), answered, deep);
        if (psyboid == null) {
            System.out.printf("   %d of %d over-narrowed, with nothing there to find%n",
                    answered - wide, answered);
        } else {
            System.out.printf(" %7d %7d %7d %8.3f %8.3f%n", kept, ruledOut, excluded,
                    SolverScore.penalty(tp, fn, tn, fp),
                    SolverScore.normalised(tp, fn, tn, fp, SolverScore.K));
        }
    }

    /**
     * The three things the solver must do when it has been given no windows.
     * <p>
     * Worth asserting rather than describing, because with no windows the answer is a pure
     * function of which edges are unstable, and nothing about leaders, drift or the clock can
     * reach it. Anything breaking these has broken the skeleton rather than the modelling.
     */
    public static void solverInvariants(PresetScenarioParameter preset, boolean horizontal,
                                        int line, int lo, int hi, int dir,
                                        EdgeWeights.Scheme scheme, double[][] chain,
                                        Flocking flock, int seeds, int warm) throws IOException {
        SolverFacts f = SolverStore.prepare(preset,
                new SolverFacts.Gate(horizontal, line, lo, hi, dir), scheme, chain, flock);
        Boids2DEngine engine = new Boids2DEngine(preset);
        UnstableEdgeClue clue = new UnstableEdgeClue();

        int[] seen = new int[4];
        int checked = 0, deep = 0, wrong = 0;
        for (long seed = 0; seed < seeds; seed++) {
            Sim.State s = engine.init(seed);
            for (int t = 0; t < warm; t++) s = engine.tick(s);
            for (int t = 0; t < 3000; t++) {
                s = engine.tick(s);
                int owing = owing(f, s);
                int[] odds;
                try {
                    odds = clue.odds(f, s);
                } catch (Solver.TooDeep e) {
                    deep++;
                    continue;
                }
                checked++;
                seen[Math.min(3, owing)]++;
                boolean ok;
                if (owing == 0) {
                    ok = uniform(odds, 1);
                } else if (owing == 1) {
                    ok = true;
                    for (int i = 0; i < s.n; i++) {
                        boolean unstable = !f.stable(f.edgeAt(s.x[i], s.y[i], s.h[i]));
                        ok &= odds[i] == (unstable ? 1 : 0);
                    }
                } else {
                    ok = uniform(odds, 0);
                }
                if (!ok && wrong++ < 5 && UnstableEdgeClue.acknowledged().isEmpty()) {
                    System.out.printf("  BROKEN at tick %d: %d owing, odds %s%n", s.tick, owing,
                            Arrays.toString(odds));
                }
            }
        }
        System.out.printf("%n=== %s: no-window invariants over %,d scenes ===%n", preset.name(),
                checked);
        System.out.printf("  %,d with nothing owing, %,d with one, %,d with two or more%n",
                seen[0], seen[1], seen[2] + seen[3]);
        System.out.printf("  %,d gave up on depth%n", deep);
        // They stop being invariants the moment a window is acknowledged, which is the whole
        // point of a window: it lets a boid other than the one owing an explanation account for
        // it. With windows in play the departures are a measure of how much they are doing.
        List<String> windows = UnstableEdgeClue.acknowledged();
        if (windows.isEmpty()) {
            System.out.printf("  %s%n", wrong == 0 ? "all three invariants hold"
                    : wrong + " SCENES BROKE AN INVARIANT");
        } else {
            System.out.printf("  %,d scenes answered differently from the no-window baseline,%n"
                            + "  which is what the %d acknowledged window(s) are for: %s%n",
                    wrong, windows.size(), String.join(", ", windows));
        }
    }

    private static boolean uniform(int[] values, int of) {
        for (int v : values) if (v != of) return false;
        return true;
    }

    /**
     * Runs a corpus past the audit and asks whether anything got out without a reason.
     * <p>
     * <b>This is the test the whole net exists to pass.</b> The critical envelope is complete by
     * construction — every boid that exits was steered onto it — so an exit the audit cannot
     * account for means one of two things, and both are findings rather than noise: the
     * envelope's leader table is missing an arrangement it should admit, or the exit was
     * produced by several boids between them in a way no single leader reproduces.
     * <p>
     * Warmed before the audit is attached. A cold start drops boids inside each other's
     * separation radius, so the first few hundred ticks are full of shoves that are real but are
     * a property of the placement rather than of how a settled flock flies.
     */
    public static void census(PresetScenarioParameter preset, boolean horizontal, int line,
                              int lo, int hi, int dir, int[][] arcs, Flocking normal,
                              Flocking widened, int seeds, int ticks, int warm)
            throws IOException {
        Labelling l = label(preset, horizontal, line, lo, hi, dir);
        long built = System.nanoTime();
        ExitAudit audit = new ExitAudit(ExitAudit.Tables.of(preset.ingest().outputDir("envelope"),
                l.map(), l.edge(), l.live(), l.liveCount(), arcs, normal, widened),
                new PsyboidOverride[0]);
        System.out.printf("%ntables built in %.0fs%s%n", (System.nanoTime() - built) / 1e9,
                widened == null ? " (no widened tables: level 3 is not being checked)" : "");

        Boids2DEngine engine = new Boids2DEngine(preset);
        for (long seed = 0; seed < seeds; seed++) {
            engine.trace(null);
            Sim.State s = engine.init(seed);
            for (int t = 0; t < warm; t++) s = engine.tick(s);
            audit.reset(s.n);
            engine.trace(audit);
            for (int t = 0; t < ticks; t++) s = engine.tick(s);
        }
        engine.trace(null);

        System.out.printf("%n=== %s @%s: exit audit over %d seeds x %,d ticks, warmed %,d ===%n",
                preset.name(), preset.ingest().hash(), seeds, ticks, warm);
        System.out.print(audit.summary());

        java.util.Map<String, Integer> causes = new java.util.TreeMap<>();
        for (ExitAudit.Exit e : audit.exits()) {
            if (e.reasons().isEmpty()) continue;
            ExitAudit.Reason r = e.reasons().get(0);
            if (r.cause() == null) continue;
            causes.merge(e.fromEdge() + "->" + e.toEdge() + " L"
                    + r.leaderPath()[r.leaderPath().length - 1] + " " + r.cause(), 1,
                    Integer::sum);
        }
        System.out.println("  leader edge and cause, by best reason:");
        causes.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue())
                .forEach(e -> System.out.printf("    %-40s %,6d%n", e.getKey(), e.getValue()));

        Path file = preset.ingest().output("audit", String.format("exits_%dseed_%dt.tsv",
                seeds, ticks));
        audit.write(file);
        System.out.printf("  wrote %s%n", file);
    }

    /**
     * A classifier window's band for one leader edge, shifted to the instant of the turn.
     * <p>
     * Rows the leader search left as good as the whole edge are dropped: those are not wide
     * constraints but missing ones, and letting them into the union widens every other row for
     * nothing. The anchor is {@code length[from]} rather than the largest tick any state of
     * {@code from} has, because that is where the solver's own rebasing puts a boid at the
     * instant it crosses.
     */
    private static double[] recorded(SolverFacts f, SolverFacts.Window w, int leaderEdge) {
        if (w == null) return new double[]{Double.NaN, Double.NaN};
        double anchor = f.length()[w.from()];
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (SolverFacts.Band b : w.bands()) {
            if (b.leaderEdge() != leaderEdge || f.vacuous(b)) continue;
            lo = Math.min(lo, b.lo() + anchor - b.tau());
            hi = Math.max(hi, b.hi() + anchor - b.tau());
        }
        return lo > hi ? new double[]{Double.NaN, Double.NaN} : new double[]{lo, hi};
    }

    /** The arcs a leader on this edge would have to account for, walking its own history back. */
    private static String backwards(SolverFacts f, int leaderEdge) {
        StringBuilder s = new StringBuilder();
        java.util.ArrayDeque<int[]> work = new java.util.ArrayDeque<>();
        boolean[] seen = new boolean[f.edges()];
        work.add(new int[]{leaderEdge, 0});
        seen[leaderEdge] = true;
        while (!work.isEmpty()) {
            int[] at = work.poll();
            if (f.stable(at[0])) {
                if (at[0] != leaderEdge) {
                    s.append(s.length() > 0 ? ", " : "").append("stops at stable ").append(at[0]);
                }
                continue;
            }
            if (at[1] >= 4) continue;
            for (int p : f.predecessors(at[0])) {
                if (f.straightTo()[p] != at[0]) {
                    s.append(s.length() > 0 ? ", " : "").append(p).append("-").append(at[0]);
                }
                if (seen[p]) continue;
                seen[p] = true;
                work.add(new int[]{p, at[1] + 1});
            }
        }
        return s.length() == 0 ? "-" : s.toString();
    }

    /**
     * Grades the solver on a real psyboid corpus, one plan at a time.
     * <p>
     * <b>Graded per plan, not per scene.</b> A plan is one psyboid doing one thing; sampling it
     * every hundred ticks produces a dozen scenes that are not a dozen independent tests of
     * anything. So the scenes within a plan are averaged first and the plans are averaged
     * second, which makes the sample size the number of plans and puts the standard error on
     * the quantity that actually varies.
     * <p>
     * Scenes are taken from the whole measured window including the ticks the override is
     * running, which is the other half of the same mistake: a scene sampled only after a plan
     * finishes contains no psyboid, just a boid that used to be one.
     * <p>
     * The grade is {@link SolverScore}, normalised so that 1.000 is a perfect answer and 0.000
     * is knowing nothing. Normalised per plan rather than summed, because a raw penalty scales
     * with how many scenes a plan yielded and plans differ in length.
     */
    public static void graded(PresetScenarioParameter preset, boolean horizontal, int line,
                              int lo, int hi, int dir, EdgeWeights.Scheme scheme,
                              double[][] chain, Flocking flock, int every, UnstableEdgeClue clue)
            throws IOException {
        SolverFacts f = SolverStore.prepare(preset,
                new SolverFacts.Gate(horizontal, line, lo, hi, dir), scheme, chain, flock);
        List<String> labels = PsyboidCorpus.labels(preset.ingest());
        Boids2DEngine engine = new Boids2DEngine(preset);

        double[] perPlan = new double[labels.size()];
        int graded = 0, scenes = 0, blind = 0, deep = 0;
        int totalTP = 0, totalFN = 0, totalTN = 0, totalFP = 0;
        for (String label : labels) {
            PsyboidBits.Replay plan = PsyboidBits.parse(label);
            Sim.State s = engine.init(plan.seed());
            for (int t = 0; t < PsyboidBits.WARM; t++) s = engine.tick(s);
            s = withOverrides(s, plan.overrides());

            long last = 0;
            for (PsyboidOverride o : plan.overrides()) {
                last = Math.max(last, o.onset() + o.duration());
            }
            int planTP = 0, planFN = 0, planTN = 0, planFP = 0;
            int taken = 0;
            while (s.tick <= last) {
                s = engine.tick(s);
                if (s.tick % every != 0) continue;
                if (owing(f, s) == 0) { blind++; continue; }
                scenes++;
                int[] odds;
                try {
                    odds = clue.odds(f, s);
                } catch (Solver.TooDeep e) {
                    deep++;
                    continue;
                }
                int kept = 0;
                for (int v : odds) if (v > 0) kept++;
                boolean hit = odds[Sim.PSYBOID] > 0;
                int negatives = (s.n - kept) - (hit ? 0 : 1);
                taken++;
                if (hit) planTP++; else planFN++;
                planTN += negatives;
                planFP += (s.n - 1) - negatives;
            }
            if (taken == 0) continue;
            totalTP += planTP; totalFN += planFN; totalTN += planTN; totalFP += planFP;
            // Normalised per plan, because a raw penalty scales with how many scenes the plan
            // yielded and plans differ. Averaging the normalised figure keeps the plan as the
            // unit of sampling, which is what the standard error below is a statement about.
            perPlan[graded++] = SolverScore.normalised(planTP, planFN, planTN, planFP,
                    SolverScore.K);
        }

        double mean = 0;
        for (int i = 0; i < graded; i++) mean += perPlan[i];
        mean /= Math.max(1, graded);
        double var = 0;
        for (int i = 0; i < graded; i++) var += (perPlan[i] - mean) * (perPlan[i] - mean);
        double se = Math.sqrt(var / Math.max(1, graded - 1)) / Math.sqrt(Math.max(1, graded));

        System.out.printf("%n=== %s @%s: solver on the psyboid corpus, windows %s ===%n",
                preset.name(), preset.ingest().hash(),
                clue.using().isEmpty() ? "none" : String.join(" + ", clue.using()));
        System.out.printf("%,d plans, %,d scenes with something to explain (%,d with nothing, "
                + "%,d too deep)%n", graded, scenes, blind, deep);
        System.out.printf("true positives %,d, false negatives %,d, true negatives %,d, "
                + "false positives %,d%n", totalTP, totalFN, totalTN, totalFP);
        System.out.printf("corpus penalty %.2f against %.2f for keeping everything%n",
                SolverScore.penalty(totalTP, totalFN, totalTN, totalFP),
                SolverScore.abstaining(totalTP + totalFN, totalTN + totalFP, SolverScore.K));
        System.out.printf("normalised score, averaged within a plan then across plans: "
                + "%.3f +- %.3f  (0.000 knows nothing, 1.000 is perfect)%n", mean, se);
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

    /** Shared with every other render, so an edge is one colour wherever it is drawn. */
    private static final int[] EDGE_PALETTE = SceneRender.EDGE_PALETTE;

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

    /**
     * Critical-envelope analysis for one arc, reported and written to the ingest.
     * <p>
     * Run it for each steered transition the two-boid census found. Passing a widened
     * {@code flock} — half straight bias — produces the level-3 fallback table rather than the
     * normal-physics one, and writes it alongside under its own name.
     */
    public static void envelope(PresetScenarioParameter preset, boolean horizontal, int line,
                                int lo, int hi, int dir, int from, int keep, Flocking flock)
            throws IOException {
        envelope(preset, label(preset, horizontal, line, lo, hi, dir), from, keep, flock);
    }

    /** The same against a labelling already in hand, so a sweep of arcs decomposes once. */
    public static void envelope(PresetScenarioParameter preset, Labelling l, int from, int keep,
                                Flocking flock) throws IOException {
        long start = System.nanoTime();
        CriticalEnvelope.Table t = CriticalEnvelope.analyse(l.map(), l.edge(), l.live(),
                l.liveCount(), from, keep, flock);
        double secs = (System.nanoTime() - start) / 1e9;

        boolean widened = flock.straightBias() != Params.STRAIGHT_BIAS;
        CriticalEnvelope.Envelope env = t.envelope();
        System.out.printf("%n=== %s @%s: critical envelope %d -> %d%s ===%n", preset.name(),
                preset.ingest().hash(), from, keep, widened ? ", half straight bias" : "");
        System.out.printf("envelope %,d states (%,d on edge %d, %,d one tick onto edge %d), "
                        + "%,d at the front%n", env.size(), env.onFrom().length, from,
                env.onKeep().length, keep, env.entered().length);
        System.out.printf("settled %,d states of edge %d%n", t.settled(), from);

        int sep = 0, ali = 0;
        java.util.Set<Integer> priors = new java.util.HashSet<>();
        java.util.Map<String, Integer> paths = new java.util.TreeMap<>();
        for (CriticalEnvelope.Entry e : t.entries()) {
            if (e.cause() == CriticalEnvelope.Cause.SEPARATION) sep++; else ali++;
            priors.add(e.boidPrior());
            paths.merge(Arrays.toString(e.leaderPath()).replace(" ", ""), 1, Integer::sum);
        }
        System.out.printf("%,d admitted pairs over %,d distinct entry states: %,d SEPARATION, "
                + "%,d ALIGNMENT_AND_COHESION%n", t.entries().size(), priors.size(), sep, ali);
        System.out.printf("%,d pairs probed in %.1fs%s%n", t.probed(), secs,
                t.budgetHit() ? "  ** BUDGET HIT, table is incomplete **" : "");
        System.out.println("leader edge paths:");
        paths.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .forEach(e -> System.out.printf("  %-16s %,7d%n", e.getKey(), e.getValue()));

        Path file = preset.ingest().output("envelope", String.format("arc_%d_%d%s.tsv",
                from, keep, widened ? "_halfbias" : ""));
        CriticalEnvelope.write(file, t);
        System.out.printf("wrote %s%n", file);
    }

    /**
     * How far a boid's history can be walked backwards inside one edge, and by what.
     * <p>
     * The question this answers is what actually bounds the admission search in
     * {@link CriticalEnvelope}. While a leader is out of flocking range it steers nothing, so
     * the boid's only predecessors are its unsteered ones — and if those chains are short, the
     * out-of-range part of the search is shallow whatever the leader does. Reported separately
     * from the chains available once a leader is in range, which admit steered predecessors too.
     */
    public static void chains(PresetScenarioParameter preset, boolean horizontal, int line,
                              int lo, int hi, int dir, int[] edges) throws IOException {
        Labelling l = label(preset, horizontal, line, lo, hi, dir);
        NavMap map = l.map();
        int[] edge = l.edge();
        int[] preds = new int[3];

        for (int from : edges) {
            boolean[] settled = CriticalEnvelope.settled(map, edge, l.live(), l.liveCount(), from);
            List<Integer> on = new ArrayList<>();
            for (int i = 0; i < l.liveCount(); i++) if (edge[l.live()[i]] == from) on.add(l.live()[i]);

            // Longest backward unsteered chain that stays on the edge, by memoised depth-first
            // search. A cycle would make the answer infinite, so it is detected and reported
            // rather than assumed away.
            Map<Integer, Integer> depth = new java.util.HashMap<>();
            java.util.Set<Integer> onStack = new java.util.HashSet<>();
            boolean[] cyclic = {false};
            int settledCount = 0, reachesSettled = 0, terminates = 0;
            long total = 0;
            int worst = 0;
            int[] histogram = new int[9];
            for (int s : on) {
                if (settled[s]) { settledCount++; continue; }
                int d = unsteeredDepth(map, edge, from, s, depth, onStack, cyclic, preds);
                worst = Math.max(worst, d);
                total += d;
                histogram[Math.min(histogram.length - 1, d)]++;
                if (d == 0) terminates++;
                // Confirms the theory: settled is forward-closed under coasting, so its
                // complement is backward-closed and no unsteered chain can walk into it.
                int at = s;
                for (int guard = 0; guard < 4096; guard++) {
                    int n = map.unsteeredPredecessors(at, preds);
                    int next = -1;
                    for (int k = 0; k < n; k++) if (edge[preds[k]] == from) next = preds[k];
                    if (next < 0) break;
                    if (settled[next]) { reachesSettled++; break; }
                    at = next;
                }
            }
            int unsettled = on.size() - settledCount;

            // What the search actually has to cope with once a leader is in range: every
            // predecessor, not only the unsteered one.
            boolean[] seen = new boolean[edge.length];
            java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
            for (int s : on) if (!settled[s] && !seen[s]) { seen[s] = true; q.add(s); }
            int reach = 0;
            while (!q.isEmpty()) {
                int s = q.poll();
                reach++;
                int n = map.steeredPredecessors(s, preds);
                for (int k = 0; k < n; k++) {
                    if (edge[preds[k]] == from && !seen[preds[k]]) {
                        seen[preds[k]] = true;
                        q.add(preds[k]);
                    }
                }
            }

            System.out.printf("%n=== edge %d: %,d states, %,d settled, %,d not ===%n",
                    from, on.size(), settledCount, unsettled);
            System.out.printf("unsteered backward chains from unsettled states%s:%n",
                    cyclic[0] ? "  ** A CYCLE EXISTS, depths are lower bounds **" : "");
            System.out.printf("  longest %d, mean %.2f, %,d terminate immediately%n",
                    worst, unsettled == 0 ? 0 : (double) total / unsettled, terminates);
            System.out.print("  depth histogram 0..7,8+: ");
            for (int v : histogram) System.out.printf("%,d ", v);
            System.out.printf("%n  reached a settled state: %,d of %,d%n", reachesSettled,
                    unsettled);
            System.out.printf("backward closure over ALL predecessors: %,d states of %,d%n",
                    reach, on.size());
            System.out.printf("  worst-case pair space against %,d live leaders: %,d%n",
                    l.liveCount(), (long) reach * l.liveCount());
        }
    }

    private static int unsteeredDepth(NavMap map, int[] edge, int from, int s,
                                      Map<Integer, Integer> depth,
                                      java.util.Set<Integer> onStack, boolean[] cyclic,
                                      int[] scratch) {
        Integer have = depth.get(s);
        if (have != null) return have;
        if (!onStack.add(s)) { cyclic[0] = true; return 0; }
        int[] preds = new int[3];
        int n = map.unsteeredPredecessors(s, preds);
        int best = 0;
        for (int k = 0; k < n; k++) {
            if (edge[preds[k]] != from) continue;
            best = Math.max(best, 1 + unsteeredDepth(map, edge, from, preds[k], depth, onStack,
                    cyclic, scratch));
        }
        onStack.remove(s);
        depth.put(s, best);
        return best;
    }

    /**
     * The same test against the psyboid corpus rather than plain seeds.
     * <p>
     * <b>This is the version with enough exits in it to mean anything.</b> Left alone a settled
     * flock almost never leaves the main loop — by design, since that is what makes an exit
     * evidence — so a plain run yields a handful of events over hundreds of thousands of ticks.
     * A psyboid is the thing that makes exits happen, and a corpus of searched plans is the only
     * honest supply of them: the alternative is inventing overrides inside the harness, which
     * measures the harness.
     * <p>
     * Each plan brings its own overrides, and they are installed on the audit as well as on the
     * run, because an exit taken by the boid under an override is accounted for at
     * {@link ExitAudit.Level#PSYBOID} and nothing else has to explain it.
     */
    public static void auditCorpus(PresetScenarioParameter preset, boolean horizontal, int line,
                                   int lo, int hi, int dir, int[][] arcs, Flocking normal,
                                   Flocking widened) throws IOException {
        Labelling l = label(preset, horizontal, line, lo, hi, dir);
        long built = System.nanoTime();
        ExitAudit audit = new ExitAudit(ExitAudit.Tables.of(preset.ingest().outputDir("envelope"),
                l.map(), l.edge(), l.live(), l.liveCount(), arcs, normal, widened),
                new PsyboidOverride[0]);
        System.out.printf("%ntables built in %.0fs%s%n", (System.nanoTime() - built) / 1e9,
                widened == null ? " (no widened tables: level 3 is not being checked)" : "");

        List<String> labels = PsyboidCorpus.labels(preset.ingest());
        Boids2DEngine engine = new Boids2DEngine(preset);
        long ticks = 0;
        for (String label : labels) {
            PsyboidBits.Replay plan = PsyboidBits.parse(label);
            engine.trace(null);
            Sim.State s = engine.init(plan.seed());
            for (int t = 0; t < PsyboidBits.WARM; t++) s = engine.tick(s);
            s = withOverrides(s, plan.overrides());
            long last = 0;
            for (PsyboidOverride o : plan.overrides()) {
                last = Math.max(last, (long) o.onset() + o.duration());
            }
            audit.reset(s.n, plan.overrides());
            engine.trace(audit);
            while (s.tick <= last) { s = engine.tick(s); ticks++; }
        }
        engine.trace(null);

        System.out.printf("%n=== %s @%s: exit audit over %d psyboid plans, %,d ticks ===%n",
                preset.name(), preset.ingest().hash(), labels.size(), ticks);
        System.out.print(audit.summary());

        java.util.Map<String, Integer> causes = new java.util.TreeMap<>();
        int psyboidExits = 0, alsoLed = 0;
        for (ExitAudit.Exit e : audit.exits()) {
            if (e.reasons().isEmpty()) continue;
            ExitAudit.Reason r = e.reasons().get(0);
            if (r.level() == ExitAudit.Level.PSYBOID) {
                psyboidExits++;
                // A psyboid that could have been led anyway took an exit it did not need an
                // override for, which is a different kind of event and worth counting apart.
                if (e.reasons().size() > 1) alsoLed++;
                continue;
            }
            if (r.cause() == null) continue;
            causes.merge(e.fromEdge() + "->" + e.toEdge() + " L"
                    + r.leaderPath()[r.leaderPath().length - 1] + " " + r.cause(), 1,
                    Integer::sum);
        }
        System.out.printf("  %,d exits under an override, of which %,d had a leader as well%n",
                psyboidExits, alsoLed);
        System.out.println("  leader edge and cause, where a leader was the best reason:");
        causes.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue())
                .forEach(e -> System.out.printf("    %-40s %,6d%n", e.getKey(), e.getValue()));

        Path file = preset.ingest().output("audit", "exits_corpus.tsv");
        audit.write(file);
        System.out.printf("  wrote %s%n", file);
    }

    /**
     * Draws every exit nothing accounts for, and prints the influence that produced it.
     * <p>
     * A count of unexplained exits says only that the account is incomplete. The picture and the
     * decomposition together say <em>what</em> it is missing, and the specific thing worth
     * looking for is <b>superposition</b>: each of the three rules is normalised to unit length
     * before weighting, so one neighbour inside the separation radius contributes the whole of
     * {@code W_SEP} while every neighbour out in the annulus shares one {@code W_COH} and one
     * {@code W_ALI} between them. One close boid and several far ones can therefore carry a turn
     * that no single-neighbour analysis admits, and no leader table built pairwise can contain
     * it.
     * <p>
     * Drawn at the <b>envelope entry</b> rather than at the crossing, because that is the
     * arrangement the boid was actually steered by.
     */
    public static void renderUnexplained(PresetScenarioParameter preset, boolean horizontal,
                                         int line, int lo, int hi, int dir, int[][] arcs,
                                         Flocking normal, Flocking widened, Path outDir)
            throws IOException {
        Labelling l = label(preset, horizontal, line, lo, hi, dir);
        ExitAudit audit = new ExitAudit(ExitAudit.Tables.of(preset.ingest().outputDir("envelope"),
                l.map(), l.edge(), l.live(), l.liveCount(), arcs, normal, widened),
                new PsyboidOverride[0]);

        List<String> labels = PsyboidCorpus.labels(preset.ingest());
        Boids2DEngine engine = new Boids2DEngine(preset);
        for (String label : labels) {
            PsyboidBits.Replay plan = PsyboidBits.parse(label);
            engine.trace(null);
            Sim.State s = engine.init(plan.seed());
            for (int t = 0; t < PsyboidBits.WARM; t++) s = engine.tick(s);
            s = withOverrides(s, plan.overrides());
            long last = 0;
            for (PsyboidOverride o : plan.overrides()) {
                last = Math.max(last, (long) o.onset() + o.duration());
            }
            audit.reset(s.n, plan.overrides());
            engine.trace(audit);
            while (s.tick <= last) s = engine.tick(s);
        }
        engine.trace(null);

        MovementLogic rules = new MovementLogic(preset.turningRadius());
        double rSep = Params.separation(preset.turningRadius());
        int turns = Params.TURNS, w = l.map().width();
        int drawn = 0;
        for (ExitAudit.Exit e : audit.exits()) {
            if (!e.unexplained() || e.entryStates().length == 0) continue;
            int n = e.entryStates().length;
            int[] x = new int[n], y = new int[n], h = new int[n];
            for (int j = 0; j < n; j++) {
                int st = e.entryStates()[j];
                h[j] = st % turns;
                x[j] = (st / turns) % w;
                y[j] = (st / turns) / w;
            }
            Path png = outDir.resolve(String.format("unexplained_%02d_t%d_b%d.png",
                    ++drawn, e.tick(), e.suspect()));
            ExitRender.write(preset.ingest().display(), png, e, x, y, h, n, rules,
                    preset.turningRadius(), 3);

            int i = e.suspect();
            MovementLogic.Influence in = rules.decompose(
                    new BoidArray(n, x, y, h, e.entryTick()), i);
            double sep = MovementLogic.Influence.across(in.sepX(), in.sepY(), h[i]);
            double coh = MovementLogic.Influence.across(in.cohX(), in.cohY(), h[i]);
            double ali = MovementLogic.Influence.across(in.aliX(), in.aliY(), h[i]);

            System.out.printf("%n--- %s ---%n", png.getFileName());
            System.out.printf("exit %d->%d at tick %d, steered onto the envelope at tick %d%n",
                    e.fromEdge(), e.toEdge(), e.tick(), e.entryTick());
            System.out.printf("suspect %d at (%d,%d,%d); %d seen, %d inside separation; "
                    + "flock asked for %+d%n", i, x[i], y[i], h[i], in.seen(), in.close(),
                    in.turn());
            System.out.printf("  across-heading, positive right:  separation %+8.2f   "
                            + "cohesion %+7.2f   alignment %+7.2f   total %+8.2f%n",
                    sep, coh, ali, sep + coh + ali);
            System.out.printf("  straight bias %.4f; a turn needs the total to beat it%n",
                    Params.STRAIGHT_BIAS);
            boolean known = audit.isEntryState(e.fromEdge(), e.toEdge(), e.entryPrior());
            System.out.printf("  suspect's entry state is %s the tables%n",
                    known ? "IN" : "ABSENT FROM");
            System.out.printf("  %-5s %-16s %8s %10s %8s %10s%n", "boid", "state", "distance",
                    "regime", "alone", "in table");
            for (int j = 0; j < n; j++) {
                if (j == i) continue;
                double d = Math.hypot(x[j] - (double) x[i], y[j] - (double) y[i]);
                double vis = rules.perceived(x[i], y[i], h[i], x[j], y[j]);
                String regime = vis < 0 ? "unseen" : d < rSep ? "SEPARATION" : "annulus";
                int alone = EdgeInfluence.steer(h[i], x[j] - x[i], y[j] - y[i], h[j], normal);
                boolean pair = audit.lookup(e.fromEdge(), e.toEdge(), e.entryPrior(),
                        e.entryStates()[j]) != null;
                System.out.printf("  %-5d (%3d,%3d,%2d)     %8.1f %10s %8s %10s%n", j, x[j],
                        y[j], h[j], d, regime, vis < 0 ? "-" : String.format("%+d", alone),
                        pair ? "yes" : "no");
            }
        }
        System.out.printf("%n%d unexplained exits drawn into %s%n", drawn, outDir);
    }

    /**
     * Tick by tick, what the flock made the suspect do against what a pairwise model says it
     * would have done.
     * <p>
     * This is the admission test written out longhand. {@link CriticalEnvelope} walks a pair
     * backwards accepting a step only when one of the two physics models reproduces the move the
     * boid actually made; where neither does, the chain breaks and the arrangement is refused.
     * The <b>lands</b> column is that test, and the first row where it reads {@code no} is where
     * the history was lost.
     */
    public static void steeringHistory(PresetScenarioParameter preset, boolean horizontal,
                                       int line, int lo, int hi, int dir, int[][] arcs,
                                       Flocking normal, Flocking alt, int lookback)
            throws IOException {
        Labelling l = label(preset, horizontal, line, lo, hi, dir);
        NavMap map = l.map();
        ExitAudit audit = new ExitAudit(ExitAudit.Tables.of(preset.ingest().outputDir("envelope"),
                map, l.edge(), l.live(), l.liveCount(), arcs, normal, alt),
                new PsyboidOverride[0]);
        boolean[] settled = CriticalEnvelope.settled(map, l.edge(), l.live(), l.liveCount(),
                arcs[0][0]);

        Boids2DEngine engine = new Boids2DEngine(preset);
        for (String label : PsyboidCorpus.labels(preset.ingest())) {
            PsyboidBits.Replay plan = PsyboidBits.parse(label);
            long last = 0;
            for (PsyboidOverride o : plan.overrides()) {
                last = Math.max(last, (long) o.onset() + o.duration());
            }
            int before = audit.exits().size();
            engine.trace(null);
            Sim.State s = engine.init(plan.seed());
            for (int t = 0; t < PsyboidBits.WARM; t++) s = engine.tick(s);
            s = withOverrides(s, plan.overrides());
            audit.reset(s.n, plan.overrides());
            engine.trace(audit);
            while (s.tick <= last) s = engine.tick(s);
            engine.trace(null);

            for (int k = before; k < audit.exits().size(); k++) {
                ExitAudit.Exit e = audit.exits().get(k);
                if (!e.unexplained() || e.entryStates().length == 0) continue;
                replayHistory(preset, engine, plan, last, e, map, l.edge(), settled, normal,
                        alt, lookback);
            }
        }
    }

    /**
     * Flies the plan again with a recorder on the suspect, and prints the window.
     * <p>
     * <b>Every neighbour, not one chosen leader.</b> The question a fixed leader cannot answer is
     * whether the history is a <em>baton pass</em> — one boid accounting for the early ticks and
     * a different one for the later ticks, with no single boid covering the whole run. Admission
     * holds one leader for an entire history, so a baton pass is unaccountable to any pairwise
     * table however its constants are set, and that is a different finding from a window being
     * slightly too narrow.
     */
    private static void replayHistory(PresetScenarioParameter preset, Boids2DEngine engine,
                                      PsyboidBits.Replay plan, long last, ExitAudit.Exit e,
                                      NavMap map, int[] edge, boolean[] settled,
                                      Flocking normal, Flocking alt, int lookback)
            throws IOException {
        int who = e.suspect();
        int n = e.entryStates().length;
        long from = e.entryTick() - lookback + 1;

        int[][] landed = new int[n][1];
        int[] ticks = {0};

        System.out.printf("%n=== exit %d->%d at tick %d, suspect %d, envelope entry at tick"
                        + " %d ===%n", e.fromEdge(), e.toEdge(), e.tick(), who, e.entryTick());
        System.out.printf("%6s %5s %6s %5s  %-10s", "tick", "want", "actual", "edge", "accounts");
        for (int j = 0; j < n; j++) {
            if (j != who) System.out.printf("  %14s", "boid " + j + " d/dil/dist");
        }
        System.out.printf("  %s%n", "note");

        Boids2DEngine.Trace recorder = (tick, i, boids, want) -> {
            if (i != who || tick < from || tick > e.entryTick()) return;
            ticks[0]++;
            int x = boids.x()[i], y = boids.y()[i], h = boids.h()[i];
            int state = map.index(x, y, h);
            int went = map.successor(state, want);

            StringBuilder cells = new StringBuilder();
            StringBuilder accounts = new StringBuilder();
            for (int j = 0; j < n; j++) {
                if (j == who) continue;
                int lx = boids.x()[j], ly = boids.y()[j], lh = boids.h()[j];
                int p1 = EdgeInfluence.steer(h, lx - x, ly - y, lh, normal);
                int p2 = EdgeInfluence.steer(h, lx - x, ly - y, lh, alt);
                boolean ok = map.successor(state, p1) == went || map.successor(state, p2) == went;
                double d = Math.hypot(lx - (double) x, ly - (double) y);
                if (ok) {
                    landed[j][0]++;
                    accounts.append(accounts.length() > 0 ? "," : "").append(j);
                }
                cells.append(String.format("  %+2d/%+2d/%5.0f%s", p1, p2, d, ok ? "*" : " "));
            }
            String note = settled[state] ? "settled" : "";
            if (tick == e.entryTick()) note = (note.isEmpty() ? "" : note + ", ") + "ENTERS";
            System.out.printf("%6d %+5d %+6d %5d  %-10s%s  %s%n", tick, want,
                    map.constrainTurn(x, y, h, want), edge[state],
                    accounts.length() == 0 ? "NOBODY" : "{" + accounts + "}",
                    cells, note);
        };

        engine.trace(null);
        Sim.State s = engine.init(plan.seed());
        for (int t = 0; t < PsyboidBits.WARM; t++) s = engine.tick(s);
        s = withOverrides(s, plan.overrides());
        engine.trace(recorder);
        while (s.tick <= last) s = engine.tick(s);
        engine.trace(null);

        // The decisive number. A pairwise table holds one leader for a whole history, so unless
        // some boid accounts for every tick, no setting of the constants can admit this.
        System.out.printf("over %d ticks: ", ticks[0]);
        boolean any = false;
        for (int j = 0; j < n; j++) {
            if (j == who) continue;
            System.out.printf("boid %d accounts for %d, ", j, landed[j][0]);
            any |= landed[j][0] == ticks[0];
        }
        System.out.printf("%n  %s%n", any
                ? "at least one boid accounts for the whole window"
                : "NO SINGLE BOID ACCOUNTS FOR THE WHOLE WINDOW -- multi-leader");
    }

    /**
     * Draws one exit on the whole map, with the critical envelope tinted into the background.
     * <p>
     * The cropped view {@link ExitRender} gives answers who could see whom, which is the right
     * frame for an influence question and the wrong one for a structural one. At map scale the
     * question becomes where everybody is on the route: which edge each boid is on, how far the
     * suspect is from the branch, and whether the boid carrying the turn is somewhere the map
     * makes likely rather than somewhere it happened to be.
     * <p>
     * The envelope goes into the background rather than being drawn over the flock, so it reads
     * as a region of the map — which is what it is — rather than as an annotation on this
     * particular arrangement.
     */
    public static void renderTick(PresetScenarioParameter preset, boolean horizontal, int line,
                                  int lo, int hi, int dir, int from, int keep, long crossing,
                                  int suspect, int[] before, EdgeWeights.Scheme scheme,
                                  double[][] chain, Flocking flock, Path out) throws IOException {
        SolverFacts f = SolverStore.prepare(preset,
                new SolverFacts.Gate(horizontal, line, lo, hi, dir), scheme, chain, flock);
        Labelling l = label(preset, horizontal, line, lo, hi, dir);
        CriticalEnvelope.Envelope env = CriticalEnvelope.envelope(l.map(), l.edge(), l.live(),
                l.liveCount(), from, keep);

        // Tint every pixel some envelope state passes through. A pixel can carry states on
        // several edges, so this is a projection and deliberately coarse — it says "the envelope
        // runs through here", which is the only thing the eye can use at map scale anyway.
        BufferedImage bg = javax.imageio.ImageIO.read(preset.ingest().display().toFile());
        int w = bg.getWidth(), turns = Params.TURNS;
        for (int[] set : new int[][]{env.onFrom(), env.onKeep()}) {
            boolean onKeep = set == env.onKeep();
            for (int s : set) {
                int cell = s / turns, x = cell % w, y = cell / w;
                int rgb = bg.getRGB(x, y);
                int r = (rgb >> 16) & 255, g2 = (rgb >> 8) & 255, b = rgb & 255;
                // The edge being left and the edge being entered get different tints, so the
                // boundary the boid is about to cross is visible rather than implied.
                bg.setRGB(x, y, onKeep
                        ? (Math.min(255, r + 10) << 16) | (Math.min(255, g2 + 70) << 8) | b / 2
                        : (Math.min(255, r + 90) << 16) | (g2 / 2 << 8) | (b / 2));
            }
        }
        Path tinted = out.resolveSibling("background-envelope.png");
        if (tinted.getParent() != null) Files.createDirectories(tinted.getParent());
        javax.imageio.ImageIO.write(bg, "png", tinted.toFile());

        // Find the plan holding this exit, then replay it capturing the wanted ticks.
        Boids2DEngine engine = new Boids2DEngine(preset);
        for (String label : PsyboidCorpus.labels(preset.ingest())) {
            PsyboidBits.Replay plan = PsyboidBits.parse(label);
            long last = 0;
            for (PsyboidOverride o : plan.overrides()) {
                last = Math.max(last, (long) o.onset() + o.duration());
            }
            if (crossing > last) continue;
            Sim.State s = engine.init(plan.seed());
            for (int t = 0; t < PsyboidBits.WARM; t++) s = engine.tick(s);
            s = withOverrides(s, plan.overrides());

            Map<Long, Sim.State> want = new java.util.HashMap<>();
            java.util.List<Long> ticks = new ArrayList<>();
            for (int b : before) ticks.add(crossing - b);
            ticks.add(crossing);
            // The boid is still on the edge it is leaving at the crossing tick; it stands on
            // the exit edge only once that tick has been executed.
            ticks.add(crossing + 1);
            while (s.tick <= last) {
                if (ticks.contains(s.tick)) want.put(s.tick, s);
                s = engine.tick(s);
            }
            if (want.size() < ticks.size()) continue;
            // The right plan is the one where this boid really is crossing here.
            Sim.State on = want.get(crossing), off = want.get(crossing + 1);
            int was = f.edgeAt(on.x[suspect], on.y[suspect], on.h[suspect]);
            int now = f.edgeAt(off.x[suspect], off.y[suspect], off.h[suspect]);
            if (was != from || now != keep) continue;
            ticks.remove(crossing + 1);
            java.util.Collections.sort(ticks);

            List<SceneRender.Panel> panels = new ArrayList<>();
            for (long t : ticks) {
                panels.add(new SceneRender.Panel(t == crossing ? "tick " + t + " — crosses"
                        : "tick " + t + "  (" + (crossing - t) + " ticks earlier)",
                        want.get(t), suspect));
            }
            SceneRender.write(tinted, out, f,
                    String.format("%s  exit %d->%d at tick %d, boid %d — no single leader accounts"
                            + " for it", preset.name(), from, keep, crossing, suspect),
                    String.format("boid %d is the suspect. envelope tint is faint by nature: it is "
                            + "%d states on edge %d and %d on edge %d, which project to a few "
                            + "dozen pixels", suspect, env.onFrom().length, from,
                            env.onKeep().length, keep),
                    panels, preset.turningRadius(), 2);
            System.out.printf("wrote %s (seed %d)%n", out, plan.seed());
            return;
        }
        System.out.println("no plan in the corpus holds that crossing");
    }

    /**
     * The map-wide stable set, its straight-travel loops, and what agreement expands it to.
     * <p>
     * The test this is here to run: stable+ should be <b>larger</b> than stable, because ordinary
     * traffic does knock boids off the states a lone boid holds, and should reach <b>no new
     * edges</b>, because an edge only reachable once jostling is allowed is a route rather than a
     * wobble — and a route is precisely what the solver is supposed to find remarkable.
     */
    /**
     * The whole pipeline rerun under a proposed aggregation, end to end.
     * <p>
     * Unlike {@link #aggregationPhaseMaps}, this is <b>not</b> a variant that agrees with the
     * simulation on a single neighbour, so nothing downstream can be reused. The single-neighbour
     * closed form changes, therefore stable+ changes, therefore the critical-envelope tables
     * change, therefore the colouring of the phase map changes as well as the trajectories. Each
     * of those is rebuilt here and reported, so that "end to end it looks similar" is a claim
     * about every stage rather than about the last picture.
     * <p>
     * What does <b>not</b> change, and is worth knowing before reading the numbers: the navmap,
     * the edge decomposition, the clock, {@code pureStable}, map-wide stable, the envelope itself
     * and cost-to-leave. None of them consults the flocking constants — they are properties of
     * the map and of straight travel alone.
     */
    public static void proposedPhysics(PresetScenarioParameter preset, Labelling l, SolverFacts f,
                                       Flocking base, int from, int keep, int quorum,
                                       Aggregation proposal, Path out) throws IOException {
        Flocking was = base.sepFalloff(Aggregation.CURRENT.separationFalloffAtOne());
        Flocking now = base.sepFalloff(proposal.separationFalloffAtOne());
        System.out.printf("%n=== %s @%s: the pipeline under %s, arc %d->%d ===%n", preset.name(),
                preset.ingest().hash(), proposal.id(), from, keep);
        System.out.printf("%s; separation falloff at one neighbour: %s -> %s%n",
                proposal.describes(), was.sepFalloff(), now.sepFalloff());

        // Only the proposal has to have a closed form. Some variants deliberately do not — see
        // AggregationSurvey.checkClosedForm — and one of them failing says nothing about this one.
        if (!AggregationSurvey.checkClosedForm(l.map(), l.live(), l.liveCount(), base, 11, 4000,
                Aggregation.CURRENT, proposal)) {
            throw new IllegalStateException("closed form disagrees with " + proposal.id()
                    + "; every table built from here would be wrong");
        }

        MapStates old = MapStates.of(l.map(), was, l.live(), l.liveCount());
        MapStates fresh = MapStates.of(l.map(), now, l.live(), l.liveCount());
        StateSet pure = old.pureStable(1);
        StateSet stable = pure.partialTick(StateSet.Steering.STRAIGHT)
                .closed(StateSet.Steering.STRAIGHT);
        StateSet plusWas = old.stablePlus(quorum), plusNow = fresh.stablePlus(quorum);
        System.out.printf("%npureStable(1) %,d and map-wide stable %,d — unchanged, neither reads "
                + "the flocking constants%n", pure.size(), stable.size());
        System.out.printf("stable+ q%d: %,d -> %,d states%n  was %s%n  now %s%n", quorum,
                plusWas.size(), plusNow.size(), MapStates.byEdge(plusWas, l.edge(), f.edges()),
                MapStates.byEdge(plusNow, l.edge(), f.edges()));

        Path base1 = Path.of("render", "prop-baseline.png");
        runPipeline(preset, l, f, was, from, keep, plusWas, null, base1);
        runPipeline(preset, l, f, now, from, keep, plusNow, proposal, out);
        ThreeBoidPhase.compare(base1, out, f, from, 0.5);
        ThreeBoidPhase.panelSheet(List.of(base1, out),
                List.of("CURRENT (physics 2)", proposal.id() + " (proposed)"), f, from, 0.5, 1, 1,
                2, String.format("%s @%s — centre panel of the %d->%d phase map, current against "
                        + "proposed", preset.name(), preset.ingest().hash(), from, keep),
                out.resolveSibling("prop-centre-panels.png"));
    }

    /** Tables on the given constants and ground, then the phase map they colour. */
    private static void runPipeline(PresetScenarioParameter preset, Labelling l, SolverFacts f,
                                    Flocking flock, int from, int keep, StateSet plus,
                                    Aggregation agg, Path out) throws IOException {
        boolean[] settled = CriticalEnvelope.settled(l.map(), l.edge(), l.live(), l.liveCount(),
                from);
        boolean[] ground = new boolean[l.edge().length];
        for (int i = 0; i < l.liveCount(); i++) {
            int s = l.live()[i];
            ground[s] = settled[s] || (l.edge()[s] == from && plus.contains(s));
        }
        long t0 = System.nanoTime();
        ExitAudit.Tables tabs = ExitAudit.Tables.of(preset.ingest().outputDir("envelope"),
                l.map(), l.edge(), l.live(), l.liveCount(), new int[][]{{from, keep}}, flock,
                flock.diluted(), ground);
        System.out.printf("%ntables in %.0fs%n%s", (System.nanoTime() - t0) / 1e9, tabs.summary());
        ThreeBoidPhase.run(preset, l, f, tabs, from, keep, 8, 8, 8, 0.5, 0.9995, 1, plus, agg,
                out);
    }

    /**
     * The same phase map flown under each candidate aggregation, cross-tabulated against the
     * baseline.
     * <p>
     * The baseline is regenerated here rather than reused from disk, so that the two pictures
     * being compared came out of the same build — a comparison against a file written by earlier
     * code would silently fold in every unrelated change since.
     * <p>
     * <b>The tables are built once and shared across all of them.</b> Every variant passed here
     * agrees with the simulation on a single neighbour, so the pairwise closed form and therefore
     * the envelope is identical under each; rebuilding them per variant would produce the same
     * bytes and invite the suspicion that it had not.
     */
    public static void aggregationPhaseMaps(PresetScenarioParameter preset, Labelling l,
                                            SolverFacts f, Flocking flock, int from, int keep,
                                            int quorum, Aggregation[] variants)
            throws IOException {
        MapStates m = MapStates.of(l.map(), flock, l.live(), l.liveCount());
        StateSet plus = m.stablePlus(quorum);
        ExitAudit.Tables tabs = tablesOnStablePlus(preset, l, f, flock, from, keep, quorum);

        Path base = Path.of("render", "agg-baseline.png");
        System.out.printf("%n=== phase maps under each aggregation, arc %d->%d ===%n", from, keep);
        ThreeBoidPhase.run(preset, l, f, tabs, from, keep, 8, 8, 8, 0.5, 0.9995, 1, plus, null,
                base);
        for (Aggregation a : variants) {
            Path out = Path.of("render", "agg-" + a.id().toLowerCase(java.util.Locale.ROOT)
                    + ".png");
            ThreeBoidPhase.run(preset, l, f, tabs, from, keep, 8, 8, 8, 0.5, 0.9995, 1, plus, a,
                    out);
            ThreeBoidPhase.compare(base, out, f, from, 0.5);
        }
    }

    /**
     * The critical-envelope tables for one arc, on the same ground {@link #phaseMapOnStablePlus}
     * builds them on: settled unioned with stable+ restricted to the edge being left.
     * <p>
     * Split out because anything reading a phase map drawn on stable+ has to ask its questions
     * against the tables that map was coloured by, and rebuilding that ground by hand at each
     * call site is how two analyses end up quietly disagreeing about what an exit means.
     */
    public static ExitAudit.Tables tablesOnStablePlus(PresetScenarioParameter preset, Labelling l,
                                                      SolverFacts f, Flocking flock, int from,
                                                      int keep, int quorum) {
        MapStates m = MapStates.of(l.map(), flock, l.live(), l.liveCount());
        StateSet plus = m.stablePlus(quorum);
        boolean[] settled = CriticalEnvelope.settled(l.map(), l.edge(), l.live(), l.liveCount(),
                from);
        boolean[] ground = new boolean[l.edge().length];
        for (int i = 0; i < l.liveCount(); i++) {
            int s = l.live()[i];
            ground[s] = settled[s] || (l.edge()[s] == from && plus.contains(s));
        }
        return ExitAudit.Tables.of(preset.ingest().outputDir("envelope"), l.map(), l.edge(),
                l.live(), l.liveCount(), new int[][]{{from, keep}}, flock, flock.diluted(),
                ground);
    }

    /**
     * The three-boid phase map rebuilt on stable+ throughout.
     * <p>
     * Two things change together, and they have to: the suspect starts anywhere ordinary traffic
     * could have left it on the edge rather than in a settled band across the middle, <b>and</b>
     * the critical-envelope tables the map is coloured by terminate their histories on the same
     * ground. Changing only the starts would draw a wider population against an account that
     * still refuses everything off the narrow ground, which is a picture of the mismatch rather
     * than of the flock.
     * <p>
     * <b>The ground is the union with settled, not a replacement.</b> A ground that failed to
     * contain settled would refuse histories the old tables admit, and stable+ is meant to widen
     * what counts as ordinary, never to narrow it. The overlap is reported because if stable+
     * already contained settled the union is free, and if it did not that is worth knowing.
     */
    public static void phaseMapOnStablePlus(PresetScenarioParameter preset, Labelling l,
                                            SolverFacts f, Flocking flock, int from, int keep,
                                            int quorum, Path out) throws IOException {
        MapStates m = MapStates.of(l.map(), flock, l.live(), l.liveCount());
        System.out.printf("%n=== %s @%s: phase map on stable+, arc %d->%d ===%n", preset.name(),
                preset.ingest().hash(), from, keep);

        StateSet pureFor = m.pureStable(1);
        int spread = pureFor.partialTick(StateSet.Steering.STRAIGHT).size();
        int shape = m.shapeQuorum();
        System.out.printf("pureStable(1) %,d, .partialTick %,d, spread %.2f -> %d offset(s); "
                        + "4*round(speed) = %d%n", pureFor.size(), spread,
                spread / (double) pureFor.size(),
                Integer.highestOneBit(Math.max(1, spread / pureFor.size())),
                4 * (int) Math.round(Params.speed(preset.turningRadius())));
        System.out.printf("quorum %d in use; the map's shape asks for %d%s%n", quorum, shape,
                shape == quorum ? "" : "  <-- THESE DISAGREE, using the one passed in");

        StateSet plus = m.stablePlus(quorum);
        System.out.printf("stable+ %,d states: %s%n", plus.size(),
                MapStates.byEdge(plus, l.edge(), f.edges()));

        boolean[] settled = CriticalEnvelope.settled(l.map(), l.edge(), l.live(), l.liveCount(),
                from);
        int settledOn = 0, alsoPlus = 0, plusOn = 0;
        boolean[] ground = new boolean[l.edge().length];
        for (int i = 0; i < l.liveCount(); i++) {
            int s = l.live()[i];
            if (settled[s]) { settledOn++; if (plus.contains(s)) alsoPlus++; }
            if (l.edge()[s] == from && plus.contains(s)) plusOn++;
            ground[s] = settled[s] || (l.edge()[s] == from && plus.contains(s));
        }
        int groundSize = 0;
        for (boolean b : ground) if (b) groundSize++;
        System.out.printf("edge %d: %,d settled, %,d in stable+, %,d settled states also in "
                        + "stable+; ground is their union, %,d states%n", from, settledOn, plusOn,
                alsoPlus, groundSize);

        long t0 = System.nanoTime();
        ExitAudit.Tables tabs = ExitAudit.Tables.of(preset.ingest().outputDir("envelope"),
                l.map(), l.edge(), l.live(), l.liveCount(), new int[][]{{from, keep}}, flock,
                flock.diluted(), ground);
        System.out.printf("tables on the wider ground in %.0fs%n%s",
                (System.nanoTime() - t0) / 1e9, tabs.summary());

        ThreeBoidPhase.run(preset, l, f, tabs, from, keep, /*band=*/8, /*kBoid=*/8, /*kPsy=*/8,
                /*resolution=*/0.5, /*targetFill=*/0.9995, /*seed=*/1, plus, out);

        // The control. Two things changed at once — where the suspect starts and what the tables
        // will terminate a history on — and a single number cannot be attributed to either
        // without holding one of them still. This holds the ground at settled and keeps the
        // wider starts, so the difference between the two runs is the ground alone.
        System.out.printf("%n--- control: the same wider starts, tables still on settled ---%n");
        ExitAudit.Tables narrow = ExitAudit.Tables.of(preset.ingest().outputDir("envelope"),
                l.map(), l.edge(), l.live(), l.liveCount(), new int[][]{{from, keep}}, flock,
                flock.diluted(), null);
        ThreeBoidPhase.run(preset, l, f, narrow, from, keep, 8, 8, 8, 0.5, 0.9995, 1, plus,
                out.resolveSibling(out.getFileName().toString()
                        .replace(".png", "-control.png")));
    }

    /**
     * Stable+ over a range of agreement ratios: what it grows to, whether it ever reaches an
     * exit, what it costs to leave from it, and a picture of each.
     * <p>
     * <b>The prediction under test.</b> With one quorum and every state closed under straight
     * travel, an exit can only enter the set if an exit window sits on the stable loop with a
     * quorum of influencers on it. On dabeone that is expected to need a quorum of 2 or fewer, so
     * every ratio short of {@code |influencers| / 2} should stay on the stable edges.
     *
     * @param arcs pairs to report cost-to-leave for, as {@code {from, to}}
     */
    public static void stablePlusScan(PresetScenarioParameter preset, Labelling l, SolverFacts f,
                                      Flocking flock, int[] ratios, int[][] arcs, int[] path,
                                      Path out) throws IOException {
        MapStates m = MapStates.of(l.map(), flock, l.live(), l.liveCount());
        StateSet pure = m.pureStable(1);
        StateSet stable = pure.partialTick(StateSet.Steering.STRAIGHT)
                .closed(StateSet.Steering.STRAIGHT);
        boolean[] stableEdge = new boolean[f.edges()];
        for (int s : stable.toArray()) if (l.edge()[s] >= 0) stableEdge[l.edge()[s]] = true;

        System.out.printf("%n=== %s @%s: stable+ under the single-quorum rule ===%n",
                preset.name(), preset.ingest().hash());
        System.out.printf("pureStable(1) %,d in %d loop(s); stable %,d on edges %s%n",
                pure.size(), m.cycles(pure).size(), stable.size(),
                MapStates.byEdge(stable, l.edge(), f.edges()));

        // The existing cost-to-leave, reduced over each edge's inbound points, so the stable+
        // figures below have the published number to sit against.
        EdgeNavigation.EdgeNav[] nav = EdgeNavigation.analyse(l.map(), l.live(), l.liveCount(),
                l.edge(), f.edges());
        int[][] cost = new int[arcs.length][];
        for (int a = 0; a < arcs.length; a++) {
            cost[a] = EdgeNavigation.steerCostTo(l.map(), l.live(), l.liveCount(), l.edge(),
                    f.edges(), arcs[a][0], arcs[a][1]);
            for (EdgeNavigation.Exit e : nav[arcs[a][0]].exits()) {
                if (e.to() != arcs[a][1]) continue;
                System.out.printf("cost to leave %d->%d  from the %,d INBOUND points (what the "
                                + "edge graph publishes): min %d, max %d, %d unreachable%n",
                        arcs[a][0], arcs[a][1], nav[arcs[a][0]].inbound(), e.minTicks(),
                        e.maxTicks(), e.unreachable());
            }
            System.out.printf("                      from pureStable(1): %s        from stable: "
                            + "%s%n", reduce(pure, cost[a], l.edge(), arcs[a][0]),
                    reduce(stable, cost[a], l.edge(), arcs[a][0]));
        }

        System.out.printf("%n%6s %7s %10s %9s %9s  %-24s", "ratio", "quorum", "states",
                "vs stable", "path tick", "edges");
        for (int[] arc : arcs) System.out.printf(" %14s", "cost " + arc[0] + "->" + arc[1]);
        System.out.println("   exits?");

        List<StateSetRender.Panel> panels = new ArrayList<>();
        panels.add(new StateSetRender.Panel("pureStable(1)",
                String.format("%,d states, %d loop(s)", pure.size(), m.cycles(pure).size()), pure));
        panels.add(new StateSetRender.Panel("stable = .partialTick.closed",
                String.format("%,d states", stable.size()), stable));

        for (int r : ratios) {
            StateSet plus = stable.expandByAgreement(pure, r);
            int quorum = Math.max(1, pure.size() / r);
            int last = -1;
            for (int t = 0; t < path.length; t++) if (plus.contains(path[t])) last = t;
            List<Integer> escaped = new ArrayList<>();
            for (int s : plus.toArray()) {
                int e = l.edge()[s];
                if (e >= 0 && !stableEdge[e] && !escaped.contains(e)) escaped.add(e);
            }
            StringBuilder costs = new StringBuilder();
            for (int a = 0; a < arcs.length; a++) {
                costs.append(String.format(" %14s", reduce(plus, cost[a], l.edge(), arcs[a][0])));
            }
            System.out.printf("%6d %7d %,10d %8.1fx %9d  %-24s%s   %s%n", r, quorum, plus.size(),
                    plus.size() / (double) stable.size(), last,
                    MapStates.byEdge(plus, l.edge(), f.edges()), costs,
                    escaped.isEmpty() ? "none" : "EXITS " + escaped);
            panels.add(new StateSetRender.Panel(
                    String.format("ratio %d — quorum %d", r, quorum),
                    String.format("%,d states, %.1fx stable, exits: %s", plus.size(),
                            plus.size() / (double) stable.size(),
                            escaped.isEmpty() ? "none" : escaped.toString()), plus));
        }
        System.out.printf("%d turns hit the cap instead of ending%n", m.capped());

        if (out != null) {
            StateSetRender.write(preset.ingest().display(), out, panels, 4, 2,
                    String.format("%s @%s — stable+ projected to (x,y), by agreement ratio",
                            preset.name(), preset.ingest().hash()));
        }
    }

    /** Cost to leave, reduced over the part of a state set that lies on one edge. */
    private static String reduce(StateSet set, int[] cost, int[] edge, int from) {
        int min = EdgeNavigation.NEVER, max = -1, unreachable = 0, n = 0;
        for (int s : set.toArray()) {
            if (edge[s] != from) continue;
            n++;
            if (cost[s] == EdgeNavigation.NEVER) { unreachable++; continue; }
            min = Math.min(min, cost[s]);
            max = Math.max(max, cost[s]);
        }
        if (n == 0) return "no states";
        return String.format("%s-%d, %d unr",
                min == EdgeNavigation.NEVER ? "-" : String.valueOf(min), max, unreachable);
    }

    /**
     * The same, over a range of agreement ratios, against one flown path.
     * <p>
     * The ratio is the only free parameter in stable+ and there is no principle fixing it, so the
     * thing to look at is where the set stops growing gracefully. Two numbers say that: the
     * <b>edges</b> it reaches, which must not grow at all, and how far along a real approach the
     * boid is still on ordinary ground, which is what decides whether an exit has a single-leader
     * account.
     */
    public static void stablePlusSweep(PresetScenarioParameter preset, Labelling l, SolverFacts f,
                                       Flocking flock, int[] ratios, int[] path) {
        MapStates m = MapStates.of(l.map(), flock, l.live(), l.liveCount());
        StateSet pure = m.pureStable(1);
        StateSet stable = pure.partialTick(StateSet.Steering.STRAIGHT)
                .closed(StateSet.Steering.STRAIGHT);

        System.out.printf("%n=== %s @%s: stable+ against the agreement ratio ===%n",
                preset.name(), preset.ingest().hash());
        System.out.printf("pureStable(1) %,d, stable %,d, path %d ticks%n", pure.size(),
                stable.size(), path.length);
        System.out.printf("%n%6s %8s %10s %8s %9s   %s%n", "ratio", "quorum", "states",
                "vs stable", "last tick", "edges");
        for (int r : ratios) {
            StateSet plus = stable.expandByAgreement(pure, r);
            int last = -1;
            for (int t = 0; t < path.length; t++) if (plus.contains(path[t])) last = t;
            boolean[] had = new boolean[f.edges()];
            for (int s : stable.toArray()) if (l.edge()[s] >= 0) had[l.edge()[s]] = true;
            StringBuilder added = new StringBuilder();
            for (int s : plus.toArray()) {
                int e = l.edge()[s];
                if (e >= 0 && !had[e]) { had[e] = true; added.append(" +").append(e); }
            }
            StringBuilder on = new StringBuilder();
            for (int t = 0; t < path.length; t++) on.append(plus.contains(path[t]) ? '#' : '.');
            System.out.printf("%6d %8d %,10d %8.1fx %9d   %s%s%n", r, pure.size() / r,
                    plus.size(), plus.size() / (double) stable.size(), last,
                    MapStates.byEdge(plus, l.edge(), f.edges()), added);
            System.out.printf("       path tick 0..%d on stable+: %s%n", path.length - 1, on);
        }

        // What the path itself asks for, tick by tick, so the ratio is chosen from the geometry
        // rather than from a sweep.
        System.out.printf("%n%6s %8s %8s   of %d influencers%n", "tick", "ask left", "ask right",
                pure.size());
        for (int t = 0; t < path.length; t++) {
            int[] a = m.agreement(pure, path[t]);
            System.out.printf("%6d %8d %8d   left needs ratio <= %s, right <= %s%n", t, a[0],
                    a[1], a[0] == 0 ? "-" : String.valueOf(a[2] / a[0]),
                    a[1] == 0 ? "-" : String.valueOf(a[2] / a[1]));
        }
    }

    public static StateSet stablePlus(PresetScenarioParameter preset, Labelling l, SolverFacts f,
                                      Flocking flock, int agreementRatio) {
        MapStates m = MapStates.of(l.map(), flock, l.live(), l.liveCount());
        System.out.printf("%n=== %s @%s: map-wide stable and stable+ ===%n", preset.name(),
                preset.ingest().hash());

        long t0 = System.nanoTime();
        StateSet pure = m.pureStable(1);
        java.util.List<int[]> loops = m.cycles(pure);
        System.out.printf("pureStable(1)              %,8d states in %.1fs%n", pure.size(),
                (System.nanoTime() - t0) / 1e9);
        System.out.printf("  %d straight-travel loops, lengths %s (speed %.2f)%n", loops.size(),
                loops.stream().map(a -> String.format("%,d", a.length)).toList(),
                Params.speed(preset.turningRadius()));
        System.out.printf("  edges  %s%n", MapStates.byEdge(pure, l.edge(), f.edges()));

        t0 = System.nanoTime();
        StateSet stable = pure.partialTick(StateSet.Steering.STRAIGHT)
                .closed(StateSet.Steering.STRAIGHT);
        System.out.printf("%n.partialTick.closed        %,8d states in %.1fs  (x%.1f)%n",
                stable.size(), (System.nanoTime() - t0) / 1e9,
                stable.size() / (double) pure.size());
        System.out.printf("  edges  %s%n", MapStates.byEdge(stable, l.edge(), f.edges()));

        t0 = System.nanoTime();
        StateSet plus = stable.expandByAgreement(pure, agreementRatio);
        System.out.printf("%n.expandByAgreement(pure,%d) %,8d states in %.1fs  (x%.1f)%n",
                agreementRatio, plus.size(), (System.nanoTime() - t0) / 1e9,
                plus.size() / (double) stable.size());
        System.out.printf("  edges  %s%n", MapStates.byEdge(plus, l.edge(), f.edges()));
        System.out.printf("  %d turns hit the cap instead of ending%n", m.capped());

        // The headline test, stated as the two things that could go wrong.
        StateSet fresh = plus.minus(stable);
        boolean[] had = new boolean[f.edges()], now = new boolean[f.edges()];
        for (int s : stable.toArray()) if (l.edge()[s] >= 0) had[l.edge()[s]] = true;
        for (int s : plus.toArray()) if (l.edge()[s] >= 0) now[l.edge()[s]] = true;
        java.util.List<Integer> added = new ArrayList<>();
        for (int e = 0; e < f.edges(); e++) if (now[e] && !had[e]) added.add(e);
        System.out.printf("%n%,d states added over stable; new edges reached: %s%n", fresh.size(),
                added.isEmpty() ? "NONE — stable+ stays on the edges stable already touched"
                        : added.toString());
        System.out.printf("  the added states lie on  %s%n",
                MapStates.byEdge(fresh, l.edge(), f.edges()));
        return plus;
    }

    public static void main(String[] args) throws IOException {
        // Output goes inside the map's own ingest, so a route trace or an edge map can
        // never be read against a dabnt that has been edited since it was produced.
        PresetScenarioParameter p = PresetScenarioParameter.DABEONE;
        Flocking f = Flocking.of(p.turningRadius());
        // Set pruneOutOfRangeLeaders while the exact out-of-range collapse is unbuilt; see ROADMAP.
        // Approximate, and on because the exact out-of-range collapse is not built yet; see
        // ROADMAP. Building all three arcs' tables costs about thirteen minutes and is paid on
        // every run, since the tables are not yet persisted. Drop 4->0 for a fast pass.
        CriticalEnvelope.pruneOutOfRangeLeaders = true;
        double[][] chain = EdgeWeights.blend(new double[][]{{1,1,1},{1,1,1},{1,1,1}}, 0);
        CriticalEnvelope.pruneOutOfRangeLeaders = true;
        SolverFacts facts = SolverStore.prepare(p, new SolverFacts.Gate(false, 202, 174, 191, -1),
                EdgeWeights.Scheme.MOMENTUM, chain, f);
        Labelling lab = label(p, false, 202, 174, 191, -1);
        ExitAudit.Tables tabs = ExitAudit.Tables.of(p.ingest().outputDir("envelope"), lab.map(),
                lab.edge(), lab.live(), lab.liveCount(), new int[][]{{4, 0}}, f, f.diluted());
        int[] path = ThreeBoidSamples.suspectPath(p, facts, tabs, 4, 2, 1, 158, 255,
                Path.of("render", "phase40-replays.tsv"));
        Path proposed = Path.of("render", "prop-rule_sum_clamp.png");
        Path propBase = Path.of("render", "prop-baseline.png");
        ThreeBoidPhase.compare(propBase, proposed, facts, 4, 0.5);
        for (int block : new int[]{4, 8, 16}) {
            ThreeBoidPhase.compareBlocks(propBase, proposed, facts, 4, 0.5, block);
        }
        for (Path v : new Path[]{propBase, proposed}) {
            List<ThreeBoidSamples.Feature> w = ThreeBoidSamples.features(v, facts, 4, 0.5, 1, 1,
                    ThreeBoidPhase.UNEXPLAINED, 4, 40);
            System.out.printf("  %s: %d clumps of 40+ in the centre panel%n", v.getFileName(),
                    w.size());
        }
        if (true) return;
        Path plus = Path.of("render", "phase40-stableplus.png");
        for (int merge : new int[]{2, 4, 6}) {
            List<ThreeBoidSamples.Feature> w = ThreeBoidSamples.features(plus, facts, 4, 0.5,
                    /*route=*/1, /*otherRoute=*/1, ThreeBoidPhase.UNEXPLAINED, merge, 40);
            System.out.printf("  merge %d: %d features%n", merge, w.size());
            if (merge != 4) continue;
            System.out.printf("%n%5s %10s %10s %8s %7s %6s%n", "id", "x range", "y range",
                    "w x h", "cells", "fill");
            for (ThreeBoidSamples.Feature v : w) {
                System.out.printf("%5s %4d..%-5d %4d..%-5d %3d x %-4d %7d %5.0f%%%n", v.id(),
                        v.x0(), v.x1(), v.y0(), v.y1(), v.width(), v.height(), v.cells(),
                        100 * v.fill());
            }
            ThreeBoidSamples.bands(w, 4);
            ThreeBoidSamples.windowFit(plus, facts, 4, 0.5, w, ThreeBoidPhase.UNEXPLAINED, 38, 12);
            ThreeBoidSamples.atlas(plus, facts, 4, 0.5, w, /*cropW=*/150, /*cropH=*/100,
                    /*scale=*/3, /*columns=*/4,
                    "DABEONE @609cffdb84be218c — every unexplained clump in the centre panel "
                            + "[4,2,1,5,8] x [4,2,1,5,8], arc 4->0 on stable+",
                    Path.of("render", "phase40-stableplus-white-atlas.png"));
            ExitAudit.Tables tabsPlus = tablesOnStablePlus(p, lab, facts, f, 4, 0, 5);
            Path reps = Path.of("render", "phase40-stableplus-replays.tsv");
            ThreeBoidSamples.sampleFeatures(p, facts, tabsPlus, 4, 0, 0.5, plus, reps, w, 4, 2,
                    Path.of("render", "phase40-stableplus-white-samples.png"));
            StateSet groundSet = MapStates.of(lab.map(), f, lab.live(), lab.liveCount())
                    .stablePlus(5);
            ThreeBoidSamples.classify(p, lab, facts, tabsPlus, 4, w, plus, reps, 0.5, f,
                    f.diluted(), groundSet);
        }
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

