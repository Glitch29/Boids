package boids;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Keeps a computed critical-envelope table on disk, because computing one costs minutes.
 * <p>
 * Content-addressed on everything the answer depends on rather than on the arguments that
 * produced it — the map's dimensions and step vectors, the live set, the edge each live state
 * was given, the arc, both flocking models and whether the out-of-range approximation was on.
 * Two callers that reach the same table by different routes therefore share a file, and a
 * decomposition that shifts by one state gets a different name instead of silently reusing the
 * old answer. The same rule the map ingests and the clock follow, for the same reason: a stale
 * table is not visibly stale, it is just wrong about which leaders account for what.
 * <p>
 * <b>Why this exists at all.</b> {@link ExitAudit} is meant to be cheap and called often, from
 * many threads. Rebuilding these tables inside its constructor made every consumer pay minutes
 * and made sharing impossible. Building is now a separate, deliberate step whose result is
 * immutable and shareable; see {@link ExitAudit.Tables}.
 */
public final class CriticalEnvelopeStore {
    private CriticalEnvelopeStore() {}

    /**
     * Bumped whenever the stored layout changes <b>or the meaning of what is stored changes</b>.
     * <p>
     * The key is built from the inputs, so improving the analysis leaves every key exactly where
     * it was and the store hands back answers from the old code without a word. Anything that
     * would make the same inputs produce a different table has to be reflected here by hand.
     * <p>
     * 1: first version — envelope from the unsteered-predecessor closure, downstream part over
     * every one-tick crossing, admission with the two-model boid.
     */
    private static final int FORMAT = 2;

    /** The table for this arc, from store if it is there and computed and stored if it is not. */
    public static CriticalEnvelope.Table of(Path dir, NavMap map, int[] edge, int[] live,
                                            int liveCount, int from, int keep, Flocking f,
                                            Flocking alt) {
        return of(dir, map, edge, live, liveCount, from, keep, f, alt, null);
    }

    /**
     * The same, naming the ground a history has to reach.
     * <p>
     * The ground goes into the cache key, because a table built against a wider ground is a
     * different table answering a different question, and two of them under one name is exactly
     * the failure the FORMAT rule exists to prevent.
     *
     * @param ground states of {@code from} that terminate a history, or null for the settled set
     */
    public static CriticalEnvelope.Table of(Path dir, NavMap map, int[] edge, int[] live,
                                            int liveCount, int from, int keep, Flocking f,
                                            Flocking alt, boolean[] ground) {
        String key = key(map, edge, live, liveCount, from, keep, f, alt, ground);
        Path file = dir.resolve("envelope-" + key + ".bin");
        if (Files.exists(file)) {
            try (DataInputStream in = new DataInputStream(
                    new BufferedInputStream(Files.newInputStream(file)))) {
                return read(in);
            } catch (IOException e) {
                // A file that will not read is a file worth replacing, not a reason to stop.
                System.out.printf("  (stored envelope %s unreadable: %s; recomputing)%n",
                        file.getFileName(), e.getMessage());
            }
        }
        CriticalEnvelope.Table t = CriticalEnvelope.analyse(map, edge, live, liveCount, from,
                keep, f, alt, ground);
        if (t.budgetHit()) {
            // An incomplete table would be indistinguishable from a complete one once written,
            // and every later run would inherit the gap silently.
            throw new IllegalStateException("admission hit its budget on arc " + from + "->"
                    + keep + "; refusing to store a table that is missing entries");
        }
        try {
            write(file, t);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot store envelope at " + file, e);
        }
        return t;
    }

