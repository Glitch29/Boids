package boids;

import java.io.IOException;
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

    public static void main(String[] args) throws IOException {
        psyboidLeverage();
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
