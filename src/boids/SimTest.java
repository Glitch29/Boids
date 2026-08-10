package boids;

import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
        Sim.State s = engine.init(seed);
        while (s.tick < WARMUP) s = engine.tick(s);
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
        PresetScenarioParameter preset = PresetScenarioParameter.OUTLOOPED;
        int commits = args.length > 0 ? Integer.parseInt(args[0]) : 250;

        kernelReport(preset);
        flockSizes(preset, new int[]{256, 2, 2, 2, 1, 1, 1, 1},
                commits, new int[]{10, 20, 30, 40}, 0L, 7);
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
