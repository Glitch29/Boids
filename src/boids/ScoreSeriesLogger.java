package boids;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Records every boid's running score at a fixed interval, and writes it as a table with
 * one row per sample and one column per boid.
 * <p>
 * Scores are cumulative, so the score over any window is the difference between two
 * rows.
 */
public final class ScoreSeriesLogger implements SimObserver {

    public static final Trigger[] TRIGGERS = {
            Trigger.STATE_INIT, Trigger.STATE_ADVANCE, Trigger.SIM_END
    };

    private final Path out;
    private final int interval;

    private final List<long[]> samples = new ArrayList<>();
    private long firstTick = Long.MIN_VALUE;

    /** @param interval ticks between samples; a "second" is {@code Params.TURNS / 8} */
    public ScoreSeriesLogger(Path out, int interval) {
        this.out = out;
        this.interval = interval;
    }

    @java.lang.Override
    public void observe(Trigger trigger, Observation observation) {
        switch (trigger) {
            case STATE_INIT, STATE_ADVANCE -> {
                Observation.Scores scores = observation.get(Observation.SCORES);
                if (firstTick == Long.MIN_VALUE) firstTick = scores.tick();
                if ((scores.tick() - firstTick) % interval != 0) return;

                long[] row = new long[scores.perBoid().length + 1];
                row[0] = scores.tick() - firstTick;
                System.arraycopy(scores.perBoid(), 0, row, 1, scores.perBoid().length);
                samples.add(row);
            }
            case SIM_END -> flush();
            default -> { }
        }
    }

    private void flush() {
        if (samples.isEmpty()) return;
        int boids = samples.get(0).length - 1;

        try {
            if (out.getParent() != null) Files.createDirectories(out.getParent());
            try (BufferedWriter w = Files.newBufferedWriter(out)) {
                w.write("elapsed");
                for (int i = 0; i < boids; i++) w.write(",boid" + i);
                w.write(",total");
                w.newLine();

                for (long[] row : samples) {
                    w.write(Long.toString(row[0]));
                    long total = 0;
                    for (int i = 1; i <= boids; i++) {
                        w.write("," + row[i]);
                        total += row[i];
                    }
                    w.write("," + total);
                    w.newLine();
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