    /**
     * A name for everything the table is a function of.
     * <p>
     * The map goes in as its dimensions and its step table rather than its pixels: those are what
     * the successor relation is built from, so two maps agreeing on them and on the live set have
     * the same graph whatever their images look like.
     */
    private static String key(NavMap map, int[] edge, int[] live, int liveCount, int from,
                              int keep, Flocking f, Flocking alt, boolean[] ground) {
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
        feed.accept(from);
        feed.accept(keep);
        feed.accept(liveCount);
        // The approximation is part of the answer, not part of how it was reached.
        feed.accept(CriticalEnvelope.pruneOutOfRangeLeaders ? 1 : 0);
        flocking(feed, f);
        flocking(feed, alt);
        for (int i = 0; i < liveCount; i++) { feed.accept(live[i]); feed.accept(edge[live[i]]); }
        // The ground is part of what the table means, so it is part of its name.
        if (ground == null) feed.accept(-1);
        else for (int s = 0; s < ground.length; s++) if (ground[s]) feed.accept(s);

        byte[] sum = digest.digest();
        StringBuilder out = new StringBuilder(16);
        for (int i = 0; i < 8; i++) out.append(String.format("%02x", sum[i]));
        return out.toString();
    }

    private static void flocking(java.util.function.IntConsumer feed, Flocking f) {
        for (double v : new double[]{f.wSep(), f.wCoh(), f.wAli(), f.straightBias(), f.rSep(),
                f.rFlock()}) {
            long bits = Double.doubleToLongBits(v);
            feed.accept((int) (bits >>> 32));
            feed.accept((int) bits);
        }
    }

    private static void write(Path file, CriticalEnvelope.Table t) throws IOException {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".partial");
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(tmp)))) {
            out.writeInt(FORMAT);
            CriticalEnvelope.Envelope e = t.envelope();
            out.writeInt(e.from());
            out.writeInt(e.keep());
            ints(out, e.onFrom());
            ints(out, e.onKeep());
            ints(out, e.entered());
            out.writeInt(t.settled());
            out.writeLong(t.probed());
            out.writeInt(t.entries().size());
            for (CriticalEnvelope.Entry entry : t.entries()) {
                out.writeInt(entry.boidPrior());
                out.writeInt(entry.leaderPrior());
                out.writeInt(entry.boidAt());
                out.writeInt(entry.turn());
                out.writeInt(entry.cause().ordinal());
                out.writeBoolean(entry.diluted());
                ints(out, entry.leaderPath());
            }
        }
        // Renamed into place so a run killed mid-write leaves no file that looks complete.
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    private static CriticalEnvelope.Table read(DataInputStream in) throws IOException {
        if (in.readInt() != FORMAT) throw new IOException("wrong format");
        int from = in.readInt(), keep = in.readInt();
        CriticalEnvelope.Envelope env = new CriticalEnvelope.Envelope(from, keep, ints(in),
                ints(in), ints(in));
        int settled = in.readInt();
        long probed = in.readLong();
        int n = in.readInt();
        List<CriticalEnvelope.Entry> entries = new ArrayList<>(n);
        CriticalEnvelope.Cause[] causes = CriticalEnvelope.Cause.values();
        // Read into locals rather than inlining into the constructor. The fields are all ints
        // in a row and argument evaluation order is easy to get wrong silently.
        for (int i = 0; i < n; i++) {
            int boidPrior = in.readInt();
            int leaderPrior = in.readInt();
            int boidAt = in.readInt();
            int turn = in.readInt();
            CriticalEnvelope.Cause cause = causes[in.readInt()];
            boolean diluted = in.readBoolean();
            entries.add(new CriticalEnvelope.Entry(boidPrior, leaderPrior, boidAt, turn, cause,
                    ints(in), diluted));
        }
        // budgetHit is not stored: a table that hit its budget is never written.
        return new CriticalEnvelope.Table(env, entries, settled, probed, false);
    }

    private static void ints(DataOutputStream out, int[] values) throws IOException {
        out.writeInt(values.length);
        for (int v : values) out.writeInt(v);
    }

    private static int[] ints(DataInputStream in) throws IOException {
        int[] values = new int[in.readInt()];
        for (int i = 0; i < values.length; i++) values[i] = in.readInt();
        return values;
    }
}
