package boids;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Keeps a computed clock on disk, because computing one costs thousands of gradient steps.
 * <p>
 * Content-addressed on everything the answer depends on rather than on the arguments that
 * produced it — the map's dimensions and step vectors, the live set, the edge each live state
 * was given, the weighting scheme and whether paths were pinned. Two callers that reach the
 * same decomposition by different routes therefore share a file, and a decomposition that
 * shifts by one state gets a different name instead of silently reusing the old answer. That
 * is the same rule the map ingests follow, and for the same reason: a stale clock is not
 * visibly stale, it is just wrong by a few ticks in places nobody is looking.
 * <p>
 * The stored tick array is the live states only. It is handed out as a full state-indexed
 * array with NaN elsewhere, matching what a solve returns, but writing all of it would be
 * sixty-odd megabytes of mostly NaN on a map whose live states are a tenth of that.
 */
public final class EdgeMetricStore {
    private EdgeMetricStore() {}

    /**
     * Bumped whenever the stored layout changes <b>or the way a clock is computed changes</b>,
     * so an old file is a miss rather than a misread.
     * <p>
     * The second half of that is the one worth stating, because it is not automatic and it has
     * already bitten once. The key is built from the inputs, so improving the solver leaves
     * every key exactly where it was and the store hands back answers from the old code
     * without a word. Anything that would make the same inputs produce a different clock has
     * to be reflected here by hand.
     * <p>
     * 1: original. 2: lifted weighting prunes nodes no traffic can circulate through.
     * 3: edge lengths kept as fitted reals instead of being rounded to whole ticks.
     */
    private static final int FORMAT = 3;

    public static EdgeMetric.Metric of(Path dir, NavMap map, int[] edge, int[] live,
                                       int liveCount, int edges) {
        return of(dir, map, edge, live, liveCount, edges, false, EdgeWeights.Scheme.UNIFORM,
                null);
    }

    public static EdgeMetric.Metric of(Path dir, NavMap map, int[] edge, int[] live,
                                       int liveCount, int edges, EdgeWeights.Scheme scheme) {
        return of(dir, map, edge, live, liveCount, edges, false, scheme, null);
    }

    public static EdgeMetric.Metric of(Path dir, NavMap map, int[] edge, int[] live,
                                       int liveCount, int edges, EdgeWeights.Scheme scheme,
                                       double[][] chain) {
        return of(dir, map, edge, live, liveCount, edges, false, scheme, chain);
    }

    /**
     * The clock for this decomposition, from store if it is there and computed and stored
     * if it is not.
     *
     * @param dir where clocks for this map live; normally the map's own ingest folder, so a
     *            clock cannot outlive the map it measures
     */
    public static EdgeMetric.Metric of(Path dir, NavMap map, int[] edge, int[] live,
                                       int liveCount, int edges, boolean pinPaths,
                                       EdgeWeights.Scheme scheme, double[][] chain) {
        String key = key(map, edge, live, liveCount, edges, pinPaths, scheme, chain);
        Path file = dir.resolve("metric-" + key + ".bin");
        if (Files.exists(file)) {
            try (DataInputStream in = new DataInputStream(
                    new BufferedInputStream(Files.newInputStream(file)))) {
                EdgeMetric.Metric m = read(in, edge.length, liveCount, live);
                EdgeMetric.restored("loaded " + file.getFileName(), in.readDouble());
                return m;
            } catch (IOException e) {
                // A file that will not read is a file worth replacing, not a reason to stop.
                System.out.printf("  (stored metric %s unreadable: %s; recomputing)%n",
                        file.getFileName(), e.getMessage());
            }
        }
        EdgeMetric.Metric m = EdgeMetric.compute(map, edge, live, liveCount, edges, pinPaths,
                scheme, chain);
        try {
            write(file, m, live, liveCount);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot store metric at " + file, e);
        }
        return m;
    }

