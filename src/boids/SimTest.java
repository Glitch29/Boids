package boids;

import javax.imageio.ImageIO;
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
import java.util.Collection;
import java.util.HashSet;
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

    private static final int INTERVAL = 500;
    private static final int INTERVALS = 5;
    private static final int SIMS = 10;

    /** Variations split off the same warmed-up timeline, each with its own override. */
    private static final int VARIATIONS = 20;

    /** Repeats per boid count, each from a different starting seed. */
    private static final int REPEATS = 5;

    /** Longest an override may wait before taking effect: three eighths of a turn. */
    private static final int MAX_DELAY = 3 * Params.TURNS / 8;

    /** Observation window after the delay window closes: ten eighths of a turn. */
    private static final int OBSERVE = 10 * Params.TURNS / 8;

    // ---- Exhaustive override sweep -----------------------------------------

    private static final int SWEEP_BOIDS = 10;

    /** Widest start delay: ten eighths of a turn. */
    private static final int SWEEP_MAX_DELAY = 30 * Params.TURNS / 8;

    /**
     * Delays enumerated evenly in the square root of the delay, so starts bunch toward
     * the beginning of the window rather than spreading flat across it.
     */
    private static final int SWEEP_DELAY_STEPS = 16;

    private static final int SWEEP_HORIZON = 640;
    private static final int SWEEP_CHECKPOINT = 32;

    /** Seeds run when none are given on the command line. */
    private static final long[] DEFAULT_SEEDS;
    static {
        DEFAULT_SEEDS = new long[500];
        for (int i = 0 ; i < DEFAULT_SEEDS.length; i++) {
            DEFAULT_SEEDS[i]=i;
        }
    }

    // ---- Receding-horizon psyboid search -----------------------------------

    private static final int SEARCH_MAX_DELAY = Params.TURNS / 8;        // 1s
    private static final int SEARCH_DURATION = 4 * Params.TURNS / 8;     // 4s
    private static final int SEARCH_SEGMENTS = 3;
    private static final int SEARCH_LOOKAHEAD = 80;
    private static final int SEARCH_COMMITS = 20;

    private record Run(int[] branches, double alpha, long seed) {}

    /**
     * @param lift mean of the per-seed lift, not the lift of the means â€” the control is
     *             paired with each seed, so pairing before averaging is what makes the
     *             standard error meaningful
     */
    private record Result(int[] branches, String label, double budget, double alpha,
                          double psyboid, double control, int ticks, int boids, int n,
                          double lift, double sem) {}

    /**
     * Sweeps branch shapes and discount rates against the same warmed timelines, to see
     * how a fixed concurrency budget is best spent: wide and greedy, or narrow and deep.
     */
    /** Widths the enumerated tail may use, largest first so recursion stays non-increasing. */
    private static final int[] TAIL_VALUES = {16, 8, 4, 2, 1};
    /** Raised alongside the budget, so deeper tails can compete for it. */
    private static final int MAX_TAIL_LENGTH = 10;
    private static final int MIN_ROOT_WIDTH = 8;

    public static void rollingSearch(PresetScenarioParameter preset, int boids,
                                     int uncontrolled, int commits) throws IOException {
        Engine engine = new Boids2DEngine(withFlockSize(preset, boids));

        List<int[]> shapes = enumerateShapes(uncontrolled);
        System.out.printf("%s, %d boids, %d uncontrolled ticks, %d commits of %d ticks%n",
                preset.name(), boids, uncontrolled, commits, uncontrolled + SEARCH_DURATION);
        System.out.printf("%d shapes fit the budget of %d%n%n",
                shapes.size(), PsyboidSearch.MAX_BUDGET);

        System.out.println("=== shape sweep, alpha 0.85 ===");
        List<Result> broad = sweep(engine, boids, uncontrolled, commits,
                shapes, new double[]{0.85}, seedRange(8));
        report(broad, 24);

        List<int[]> best = broad.stream()
                .sorted((a, b) -> Double.compare(b.lift(), a.lift()))
                .limit(6)
                .map(Result::branches)
                .toList();

        System.out.println("=== top shapes, alpha sweep ===");
        report(sweep(engine, boids, uncontrolled, commits,
                best, new double[]{0.80, 0.85, 0.90}, seedRange(20)), 30);
    }

    /**
     * Every non-increasing tail over {@link #TAIL_VALUES} that leaves room for a root
     * wide enough to be worth searching.
     * <p>
     * The root width factors cleanly out of the budget, so the tail is enumerated over
     * round numbers and the root simply takes whatever allowance is left.
     */
    private static List<int[]> enumerateShapes(int uncontrolled) {
        List<int[]> tails = new ArrayList<>();
        tails.add(new int[0]);
        buildTails(new int[MAX_TAIL_LENGTH], 0, 0, tails);

        // Diluting lengthens the commit, which makes the lookahead a smaller share of a
        // branching interval and so cheapens the deepest level.
        double k = (SEARCH_LOOKAHEAD + uncontrolled + SEARCH_DURATION)
                / (double) (uncontrolled + SEARCH_DURATION);

        List<int[]> shapes = new ArrayList<>();
        for (int[] tail : tails) {
            double cost = tailCost(tail, k);
            int root = (int) (PsyboidSearch.MAX_BUDGET / cost);
            if (root < MIN_ROOT_WIDTH) continue;
            if (tail.length > 0 && root < tail[0]) continue;   // must stay non-increasing

            int[] shape = new int[tail.length + 1];
            shape[0] = root;
            System.arraycopy(tail, 0, shape, 1, tail.length);
            shapes.add(shape);
        }
        return shapes;
    }

    private static void buildTails(int[] prefix, int length, int startValue, List<int[]> out) {
        if (length == MAX_TAIL_LENGTH) return;
        for (int i = startValue; i < TAIL_VALUES.length; i++) {
            prefix[length] = TAIL_VALUES[i];
            out.add(java.util.Arrays.copyOf(prefix, length + 1));
            buildTails(prefix, length + 1, i, out);
        }
    }

    /** The budget a tail consumes per unit of root width. */
    private static double tailCost(int[] tail, double k) {
        double total = 0;
        double partial = 1;
        for (int value : tail) {
            total += partial;
            partial *= value;
        }
        return total + partial * k;
    }

    private static long[] seedRange(int count) {
        long[] seeds = new long[count];
        for (int i = 0; i < count; i++) seeds[i] = i;
        return seeds;
    }

    private static List<Result> sweep(Engine engine, int boids, int uncontrolled, int commits,
                                      List<int[]> shapes, double[] alphas, long[] seeds) {
        List<Run> runs = new ArrayList<>();
        for (int[] branches : shapes) {
            for (double alpha : alphas) {
                for (long seed : seeds) runs.add(new Run(branches, alpha, seed));
            }
        }

        long started = System.nanoTime();
        java.util.Queue<String> labels = new java.util.concurrent.ConcurrentLinkedQueue<>();
        Map<String, List<Result>> grouped = runs.parallelStream().map(run -> {
            PsyboidSearch.Config config = new PsyboidSearch.Config(
                    run.branches(), SEARCH_LOOKAHEAD, run.alpha(),
                    uncontrolled, SEARCH_DURATION, SEARCH_SEGMENTS);

            Sim.State root = warmedRoot(engine, run.seed());
            int ticks = commits * config.splitSpacing();

            PsyboidSearch search = new PsyboidSearch(engine, config, root, run.seed());
            for (int c = 0; c < commits; c++) search.commit();

            double control = plainRun(engine, root, ticks).score;
            Sim.State canonical = search.canonical();
            // Every search result is a run that can be reconstructed later; throwing the
            // label away means paying for the search twice.
            labels.add(config.describe() + "," + run.alpha() + "," + run.seed()
                    + "," + ticks + "," + canonical.label);
            return new Result(run.branches(), config.describe(), config.budget(), run.alpha(),
                    canonical.score, control, ticks, boids, 1,
                    100.0 * (canonical.score - control) / control, 0);
        }).collect(java.util.stream.Collectors.groupingBy(r -> r.label() + "|" + r.alpha()));

        List<Result> merged = new ArrayList<>();
        for (List<Result> group : grouped.values()) {
            int n = group.size();
            double psy = 0, ctl = 0, lift = 0;
            for (Result r : group) { psy += r.psyboid(); ctl += r.control(); lift += r.lift(); }
            psy /= n; ctl /= n; lift /= n;

            double variance = 0;
            for (Result r : group) variance += (r.lift() - lift) * (r.lift() - lift);
            double sem = n > 1 ? Math.sqrt(variance / (n - 1) / n) : 0;

            Result first = group.get(0);
            merged.add(new Result(first.branches(), first.label(), first.budget(), first.alpha(),
                    psy, ctl, first.ticks(), first.boids(), n, lift, sem));
        }
        try {
            Path out = Path.of("data", "sweep_labels.csv");
            Files.createDirectories(out.getParent());
            boolean fresh = !Files.exists(out);
            try (BufferedWriter w = Files.newBufferedWriter(out,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND)) {
                if (fresh) { w.write("shape,alpha,seed,ticks,label"); w.newLine(); }
                for (String line : labels) { w.write(line); w.newLine(); }
            }
        } catch (IOException e) {
            System.out.println("could not append sweep labels: " + e);
        }

        System.out.printf("%d runs in %.1fs, %d labels logged%n",
                runs.size(), (System.nanoTime() - started) / 1e9, labels.size());
        return merged;
    }

    private static void report(List<Result> results, int limit) {
        System.out.println("branches                budget  alpha    n   psyboid  control      lift +/- sem   per boid/tick");
        results.stream()
                .sorted((a, b) -> Double.compare(b.lift(), a.lift()))
                .limit(limit)
                .forEach(r -> System.out.printf("%-23s %6.0f  %5.2f %4d  %8.1f %8.1f  %+7.1f%% +/-%4.1f   %.4f%n",
                        r.label(), r.budget(), r.alpha(), r.n(), r.psyboid(), r.control(),
                        r.lift(), r.sem(), r.psyboid() / r.boids() / r.ticks()));
        System.out.println();
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

    /** The same timeline with nobody steering it, for comparison. */
    private static Sim.State plainRun(Engine engine, Sim.State root, int ticks) {
        Sim.State s = root;
        for (int i = 0; i < ticks; i++) s = engine.tick(s);
        return s;
    }

    // ---- Dilution and budget grid ------------------------------------------

    /** Canonical ticks each run is measured over, regardless of how long a commit is. */
    private static final int GRID_TICKS = 3200;
    private static final int GRID_SEEDS = 30;

    private record GridRun(int[] branches, int uncontrolled, long seed) {}

    private record GridResult(String shape, int uncontrolled, long seed, int psyboid,
                              int ticks, double budget, long flock, long psy,
                              long controlFlock, long controlPsy, String label) {}

    /**
     * How much the psyboid's grip matters, against how much search is spent on it.
     * <p>
     * Dilution lengthens the uncontrolled gap before each override while the override
     * itself stays four seconds, so the psyboid goes from steering four ticks in five to
     * one in nine. Since a commit spans one whole override cycle, diluting also
     * lengthens the commit â€” so runs are held to a fixed number of canonical ticks
     * rather than a fixed number of commits, and the search gets correspondingly fewer
     * decisions at high dilution.
     */
    public static void dilutionGrid(PresetScenarioParameter preset) throws IOException {
        ScenarioParameter parameter = withFlockSize(preset, SWEEP_BOIDS);
        Engine engine = new Boids2DEngine(parameter);

        int[][] shapes = {{35, 4, 2}, {70, 4, 2}, {140, 4, 2}};
        int[] uncontrolledTicks = {8, 16, 32, 64, 128, 256};

        List<GridRun> runs = new ArrayList<>();
        for (int[] branches : shapes) {
            for (int uncontrolled : uncontrolledTicks) {
                for (long seed = 0; seed < GRID_SEEDS; seed++) {
                    runs.add(new GridRun(branches, uncontrolled, seed));
                }
            }
        }

        long started = System.nanoTime();
        List<GridResult> results = runs.parallelStream().map(run -> {
            int psyboid = (int) (run.seed() % SWEEP_BOIDS);
            PsyboidSearch.Config config = new PsyboidSearch.Config(
                    run.branches(), SEARCH_LOOKAHEAD, 0.85,
                    run.uncontrolled(), SEARCH_DURATION, SEARCH_SEGMENTS, psyboid);

            int commits = Math.max(1, Math.round(GRID_TICKS / (float) config.splitSpacing()));
            int ticks = commits * config.splitSpacing();

            Sim.State root = warmedRoot(engine, run.seed());
            PsyboidSearch search = new PsyboidSearch(engine, config, root, run.seed());
            for (int c = 0; c < commits; c++) search.commit();

            Sim.State canonical = search.canonical();
            Sim.State control = plainRun(engine, root, ticks);

            return new GridResult(config.describe(), run.uncontrolled(), run.seed(), psyboid,
                    ticks, config.budget(), canonical.score, canonical.boidScore[psyboid],
                    control.score, control.boidScore[psyboid], canonical.label);
        }).toList();

        Path out = Path.of("data", "canonical_" + preset.name().toLowerCase() + ".csv");
        Files.createDirectories(out.getParent());
        try (BufferedWriter w = Files.newBufferedWriter(out)) {
            w.write("shape,uncontrolled,seed,psyboid,ticks,budget,flock,psyboid_score,"
                    + "control_flock,control_psyboid,label");
            w.newLine();
            for (GridResult r : results) {
                w.write(r.shape() + "," + r.uncontrolled() + "," + r.seed() + "," + r.psyboid()
                        + "," + r.ticks() + "," + (long) r.budget() + "," + r.flock() + ","
                        + r.psy() + "," + r.controlFlock() + "," + r.controlPsy() + ","
                        + r.label());
                w.newLine();
            }
        }

        System.out.printf("%s, %d boids, %d canonical ticks, %d seeds%n",
                preset.name(), SWEEP_BOIDS, GRID_TICKS, GRID_SEEDS);
        System.out.printf("%d runs in %.0fs -> %s%n%n", runs.size(),
                (System.nanoTime() - started) / 1e9, out);
        System.out.println("shape      ctrl:unctrl  budget  commits   flock%   psyboid%   others%   psy/others");

        for (int[] branches : shapes) {
            for (int uncontrolled : uncontrolledTicks) {
                double flock = 0, psy = 0, cFlock = 0, ticks = 0, budget = 0;
                int n = 0;
                String shape = null;
                for (GridResult r : results) {
                    if (r.uncontrolled() != uncontrolled
                            || !r.shape().equals(new PsyboidSearch.Config(branches,
                            SEARCH_LOOKAHEAD, 0.85, uncontrolled, SEARCH_DURATION,
                            SEARCH_SEGMENTS).describe())) continue;
                    flock += r.flock();
                    psy += r.psy();
                    cFlock += r.controlFlock();
                    ticks += r.ticks();
                    budget = r.budget();
                    shape = r.shape();
                    n++;
                }
                double perTick = ticks / n;
                double flockPct = 100 * flock / n / SWEEP_BOIDS / perTick;
                double psyPct = 100 * psy / n / perTick;
                double othersPct = 100 * (flock - psy) / n / (SWEEP_BOIDS - 1) / perTick;

                System.out.printf("%-10s %5.1f:%-5.1f %7.0f %8.0f  %6.2f%%   %6.2f%%   %6.2f%%      %.2fx%n",
                        shape, SEARCH_DURATION / 8.0, uncontrolled / 8.0, budget,
                        perTick / (uncontrolled + SEARCH_DURATION),
                        flockPct, psyPct, othersPct, psyPct / othersPct);
            }
            System.out.printf("%-10s control flock%% %.2f%%%n%n", "",
                    100 * results.stream().filter(r -> r.shape().startsWith(branches[0] + "x"))
                            .mapToLong(GridResult::controlFlock).average().orElse(0)
                            / SWEEP_BOIDS / GRID_TICKS);
        }
    }

    // ---- Label reconstruction ----------------------------------------------

    /**
     * Checks that a canonical line can be rebuilt from its label alone, by replaying it
     * and comparing every boid's position, heading and score against the original.
     */
    public static boolean verifyReplay(PresetScenarioParameter preset, int uncontrolled,
                                       int[] branches, long seed, int commits) throws IOException {
        ScenarioParameter parameter = withFlockSize(preset, SWEEP_BOIDS);
        Engine engine = new Boids2DEngine(parameter);

        PsyboidSearch.Config config = new PsyboidSearch.Config(
                branches, SEARCH_LOOKAHEAD, 0.85,
                uncontrolled, SEARCH_DURATION, SEARCH_SEGMENTS, 3);

        Sim.State root = warmedRoot(engine, seed);
        PsyboidSearch search = new PsyboidSearch(engine, config, root, seed);
        for (int c = 0; c < commits; c++) search.commit();

        Sim.State original = search.canonical();
        Sim.State rebuilt = PsyboidSearch.replay(engine, original.label, WARMUP, original.tick);

        boolean ok = original.tick == rebuilt.tick && original.score == rebuilt.score;
        double worstPosition = 0;
        for (int i = 0; i < original.n && ok; i++) {
            worstPosition = Math.max(worstPosition,
                    Math.hypot(original.x[i] - rebuilt.x[i], original.y[i] - rebuilt.y[i]));
            if (original.h[i] != rebuilt.h[i]) ok = false;
            if (original.boidScore[i] != rebuilt.boidScore[i]) ok = false;
        }
        if (worstPosition != 0) ok = false;

        System.out.printf("  %-10s uncontrolled %3d  %-10s seed %2d  commits %3d  "
                        + "tick %5d  score %6d vs %6d  worst offset %.1e  %s%n",
                preset.name(), uncontrolled, config.describe(), seed, commits,
                original.tick, original.score, rebuilt.score, worstPosition,
                ok ? "MATCH" : "MISMATCH");
        return ok;
    }

    // ---- Score attribution -------------------------------------------------

    private record Attribution(double psyOwn, double psyOthers,
                               double ctlOwn, double ctlOthers,
                               int rank, boolean top) {}

    /**
     * Splits the excess score into the part the psyboid collects itself and the part it
     * causes the rest of the flock to collect.
     * <p>
     * The first kind is trivially visible â€” a boid that parks in the scoring zone stands
     * out at a glance. The second is the interesting kind, and the fraction of excess
     * coming from it is the dial to turn when making a scenario harder.
     */
    public static void attribution() throws IOException {
        ScenarioParameter parameter = withFlockSize(PresetScenarioParameter.HAMBURGER, SWEEP_BOIDS);
        Engine engine = new Boids2DEngine(parameter);

        List<long[]> cases = new ArrayList<>();
        for (long seed = 0; seed < 30; seed++) {
            for (int psyboid = 0; psyboid < SWEEP_BOIDS; psyboid++) {
                cases.add(new long[]{seed, psyboid});
            }
        }

        List<Attribution> results = cases.parallelStream().map(c -> {
            long seed = c[0];
            int psyboid = (int) c[1];

            PsyboidSearch.Config config = new PsyboidSearch.Config(
                    new int[]{256, 1}, SEARCH_LOOKAHEAD, 0.85,
                    SEARCH_MAX_DELAY, SEARCH_DURATION, SEARCH_SEGMENTS, psyboid);

            Sim.State root = warmedRoot(engine, seed);
            int ticks = SEARCH_COMMITS * config.splitSpacing();

            PsyboidSearch search = new PsyboidSearch(engine, config, root, seed);
            for (int i = 0; i < SEARCH_COMMITS; i++) search.commit();

            long[] psy = search.canonical().boidScore;
            long[] ctl = plainRun(engine, root, ticks).boidScore;

            long psyOthers = 0, ctlOthers = 0;
            int better = 0;
            for (int i = 0; i < psy.length; i++) {
                if (i == psyboid) continue;
                psyOthers += psy[i];
                ctlOthers += ctl[i];
                if (psy[i] > psy[psyboid]) better++;
            }
            return new Attribution(psy[psyboid], psyOthers, ctl[psyboid], ctlOthers,
                    better + 1, better == 0);
        }).toList();

        int n = results.size();
        double psyOwn = 0, psyOthers = 0, ctlOwn = 0, ctlOthers = 0, rank = 0;
        int top = 0;
        for (Attribution a : results) {
            psyOwn += a.psyOwn();
            psyOthers += a.psyOthers();
            ctlOwn += a.ctlOwn();
            ctlOthers += a.ctlOthers();
            rank += a.rank();
            if (a.top()) top++;
        }
        psyOwn /= n; psyOthers /= n; ctlOwn /= n; ctlOthers /= n; rank /= n;

        int ticks = SEARCH_COMMITS * (SEARCH_MAX_DELAY + SEARCH_DURATION);
        int others = SWEEP_BOIDS - 1;
        double excessOwn = psyOwn - ctlOwn;
        double excessOthers = psyOthers - ctlOthers;
        double excess = excessOwn + excessOthers;

        System.out.printf("%d runs: every boid as psyboid across 30 seeds, %d ticks each%n%n", n, ticks);
        System.out.println("                        psyboid   others(each)      flock");
        System.out.printf("control  score/tick     %8.4f       %8.4f   %8.4f%n",
                ctlOwn / ticks, ctlOthers / others / ticks, (ctlOwn + ctlOthers) / ticks);
        System.out.printf("steered  score/tick     %8.4f       %8.4f   %8.4f%n",
                psyOwn / ticks, psyOthers / others / ticks, (psyOwn + psyOthers) / ticks);
        System.out.printf("excess                  %+8.1f       %+8.1f   %+8.1f%n",
                excessOwn, excessOthers / others, excess);
        System.out.printf("share of excess          %6.1f%%         %6.1f%%%n%n",
                100 * excessOwn / excess, 100 * excessOthers / excess);

        System.out.printf("psyboid scores %.2fx the average of the others%n",
                psyOwn / (psyOthers / others));
        System.out.printf("psyboid is the single highest scorer in %.1f%% of runs (chance is %.1f%%)%n",
                100.0 * top / n, 100.0 / SWEEP_BOIDS);
        System.out.printf("mean rank by score: %.2f of %d%n", rank, SWEEP_BOIDS);
    }

    // ---- Detective scenario ------------------------------------------------

    private static final int GRID_COLUMNS = 4;
    private static final int GRID_ROWS = 5;
    private static final int GRID_SCALE = 3;
    private static final int DETECTIVE_COMMITS = 40;

    /**
     * One run with a randomly chosen psyboid, rendered as a grid of snapshots for
     * someone to inspect without being told who it is. The answer goes to a file rather
     * than to the console so it can be checked afterwards rather than spoiled.
     */
    public static void detective(long seed) throws IOException {
        Random meta = new Random(seed);
        int psyboid = meta.nextInt(SWEEP_BOIDS);

        ScenarioParameter parameter = withFlockSize(PresetScenarioParameter.HAMBURGER, SWEEP_BOIDS);
        Engine engine = new Boids2DEngine(parameter);
        Renderer renderer = new Boids2DRenderer(parameter.mapPath(), GRID_SCALE);

        PsyboidSearch.Config config = new PsyboidSearch.Config(
                new int[]{256, 1}, SEARCH_LOOKAHEAD, 0.85,
                SEARCH_MAX_DELAY, SEARCH_DURATION, SEARCH_SEGMENTS, psyboid);

        Sim.State root = warmedRoot(engine, seed);
        PsyboidSearch search = new PsyboidSearch(engine, config, root, seed);

        List<Sim.State> timeline = new ArrayList<>();
        for (int c = 0; c < DETECTIVE_COMMITS; c++) {
            search.commit();
            timeline.add(search.canonical());
        }

        int panels = GRID_COLUMNS * GRID_ROWS;
        List<Integer> chosen = new ArrayList<>();
        for (int i = 0; i < timeline.size(); i++) chosen.add(i);
        java.util.Collections.shuffle(chosen, meta);
        chosen = new ArrayList<>(chosen.subList(0, panels));
        java.util.Collections.sort(chosen);

        List<BufferedImage> rows = new ArrayList<>();
        for (int r = 0; r < GRID_ROWS; r++) {
            List<BufferedImage> row = new ArrayList<>();
            for (int c = 0; c < GRID_COLUMNS; c++) {
                row.add(renderer.render(timeline.get(chosen.get(r * GRID_COLUMNS + c))));
            }
            rows.add(Sim.stitchHorizontal(row));
        }

        Path dir = Path.of("render", "detective");
        Files.createDirectories(dir);
        Path grid = dir.resolve("hamburger_seed" + seed + ".png");
        javax.imageio.ImageIO.write(Sim.stitchVertical(rows), "png", grid.toFile());

        try (BufferedWriter w = Files.newBufferedWriter(dir.resolve("answer_seed" + seed + ".txt"))) {
            w.write("psyboid index: " + psyboid);
            w.newLine();
            w.write("colour: " + hex(psyboid, SWEEP_BOIDS));
            w.newLine();
        }

        System.out.println("boid  colour");
        for (int i = 0; i < SWEEP_BOIDS; i++) {
            System.out.printf("%4d  %s%n", i, hex(i, SWEEP_BOIDS));
        }
        System.out.println();
        System.out.println("grid   -> " + grid);
        System.out.println("answer -> " + dir.resolve("answer_seed" + seed + ".txt"));
    }

    private static String hex(int index, int n) {
        return String.format("#%06X", Boids2DRenderer.colorOf(index, n).getRGB() & 0xFFFFFF);
    }

    /** Replays a saved canonical line and draws the psyboid's path over the play area. */
    public static void trailFromLabel(PresetScenarioParameter preset, String label,
                                      long targetTick, Path out) throws IOException {
        Sim sim = new Sim(withFlockSize(preset, SWEEP_BOIDS));
        PsyboidTrailLogger logger = new PsyboidTrailLogger(out, 2);
        sim.register(logger, PsyboidTrailLogger.TRIGGERS);
        sim.replay(label, WARMUP, targetTick);
        System.out.printf("psyboid %d, %d ticks -> %s%n", logger.psyboid(), targetTick - WARMUP, out);
    }

    /** A plain run with no psyboid, with every boid's path traced. */
    public static void flockTrail(PresetScenarioParameter preset, long seed,
                                  int ticks, Path out) throws IOException {
        Sim sim = new Sim(preset);
        sim.register(new FlockTrailLogger(out, 2, 1.2f), FlockTrailLogger.TRIGGERS);
        // A label with no override sections replays as an ordinary unsteered run.
        sim.replay("seed" + seed, WARMUP, WARMUP + ticks);
        System.out.printf("%s seed %d, %d boids, %d ticks -> %s%n",
                preset.name(), seed, preset.flockSize(), ticks, out);
    }

    /**
     * One timeline per dilution level, all from the same seed and psyboid, searched and
     * then replayed with every path traced.
     */
    public static void wormwayPsyboid(long seed, int psyboid, int ticks) throws IOException {
        PresetScenarioParameter preset = PresetScenarioParameter.WORMWAY;
        Engine engine = new Boids2DEngine(preset);
        int boids = preset.flockSize();

        System.out.println("uncontrolled  commits   flock%   psyboid%   others%   control flock%");
        for (int uncontrolled : new int[]{8, 32, 256}) {
            PsyboidSearch.Config config = new PsyboidSearch.Config(
                    new int[]{35, 4, 2}, SEARCH_LOOKAHEAD, 0.85,
                    uncontrolled, SEARCH_DURATION, SEARCH_SEGMENTS, psyboid);

            int commits = Math.max(1, ticks / config.splitSpacing());
            int span = commits * config.splitSpacing();

            Sim.State root = warmedRoot(engine, seed);
            PsyboidSearch search = new PsyboidSearch(engine, config, root, seed);
            for (int c = 0; c < commits; c++) search.commit();

            Sim.State canonical = search.canonical();
            Sim.State control = plainRun(engine, root, span);

            double psy = 100.0 * canonical.boidScore[psyboid] / span;
            double others = 100.0 * (canonical.score - canonical.boidScore[psyboid])
                    / (boids - 1) / span;
            System.out.printf("%12d %8d  %6.2f%%   %6.2f%%   %6.2f%%   %11.2f%%%n",
                    uncontrolled, commits, 100.0 * canonical.score / boids / span,
                    psy, others, 100.0 * control.score / boids / span);

            Sim sim = new Sim(preset);
            sim.register(new FlockTrailLogger(
                            Path.of("render", "trail", "wormway_psy_u" + uncontrolled + ".png"),
                            2, 1.0f),
                    FlockTrailLogger.TRIGGERS);
            sim.replay(canonical.label, WARMUP, WARMUP + span);
        }
    }

    /**
     * One deep, expensive run, kept and dissected rather than aggregated away: the
     * canonical timeline is searched once, then replayed to produce the picture and the
     * score series.
     */
    public static void longRun(PresetScenarioParameter preset, int[] branches, int lookahead,
                               double alpha, int commits, long seed, int psyboid, String tag)
            throws IOException {
        int boids = preset.flockSize();
        int uncontrolled = 32;

        Engine engine = new Boids2DEngine(preset);
        PsyboidSearch.Config config = new PsyboidSearch.Config(
                branches, lookahead, alpha, uncontrolled, SEARCH_DURATION, SEARCH_SEGMENTS, psyboid);

        int span = commits * config.splitSpacing();
        int planned = branches.length * config.splitSpacing();
        System.out.printf("%n%s  %s  shape %s  lookahead %d  alpha %.2f  budget %.0f%n",
                preset.name(), tag, config.describe(), lookahead, alpha, config.budget());
        System.out.printf("  horizon: %d planned + %d coasting = %d ticks (%.0fs)%n",
                planned, lookahead, planned + lookahead, (planned + lookahead) / 8.0);

        long started = System.nanoTime();
        Sim.State root = warmedRoot(engine, seed);
        PsyboidSearch search = new PsyboidSearch(engine, config, root, seed);
        for (int c = 0; c < commits; c++) {
            search.commit();
            if ((c + 1) % 25 == 0 || c + 1 == commits) {
                double secs = (System.nanoTime() - started) / 1e9;
                System.out.printf("  %d/%d commits, %.0fs (%.2fs each)%n",
                        c + 1, commits, secs, secs / (c + 1));
            }
        }

        Sim.State canonical = search.canonical();
        Sim.State control = plainRun(engine, root, span);

        String stem = preset.name().toLowerCase() + "_" + tag;
        Path dir = Path.of("render", "longrun");
        Sim sim = new Sim(preset);
        sim.register(new FlockTrailLogger(dir.resolve(stem + "_paths.png"), 2, 0.8f),
                FlockTrailLogger.TRIGGERS);
        sim.register(new ScoreSeriesLogger(
                Path.of("data", stem + "_scores.csv"), Params.TURNS / 8),
                ScoreSeriesLogger.TRIGGERS);
        sim.register(new FrameGridLogger(dir.resolve(stem + "_grid.png"), span / 20, 4, 1),
                FrameGridLogger.TRIGGERS);
        sim.register(new TrailGridLogger(dir.resolve(stem + "_sequence.png"), span / 16, 4, 1, 0.8f),
                TrailGridLogger.TRIGGERS);
        sim.replay(canonical.label, WARMUP, WARMUP + span);

        // The label is the whole run; keeping it means never paying for this search twice.
        Files.writeString(Path.of("data", stem + "_label.txt"), canonical.label);

        System.out.printf("%nscoring occupancy over %d ticks%n", span);
        System.out.printf("  flock          %6.2f%%   (control %.2f%%)%n",
                100.0 * canonical.score / boids / span, 100.0 * control.score / boids / span);
        System.out.printf("  psyboid (%d)    %6.2f%%   (unsteered %.2f%%)%n",
                psyboid, 100.0 * canonical.boidScore[psyboid] / span,
                100.0 * control.boidScore[psyboid] / span);
        System.out.printf("  others (each)  %6.2f%%%n",
                100.0 * (canonical.score - canonical.boidScore[psyboid]) / (boids - 1) / span);

        int better = 0;
        for (int i = 0; i < boids; i++) {
            if (canonical.boidScore[i] > canonical.boidScore[psyboid]) better++;
        }
        System.out.printf("  psyboid rank by score: %d of %d%n", better + 1, boids);
        System.out.println("  -> " + dir.resolve(stem + "_paths.png") + " , data/" + stem + "_scores.csv");
    }

    /**
     * How much search does a psyboid on this map actually need?
     * <p>
     * Holds the override shape and the lookahead fixed and varies only the root width of
     * an {@code Nx1x1x1} tree, so the whole curve is a function of one number. Every width
     * runs the same seeds with the same boid steered, which makes the comparison paired:
     * the variation between seeds on a map this sparse dwarfs the variation between
     * widths, and only the paired difference can see through it.
     */
    public static void computeCurve(PresetScenarioParameter preset, int boids, int[] widths,
                                    int commits, long[] seeds, int maxDelay, int duration,
                                    int segments, int lookahead, double alpha) throws IOException {
        Engine engine = new Boids2DEngine(withFlockSize(preset, boids));
        SearchJournal.context(preset.name() + " n" + boids + " computeCurve");
        int spacing = maxDelay + duration;
        int span = commits * spacing;

        System.out.printf("%s  %d boids  override %d segment(s), %d ticks (%.0fs) "
                        + "in a %d tick (%.0fs) window, spacing %d%n",
                preset.name(), boids, segments, duration, duration / 8.0,
                maxDelay, maxDelay / 8.0, spacing);
        System.out.printf("lookahead %d (%.0fs), alpha %.2f, %d commits = %d ticks, %d seeds%n%n",
                lookahead, lookahead / 8.0, alpha, commits, span, seeds.length);

        // Control is independent of the search, so it is computed once per seed and reused.
        double[] controlFlock = new double[seeds.length];
        double[] controlPsy = new double[seeds.length];
        for (int s = 0; s < seeds.length; s++) {
            Sim.State root = warmedRoot(engine, seeds[s]);
            Sim.State plain = plainRun(engine, root, span);
            controlFlock[s] = 100.0 * plain.score / boids / span;
            controlPsy[s] = 100.0 * plain.boidScore[(int) (seeds[s] % boids)] / span;
        }
        System.out.printf("control: flock %.3f%%   steered-boid-unsteered %.3f%%%n%n",
                mean(controlFlock), mean(controlPsy));

        System.out.println("    N   budget    flock   psyboid    others   psy/others  "
                + "mean rank    secs");

        double[] baseline = null;
        for (int width : widths) {
            int[] branches = new int[]{width, 1, 1, 1};
            double[] flock = new double[seeds.length];
            double[] psy = new double[seeds.length];
            double[] others = new double[seeds.length];
            double[] rank = new double[seeds.length];

            long started = System.nanoTime();
            for (int s = 0; s < seeds.length; s++) {
                int psyboid = (int) (seeds[s] % boids);
                PsyboidSearch.Config config = new PsyboidSearch.Config(
                        branches, lookahead, alpha, maxDelay, duration, segments, psyboid);

                Sim.State root = warmedRoot(engine, seeds[s]);
                PsyboidSearch search = new PsyboidSearch(engine, config, root, seeds[s]);
                for (int c = 0; c < commits; c++) search.commit();
                Sim.State canonical = search.canonical();

                flock[s] = 100.0 * canonical.score / boids / span;
                psy[s] = 100.0 * canonical.boidScore[psyboid] / span;
                others[s] = 100.0 * (canonical.score - canonical.boidScore[psyboid])
                        / (boids - 1) / span;
                rank[s] = rankOf(canonical, psyboid);
            }
            double secs = (System.nanoTime() - started) / 1e9;
            if (baseline == null) baseline = psy.clone();

            System.out.printf("%5d %8.0f  %6.2f%%  %6.2f%%  %6.2f%%   %8.2fx  %7.1f/%d  %6.1f%n",
                    width, new PsyboidSearch.Config(branches, lookahead, alpha,
                            maxDelay, duration, segments, 0).budget(),
                    mean(flock), mean(psy), mean(others), mean(psy) / mean(others),
                    mean(rank), boids, secs);
        }
    }

    /**
     * The same measurement as {@link #computeCurve}, but with the override choices
     * enumerated instead of sampled, so the result can be read against that curve.
     */
    public static void planRun(PresetScenarioParameter preset, OverridePlan plan, int depth,
                               int lookahead, double alpha, int commits, long[] seeds)
            throws IOException {
        Engine engine = new Boids2DEngine(preset);
        int boids = preset.flockSize();

        PsyboidSearch.Config shape = new PsyboidSearch.Config(depth, lookahead, alpha, 0, plan);
        int spacing = shape.splitSpacing();
        int span = commits * spacing;

        System.out.printf("%s  %d boids  enumerated plan: %s%n", preset.name(), boids,
                plan.describe());
        System.out.printf("  delays [0, %d) step %d  durations [%d, %d] step %d  directions %s%n",
                plan.interval(), plan.delayStep(), plan.durationLo(), plan.durationHi(),
                plan.durationStep(), java.util.Arrays.toString(plan.directions()));
        System.out.printf("  shape %s  lookahead %d (%.0fs)  alpha %.2f  budget %.0f%n",
                shape.describe(), lookahead, lookahead / 8.0, alpha, shape.budget());
        System.out.printf("  commit interval %d, %d commits = %d ticks, %d seeds%n%n",
                spacing, commits, span, seeds.length);

        double[] flock = new double[seeds.length];
        double[] psy = new double[seeds.length];
        double[] others = new double[seeds.length];
        double[] cFlock = new double[seeds.length];
        double[] cPsy = new double[seeds.length];

        long started = System.nanoTime();
        for (int s = 0; s < seeds.length; s++) {
            int psyboid = (int) (seeds[s] % boids);
            PsyboidSearch.Config config =
                    new PsyboidSearch.Config(depth, lookahead, alpha, psyboid, plan);

            Sim.State root = warmedRoot(engine, seeds[s]);
            Sim.State plain = plainRun(engine, root, span);
            cFlock[s] = 100.0 * plain.score / boids / span;
            cPsy[s] = 100.0 * plain.boidScore[psyboid] / span;

            PsyboidSearch search = new PsyboidSearch(engine, config, root, seeds[s]);
            for (int c = 0; c < commits; c++) search.commit();
            Sim.State canonical = search.canonical();

            flock[s] = 100.0 * canonical.score / boids / span;
            psy[s] = 100.0 * canonical.boidScore[psyboid] / span;
            others[s] = 100.0 * (canonical.score - canonical.boidScore[psyboid])
                    / (boids - 1) / span;
        }
        double secs = (System.nanoTime() - started) / 1e9;

        System.out.println("            mean      s.e.");
        System.out.printf("flock     %7.3f%%   %6.3f%n", mean(flock), stderr(flock));
        System.out.printf("psyboid   %7.3f%%   %6.3f%n", mean(psy), stderr(psy));
        System.out.printf("others    %7.3f%%   %6.3f%n", mean(others), stderr(others));
        System.out.printf("control   %7.3f%%   %6.3f   (steered boid unsteered %.3f%%)%n",
                mean(cFlock), stderr(cFlock), mean(cPsy));
        System.out.printf("%npsy/others %.2fx   total %.0fs (%.1fs per seed)%n",
                mean(psy) / mean(others), secs, secs / seeds.length);

        System.out.print("\nper seed psyboid: ");
        for (double v : psy) System.out.printf("%.2f ", v);
        System.out.println();
    }

    /**
     * Does an unsteered flock ever score on this map?
     * <p>
     * A map whose control rate is exactly zero makes any score at all evidence of
     * interference, which removes a whole class of false positive. Reports the warm-up
     * separately because it is unsteered too: score earned there is discarded by the
     * reset, but a map where it happens has not really achieved the property.
     */
    public static void controlCheck(PresetScenarioParameter preset, int seedCount,
                                    int warmup, int ticks) throws IOException {
        Engine engine = new Boids2DEngine(preset);
        int boids = preset.flockSize();

        int scoringSeeds = 0, warmupSeeds = 0;
        long worst = 0, total = 0;
        List<Long> offenders = new ArrayList<>();

        for (long seed = 0; seed < seedCount; seed++) {
            Sim.State s = engine.init(seed);
            while (s.tick < warmup) s = engine.tick(s);
            if (s.score > 0) warmupSeeds++;

            s = new Sim.State(s.n, s.x, s.y, s.h, s.tick, 0L, new long[s.n], s.label);
            s = plainRun(engine, s, ticks);

            total += s.score;
            if (s.score > 0) {
                scoringSeeds++;
                worst = Math.max(worst, s.score);
                if (offenders.size() < 25) offenders.add(seed);
            }
        }

        System.out.printf("%n%s control: %d seeds, warm-up %d then ticks %d-%d, %d boids "
                        + "(%,d boid-ticks measured)%n",
                preset.name(), seedCount, warmup, warmup, warmup + ticks, boids,
                (long) seedCount * ticks * boids);
        System.out.printf("  seeds scoring in window     : %d / %d  (%.1f%%)%n",
                scoringSeeds, seedCount, 100.0 * scoringSeeds / seedCount);
        System.out.printf("  seeds scoring during warm-up: %d / %d%n", warmupSeeds, seedCount);
        System.out.printf("  mean occupancy              : %.4f%%%n",
                100.0 * total / boids / ticks / seedCount);
        System.out.printf("  worst single seed           : %d boid-ticks (%.4f%%)%n",
                worst, 100.0 * worst / boids / ticks);
        if (!offenders.isEmpty()) System.out.println("  first offenders: " + offenders);
    }

    private static double mean(double[] v) {
        double t = 0;
        for (double d : v) t += d;
        return t / v.length;
    }

    private static double stderr(double[] v) {
        if (v.length < 2) return 0;
        double m = mean(v), var = 0;
        for (double d : v) var += (d - m) * (d - m);
        return Math.sqrt(var / (v.length - 1) / v.length);
    }

    /**
     * One searched timeline sampled at random times, numbered in order and stitched into
     * a grid.
     * <p>
     * Draws are uniform and independent over the whole window rather than one per commit,
     * so the frames clump: some commits get looked at twice and others not at all. That is
     * a property of the sampling, not a defect, and it is part of what a photos-to-commits
     * ratio has to account for.
     * <p>
     * Nothing marks the psyboid. Frames are ordinary renders, so the grid is case
     * material rather than an answer key.
     */
    public static void photoGrid(PresetScenarioParameter preset, int boids, int[] branches,
                                 int lookahead, double alpha, int maxDelay, int duration,
                                 int segments, int commits, int warmup, long seed,
                                 int fromCommit, int spanCommits, int photos, int columns,
                                 int scale, long pickSeed, Path dir) throws IOException {
        ScenarioParameter scenario = withFlockSize(preset, boids);
        Engine engine = new Boids2DEngine(scenario);
        Boids2DRenderer renderer = new Boids2DRenderer(scenario.mapPath(), scale);

        Path build = dir.resolve(".build");
        Files.createDirectories(build);
        SearchJournal.context(preset.name() + " n" + boids + " photoGrid " + dir);

        Random pick = new Random(pickSeed);
        int psyboid = pick.nextInt(boids);

        PsyboidSearch.Config config = new PsyboidSearch.Config(
                branches, lookahead, alpha, maxDelay, duration, segments, psyboid);
        int spacing = config.splitSpacing();
        int span = commits * spacing;

        System.out.printf("%s  %d boids  r=%.0f  seed %d%n",
                preset.name(), boids, scenario.turningRadius(), seed);
        System.out.printf("  override: %d segments, %d ticks (%.0fs) in a %d tick (%.0fs) "
                        + "window, commit interval %d (%.0fs)%n",
                segments, duration, duration / 8.0, maxDelay, maxDelay / 8.0,
                spacing, spacing / 8.0);
        System.out.printf("  shape %s  lookahead %d (%.0fs)  alpha %.2f  budget %.0f%n",
                config.describe(), lookahead, lookahead / 8.0, alpha, config.budget());
        System.out.printf("  warm-up %d, %d commits = %d ticks%n", warmup, commits, span);

        long started = System.nanoTime();
        Sim.State root = warmedRoot(engine, seed, warmup);
        PsyboidSearch search = new PsyboidSearch(engine, config, root, seed);
        for (int c = 0; c < commits; c++) search.commit();
        Sim.State canonical = search.canonical();
        Sim.State control = plainRun(engine, root, span);

        int windowFrom = warmup + (fromCommit - 1) * spacing;
        int windowTo = windowFrom + spanCommits * spacing;

        // Without replacement: two draws landing on the same tick would ship the same
        // image twice, which costs a frame and tells a reader nothing.
        java.util.TreeSet<Integer> drawn = new java.util.TreeSet<>();
        while (drawn.size() < photos) {
            drawn.add(windowFrom + pick.nextInt(windowTo - windowFrom));
        }
        int[] ticks = new int[photos];
        int at = 0;
        for (int t : drawn) ticks[at++] = t;

        List<BufferedImage> frames = new ArrayList<>();
        for (int i = 0; i < photos; i++) {
            Sim.State frame = PsyboidSearch.replay(engine, canonical.label, warmup, ticks[i]);
            BufferedImage img = renderer.render(frame);
            label(img, String.valueOf(i + 1));
            frames.add(img);
            ImageIO.write(img, "png",
                    dir.resolve(String.format("photo_%02d.png", i + 1)).toFile());
        }

        List<BufferedImage> rows = new ArrayList<>();
        for (int i = 0; i < frames.size(); i += columns) {
            rows.add(Sim.stitchHorizontal(frames.subList(i, Math.min(i + columns, frames.size()))));
        }
        ImageIO.write(Sim.stitchVertical(rows), "png", dir.resolve("grid.png").toFile());
        double secs = (System.nanoTime() - started) / 1e9;

        double flock = 100.0 * canonical.score / boids / span;
        double ctl = 100.0 * control.score / boids / span;
        System.out.printf("%n  %d photos, commits %d-%d, ticks %d-%d, photos:commits = %d:%d%n",
                photos, fromCommit, fromCommit + spanCommits - 1, ticks[0], ticks[photos - 1],
                photos, spanCommits);
        int[] perCommit = new int[spanCommits];
        for (int t : ticks) perCommit[(t - windowFrom) / spacing]++;
        System.out.printf("  photos per commit: %s%n", Arrays.toString(perCommit));

        // Only the excess reaches the console. How the excess was earned â€” the psyboid's
        // own share against the rest of the flock's, and its rank â€” is exactly what a
        // reader is meant to infer from the pictures, so it goes to the build file only.
        System.out.printf("  EXCESS %.2f%% (%.2fx control), %.0fs%n",
                flock - ctl, flock / ctl, secs);

        StringBuilder notes = new StringBuilder();
        notes.append(String.format("psyboid: boid %d (%s)%n", psyboid, hex(psyboid, boids)));
        notes.append(String.format("seed %d, %d boids, warm-up %d, commit interval %d, %d commits%n",
                seed, boids, warmup, spacing, commits));
        notes.append(String.format("photos at ticks: %s%n", Arrays.toString(ticks)));
        notes.append(String.format("flock %.2f%%  psyboid %.2f%%  control %.2f%%  excess %.2f%%  rank %d/%d%n",
                flock, 100.0 * canonical.boidScore[psyboid] / span, ctl, flock - ctl,
                rankOf(canonical, psyboid), boids));
        Files.writeString(build.resolve("notes.txt"), notes.toString());
        Files.writeString(build.resolve("label.txt"), canonical.label);
        System.out.println("  -> " + dir);
    }

    /**
     * What a reader could actually have seen: for every photographed instant, who was in
     * the scoring zone and how the psyboid sat relative to the flock.
     * <p>
     * A set of photographs can only carry the evidence present at the instants sampled.
     * If the psyboid is inside the zone in none of them, "find who is scoring" was never
     * available however much excess the run earned overall.
     */
    public static void photoAnalysis(PresetScenarioParameter preset, int boids, String label,
                                     int warmup, int[] ticks, int psyboid) throws IOException {
        ScenarioParameter scenario = withFlockSize(preset, boids);
        Engine engine = new Boids2DEngine(scenario);
        NavMap map = NavMapBuilder.buildFromPng(scenario.mapPath(),
                Math.round(scenario.turningRadius()));

        System.out.printf("%n  tick   inzone  psy?   psy-dist  flock-dist   psy-head-dev%n");
        int psyInZone = 0, aloneInZone = 0;
        double distSum = 0, devSum = 0;

        for (int tick : ticks) {
            Sim.State s = PsyboidSearch.replay(engine, label, warmup, tick);

            int inZone = 0;
            boolean psyIn = false;
            for (int i = 0; i < s.n; i++) {
                if (map.score(s.x[i], s.y[i]) > 0) {
                    inZone++;
                    if (i == psyboid) psyIn = true;
                }
            }
            if (psyIn) psyInZone++;
            if (psyIn && inZone == 1) aloneInZone++;

            double cx = 0, cy = 0;
            for (int i = 0; i < s.n; i++) { cx += s.x[i]; cy += s.y[i]; }
            cx /= s.n; cy /= s.n;

            double psyDist = Math.hypot(s.x[psyboid] - cx, s.y[psyboid] - cy);
            double others = 0;
            for (int i = 0; i < s.n; i++) {
                if (i != psyboid) others += Math.hypot(s.x[i] - cx, s.y[i] - cy);
            }
            others /= (s.n - 1);

            // Mean heading as a unit vector, so opposing headings cancel rather than
            // averaging to something neither boid is flying.
            double hx = 0, hy = 0;
            for (int i = 0; i < s.n; i++) {
                if (i == psyboid) continue;
                hx += Params.COS[s.h[i]];
                hy += Params.SIN[s.h[i]];
            }
            double dev = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1,
                    (Params.COS[s.h[psyboid]] * hx + Params.SIN[s.h[psyboid]] * hy)
                            / Math.hypot(hx, hy)))));

            distSum += psyDist / others;
            devSum += dev;
            System.out.printf("  %5d   %4d    %-4s  %8.1f  %10.1f   %10.0f%n",
                    tick, inZone, psyIn ? "YES" : "-", psyDist, others, dev);
        }

        System.out.printf("%n  psyboid in zone in %d of %d photos (%.0f%%), alone in %d%n",
                psyInZone, ticks.length, 100.0 * psyInZone / ticks.length, aloneInZone);
        System.out.printf("  mean psyboid distance from centroid: %.2fx the flock's%n",
                distSum / ticks.length);
        System.out.printf("  mean heading deviation from flock: %.0f degrees%n",
                devSum / ticks.length);
    }

    /**
     * Suspicion scores per boid, computed from frames alone.
     * <p>
     * Deliberately symmetric: for every boid the reference flock is the <em>other</em>
     * n-1, so no boid is measured against a group it belongs to. Measuring one candidate
     * against "the flock including itself" would flatter whichever boid sits nearest the
     * middle and would not be a test a reader could actually apply.
     * <p>
     * Per frame each metric is turned into a rank rather than used raw, so a single frame
     * where the flock happens to be strung out cannot dominate the sum.
     *
     * @param mode 0 distance from the others' centroid, 1 heading deviation, 2 both
     */
    private static double[] suspicion(List<Sim.State> frames, int n, int mode) {
        double[] total = new double[n];
        for (Sim.State s : frames) {
            double[] dist = new double[n];
            double[] dev = new double[n];
            for (int i = 0; i < n; i++) {
                double cx = 0, cy = 0, hx = 0, hy = 0;
                for (int j = 0; j < n; j++) {
                    if (j == i) continue;
                    cx += s.x[j];
                    cy += s.y[j];
                    hx += Params.COS[s.h[j]];
                    hy += Params.SIN[s.h[j]];
                }
                cx /= (n - 1);
                cy /= (n - 1);
                dist[i] = Math.hypot(s.x[i] - cx, s.y[i] - cy);

                double m = Math.hypot(hx, hy);
                double dot = (Params.COS[s.h[i]] * hx + Params.SIN[s.h[i]] * hy) / (m == 0 ? 1 : m);
                dev[i] = m == 0 ? 0 : Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, dot))));
            }
            if (mode == 0 || mode == 2) addRanks(total, dist, n);
            if (mode == 1 || mode == 2) addRanks(total, dev, n);
        }
        return total;
    }

    /** Adds each boid's rank within {@code v}: 1 for the smallest, n for the largest. */
    private static void addRanks(double[] total, double[] v, int n) {
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Double.compare(v[a], v[b]));
        for (int r = 0; r < n; r++) total[order[r]] += r + 1;
    }

    /** Where {@code boid} sits in the suspicion order, 1 being the prime suspect. */
    private static int suspicionRank(double[] score, int boid) {
        int above = 0;
        for (double v : score) if (v > score[boid]) above++;
        return above + 1;
    }

    /**
     * Generates runs under one setting and tries to solve each from its frames alone.
     * <p>
     * The detector never sees which boid was steered; it gets the same frames a reader
     * would. Reported per metric, because a clue that only works combined is a different
     * claim from one that works on its own.
     */
    public static void clueTrial(String tag, PresetScenarioParameter preset, int boids,
                                 int[] branches, int lookahead, double alpha, int maxDelay,
                                 int duration, int segments, int commits, int warmup,
                                 long[] seeds, int photos, long pickSeed) throws IOException {
        ScenarioParameter scenario = withFlockSize(preset, boids);
        Engine engine = new Boids2DEngine(scenario);
        Random pick = new Random(pickSeed);
        SearchJournal.context(preset.name() + " n" + boids + " clueTrial " + tag);

        int[] hits = new int[3];
        double[] rankSum = new double[3];
        double excessSum = 0;
        StringBuilder perSeed = new StringBuilder();

        long started = System.nanoTime();
        for (long seed : seeds) {
            int psyboid = pick.nextInt(boids);
            PsyboidSearch.Config config = new PsyboidSearch.Config(
                    branches, lookahead, alpha, maxDelay, duration, segments, psyboid);
            int spacing = config.splitSpacing();
            int span = commits * spacing;

            Sim.State root = warmedRoot(engine, seed, warmup);
            PsyboidSearch search = new PsyboidSearch(engine, config, root, seed);
            for (int c = 0; c < commits; c++) search.commit();
            Sim.State canonical = search.canonical();
            Sim.State control = plainRun(engine, root, span);
            excessSum += 100.0 * (canonical.score - control.score) / boids / span;

            java.util.TreeSet<Integer> drawn = new java.util.TreeSet<>();
            while (drawn.size() < photos) drawn.add(warmup + pick.nextInt(span));

            List<Sim.State> frames = new ArrayList<>();
            for (int t : drawn) frames.add(PsyboidSearch.replay(engine, canonical.label, warmup, t));

            StringBuilder line = new StringBuilder(String.format("    seed %-4d psy %-2d ", seed, psyboid));
            for (int mode = 0; mode < 3; mode++) {
                double[] score = suspicion(frames, boids, mode);
                int r = suspicionRank(score, psyboid);
                if (r == 1) hits[mode]++;
                rankSum[mode] += r;
                line.append(String.format("  %s%d/%d", r == 1 ? "HIT " : "    ", r, boids));
            }
            perSeed.append(line).append('\n');
        }
        double secs = (System.nanoTime() - started) / 1e9;

        System.out.printf("%n=== %s ===%n", tag);
        System.out.printf("  %d boids, commit interval %d (%.0fs), max delay %d (%.0fs), "
                        + "%d commits, %d photos%n",
                boids, maxDelay + duration, (maxDelay + duration) / 8.0, maxDelay, maxDelay / 8.0,
                commits, photos);
        System.out.printf("  mean excess %.2f%%, %d seeds, %.0fs%n%n",
                excessSum / seeds.length, seeds.length, secs);
        System.out.print(perSeed);
        String[] names = {"distance only ", "heading only  ", "both combined "};
        System.out.println();
        for (int mode = 0; mode < 3; mode++) {
            System.out.printf("  %s  %2d/%d correct (%3.0f%%)   mean psyboid rank %.2f of %d%n",
                    names[mode], hits[mode], seeds.length,
                    100.0 * hits[mode] / seeds.length, rankSum[mode] / seeds.length, boids);
        }
    }

    /**
     * A first look at an unfamiliar map: excess score across dilution, flock size and
     * search budget, all at a fixed measured span.
     * <p>
     * Every cell runs the same number of ticks, so a psyboid acting a fifth of the time and
     * one acting most of the time are compared over the same stretch of simulation rather
     * than the same number of decisions. Control is measured per cell from the same warmed
     * root, which is what makes the excess column meaningful â€” the baseline moves with
     * flock size and seed, and quoting flock occupancy alone would hide that.
     */
    public static void landscape(PresetScenarioParameter preset, int[] boidCounts,
                                 int[][] shapes, String[] shapeNames, int[] maxDelays,
                                 int duration, int segments, int lookahead, double alpha,
                                 int span, int warmup, long[] seeds) throws IOException {
        System.out.printf("%s: %d flock sizes x %d shapes x %d dilutions, "
                        + "%d seeds, %d ticks each%n%n",
                preset.name(), boidCounts.length, shapes.length, maxDelays.length,
                seeds.length, span);
        System.out.printf("%-6s %-22s %-8s %8s %8s %8s %8s %9s %7s %6s%n",
                "boids", "shape", "dilution", "budget", "flock", "psyboid", "others",
                "control", "excess", "rank");

        for (int boids : boidCounts) {
            ScenarioParameter scenario = withFlockSize(preset, boids);
            Engine engine = new Boids2DEngine(scenario);

            for (int s = 0; s < shapes.length; s++) {
                for (int maxDelay : maxDelays) {
                    int spacing = maxDelay + duration;
                    int commits = span / spacing;
                    String tag = maxDelay <= 8 ? "low" : maxDelay <= 32 ? "medium" : "high";
                    SearchJournal.context(String.format("%s n%d landscape %s %s",
                            preset.name(), boids, shapeNames[s], tag));

                    double flock = 0, psy = 0, others = 0, ctl = 0, rank = 0;
                    double budget = 0;
                    long started = System.nanoTime();
                    for (long seed : seeds) {
                        int psyboid = (int) Math.floorMod(seed * 7919L + s, boids);
                        PsyboidSearch.Config config = new PsyboidSearch.Config(shapes[s],
                                lookahead, alpha, maxDelay, duration, segments, psyboid);
                        budget = config.budget();

                        Sim.State root = warmedRoot(engine, seed, warmup);
                        PsyboidSearch search = new PsyboidSearch(engine, config, root, seed);
                        for (int c = 0; c < commits; c++) search.commit();
                        Sim.State end = search.canonical();
                        Sim.State control = plainRun(engine, root, commits * spacing);
                        int ticks = commits * spacing;

                        flock += 100.0 * end.score / boids / ticks / seeds.length;
                        psy += 100.0 * end.boidScore[psyboid] / ticks / seeds.length;
                        others += 100.0 * (end.score - end.boidScore[psyboid])
                                / (boids - 1) / ticks / seeds.length;
                        ctl += 100.0 * control.score / boids / ticks / seeds.length;
                        rank += rankOf(end, psyboid) / (double) seeds.length;
                    }
                    System.out.printf("%-6d %-22s %-8s %8.0f %7.2f%% %7.2f%% %7.2f%% "
                                    + "%8.2f%% %+6.2f%% %4.1f  (%.0fs)%n",
                            boids, shapeNames[s], tag, budget, flock, psy, others, ctl,
                            flock - ctl, rank, (System.nanoTime() - started) / 1e9);
                }
            }
            SearchJournal.flush();
        }
    }

    /**
     * Occupancy over binned (x, y, heading), psyboid against everyone else.
     * <p>
     * The regression asks whether any feature is linearly informative. This asks a weaker
     * and more forgiving question: is there anywhere the psyboid stands, facing any
     * particular way, that the others do not? A pattern too lumpy for a linear model to
     * express would still show up as a cell it visits far more than its share.
     * <p>
     * Every tick is counted, not only the sampled instants, so the picture is the run
     * rather than a sample of it.
     */
    public static void occupancyHeatmap(PresetScenarioParameter preset, int boids,
                                        String context, int warmup, int spatialBins,
                                        int headingBins, int stride) throws IOException {
        ScenarioParameter scenario = withFlockSize(preset, boids);
        Engine engine = new Boids2DEngine(scenario);
        NavMap map = NavMapBuilder.buildFromPng(scenario.mapPath(),
                Math.round(scenario.turningRadius()));

        int cells = spatialBins * spatialBins * headingBins;
        double[] psy = new double[cells];
        double[] oth = new double[cells];
        long psyN = 0, othN = 0;

        for (JournalRow run : readJournal(Path.of("data", "searches.tsv"), context)) {
            int span = run.commits() * run.spacing();
            Sim.State s = engine.init(run.seed());
            while (s.tick < warmup) s = engine.tick(s);
            s = new Sim.State(s.n, s.x, s.y, s.h, s.tick, 0L, new long[s.n], run.label(),
                    PsyboidSearch.overridesOf(run.label()));

            for (int t = 0; t < span; t++) {
                s = engine.tick(s);
                if (t % stride != 0) continue;
                for (int i = 0; i < boids; i++) {
                    int bx = Math.min(spatialBins - 1, s.x[i] * spatialBins / map.width());
                    int by = Math.min(spatialBins - 1, s.y[i] * spatialBins / map.height());
                    int bh = s.h[i] * headingBins / Params.TURNS;
                    int cell = (bx * spatialBins + by) * headingBins + bh;
                    if (i == run.psyboid()) { psy[cell]++; psyN++; }
                    else { oth[cell]++; othN++; }
                }
            }
            System.out.printf("  seed %d done%n", run.seed());
        }

        // Chi-square of the psyboid's distribution against the others', treating the
        // others as the expected shape.
        double chi = 0;
        int used = 0;
        for (int c = 0; c < cells; c++) {
            double expected = oth[c] / othN * psyN;
            if (expected < 5) continue;
            chi += (psy[c] - expected) * (psy[c] - expected) / expected;
            used++;
        }
        System.out.printf("%n%d cells (%dx%d spatial x %d heading), %d with enough data%n",
                cells, spatialBins, spatialBins, headingBins, used);
        System.out.printf("chi-square %.0f on ~%d df â€” %.2f per cell (1.0 means no structure)%n",
                chi, used - 1, chi / Math.max(1, used - 1));

        System.out.printf("%n%-24s %8s %8s %7s%n", "cell (x,y,heading)", "psyboid", "expected", "ratio");
        Integer[] order = new Integer[cells];
        for (int c = 0; c < cells; c++) order[c] = c;
        final double[] p = psy, o = oth;
        final long pn = psyN, on = othN;
        java.util.Arrays.sort(order, (a, b) -> Double.compare(
                ratio(p[b], o[b], pn, on), ratio(p[a], o[a], pn, on)));
        int shown = 0;
        for (int c : order) {
            double expected = oth[c] / othN * psyN;
            if (expected < 20 || shown >= 8) continue;
            System.out.printf("x%d y%d h%-18s %8.0f %8.1f %6.2fx%n",
                    (c / headingBins) / spatialBins, (c / headingBins) % spatialBins,
                    (c % headingBins) + "/" + headingBins, psy[c], expected, psy[c] / expected);
            shown++;
        }
    }

    private static double ratio(double psy, double oth, long psyN, long othN) {
        double expected = oth / othN * psyN;
        return expected < 20 ? 0 : psy / expected;
    }

    /**
     * When did the override actually change anything, and what came of it?
     * <p>
     * An override is <em>nominally</em> active for its whole duration, but most of that
     * time it asks for the turn the flocking rules were going to take anyway, or asks for
     * one the collision veto refuses. What matters causally is the far smaller set of
     * ticks where the psyboid's heading genuinely differs from what it would have been â€”
     * found here by advancing every state twice, once as it happened and once with the
     * overrides stripped, and comparing.
     * <p>
     * Around each such tick the two timelines are then run forward independently, which
     * answers the question the leader argument turns on: would this boid have reached the
     * scoring zone anyway, and would anyone else?
     */
    public static void deviationReport(PresetScenarioParameter preset, int boids, String label,
                                       int warmup, int span, int psyboid, int horizon)
            throws IOException {
        ScenarioParameter scenario = withFlockSize(preset, boids);
        Engine engine = new Boids2DEngine(scenario);
        NavMap map = NavMapBuilder.buildFromPng(scenario.mapPath(),
                Math.round(scenario.turningRadius()));

        Sim.State s = engine.init(PsyboidSearch.seedOf(label));
        while (s.tick < warmup) s = engine.tick(s);
        s = new Sim.State(s.n, s.x, s.y, s.h, s.tick, 0L, new long[s.n], label,
                PsyboidSearch.overridesOf(label));

        List<int[]> deviations = new ArrayList<>();   // {tick, turn actually taken, turn otherwise}
        List<Sim.State> before = new ArrayList<>();

        for (int t = 0; t < span; t++) {
            Sim.State actual = engine.tick(s);
            Sim.State plain = engine.tick(unsteered(s));
            if (actual.h[psyboid] != plain.h[psyboid]) {
                deviations.add(new int[]{(int) s.tick,
                        Math.floorMod(actual.h[psyboid] - s.h[psyboid], Params.TURNS),
                        Math.floorMod(plain.h[psyboid] - s.h[psyboid], Params.TURNS)});
                before.add(s);
            }
            s = actual;
        }

        System.out.printf("%s seed %d, psyboid %d (%s): %d ticks, "
                        + "%d with an effective deviation (%.1f%%)%n",
                preset.name(), PsyboidSearch.seedOf(label), psyboid, hex(psyboid, boids),
                span, deviations.size(), 100.0 * deviations.size() / span);

        // Group consecutive deviations: one manoeuvre, not a dozen separate events.
        List<int[]> episodes = new ArrayList<>();     // {firstTick, lastTick, indexOfFirst}
        for (int i = 0; i < deviations.size(); i++) {
            int start = deviations.get(i)[0], startIdx = i;
            while (i + 1 < deviations.size() && deviations.get(i + 1)[0] <= deviations.get(i)[0] + 4) i++;
            episodes.add(new int[]{start, deviations.get(i)[0], startIdx});
        }
        System.out.printf("grouped into %d manoeuvre(s)%n%n", episodes.size());

        System.out.printf("%-8s %-8s %10s %10s %10s %10s   %s%n", "tick", "ticks",
                "psy actual", "psy if not", "flock act", "flock if", "first to score");
        for (int[] ep : episodes) {
            Sim.State root = before.get(ep[2]);
            Sim.State a = root, b = unsteered(root);
            long psyA = 0, psyB = 0, flockA = 0, flockB = 0;
            int firstA = -1, firstB = -1;
            int tickA = -1, tickB = -1;

            for (int t = 0; t < horizon; t++) {
                a = engine.tick(a);
                b = engine.tick(b);
                for (int i = 0; i < boids; i++) {
                    if (map.score(a.x[i], a.y[i]) > 0) {
                        if (i == psyboid) psyA++;
                        flockA++;
                        if (firstA < 0) { firstA = i; tickA = t; }
                    }
                    if (map.score(b.x[i], b.y[i]) > 0) {
                        if (i == psyboid) psyB++;
                        flockB++;
                        if (firstB < 0) { firstB = i; tickB = t; }
                    }
                }
            }
            System.out.printf("%-8d %-8d %10d %10d %10d %10d   actual %s@%d / without %s@%d%n",
                    ep[0], ep[1] - ep[0] + 1, psyA, psyB, flockA, flockB,
                    firstA < 0 ? "none" : COLOUR_NAMES[Math.min(firstA, 9)], tickA,
                    firstB < 0 ? "none" : COLOUR_NAMES[Math.min(firstB, 9)], tickB);
        }
    }

    /**
     * A decision point: a directed gate, crossed when a boid passes from the green side
     * to the blue side within the gate's span.
     */
    private record Gate(String name, int greenX, int blueX, int y0, int y1) {
        /** True when the step from {@code (px,py)} to {@code (x,y)} crosses green to blue. */
        boolean crossed(int px, int py, int x, int y) {
            boolean inSpan = (py >= y0 - 3 && py <= y1 + 3) || (y >= y0 - 3 && y <= y1 + 3);
            if (!inSpan) return false;
            return greenX > blueX ? (px > blueX && x <= blueX) : (px < blueX && x >= blueX);
        }
    }

    /**
     * The lines drawn onto dabeone, extended to catch a step that crosses diagonally.
     * <p>
     * Four of the five run into solid wall within a couple of pixels, so extending them is
     * free. {@code exit2} does not: only four pixels of wall sit below it before another
     * corridor opens at y=274, so its lower end is clipped to keep the gate â€” including
     * the record's own three pixels of slack â€” inside that wall.
     */
    private static final Gate[] DABEONE_GATES = {
            new Gate("start", 202, 201, 174, 191),       // red middle, R->L, before exit 1
            new Gate("pre-exit2", 78, 79, 177, 195),     // red left,   L->R, before exit 2
            new Gate("pre-score", 332, 331, 268, 285),   // red right,  R->L, into scoring
            // Both directions of each blue line, since which way it is crossed is the whole
            // distinction between BC and DE â€” they share these pixels and differ only in
            // heading.
            new Gate("exit1>BC", 153, 154, 106, 126),
            new Gate("exit1<DE", 155, 154, 106, 126),
            new Gate("exit2>BC", 85, 86, 259, 270),
            new Gate("exit2<DE", 87, 86, 259, 270),
    };

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

    /** Can one anchor on route A locate the exit branches? */
    public static void exitByAnchor(PresetScenarioParameter preset, int x0, int y0, int d0,
                                    int[] onsets) throws IOException {
        NavMap map = NavMapBuilder.buildFromPng(preset.mapPath(),
                Math.round(preset.turningRadius()));
        NavMap free = avoidScoring(preset);
        Engine solo = new Boids2DEngine(withFlockSize(preset, 1));
        boolean[] reach = canReachA(map, free, x0, y0, d0);
        int w = map.width(), turns = Params.TURNS;

        int live = 0, exited = 0;
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                if (map.oob(x, y)) continue;
                for (int d = 0; d < turns; d++) {
                    if (!map.alive(x, y, d)) continue;
                    live++;
                    if (!reach[(x + y * w) * turns + d]) exited++;
                }
            }
        }
        System.out.printf("%n=== %s @%s: exited = cannot reach A(%d,%d,%d) without scoring ===%n",
                preset.name(), preset.ingest().hash(), x0, y0, d0);
        System.out.printf("%d of %d live states have exited%n%n", exited, live);

        for (int onset : onsets) {
            PsyboidOverride[] ov = onset < 0 ? new PsyboidOverride[0]
                    : new PsyboidOverride[]{new PsyboidOverride(onset, 4 * PsyboidSearch.SECOND,
                            +1, 0)};
            Sim.State s = new Sim.State(1, new int[]{x0}, new int[]{y0}, new int[]{d0},
                    0L, 0L, new long[1], "probe", ov);
            int flip = -1;
            for (int t = 0; t <= 400 && flip < 0; t++) {
                if (!reach[(s.x[0] + s.y[0] * w) * turns + s.h[0]]) flip = t;
                else s = solo.tick(s);
            }
            System.out.printf("  onset %-5s exits at tick %-6s %s%n",
                    onset < 0 ? "none" : onset, flip < 0 ? "never" : String.valueOf(flip),
                    flip < 0 ? "(stays on A)" : "(%d,%d,%d)".formatted(s.x[0], s.y[0], s.h[0]));
        }
    }

    /**
     * Checks the one-anchor exit test against the hand-annotated edge map.
     * <p>
     * The prediction is exact and falsifiable: the four edges route A traverses â€” ABCDE,
     * ADE, A, ABD â€” must be precisely the states that can still reach the anchor without
     * scoring, and the other six, X included, must be precisely those that cannot. Any
     * disagreement means the irreversibility claim is wrong somewhere.
     */
    public static void crossCheckAnchor(PresetScenarioParameter preset, int settle)
            throws IOException {
        NavMap map = NavMapBuilder.buildFromPng(preset.mapPath(),
                Math.round(preset.turningRadius()));
        NavMap free = avoidScoring(preset);

        // Anchor on the settled single-boid orbit rather than on a hand-picked start. That
        // orbit is route A by construction â€” it never scores â€” and every state in it is
        // live and reachable, which a start point chosen under older physics need not be.
        Engine solo = new Boids2DEngine(withFlockSize(preset, 1));
        Sim.State orbit = solo.init(0);
        for (int t = 0; t < settle; t++) orbit = solo.tick(orbit);
        int ax = orbit.x[0], ay = orbit.y[0], ad = orbit.h[0];
        boolean[] reach = canReachA(map, free, ax, ay, ad);
        byte[] edge = Files.readAllBytes(preset.ingest().output("routes", "edgemap.bin"));
        int w = map.width(), turns = Params.TURNS;

        int[][] tally = new int[EDGES.length][2];      // [edge][0 = on A, 1 = exited]
        int unlabelled = 0, unlabelledExited = 0;
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                if (map.oob(x, y)) continue;
                for (int d = 0; d < turns; d++) {
                    if (!map.alive(x, y, d)) continue;
                    int s = (x + y * w) * turns + d;
                    boolean exited = !reach[s];
                    byte e = edge[s];
                    if (e < 0) { unlabelled++; if (exited) unlabelledExited++; continue; }
                    tally[e][exited ? 1 : 0]++;
                }
            }
        }

        System.out.printf("%n=== %s @%s: one-anchor exit test vs the annotated edge map ===%n",
                preset.name(), preset.ingest().hash());
        int reached = 0;
        for (boolean b : reach) if (b) reached++;
        System.out.printf("anchor (%d,%d,%d): alive=%b, avoids-scoring=%b, edge=%s%n",
                ax, ay, ad, map.alive(ax, ay, ad), free.alive(ax, ay, ad),
                edge[(ax + ay * w) * turns + ad] < 0 ? "none"
                        : EDGES[edge[(ax + ay * w) * turns + ad]]);
        System.out.printf("backward search reached %d states%n%n", reached);
        System.out.printf("%-8s %-9s %9s %9s   %s%n",
                "edge", "expected", "on A", "exited", "verdict");
        int wrong = 0;
        for (int i = 0; i < EDGES.length; i++) {
            boolean routeA = EDGES[i].contains("A") && !EDGES[i].equals("X");
            int bad = routeA ? tally[i][1] : tally[i][0];
            wrong += bad;
            if (tally[i][0] + tally[i][1] == 0) continue;
            System.out.printf("%-8s %-9s %9d %9d   %s%n", EDGES[i], routeA ? "on A" : "exited",
                    tally[i][0], tally[i][1], bad == 0 ? "ok" : bad + " WRONG");
        }
        System.out.printf("%nmislabelled states: %d%n", wrong);
        System.out.printf("unlabelled by the annotation: %d (%d of them exited)%n",
                unlabelled, unlabelledExited);
    }

    /** Does "cannot avoid scoring" reproduce the commit boundary the landmarks gave? */
    public static void commitWithoutLandmarks(PresetScenarioParameter preset,
                                              int x0, int y0, int d0, int[] onsets)
            throws IOException {
        NavMap map = NavMapBuilder.buildFromPng(preset.mapPath(),
                Math.round(preset.turningRadius()));
        NavMap free = avoidScoring(preset);
        Engine solo = new Boids2DEngine(withFlockSize(preset, 1));

        int live = 0, committed = 0;
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                if (map.oob(x, y)) continue;
                for (int d = 0; d < Params.TURNS; d++) {
                    if (!map.alive(x, y, d)) continue;
                    live++;
                    if (!free.alive(x, y, d)) committed++;
                }
            }
        }
        System.out.printf("%n=== %s @%s: committed = cannot avoid scoring ===%n",
                preset.name(), preset.ingest().hash());
        System.out.printf("%d of %d live states cannot avoid scoring%n%n", committed, live);

        for (int onset : onsets) {
            PsyboidOverride[] ov = onset < 0 ? new PsyboidOverride[0]
                    : new PsyboidOverride[]{new PsyboidOverride(onset, 4 * PsyboidSearch.SECOND,
                            +1, 0)};
            Sim.State s = new Sim.State(1, new int[]{x0}, new int[]{y0}, new int[]{d0},
                    0L, 0L, new long[1], "probe", ov);
            int flipped = -1;
            for (int t = 0; t <= 400; t++) {
                if (!free.alive(s.x[0], s.y[0], s.h[0])) { flipped = t; break; }
                s = solo.tick(s);
            }
            System.out.printf("  onset %-4s  commits at tick %-5s %s%n",
                    onset < 0 ? "none" : onset,
                    flipped < 0 ? "never" : String.valueOf(flipped),
                    flipped < 0 ? "(stays on the main loop)"
                            : "(%d,%d,%d)".formatted(s.x[0], s.y[0], s.h[0]));
        }
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
        Labelling l = label(preset, horizontal, line, lo, hi, dir);
        EdgeMetric.Metric m = EdgeMetric.compute(l.map(), l.edge(), l.live(), l.liveCount(),
                l.edges());
        NavMap map = l.map();
        int[] edge = l.edge();
        int w = map.width(), h = map.height(), turns = Params.TURNS;
        double[] tick = m.tick();
        int[] length = m.length();

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

        System.out.printf("%n=== %s @%s: how well the clock holds ===%n",
                preset.name(), preset.ingest().hash());
        report("successors minus predecessors (want 2)", spread, hasSpread, 2);
        report("unsteered step, worst per pixel (want 1)", slowest, hasSlowest, 1);

        Path a = preset.ingest().output("edges", "tick_rate.png");
        Path b = preset.ingest().output("edges", "tick_unsteered.png");
        NavMapRender.writeField(map, spread, hasSpread, 2, 0.25, 0xD2382C, 0x2F6FD0, 2, a);
        NavMapRender.writeField(map, slowest, hasSlowest, 1, 0.25, 0xE8C21E, 0x35A853, 2, b);
        System.out.printf("wrote %s%nwrote %s%n", a, b);
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
        Labelling l = label(preset, horizontal, line, lo, hi, dir);
        EdgeMetric.Metric m = EdgeMetric.compute(l.map(), l.edge(), l.live(), l.liveCount(),
                l.edges(), scheme);
        NavMap map = l.map();
        int[] edge = l.edge();

        System.out.printf("%n=== %s @%s: %d-tick journeys, weighting %s ===%n",
                preset.name(), preset.ingest().hash(), ticks, scheme);
        System.out.printf("  %s%n", EdgeMetric.lastSolve());
        System.out.printf("%-7s %8s %9s %9s %10s %8s %9s%n", "boids", "journeys",
                "mean", "sd", "sd/mean", "routes", "shortest");
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
        for (int len : m.length()) total += len;
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
            System.out.printf("%-7d %8d %9.2f %9.3f %9.3f%% %8.1f %8.1f%%%n", boids, journeys,
                    mean, sd, 100 * sd / mean, routeTotal / (double) journeys,
                    100.0 * shortestBest / journeys);
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

    /** Canonical paths, edge lengths and per-state ticks for a decomposed map. */
    public static void metric(PresetScenarioParameter preset, boolean horizontal, int line,
                              int lo, int hi, int dir) throws IOException {
        Labelling l = label(preset, horizontal, line, lo, hi, dir);
        EdgeMetric.Metric m = EdgeMetric.compute(l.map(), l.edge(), l.live(), l.liveCount(),
                l.edges());
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
            System.out.printf("%-5d %7d %9d  %.2f to %.2f%n", e, m.length()[e], n, min, max);
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

    private static String where(int state, int w, int turns) {
        if (state < 0) return "-";
        int d = state % turns, cell = state / turns;
        return "(" + cell % w + "," + cell / w + "," + d + ")";
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
        EdgeMetric.Metric m = EdgeMetric.compute(l.map(), l.edge(), l.live(), l.liveCount(),
                l.edges());
        EdgeInfluence.Envelope env = EdgeInfluence.envelope(l.map(), l.edge(), l.live(),
                l.liveCount(), from, keep);
        EdgeInfluence.Lead lead = EdgeInfluence.lead(l.map(), l.edge(), l.live(), l.liveCount(),
                from, keep, preset.turningRadius(), env);

        double first = Double.MAX_VALUE, last = -Double.MAX_VALUE;
        for (int s : env.envelope()) {
            if (l.edge()[s] != from || Double.isNaN(m.tick()[s])) continue;
            first = Math.min(first, m.tick()[s]);
            last = Math.max(last, m.tick()[s]);
        }
        System.out.printf("%n=== %s: edge %d spans ticks %.1f to %.1f inside the envelope, "
                        + "edge length %d ===%n", preset.name(), from, first, last, m.length()[from]);
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
                from, keep, preset.turningRadius(), e);
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
                l.liveCount(), from, keep, preset.turningRadius());

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
        long[] outTo = new long[edges];
        for (int i = 0; i < liveCount; i++) {
            int s = live[i], e = edge[s];
            int d = s % turns, cell = s / turns, x = cell % w, y = cell / w;
            size[e]++;
            if (map.score(x, y) > 0) scoring[e]++;
            if (onA[s]) reachesA[e]++;
            minX[e] = Math.min(minX[e], x); maxX[e] = Math.max(maxX[e], x);
            minY[e] = Math.min(minY[e], y); maxY[e] = Math.max(maxY[e], y);
        }
        for (int i = 0; i < liveCount; i++) {
            int s = live[i];
            int d = s % turns, cell = s / turns, x = cell % w, y = cell / w;
            for (int t = -1; t <= 1; t++) {
                if (map.constrainTurn(x, y, d, t) != t) continue;
                int nd = Math.floorMod(d + t, turns);
                int nx = x + map.stepX(nd), ny = y + map.stepY(nd);
                if (nx < 0 || ny < 0 || nx >= w || ny >= h(map) || map.oob(nx, ny)) continue;
                if (!map.alive(nx, ny, nd)) continue;
                int u = (nx + ny * w) * turns + nd;
                if (edge[u] != edge[s]) outTo[edge[s]] |= 1L << edge[u];
            }
        }

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

    private static int[] firstGates(NavMap map, Gate[] gates) {
        int w = map.width(), h = map.height(), turns = Params.TURNS;
        int[] mask = new int[w * h * turns];
        int[] stack = new int[1 << 16];
        int top = 0;

        // Seed: transitions that cross a gate settle immediately, and never change again.
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (map.oob(x, y)) continue;
                for (int d = 0; d < turns; d++) {
                    if (!map.alive(x, y, d)) continue;
                    int bits = 0;
                    for (int t = -1; t <= 1; t++) {
                        if (map.constrainTurn(x, y, d, t) != t) continue;
                        int nd = Math.floorMod(d + t, turns);
                        int nx = x + map.stepX(nd), ny = y + map.stepY(nd);
                        for (int g = 0; g < gates.length; g++) {
                            if (gates[g].crossed(x, y, nx, ny)) bits |= 1 << g;
                        }
                    }
                    if (bits != 0) {
                        int s = (x + y * w) * turns + d;
                        mask[s] = bits;
                        if (top == stack.length) stack = grow(stack);
                        stack[top++] = s;
                    }
                }
            }
        }

        // Propagate backwards: a state inherits from any successor it can still turn into
        // without crossing a gate on the way.
        while (top > 0) {
            int s = stack[--top];
            int d = s % turns, cell = s / turns, x = cell % w, y = cell / w;
            int px = x - map.stepX(d), py = y - map.stepY(d);
            if (px < 0 || py < 0 || px >= w || py >= h || map.oob(px, py)) continue;
            for (int t = -1; t <= 1; t++) {
                int pd = Math.floorMod(d - t, turns);
                if (!map.alive(px, py, pd) || map.constrainTurn(px, py, pd, t) != t) continue;
                boolean crosses = false;
                for (Gate g : gates) if (g.crossed(px, py, x, y)) crosses = true;
                if (crosses) continue;              // that predecessor already settled here
                int p = (px + py * w) * turns + pd;
                int merged = mask[p] | mask[s];
                if (merged != mask[p]) {
                    mask[p] = merged;
                    if (top == stack.length) stack = grow(stack);
                    stack[top++] = p;
                }
            }
        }
        return mask;
    }

    /**
     * Where along route A each decision opens and closes, in states rather than in ticks.
     */
    public static void dabeoneCommit(PresetScenarioParameter preset, int x0, int y0, int d0)
            throws IOException {
        NavMap map = NavMapBuilder.buildFromPng(preset.mapPath(),
                Math.round(preset.turningRadius()));
        Engine solo = new Boids2DEngine(withFlockSize(preset, 1));
        int[] mask = firstGates(map, DABEONE_GATES);
        int w = map.width(), turns = Params.TURNS;

        System.out.printf("%n=== %s @%s: first landmark reachable, along route A ===%n",
                preset.name(), preset.ingest().hash());
        System.out.printf("gates: ");
        for (int g = 0; g < DABEONE_GATES.length; g++) {
            System.out.printf("%d=%s ", g, DABEONE_GATES[g].name());
        }
        System.out.printf("%n%n%-6s %-14s %s%n", "tick", "state", "could still reach");

        Sim.State s = new Sim.State(1, new int[]{x0}, new int[]{y0}, new int[]{d0},
                0L, 0L, new long[1], "A", new PsyboidOverride[0]);
        int prev = -1;
        for (int t = 0; t <= 278; t++) {
            int bits = mask[(s.x[0] + s.y[0] * w) * turns + s.h[0]];
            if (bits != prev) {
                StringBuilder names = new StringBuilder();
                for (int g = 0; g < DABEONE_GATES.length; g++) {
                    if ((bits & (1 << g)) != 0) {
                        names.append(names.length() > 0 ? " | " : "").append(DABEONE_GATES[g].name());
                    }
                }
                System.out.printf("%-6d (%3d,%3d,%2d)   %s%n", t, s.x[0], s.y[0], s.h[0],
                        names.length() > 0 ? names : "(nothing)");
                prev = bits;
            }
            s = solo.tick(s);
        }

        // Census over every live state, not just route A: the deciding set is the inbound
        // edge, the committed sets are what lies past each branch.
        int E1 = 1 << 3, E2 = 1 << 6, CONT1 = 1 << 1, CONT2 = 1 << 0;
        int decide1 = 0, commit1 = 0, decide2 = 0, commit2 = 0, live = 0, stuck = 0;
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                if (map.oob(x, y)) continue;
                for (int d = 0; d < turns; d++) {
                    if (!map.alive(x, y, d)) continue;
                    live++;
                    int b = mask[(x + y * w) * turns + d];
                    if (b == 0) { stuck++; continue; }
                    if ((b & E1) != 0 && (b & CONT1) != 0) decide1++;
                    else if (b == E1) commit1++;
                    if ((b & E2) != 0 && (b & CONT2) != 0) decide2++;
                    else if (b == E2) commit2++;
                }
            }
        }
        System.out.printf("%nof %d live states:%n", live);
        System.out.printf("  exit 1 still a choice   %6d      exit 1 unavoidable   %6d%n",
                decide1, commit1);
        System.out.printf("  exit 2 still a choice   %6d      exit 2 unavoidable   %6d%n",
                decide2, commit2);
        System.out.printf("  reach no landmark ever  %6d%n", stuck);
    }

    /**
     * Which tick a right turn has to start on to take each exit.
     * <p>
     * Sweeps a single right-turn override across every tick of the lap and reads the
     * outcome off the blue lines: crossed left to right the boid is on BC, right to left
     * on DE, and neither means it stayed on A. This replaces the hand-found onsets used on
     * dabnt, and unlike those it also measures how wide each window is.
     */
    public static void dabeoneExits(PresetScenarioParameter preset, int x0, int y0, int d0)
            throws IOException {
        Engine solo = new Boids2DEngine(withFlockSize(preset, 1));
        int duration = 4 * PsyboidSearch.SECOND;

        List<int[]> routeA = traceRoute(solo, x0, y0, d0, List.of(), 4000, new ArrayList<>());
        int lap = routeA.size() - 1;
        System.out.printf("%n=== %s @%s: exit sweep from (%d,%d,%d) ===%n", preset.name(),
                preset.ingest().hash(), x0, y0, d0);
        System.out.printf("route A lap is %d ticks; right turn of %d ticks at each onset%n",
                lap, duration);

        // Where the lap passes each landmark, so window positions can be read against them.
        Sim.State probe = new Sim.State(1, new int[]{x0}, new int[]{y0}, new int[]{d0},
                0L, 0L, new long[1], "probe", new PsyboidOverride[0]);
        System.out.print("route A crosses:");
        for (int t = 1; t <= lap; t++) {
            int px = probe.x[0], py = probe.y[0];
            probe = solo.tick(probe);
            for (Gate g : DABEONE_GATES) {
                if (g.crossed(px, py, probe.x[0], probe.y[0])) {
                    System.out.printf("  %s@%d", g.name(), t);
                }
            }
        }
        System.out.println("\n");

        char[] outcome = new char[lap];
        long[] scored = new long[lap];
        for (int onset = 0; onset < lap; onset++) {
            Sim.State s = new Sim.State(1, new int[]{x0}, new int[]{y0}, new int[]{d0},
                    0L, 0L, new long[1], "sweep",
                    new PsyboidOverride[]{new PsyboidOverride(onset, duration, +1, 0)});
            char got = '.';
            for (int t = 1; t <= 700; t++) {
                int px = s.x[0], py = s.y[0];
                s = solo.tick(s);
                if (got != '.') continue;
                for (Gate g : DABEONE_GATES) {
                    if (!g.crossed(px, py, s.x[0], s.y[0])) continue;
                    if (g.name().endsWith("BC")) got = 'B';
                    else if (g.name().endsWith("DE")) got = 'D';
                }
            }
            outcome[onset] = got;
            scored[onset] = s.score;
        }

        System.out.println("  '.' stayed on A   'B' reached BC   'D' reached DE");
        for (int row = 0; row < lap; row += 50) {
            System.out.printf("  %4d  ", row);
            for (int i = row; i < Math.min(row + 50, lap); i++) System.out.print(outcome[i]);
            System.out.println();
        }

        for (char which : new char[]{'B', 'D'}) {
            StringBuilder runs = new StringBuilder();
            int from = -1;
            for (int i = 0; i <= lap; i++) {
                boolean hit = i < lap && outcome[i] == which;
                if (hit && from < 0) from = i;
                if (!hit && from >= 0) {
                    runs.append(runs.length() > 0 ? ", " : "")
                            .append(from == i - 1 ? String.valueOf(from) : from + "-" + (i - 1));
                    from = -1;
                }
            }
            System.out.printf("%nonsets reaching %s: %s%n", which == 'B' ? "BC" : "DE",
                    runs.length() > 0 ? runs : "none");
        }
        long noScore = 0;
        for (int i = 0; i < lap; i++) if (outcome[i] != '.' && scored[i] == 0) noScore++;
        System.out.printf("%nleft A but scored nothing in 700 ticks: %d onsets%n", noScore);

        int minX = 9999, maxX = -1, near331 = 0;
        for (int[] p : routeA) {
            minX = Math.min(minX, p[1]); maxX = Math.max(maxX, p[1]);
            if (Math.abs(p[1] - 331) <= 6) near331++;
        }
        System.out.printf("route A spans x %d-%d; %d of its %d states come within 6px of x=331%n",
                minX, maxX, near331, routeA.size());

        // What each exit route crosses, which is what names the edges later.
        for (int[] pick : new int[][]{{1, 1}, {100, 2}}) {
            Sim.State s = new Sim.State(1, new int[]{x0}, new int[]{y0}, new int[]{d0},
                    0L, 0L, new long[1], "route",
                    new PsyboidOverride[]{new PsyboidOverride(pick[0], duration, +1, 0)});
            System.out.printf("%nexit %d (onset %d) crosses:", pick[1], pick[0]);
            for (int t = 1; t <= 700; t++) {
                int px = s.x[0], py = s.y[0];
                s = solo.tick(s);
                for (Gate g : DABEONE_GATES) {
                    if (g.crossed(px, py, s.x[0], s.y[0])) System.out.printf("  %s@%d", g.name(), t);
                }
            }
            System.out.printf("%n  score %d after 700 ticks%n", s.score);
        }
    }

    /**
     * Where a lone boid crosses the start line, which anchors every tick number that
     * follows. Run across seeds because the start has to be a property of the circuit, not
     * of whichever seed happened to find it.
     */
    public static void dabeoneStart(PresetScenarioParameter preset, int seeds, int settle)
            throws IOException {
        Engine engine = new Boids2DEngine(withFlockSize(preset, 1));
        Gate start = DABEONE_GATES[0];
        System.out.printf("%n=== %s @%s: start line ===%n", preset.name(), preset.ingest().hash());
        Map<String, Integer> agree = new java.util.TreeMap<>();
        for (int seed = 0; seed < seeds; seed++) {
            Sim.State s = engine.init(seed);
            for (int t = 0; t < settle; t++) s = engine.tick(s);
            for (int t = 0; t < 600; t++) {
                int px = s.x[0], py = s.y[0];
                s = engine.tick(s);
                if (start.crossed(px, py, s.x[0], s.y[0])) {
                    agree.merge("(%d, %d, %d)".formatted(s.x[0], s.y[0], s.h[0]), 1, Integer::sum);
                    break;
                }
            }
        }
        agree.forEach((state, n) -> System.out.printf("  %-18s %d of %d seeds%n", state, n, seeds));
    }

    private static final Gate[] DAB_GATES = {
            new Gate("gate-A", 186, 185, 179, 189),     // upper middle, crossed westward
            new Gate("gate-B", 81, 82, 183, 190),       // left, crossed eastward
            new Gate("gate-S", 311, 310, 273, 280),     // bottom right, into the scoring loop
    };

    /**
     * One boid, no neighbours, steered only by the walls and whatever overrides it is given.
     * <p>
     * With a flock of one the flocking rules contribute nothing â€” no neighbour survives the
     * range test, so the desired direction is zero and the boid holds its heading â€” which
     * leaves {@link NavMap#constrainTurn} as the only thing steering it. The path is
     * therefore a property of the corridor alone, and is exactly reproducible.
     *
     * @return every {@code {tick, x, y, heading}} until the state repeats or the budget runs out
     */
    private static List<int[]> traceRoute(Engine engine, int x0, int y0, int d0,
                                          List<int[]> overrides, int maxTicks,
                                          List<String> crossings) {
        PsyboidOverride[] ov = new PsyboidOverride[overrides.size()];
        for (int i = 0; i < ov.length; i++) {
            int[] o = overrides.get(i);
            ov[i] = new PsyboidOverride(o[0], o[1], o[2], 0);
        }
        Sim.State s = new Sim.State(1, new int[]{x0}, new int[]{y0}, new int[]{d0},
                0L, 0L, new long[1], "route", ov);

        List<int[]> path = new ArrayList<>();
        path.add(new int[]{0, x0, y0, d0});

        for (int t = 1; t <= maxTicks; t++) {
            int px = s.x[0], py = s.y[0];
            s = engine.tick(s);
            path.add(new int[]{t, s.x[0], s.y[0], s.h[0]});

            for (Gate g : DAB_GATES) {
                if (g.crossed(px, py, s.x[0], s.y[0])) {
                    crossings.add(g.name() + "@" + t + " (" + s.x[0] + "," + s.y[0] + ")");
                }
            }

            // A circuit is done when the boid is back where it began and pointing the way
            // it began. Position alone is not enough: every route through the outer loop
            // passes this point twice, once outbound heading roughly east and once
            // inbound heading roughly west, and only the second is a return. Waiting for
            // an exact state repeat instead runs on through extra main-loop laps until the
            // pixel happens to line up.
            if (t > 5 && Math.hypot(s.x[0] - x0, s.y[0] - y0) < 4) {
                int turn = Math.abs(Math.floorMod(s.h[0] - d0 + Params.TURNS / 2,
                        Params.TURNS) - Params.TURNS / 2);
                if (turn <= 8) break;
            }
        }
        return path;
    }

    /**
     * Net turns about {@code (cx, cy)} while the path is inside {@code region}.
     * <p>
     * Screen coordinates put y downwards, so a positive result is <b>clockwise as drawn</b>.
     * Summing signed angle rather than counting crossings means a route that enters,
     * doubles back and leaves contributes what it actually swept.
     */
    private static double winding(List<int[]> path, double cx, double cy,
                                  java.util.function.BiPredicate<Integer, Integer> region) {
        double total = 0;
        int[] prev = null;
        for (int[] p : path) {
            if (p[0] < 0) continue;
            if (!region.test(p[1], p[2])) { prev = null; continue; }
            if (prev != null) {
                double ax = prev[1] - cx, ay = prev[2] - cy;
                double bx = p[1] - cx, by = p[2] - cy;
                total += Math.atan2(ax * by - ay * bx, ax * bx + ay * by);
            }
            prev = p;
        }
        return total / (2 * Math.PI);
    }

    /**
     * Draws a traced route over its play area.
     * <p>
     * Coloured blue through red by time, because on a map where a route can double back
     * through the same corridor the direction of travel is the whole question and a
     * single-colour line cannot answer it. A white dot marks the start, black the end, and
     * rings mark gate crossings.
     */
    private static void drawRoute(ScenarioParameter scenario, List<int[]> path,
                                  List<int[]> gateMarks, Path out, int scale)
            throws IOException {
        BufferedImage src = javax.imageio.ImageIO.read(scenario.mapPath().toFile());
        int w = src.getWidth() * scale, h = src.getHeight() * scale;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = src.getRGB(x / scale, y / scale) & 0xFFFFFF;
                img.setRGB(x, y, rgb == 0x000000 ? 0x000000
                        : rgb == 0xFF7F27 ? 0xF7E0CC : 0xE6E6E6);
            }
        }

        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setStroke(new java.awt.BasicStroke(Math.max(1.6f, scale * 0.9f),
                java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));

        List<int[]> pts = new ArrayList<>();
        for (int[] p : path) if (p[0] >= 0) pts.add(p);
        for (int i = 1; i < pts.size(); i++) {
            double t = (i - 1) / (double) Math.max(1, pts.size() - 2);
            g.setColor(new Color((int) (32 + 200 * t), (int) (64 * (1 - t)),
                    (int) (255 - 235 * t)));
            int[] a = pts.get(i - 1), b = pts.get(i);
            // A step that jumps the width of the map is the loop closing, not travel.
            if (Math.hypot(b[1] - a[1], b[2] - a[2]) > 20) continue;
            g.drawLine(a[1] * scale, a[2] * scale, b[1] * scale, b[2] * scale);
        }

        g.setStroke(new java.awt.BasicStroke(Math.max(1.5f, scale * 0.8f)));
        for (int[] m : gateMarks) {
            g.setColor(new Color(0x00, 0x90, 0x30));
            int r = 5 * scale;
            g.drawOval(m[0] * scale - r, m[1] * scale - r, 2 * r, 2 * r);
        }
        int r = 3 * scale;
        g.setColor(Color.WHITE);
        g.fillOval(pts.get(0)[1] * scale - r, pts.get(0)[2] * scale - r, 2 * r, 2 * r);
        g.setColor(Color.BLACK);
        g.drawOval(pts.get(0)[1] * scale - r, pts.get(0)[2] * scale - r, 2 * r, 2 * r);
        int[] last = pts.get(pts.size() - 1);
        g.fillOval(last[1] * scale - r, last[2] * scale - r, 2 * r, 2 * r);
        g.dispose();

        if (out.getParent() != null) Files.createDirectories(out.getParent());
        javax.imageio.ImageIO.write(img, "png", out.toFile());
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

    /**
     * Builds the {@code (x, y, heading) -> edge} map and writes it beside the routes.
     * <p>
     * Two stages. The annotated map divides the play area into red and blue regions, and
     * within one region a single edge owns one half of the compass â€” east/west for red at
     * headings 16 and 48, north/south for blue at 0 and 32 â€” so every state inside a region
     * can be labelled outright. Which edge that is comes from the recorded routes: whichever
     * subset of A..E runs through a region-and-half names it, which is a derivation rather
     * than an assumption and fails loudly if the regions and the routes disagree.
     * <p>
     * The white remainder is the junctions. A state there is labelled by what it runs into:
     * follow it forward until it reaches a labelled region, and it belongs to that edge.
     * Where a junction holds four edges at once, direction alone separates them, because
     * each state can only reach one of the four going forward.
     */
    public static void buildEdgeMap(PresetScenarioParameter preset, Path annotated, Path outDir)
            throws IOException {
        NavMap map = NavMapBuilder.buildFromPng(preset.mapPath(),
                Math.round(preset.turningRadius()));
        int w = map.width(), h = map.height(), turns = Params.TURNS;
        BufferedImage ann = javax.imageio.ImageIO.read(annotated.toFile());
        final int RED = 0xED1C24, BLUE = 0x00A2E8;

        // ---- regions ----
        int[] region = new int[w * h];
        Arrays.fill(region, -1);
        List<Boolean> isRed = new ArrayList<>();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = ann.getRGB(x, y) & 0xFFFFFF;
                if ((rgb != RED && rgb != BLUE) || region[x + y * w] >= 0) continue;
                int id = isRed.size();
                isRed.add(rgb == RED);
                ArrayDeque<int[]> q = new ArrayDeque<>();
                q.add(new int[]{x, y});
                region[x + y * w] = id;
                while (!q.isEmpty()) {
                    int[] p = q.poll();
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            int nx = p[0] + dx, ny = p[1] + dy;
                            if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                            if ((ann.getRGB(nx, ny) & 0xFFFFFF) != rgb) continue;
                            if (region[nx + ny * w] >= 0) continue;
                            region[nx + ny * w] = id;
                            q.add(new int[]{nx, ny});
                        }
                    }
                }
            }
        }

        // ---- which edge owns each (region, half), read off the routes ----
        Map<String, Set<String>> routesIn = new java.util.LinkedHashMap<>();
        for (String name : EDGES) { /* placeholder to fix iteration order */ }
        for (String route : new String[]{"A", "B", "C", "D", "E"}) {
            List<String> rows = Files.readAllLines(outDir.resolve("route_" + route + ".csv"));
            for (int i = 1; i < rows.size(); i++) {
                String[] v = rows.get(i).split(",");
                int x = Integer.parseInt(v[1]), y = Integer.parseInt(v[2]), d = Integer.parseInt(v[3]);
                int id = region[x + y * w];
                if (id < 0) continue;
                routesIn.computeIfAbsent(key(id, isRed.get(id), d), k -> new java.util.TreeSet<>())
                        .add(route);
            }
        }
        Map<String, Integer> edgeOf = new java.util.LinkedHashMap<>();
        List<String> unnamed = new ArrayList<>();
        for (var e : routesIn.entrySet()) {
            String label = String.join("", e.getValue());
            int idx = Arrays.asList(EDGES).indexOf(label);
            if (idx < 0) unnamed.add(e.getKey() + " -> " + label);
            else edgeOf.put(e.getKey(), idx);
        }
        System.out.printf("%d region-halves matched to edges, %d unrecognised%n",
                edgeOf.size(), unnamed.size());
        for (String u : unnamed) System.out.println("  unrecognised: " + u);

        // No route runs X, so it cannot be named from the traces. It is instead the
        // complement of A: in each of the three regions A occupies, whichever half A does
        // not own is X.
        int aIdx = Arrays.asList(EDGES).indexOf("A"), xIdx = Arrays.asList(EDGES).indexOf("X");
        for (int id = 0; id < isRed.size(); id++) {
            String[] halves = isRed.get(id) ? new String[]{"W", "E"} : new String[]{"S", "N"};
            for (int k = 0; k < 2; k++) {
                Integer owner = edgeOf.get(id + "/" + halves[k]);
                if (owner == null || owner != aIdx) continue;
                String other = id + "/" + halves[1 - k];
                Integer already = edgeOf.get(other);
                System.out.printf("  region %d: %s is A, so %s is X%s%n", id,
                        halves[k], halves[1 - k],
                        already == null ? "" : " (was " + EDGES[already] + ")");
                edgeOf.put(other, xIdx);
            }
        }

        // ---- seed, then flood the white junctions ----
        byte[] edge = new byte[w * h * turns];
        Arrays.fill(edge, (byte) -1);
        int seeded = 0, live = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (map.oob(x, y)) continue;
                int id = region[x + y * w];
                for (int d = 0; d < turns; d++) {
                    if (!map.alive(x, y, d)) continue;
                    live++;
                    if (id < 0) continue;
                    Integer idx = edgeOf.get(key(id, isRed.get(id), d));
                    if (idx == null) continue;
                    edge[(x + y * w) * turns + d] = idx.byteValue();
                    seeded++;
                }
            }
        }

        int[] reach = new int[w * h * turns];        // bitmask of edges reachable first
        for (int idx = 0; idx < EDGES.length; idx++) {
            ArrayDeque<Integer> q = new ArrayDeque<>();
            boolean[] seen = new boolean[w * h * turns];
            for (int s = 0; s < edge.length; s++) {
                if (edge[s] == idx) { seen[s] = true; q.add(s); }
            }
            while (!q.isEmpty()) {
                int s = q.poll();
                int d = s % turns, cell = s / turns, x = cell % w, y = cell / w;
                int px = x - map.stepX(d), py = y - map.stepY(d);
                if (px < 0 || py < 0 || px >= w || py >= h || map.oob(px, py)) continue;
                for (int t = -1; t <= 1; t++) {
                    int pd = Math.floorMod(d - t, turns);
                    if (!map.alive(px, py, pd)) continue;
                    if (map.constrainTurn(px, py, pd, t) != t) continue;
                    int p = (px + py * w) * turns + pd;
                    if (edge[p] >= 0 || seen[p]) continue;     // stop at labelled states
                    seen[p] = true;
                    reach[p] |= (1 << idx);
                    q.add(p);
                }
            }
        }

        // A state that can still reach both outcomes of a decision has not taken it yet, so
        // it belongs to the edge leading in. The three splits come straight from the order
        // the routes visit their edges: ABCDE parts into BC and ADE, ADE into A and DE,
        // BCDE into BD and CE.
        // A state that can still reach both branches of a split has not taken it. The last
        // two are branches that leave an edge partway along rather than at its end, so the
        // edge itself appears in the reachable set alongside what peels off it.
        Map<Integer, Integer> inboundOf = Map.of(
                (1 << 3) | (1 << 1), 0,      // {BC, ADE}  -> ABCDE
                (1 << 2) | (1 << 4), 1,      // {A, DE}    -> ADE
                (1 << 6) | (1 << 7), 5,      // {BD, CE}   -> BCDE
                (1 << 7) | (1 << 9), 7,      // {CE, X}    -> CE, where X peels off
                (1 << 1) | (1 << 4), 1);     // {ADE, DE}  -> ADE

        int filled = 0, atSplit = 0, ambiguous = 0, orphan = 0;
        Map<String, Integer> residue = new java.util.TreeMap<>();
        for (int s = 0; s < edge.length; s++) {
            int d = s % turns, cell = s / turns, x = cell % w, y = cell / w;
            if (map.oob(x, y) || !map.alive(x, y, d) || edge[s] >= 0) continue;
            int mask = reach[s];
            if (mask == 0) { orphan++; continue; }
            if (Integer.bitCount(mask) == 1) {
                edge[s] = (byte) Integer.numberOfTrailingZeros(mask);
                filled++;
            } else if (inboundOf.containsKey(mask)) {
                edge[s] = inboundOf.get(mask).byteValue();
                atSplit++;
            } else {
                ambiguous++;
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < EDGES.length; i++) {
                    if ((mask & (1 << i)) != 0) sb.append(sb.length() > 0 ? "+" : "").append(EDGES[i]);
                }
                residue.merge(sb.toString(), 1, Integer::sum);
            }
        }
        System.out.printf("%d live states: %d seeded, %d filled from junctions, "
                + "%d resolved at splits, %d ambiguous, %d unreachable%n",
                live, seeded, filled, atSplit, ambiguous, orphan);
        for (var e : residue.entrySet()) {
            System.out.printf("  still ambiguous: reaches %-24s %d states%n", e.getKey(), e.getValue());
        }

        // At a merge the outbound edge belongs only to states both inbound edges can reach.
        // Forward propagation alone cannot see this: the stretch just past one inbound edge
        // leads into the merge and gets the merge's label, even where the other inbound
        // edge could never have delivered a boid there. Processed in dependency order,
        // since ABCDE's inbound ABD is itself a merge product.
        // Only true merges: two inbound edges, one outbound. X joins BC, but ABCDE reaches
        // BC through the exit-1 decision rather than a merge, so requiring both sources
        // there wrongly hands the whole first stretch of BC back to ABCDE.
        boolean[] mergeResidue = new boolean[w * h * turns];
        int[][] merges = {{3, 4, 5}, {2, 6, 8}, {8, 7, 0}};   // {in1, in2, out}
        for (int[] m : merges) {
            boolean[][] from = new boolean[2][];
            for (int k = 0; k < 2; k++) {
                boolean[] seen = new boolean[w * h * turns];
                ArrayDeque<Integer> q = new ArrayDeque<>();
                for (int s = 0; s < edge.length; s++) if (edge[s] == m[k]) q.add(s);
                while (!q.isEmpty()) {
                    int s = q.poll();
                    int d = s % turns, cell = s / turns, x = cell % w, y = cell / w;
                    for (int t = -1; t <= 1; t++) {
                        if (map.constrainTurn(x, y, d, t) != t) continue;
                        int nd = Math.floorMod(d + t, turns);
                        int nx = x + map.stepX(nd), ny = y + map.stepY(nd);
                        if (nx < 0 || ny < 0 || nx >= w || ny >= h || map.oob(nx, ny)) continue;
                        if (!map.alive(nx, ny, nd)) continue;
                        int n = (nx + ny * w) * turns + nd;
                        if (edge[n] != m[2] || seen[n]) continue;
                        seen[n] = true;
                        q.add(n);
                    }
                }
                from[k] = seen;
            }
            int moved1 = 0, moved2 = 0, orphaned = 0;
            for (int s = 0; s < edge.length; s++) {
                if (edge[s] != m[2]) continue;
                boolean a = from[0][s], b = from[1][s];
                if (a && b) continue;
                if (a) { edge[s] = (byte) m[0]; moved1++; }
                else if (b) { edge[s] = (byte) m[1]; moved2++; }
                else { orphaned++; mergeResidue[s] = true; }
            }
            System.out.printf("merge %s+%s -> %s: %d states reassigned to %s, %d to %s, "
                            + "%d reachable from neither%n",
                    EDGES[m[0]], EDGES[m[1]], EDGES[m[2]], moved1, EDGES[m[0]],
                    moved2, EDGES[m[1]], orphaned);
        }

        // Is a state one a boid could actually be in? Being alive only means it has a
        // viable future, not that anything can deliver a boid to it. Peeling live states
        // with no live predecessor, repeatedly, leaves the recurrent core â€” where a boid
        // ends up and stays. Anything peeled is reachable only by starting there, which
        // after a warm-up means never.
        int[] indeg = new int[w * h * turns];
        boolean[] alive = new boolean[w * h * turns];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (map.oob(x, y)) continue;
                for (int d = 0; d < turns; d++) {
                    if (!map.alive(x, y, d)) continue;
                    alive[(x + y * w) * turns + d] = true;
                }
            }
        }
        for (int s = 0; s < alive.length; s++) {
            if (!alive[s]) continue;
            int d = s % turns, cell = s / turns, x = cell % w, y = cell / w;
            for (int t = -1; t <= 1; t++) {
                if (map.constrainTurn(x, y, d, t) != t) continue;
                int nd = Math.floorMod(d + t, turns);
                int nx = x + map.stepX(nd), ny = y + map.stepY(nd);
                if (nx < 0 || ny < 0 || nx >= w || ny >= h || map.oob(nx, ny)) continue;
                if (!map.alive(nx, ny, nd)) continue;
                indeg[(nx + ny * w) * turns + nd]++;
            }
        }
        // Peeled in layers, so it is visible how much of the answer a purely local check
        // gives. Layer 1 is the states with no live predecessor at all â€” decidable in
        // constant time from the navmap. Every later layer is only unreachable because
        // everything upstream of it was, which no local test can see.
        boolean[] transient_ = new boolean[w * h * turns];
        boolean[] indegZero = new boolean[w * h * turns];
        List<Integer> layer = new ArrayList<>();
        for (int s = 0; s < alive.length; s++) {
            if (alive[s] && indeg[s] == 0) { layer.add(s); indegZero[s] = true; }
        }
        int peeled = 0, round = 0, firstLayer = layer.size();
        StringBuilder layers = new StringBuilder();
        while (!layer.isEmpty()) {
            round++;
            List<Integer> next = new ArrayList<>();
            for (int s : layer) {
                if (transient_[s]) continue;
                transient_[s] = true;
                peeled++;
                int d = s % turns, cell = s / turns, x = cell % w, y = cell / w;
                for (int t = -1; t <= 1; t++) {
                    if (map.constrainTurn(x, y, d, t) != t) continue;
                    int nd = Math.floorMod(d + t, turns);
                    int nx = x + map.stepX(nd), ny = y + map.stepY(nd);
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h || map.oob(nx, ny)) continue;
                    if (!map.alive(nx, ny, nd)) continue;
                    int n = (nx + ny * w) * turns + nd;
                    if (--indeg[n] == 0 && !transient_[n]) next.add(n);
                }
            }
            if (round <= 8) layers.append(String.format(" %d:%d", round, layer.size()));
            layer = next;
        }
        System.out.printf("%nunreachable found in %d peeling rounds;%s ...%n", round, layers);
        System.out.printf("  local check (no live predecessor) finds %d of %d â€” %.1f%%%n",
                firstLayer, peeled, 100.0 * firstLayer / peeled);

        // Can in-degree zero be read straight off the navmap? Every predecessor of
        // (x,y,d) lies at the same pixel one step back, and step[d+32] = -step[d], so the
        // segment that would arrive here is the same pixels as the reverse step from here.
        // If that holds in the rasterisation, "no live predecessor" is just "the reverse
        // step is not passable" â€” one bit lookup, no search.
        int antipodal = 0;
        for (int d = 0; d < turns; d++) {
            if (map.stepX(d) == -map.stepX((d + turns / 2) % turns)
                    && map.stepY(d) == -map.stepY((d + turns / 2) % turns)) antipodal++;
        }
        System.out.printf("  step vectors antipodal for %d of %d headings%n", antipodal, turns);

        // The real question is infinite predecessors, i.e. membership in the core: an
        // infinite backward trajectory is an infinite forward one under reversed time, and
        // reversed time is the heading flipped by half a turn. Does alive() on the flipped
        // heading just answer it?
        String[] flipName = {"alive(d+32)", "any of d+31,32,33", "all of d+31,32,33",
                             "alive(d+32) && passable(d+32)",
                             "flip, move, alive(d+32)", "flip, move, gated",
                             "flip, move, any d+31,32,33", "flip, move, all d+31,32,33"};
        System.out.printf("%n  against the core (%d of %d live states have infinite predecessors):%n",
                live - peeled, live);
        for (int v = 0; v < flipName.length; v++) {
            int predictedCore = 0, falsePos = 0, falseNeg = 0;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if (map.oob(x, y)) continue;
                    for (int d = 0; d < turns; d++) {
                        int s = (x + y * w) * turns + d;
                        if (!alive[s]) continue;
                        int back = (d + turns / 2) % turns;
                        boolean predicted;
                        if (v == 0) {
                            predicted = map.alive(x, y, back);
                        } else if (v == 1) {
                            predicted = map.alive(x, y, (back + turns - 1) % turns)
                                    || map.alive(x, y, back)
                                    || map.alive(x, y, (back + 1) % turns);
                        } else if (v == 2) {
                            predicted = map.alive(x, y, (back + turns - 1) % turns)
                                    && map.alive(x, y, back)
                                    && map.alive(x, y, (back + 1) % turns);
                        } else if (v == 3) {
                            predicted = map.alive(x, y, back) && map.passable(x, y, back);
                        } else {
                            // Flip a half turn, take one step, then look at where you land.
                            int px = x + map.stepX(back), py = y + map.stepY(back);
                            if (px < 0 || py < 0 || px >= w || py >= h || map.oob(px, py)) {
                                predicted = false;
                            } else if (v == 4) {
                                predicted = map.alive(px, py, back);
                            } else if (v == 5) {
                                predicted = map.passable(x, y, back) && map.alive(px, py, back);
                            } else if (v == 6) {
                                predicted = map.alive(px, py, (back + turns - 1) % turns)
                                        || map.alive(px, py, back)
                                        || map.alive(px, py, (back + 1) % turns);
                            } else {
                                predicted = map.alive(px, py, (back + turns - 1) % turns)
                                        && map.alive(px, py, back)
                                        && map.alive(px, py, (back + 1) % turns);
                            }
                        }
                        boolean actual = !transient_[s];
                        if (predicted) predictedCore++;
                        if (predicted != actual) { if (predicted) falsePos++; else falseNeg++; }
                    }
                }
            }
            System.out.printf("  %-30s predicts %6d in core; %5d wrong (%d false pos, %d false neg)%n",
                    flipName[v], predictedCore, falsePos + falseNeg, falsePos, falseNeg);
        }

        // Where do the flip-move-alive disagreements sit? A wrapped heading would pile
        // them onto a few headings; a rasterisation tie would spread them.
        int[] errByHeading = new int[turns];
        int errs = 0, errsWithAsymSegment = 0, asymTotal = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (map.oob(x, y)) continue;
                for (int d = 0; d < turns; d++) {
                    int s = (x + y * w) * turns + d;
                    if (!alive[s]) continue;
                    int back = (d + turns / 2) % turns;
                    int px = x + map.stepX(back), py = y + map.stepY(back);
                    boolean inPlay = px >= 0 && py >= 0 && px < w && py < h && !map.oob(px, py);
                    boolean predicted = inPlay && map.alive(px, py, back);
                    // Does the same physical segment agree when swept the other way?
                    boolean asym = inPlay && map.passable(x, y, back) != map.passable(px, py, d);
                    if (asym) asymTotal++;
                    if (predicted != !transient_[s]) {
                        errs++;
                        errByHeading[d]++;
                        if (asym) errsWithAsymSegment++;
                    }
                }
            }
        }
        System.out.printf("%n  flip-move-alive errors by heading (%d total):%n  ", errs);
        for (int d = 0; d < turns; d++) {
            System.out.printf("%5d", errByHeading[d]);
            if (d % 16 == 15) System.out.print("\n  ");
        }
        System.out.printf("%n  segments that disagree when swept in reverse: %d "
                        + "(%d of the %d errors sit on one)%n",
                asymTotal, errsWithAsymSegment, errs);

        String[] variantName = {"reverse step, no gate", "reverse step, gated",
                                "true predecessor", "arriving segment only"};
        for (int v = 0; v < 4; v++) {
            int predictedUnreachable = 0, falsePos = 0, falseNeg = 0;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if (map.oob(x, y)) continue;
                    for (int d = 0; d < turns; d++) {
                        if (!map.alive(x, y, d)) continue;
                        boolean hasPred;
                        if (v < 2) {
                            // Flip 180, step once, then allow a turn of -1/0/+1 and ask
                            // whether any of those states is navigable.
                            int back = (d + turns / 2) % turns;
                            int px = x + map.stepX(back), py = y + map.stepY(back);
                            hasPred = false;
                            if (v == 1 && !map.passable(x, y, back)) {
                                // gated: the reverse step itself must be clear
                            } else if (px >= 0 && py >= 0 && px < w && py < h && !map.oob(px, py)) {
                                for (int t = -1; t <= 1; t++) {
                                    if (map.alive(px, py, Math.floorMod(back + t, turns))) hasPred = true;
                                }
                            }
                        } else if (v == 2) {
                            // What the dynamics actually require, for reference.
                            int px = x - map.stepX(d), py = y - map.stepY(d);
                            hasPred = false;
                            if (px >= 0 && py >= 0 && px < w && py < h && !map.oob(px, py)) {
                                for (int t = -1; t <= 1; t++) {
                                    int pd = Math.floorMod(d + t, turns);
                                    if (map.alive(px, py, pd) && map.constrainTurn(px, py, pd, -t) == -t) {
                                        hasPred = true;
                                    }
                                }
                            }
                        } else {
                            // Collapsed form: all three predecessors need the same clear
                            // segment, and each is then alive for free.
                            int px = x - map.stepX(d), py = y - map.stepY(d);
                            hasPred = px >= 0 && py >= 0 && px < w && py < h
                                    && !map.oob(px, py) && map.passable(px, py, d);
                        }
                        boolean predicted = !hasPred;
                        boolean actual = indegZero[(x + y * w) * turns + d];
                        if (predicted) predictedUnreachable++;
                        if (predicted != actual) { if (predicted) falsePos++; else falseNeg++; }
                    }
                }
            }
            System.out.printf("  %-22s predicts %6d; %5d wrong (%d false pos, %d false neg)%n",
                    variantName[v], predictedUnreachable, falsePos + falseNeg, falsePos, falseNeg);
        }

        int resid = 0, residTransient = 0, ambigTransient = 0, ambigTotal = 0;
        for (int s = 0; s < alive.length; s++) {
            if (mergeResidue[s]) { resid++; if (transient_[s]) residTransient++; }
            if (alive[s] && edge[s] < 0) { ambigTotal++; if (transient_[s]) ambigTransient++; }
        }
        System.out.printf("%nrecurrent core: %d of %d live states; %d peeled as unreachable%n",
                live - peeled, live, peeled);
        System.out.printf("  merge residue      %6d states, %d of them unreachable (%s)%n",
                resid, residTransient, resid == residTransient ? "all" : "NOT all");
        System.out.printf("  still unlabelled   %6d states, %d of them unreachable (%s)%n",
                ambigTotal, ambigTransient, ambigTotal == ambigTransient ? "all" : "NOT all");

        // ---- validate against the routes ----
        int checked = 0, wrong = 0;
        Map<String, Integer> failures = new java.util.TreeMap<>();
        List<String> examples = new ArrayList<>();
        for (String route : new String[]{"A", "B", "C", "D", "E"}) {
            List<String> rows = Files.readAllLines(outDir.resolve("route_" + route + ".csv"));
            for (int i = 1; i < rows.size(); i++) {
                String[] v = rows.get(i).split(",");
                int t = Integer.parseInt(v[0]);
                int x = Integer.parseInt(v[1]), y = Integer.parseInt(v[2]), d = Integer.parseInt(v[3]);
                if (map.oob(x, y) || !map.alive(x, y, d)) continue;
                byte idx = edge[(x + y * w) * turns + d];
                checked++;
                if (idx >= 0 && EDGES[idx].contains(route)) continue;
                wrong++;
                boolean inRegion = region[x + y * w] >= 0;
                failures.merge(route + " on " + (idx < 0 ? "unassigned" : EDGES[idx])
                        + (inRegion ? " (seeded)" : " (junction)"), 1, Integer::sum);
                if (examples.size() < 10) {
                    examples.add(String.format("  %s t=%d (%d,%d,d=%d) -> %s %s", route, t, x, y, d,
                            idx < 0 ? "unassigned" : EDGES[idx], inRegion ? "seeded" : "junction"));
                }
            }
        }
        System.out.printf("validation: %d route states checked, %d wrong%n", checked, wrong);
        for (var e : failures.entrySet()) System.out.printf("  %-32s %d%n", e.getKey(), e.getValue());
        examples.forEach(System.out::println);

        int[] perEdge = new int[EDGES.length];
        for (byte b : edge) if (b >= 0) perEdge[b]++;
        System.out.printf("%n%-8s %9s%n", "edge", "states");
        for (int i = 0; i < EDGES.length; i++) System.out.printf("%-8s %9d%n", EDGES[i], perEdge[i]);

        // The order each route meets its edges. If the map is right this reproduces the
        // stated circuits exactly, which checks the labelling and the claimed edge order
        // against each other rather than either against itself.
        System.out.println();
        int[] palette = {0xE41A1C, 0x377EB8, 0x4DAF4A, 0x984EA3, 0xFF7F00,
                0xA65628, 0xF781BF, 0x00CED1, 0x999999, 0x000000};
        for (String route : new String[]{"A", "B", "C", "D", "E"}) {
            List<String> rows = Files.readAllLines(outDir.resolve("route_" + route + ".csv"));
            List<int[]> path = new ArrayList<>();
            StringBuilder seq = new StringBuilder();
            int runStart = 0, prev = -2;
            for (int i = 1; i < rows.size(); i++) {
                String[] v = rows.get(i).split(",");
                int t = Integer.parseInt(v[0]);
                int x = Integer.parseInt(v[1]), y = Integer.parseInt(v[2]), d = Integer.parseInt(v[3]);
                path.add(new int[]{t, x, y, d});
                int idx = (map.oob(x, y) || !map.alive(x, y, d)) ? -1 : edge[(x + y * w) * turns + d];
                if (idx != prev) {
                    if (prev != -2) seq.append(prev < 0 ? "?" : EDGES[prev])
                            .append('[').append(runStart).append('-').append(t - 1).append("] ");
                    prev = idx;
                    runStart = t;
                }
            }
            seq.append(prev < 0 ? "?" : EDGES[prev]).append('[').append(runStart).append("-end]");
            System.out.printf("route %s: %s%n", route, seq);

            BufferedImage img = javax.imageio.ImageIO.read(preset.mapPath().toFile());
            int scale = 2;
            BufferedImage out = new BufferedImage(w * scale, h * scale, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < h * scale; y++) {
                for (int x = 0; x < w * scale; x++) {
                    int rgb = img.getRGB(x / scale, y / scale) & 0xFFFFFF;
                    out.setRGB(x, y, rgb == 0x000000 ? 0x000000
                            : rgb == 0xFF7F27 ? 0xF7E0CC : 0xE6E6E6);
                }
            }
            Graphics2D g = out.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setStroke(new java.awt.BasicStroke(2.4f, java.awt.BasicStroke.CAP_ROUND,
                    java.awt.BasicStroke.JOIN_ROUND));
            for (int i = 1; i < path.size(); i++) {
                int[] a = path.get(i - 1), b = path.get(i);
                if (Math.hypot(b[1] - a[1], b[2] - a[2]) > 20) continue;
                int idx = (map.oob(b[1], b[2]) || !map.alive(b[1], b[2], b[3]))
                        ? -1 : edge[(b[1] + b[2] * w) * turns + b[3]];
                g.setColor(new Color(idx < 0 ? 0x000000 : palette[idx]));
                g.drawLine(a[1] * scale, a[2] * scale, b[1] * scale, b[2] * scale);
            }
            g.dispose();
            javax.imageio.ImageIO.write(out, "png",
                    outDir.resolve("edges_" + route + ".png").toFile());
        }
        System.out.println();
        for (int i = 0; i < EDGES.length; i++) {
            System.out.printf("  %-6s #%06X%n", EDGES[i], palette[i]);
        }

        // Pixelwise view: one hue per edge, chosen by whichever edge owns the most headings
        // at that pixel, desaturated by half for every further edge sharing it. So a pixel
        // used by a single edge reads as its pure colour and a four-way crossing reads
        // almost grey, which makes the shared stretches obvious at a glance.
        BufferedImage px = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] shared = new int[6];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (map.oob(x, y)) { px.setRGB(x, y, 0x000000); continue; }
                int[] tally = new int[EDGES.length];
                int liveHere = 0;
                for (int d = 0; d < turns; d++) {
                    if (!map.alive(x, y, d)) continue;
                    liveHere++;
                    byte idx = edge[(x + y * w) * turns + d];
                    if (idx >= 0) tally[idx]++;
                }
                if (liveHere == 0) { px.setRGB(x, y, 0x202020); continue; }
                // Hue comes from a fixed priority â€” the first edge in canonical order
                // present at this pixel. Choosing by whichever edge owned the most headings
                // made the colour flip between neighbouring pixels over a one-heading
                // difference, which drowned the region boundaries in noise.
                int best = -1, distinct = 0;
                for (int i = 0; i < tally.length; i++) {
                    if (tally[i] == 0) continue;
                    distinct++;
                    if (best < 0) best = i;
                }
                if (best < 0) { px.setRGB(x, y, 0x606060); continue; }
                shared[Math.min(distinct, shared.length - 1)]++;
                float sat = (float) Math.pow(0.5, distinct - 1);
                px.setRGB(x, y, Color.HSBtoRGB(best / (float) EDGES.length, sat, 0.98f));
            }
        }
        BufferedImage big = new BufferedImage(w * 2, h * 2, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h * 2; y++) {
            for (int x = 0; x < w * 2; x++) big.setRGB(x, y, px.getRGB(x / 2, y / 2));
        }
        javax.imageio.ImageIO.write(big, "png", outDir.resolve("edgemap.png").toFile());
        System.out.printf("%npixels carrying n edges:");
        for (int i = 1; i < shared.length; i++) System.out.printf("  %d:%d", i, shared[i]);
        System.out.println();
        for (int i = 0; i < EDGES.length; i++) {
            System.out.printf("  %-6s hue %.2f  #%06X%n", EDGES[i], i / (float) EDGES.length,
                    Color.HSBtoRGB(i / (float) EDGES.length, 1f, 0.98f) & 0xFFFFFF);
        }

        Files.createDirectories(outDir);
        Files.write(outDir.resolve("edgemap.bin"), edge);
        StringBuilder doc = new StringBuilder();
        doc.append("edge map for ").append(preset.mapPath()).append('\n');
        doc.append("byte per state, index = (x + y*").append(w).append(")*")
                .append(turns).append(" + heading\n");
        doc.append("value -1 = not navigable or unassigned; otherwise:\n");
        for (int i = 0; i < EDGES.length; i++) doc.append("  ").append(i).append(" = ")
                .append(EDGES[i]).append('\n');
        Files.writeString(outDir.resolve("edgemap.txt"), doc.toString());
        System.out.println("\n-> " + outDir.resolve("edgemap.bin"));
    }

    private static String key(int region, boolean red, int d) {
        String half = red ? ((d > 16 && d < 48) ? "W" : "E") : ((d > 0 && d < 32) ? "S" : "N");
        return region + "/" + half;
    }

    /**
     * Which edge of the route graph each navigable {@code (x, y, heading)} belongs to.
     * <p>
     * An edge is a stretch between two vertices, and a vertex is where routes part or
     * join. So a state's edge is fixed by what it can still reach and what could have
     * reached it: from a state on the inbound edge of a decision point both outcomes are
     * still available, while a state past the decision can only reach the one it committed
     * to. The reverse holds at merges.
     * <p>
     * Reachability is taken in the state graph â€” successors of {@code (x, y, h)} are the
     * turns that survive the wall veto â€” and is truncated at the marker lines, so what is
     * computed is the set of markers reachable <em>first</em> rather than eventually.
     * Without that truncation every state upstream reaches everything and nothing
     * separates.
     */
    public static void edgeMap(PresetScenarioParameter preset, Path annotated, Path outDir)
            throws IOException {
        NavMap map = NavMapBuilder.buildFromPng(preset.mapPath(),
                Math.round(preset.turningRadius()));
        int w = map.width(), h = map.height(), turns = Params.TURNS;
        int states = w * h * turns;

        BufferedImage ann = javax.imageio.ImageIO.read(annotated.toFile());
        // Marker lines, in the order used for the signature bits.
        int[] markerColour = {0xED1C24, 0xFFAEC9, 0xC3C3C3};
        String[] markerName = {"red", "rose", "silver"};

        // Each colour has two separate lines; split them so the six are distinguishable.
        List<List<int[]>> lines = new ArrayList<>();
        List<String> lineNames0 = new ArrayList<>();
        List<String> lineNames = lineNames0;
        for (int c = 0; c < markerColour.length; c++) {
            List<int[]> px = new ArrayList<>();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if ((ann.getRGB(x, y) & 0xFFFFFF) == markerColour[c] && !map.oob(x, y)) {
                        px.add(new int[]{x, y});
                    }
                }
            }
            // Split into connected components. Sorting by x+y and cutting at the largest
            // gap fails whenever two lines occupy overlapping diagonals, which the silver
            // pair does: their x+y ranges interleave and the cut lands mid-line.
            Set<Long> pool = new HashSet<>();
            for (int[] p : px) pool.add(((long) p[0] << 20) | p[1]);
            int part = 0;
            while (!pool.isEmpty()) {
                long seed = pool.iterator().next();
                ArrayDeque<Long> q = new ArrayDeque<>();
                q.add(seed);
                pool.remove(seed);
                List<int[]> blob = new ArrayList<>();
                while (!q.isEmpty()) {
                    long k = q.poll();
                    int bx = (int) (k >> 20), by = (int) (k & 0xFFFFF);
                    blob.add(new int[]{bx, by});
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            long nk = ((long) (bx + dx) << 20) | (by + dy);
                            if (pool.remove(nk)) q.add(nk);
                        }
                    }
                }
                lines.add(blob);
                lineNames.add(markerName[c] + "-" + (++part));
            }
        }

        // dabnt walls cut the rose lines into fragments, so components alone over-count
        // the markers. Fragments sharing an axis and position are one line.
        List<List<int[]>> merged = new ArrayList<>();
        List<String> mergedNames = new ArrayList<>();
        List<int[]> mergedAxis = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            List<int[]> blob = lines.get(i);
            int minX = Integer.MAX_VALUE, maxX = -1, minY = Integer.MAX_VALUE, maxY = -1;
            for (int[] p : blob) {
                minX = Math.min(minX, p[0]); maxX = Math.max(maxX, p[0]);
                minY = Math.min(minY, p[1]); maxY = Math.max(maxY, p[1]);
            }
            boolean vertical = (maxX - minX) < (maxY - minY);
            int at = vertical ? minX : minY;
            int found = -1;
            for (int j = 0; j < merged.size(); j++) {
                if (mergedAxis.get(j)[0] == (vertical ? 1 : 0) && mergedAxis.get(j)[1] == at
                        && mergedNames.get(j).startsWith(lineNames.get(i).split("-")[0])) {
                    found = j;
                }
            }
            if (found < 0) {
                merged.add(new ArrayList<>(blob));
                mergedNames.add(lineNames.get(i).split("-")[0] + "@" + at);
                mergedAxis.add(new int[]{vertical ? 1 : 0, at});
            } else {
                merged.get(found).addAll(blob);
            }
        }
        lines = merged;
        lineNames = mergedNames;
        for (int i = 0; i < lines.size(); i++) {
            System.out.printf("  %-9s %3d navigable px%n", lineNames.get(i), lines.get(i).size());
        }

        // A marker is a line the boid steps across, not a pixel it lands on: the lines are
        // one pixel wide and a step covers nearly four, so occupancy misses almost every
        // crossing. Each line becomes a span, and a transition is tested against it.
        int[][] span = new int[lines.size()][];      // {vertical? 1 : 0, at, lo, hi}
        for (int i = 0; i < lines.size(); i++) {
            List<int[]> px = lines.get(i);
            int minX = Integer.MAX_VALUE, maxX = -1, minY = Integer.MAX_VALUE, maxY = -1;
            for (int[] p : px) {
                minX = Math.min(minX, p[0]); maxX = Math.max(maxX, p[0]);
                minY = Math.min(minY, p[1]); maxY = Math.max(maxY, p[1]);
            }
            boolean vertical = (maxX - minX) < (maxY - minY);
            span[i] = vertical ? new int[]{1, minX, minY, maxY} : new int[]{0, minY, minX, maxX};
            System.out.printf("  %-9s %s at %d, spanning %d-%d%n", lineNames.get(i),
                    vertical ? "vertical" : "horizontal", span[i][1], span[i][2], span[i][3]);
        }

        // Reverse BFS from each marker. Seeds are the states a marker-crossing step leaves
        // from; expansion stops at any step that crosses any marker, so what accumulates is
        // the set of markers reachable first.
        int[] signature = new int[states];
        for (int m = 0; m < lines.size(); m++) {
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            boolean[] seen = new boolean[states];

            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if (map.oob(x, y)) continue;
                    for (int d = 0; d < turns; d++) {
                        if (!map.alive(x, y, d)) continue;
                        for (int t = -1; t <= 1; t++) {
                            if (map.constrainTurn(x, y, d, t) != t) continue;
                            int nd = Math.floorMod(d + t, turns);
                            int nx = x + map.stepX(nd), ny = y + map.stepY(nd);
                            if (crosses(span[m], x, y, nx, ny)) {
                                int s = (x + y * w) * turns + d;
                                if (!seen[s]) { seen[s] = true; signature[s] |= (1 << m); queue.add(s); }
                            }
                        }
                    }
                }
            }

            while (!queue.isEmpty()) {
                int s = queue.poll();
                int d = s % turns, cell = s / turns, x = cell % w, y = cell / w;
                int px = x - map.stepX(d), py = y - map.stepY(d);
                if (px < 0 || py < 0 || px >= w || py >= h || map.oob(px, py)) continue;
                boolean blocked = false;
                for (int[] sp : span) if (crosses(sp, px, py, x, y)) blocked = true;
                if (blocked) continue;
                for (int t = -1; t <= 1; t++) {
                    int pd = Math.floorMod(d - t, turns);
                    if (!map.alive(px, py, pd)) continue;
                    if (map.constrainTurn(px, py, pd, t) != t) continue;
                    int p = (px + py * w) * turns + pd;
                    if (seen[p]) continue;
                    seen[p] = true;
                    signature[p] |= (1 << m);
                    queue.add(p);
                }
            }
        }

        // Backward half of the same rule. At a merge, a state reachable from both inbound
        // edges is on the outbound one; reachable from only one, it is still on that one.
        // Forward BFS from where each marker-crossing lands, stopping at markers again.
        int[] behind = new int[states];
        for (int m = 0; m < lines.size(); m++) {
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            boolean[] seen = new boolean[states];

            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if (map.oob(x, y)) continue;
                    for (int d = 0; d < turns; d++) {
                        if (!map.alive(x, y, d)) continue;
                        for (int t = -1; t <= 1; t++) {
                            if (map.constrainTurn(x, y, d, t) != t) continue;
                            int nd = Math.floorMod(d + t, turns);
                            int nx = x + map.stepX(nd), ny = y + map.stepY(nd);
                            if (nx < 0 || ny < 0 || nx >= w || ny >= h || map.oob(nx, ny)) continue;
                            if (!crosses(span[m], x, y, nx, ny)) continue;
                            int s = (nx + ny * w) * turns + nd;
                            if (!seen[s]) { seen[s] = true; behind[s] |= (1 << m); queue.add(s); }
                        }
                    }
                }
            }

            while (!queue.isEmpty()) {
                int s = queue.poll();
                int d = s % turns, cell = s / turns, x = cell % w, y = cell / w;
                for (int t = -1; t <= 1; t++) {
                    if (map.constrainTurn(x, y, d, t) != t) continue;
                    int nd = Math.floorMod(d + t, turns);
                    int nx = x + map.stepX(nd), ny = y + map.stepY(nd);
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h || map.oob(nx, ny)) continue;
                    if (!map.alive(nx, ny, nd)) continue;
                    boolean blocked = false;
                    for (int[] sp : span) if (crosses(sp, x, y, nx, ny)) blocked = true;
                    if (blocked) continue;
                    int n = (nx + ny * w) * turns + nd;
                    if (seen[n]) continue;
                    seen[n] = true;
                    behind[n] |= (1 << m);
                    queue.add(n);
                }
            }
        }

        // An edge is a (behind, ahead) pair: what could have led here, and what is still
        // reachable. Either alone merges distinct stretches that share an outlook.
        Map<Long, Integer> counts = new java.util.LinkedHashMap<>();
        Map<Long, Map<String, Integer>> byRoute = new java.util.LinkedHashMap<>();
        int live = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (map.oob(x, y)) continue;
                for (int d = 0; d < turns; d++) {
                    if (!map.alive(x, y, d)) continue;
                    live++;
                    int s = (x + y * w) * turns + d;
                    counts.merge(((long) behind[s] << 8) | signature[s], 1, Integer::sum);
                }
            }
        }
        for (String name : new String[]{"A", "B", "C", "D", "E"}) {
            Path f = outDir.resolve("route_" + name + ".csv");
            if (!Files.exists(f)) continue;
            List<String> rows = Files.readAllLines(f);
            for (int i = 1; i < rows.size(); i++) {
                String[] v = rows.get(i).split(",");
                int x = Integer.parseInt(v[1]), y = Integer.parseInt(v[2]), d = Integer.parseInt(v[3]);
                if (map.oob(x, y) || !map.alive(x, y, d)) continue;
                int s = (x + y * w) * turns + d;
                byRoute.computeIfAbsent(((long) behind[s] << 8) | signature[s],
                        k -> new java.util.TreeMap<>()).merge(name, 1, Integer::sum);
            }
        }

        System.out.printf("%n%d live states, %d (behind, ahead) classes, "
                + "%d of them visited by a route%n", live, counts.size(), byRoute.size());
        System.out.printf("%n%-24s %-24s %8s   %s%n", "behind", "ahead", "states", "routes");
        List<Long> keys = new ArrayList<>(byRoute.keySet());
        keys.sort((p, q) -> counts.get(q) - counts.get(p));
        for (long k : keys) {
            System.out.printf("%-24s %-24s %8d   %s%n",
                    describeMarkers((int) (k >> 8), lineNames),
                    describeMarkers((int) (k & 0xFF), lineNames),
                    counts.get(k), byRoute.get(k));
        }
    }

    private static String describeMarkers(int mask, List<String> names) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if ((mask & (1 << i)) != 0) sb.append(sb.length() > 0 ? "+" : "").append(names.get(i));
        }
        return sb.length() == 0 ? "-" : sb.toString();
    }

    /**
     * Does the step from {@code (px,py)} to {@code (x,y)} cross a marker line?
     *
     * @param span {@code {vertical?, at, lo, hi}} â€” the line's axis, position and extent
     */
    private static boolean crosses(int[] span, int px, int py, int x, int y) {
        boolean vertical = span[0] == 1;
        int a = vertical ? px : py, b = vertical ? x : y;
        if ((a < span[1]) == (b < span[1])) return false;      // both sides, no crossing
        int lo = vertical ? Math.min(py, y) : Math.min(px, x);
        int hi = vertical ? Math.max(py, y) : Math.max(px, x);
        return hi >= span[2] && lo <= span[3];
    }

    /** The tick at which a route crosses the scoring-loop gate, or -1 if it never does. */
    private static int gateTick(Engine engine, int x0, int y0, int d0, List<int[]> overrides) {
        List<String> cross = new ArrayList<>();
        traceRoute(engine, x0, y0, d0, overrides, 4000, cross);
        for (String c : cross) {
            if (c.startsWith("gate-S")) {
                return Integer.parseInt(c.substring(c.indexOf('@') + 1, c.indexOf(' ')));
            }
        }
        return -1;
    }

    /**
     * Which phase differences let a leader on route D pull an ordinary boid out of route A.
     * <p>
     * Two boids. The leader is placed at route D's own state at time-on-lap {@code delta}
     * and given route D's overrides shifted back by {@code delta}, so it flies exactly the
     * recorded circuit from wherever in it it started. The follower begins a fresh lap at
     * the start point with no overrides at all â€” every turn it makes is the flock's doing.
     * Whether it leaves route A is then a property of the phase difference alone.
     * <p>
     * Exiting is read off the edge map rather than off the score, because the two are not
     * the same event: the score arrives a few hundred ticks later, and by then a second
     * decision has happened. Landing on BC means exit 1 was taken, on DE exit 2.
     * <p>
     * The leader is boid 0 and the follower boid 1, so the leader moves first each tick.
     * That is the ordering a real leader would have, but it is an ordering, and a follower
     * indexed ahead of its leader may not behave identically.
     */
    public static void dabLeaderWindow(PresetScenarioParameter preset, int x0, int y0, int d0,
                                       int maxDelta, String routeName) throws IOException {
        MapStore.Ingest ingest = preset.ingest();
        byte[] edge = Files.readAllBytes(ingest.output("routes", "edgemap.bin"));
        NavMap map = NavMapBuilder.buildFromPng(preset.mapPath(),
                Math.round(preset.turningRadius()));
        int w = map.width(), turns = Params.TURNS;

        Engine solo = new Boids2DEngine(withFlockSize(preset, 1));
        int second = PsyboidSearch.SECOND;
        int exit2 = 122;
        int exit1 = 19;
        int gateSofC = gateTick(solo, x0, y0, d0, List.of(new int[]{exit1, 4 * second, +1}));
        int gateSofE = gateTick(solo, x0, y0, d0, List.of(new int[]{exit2, 4 * second, +1}));
        List<int[]> schedule = switch (routeName) {
            case "B" -> List.of(new int[]{exit1, 4 * second, +1},
                    new int[]{gateSofC - 5, second, +1});
            case "C" -> List.of(new int[]{exit1, 4 * second, +1});
            case "D" -> List.of(new int[]{exit2, 4 * second, +1},
                    new int[]{gateSofE - 5, second, +1});
            case "E" -> List.of(new int[]{exit2, 4 * second, +1});
            default -> throw new IllegalArgumentException("not a non-A route: " + routeName);
        };
        List<int[]> trace = traceRoute(solo, x0, y0, d0, schedule, 4000, new ArrayList<>());

        Engine pair = new Boids2DEngine(withFlockSize(preset, 2));
        System.out.printf("%n=== route %s: lap %d ticks, sweeping phase difference 0-%d ===%n",
                routeName, trace.size() - 1, maxDelta);

        char[] outcome = new char[maxDelta + 1];
        int[] leaderDrift = new int[maxDelta + 1];
        int[] exitTick = new int[maxDelta + 1];
        Arrays.fill(leaderDrift, -1);
        Arrays.fill(exitTick, -1);
        for (int delta = 0; delta <= maxDelta; delta++) {
            int[] at = trace.get(delta);

            // Hold the leader straight for the whole run, then let route D's own overrides
            // land on top. The recorded route was flown by a lone boid, which had nothing
            // to steer towards and so proposed straight everywhere outside those two
            // windows â€” so forcing straight reproduces it exactly while making the leader
            // deaf to the follower. Two shifted windows alone are not enough: outside them
            // the leader flocks, and one other boid is sufficient to push it off the
            // circuit long before the follower decides anything.
            List<PsyboidOverride> ov = new ArrayList<>();
            ov.add(new PsyboidOverride(0, 10000, 0, 0));
            // Laps, not one lap: a leader most of a circuit ahead reaches its own next
            // exit-2 decision inside the window being measured, and without that lap's
            // override it would fly straight past and stop being on route D.
            for (int[] o : schedule) {
                int onset = o[0] - delta;
                if (onset >= 0) ov.add(new PsyboidOverride(onset, o[1], o[2], 0));
                else if (onset + o[1] > 0) {
                    ov.add(new PsyboidOverride(0, onset + o[1], o[2], 0));
                }
            }

            Sim.State s = new Sim.State(2,
                    new int[]{at[1], x0}, new int[]{at[2], y0}, new int[]{at[3], d0},
                    0L, 0L, new long[2], "leader",
                    ov.toArray(new PsyboidOverride[0]));

            char result = '.';
            int when = -1, drift = -1;
            for (int t = 1; t <= 280; t++) {
                s = pair.tick(s);
                if (result == '.') {
                    byte e = edge[(s.x[1] + s.y[1] * w) * turns + s.h[1]];
                    if (e == 3) { result = '1'; when = t; }        // BC: took exit 1
                    else if (e == 4) { result = '2'; when = t; }   // DE: took exit 2
                }
                // Is the leader still flying the circuit it was placed on? If the follower
                // pushes it off route D, the phase difference is no longer what is being
                // measured and the row means nothing.
                int i = delta + t;
                if (drift < 0 && i < trace.size()) {
                    int[] want = trace.get(i);
                    if (s.x[0] != want[1] || s.y[0] != want[2] || s.h[0] != want[3]) drift = t;
                }
            }
            outcome[delta] = result;
            if (drift >= 0) leaderDrift[delta] = drift;
            if (when >= 0) exitTick[delta] = when;
        }

        System.out.printf("%n  '.' stayed on route A   '1' took exit 1   '2' took exit 2%n");
        for (int row = 0; row <= maxDelta; row += 50) {
            System.out.printf("  %4d  ", row);
            for (int d = row; d < Math.min(row + 50, maxDelta + 1); d++) {
                System.out.print(outcome[d]);
            }
            System.out.println();
        }

        System.out.printf("%n%-10s %s%n", "exit", "phase differences that induce it");
        for (char which : new char[]{'1', '2'}) {
            StringBuilder runs = new StringBuilder();
            int start = -1;
            for (int d = 0; d <= maxDelta + 1; d++) {
                boolean hit = d <= maxDelta && outcome[d] == which;
                if (hit && start < 0) start = d;
                if (!hit && start >= 0) {
                    runs.append(runs.length() > 0 ? ", " : "")
                            .append(start == d - 1 ? String.valueOf(start) : start + "-" + (d - 1));
                    start = -1;
                }
            }
            System.out.printf("exit %-5c %s%n", which, runs.length() > 0 ? runs : "none");
        }

        int clean = 0, contaminated = 0;
        for (int d = 0; d <= maxDelta; d++) {
            if (leaderDrift[d] < 0) clean++; else contaminated++;
        }
        System.out.printf("%nleader held route %s for all 280 ticks in %d of %d runs%n",
                routeName, clean, maxDelta + 1);
        if (contaminated > 0) {
            System.out.print("  pushed off at (delta:tick)");
            for (int d = 0, shown = 0; d <= maxDelta && shown < 14; d++) {
                if (leaderDrift[d] >= 0) { System.out.printf(" %d:%d", d, leaderDrift[d]); shown++; }
            }
            System.out.println();
        }
        int first = Integer.MAX_VALUE, last = -1;
        for (int d = 0; d <= maxDelta; d++) {
            if (exitTick[d] >= 0) { first = Math.min(first, exitTick[d]); last = Math.max(last, exitTick[d]); }
        }
        if (last >= 0) System.out.printf("  follower reached the exit edge at tick %d-%d%n",
                first, last);
    }

    /**
     * Lines up a route D leader's crossing of {@code y < 57} with a route A follower's
     * crossing of {@code x < 163}, and renders the moment.
     * <p>
     * Both crossings are read off single-boid traces, so each is a property of its circuit
     * alone. The follower starts its lap at tick 0, so its crossing is at its own trace
     * index; the leader placed {@code delta} ticks ahead reaches its crossing that many
     * ticks sooner. Aligning them is therefore just the difference of the two indices.
     */
    public static void dabAlign(PresetScenarioParameter preset, int x0, int y0, int d0)
            throws IOException {
        Engine solo = new Boids2DEngine(withFlockSize(preset, 1));
        int second = PsyboidSearch.SECOND, exit2 = 122;
        int gateSofE = gateTick(solo, x0, y0, d0, List.of(new int[]{exit2, 4 * second, +1}));
        List<int[]> lapPlan = List.of(new int[]{exit2, 4 * second, +1},
                new int[]{gateSofE - 5, second, +1});

        List<int[]> routeD = traceRoute(solo, x0, y0, d0, lapPlan, 4000, new ArrayList<>());
        List<int[]> routeA = traceRoute(solo, x0, y0, d0, List.of(), 4000, new ArrayList<>());

        int tD = -1, tA = -1;
        for (int i = 0; i < routeD.size() && tD < 0; i++) if (routeD.get(i)[2] < 57) tD = i;
        for (int i = 0; i < routeA.size() && tA < 0; i++) if (routeA.get(i)[1] < 163) tA = i;
        int lap = routeD.size() - 1;
        System.out.printf("route D first has y < 57 at tick %d %s%n", tD,
                tD < 0 ? "" : Arrays.toString(routeD.get(tD)));
        System.out.printf("route A first has x < 163 at tick %d %s%n", tA,
                tA < 0 ? "" : Arrays.toString(routeA.get(tA)));
        if (tD < 0 || tA < 0) return;

        int delta = Math.floorMod(tD - tA, lap);
        System.out.printf("%nphase shift to align them: %d - %d = %d (mod %d lap = %d)%n",
                tD, tA, tD - tA, lap, delta);

        Engine pair = new Boids2DEngine(withFlockSize(preset, 2));
        int[] at = routeD.get(delta % routeD.size());
        List<PsyboidOverride> ov = new ArrayList<>();
        ov.add(new PsyboidOverride(0, 10000, 0, 0));
        for (int[] o : lapPlan) {
            int onset = o[0] - delta;
            if (onset >= 0) ov.add(new PsyboidOverride(onset, o[1], o[2], 0));
            else if (onset + o[1] > 0) ov.add(new PsyboidOverride(0, onset + o[1], o[2], 0));
        }
        Sim.State s = new Sim.State(2, new int[]{at[1], x0}, new int[]{at[2], y0},
                new int[]{at[3], d0}, 0L, 0L, new long[2], "align",
                ov.toArray(new PsyboidOverride[0]));

        byte[] edge = Files.readAllBytes(preset.ingest().output("routes", "edgemap.bin"));
        NavMap map = NavMapBuilder.buildFromPng(preset.mapPath(),
                Math.round(preset.turningRadius()));
        Boids2DRenderer renderer = new Boids2DRenderer(preset.mapPath(), 3);

        Path out = preset.ingest().output("routes", "align_" + delta + ".png");
        String exited = "no";
        for (int t = 1; t <= 280; t++) {
            s = pair.tick(s);
            if (t == tA) {
                System.out.printf("%nat the aligned tick %d:%n", t);
                System.out.printf("  leader   (%3d,%3d,%2d)  lap time %d%n",
                        s.x[0], s.y[0], s.h[0], delta + t);
                System.out.printf("  follower (%3d,%3d,%2d)  lap time %d%n",
                        s.x[1], s.y[1], s.h[1], t);
                javax.imageio.ImageIO.write(renderer.render(s), "png", out.toFile());
            }
            if (exited.equals("no")) {
                byte e = edge[(s.x[1] + s.y[1] * map.width()) * Params.TURNS + s.h[1]];
                if (e == 3) exited = "exit 1 at tick " + t;
                else if (e == 4) exited = "exit 2 at tick " + t;
            }
        }
        System.out.printf("%nfollower exited: %s%nrendered %s%n", exited, out);
    }

    /**
     * Does an unsteered flock fall into an orbit, and does that orbit score?
     * <p>
     * The dynamics are deterministic and the state space is finite, so every run must
     * eventually repeat a state and cycle forever after. That makes the question exactly
     * answerable rather than a matter of watching a score curve flatten: find the repeat,
     * then run one full period and see what it scores. Zero means the flock has settled
     * into a stable non-scoring orbit and only a psyboid can score from there.
     */
    public static List<int[]> settling(PresetScenarioParameter preset, int[] flockSizes,
                                       int seeds, int maxTicks) throws IOException {
        List<int[]> scoring = new ArrayList<>();
        System.out.printf("%n=== %s @%s: unsteered settling, %d seeds, up to %d ticks ===%n",
                preset.name(), preset.ingest().hash(), seeds, maxTicks);
        System.out.printf("%n%-6s %-6s %9s %9s %12s %12s   %s%n", "boids", "seed", "settles",
                "period", "score/cycle", "score/1k", "verdict");

        for (int n : flockSizes) {
            Engine engine = new Boids2DEngine(withFlockSize(preset, n));
            for (int seed = 0; seed < seeds; seed++) {
                Sim.State s = engine.init(seed);
                Map<String, Integer> seen = new java.util.HashMap<>();
                int start = -1, period = -1;
                for (int t = 0; t <= maxTicks; t++) {
                    String key = key(s);
                    Integer before = seen.putIfAbsent(key, t);
                    if (before != null) { start = before; period = t - before; break; }
                    s = engine.tick(s);
                }
                if (period < 0) {
                    System.out.printf("%-6d %-6d %9s %9s %12s %12s   %s%n", n, seed,
                            "no", ">" + maxTicks, "-", "-", "never repeated a state");
                    continue;
                }
                // One full period from the cycle, so the transient contributes nothing.
                long before = s.score;
                for (int t = 0; t < period; t++) s = engine.tick(s);
                long gained = s.score - before;
                System.out.printf("%-6d %-6d %9d %9d %12d %12.1f   %s%n", n, seed, start,
                        period, gained, 1000.0 * gained / period,
                        gained == 0 ? "stable, no score" : "ORBIT SCORES");
                if (gained > 0) scoring.add(new int[]{n, seed, start, period});
            }
        }
        return scoring;
    }

    /**
     * Ticks where a boid crosses one of the map's marked zones the wrong way round.
     * <p>
     * The zones are ordinary floor to the engine â€” neither black nor scoring orange â€” so
     * they change nothing about how a boid flies. They are there to be looked at.
     */
    public static void zoneEvents(PresetScenarioParameter preset, int n, int seed,
                                  int from, int scan, int maxFrames) throws IOException {
        final int GREEN = 0x22B14C, BLUE = 0x3F48CC;
        BufferedImage img = javax.imageio.ImageIO.read(preset.ingest().png().toFile());
        NavMap map = NavMapBuilder.buildFromPng(preset.mapPath(),
                Math.round(preset.turningRadius()));
        Engine engine = new Boids2DEngine(withFlockSize(preset, n));
        Boids2DRenderer renderer = new Boids2DRenderer(preset.mapPath(), 3);

        Sim.State s = engine.init(seed);
        for (int t = 0; t < from; t++) s = engine.tick(s);

        System.out.printf("%nseed %d, scanning ticks %d-%d for right-on-green / left-on-blue%n",
                seed, from, from + scan);
        int green = 0, blue = 0;
        int[] shown = new int[2], lastFrame = {from - 41, from - 41};
        List<Path> frames = new ArrayList<>();
        for (int t = from; t < from + scan; t++) {
            String what = null;
            int who = -1, kind = -1;
            for (int i = 0; i < s.n; i++) {
                int rgb = img.getRGB(s.x[i], s.y[i]) & 0xFFFFFF;
                int dx = map.stepX(s.h[i]);
                if (rgb == GREEN && dx > 0) { what = "right on green"; who = i; kind = 0; green++; }
                else if (rgb == BLUE && dx < 0) { what = "left on blue"; who = i; kind = 1; blue++; }
            }
            // Both kinds get frames, rather than whichever happens to fire first filling
            // the quota â€” blue events are four times rarer.
            if (what != null && shown[kind] < maxFrames / 2 && t - lastFrame[kind] >= 40) {
                Path out = preset.ingest().output("zones",
                        "seed%d_t%d_%s.png".formatted(seed, t, what.replace(' ', '_')));
                javax.imageio.ImageIO.write(renderer.render(s), "png", out.toFile());
                System.out.printf("  tick %-7d boid %d  %-16s (%3d,%3d,%2d)  score %d%n",
                        t, who, what, s.x[who], s.y[who], s.h[who], s.score);
                frames.add(out);
                lastFrame[kind] = t;
                shown[kind]++;
            }
            s = engine.tick(s);
        }
        System.out.printf("  %d right-on-green tick-boids, %d left-on-blue, %d frames written%n",
                green, blue, frames.size());
    }

    /** Frames from a settled orbit, for looking at what the flock has locked into. */
    public static void orbitFrames(PresetScenarioParameter preset, int n, int seed,
                                   int from, int count, int stride) throws IOException {
        Engine engine = new Boids2DEngine(withFlockSize(preset, n));
        Boids2DRenderer renderer = new Boids2DRenderer(preset.mapPath(), 3);
        Sim.State s = engine.init(seed);
        for (int t = 0; t < from; t++) s = engine.tick(s);

        System.out.printf("%nseed %d, frames from tick %d every %d%n", seed, from, stride);
        for (int i = 0; i < count; i++) {
            long before = s.score;
            Path out = preset.ingest().output("orbit",
                    "seed%d_t%d.png".formatted(seed, from + i * stride));
            javax.imageio.ImageIO.write(renderer.render(s), "png", out.toFile());
            System.out.printf("  %-42s score %d%n", out.getFileName(), s.score);
            for (int t = 0; t < stride; t++) s = engine.tick(s);
            if (i == 0) System.out.printf("  (scoring %d over the following %d ticks)%n",
                    s.score - before, stride);
        }
    }

    /** Position and heading of every boid â€” the whole of the state the dynamics see. */
    private static String key(Sim.State s) {
        StringBuilder sb = new StringBuilder(s.n * 12);
        for (int i = 0; i < s.n; i++) {
            sb.append(s.x[i]).append(',').append(s.y[i]).append(',').append(s.h[i]).append(';');
        }
        return sb.toString();
    }

    /** The turn a traced route takes going from step {@code i} to {@code i + 1}. */
    private static int turns(List<int[]> trace, int i) {
        int from = trace.get(i)[3], to = trace.get(i + 1)[3];
        return Math.floorMod(to - from + Params.TURNS / 2, Params.TURNS) - Params.TURNS / 2;
    }

    /** Traces all five circuits and writes each to its own file. */
    public static void traceDabRoutes(PresetScenarioParameter preset, int x0, int y0, int d0,
                                      Path outDir) throws IOException {
        Engine engine = new Boids2DEngine(withFlockSize(preset, 1));
        Files.createDirectories(outDir);
        int second = PsyboidSearch.SECOND;

        System.out.printf("%s from (%d, %d) heading %d%n%n", preset.name(), x0, y0, d0);

        NavMap map = NavMapBuilder.buildFromPng(preset.mapPath(),
                Math.round(preset.turningRadius()));
        // Outer loop is the lavender of the annotation; the scoring loop is the map's own
        // orange. Centroids of each serve as the pivot for measuring which way round a
        // route goes.
        BufferedImage ann = javax.imageio.ImageIO.read(
                Path.of("areas", "dab_annotated.png").toFile());
        double lx = 0, ly = 0, ln = 0, sx = 0, sy = 0, sn = 0;
        for (int y = 0; y < ann.getHeight(); y++) {
            for (int x = 0; x < ann.getWidth(); x++) {
                int rgb = ann.getRGB(x, y) & 0xFFFFFF;
                if (rgb == 0xC8BFE7) { lx += x; ly += y; ln++; }
                if (rgb == 0xFF7F27) { sx += x; sy += y; sn++; }
            }
        }
        final double lavX = lx / ln, lavY = ly / ln, scoX = sx / sn, scoY = sy / sn;
        java.util.function.BiPredicate<Integer, Integer> inLavender =
                (x, y) -> (ann.getRGB(x, y) & 0xFFFFFF) == 0xC8BFE7;
        java.util.function.BiPredicate<Integer, Integer> inScoring =
                (x, y) -> map.score(x, y) > 0;

        // Exit 1 is gate-A (tick 22), exit 2 is gate-B (tick 122); the second override for
        // B and D goes at whichever tick C and E reach the scoring gate.
        // Exit 1 fires at 19 rather than 22 so that B and C finish within a tick of D and
        // E. The scoring-loop branch is passed about three ticks before the gate line, so
        // the second override starts five ticks early to still be running at the choice.
        final int exit1 = 19, exit2 = 122;
        int gateSofC = gateTick(engine, x0, y0, d0, List.of(new int[]{exit1, 4 * second, +1}));
        int gateSofE = gateTick(engine, x0, y0, d0, List.of(new int[]{exit2, 4 * second, +1}));
        System.out.printf("exit 1 at %d -> gate-S at %d;  exit 2 at %d -> gate-S at %d%n",
                exit1, gateSofC, exit2, gateSofE);

        record Plan(String name, List<int[]> overrides, String description) {}
        List<Plan> plans = List.of(
                new Plan("A", List.of(), "main loop anticlockwise, no score"),
                new Plan("C", List.of(new int[]{exit1, 4 * second, +1}),
                        "exit 1, anticlockwise outer, clockwise scoring"),
                new Plan("E", List.of(new int[]{exit2, 4 * second, +1}),
                        "exit 2, clockwise outer, clockwise scoring"),
                new Plan("B", List.of(new int[]{exit1, 4 * second, +1},
                        new int[]{gateSofC - 5, second, +1}),
                        "exit 1, anticlockwise outer, anticlockwise scoring"),
                new Plan("D", List.of(new int[]{exit2, 4 * second, +1},
                        new int[]{gateSofE - 5, second, +1}),
                        "exit 2, clockwise outer, anticlockwise scoring"));

        System.out.printf("%n%-6s %7s %7s %7s %9s %9s   %s%n", "route", "ticks",
                "closed", "scores", "outer", "scoring", "gates");
        System.out.println("  (winding: positive = clockwise as drawn)");
        for (Plan plan : plans) {
            List<String> cross = new ArrayList<>();
            List<int[]> r = traceRoute(engine, x0, y0, d0, plan.overrides(), 4000, cross);
            int scoring = 0;
            for (int[] p : r) if (p[0] >= 0 && map.score(p[1], p[2]) > 0) scoring++;

            List<int[]> marks = new ArrayList<>();
            for (String c : cross) {
                String pos = c.substring(c.indexOf('(') + 1, c.indexOf(')'));
                marks.add(new int[]{Integer.parseInt(pos.split(",")[0]),
                        Integer.parseInt(pos.split(",")[1])});
            }
            System.out.printf("%-6s %7d %7s %7d %+9.2f %+9.2f   %s%n",
                    plan.name(), r.size() - 1, r.get(r.size() - 1)[0] < 0 ? "yes" : "no",
                    scoring, winding(r, lavX, lavY, inLavender),
                    winding(r, scoX, scoY, inScoring), cross);
            write(outDir.resolve("route_" + plan.name() + ".csv"), r);
            drawRoute(withFlockSize(preset, 1), r, marks,
                    outDir.resolve("route_" + plan.name() + ".png"), 2);
        }
        System.out.printf("%nouter-loop pivot (%.0f, %.0f), scoring pivot (%.0f, %.0f)%n",
                lavX, lavY, scoX, scoY);
        System.out.println("-> " + outDir);

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

    /** One row of {@code data/searches.tsv}, enough to replay the line it records. */
    private record JournalRow(String context, long seed, int psyboid, int spacing,
                              int commits, String label) {}

    private static List<JournalRow> readJournal(Path file, String contextContains)
            throws IOException {
        List<JournalRow> rows = new ArrayList<>();
        List<String> lines = Files.readAllLines(file);
        for (int i = 1; i < lines.size(); i++) {          // row 0 is the header
            String[] f = lines.get(i).split("\t", -1);
            if (f.length < 14) continue;
            if (!f[1].contains(contextContains)) continue;
            rows.add(new JournalRow(f[1], Long.parseLong(f[2]), Integer.parseInt(f[3]),
                    Integer.parseInt(f[7]), Integer.parseInt(f[12]), f[13]));
        }
        return rows;
    }

    /** Discounted score over a window: index 0 is the flock total, 1..n are per boid. */
    private static double[] discountedScores(Engine engine, Sim.State from,
                                             int lookahead, double alpha) {
        Sim.State s = from;
        long prevTotal = s.score;
        long[] prevBoid = s.boidScore.clone();
        double weight = alpha;
        double[] out = new double[s.n + 1];

        for (int elapsed = 0; elapsed < lookahead; elapsed += PsyboidSearch.SECOND) {
            int chunk = Math.min(PsyboidSearch.SECOND, lookahead - elapsed);
            for (int t = 0; t < chunk; t++) s = engine.tick(s);
            out[0] += weight * (s.score - prevTotal);
            for (int i = 0; i < s.n; i++) out[i + 1] += weight * (s.boidScore[i] - prevBoid[i]);
            prevTotal = s.score;
            prevBoid = s.boidScore.clone();
            weight *= alpha;
        }
        return out;
    }

    /** A state stripped of overrides, with its score zeroed so a rollout measures only itself. */
    private static Sim.State unsteered(Sim.State s) {
        return new Sim.State(s.n, s.x.clone(), s.y.clone(), s.h.clone(), s.tick,
                0L, new long[s.n], s.label);
    }

    /** The same, with one boid removed entirely. */
    private static Sim.State without(Sim.State s, int drop) {
        int n = s.n - 1;
        int[] x = new int[n], y = new int[n], h = new int[n];
        for (int i = 0, k = 0; i < s.n; i++) {
            if (i == drop) continue;
            x[k] = s.x[i];
            y[k] = s.y[i];
            h[k] = s.h[i];
            k++;
        }
        return new Sim.State(n, x, y, h, s.tick, 0L, new long[n], s.label);
    }

    /**
     * The fixed vocabulary a boid is credited with when measuring its steering value.
     * <p>
     * Up to three one-second overrides placed in six one-second slots, each override
     * turning left, holding straight or turning right. Forced-straight is a genuine choice
     * rather than a no-op: a boid that would have turned under the flocking rules and does
     * not is steering, and any test that ignored that would miss the cheapest deviation
     * available. Empty slots leave the flocking rules in charge.
     * <p>
     * {@code sum(k=0..3) C(6,k) * 3^k} = 1 + 18 + 135 + 540 = 694 plans, the same set
     * everywhere it is used, so a steering value from one scenario means the same thing
     * as one from another.
     */
    private static List<int[][]> steeringPlans() {
        List<int[][]> plans = new ArrayList<>();
        int slots = 6;
        for (int mask = 0; mask < (1 << slots); mask++) {
            int chosen = Integer.bitCount(mask);
            if (chosen > 3) continue;

            int combos = (int) Math.pow(3, chosen);
            for (int combo = 0; combo < combos; combo++) {
                int[][] plan = new int[chosen][2];      // {onset in ticks, direction}
                int c = combo, at = 0;
                for (int slot = 0; slot < slots; slot++) {
                    if ((mask & (1 << slot)) == 0) continue;
                    plan[at][0] = slot * PsyboidSearch.SECOND;
                    plan[at][1] = (c % 3) - 1;          // -1 left, 0 straight, +1 right
                    c /= 3;
                    at++;
                }
                plans.add(plan);
            }
        }
        return plans;
    }

    /**
     * How many boids can see each boid, and how many each can see.
     * <p>
     * Perception is not symmetric: {@link Params#COS_FOV} blanks the rear 120 degrees, so
     * a boid ahead of the flock is watched by everyone while seeing nobody. In-degree is
     * therefore the most direct measure of leverage available â€” cohesion and alignment
     * both pull towards what is seen, so a boid that many others can see moves the flock
     * whether or not it intends to.
     *
     * @return {@code [0]} in-degree per boid, {@code [1]} out-degree per boid
     */
    private static int[][] visibility(Sim.State s, double rFlock) {
        int[] in = new int[s.n];
        int[] out = new int[s.n];
        for (int i = 0; i < s.n; i++) {
            double hx = Params.COS[s.h[i]];
            double hy = Params.SIN[s.h[i]];
            for (int j = 0; j < s.n; j++) {
                if (j == i) continue;
                double dx = s.x[j] - s.x[i];
                double dy = s.y[j] - s.y[i];
                double d2 = dx * dx + dy * dy;
                if (d2 == 0.0 || d2 > rFlock * rFlock) continue;
                double d = Math.sqrt(d2);
                if (dx * hx + dy * hy < Params.COS_FOV * d) continue;
                in[j]++;        // i can see j
                out[i]++;
            }
        }
        return new int[][]{in, out};
    }

    /** 1 = the largest value in {@code v}; ties share the better rank. */
    private static int rankByValue(double[] v, int i) {
        int above = 0;
        for (double x : v) if (x > v[i]) above++;
        return above + 1;
    }

    /**
     * All three leverage tests at the same instants, so they can be combined.
     * <p>
     * Coasting value, steering value and visibility answer different questions â€” what a
     * boid is worth doing nothing, what it could be worth trying, and how much of the
     * flock it can influence at all â€” and the first two have already been shown to fire in
     * opposite dilution regimes. Measuring them at separate sample points made a joint
     * detector impossible to evaluate; this measures them together.
     */
    public static void leverageStudy(PresetScenarioParameter preset, int boids, String context,
                                     int warmup, int lookahead, double alpha, int measurements,
                                     long pickSeed, Path out) throws IOException {
        ScenarioParameter scenario = withFlockSize(preset, boids);
        Engine engine = new Boids2DEngine(scenario);
        double rFlock = Params.flock(scenario.turningRadius());
        List<JournalRow> runs = readJournal(Path.of("data", "searches.tsv"), context);
        List<int[][]> plans = steeringPlans();
        System.out.printf("%d runs match \"%s\", %d steering plans, rFlock %.0f%n",
                runs.size(), context, plans.size(), rFlock);

        Files.createDirectories(out.getParent());
        StringBuilder csv = new StringBuilder(
                "dilution,seed,psyboid,tick,boid,base,cut,own,steer,indeg,outdeg\n");
        Random pick = new Random(pickSeed);

        // dilution -> metric -> rank histogram. Metrics: influence, headroom, indeg, combined.
        String[] metrics = {"coasting influence", "steering headroom", "in-degree", "combined"};
        Map<String, int[][]> hist = new java.util.LinkedHashMap<>();
        Map<String, Integer> points = new java.util.LinkedHashMap<>();

        long started = System.nanoTime();
        for (JournalRow run : runs) {
            String dilution = run.context().substring(run.context().lastIndexOf(' ') + 1);
            int span = run.commits() * run.spacing();

            java.util.TreeSet<Integer> when = new java.util.TreeSet<>();
            while (when.size() < measurements) when.add(warmup + pick.nextInt(span));

            Sim.State s = engine.init(run.seed());
            while (s.tick < warmup) s = engine.tick(s);
            s = new Sim.State(s.n, s.x, s.y, s.h, s.tick, 0L, new long[s.n], run.label(),
                    PsyboidSearch.overridesOf(run.label()));

            for (int tick : when) {
                while (s.tick < tick) s = engine.tick(s);
                Sim.State base = unsteered(s);
                double[] intact = discountedScores(engine, base, lookahead, alpha);
                int[][] see = visibility(base, rFlock);

                double[] influence = new double[boids];
                double[] headroom = new double[boids];
                double[] indeg = new double[boids];

                for (int i = 0; i < boids; i++) {
                    double cut = discountedScores(engine, without(base, i), lookahead, alpha)[0];
                    influence[i] = (intact[0] - cut) - intact[i + 1];

                    double best = intact[0];
                    for (int[][] plan : plans) {
                        if (plan.length == 0) continue;
                        PsyboidOverride[] overrides = new PsyboidOverride[plan.length];
                        for (int k = 0; k < plan.length; k++) {
                            overrides[k] = new PsyboidOverride((int) (base.tick + plan[k][0]),
                                    PsyboidSearch.SECOND, plan[k][1], i);
                        }
                        Sim.State steered = new Sim.State(base.n, base.x.clone(), base.y.clone(),
                                base.h.clone(), base.tick, 0L, new long[base.n],
                                base.label, overrides);
                        best = Math.max(best,
                                discountedScores(engine, steered, lookahead, alpha)[0]);
                    }
                    headroom[i] = best - intact[0];
                    indeg[i] = see[0][i];

                    csv.append(String.format("%s,%d,%d,%d,%d,%.4f,%.4f,%.4f,%.4f,%d,%d%n",
                            dilution, run.seed(), run.psyboid(), tick, i,
                            intact[0], intact[0] - influence[i] - intact[i + 1],
                            intact[i + 1], best, see[0][i], see[1][i]));
                }

                // Combined: sum of the three ranks, lowest total the prime suspect.
                double[] combined = new double[boids];
                for (int i = 0; i < boids; i++) {
                    combined[i] = -(rankByValue(influence, i) + rankByValue(headroom, i)
                            + rankByValue(indeg, i));
                }

                int[][] h = hist.computeIfAbsent(dilution, k -> new int[4][boids + 1]);
                h[0][rankByValue(influence, run.psyboid())]++;
                h[1][rankByValue(headroom, run.psyboid())]++;
                h[2][rankByValue(indeg, run.psyboid())]++;
                h[3][rankByValue(combined, run.psyboid())]++;
                points.merge(dilution, 1, Integer::sum);
            }
            System.out.printf("  %s seed %d done (%.0fs)%n",
                    dilution, run.seed(), (System.nanoTime() - started) / 1e9);
        }

        Files.writeString(out, csv.toString());
        System.out.println("-> " + out);

        System.out.printf("%n%-20s %-8s %8s %8s %8s%n",
                "metric", "dilution", "meanrank", "top", "bottom");
        for (int m = 0; m < 4; m++) {
            for (var e : hist.entrySet()) {
                int[] h = e.getValue()[m];
                int n = points.get(e.getKey());
                double mean = 0;
                for (int r = 1; r <= boids; r++) mean += (double) r * h[r] / n;
                System.out.printf("%-20s %-8s %8.2f %7.1f%% %7.1f%%%n",
                        m == 0 || e.getKey().equals("low") ? metrics[m] : "", e.getKey(),
                        mean, 100.0 * h[1] / n, 100.0 * h[boids] / n);
            }
        }
        System.out.printf("%nchance: mean %.2f, top %.1f%%, bottom %.1f%%%n",
                (boids + 1) / 2.0, 100.0 / boids, 100.0 / boids);
    }

    /**
     * The best a boid could do from where it stands, if it were the one steering.
     * <p>
     * Coasting value asks what a boid is worth doing nothing; this asks what it is worth
     * trying. A boid the flock has drifted past may coast to nothing and still be one turn
     * from something, and only this test can see that.
     * <p>
     * Every boid is credited with the same {@link #steeringPlans() vocabulary}, so the
     * numbers are comparable across boids, runs and maps.
     */
    public static void steeringStudy(PresetScenarioParameter preset, int boids, String context,
                                     int warmup, int lookahead, double alpha, int measurements,
                                     long pickSeed, Path out) throws IOException {
        ScenarioParameter scenario = withFlockSize(preset, boids);
        Engine engine = new Boids2DEngine(scenario);
        List<JournalRow> runs = readJournal(Path.of("data", "searches.tsv"), context);
        List<int[][]> plans = steeringPlans();
        System.out.printf("%d runs match \"%s\", %d steering plans each%n",
                runs.size(), context, plans.size());

        Files.createDirectories(out.getParent());
        StringBuilder csv = new StringBuilder("dilution,seed,psyboid,tick,boid,coast,steer\n");
        Random pick = new Random(pickSeed);
        Map<String, List<double[]>> summary = new java.util.LinkedHashMap<>();

        long started = System.nanoTime();
        for (JournalRow run : runs) {
            String dilution = run.context().substring(run.context().lastIndexOf(' ') + 1);
            int span = run.commits() * run.spacing();

            java.util.TreeSet<Integer> when = new java.util.TreeSet<>();
            while (when.size() < measurements) when.add(warmup + pick.nextInt(span));

            Sim.State s = engine.init(run.seed());
            while (s.tick < warmup) s = engine.tick(s);
            s = new Sim.State(s.n, s.x, s.y, s.h, s.tick, 0L, new long[s.n], run.label(),
                    PsyboidSearch.overridesOf(run.label()));

            for (int tick : when) {
                while (s.tick < tick) s = engine.tick(s);
                Sim.State base = unsteered(s);
                double coastAll = discountedScores(engine, base, lookahead, alpha)[0];

                double psyCoast = 0, psySteer = 0, othCoast = 0, othSteer = 0;
                for (int i = 0; i < boids; i++) {
                    double best = coastAll;
                    for (int[][] plan : plans) {
                        if (plan.length == 0) continue;         // the no-op is coastAll
                        PsyboidOverride[] overrides = new PsyboidOverride[plan.length];
                        for (int k = 0; k < plan.length; k++) {
                            overrides[k] = new PsyboidOverride((int) (base.tick + plan[k][0]),
                                    PsyboidSearch.SECOND, plan[k][1], i);
                        }
                        Sim.State steered = new Sim.State(base.n, base.x.clone(), base.y.clone(),
                                base.h.clone(), base.tick, 0L, new long[base.n],
                                base.label, overrides);
                        best = Math.max(best, discountedScores(engine, steered, lookahead, alpha)[0]);
                    }
                    csv.append(String.format("%s,%d,%d,%d,%d,%.4f,%.4f%n",
                            dilution, run.seed(), run.psyboid(), tick, i, coastAll, best));
                    if (i == run.psyboid()) {
                        psyCoast = coastAll;
                        psySteer = best;
                    } else {
                        othCoast += coastAll / (boids - 1);
                        othSteer += best / (boids - 1);
                    }
                }
                summary.computeIfAbsent(dilution, k -> new ArrayList<>())
                        .add(new double[]{psySteer - psyCoast, othSteer - othCoast,
                                psySteer, othSteer});
            }
            System.out.printf("  %s seed %d done (%.0fs elapsed)%n",
                    dilution, run.seed(), (System.nanoTime() - started) / 1e9);
        }

        Files.writeString(out, csv.toString());
        System.out.println("-> " + out);

        System.out.printf("%n%-8s   %-24s   %-24s%n", "",
                "psyboid steering headroom", "average other's headroom");
        System.out.printf("%-8s   %8s %8s %6s   %8s %8s %6s%n", "dilution",
                "gain", "sd", "best", "gain", "sd", "best");
        for (var e : summary.entrySet()) {
            List<double[]> v = e.getValue();
            double[] m = new double[4];
            for (double[] row : v) for (int k = 0; k < 4; k++) m[k] += row[k] / v.size();
            double pv = 0, ov = 0;
            for (double[] row : v) {
                pv += (row[0] - m[0]) * (row[0] - m[0]) / (v.size() - 1);
                ov += (row[1] - m[1]) * (row[1] - m[1]) / (v.size() - 1);
            }
            System.out.printf("%-8s   %8.3f %8.3f %6.3f   %8.3f %8.3f %6.3f%n",
                    e.getKey(), m[0], Math.sqrt(pv), m[2], m[1], Math.sqrt(ov), m[3]);
        }
    }

    /**
     * How much does each boid matter?
     * <p>
     * At sampled instants along a run the overrides are stripped and the flock rolled
     * forward once intact and once for every boid removed, all scored over the same
     * discounted window. Removing a boid always costs whatever that boid would have scored
     * itself; what is interesting is the rest â€” how much the flock loses beyond that,
     * which is the boid's influence on everyone else.
     * <p>
     * The psyboid is not steering during any of these rollouts. This measures the position
     * it has manoeuvred the flock into, not the manoeuvre.
     */
    public static void deletionStudy(PresetScenarioParameter preset, int boids, String context,
                                     int warmup, int lookahead, double alpha, int measurements,
                                     long pickSeed, Path out) throws IOException {
        ScenarioParameter scenario = withFlockSize(preset, boids);
        Engine engine = new Boids2DEngine(scenario);
        List<JournalRow> runs = readJournal(Path.of("data", "searches.tsv"), context);
        System.out.printf("%d journalled runs match \"%s\"%n", runs.size(), context);

        Files.createDirectories(out.getParent());
        StringBuilder csv = new StringBuilder("dilution,seed,psyboid,tick,deleted,total,own\n");
        Random pick = new Random(pickSeed);

        // dilution -> accumulated deltas, psyboid and others kept apart
        Map<String, List<double[]>> summary = new java.util.LinkedHashMap<>();

        for (JournalRow run : runs) {
            String dilution = run.context().substring(run.context().lastIndexOf(' ') + 1);
            int span = run.commits() * run.spacing();

            java.util.TreeSet<Integer> when = new java.util.TreeSet<>();
            while (when.size() < measurements) when.add(warmup + pick.nextInt(span));

            Sim.State s = engine.init(run.seed());
            while (s.tick < warmup) s = engine.tick(s);
            s = new Sim.State(s.n, s.x, s.y, s.h, s.tick, 0L, new long[s.n], run.label(),
                    PsyboidSearch.overridesOf(run.label()));

            for (int tick : when) {
                while (s.tick < tick) s = engine.tick(s);

                double[] base = discountedScores(engine, unsteered(s), lookahead, alpha);
                csv.append(String.format("%s,%d,%d,%d,-1,%.4f,%.4f%n",
                        dilution, run.seed(), run.psyboid(), tick, base[0], 0.0));

                double psyDelta = 0, psyOwn = 0, otherDelta = 0, otherOwn = 0;
                for (int i = 0; i < boids; i++) {
                    double[] cut = discountedScores(engine, without(s, i), lookahead, alpha);
                    double delta = base[0] - cut[0];
                    csv.append(String.format("%s,%d,%d,%d,%d,%.4f,%.4f%n",
                            dilution, run.seed(), run.psyboid(), tick, i, cut[0], base[i + 1]));
                    if (i == run.psyboid()) {
                        psyDelta = delta;
                        psyOwn = base[i + 1];
                    } else {
                        otherDelta += delta / (boids - 1);
                        otherOwn += base[i + 1] / (boids - 1);
                    }
                }
                summary.computeIfAbsent(dilution, k -> new ArrayList<>())
                        .add(new double[]{psyDelta, psyOwn, otherDelta, otherOwn, base[0]});
            }
            System.out.printf("  %s seed %d done%n", dilution, run.seed());
        }

        Files.writeString(out, csv.toString());
        System.out.println("-> " + out);

        System.out.printf("%n%-8s %8s   %-22s   %-22s%n", "", "baseline",
                "delete psyboid", "delete an average other");
        System.out.printf("%-8s %8s   %7s %7s %6s   %7s %7s %6s%n", "dilution", "score",
                "loss", "sd", "own", "loss", "sd", "own");
        for (var e : summary.entrySet()) {
            List<double[]> v = e.getValue();
            double[] m = new double[5];
            for (double[] row : v) for (int k = 0; k < 5; k++) m[k] += row[k] / v.size();
            double psyVar = 0, othVar = 0;
            for (double[] row : v) {
                psyVar += (row[0] - m[0]) * (row[0] - m[0]) / (v.size() - 1);
                othVar += (row[2] - m[2]) * (row[2] - m[2]) / (v.size() - 1);
            }
            System.out.printf("%-8s %8.3f   %7.3f %7.3f %6.3f   %7.3f %7.3f %6.3f%n",
                    e.getKey(), m[4], m[0], Math.sqrt(psyVar), m[1],
                    m[2], Math.sqrt(othVar), m[3]);
        }
    }

    /**
     * Searches one run per seed at each dilution, holding the measured span constant.
     * <p>
     * Commits are set from the commit interval so every dilution covers the same number of
     * ticks: a psyboid acting 80% of the time and one acting 20% of the time are then
     * being compared over the same stretch of simulation rather than the same number of
     * decisions. The labels are what this is for â€” the journal keeps them, and the
     * analysis that follows replays rather than re-searches.
     */
    public static void dilutionRuns(PresetScenarioParameter preset, int boids, int[] branches,
                                    int lookahead, double alpha, int[] maxDelays, int duration,
                                    int segments, int span, int warmup, long[] seeds,
                                    long pickSeed) throws IOException {
        ScenarioParameter scenario = withFlockSize(preset, boids);
        Engine engine = new Boids2DEngine(scenario);
        Random pick = new Random(pickSeed);

        for (int maxDelay : maxDelays) {
            int spacing = maxDelay + duration;
            int commits = span / spacing;
            String tag = maxDelay <= 8 ? "low" : maxDelay <= 32 ? "medium" : "high";
            SearchJournal.context(preset.name() + " n" + boids + " dilution " + tag);

            System.out.printf("%n=== %s dilution: maxDelay %d (%.0fs), interval %d, "
                            + "%d commits = %d ticks ===%n",
                    tag, maxDelay, maxDelay / 8.0, spacing, commits, commits * spacing);
            System.out.println("  seed  psyboid   flock  psyboid   others  control   rank   secs");

            for (long seed : seeds) {
                int psyboid = pick.nextInt(boids);
                PsyboidSearch.Config config = new PsyboidSearch.Config(
                        branches, lookahead, alpha, maxDelay, duration, segments, psyboid);

                long started = System.nanoTime();
                Sim.State root = warmedRoot(engine, seed, warmup);
                PsyboidSearch search = new PsyboidSearch(engine, config, root, seed);
                for (int c = 0; c < commits; c++) search.commit();
                Sim.State end = search.canonical();
                Sim.State control = plainRun(engine, root, commits * spacing);
                int ticks = commits * spacing;

                System.out.printf("  %4d  %7d  %6.2f%%  %6.2f%%  %6.2f%%  %6.2f%%   %2d/%-2d  %5.0f%n",
                        seed, psyboid,
                        100.0 * end.score / boids / ticks,
                        100.0 * end.boidScore[psyboid] / ticks,
                        100.0 * (end.score - end.boidScore[psyboid]) / (boids - 1) / ticks,
                        100.0 * control.score / boids / ticks,
                        rankOf(end, psyboid), boids,
                        (System.nanoTime() - started) / 1e9);
            }
            SearchJournal.flush();
        }
    }

    /**
     * Distance from every pixel to the nearest scoring pixel, by multi-source BFS.
     * <p>
     * Occupancy is binary and therefore blunt: a boid one pixel outside the zone and one
     * across the map both read as "not scoring". Distance keeps the gradient, which is what
     * a boid closing on the zone looks like before it arrives.
     */
    private static int[] zoneDistance(NavMap map) {
        int w = map.width(), h = map.height();
        int[] dist = new int[w * h];
        java.util.Arrays.fill(dist, Integer.MAX_VALUE);
        java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (map.score(x, y) > 0) {
                    dist[x + y * w] = 0;
                    queue.add(x + y * w);
                }
            }
        }
        while (!queue.isEmpty()) {
            int p = queue.poll();
            int px = p % w, py = p / w;
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int nx = px + dx, ny = py + dy;
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                    int q = nx + ny * w;
                    if (map.oob(nx, ny) || dist[q] != Integer.MAX_VALUE) continue;
                    dist[q] = dist[p] + 1;
                    queue.add(q);
                }
            }
        }
        return dist;
    }

    /**
     * Single-frame geometry for every boid at the instants already measured, so it can be
     * joined onto the rollout features without repeating any rollouts.
     * <p>
     * Everything here is computable from one screenshot: where a boid is relative to the
     * scoring zone, relative to its flock, and which way it is pointing compared to
     * everyone else.
     */
    public static void geometryFeatures(PresetScenarioParameter preset, int boids,
                                        String context, int warmup, Path leverageCsv, Path out)
            throws IOException {
        ScenarioParameter scenario = withFlockSize(preset, boids);
        Engine engine = new Boids2DEngine(scenario);
        NavMap map = NavMapBuilder.buildFromPng(scenario.mapPath(),
                Math.round(scenario.turningRadius()));
        int[] zone = zoneDistance(map);

        // Which instants were measured, keyed by dilution AND seed: the same seeds are
        // reused at every dilution, so keying by seed alone silently merges three runs.
        Map<String, java.util.TreeSet<Integer>> ticks = new java.util.LinkedHashMap<>();
        List<String> lines = Files.readAllLines(leverageCsv);
        for (int i = 1; i < lines.size(); i++) {
            String[] f = lines.get(i).split(",");
            ticks.computeIfAbsent(f[0] + "/" + f[1], x -> new java.util.TreeSet<>())
                    .add(Integer.parseInt(f[3]));
        }

        // Map-relative features are taken about the image centre. On a map with an
        // obstruction there and n-fold rotational symmetry, that is the natural origin:
        // a boid's radius says how far out it is and its polar angle says which lobe it
        // is in, neither of which any flock-relative feature can express.
        double ox = map.width() / 2.0, oy = map.height() / 2.0;

        StringBuilder csv = new StringBuilder(
                "dilution,seed,tick,boid,zonedist,inzone,centdist,headdev,nndist,"
                        + "ahead,pillardist,radial,tangential,cos2t,sin2t\n");
        for (JournalRow run : readJournal(Path.of("data", "searches.tsv"), context)) {
            String dilution = run.context().substring(run.context().lastIndexOf(' ') + 1);
            if (!ticks.containsKey(dilution + "/" + run.seed())) continue;

            Sim.State s = engine.init(run.seed());
            while (s.tick < warmup) s = engine.tick(s);
            s = new Sim.State(s.n, s.x, s.y, s.h, s.tick, 0L, new long[s.n], run.label(),
                    PsyboidSearch.overridesOf(run.label()));

            for (int tick : ticks.get(dilution + "/" + run.seed())) {
                while (s.tick < tick) s = engine.tick(s);
                for (int i = 0; i < boids; i++) {
                    // Reference flock excludes the boid being measured, as elsewhere.
                    double cx = 0, cy = 0, hx = 0, hy = 0;
                    for (int j = 0; j < boids; j++) {
                        if (j == i) continue;
                        cx += s.x[j] / (double) (boids - 1);
                        cy += s.y[j] / (double) (boids - 1);
                        hx += Params.COS[s.h[j]];
                        hy += Params.SIN[s.h[j]];
                    }
                    double m = Math.hypot(hx, hy);
                    double dev = m == 0 ? 0 : Math.toDegrees(Math.acos(Math.max(-1, Math.min(1,
                            (Params.COS[s.h[i]] * hx + Params.SIN[s.h[i]] * hy) / m))));

                    double nn = Double.MAX_VALUE;
                    for (int j = 0; j < boids; j++) {
                        if (j == i) continue;
                        nn = Math.min(nn, Math.hypot(s.x[i] - s.x[j], s.y[i] - s.y[j]));
                    }

                    int zd = zone[s.x[i] + s.y[i] * map.width()];

                    // Where this boid sits relative to the flock's own direction of
                    // travel. Distance from the centroid cannot tell a leader from a
                    // straggler; this can, and a herding psyboid should be out in front.
                    double offX = s.x[i] - cx, offY = s.y[i] - cy;
                    double offLen = Math.hypot(offX, offY);
                    double ahead = (offLen < 1e-9 || m < 1e-9) ? 0
                            : (offX * hx + offY * hy) / (offLen * m);

                    // Polar about the pillar. radial: +1 heading straight out, -1 straight
                    // in. tangential: signed, so it separates the two orbit directions.
                    double px = s.x[i] - ox, py = s.y[i] - oy;
                    double pr = Math.hypot(px, py);
                    double bhx = Params.COS[s.h[i]], bhy = Params.SIN[s.h[i]];
                    double radial = pr < 1e-9 ? 0 : (px * bhx + py * bhy) / pr;
                    double tangential = pr < 1e-9 ? 0 : (px * bhy - py * bhx) / pr;

                    // Doubled angle, so the two halves of a 180-degree-symmetric map are
                    // the same place. Raw theta would split one lobe across the seam.
                    double theta = Math.atan2(py, px);

                    csv.append(String.format(
                            "%s,%d,%d,%d,%d,%d,%.2f,%.1f,%.2f,%.4f,%.2f,%.4f,%.4f,%.4f,%.4f%n",
                            dilution, run.seed(), tick, i,
                            zd == Integer.MAX_VALUE ? 9999 : zd,
                            map.score(s.x[i], s.y[i]) > 0 ? 1 : 0,
                            offLen, dev, nn,
                            ahead, pr, radial, tangential,
                            Math.cos(2 * theta), Math.sin(2 * theta)));
                }
            }
            System.out.printf("  %s seed %d%n", dilution, run.seed());
        }
        Files.createDirectories(out.getParent());
        Files.writeString(out, csv.toString());
        System.out.println("-> " + out);
    }

    // ---- Conditional-logit psyboid estimator --------------------------------

    /** Feature order everywhere below: out-degree, in-degree, influence, headroom, own. */
    private static final int LEV_F = 5;

    private static double[] softmax(double[] z) {
        double max = Double.NEGATIVE_INFINITY;
        for (double v : z) max = Math.max(max, v);
        double sum = 0;
        double[] p = new double[z.length];
        for (int i = 0; i < z.length; i++) { p[i] = Math.exp(z[i] - max); sum += p[i]; }
        for (int i = 0; i < z.length; i++) p[i] /= sum;
        return p;
    }

    /** Standardises each feature across the flock, in place: the signal is deviation. */
    private static void zScoreWithin(double[][] x) {
        int n = x.length;
        for (int f = 0; f < LEV_F; f++) {
            double mean = 0;
            for (double[] row : x) mean += row[f] / n;
            double var = 0;
            for (double[] row : x) var += (row[f] - mean) * (row[f] - mean) / n;
            double sd = Math.sqrt(var);
            for (double[] row : x) row[f] = sd < 1e-9 ? 0 : (row[f] - mean) / sd;
        }
    }

    /** Gradient ascent on the conditional log-likelihood; exactly one boid per case is true. */
    private static double[] fitLogit(List<double[][]> x, List<Integer> y, double lambda) {
        double[] beta = new double[LEV_F];
        for (int it = 0; it < 4000; it++) {
            double[] grad = new double[LEV_F];
            for (int m = 0; m < x.size(); m++) {
                double[][] rows = x.get(m);
                double[] z = new double[rows.length];
                for (int i = 0; i < rows.length; i++)
                    for (int f = 0; f < LEV_F; f++) z[i] += beta[f] * rows[i][f];
                double[] p = softmax(z);
                for (int f = 0; f < LEV_F; f++) {
                    double expected = 0;
                    for (int i = 0; i < rows.length; i++) expected += p[i] * rows[i][f];
                    grad[f] += rows[y.get(m)][f] - expected;
                }
            }
            for (int f = 0; f < LEV_F; f++) beta[f] += 0.5 * (grad[f] / x.size() - lambda * beta[f]);
        }
        return beta;
    }

    /** All five leverage features for every boid at one instant, unstandardised. */
    private static double[][] leverageFeatures(Engine engine, Sim.State base, int boids,
                                               double rFlock, int lookahead, double alpha,
                                               List<int[][]> plans) {
        double[] intact = discountedScores(engine, base, lookahead, alpha);
        int[][] see = visibility(base, rFlock);
        double[][] x = new double[boids][LEV_F];

        for (int i = 0; i < boids; i++) {
            double cut = discountedScores(engine, without(base, i), lookahead, alpha)[0];
            double best = intact[0];
            for (int[][] plan : plans) {
                if (plan.length == 0) continue;
                PsyboidOverride[] o = new PsyboidOverride[plan.length];
                for (int k = 0; k < plan.length; k++) {
                    o[k] = new PsyboidOverride((int) (base.tick + plan[k][0]),
                            PsyboidSearch.SECOND, plan[k][1], i);
                }
                Sim.State steered = new Sim.State(base.n, base.x.clone(), base.y.clone(),
                        base.h.clone(), base.tick, 0L, new long[base.n], base.label, o);
                best = Math.max(best, discountedScores(engine, steered, lookahead, alpha)[0]);
            }
            x[i][0] = see[1][i];
            x[i][1] = see[0][i];
            x[i][2] = (intact[0] - cut) - intact[i + 1];
            x[i][3] = best - intact[0];
            x[i][4] = intact[i + 1];
        }
        return x;
    }

    /**
     * Turns journalled runs into cases, admitting only those the estimator can actually
     * solve.
     * <p>
     * A case is accepted when the estimator's top pick is the true psyboid <em>and</em>
     * carries at least {@code minRatio} times the probability of the runner-up. Coefficients
     * come from the other seeds, never the candidate's own, and the photographed instants
     * are drawn fresh rather than reused from the data the estimator was fitted on.
     */
    public static void leverageCases(PresetScenarioParameter preset, int boids, String dilution,
                                     int warmup, int lookahead, double alpha, int[] photoCounts,
                                     long pickSeed, int firstCase, double minRatio, int scale,
                                     String clue, Path packetDir, Path buildDir) throws IOException {
        ScenarioParameter scenario = withFlockSize(preset, boids);
        Engine engine = new Boids2DEngine(scenario);
        Boids2DRenderer renderer = new Boids2DRenderer(scenario.mapPath(), scale);
        double rFlock = Params.flock(scenario.turningRadius());
        List<int[][]> plans = steeringPlans();
        Files.createDirectories(packetDir);
        Files.createDirectories(buildDir);

        // Training data straight off the leverage CSV, grouped by the run it came from.
        Map<Long, List<double[][]>> trainX = new java.util.LinkedHashMap<>();
        Map<Long, List<Integer>> trainY = new java.util.LinkedHashMap<>();
        Map<Long, Integer> psyOf = new java.util.LinkedHashMap<>();
        record Key(long seed, int tick) {}
        Map<Key, double[][]> grouped = new java.util.LinkedHashMap<>();
        List<String> lines = Files.readAllLines(Path.of("data", "leverage.csv"));
        for (int i = 1; i < lines.size(); i++) {
            String[] f = lines.get(i).split(",");
            if (!f[0].equals(dilution)) continue;
            long seed = Long.parseLong(f[1]);
            Key k = new Key(seed, Integer.parseInt(f[3]));
            psyOf.put(seed, Integer.parseInt(f[2]));
            double base = Double.parseDouble(f[5]), cut = Double.parseDouble(f[6]);
            double own = Double.parseDouble(f[7]), steer = Double.parseDouble(f[8]);
            grouped.computeIfAbsent(k, x -> new double[boids][LEV_F])[Integer.parseInt(f[4])] =
                    new double[]{Double.parseDouble(f[10]), Double.parseDouble(f[9]),
                            (base - cut) - own, steer - base, own};
        }
        for (var e : grouped.entrySet()) {
            zScoreWithin(e.getValue());
            trainX.computeIfAbsent(e.getKey().seed(), x -> new ArrayList<>()).add(e.getValue());
            trainY.computeIfAbsent(e.getKey().seed(), x -> new ArrayList<>())
                    .add(psyOf.get(e.getKey().seed()));
        }

        List<Long> seeds = new ArrayList<>(trainX.keySet());
        Random pick = new Random(pickSeed);
        java.util.Collections.shuffle(seeds, pick);
        System.out.printf("%d candidate runs at %s dilution, need %d cases%n",
                seeds.size(), dilution, photoCounts.length);

        int caseAt = 0, attempt = 0;
        StringBuilder palette = new StringBuilder();
        for (int i = 0; i < boids; i++) {
            palette.append(String.format("| `%s` | %s |%n", hex(i, boids), COLOUR_NAMES[i]));
        }

        for (long seed : seeds) {
            if (caseAt >= photoCounts.length) break;
            attempt++;
            int photos = photoCounts[caseAt];
            int psyboid = psyOf.get(seed);

            // Coefficients from every other run, so the candidate never trains its own judge.
            List<double[][]> fx = new ArrayList<>();
            List<Integer> fy = new ArrayList<>();
            for (long other : trainX.keySet()) {
                if (other == seed) continue;
                fx.addAll(trainX.get(other));
                fy.addAll(trainY.get(other));
            }
            double[] beta = fitLogit(fx, fy, 1e-3);

            JournalRow row = null;
            for (JournalRow r : readJournal(Path.of("data", "searches.tsv"), "dilution " + dilution)) {
                if (r.seed() == seed) row = r;
            }
            if (row == null) continue;
            int span = row.commits() * row.spacing();

            java.util.TreeSet<Integer> when = new java.util.TreeSet<>();
            while (when.size() < photos) when.add(warmup + pick.nextInt(span));

            Sim.State s = engine.init(seed);
            while (s.tick < warmup) s = engine.tick(s);
            s = new Sim.State(s.n, s.x, s.y, s.h, s.tick, 0L, new long[s.n], row.label(),
                    PsyboidSearch.overridesOf(row.label()));

            double[] logit = new double[boids];
            List<Sim.State> frames = new ArrayList<>();
            for (int tick : when) {
                while (s.tick < tick) s = engine.tick(s);
                frames.add(s);
                double[][] x = leverageFeatures(engine, unsteered(s), boids, rFlock,
                        lookahead, alpha, plans);
                zScoreWithin(x);
                for (int i = 0; i < boids; i++)
                    for (int f = 0; f < LEV_F; f++) logit[i] += beta[f] * x[i][f];
            }

            // Temperature 1/sqrt(k) keeps k summed logits from reading as k-fold certainty.
            double[] scaled = logit.clone();
            for (int i = 0; i < boids; i++) scaled[i] /= Math.sqrt(photos);
            double[] p = softmax(scaled);

            int top = 0, second = -1;
            for (int i = 1; i < boids; i++) if (p[i] > p[top]) top = i;
            for (int i = 0; i < boids; i++) if (i != top && (second < 0 || p[i] > p[second])) second = i;
            double ratio = p[top] / p[second];

            boolean ok = top == psyboid && ratio >= minRatio;
            System.out.printf("  seed %d, %d photos: picks %s (p=%.3f), runner-up %s (p=%.3f), "
                            + "ratio %.1fx  -> %s%n",
                    seed, photos, COLOUR_NAMES[top], p[top], COLOUR_NAMES[second], p[second],
                    ratio, ok ? "ACCEPT" : (top == psyboid ? "reject: ratio" : "reject: wrong pick"));
            if (!ok) continue;

            String number = String.format("%04d", firstCase + caseAt);
            Path folder = packetDir.resolve("Case " + number);
            Files.createDirectories(folder);
            int n = 0;
            for (Sim.State frame : frames) {
                ImageIO.write(renderer.render(frame), "png",
                        folder.resolve(String.format("photo_%02d.png", ++n)).toFile());
            }
            writeCaseDocs(folder, buildDir, number, photos, scale, palette.toString(),
                    boids, psyboid, seed, warmup, span, clue, when, p, top, second, ratio,
                    beta, row.label(), scenario, """
                    | Dilution | low (psyboid steering ~80% of ticks) |
                    | Overrides | 3 segments, 32 ticks (4s), max delay 8 ticks (1s) |
                    | Commit interval | 40 ticks |
                    | Search | 256x2x2x2x1x1x1x1, lookahead 80, alpha 0.85 |""", """
                    - Accepted on the estimator's judgement, not a human's. It is a strong
                      test in this regime - about 90% at twelve photographs - but the
                      acceptance criterion means these cases are selected for being solvable
                      *by that estimator*, which is not the same as being fair to every
                      method.
                    - Nearly all the estimator's signal here is visibility geometry: a boid
                      that can see few others, and is seen by few. Solvable without
                      simulating anything, given the perception radius and field of view
                      from `Params`.
                    - Control on this map runs about 7%, so the flock scores unaided.
                      Presence in the scoring zone is not by itself evidence.""");
            caseAt++;
        }
        System.out.printf("%n%d cases built from %d attempts%n", caseAt, attempt);
    }

    /** All ten features for every boid at one instant, in the estimator's order. */
    private static double[][] fullFeatures(Engine engine, Sim.State base, int boids,
                                           double rFlock, int[] zoneDist, int mapWidth,
                                           NavMap map, int lookahead, double alpha,
                                           List<int[][]> plans) {
        double[][] x = leverageFeatures(engine, base, boids, rFlock, lookahead, alpha, plans);
        double[][] out = new double[boids][10];
        for (int i = 0; i < boids; i++) {
            System.arraycopy(x[i], 0, out[i], 0, 5);

            double cx = 0, cy = 0, hx = 0, hy = 0;
            for (int j = 0; j < boids; j++) {
                if (j == i) continue;
                cx += base.x[j] / (double) (boids - 1);
                cy += base.y[j] / (double) (boids - 1);
                hx += Params.COS[base.h[j]];
                hy += Params.SIN[base.h[j]];
            }
            double m = Math.hypot(hx, hy);
            double dev = m == 0 ? 0 : Math.toDegrees(Math.acos(Math.max(-1, Math.min(1,
                    (Params.COS[base.h[i]] * hx + Params.SIN[base.h[i]] * hy) / m))));
            double nn = Double.MAX_VALUE;
            for (int j = 0; j < boids; j++) {
                if (j == i) continue;
                nn = Math.min(nn, Math.hypot(base.x[i] - base.x[j], base.y[i] - base.y[j]));
            }
            int zd = zoneDist[base.x[i] + base.y[i] * mapWidth];

            out[i][5] = zd == Integer.MAX_VALUE ? 9999 : zd;
            out[i][6] = map.score(base.x[i], base.y[i]) > 0 ? 1 : 0;
            out[i][7] = Math.hypot(base.x[i] - cx, base.y[i] - cy);
            out[i][8] = dev;
            out[i][9] = nn;
        }
        return out;
    }

    /** Standardises ten features across the flock, in place. */
    private static void zScore10(double[][] x) {
        int n = x.length;
        for (int f = 0; f < 10; f++) {
            double mean = 0;
            for (double[] row : x) mean += row[f] / n;
            double var = 0;
            for (double[] row : x) var += (row[f] - mean) * (row[f] - mean) / n;
            double sd = Math.sqrt(var);
            for (double[] row : x) row[f] = sd < 1e-9 ? 0 : (row[f] - mean) / sd;
        }
    }

    /**
     * Does more evidence rescue a regime the estimator cannot currently solve?
     * <p>
     * Runs seeds the estimator has never seen, samples them far more densely than the
     * fitting data allowed, and reports whether the extra evidence converts into a correct
     * and confident identification. Coefficients come from the original runs only.
     * <p>
     * The model averages standardised features across the series, so the coefficients carry
     * over to any series length unchanged; only the temperature depends on it. Averaging k
     * independent instants shrinks the noise as 1/sqrt(k), so the temperature is scaled by
     * sqrt(k / kFit) - an approximation, since neighbouring instants are not independent,
     * and one that will read slightly overconfident.
     */
    public static void holdoutTest(PresetScenarioParameter preset, int boids, long[] seeds,
                                   int[] branches, int lookahead, double alpha, int maxDelay,
                                   int duration, int segments, int commits, int warmup,
                                   int[] counts, String dilution, long pickSeed,
                                   Path leverageCsv, Path geometryCsv)
            throws IOException {
        ScenarioParameter scenario = withFlockSize(preset, boids);
        Engine engine = new Boids2DEngine(scenario);
        NavMap map = NavMapBuilder.buildFromPng(scenario.mapPath(),
                Math.round(scenario.turningRadius()));
        int[] zoneDist = zoneDistance(map);
        double rFlock = Params.flock(scenario.turningRadius());
        List<int[][]> plans = steeringPlans();

        // ---- coefficients from the original runs, never these ones ----
        // Keys are "seed/tick" strings: the CSVs are keyed that way and inventing a record
        // for it only bought an unchecked cast that failed at runtime.
        Map<String, double[][]> train = new java.util.LinkedHashMap<>();
        Map<String, Integer> psyOf = new java.util.HashMap<>();
        loadFeatureCsv(leverageCsv, geometryCsv, dilution, boids, train, psyOf);
        Map<String, List<String>> byRun = new java.util.LinkedHashMap<>();
        for (String k : train.keySet()) {
            byRun.computeIfAbsent(k.substring(0, k.indexOf('/')), x -> new ArrayList<>()).add(k);
        }

        final int kFit = 60;
        Random rng = new Random(pickSeed);
        List<double[][]> tx = new ArrayList<>();
        List<Integer> ty = new ArrayList<>();
        for (var e : byRun.entrySet()) {
            for (int rep = 0; rep < 60; rep++) {
                List<String> pick = new ArrayList<>(e.getValue());
                java.util.Collections.shuffle(pick, rng);
                pick = pick.subList(0, Math.min(kFit, pick.size()));
                double[][] agg = new double[boids][10];
                for (String k : pick)
                    for (int i = 0; i < boids; i++)
                        for (int f = 0; f < 10; f++) agg[i][f] += train.get(k)[i][f] / pick.size();
                tx.add(agg);
                ty.add(psyOf.get(pick.get(0)));
            }
        }
        double[] beta = fitLogit10(tx, ty, 0.05);
        double tFit = temperature10(tx, ty, beta);
        System.out.printf("estimator fitted on %d runs at %s dilution, %d series of %d%n",
                byRun.size(), dilution, tx.size(), kFit);
        String[] fname = {"out-degree", "in-degree", "influence", "headroom", "own",
                "zone-dist", "in-zone", "centroid-dist", "heading-dev", "nn-dist"};
        for (int f = 0; f < 10; f++) System.out.printf("  %-15s %+7.3f%n", fname[f], beta[f]);

        // ---- fresh runs, sampled densely ----
        int most = 0;
        for (int c : counts) most = Math.max(most, c);
        System.out.printf("%n%-6s %-8s %-10s %10s %10s %8s%n",
                "seed", "psyboid", "photos", "picked", "p(top)", "ratio");

        for (long seed : seeds) {
            int psyboid = rng.nextInt(boids);
            SearchJournal.context(preset.name() + " n" + boids + " holdout " + dilution);
            PsyboidSearch.Config config = new PsyboidSearch.Config(
                    branches, lookahead, alpha, maxDelay, duration, segments, psyboid);
            int spacing = config.splitSpacing();
            int span = commits * spacing;

            Sim.State root = warmedRoot(engine, seed, warmup);
            PsyboidSearch search = new PsyboidSearch(engine, config, root, seed);
            for (int c = 0; c < commits; c++) search.commit();
            String label = search.canonical().label;

            java.util.TreeSet<Integer> when = new java.util.TreeSet<>();
            while (when.size() < most) when.add(warmup + rng.nextInt(span));

            List<double[][]> per = new ArrayList<>();
            Sim.State s = engine.init(seed);
            while (s.tick < warmup) s = engine.tick(s);
            s = new Sim.State(s.n, s.x, s.y, s.h, s.tick, 0L, new long[s.n], label,
                    PsyboidSearch.overridesOf(label));
            for (int tick : when) {
                while (s.tick < tick) s = engine.tick(s);
                double[][] x = fullFeatures(engine, unsteered(s), boids, rFlock, zoneDist,
                        map.width(), map, lookahead, alpha, plans);
                zScore10(x);
                per.add(x);
            }

            for (int count : counts) {
                // Random subset rather than the first N: taking them in tick order would
                // confine a short series to the opening of the run instead of spreading it.
                List<Integer> idx = new ArrayList<>();
                for (int m = 0; m < per.size(); m++) idx.add(m);
                java.util.Collections.shuffle(idx, rng);

                double[][] agg = new double[boids][10];
                for (int m = 0; m < count; m++)
                    for (int i = 0; i < boids; i++)
                        for (int f = 0; f < 10; f++)
                            agg[i][f] += per.get(idx.get(m))[i][f] / count;

                double[] z = new double[boids];
                for (int i = 0; i < boids; i++)
                    for (int f = 0; f < 10; f++) z[i] += beta[f] * agg[i][f];
                double temp = tFit * Math.sqrt(count / (double) kFit);
                for (int i = 0; i < boids; i++) z[i] *= temp;
                double[] p = softmax(z);

                int top = 0, second = -1;
                for (int i = 1; i < boids; i++) if (p[i] > p[top]) top = i;
                for (int i = 0; i < boids; i++)
                    if (i != top && (second < 0 || p[i] > p[second])) second = i;

                System.out.printf("%-6d %-8s %-10d %10s %10.4f %7.1fx  %s%n",
                        seed, COLOUR_NAMES[psyboid], count, COLOUR_NAMES[top], p[top],
                        p[top] / p[second], top == psyboid ? "correct" : "WRONG");
            }
        }
    }

    /** Joins the rollout and geometry CSVs into ten standardised features per "seed/tick". */
    private static void loadFeatureCsv(Path lev, Path geo, String dilution, int boids,
                                       Map<String, double[][]> out, Map<String, Integer> psy)
            throws IOException {
        List<String> a = Files.readAllLines(lev);
        for (int i = 1; i < a.size(); i++) {
            String[] f = a.get(i).split(",");
            if (!f[0].equals(dilution)) continue;
            String key = f[1] + "/" + f[3];
            psy.put(key, Integer.parseInt(f[2]));
            double base = Double.parseDouble(f[5]), cut = Double.parseDouble(f[6]);
            double own = Double.parseDouble(f[7]), steer = Double.parseDouble(f[8]);
            out.computeIfAbsent(key, x -> new double[boids][10])[Integer.parseInt(f[4])] =
                    new double[]{Double.parseDouble(f[10]), Double.parseDouble(f[9]),
                            (base - cut) - own, steer - base, own, 0, 0, 0, 0, 0};
        }
        List<String> g = Files.readAllLines(geo);
        for (int i = 1; i < g.size(); i++) {
            String[] f = g.get(i).split(",");
            if (!f[0].equals(dilution)) continue;
            double[][] rows = out.get(f[1] + "/" + f[2]);
            if (rows == null) continue;
            double[] r = rows[Integer.parseInt(f[3])];
            for (int c = 0; c < 5; c++) r[5 + c] = Double.parseDouble(f[4 + c]);
        }
        for (double[][] rows : out.values()) zScore10(rows);
    }

    private static double[] fitLogit10(List<double[][]> x, List<Integer> y, double lambda) {
        double[] beta = new double[10];
        for (int it = 0; it < 3000; it++) {
            double[] grad = new double[10];
            for (int m = 0; m < x.size(); m++) {
                double[][] rows = x.get(m);
                double[] z = new double[rows.length];
                for (int i = 0; i < rows.length; i++)
                    for (int f = 0; f < 10; f++) z[i] += beta[f] * rows[i][f];
                double[] p = softmax(z);
                for (int f = 0; f < 10; f++) {
                    double expected = 0;
                    for (int i = 0; i < rows.length; i++) expected += p[i] * rows[i][f];
                    grad[f] += rows[y.get(m)][f] - expected;
                }
            }
            for (int f = 0; f < 10; f++) beta[f] += 0.5 * (grad[f] / x.size() - lambda * beta[f]);
        }
        return beta;
    }

    private static double temperature10(List<double[][]> x, List<Integer> y, double[] beta) {
        double best = 1, bestErr = Double.MAX_VALUE;
        for (double t = 0.1; t <= 6.0; t += 0.1) {
            double err = 0;
            for (int m = 0; m < x.size(); m++) {
                double[][] rows = x.get(m);
                double[] z = new double[rows.length];
                for (int i = 0; i < rows.length; i++)
                    for (int f = 0; f < 10; f++) z[i] += t * beta[f] * rows[i][f];
                double[] p = softmax(z);
                for (int i = 0; i < p.length; i++) {
                    double yy = (i == y.get(m)) ? 1 : 0;
                    err += (p[i] - yy) * (p[i] - yy);
                }
            }
            if (err < bestErr) { bestErr = err; best = t; }
        }
        return best;
    }

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

    /**
     * Turns saved canonical lines into shippable cases: photographs into the packet,
     * everything identifying into a build note outside it.
     * <p>
     * Works from labels rather than re-searching, which is the whole point of journalling
     * them â€” a case costs a replay rather than the minutes the search originally took.
     */
    public static void packetCases(PresetScenarioParameter preset, int boids, String[] labels,
                                   int[] psyboids, long[] seeds, int warmup, int span,
                                   int photos, int scale, long pickSeed, int firstCase,
                                   String clue, Path packetDir, Path buildDir)
            throws IOException {
        ScenarioParameter scenario = withFlockSize(preset, boids);
        Engine engine = new Boids2DEngine(scenario);
        Boids2DRenderer renderer = new Boids2DRenderer(scenario.mapPath(), scale);
        NavMap map = NavMapBuilder.buildFromPng(scenario.mapPath(),
                Math.round(scenario.turningRadius()));
        Files.createDirectories(packetDir);
        Files.createDirectories(buildDir);

        Random pick = new Random(pickSeed);
        StringBuilder palette = new StringBuilder();
        for (int i = 0; i < boids; i++) {
            palette.append(String.format("| `%s` | %s |%n", hex(i, boids), COLOUR_NAMES[i]));
        }

        for (int c = 0; c < labels.length; c++) {
            String number = String.format("%04d", firstCase + c);
            Path folder = packetDir.resolve("Case " + number);
            Files.createDirectories(folder);

            java.util.TreeSet<Integer> drawn = new java.util.TreeSet<>();
            while (drawn.size() < photos) drawn.add(warmup + pick.nextInt(span));

            StringBuilder perPhoto = new StringBuilder();
            int psyInZone = 0, soleOccupant = 0, n = 0;
            for (int tick : drawn) {
                Sim.State s = PsyboidSearch.replay(engine, labels[c], warmup, tick);
                int inZone = 0;
                boolean psyIn = false;
                for (int i = 0; i < s.n; i++) {
                    if (map.score(s.x[i], s.y[i]) > 0) {
                        inZone++;
                        if (i == psyboids[c]) psyIn = true;
                    }
                }
                if (psyIn) psyInZone++;
                if (psyIn && inZone == 1) soleOccupant++;
                perPhoto.append(String.format("| %d | %d | %d | %s |%n",
                        ++n, tick, inZone, psyIn ? "**yes**" : "no"));
                ImageIO.write(renderer.render(s), "png",
                        folder.resolve(String.format("photo_%d.png", n)).toFile());
            }

            Sim.State end = PsyboidSearch.replay(engine, labels[c], warmup, warmup + span);
            Sim.State control = plainRun(engine, warmedRoot(engine, seeds[c], warmup), span);
            double psy = 100.0 * end.boidScore[psyboids[c]] / span;
            double flock = 100.0 * end.score / boids / span;
            double others = 100.0 * (end.score - end.boidScore[psyboids[c]]) / (boids - 1) / span;
            double ctl = 100.0 * control.score / boids / span;

            Files.writeString(folder.resolve("INSTRUCTIONS.md"), String.format("""
                    # Case %s

                    ## The scenario

                    This folder contains %d screenshots taken from a single run of a boids
                    simulation, in chronological order. Every boid follows the same flocking
                    rules, which are given in full as source code under `code/`.

                    Exactly one boid is the **psyboid**. It usually behaves like the others,
                    but from time to time it ignores the flocking rules and steers
                    deliberately, in order to maximise the time the flock spends inside the
                    scoring zone. It is trying not to be identified.

                    **Your task: work out which boid is the psyboid.**

                    ## Reading the images

                    | element | appearance |
                    | --- | --- |
                    | Play area | light grey `#E6E6E6` |
                    | Wall / out of bounds | black `#000000` |
                    | Scoring zone | pale peach `#F7E0CC` |
                    | Boids | coloured triangles, nose pointing along the direction of travel |

                    Boids move at constant speed and can only turn by a fixed amount per
                    tick. Images are rendered at %dx, so each simulation pixel is a %dx%d
                    block. The photographs are not evenly spaced in time.

                    ## The candidates

                    | colour | name |
                    | --- | --- |
                    %s
                    %s
                    ## Submitting your answer

                    Append a single line to `out/psyboids.txt`:

                    ```
                    Case %s: COLOUR
                    ```

                    for example `Case %s: %s`.

                    Rules:

                    - Exactly one line per case. If a case appears more than once, the last
                      line wins.
                    - Use the colour name exactly as written above, in capitals.
                    - **A guess is mandatory.** If you are unsure, give your best guess.
                    """, number, photos, scale, scale, scale, palette, PHYSICS_SECTION,
                    number, number, COLOUR_NAMES[0]));

            Files.writeString(buildDir.resolve("Case " + number + ".md"), String.format("""
                    # Case %s - build notes

                    **NOT FOR SHIPMENT.** Contains the answer.

                    ## Answer

                    Boid %d, `%s`, rendered %s.

                    Intended clue: **%s**

                    ## Scenario

                    | | |
                    | --- | --- |
                    | Map | `%s` |
                    | Turning radius | %.0f |
                    | Flock size | %d |
                    | Seed | %d |
                    | Warm-up | %d ticks, unsteered, score discarded |
                    | Run | %d ticks (%d - %d) |
                    | Photographs | %d, uniform over the whole run, without replacement |

                    ## Psyboid search

                    | | |
                    | --- | --- |
                    | Overrides | 2 segments, 8 ticks (1s), max delay 2 ticks (0.25s) |
                    | Commit interval | 10 ticks |
                    | Tree shape | 1024x1x1x1, budget 6144 |
                    | Lookahead | 20 ticks (2.5s), alpha 0.85 |

                    ## Metrics over the run

                    | | |
                    | --- | --- |
                    | Psyboid occupancy | %.2f%% |
                    | Other boids (each) | %.2f%% |
                    | Flock | %.2f%% |
                    | Unsteered control | %.2f%% |
                    | Psyboid rank | %d of %d |

                    ## What each photograph shows

                    | # | tick | boids in zone | psyboid in zone |
                    | --- | --- | --- | --- |
                    %s
                    Psyboid in the zone in **%d of %d** photographs, and the sole occupant
                    in **%d**.

                    ## Reservations

                    - The psyboid index equals `seed %% flockSize` here, because that is how
                      `computeCurve` assigns it. Invisible to a solver, who never sees the
                      seed, but it means these cases cannot be pooled with any batch that
                      assigns differently without the assignment becoming guessable.
                    - Not screened for control: an unsteered flock on this map scores about
                      3%% of the time and every seed scores at some point, so presence in the
                      zone is strong evidence rather than proof.

                    ## Regenerating

                    ```
                    %s
                    ```
                    """, number, psyboids[c], hex(psyboids[c], boids), COLOUR_NAMES[psyboids[c]],
                    clue, scenario.mapPath(), scenario.turningRadius(), boids, seeds[c],
                    warmup, span, warmup, warmup + span, photos,
                    psy, others, flock, ctl, rankOf(end, psyboids[c]), boids,
                    perPhoto, psyInZone, photos, soleOccupant, labels[c]));

            System.out.printf("Case %s  seed %d  %s  psyboid in zone %d/%d (sole %d)  "
                            + "psy %.1f%% others %.1f%% control %.1f%%%n",
                    number, seeds[c], COLOUR_NAMES[psyboids[c]], psyInZone, photos,
                    soleOccupant, psy, others, ctl);
        }
    }

    /** First capture group of whichever pattern matches, trying them in order. */
    private static int firstOf(String text, String... patterns) {
        for (String p : patterns) {
            var m = java.util.regex.Pattern.compile(p).matcher(text);
            if (m.find()) return Integer.parseInt(m.group(1));
        }
        throw new IllegalArgumentException("no pattern matched");
    }

    /** Feature names in estimator order; the first five are the rollout metrics. */
    private static final String[] FEATURE_NAMES = {
            "out-degree", "in-degree", "influence", "headroom", "own",
            "zone-dist", "in-zone", "centroid-dist", "heading-dev", "nn-dist"};

    /** Coefficients as one readable line, however many the model was fitted with. */
    private static String describeBeta(double[] beta) {
        StringBuilder sb = new StringBuilder();
        for (int f = 0; f < beta.length; f++) {
            if (f > 0) sb.append(", ");
            sb.append(FEATURE_NAMES[f]).append(String.format(" %+.3f", beta[f]));
        }
        return sb.toString();
    }

    /**
     * Builds cases from journalled runs the estimator never trained on.
     * <p>
     * Unlike {@link #leverageCases}, nothing is searched here: the runs already exist and
     * are replayed from their labels. Photographed instants are drawn fresh, so the
     * acceptance figures below belong to the shipped photographs rather than to some other
     * sample of the same run.
     */
    public static void holdoutCases(PresetScenarioParameter preset, int boids, long[] seeds,
                                    int photos, int warmup, int lookahead, double alpha,
                                    String fitDilution, String runContext, int firstCase,
                                    double minRatio, int scale, String clue,
                                    String configBlock, String reservations,
                                    Path packetDir, Path buildDir, long pickSeed)
            throws IOException {
        holdoutCases(preset, boids, seeds, photos, warmup, lookahead, alpha, fitDilution,
                runContext, firstCase, minRatio, scale, clue, configBlock, reservations,
                packetDir, buildDir, pickSeed,
                Path.of("data", "leverage.csv"), Path.of("data", "geometry.csv"));
    }

    /** As above, reading the fit from named feature files rather than the default pair. */
    public static void holdoutCases(PresetScenarioParameter preset, int boids, long[] seeds,
                                    int photos, int warmup, int lookahead, double alpha,
                                    String fitDilution, String runContext, int firstCase,
                                    double minRatio, int scale, String clue,
                                    String configBlock, String reservations,
                                    Path packetDir, Path buildDir, long pickSeed,
                                    Path leverageCsv, Path geometryCsv)
            throws IOException {
        ScenarioParameter scenario = withFlockSize(preset, boids);
        Engine engine = new Boids2DEngine(scenario);
        Boids2DRenderer renderer = new Boids2DRenderer(scenario.mapPath(), scale);
        NavMap map = NavMapBuilder.buildFromPng(scenario.mapPath(),
                Math.round(scenario.turningRadius()));
        int[] zoneDist = zoneDistance(map);
        double rFlock = Params.flock(scenario.turningRadius());
        List<int[][]> plans = steeringPlans();
        Files.createDirectories(packetDir);
        Files.createDirectories(buildDir);

        // Coefficients from the fitting runs only; these seeds are not among them.
        Map<String, double[][]> train = new java.util.LinkedHashMap<>();
        Map<String, Integer> psyOf = new java.util.HashMap<>();
        loadFeatureCsv(leverageCsv, geometryCsv, fitDilution, boids, train, psyOf);
        Map<String, List<String>> byRun = new java.util.LinkedHashMap<>();
        for (String k : train.keySet()) {
            byRun.computeIfAbsent(k.substring(0, k.indexOf('/')), x -> new ArrayList<>()).add(k);
        }
        final int kFit = 60;
        Random rng = new Random(pickSeed);
        List<double[][]> tx = new ArrayList<>();
        List<Integer> ty = new ArrayList<>();
        for (var e : byRun.entrySet()) {
            for (int rep = 0; rep < 60; rep++) {
                List<String> pick = new ArrayList<>(e.getValue());
                java.util.Collections.shuffle(pick, rng);
                pick = pick.subList(0, Math.min(kFit, pick.size()));
                double[][] agg = new double[boids][10];
                for (String k : pick)
                    for (int i = 0; i < boids; i++)
                        for (int f = 0; f < 10; f++) agg[i][f] += train.get(k)[i][f] / pick.size();
                tx.add(agg);
                ty.add(psyOf.get(pick.get(0)));
            }
        }
        double[] beta = fitLogit10(tx, ty, 0.05);
        double temp = temperature10(tx, ty, beta) * Math.sqrt(photos / (double) kFit);

        StringBuilder palette = new StringBuilder();
        for (int i = 0; i < boids; i++) {
            palette.append(String.format("| `%s` | %s |%n", hex(i, boids), COLOUR_NAMES[i]));
        }

        List<JournalRow> runs = readJournal(Path.of("data", "searches.tsv"), runContext);
        int caseAt = 0;
        for (long seed : seeds) {
            JournalRow row = null;
            for (JournalRow r : runs) if (r.seed() == seed) row = r;
            if (row == null) { System.out.println("no journalled run for seed " + seed); continue; }
            int span = row.commits() * row.spacing();

            java.util.TreeSet<Integer> when = new java.util.TreeSet<>();
            while (when.size() < photos) when.add(warmup + rng.nextInt(span));

            Sim.State s = engine.init(seed);
            while (s.tick < warmup) s = engine.tick(s);
            s = new Sim.State(s.n, s.x, s.y, s.h, s.tick, 0L, new long[s.n], row.label(),
                    PsyboidSearch.overridesOf(row.label()));

            List<Sim.State> frames = new ArrayList<>();
            double[] logit = new double[boids];
            for (int tick : when) {
                while (s.tick < tick) s = engine.tick(s);
                frames.add(s);
                double[][] x = fullFeatures(engine, unsteered(s), boids, rFlock, zoneDist,
                        map.width(), map, lookahead, alpha, plans);
                zScore10(x);
                for (int i = 0; i < boids; i++)
                    for (int f = 0; f < 10; f++) logit[i] += beta[f] * x[i][f] / photos;
            }
            for (int i = 0; i < boids; i++) logit[i] *= temp;
            double[] p = softmax(logit);

            int top = 0, second = -1;
            for (int i = 1; i < boids; i++) if (p[i] > p[top]) top = i;
            for (int i = 0; i < boids; i++)
                if (i != top && (second < 0 || p[i] > p[second])) second = i;

            // The probability ratio saturates: summed log-odds over a hundred-odd frames
            // reach magnitudes where p(top) rounds to 1 and the ratio reports arithmetic
            // rather than confidence. The margin is the same quantity before the
            // temperature blows it up â€” the gap in weighted mean-z between the top two,
            // which stays on a human scale however many photographs there are.
            double margin = (logit[top] - logit[second]) / temp;
            double ratio = p[top] / p[second];
            boolean ok = top == row.psyboid() && margin >= minRatio;

            System.out.printf("  seed %d: picks %s over %s, margin %.3f (p=%.4f, ratio %.3g) -> %s%n",
                    seed, COLOUR_NAMES[top], COLOUR_NAMES[second], margin, p[top], ratio,
                    ok ? "ACCEPT" : "REJECT");
            if (!ok) continue;

            String number = String.format("%04d", firstCase + caseAt);
            Path folder = packetDir.resolve("Case " + number);
            Files.createDirectories(folder);
            int n = 0;
            for (Sim.State frame : frames) {
                ImageIO.write(renderer.render(frame), "png",
                        folder.resolve(String.format("photo_%03d.png", ++n)).toFile());
            }
            writeCaseDocs(folder, buildDir, number, photos, scale, palette.toString(), boids,
                    row.psyboid(), seed, warmup, span, clue, when, p, top, second, ratio,
                    beta, row.label(), scenario, configBlock, reservations);
            caseAt++;
        }
        System.out.printf("%n%d case(s) built%n", caseAt);
    }

    /** Shipped instructions and the build note that must not ship with them. */
    private static void writeCaseDocs(Path folder, Path buildDir, String number, int photos,
                                      int scale, String palette, int boids, int psyboid,
                                      long seed, int warmup, int span, String clue,
                                      Collection<Integer> ticks, double[] p, int top,
                                      int second, double ratio, double[] beta, String label,
                                      ScenarioParameter scenario, String configBlock,
                                      String reservations) throws IOException {
        Files.writeString(folder.resolve("INSTRUCTIONS.md"), String.format("""
                # Case %s

                ## The scenario

                This folder contains %d screenshots taken from a single run of a boids
                simulation, in chronological order. Every boid follows the same flocking
                rules, which are given in full as source code under `code/`.

                Exactly one boid is the **psyboid**. It usually behaves like the others,
                but from time to time it ignores the flocking rules and steers
                deliberately, in order to maximise the time the flock spends inside the
                scoring zone. It is trying not to be identified.

                **Your task: work out which boid is the psyboid.**

                ## Reading the images

                | element | appearance |
                | --- | --- |
                | Play area | light grey `#E6E6E6` |
                | Wall / out of bounds | black `#000000` |
                | Scoring zone | pale peach `#F7E0CC` |
                | Boids | coloured triangles, nose pointing along the direction of travel |

                Boids move at constant speed and can only turn by a fixed amount per tick.
                Images are rendered at %dx, so each simulation pixel is a %dx%d block. The
                photographs are in chronological order but not evenly spaced in time.

                ## The candidates

                | colour | name |
                | --- | --- |
                %s
                ## Submitting your answer

                Append a single line to `out/psyboids.txt`:

                ```
                Case %s: COLOUR
                ```

                Rules:

                - Exactly one line per case. If a case appears more than once, the last
                  line wins.
                - Use the colour name exactly as written above, in capitals.
                - **A guess is mandatory.** If you are unsure, give your best guess.
                """, number, photos, scale, scale, scale, palette, PHYSICS_SECTION, number));

        StringBuilder probs = new StringBuilder();
        for (int i = 0; i < boids; i++) {
            probs.append(String.format("| %s | %.4f |%n", COLOUR_NAMES[i], p[i]));
        }

        Files.writeString(buildDir.resolve("Case " + number + ".md"), String.format("""
                # Case %s - build notes

                **NOT FOR SHIPMENT.** Contains the answer.

                ## Answer

                Boid %d, `%s`, rendered %s.

                Intended clue: **%s**

                ## Scenario

                | | |
                | --- | --- |
                | Map | `%s` |
                | Turning radius | %.0f |
                | Flock size | %d |
                | Seed | %d |
                %s
                | Warm-up | %d ticks, unsteered, score discarded |
                | Run | %d ticks |
                | Photographs | %d, uniform without replacement over the whole run |

                ## Acceptance

                Scored by the conditional-logit estimator using coefficients fitted on the
                other nine runs, never this one, at photographed instants drawn fresh rather
                than reused from the fitting data.

                Top pick **%s** at p=%.4f, runner-up **%s** at p=%.4f, ratio **%.1fx**
                against a required 4.0x.

                Coefficients (z-units, positive = more suspicious): %s

                | colour | estimated probability |
                | --- | --- |
                %s
                ## Photograph ticks

                %s

                ## Reservations

                %s

                ## Regenerating

                ```
                %s
                ```
                """, number, psyboid, hex(psyboid, boids), COLOUR_NAMES[psyboid], clue,
                scenario.mapPath(), scenario.turningRadius(), boids, seed, configBlock,
                warmup, span, photos,
                COLOUR_NAMES[top], p[top], COLOUR_NAMES[second], p[second], ratio,
                describeBeta(beta), probs, ticks, reservations, label));
    }

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
     * Redraws a saved timeline as paths: the whole run accumulated, and one panel per
     * commit so the run can be read as a sequence rather than a smear.
     * <p>
     * The psyboid is drawn heavily in both, so these reveal the answer. They are a tool
     * for learning what a psyboid looks like, not case material.
     */
    public static void pathsFromLabel(PresetScenarioParameter preset, String label, int warmup,
                                      int span, int interval, int columns, int scale, Path dir)
            throws IOException {
        Files.createDirectories(dir);
        Sim sim = new Sim(preset);
        sim.register(new FlockTrailLogger(dir.resolve("paths.png"), scale, 0.8f),
                FlockTrailLogger.TRIGGERS);
        sim.register(new TrailGridLogger(dir.resolve("sequence.png"), interval, columns, scale, 0.8f),
                TrailGridLogger.TRIGGERS);
        sim.replay(label, warmup, warmup + span);
        System.out.printf("paths over ticks %d-%d, %d panels of %d ticks -> %s%n",
                warmup, warmup + span, (span + interval - 1) / interval, interval, dir);
    }

    /**
     * One searched timeline, sampled several times inside a window of consecutive commits.
     * <p>
     * Unlike a case batch, every photograph here comes from the <em>same</em> run, so the
     * set is read together. How much a set can say depends on how many commits it covers:
     * a run's excess score is spread across its overrides, and a fixed number of frames
     * over a wider window catches a smaller share of it.
     */
    public static void photoArray(PresetScenarioParameter preset, int[] branches, int lookahead,
                                  double alpha, int maxDelay, int duration, int segments,
                                  int commits, int warmup, long seed, int spanFromCommit,
                                  int spanCommits, int photos, int scale, long pickSeed, Path dir)
            throws IOException {
        Engine engine = new Boids2DEngine(preset);
        int boids = preset.flockSize();
        Boids2DRenderer renderer = new Boids2DRenderer(preset.mapPath(), scale);

        Path build = dir.resolve(".build");
        Files.createDirectories(build);

        Random pick = new Random(pickSeed);
        int psyboid = pick.nextInt(boids);

        PsyboidSearch.Config config = new PsyboidSearch.Config(
                branches, lookahead, alpha, maxDelay, duration, segments, psyboid);
        int spacing = config.splitSpacing();
        int span = commits * spacing;

        // The window is a half-open run of commits: it opens where spanFromCommit opens
        // and closes where the commit after the last one in the window opens.
        int windowFrom = warmup + (spanFromCommit - 1) * spacing;
        int windowTo = windowFrom + spanCommits * spacing;

        System.out.printf("%s  %d boids  r=%.0f  seed %d%n",
                preset.name(), boids, preset.turningRadius(), seed);
        System.out.printf("  override: %d segments, %d ticks (%.0fs) in a %d tick (%.0fs) "
                        + "window, spacing %d%n",
                segments, duration, duration / 8.0, maxDelay, maxDelay / 8.0, spacing);
        System.out.printf("  shape %s  lookahead %d (%.0fs)  alpha %.2f  budget %.0f%n",
                config.describe(), lookahead, lookahead / 8.0, alpha, config.budget());
        System.out.printf("  warm-up %d, %d commits = %d ticks (%d-%d)%n",
                warmup, commits, span, warmup, warmup + span);
        System.out.printf("  %d photos over commits %d-%d, ticks [%d, %d)%n%n",
                photos, spanFromCommit, spanFromCommit + spanCommits - 1, windowFrom, windowTo);

        long started = System.nanoTime();
        Sim.State root = warmedRoot(engine, seed, warmup);
        PsyboidSearch search = new PsyboidSearch(engine, config, root, seed);
        for (int c = 0; c < commits; c++) search.commit();
        Sim.State canonical = search.canonical();
        Sim.State control = plainRun(engine, root, span);

        int[] ticks = new int[photos];
        for (int i = 0; i < photos; i++) ticks[i] = windowFrom + pick.nextInt(windowTo - windowFrom);
        Arrays.sort(ticks);

        for (int i = 0; i < photos; i++) {
            Sim.State frame = PsyboidSearch.replay(engine, canonical.label, warmup, ticks[i]);
            ImageIO.write(renderer.render(frame), "png",
                    dir.resolve(String.format("photo_%d.png", i + 1)).toFile());
        }
        double secs = (System.nanoTime() - started) / 1e9;

        double flock = 100.0 * canonical.score / boids / span;
        double ctl = 100.0 * control.score / boids / span;
        System.out.printf("occupancy over %d ticks%n", span);
        System.out.printf("  flock        %6.2f%%%n", flock);
        System.out.printf("  psyboid      %6.2f%%%n", 100.0 * canonical.boidScore[psyboid] / span);
        System.out.printf("  others each  %6.2f%%%n",
                100.0 * (canonical.score - canonical.boidScore[psyboid]) / (boids - 1) / span);
        System.out.printf("  control      %6.2f%%%n", ctl);
        System.out.printf("  EXCESS       %6.2f%% (%.2fx control)%n", flock - ctl, flock / ctl);
        System.out.printf("  psyboid rank %d of %d%n", rankOf(canonical, psyboid), boids);
        System.out.printf("  %.0fs%n", secs);

        StringBuilder notes = new StringBuilder();
        notes.append(String.format("psyboid: boid %d (%s)%n", psyboid, hex(psyboid, boids)));
        notes.append(String.format("seed %d, warm-up %d, spacing %d, %d commits%n",
                seed, warmup, spacing, commits));
        notes.append(String.format("photos at ticks: %s%n", Arrays.toString(ticks)));
        notes.append(String.format("window: commits %d-%d, ticks [%d, %d)%n",
                spanFromCommit, spanFromCommit + spanCommits - 1, windowFrom, windowTo));
        notes.append(String.format("photos:commits = %d:%d%n", photos, spanCommits));
        notes.append(String.format("flock %.2f%%  psyboid %.2f%%  control %.2f%%  excess %.2f%%%n",
                flock, 100.0 * canonical.boidScore[psyboid] / span, ctl, flock - ctl));
        notes.append(String.format("rank %d of %d%n", rankOf(canonical, psyboid), boids));
        notes.append("label:\n").append(canonical.label).append('\n');
        Files.writeString(build.resolve("notes.txt"), notes.toString());

        System.out.println("-> " + dir);
    }

    /**
     * Builds a batch of candidate cases: one searched timeline per seed, each reduced to a
     * single rendered frame.
     * <p>
     * Which boid is steered varies from case to case, so a reader cannot carry an answer
     * from one to the next. Everything that would give a case away â€” the psyboid, the tick
     * the frame was taken at, what each boid scored â€” goes to a build file rather than to
     * the console, so the batch can be generated and looked at without being spoiled.
     */
    public static void caseSet(PresetScenarioParameter preset, OverridePlan plan, int depth,
                               int lookahead, double alpha, int commits, int warmup,
                               long[] seeds, int frameFrom, int frameTo, long pickSeed, Path dir)
            throws IOException {
        Engine engine = new Boids2DEngine(preset);
        int boids = preset.flockSize();
        Boids2DRenderer renderer = new Boids2DRenderer(preset.mapPath(), 2);

        Path build = dir.resolve(".build");
        Files.createDirectories(build);
        SearchJournal.context(preset.name() + " n" + boids + " caseSet " + dir);

        Random pick = new Random(pickSeed);
        StringBuilder answers = new StringBuilder();
        answers.append("# ").append(preset.name()).append(" case batch\n\n");
        answers.append(String.format("map %s, turning radius %.0f, %d boids%n",
                preset.mapPath(), preset.turningRadius(), boids));
        answers.append("override plan: ").append(plan.describe()).append('\n');
        answers.append(String.format("  delays [0, %d) step %d, durations [%d, %d] step %d, "
                        + "directions %s%n",
                plan.interval(), plan.delayStep(), plan.durationLo(), plan.durationHi(),
                plan.durationStep(), java.util.Arrays.toString(plan.directions())));

        for (int c = 0; c < seeds.length; c++) {
            long seed = seeds[c];
            int psyboid = pick.nextInt(boids);
            int frameTick = frameFrom + pick.nextInt(frameTo - frameFrom + 1);

            PsyboidSearch.Config config =
                    new PsyboidSearch.Config(depth, lookahead, alpha, psyboid, plan);
            int span = commits * config.splitSpacing();

            if (c == 0) {
                answers.append(String.format("search: shape %s, lookahead %d (%.0fs), "
                                + "alpha %.2f, budget %.0f, %d commits of %d = %d ticks%n",
                        config.describe(), lookahead, lookahead / 8.0, alpha, config.budget(),
                        commits, config.splitSpacing(), span));
                answers.append(String.format("warm-up %d, steering begins at tick %d%n",
                        warmup, warmup));
                answers.append(String.format("frames drawn from ticks [%d, %d] "
                                + "(commit %d to commit %d)%n%n",
                        frameFrom, frameTo,
                        (frameFrom - warmup) / config.splitSpacing() + 1,
                        (frameTo - warmup) / config.splitSpacing() + 1));
                answers.append("case  seed  psyboid  frame   flock  psyboid   others  "
                        + "control   rank\n");
            }

            Sim.State root = warmedRoot(engine, seed, warmup);
            PsyboidSearch search = new PsyboidSearch(engine, config, root, seed);
            for (int i = 0; i < commits; i++) search.commit();

            Sim.State canonical = search.canonical();
            Sim.State control = plainRun(engine, root, span);
            Sim.State frame = PsyboidSearch.replay(engine, canonical.label, warmup, frameTick);

            String name = String.format("case_%02d", c + 1);
            ImageIO.write(renderer.render(frame), "png", dir.resolve(name + ".png").toFile());
            Files.writeString(build.resolve(name + "_label.txt"), canonical.label);

            answers.append(String.format(
                    "%4s  %4d  %7d  %5d  %5.2f%%  %6.2f%%  %6.2f%%  %6.2f%%   %d/%d%n",
                    name.substring(5), seed, psyboid, frameTick,
                    100.0 * canonical.score / boids / span,
                    100.0 * canonical.boidScore[psyboid] / span,
                    100.0 * (canonical.score - canonical.boidScore[psyboid]) / (boids - 1) / span,
                    100.0 * control.score / boids / span,
                    rankOf(canonical, psyboid), boids));

            System.out.printf("  %s.png%n", name);
        }

        Files.writeString(build.resolve("answers.md"), answers.toString());
        System.out.println("-> " + dir + "  (answers in " + build + ")");
    }

    /**
     * Checks the sequential update did what it was meant to and nothing else.
     * <p>
     * Three properties, in order of how quietly they would break things. A state handed
     * out must never be written to, or the search corrupts the tree it is standing on.
     * Advancing the same state twice must give the same answer, or nothing replays. And
     * no two boids may end a tick sharing a position and heading, which is the fusion the
     * sequencing was introduced to prevent.
     */
    public static void sequentialCheck(PresetScenarioParameter preset, long seed, int ticks)
            throws IOException {
        Engine engine = new Boids2DEngine(preset);
        Sim.State root = engine.init(seed);
        int n = root.n;

        int[] x0 = root.x.clone(), y0 = root.y.clone(), h0 = root.h.clone();
        Sim.State a = engine.tick(root);
        Sim.State b = engine.tick(root);

        boolean untouched = Arrays.equals(x0, root.x) && Arrays.equals(y0, root.y)
                && Arrays.equals(h0, root.h);
        boolean idempotent = Arrays.equals(a.x, b.x) && Arrays.equals(a.y, b.y)
                && Arrays.equals(a.h, b.h) && a.score == b.score;

        System.out.printf("%s seed %d, %d boids, %d ticks%n", preset.name(), seed, n, ticks);
        System.out.println("  argument state untouched by tick : " + untouched);
        System.out.println("  re-advancing gives same result   : " + idempotent);

        // A coincidence is only fatal if it persists: two boids at the same position and
        // heading under a symmetric update can never separate again, so what matters is
        // not whether pairs touch but whether any pair is still welded at the end.
        int coincidences = 0, worstRun = 0;
        int[] running = new int[n * n];
        Sim.State s = root;
        for (int t = 0; t < ticks; t++) {
            s = engine.tick(s);
            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) {
                    boolean same = s.x[i] == s.x[j] && s.y[i] == s.y[j] && s.h[i] == s.h[j];
                    if (same) {
                        coincidences++;
                        running[i * n + j]++;
                        worstRun = Math.max(worstRun, running[i * n + j]);
                    } else {
                        running[i * n + j] = 0;
                    }
                }
            }
        }
        System.out.printf("  coincident pair-ticks            : %d%n", coincidences);
        System.out.printf("  longest unbroken coincidence     : %d ticks%n", worstRun);
        System.out.println("  no escapes                       : true (tick throws otherwise)");
    }

    /**
     * The same map and the same search at several flock sizes, each reduced to one picture
     * and two numbers.
     * <p>
     * Flock size is the one scenario knob that changes the problem rather than the search:
     * a psyboid nudging nine neighbours and a psyboid nudging thirty-nine are doing
     * different jobs with the same lever. Only the psyboid's own path is drawn, since with
     * forty boids a whole-flock trail is a solid block of ink.
     */
    public static void flockSizes(PresetScenarioParameter preset, int[] branches, int commits,
                                  int[] sizes, long seed, int psyboid) throws IOException {
        Path dir = Path.of("render", "flocksize");

        PsyboidSearch.Config shape = new PsyboidSearch.Config(
                branches, SEARCH_LOOKAHEAD, 0.85, 32, SEARCH_DURATION, SEARCH_SEGMENTS, psyboid);
        int span = commits * shape.splitSpacing();
        System.out.printf("%s  shape %s  lookahead %d  alpha %.2f  budget %.0f%n",
                preset.name(), shape.describe(), SEARCH_LOOKAHEAD, 0.85, shape.budget());
        System.out.printf("%d commits of %d = %d ticks, seed %d, psyboid %d%n%n",
                commits, shape.splitSpacing(), span, seed, psyboid);
        System.out.println("boids     flock    psyboid    control  unsteered   rank    secs");

        for (int boids : sizes) {
            ScenarioParameter scenario = withFlockSize(preset, boids);
            Engine engine = new Boids2DEngine(scenario);
            PsyboidSearch.Config config = new PsyboidSearch.Config(
                    branches, SEARCH_LOOKAHEAD, 0.85, 32, SEARCH_DURATION, SEARCH_SEGMENTS, psyboid);

            long started = System.nanoTime();
            Sim.State root = warmedRoot(engine, seed);
            PsyboidSearch search = new PsyboidSearch(engine, config, root, seed);
            for (int c = 0; c < commits; c++) search.commit();

            Sim.State canonical = search.canonical();
            Sim.State control = plainRun(engine, root, span);
            double secs = (System.nanoTime() - started) / 1e9;

            String stem = preset.name().toLowerCase() + "_n" + boids;
            Sim sim = new Sim(scenario);
            sim.register(new PsyboidTrailLogger(dir.resolve(stem + "_psyboid.png"), 2),
                    PsyboidTrailLogger.TRIGGERS);
            sim.replay(canonical.label, WARMUP, WARMUP + span);
            Files.writeString(Path.of("data", stem + "_label.txt"), canonical.label);

            System.out.printf("%5d   %6.2f%%   %6.2f%%   %6.2f%%   %6.2f%%   %2d/%-2d  %6.0f%n",
                    boids,
                    100.0 * canonical.score / boids / span,
                    100.0 * canonical.boidScore[psyboid] / span,
                    100.0 * control.score / boids / span,
                    100.0 * control.boidScore[psyboid] / span,
                    rankOf(canonical, psyboid), boids, secs);
        }
        System.out.println("\n-> " + dir);
    }

    /**
     * Deliberately does nothing. The entry points below all launch searches that cost
     * minutes to hours, so which one runs is a decision to make on purpose rather than
     * by launching the class.
     */
    /**
     * Does the flock need the psyboid, or has it merely been parked somewhere good?
     * <p>
     * At intervals along a run the flock is frozen, the psyboid withdrawn, and the
     * timeline allowed to continue unsteered. An orbit that holds its occupancy is
     * self-sustaining and the psyboid's work was one-time setup; one that decays back
     * toward the control was being actively maintained, and the gap between the two is
     * how much work the psyboid is doing per unit time.
     */
    public static void orbitMaintenance(PresetScenarioParameter preset, int[] branches,
                                        int commits, long[] seeds, int psyboid)
            throws IOException {
        Engine engine = new Boids2DEngine(preset);
        int boids = preset.flockSize();

        PsyboidSearch.Config config = new PsyboidSearch.Config(
                branches, SEARCH_LOOKAHEAD, 0.85, 32, SEARCH_DURATION, SEARCH_SEGMENTS, psyboid);
        int spacing = config.splitSpacing();
        int span = commits * spacing;

        final int tail = 2000;
        final int sub = 500;
        int windowCommits = tail / spacing;

        System.out.printf("%s  shape %s  budget %.0f  %d commits of %d = %d ticks%n",
                preset.name(), config.describe(), config.budget(), commits, spacing, span);
        System.out.printf("abandonment: freeze, withdraw the psyboid, run %d ticks in %d-tick windows%n%n",
                tail, sub);
        System.out.println("seed   tick   steered   withdrawn: +500  +1000  +1500  +2000   control");

        for (long seed : seeds) {
            Sim.State root = warmedRoot(engine, seed);
            PsyboidSearch search = new PsyboidSearch(engine, config, root, seed);
            long[] scoreAt = new long[commits + 1];

            double controlOcc = 100.0 * plainRun(engine, root, span).score / boids / span;

            for (int c = 1; c <= commits; c++) {
                search.commit();
                Sim.State canonical = search.canonical();
                scoreAt[c] = canonical.score;

                boolean breakpoint = c % (commits / 4) == 0;
                if (!breakpoint || c < windowCommits) continue;

                double steered = 100.0 * (scoreAt[c] - scoreAt[c - windowCommits])
                        / boids / (windowCommits * spacing);
                double[] withdrawn = withdraw(engine, canonical, boids, tail, sub);

                System.out.printf("%4d %6d   %6.2f%%             ", seed, c * spacing, steered);
                for (double occ : withdrawn) System.out.printf("%6.2f ", occ);
                System.out.printf("  %6.2f%%%n", controlOcc);
            }

            Sim.State canonical = search.canonical();
            Files.writeString(Path.of("data",
                    preset.name().toLowerCase() + "_maint_seed" + seed + "_label.txt"),
                    canonical.label);

            if (seed == seeds[0]) {
                String stem = preset.name().toLowerCase() + "_maint";
                Path dir = Path.of("render", "longrun");
                Sim sim = new Sim(preset);
                sim.register(new FlockTrailLogger(dir.resolve(stem + "_paths.png"), 2, 0.8f),
                        FlockTrailLogger.TRIGGERS);
                sim.register(new ScoreSeriesLogger(
                        Path.of("data", stem + "_scores.csv"), Params.TURNS / 8),
                        ScoreSeriesLogger.TRIGGERS);
                sim.register(new FrameGridLogger(dir.resolve(stem + "_grid.png"), span / 20, 4, 1),
                        FrameGridLogger.TRIGGERS);
                sim.replay(canonical.label, WARMUP, WARMUP + span);
            }

            System.out.printf("     final  flock %.2f%%  psyboid %.2f%%  others %.2f%%  rank %d/%d%n%n",
                    100.0 * canonical.score / boids / span,
                    100.0 * canonical.boidScore[psyboid] / span,
                    100.0 * (canonical.score - canonical.boidScore[psyboid]) / (boids - 1) / span,
                    rankOf(canonical, psyboid), boids);
        }
    }

    /** Occupancy per sub-window once the overrides are dropped. */
    private static double[] withdraw(Engine engine, Sim.State from, int boids, int tail, int sub) {
        Sim.State s = new Sim.State(from.n, from.x, from.y, from.h, from.tick, 0L,
                new long[from.n], from.label);
        double[] occupancy = new double[tail / sub];
        long previous = 0;
        for (int w = 0; w < occupancy.length; w++) {
            for (int t = 0; t < sub; t++) s = engine.tick(s);
            occupancy[w] = 100.0 * (s.score - previous) / boids / sub;
            previous = s.score;
        }
        return occupancy;
    }

    private static int rankOf(Sim.State state, int boid) {
        int better = 0;
        for (int i = 0; i < state.n; i++) if (state.boidScore[i] > state.boidScore[boid]) better++;
        return better + 1;
    }

    /** Cheap viability check before committing to a long search on a new map. */
    public static void kernelReport(PresetScenarioParameter preset) throws IOException {
        NavMap map = NavMapBuilder.buildFromPng(preset.mapPath(),
                Math.round(preset.turningRadius()));
        long free = 0, live = 0, traps = 0, full = 0;
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                if (map.oob(x, y)) continue;
                free++;
                int n = map.liveHeadings(x, y);
                live += n;
                if (n == 0) traps++;
                else if (n == Params.TURNS) full++;
            }
        }
        System.out.printf("%s %dx%d r=%.0f  in-play %d  live headings %.1f%%  "
                        + "traps %d  unconstrained %d%n",
                preset.name(), map.width(), map.height(), preset.turningRadius(),
                free, 100.0 * live / (free * Params.TURNS), traps, full);
        NavMapRender.write(map, 1,
                Path.of("render", "kernel", preset.name().toLowerCase() + ".png"));
    }

    public static void main(String[] args) throws IOException {
        // Output goes inside the map's own ingest, so a route trace or an edge map can
        // never be read against a dabnt that has been edited since it was produced.
        decompose(PresetScenarioParameter.PLAIT, true, 360, 335, 350, 0);
        if (true) return;
    }

    private static void holdoutCasesPlinko() throws IOException {
        holdoutCases(PresetScenarioParameter.PLINKO, 10,
                new long[]{3200, 3201, 3202, 3203, 3204, 3205}, 120, 500,
                SEARCH_LOOKAHEAD, 0.85, "high", "PLINKO n10 holdout high", 22, 0.0, 2,
                "Psyboid points away from the flock and drifts to its edge",
                """
                | Dilution | high (psyboid steering ~20% of ticks) |
                | Overrides | 3 segments, 32 ticks (4s), max delay 128 ticks (16s) |
                | Commit interval | 160 ticks |
                | Search | 256x2x2x2x1x1x1x1, lookahead 80, alpha 0.85 |""",
                """
                - Plinko's scoring zone is 50.5% of the play area and control runs near 50%,
                  so being in the zone means nothing here. Roughly half the flock always is.
                - The psyboid ranks 7th of 10 by score on average and last in three runs of
                  ten. Guessing the highest scorer is worse than chance; guessing the lowest
                  would do better. This is the strongest score camouflage measured on any
                  map so far.
                - The clue is orientation, not position. Heading deviation carries a
                  coefficient of +2.6, roughly double its weight on any other map, while
                  map position (distance from the central pillar, angle folded by the map's
                  180-degree symmetry) carries essentially none.
                - **Per frame the signal is weak**: the psyboid sits about 0.44 standard
                  deviations out on heading deviation and is the most extreme boid in only
                  23% of frames against a 10% baseline. It is identifiable only by averaging
                  across many photographs, which is the intended asymmetry but also means a
                  reader working by eye should be expected to fail.
                - Accepted on margin rather than probability ratio. Summed log-odds over 120
                  frames saturate: p(top) rounds to 1.0 and the ratio reports arithmetic
                  overflow rather than confidence.""",
                Path.of("packet", "cases"), Path.of("cases", ".build"), 20260827L,
                Path.of("data", "plinko-leverage.csv"), Path.of("data", "plinko-geometry.csv"));

        // Do the maps on disk still reproduce the shipped photographs? Only the map file
        // and the movement rules can break this, and both have been edited since some of
        // these cases were built.
        for (String number : new String[]{"0002", "0010", "0016", "0020"}) {
            Path note = Path.of("cases", ".build", "Case " + number + ".md");
            String text = Files.readString(note);
            String mapName = java.util.regex.Pattern.compile("\\| Map \\| `[^`]*?([a-z]+)\\.png")
                    .matcher(text).results().findFirst().orElseThrow().group(1);
            int warmup = Integer.parseInt(java.util.regex.Pattern.compile("Warm-up \\| (\\d+)")
                    .matcher(text).results().findFirst().orElseThrow().group(1));
            // Three generations wrote three build-note formats. The tick of the first
            // photograph is a table row in one, a set literal in another, and a labelled
            // field in the third; a single pattern for all three matched an override
            // delay range instead.
            int firstTick = firstOf(text, "Frame captured \\| tick (\\d+)",
                    "\\| 1 \\| (\\d+) \\|", "Photograph ticks\\s*\\n+\\[(\\d+)");
            String label = java.util.regex.Pattern
                    .compile("```\\s*\\n(seed[^`]+?)\\n```", java.util.regex.Pattern.DOTALL)
                    .matcher(text).results().reduce((a, b) -> b).orElseThrow().group(1).trim();

            Path folder = Path.of("packet", "cases", "Case " + number);
            String instructions = Files.readString(folder.resolve("INSTRUCTIONS.md"));
            int scale = Integer.parseInt(java.util.regex.Pattern.compile("rendered at (\\d)x")
                    .matcher(instructions).results().findFirst().orElseThrow().group(1));
            Path shipped = Files.list(folder).filter(f -> f.getFileName().toString()
                    .endsWith(".png")).sorted().findFirst().orElseThrow();

            PresetScenarioParameter preset = PresetScenarioParameter.valueOf(mapName.toUpperCase());
            int boids = Integer.parseInt(java.util.regex.Pattern.compile("Flock size \\| (\\d+)")
                    .matcher(text).results().findFirst().orElseThrow().group(1));
            ScenarioParameter scenario = withFlockSize(preset, boids);
            Engine engine = new Boids2DEngine(scenario);
            BufferedImage now = new Boids2DRenderer(scenario.mapPath(), scale)
                    .render(PsyboidSearch.replay(engine, label, warmup, firstTick));
            BufferedImage was = javax.imageio.ImageIO.read(shipped.toFile());

            long diff = 0;
            for (int y = 0; y < was.getHeight(); y++)
                for (int x = 0; x < was.getWidth(); x++)
                    if (now.getRGB(x, y) != was.getRGB(x, y)) diff++;
            System.out.printf("Case %s (%s, n=%d, %dx): %d differing pixels  %s%n",
                    number, mapName, boids, scale, diff, diff == 0 ? "IDENTICAL" : "MISMATCH");
        }
        if (true) return;

        holdoutCases(PresetScenarioParameter.HAMBURGER, 10,
                new long[]{2001, 2002, 2003}, 120, 500, SEARCH_LOOKAHEAD, 0.85,
                "high", "holdout high", 19, 4.0, 3,
                "Psyboid spends more time in the scoring zone and points away from the flock",
                """
                | Dilution | high (psyboid steering ~20% of ticks) |
                | Overrides | 3 segments, 32 ticks (4s), max delay 128 ticks (16s) |
                | Commit interval | 160 ticks |
                | Search | 256x2x2x2x1x1x1x1, lookahead 80, alpha 0.85 |""",
                """
                - Accepted on the estimator's judgement, not a human's, and at high dilution
                  that estimator is much weaker than at low: roughly 50-60% top-1 across
                  runs. These three are the confident tail of that distribution, so they are
                  selected for legibility rather than representative of the regime.
                - A fourth run at these settings (seed 2000) was identified correctly but at
                  a 1.3x ratio and was dropped. Roughly one high-dilution run in four looks
                  like that.
                - The estimator leans on heading deviation, time in the scoring zone and
                  distance from the flock centroid - a different signature from the low
                  dilution cases, where isolation dominates and zone occupancy is worthless.
                - Control on this map runs about 7%, so the flock scores unaided. Presence in
                  the scoring zone is evidence here but not proof.
                - Sample density matters more than photograph count. These runs are 10,720
                  ticks, so 120 photographs sit about 89 ticks apart. The same count over a
                  3,200-tick run performs far worse.""",
                Path.of("packet", "cases"), Path.of("cases", ".build"), 20260823L);
        if (true) return;
        System.out.println("Nothing runs by default. Call one of:");
        System.out.println("  longRun(preset, branches, lookahead, alpha, commits, seed, psyboid, tag)");
        System.out.println("  rollingSearch(preset, boids, uncontrolled, commits)");
        System.out.println("  dilutionGrid(preset)");
        System.out.println("  flockTrail(preset, seed, ticks, out)");
        System.out.println("  replayLabel(preset, labelFile, span, tag)");
    }

    /**
     * Redraws a saved canonical line without re-searching. Every run writes its label,
     * so any picture or series from a past run can be regenerated from a few kilobytes
     * rather than by paying for the search again.
     */
    public static void replayLabel(PresetScenarioParameter preset, Path labelFile,
                                   int span, String tag) throws IOException {
        String label = Files.readString(labelFile).trim();
        String stem = preset.name().toLowerCase() + "_" + tag;
        Path dir = Path.of("render", "longrun");

        Sim sim = new Sim(preset);
        sim.register(new FlockTrailLogger(dir.resolve(stem + "_paths.png"), 2, 0.8f),
                FlockTrailLogger.TRIGGERS);
        sim.register(new ScoreSeriesLogger(
                Path.of("data", stem + "_scores.csv"), Params.TURNS / 8),
                ScoreSeriesLogger.TRIGGERS);
        sim.register(new FrameGridLogger(dir.resolve(stem + "_grid.png"), span / 20, 4, 1),
                FrameGridLogger.TRIGGERS);
        sim.replay(label, WARMUP, WARMUP + span);
        System.out.println("replayed " + labelFile + " -> " + dir.resolve(stem + "_grid.png"));
    }

    public static void oldMain(String[] args) throws IOException {
        System.out.println("=== label replay check ===");
        boolean all = true;
        for (int uncontrolled : new int[]{8, 64, 256}) {
            for (int[] branches : new int[][]{{35, 4, 2}, {140, 4, 2}}) {
                for (long seed : new long[]{0, 3}) {
                    all &= verifyReplay(PresetScenarioParameter.PLINKO, uncontrolled,
                            branches, seed, 12);
                }
            }
        }
        for (long seed : new long[]{0, 1}) {
            all &= verifyReplay(PresetScenarioParameter.HAMBURGER, 8, new int[]{35, 4, 2}, seed, 12);
        }
        System.out.println(all ? "all replays exact" : "REPLAY FAILED");
        if (!all) return;

        System.out.println();
        System.out.println("=== psyboid trails from saved canonical lines ===");
        List<String> lines = Files.readAllLines(Path.of("data", "canonical_plinko.csv"));
        for (String pick : new String[]{"35x4x2,8,0,", "140x4x2,8,3,", "140x4x2,256,5,"}) {
            for (String line : lines) {
                if (!line.startsWith(pick)) continue;
                String[] f = line.split(",");
                long ticks = Long.parseLong(f[4]);
                trailFromLabel(PresetScenarioParameter.PLINKO, f[10], WARMUP + ticks,
                        Path.of("render", "trail", "plinko_" + f[0] + "_u" + f[1] + "_seed" + f[2] + ".png"));
                break;
            }
        }
    }

    public static void sweepMain(String[] args) throws IOException {
        long[] seeds = DEFAULT_SEEDS;
        if (args.length > 0) {
            seeds = new long[args.length];
            for (int i = 0; i < args.length; i++) seeds[i] = Long.parseLong(args[i]);
        }
        // One bad seed must not cost the whole batch: these runs are meant to be left
        // alone, so a failure is logged and the sweep moves on.
        int failed = 0;
        for (long seed : seeds) {
            try {
                overrideSweep(seed);
            } catch (RuntimeException e) {
                failed++;
                System.out.printf("seed %d FAILED: %s%n", seed, e);
            }
        }
        System.out.printf("%ndone: %d seeds, %d failed%n", seeds.length, failed);
    }

    /**
     * Every override this scenario admits, run against one warmed-up timeline, with the
     * score of each recorded at fixed intervals out to a long horizon.
     * <p>
     * The suite is deterministic rather than sampled: every boid as psyboid, crossed
     * with every turn direction, every permitted duration, and a fixed ladder of start
     * delays. The early checkpoints deliberately land before the later-starting
     * overrides have taken effect, so the data contains its own baseline.
     * <p>
     * Scores are cumulative from the split, so the score over any interval is the
     * difference between two columns.
     */
    private static void overrideSweep(long seed) throws IOException {
        long start = System.nanoTime();

        Sim sim = new Sim(withFlockSize(PresetScenarioParameter.HAMBURGER, SWEEP_BOIDS));
        sim.startSeeds(seed);
        sim.stepTo(WARMUP);
        long splitTick = sim.tick();

        List<PsyboidOverride> overrides = new ArrayList<>();
        for (int psyboid = 0; psyboid < SWEEP_BOIDS; psyboid++) {
            for (int direction = -1; direction <= 1; direction++) {
                for (int duration = Sim.MIN_OVERRIDE_TICKS; duration <= Sim.MAX_OVERRIDE_TICKS; duration++) {
                    for (int step = 0; step < SWEEP_DELAY_STEPS; step++) {
                        double u = (step + 0.5) / SWEEP_DELAY_STEPS;
                        int delay = (int) Math.round(u * u * SWEEP_MAX_DELAY);
                        overrides.add(new PsyboidOverride(
                                (int) splitTick + delay, duration, direction, psyboid));
                    }
                }
            }
        }

        sim.splitByOverrides(overrides.toArray(new PsyboidOverride[0]));
        sim.resetScores();

        int checkpoints = SWEEP_HORIZON / SWEEP_CHECKPOINT;
        long[][] columns = new long[checkpoints][];
        for (int c = 0; c < checkpoints; c++) {
            sim.stepTo(splitTick + (long) (c + 1) * SWEEP_CHECKPOINT);
            List<Long> scores = sim.scores();
            columns[c] = new long[scores.size()];
            for (int i = 0; i < scores.size(); i++) columns[c][i] = scores.get(i);
        }

        Path out = Path.of("data", String.format("hamburger_n%d_seed%d.csv", SWEEP_BOIDS, seed));
        writeSweep(out, seed, splitTick, overrides, sim.labels(), columns);

        System.out.printf("seed %d: %d variations + control, %d checkpoints, %.1fs -> %s%n",
                seed, overrides.size(), checkpoints, (System.nanoTime() - start) / 1e9, out);
    }

    private static void writeSweep(Path out, long seed, long splitTick,
                                   List<PsyboidOverride> overrides, List<String> labels,
                                   long[][] columns) throws IOException {
        if (out.getParent() != null) Files.createDirectories(out.getParent());

        try (BufferedWriter w = Files.newBufferedWriter(out)) {
            w.write("seed,label,psyboid,direction,duration,onset,delay,boids,split_tick");
            for (int c = 0; c < columns.length; c++) {
                w.write(",t" + (c + 1) * SWEEP_CHECKPOINT);
            }
            w.newLine();

            for (int row = 0; row < labels.size(); row++) {
                // Row 0 is the control; the rest line up with the override list.
                PsyboidOverride o = row == 0 ? null : overrides.get(row - 1);

                w.write(Long.toString(seed));
                w.write(',');
                w.write(labels.get(row));
                if (o == null) {
                    w.write(",,,,,");
                } else {
                    w.write("," + o.psyboid());
                    w.write("," + o.direction());
                    w.write("," + o.duration());
                    w.write("," + o.onset());
                    w.write("," + (o.onset() - splitTick));
                }
                w.write("," + SWEEP_BOIDS);
                w.write("," + splitTick);
                for (long[] column : columns) w.write("," + column[row]);
                w.newLine();
            }
        }
    }

    /**
     * How much score the luckiest override buys over the average one.
     * <p>
     * One warmed-up timeline is split into {@link #VARIATIONS} variants that differ
     * only in the override steering boid 0, plus an untouched control. All of them run
     * the same number of ticks from the same state, so the spread across them is
     * attributable to the override alone.
     */
    private static void psyboidLeverage() throws IOException {
        int span = MAX_DELAY + OBSERVE;
        System.out.printf("HAMBURGER: %d variations split at tick %d, scored over the next %d ticks%n",
                VARIATIONS, WARMUP, span);
        System.out.printf("override starts within %d ticks, runs %d-%d ticks%n%n",
                MAX_DELAY, Params.TURNS / 8, 3 * Params.TURNS / 8);
        System.out.println("boids  seed  control     mean      max   max-mean   as % of mean");

        for (int boids : new int[]{5, 10, 15, 20}) {
            double leverageTotal = 0;
            double ratioTotal = 0;
            int scoring = 0;

            for (int seed = 0; seed < REPEATS; seed++) {
                Sim sim = new Sim(withFlockSize(PresetScenarioParameter.HAMBURGER, boids));
                sim.startSeeds(seed);
                sim.stepTo(WARMUP);

                PsyboidOverride[] overrides = new PsyboidOverride[VARIATIONS];
                for (int i = 0; i < VARIATIONS; i++) overrides[i] = sim.randomOverride(MAX_DELAY);
                sim.splitByOverrides(overrides);

                sim.resetScores();
                sim.stepTo(WARMUP + span);

                List<Long> scores = sim.scores();
                long control = scores.get(0);

                double mean = 0;
                long max = Long.MIN_VALUE;
                for (int i = 1; i <= VARIATIONS; i++) {
                    mean += scores.get(i);
                    max = Math.max(max, scores.get(i));
                }
                mean /= VARIATIONS;

                double leverage = max - mean;
                leverageTotal += leverage;

                // A set where nothing scored at all has no ratio to report, and folding
                // it in as zero would understate the rest.
                String ratio = "-";
                if (mean > 0) {
                    ratioTotal += 100.0 * leverage / mean;
                    scoring++;
                    ratio = String.format("%.1f%%", 100.0 * leverage / mean);
                }

                System.out.printf("%5d %5d  %7d  %7.1f  %7d   %8.1f   %11s%n",
                        boids, seed, control, mean, max, leverage, ratio);
            }

            String avgRatio = scoring == 0 ? "-" : String.format("%.1f%%", ratioTotal / scoring);
            System.out.printf("%5d   avg                             %8.1f   %11s%s%n%n",
                    boids, leverageTotal / REPEATS, avgRatio,
                    scoring < REPEATS ? "   (" + (REPEATS - scoring) + " set(s) scored nothing)" : "");
        }
    }

    /** Kept from an earlier run: the max-minus-mean leverage probe. */
    public static void leverageProbe() throws IOException {
        psyboidLeverage();
    }

    /** Kept from an earlier run: the score-rate sweep across maps and boid counts. */
    public static void scoreSweep() throws IOException {
        System.out.printf("%d sims x %d intervals of %d ticks, after %d ticks of warm-up%n%n",
                SIMS, INTERVALS, INTERVAL, WARMUP);
        System.out.println("map        boids  samples     mean       sd    sd/mean     min     max");
        for (int boids : new int[]{5, 10, 15, 20}) {
            measure(PresetScenarioParameter.HAMBURGER, boids);
        }
        measure(PresetScenarioParameter.PLINKO, PresetScenarioParameter.PLINKO.flockSize());
    }

    /**
     * Mean and spread of score per boid per tick, sampled over fixed intervals across
     * several independent timelines.
     */
    private static void measure(PresetScenarioParameter preset, int boids) throws IOException {
        Sim sim = new Sim(withFlockSize(preset, boids));

        long[] seeds = new long[SIMS];
        for (int i = 0; i < SIMS; i++) seeds[i] = i;
        sim.startSeeds(seeds);
        sim.stepTo(WARMUP);

        List<Double> samples = new ArrayList<>(SIMS * INTERVALS);
        for (int k = 1; k <= INTERVALS; k++) {
            sim.resetScores();
            sim.stepTo(WARMUP + (long) k * INTERVAL);
            for (long score : sim.scores()) {
                samples.add(score / (double) boids / INTERVAL);
            }
        }

        double mean = 0;
        for (double v : samples) mean += v;
        mean /= samples.size();

        double var = 0;
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (double v : samples) {
            var += (v - mean) * (v - mean);
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        double sd = Math.sqrt(var / (samples.size() - 1));

        System.out.printf("%-10s %5d  %7d  %7.4f  %7.4f  %8.1f%%  %6.4f  %6.4f%n",
                preset.name(), boids, samples.size(), mean, sd, 100.0 * sd / mean, min, max);
    }

    private static ScenarioParameter withFlockSize(ScenarioParameter base, int boids) {
        return new ScenarioParameter() {
            public Path mapPath() { return base.mapPath(); }
            public float turningRadius() { return base.turningRadius(); }
            public int flockSize() { return boids; }
        };
    }

    /** Kept from an earlier run: a stitched grid of five timelines over their first ticks. */
    public static void renderStartGrid(PresetScenarioParameter preset, Path out) throws IOException {
        Sim sim = new Sim(preset);
        sim.startSeeds(0, 1, 2, 3, 4);
        for (int s = 0; s < 50; s += 5) {
            sim.stepTo(s);
            sim.logAll();
        }
        sim.print(out);
    }
}

