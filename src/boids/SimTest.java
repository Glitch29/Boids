package boids;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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

    public static void main(String[] args) throws IOException {
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