    /**
     * A name for everything the clock is a function of.
     * <p>
     * The map goes in as its dimensions and its step table rather than its pixels: those are
     * what the successor relation is built from, so two maps agreeing on them and on the live
     * set have the same graph whatever their images look like.
     */
    private static String key(NavMap map, int[] edge, int[] live, int liveCount, int edges,
                              boolean pinPaths, EdgeWeights.Scheme scheme, double[][] chain) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
        byte[] word = new byte[4];
        java.util.function.IntConsumer feed = v -> {
            word[0] = (byte) (v >>> 24); word[1] = (byte) (v >>> 16);
            word[2] = (byte) (v >>> 8);  word[3] = (byte) v;
            digest.update(word);
        };
        feed.accept(FORMAT);
        feed.accept(map.width());
        feed.accept(map.height());
        for (int h = 0; h < Params.TURNS; h++) { feed.accept(map.stepX(h)); feed.accept(map.stepY(h)); }
        feed.accept(edges);
        feed.accept(liveCount);
        feed.accept(pinPaths ? 1 : 0);
        for (byte b : scheme.name().getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            digest.update(b);
        }
        // Two clocks that differ only in the chain they were weighted by are different clocks,
        // and the scheme name alone cannot tell them apart.
        if (chain != null) {
            for (double[] row : chain) {
                for (double v : row) {
                    long bits = Double.doubleToLongBits(v);
                    feed.accept((int) (bits >>> 32));
                    feed.accept((int) bits);
                }
            }
        }
        for (int i = 0; i < liveCount; i++) { feed.accept(live[i]); feed.accept(edge[live[i]]); }

        byte[] sum = digest.digest();
        StringBuilder out = new StringBuilder(16);
        for (int i = 0; i < 8; i++) out.append(String.format("%02x", sum[i]));
        return out.toString();
    }

    private static void write(Path file, EdgeMetric.Metric m, int[] live, int liveCount)
            throws IOException {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".partial");
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(tmp)))) {
            out.writeInt(FORMAT);
            int edges = m.length().length;
            out.writeInt(edges);
            out.writeInt(liveCount);
            for (int e = 0; e < edges; e++) out.writeDouble(m.length()[e]);
            for (int e = 0; e < edges; e++) for (int f = 0; f < edges; f++) out.writeInt(m.entryFrom()[e][f]);
            for (int e = 0; e < edges; e++) for (int f = 0; f < edges; f++) out.writeInt(m.exitTo()[e][f]);
            for (int i = 0; i < liveCount; i++) out.writeDouble(m.tick()[live[i]]);
            out.writeInt(m.slack());
            out.writeInt(m.broken());
            out.writeInt(m.band());
            out.writeDouble(m.step());
            out.writeDouble(m.cost());
            // Not part of the metric, but the one diagnostic a caller reads straight after
            // computing one. Kept so a loaded clock reports its own health and not the health
            // of whatever was solved before it.
            out.writeDouble(EdgeMetric.lastWorstBand());
        }
        // Renamed into place so a run killed mid-write leaves no file that looks complete.
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static EdgeMetric.Metric read(DataInputStream in, int states, int liveCount,
                                          int[] live) throws IOException {
        if (in.readInt() != FORMAT) throw new IOException("wrong format");
        int edges = in.readInt();
        if (in.readInt() != liveCount) throw new IOException("live count moved");
        double[] length = new double[edges];
        for (int e = 0; e < edges; e++) length[e] = in.readDouble();
        int[][] entryFrom = new int[edges][edges], exitTo = new int[edges][edges];
        for (int e = 0; e < edges; e++) for (int f = 0; f < edges; f++) entryFrom[e][f] = in.readInt();
        for (int e = 0; e < edges; e++) for (int f = 0; f < edges; f++) exitTo[e][f] = in.readInt();
        double[] tick = new double[states];
        Arrays.fill(tick, Double.NaN);
        for (int i = 0; i < liveCount; i++) tick[live[i]] = in.readDouble();
        return new EdgeMetric.Metric(entryFrom, exitTo, length, tick,
                in.readInt(), in.readInt(), in.readInt(), in.readDouble(), in.readDouble());
    }
}
