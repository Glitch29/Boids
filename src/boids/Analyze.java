package boids;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Reduces the override sweep CSVs into summary tables.
 * <p>
 * Reads every {@code data/*.csv}, streaming rather than materialising, and writes the
 * results under {@code analysis/}. Nothing here touches the simulation.
 * <p>
 * A "cluster" is one (seed, psyboid) pair — the 816 variations that share a timeline
 * and a psyboid, differing only in direction, duration and start delay. A "variant
 * name" is the part of a label from the direction letter onward, e.g. {@code Ld16t523},
 * which is comparable across seeds because every sweep splits at the same tick.
 */
public final class Analyze {

    private static final int CHECKPOINTS = 20;
    private static final int CHECKPOINT_TICKS = 32;
    private static final int FIRST_SCORE_FIELD = 9;

    private static final int DIRECTIONS = 3;
    private static final int MIN_DURATION = Sim.MIN_OVERRIDE_TICKS;
    private static final int MAX_DURATION = Sim.MAX_OVERRIDE_TICKS;
    private static final int DURATIONS = MAX_DURATION - MIN_DURATION + 1;

    /** Control score bucket width for the histogram. */
    private static final int BUCKET = 10;

    /**
     * Checkpoint indices at which a "best" variation is chosen and its whole trajectory
     * recorded. The last one is hindsight; the earlier one leaves everything after it
     * out of sample.
     */
    private static final int[] SELECTION_CHECKPOINTS = {CHECKPOINTS / 2 - 1, CHECKPOINTS - 1};

    private static final char[] TURN = {'L', 'S', 'R'};

    public static void main(String[] args) throws IOException {
        Path dataDir = Path.of(args.length > 0 ? args[0] : "data");
        Path outDir = Path.of(args.length > 1 ? args[1] : "analysis");
        Files.createDirectories(outDir);

        List<Path> files;
        try (Stream<Path> s = Files.list(dataDir)) {
            files = s.filter(p -> p.toString().endsWith(".csv")).sorted().toList();
        }
        System.out.printf("reading %d files from %s%n", files.size(), dataDir);
        long start = System.nanoTime();

        Accumulator acc = new Accumulator();
        try (BufferedWriter clusterBest = writer(outDir.resolve("cluster_best.csv"));
             BufferedWriter trajectories = writer(outDir.resolve("best_trajectories.csv"))) {

            header(clusterBest, "seed,psyboid", "best");
            header(trajectories,
                    "selected_at_tick,seed,psyboid,variant,direction,duration,delay,onset",
                    "best", "control");

            for (Path f : files) acc.readFile(f, clusterBest, trajectories);
        }

        acc.writeControlStats(outDir.resolve("control_stats.csv"));
        acc.writeControlHistogram(outDir.resolve("control_histogram.csv"));
        acc.writeWinners(outDir.resolve("winners_by_tick.csv"));
        acc.writeWinnersCollapsed(outDir);
        acc.writeDelayWindow(outDir.resolve("best_by_delay_window.csv"));

        System.out.printf("%d clusters, %d controls, %.1fs -> %s%n",
                acc.clusters, acc.controls, (System.nanoTime() - start) / 1e9, outDir);
    }

    // ------------------------------------------------------------------------

    private static final class Accumulator {

        int clusters;
        int controls;

        /** Control cumulative score: running sums per checkpoint, and a bucketed histogram. */
        final double[] controlSum = new double[CHECKPOINTS];
        final double[] controlSumSq = new double[CHECKPOINTS];
        final List<Map<Integer, Integer>> controlHist = new ArrayList<>();

        /** tick index -> variant name -> share of high scores, ties split evenly. */
        final List<Map<String, Double>> winners = new ArrayList<>();

        /**
         * Per checkpoint and delay index: the best a cluster reaches using only that
         * exact delay, and using every delay up to and including it. The prefix form
         * has to be accumulated per cluster before averaging — a prefix maximum of
         * means is not the mean of prefix maxima.
         */
        double[][] bestAtDelaySum;
        double[][] prefixBestSum;
        double[][] prefixBestSumSq;
        double[][] prefixExcessSum;

        int[] delayLadder;
        Map<Integer, Integer> delayIndex;
        String[][][] variantNames;

        Accumulator() {
            for (int c = 0; c < CHECKPOINTS; c++) {
                controlHist.add(new TreeMap<>());
                winners.add(new HashMap<>());
            }
        }

        void readFile(Path file, BufferedWriter clusterBest, BufferedWriter trajectories)
                throws IOException {
            int[] offsets = new int[64];

            long[] control = null;
            // Per cluster, indexed [variation][checkpoint].
            long[][] scores = null;
            int[] varDirection = null, varDuration = null, varDelay = null, varOnset = null;
            String[] varName = null;
            int inCluster = 0;
            int currentPsyboid = -1;
            long seed = -1;

            try (BufferedReader r = Files.newBufferedReader(file)) {
                r.readLine();   // header
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.isEmpty()) continue;
                    int fields = split(line, offsets);
                    if (fields < FIRST_SCORE_FIELD + CHECKPOINTS) continue;

                    seed = parseLong(line, offsets, 0);

                    // The control has empty override columns and comes first in a file.
                    if (offsets[3] - offsets[2] == 1) {
                        control = readScores(line, offsets);
                        recordControl(control);
                        continue;
                    }

                    int psyboid = parseInt(line, offsets, 2);
                    int direction = parseInt(line, offsets, 3);
                    int duration = parseInt(line, offsets, 4);
                    int onset = parseInt(line, offsets, 5);
                    int delay = parseInt(line, offsets, 6);

                    if (delayLadder == null) {
                        splitTick = parseInt(line, offsets, 8);
                        discoverLadder(file);
                    }
                    if (scores == null) {
                        int size = DIRECTIONS * DURATIONS * delayLadder.length;
                        scores = new long[size][];
                        varDirection = new int[size];
                        varDuration = new int[size];
                        varDelay = new int[size];
                        varOnset = new int[size];
                        varName = new String[size];
                    }

                    if (psyboid != currentPsyboid) {
                        if (currentPsyboid >= 0) {
                            finishCluster(seed, currentPsyboid, control, scores, inCluster,
                                    varDirection, varDuration, varDelay, varOnset, varName,
                                    clusterBest, trajectories);
                        }
                        currentPsyboid = psyboid;
                        inCluster = 0;
                    }

                    scores[inCluster] = readScores(line, offsets);
                    varDirection[inCluster] = direction;
                    varDuration[inCluster] = duration;
                    varDelay[inCluster] = delay;
                    varOnset[inCluster] = onset;
                    varName[inCluster] = variantName(direction, duration, delay, onset);
                    inCluster++;
                }
            }
            if (currentPsyboid >= 0) {
                finishCluster(seed, currentPsyboid, control, scores, inCluster,
                        varDirection, varDuration, varDelay, varOnset, varName,
                        clusterBest, trajectories);
            }
        }

        /**
         * Reads the delay ladder off one file. It is fixed by the sweep, but reading it
         * rather than assuming it keeps this working if the sweep's window changes.
         */
        private void discoverLadder(Path file) throws IOException {
            List<Integer> found = new ArrayList<>();
            int[] offsets = new int[64];
            try (BufferedReader r = Files.newBufferedReader(file)) {
                r.readLine();
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.isEmpty()) continue;
                    split(line, offsets);
                    if (offsets[3] - offsets[2] == 1) continue;
                    int delay = parseInt(line, offsets, 6);
                    if (found.contains(delay)) break;
                    found.add(delay);
                }
            }
            found.sort(Integer::compare);
            delayLadder = found.stream().mapToInt(Integer::intValue).toArray();
            delayIndex = new HashMap<>();
            for (int i = 0; i < delayLadder.length; i++) delayIndex.put(delayLadder[i], i);

            int steps = delayLadder.length;
            bestAtDelaySum = new double[CHECKPOINTS][steps];
            prefixBestSum = new double[CHECKPOINTS][steps];
            prefixBestSumSq = new double[CHECKPOINTS][steps];
            prefixExcessSum = new double[CHECKPOINTS][steps];

            variantNames = new String[DIRECTIONS][DURATIONS][steps];
            System.out.printf("delay ladder (%d): %s%n", steps, Arrays.toString(delayLadder));
        }

        /**
         * The part of a label from the direction letter onward. Built from the numeric
         * columns rather than sliced out of the label, which avoids allocating a string
         * per row — there are only a few hundred distinct names, so they are cached.
         */
        private String variantName(int direction, int duration, int delay, int onset) {
            int d = direction + 1;
            int u = duration - MIN_DURATION;
            int k = delayIndex.get(delay);
            String cached = variantNames[d][u][k];
            if (cached == null) {
                cached = TURN[d] + "d" + duration + "t" + onset;
                variantNames[d][u][k] = cached;
            }
            return cached;
        }

        private void recordControl(long[] scores) {
            controls++;
            for (int c = 0; c < CHECKPOINTS; c++) {
                controlSum[c] += scores[c];
                controlSumSq[c] += (double) scores[c] * scores[c];
                controlHist.get(c).merge((int) (scores[c] / BUCKET) * BUCKET, 1, Integer::sum);
            }
        }

        private void finishCluster(long seed, int psyboid, long[] control, long[][] scores,
                                   int count, int[] dirs, int[] durs, int[] delays,
                                   int[] onsets, String[] names, BufferedWriter clusterBest,
                                   BufferedWriter trajectories) throws IOException {
            clusters++;
            int steps = delayLadder.length;

            long[] best = new long[CHECKPOINTS];
            Arrays.fill(best, Long.MIN_VALUE);
            long[][] bestAtDelay = new long[CHECKPOINTS][steps];
            for (long[] row : bestAtDelay) Arrays.fill(row, Long.MIN_VALUE);

            List<List<String>> tied = new ArrayList<>();
            for (int c = 0; c < CHECKPOINTS; c++) tied.add(new ArrayList<>());

            for (int v = 0; v < count; v++) {
                int k = delayIndex.get(delays[v]);
                for (int c = 0; c < CHECKPOINTS; c++) {
                    long s = scores[v][c];
                    if (s > bestAtDelay[c][k]) bestAtDelay[c][k] = s;
                    if (s > best[c]) {
                        best[c] = s;
                        tied.get(c).clear();
                        tied.get(c).add(names[v]);
                    } else if (s == best[c]) {
                        tied.get(c).add(names[v]);
                    }
                }
            }

            for (int c = 0; c < CHECKPOINTS; c++) {
                List<String> winnersAt = tied.get(c);
                double share = 1.0 / winnersAt.size();
                Map<String, Double> map = winners.get(c);
                for (String name : winnersAt) map.merge(name, share, Double::sum);

                long running = Long.MIN_VALUE;
                for (int k = 0; k < steps; k++) {
                    bestAtDelaySum[c][k] += bestAtDelay[c][k];
                    running = Math.max(running, bestAtDelay[c][k]);
                    prefixBestSum[c][k] += running;
                    prefixBestSumSq[c][k] += (double) running * running;
                    prefixExcessSum[c][k] += running - control[c];
                }
            }

            clusterBest.write(seed + "," + psyboid);
            for (long b : best) clusterBest.write("," + b);
            clusterBest.newLine();

            // How a chosen variation's advantage accumulated over time. Selecting on the
            // final checkpoint is what a searcher with full hindsight would do, but it
            // biases the level upward — the winner is partly lucky. Selecting on an
            // earlier checkpoint makes everything after it out of sample, which is both
            // unbiased and closer to what a psyboid searching a finite horizon can
            // actually see.
            for (int selection : SELECTION_CHECKPOINTS) {
                int pick = 0;
                for (int v = 1; v < count; v++) {
                    if (scores[v][selection] > scores[pick][selection]) pick = v;
                }
                trajectories.write((selection + 1) * CHECKPOINT_TICKS + "," + seed + ","
                        + psyboid + "," + names[pick] + "," + dirs[pick] + ","
                        + durs[pick] + "," + delays[pick] + "," + onsets[pick]);
                for (long s : scores[pick]) trajectories.write("," + s);
                for (long s : control) trajectories.write("," + s);
                trajectories.newLine();
            }
        }

        // ---- outputs -------------------------------------------------------

        void writeControlStats(Path out) throws IOException {
            try (BufferedWriter w = writer(out)) {
                // Cumulative score is the primary metric; the per-interval columns are
                // derived from it and carried alongside because the interval rate is
                // what shows whether the warm-up left any drift.
                w.write("tick,n,mean,sd,mean_per_tick,interval_mean,interval_per_tick");
                w.newLine();
                double previous = 0;
                for (int c = 0; c < CHECKPOINTS; c++) {
                    int tick = (c + 1) * CHECKPOINT_TICKS;
                    double mean = controlSum[c] / controls;
                    double sd = Math.sqrt(Math.max(0, controlSumSq[c] / controls - mean * mean)
                            * controls / (controls - 1.0));
                    double interval = mean - previous;
                    previous = mean;
                    w.write(String.format("%d,%d,%.4f,%.4f,%.6f,%.4f,%.6f",
                            tick, controls, mean, sd, mean / tick,
                            interval, interval / CHECKPOINT_TICKS));
                    w.newLine();
                }
            }
        }

        void writeControlHistogram(Path out) throws IOException {
            try (BufferedWriter w = writer(out)) {
                w.write("tick,bucket_low,bucket_high,count");
                w.newLine();
                for (int c = 0; c < CHECKPOINTS; c++) {
                    int tick = (c + 1) * CHECKPOINT_TICKS;
                    for (Map.Entry<Integer, Integer> e : controlHist.get(c).entrySet()) {
                        w.write(tick + "," + e.getKey() + "," + (e.getKey() + BUCKET - 1)
                                + "," + e.getValue());
                        w.newLine();
                    }
                }
            }
        }

        void writeWinners(Path out) throws IOException {
            try (BufferedWriter w = writer(out)) {
                w.write("tick,variant,direction,duration,delay,wins");
                w.newLine();
                for (int c = 0; c < CHECKPOINTS; c++) {
                    int tick = (c + 1) * CHECKPOINT_TICKS;
                    Map<String, Double> map = new TreeMap<>(winners.get(c));
                    for (Map.Entry<String, Double> e : map.entrySet()) {
                        Variant v = Variant.parse(e.getKey());
                        w.write(String.format("%d,%s,%c,%d,%d,%.4f",
                                tick, e.getKey(), v.turn, v.duration, v.delay, e.getValue()));
                        w.newLine();
                    }
                }
            }
        }

        /**
         * Two views of the winner counts, each flattening one axis and running a
         * cumulative total along the other, so a threshold question reads straight off
         * a row.
         */
        void writeWinnersCollapsed(Path outDir) throws IOException {
            int steps = delayLadder.length;

            try (BufferedWriter w = writer(outDir.resolve("winners_by_duration_cumulative.csv"))) {
                w.write("tick,direction,duration_max,wins_cumulative");
                w.newLine();
                for (int c = 0; c < CHECKPOINTS; c++) {
                    int tick = (c + 1) * CHECKPOINT_TICKS;
                    double[][] byDur = new double[DIRECTIONS][DURATIONS];
                    for (Map.Entry<String, Double> e : winners.get(c).entrySet()) {
                        Variant v = Variant.parse(e.getKey());
                        byDur[v.dirIndex][v.duration - MIN_DURATION] += e.getValue();
                    }
                    for (int d = 0; d < DIRECTIONS; d++) {
                        double run = 0;
                        for (int u = 0; u < DURATIONS; u++) {
                            run += byDur[d][u];
                            w.write(String.format("%d,%c,%d,%.4f",
                                    tick, TURN[d], MIN_DURATION + u, run));
                            w.newLine();
                        }
                    }
                }
            }

            try (BufferedWriter w = writer(outDir.resolve("winners_by_delay_cumulative.csv"))) {
                w.write("tick,direction,delay_max,wins_cumulative");
                w.newLine();
                for (int c = 0; c < CHECKPOINTS; c++) {
                    int tick = (c + 1) * CHECKPOINT_TICKS;
                    double[][] byDelay = new double[DIRECTIONS][steps];
                    for (Map.Entry<String, Double> e : winners.get(c).entrySet()) {
                        Variant v = Variant.parse(e.getKey());
                        byDelay[v.dirIndex][delayIndex.get(v.delay)] += e.getValue();
                    }
                    for (int d = 0; d < DIRECTIONS; d++) {
                        double run = 0;
                        for (int k = 0; k < steps; k++) {
                            run += byDelay[d][k];
                            w.write(String.format("%d,%c,%d,%.4f",
                                    tick, TURN[d], delayLadder[k], run));
                            w.newLine();
                        }
                    }
                }
            }
        }

        void writeDelayWindow(Path out) throws IOException {
            try (BufferedWriter w = writer(out)) {
                w.write("tick,delay_index,delay_max,mean_best_at_this_delay,"
                        + "mean_best_within_window,sd_best_within_window,mean_excess_over_control");
                w.newLine();
                for (int c = 0; c < CHECKPOINTS; c++) {
                    int tick = (c + 1) * CHECKPOINT_TICKS;
                    for (int k = 0; k < delayLadder.length; k++) {
                        double mean = prefixBestSum[c][k] / clusters;
                        double sd = Math.sqrt(Math.max(0,
                                prefixBestSumSq[c][k] / clusters - mean * mean)
                                * clusters / (clusters - 1.0));
                        w.write(String.format("%d,%d,%d,%.4f,%.4f,%.4f,%.4f",
                                tick, k, delayLadder[k],
                                bestAtDelaySum[c][k] / clusters, mean, sd,
                                prefixExcessSum[c][k] / clusters));
                        w.newLine();
                    }
                }
            }
        }
    }

    // ---- helpers -----------------------------------------------------------

    /** The tick every sweep splits at, read from the data rather than assumed. */
    private static int splitTick = 0;

    private record Variant(char turn, int dirIndex, int duration, int delay) {
        static Variant parse(String name) {
            char turn = name.charAt(0);
            int dirIndex = turn == 'L' ? 0 : turn == 'S' ? 1 : 2;
            int d = name.indexOf('d');
            int t = name.indexOf('t', d);
            int duration = Integer.parseInt(name, d + 1, t, 10);
            int onset = Integer.parseInt(name, t + 1, name.length(), 10);
            return new Variant(turn, dirIndex, duration, onset - splitTick);
        }
    }

    private static BufferedWriter writer(Path out) throws IOException {
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        return Files.newBufferedWriter(out);
    }

    private static void header(BufferedWriter w, String prefix, String... series)
            throws IOException {
        w.write(prefix);
        for (String s : series) {
            for (int c = 1; c <= CHECKPOINTS; c++) w.write("," + s + "_t" + c * CHECKPOINT_TICKS);
        }
        w.newLine();
    }

    /** Fills {@code offsets} with the start of each field; returns the field count. */
    private static int split(String line, int[] offsets) {
        int n = 0;
        offsets[n++] = 0;
        for (int i = 0, len = line.length(); i < len; i++) {
            if (line.charAt(i) == ',') offsets[n++] = i + 1;
        }
        offsets[n] = line.length() + 1;
        return n;
    }

    private static long[] readScores(String line, int[] offsets) {
        long[] out = new long[CHECKPOINTS];
        for (int c = 0; c < CHECKPOINTS; c++) out[c] = parseLong(line, offsets, FIRST_SCORE_FIELD + c);
        return out;
    }

    private static int parseInt(String line, int[] offsets, int field) {
        return (int) parseLong(line, offsets, field);
    }

    private static long parseLong(String line, int[] offsets, int field) {
        int from = offsets[field];
        int to = offsets[field + 1] - 1;
        boolean negative = from < to && line.charAt(from) == '-';
        if (negative) from++;
        long value = 0;
        for (int i = from; i < to; i++) value = value * 10 + (line.charAt(i) - '0');
        return negative ? -value : value;
    }
}
