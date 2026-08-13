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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
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
     * @param lift mean of the per-seed lift, not the lift of the means — the control is
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
     * lengthens the commit — so runs are held to a fixed number of canonical ticks
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
     * The first kind is trivially visible — a boid that parks in the scoring zone stands
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

        // Only the excess reaches the console. How the excess was earned — the psyboid's
        // own share against the rest of the flock's, and its rank — is exactly what a
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
     * root, which is what makes the excess column meaningful — the baseline moves with
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
        System.out.printf("chi-square %.0f on ~%d df — %.2f per cell (1.0 means no structure)%n",
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
     * therefore the most direct measure of leverage available — cohesion and alignment
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
     * Coasting value, steering value and visibility answer different questions — what a
     * boid is worth doing nothing, what it could be worth trying, and how much of the
     * flock it can influence at all — and the first two have already been shown to fire in
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
     * itself; what is interesting is the rest — how much the flock loses beyond that,
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
     * decisions. The labels are what this is for — the journal keeps them, and the
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
     * them — a case costs a replay rather than the minutes the search originally took.
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
            // temperature blows it up — the gap in weighted mean-z between the top two,
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
     * from one to the next. Everything that would give a case away — the psyboid, the tick
     * the frame was taken at, what each boid scored — goes to a build file rather than to
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
        holdoutCasesPlinko();
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
