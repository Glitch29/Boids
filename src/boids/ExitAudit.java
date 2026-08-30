package boids;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Watches a run, notes exactly when each exit happens, and proposes the mechanisms that could
 * account for it.
 * <p>
 * The omniscient counterpart to the solver: it sees the whole arrangement on every tick, where
 * a solver sees one photograph. That makes it the ground truth everything else is measured
 * against, and it is why it must not consult anything the solver's windows were derived from —
 * an earlier version read {@link SolverFacts#windowsInto}, which made the comparison between
 * audit and windows a comparison of the windows with themselves.
 * <p>
 * <b>What it can promise, and what it cannot.</b> Two things, and both are properties measured
 * over a corpus rather than guarantees this class makes about itself:
 * <ol>
 *   <li>no exit in the corpus was left without a reason;</li>
 *   <li>the reasons are ordered from most to least reliable.</li>
 * </ol>
 * It will not invent a reason to avoid reporting none. Where several mechanisms are genuinely
 * available every one of them is reported, because a consumer may specifically need to know
 * that a psyboid took an exit it did not need an override to take.
 * <p>
 * <b>Reported at the crossing, attributed at the entry.</b> An exit is anchored to the single
 * tick on which the boid leaves the edge, having started that tick on it — which the edges were
 * designed to make the same tick the choice of destination is locked in. The influence
 * responsible generally acted earlier, at the moment the boid was steered onto the critical
 * envelope, so that is where the leader question is asked. Only the last entry before the
 * crossing is used; re-entering would take a complete reversal of steering and is not something
 * ordinary flocking produces.
 * <p>
 * <b>No geometry.</b> Every judgement here is a lookup into {@link CriticalEnvelope}: envelope
 * membership to spot the entry, and a paired-state table to name the leaders. The one thing
 * computed locally is which boid was under an override, which is bookkeeping rather than
 * physics. Keep it that way unless the critical-envelope analysis has been exhausted and exits
 * are still escaping.
 */
public final class ExitAudit implements Boids2DEngine.Trace {

    /** How much a reason is worth, best first. */
    public enum Level {
        /** The boid was under an override. Exits are assumed intentional. */
        PSYBOID,
        /** A leader the critical-envelope analysis admits under the true constants. */
        ENVELOPE,
        /** A leader admitted only once the crowd-diluted model is allowed. */
        ENVELOPE_WIDENED
    }

    /**
     * One mechanism that could account for an exit.
     *
     * @param leader     which boid, or -1 when the suspect accounts for itself
     * @param cause      why the leader turned it, absent for {@link Level#PSYBOID}
     * @param leaderPath the edges that leader travelled setting the turn up
     */
    public record Reason(Level level, int leader, CriticalEnvelope.Cause cause,
                         int[] leaderPath) {}

    /**
     * One boid leaving one edge for another, with everything known about why.
     *
     * @param tick        when the boid crossed
     * @param entryTick   when it was steered onto the envelope, or -1 if it never was
     * @param entryPrior  where it stood on the tick it was steered, before it moved
     * @param seen        how many other boids were in its flocking vision at that moment
     */
    /**
     * @param entryStates every boid's state at the moment of envelope entry, the suspect's own
     *                    included, or empty if it never entered. Carried because an exit nothing
     *                    accounts for is a claim about an <em>arrangement</em>, and a count of
     *                    such exits cannot be argued with while a picture of one can
     */
    public record Exit(long tick, int suspect, int fromEdge, int toEdge, long entryTick,
                       int entryPrior, int seen, boolean crossedFromInside, int crossedFrom,
                       int[] entryStates, List<Reason> reasons) {

        public boolean unexplained() { return reasons.isEmpty(); }

        /** Whether the best account available needed the diluted model. */
        public boolean neededWidening() {
            return !reasons.isEmpty() && reasons.get(0).level() == Level.ENVELOPE_WIDENED;
        }

        public Level best() { return reasons.isEmpty() ? null : reasons.get(0).level(); }
    }

    /**
     * One arc's precomputed table, held exactly as it is read.
     * <p>
     * One table rather than two. The boid is allowed either physics at every step, so a single
     * pass produces both kinds of account and tags each entry with which model carried it —
     * there is nothing a second full build would add.
     */
    private record Arc(int from, int keep, boolean[] inEnvelope,
                       Map<Long, CriticalEnvelope.Entry> table) {}

    /**
     * The precomputed part, built once and shared by every audit that reads it.
     * <p>
     * <b>Immutable, and meant to be.</b> An audit is cheap and gets constructed a great many
     * times, from many threads; the tables behind it cost minutes and are identical for all of
     * them. Keeping them in a separate object means one build serves every consumer, and it is
     * why nothing here is ever written to after construction — the arrays and maps are populated
     * before publication and read-only afterwards, so concurrent readers need no locking.
     * <p>
     * One {@link ExitAudit} per thread, one {@code Tables} for the process.
     */
    public static final class Tables {
        private final NavMap map;
        private final int[] edge;
        private final double rFlock2;
        private final List<Arc> arcs;

        private Tables(NavMap map, int[] edge, double rFlock2, List<Arc> arcs) {
            this.map = map;
            this.edge = edge;
            this.rFlock2 = rFlock2;
            this.arcs = List.copyOf(arcs);
        }

        /**
         * Loads each arc's table, computing and storing any that is missing.
         *
         * @param dir where tables for this map live; normally the map's own ingest folder, so a
         *            table cannot outlive the decomposition it describes
         * @param alt the second physics the exiting boid may choose on any tick, normally
         *            {@link Flocking#diluted()}. {@code null} runs under the true constants
         *            alone, which is what measuring the fallback's contribution needs
         */
        public static Tables of(Path dir, NavMap map, int[] edge, int[] live, int liveCount,
                                int[][] arcSpecs, Flocking normal, Flocking alt) {
            List<Arc> arcs = new ArrayList<>();
            for (int[] spec : arcSpecs) {
                // The same model twice makes the second choice a no-op, which is exactly what
                // "no fallback" means and needs no separate code path.
                CriticalEnvelope.Table t = CriticalEnvelopeStore.of(dir, map, edge, live,
                        liveCount, spec[0], spec[1], normal, alt == null ? normal : alt);
                boolean[] in = new boolean[edge.length];
                for (int s : t.envelope().onFrom()) in[s] = true;
                for (int s : t.envelope().onKeep()) in[s] = true;
                Map<Long, CriticalEnvelope.Entry> table = new HashMap<>();
                for (CriticalEnvelope.Entry e : t.entries()) {
                    table.putIfAbsent(key(e.boidPrior(), e.leaderPrior()), e);
                }
                arcs.add(new Arc(spec[0], spec[1], in,
                        java.util.Collections.unmodifiableMap(table)));
            }
            return new Tables(map, edge, normal.rFlock() * normal.rFlock(), arcs);
        }

        /** How many pairings are held, per arc, for reporting what a build produced. */
        public String summary() {
            StringBuilder s = new StringBuilder();
            for (Arc a : arcs) {
                s.append(String.format("  arc %d->%d: %,d envelope states, %,d pairings%n",
                        a.from(), a.keep(), count(a.inEnvelope()), a.table().size()));
            }
            return s.toString();
        }

        private static int count(boolean[] bits) {
            int n = 0;
            for (boolean b : bits) if (b) n++;
            return n;
        }
    }

    private final NavMap map;
    private final int[] edge;
    private PsyboidOverride[] flying;
    private final double rFlock2;
    private final List<Arc> arcs = new ArrayList<>();
    private final List<Exit> exits = new ArrayList<>();

    /** The last envelope entry per boid per arc; only the most recent one is ever used. */
    private long[][] entryTick;
    private int[][] entryPrior;
    private int[][][] entryOthers;      // [boid][arc][other] = that boid's state at entry
    private int[][] entrySeen;

    private long decisions, crossings;

    /**
     * An audit over already-built tables. Cheap: it allocates only its own per-run bookkeeping,
     * so one per thread costs nothing worth measuring and they all share the same {@code Tables}.
     */
    public ExitAudit(Tables tables, PsyboidOverride[] overrides) {
        this.map = tables.map;
        this.edge = tables.edge;
        this.rFlock2 = tables.rFlock2;
        this.arcs.addAll(tables.arcs);
        this.flying = overrides == null ? new PsyboidOverride[0] : overrides;
    }

    private static long key(int boid, int leader) {
        return ((long) boid << 32) | (leader & 0xFFFFFFFFL);
    }

    /**
     * Whether the tables hold this exact pairing, and whether they hold the boid's state at all.
     * <p>
     * For diagnosing an exit nothing explained. The two answers separate two very different
     * failures: a state absent from the table means the entry was never enumerated, while a
     * state present with the leader absent means the pairing was enumerated and then
     * <em>rejected</em> — by admission, which asks whether the two of them alone could have
     * reached that arrangement. A four-boid run reaches arrangements no two-boid history can.
     */
    public CriticalEnvelope.Entry lookup(int from, int keep, int boidPrior, int leaderPrior) {
        for (Arc a : arcs) {
            if (a.from() == from && a.keep() == keep) {
                return a.table().get(key(boidPrior, leaderPrior));
            }
        }
        return null;
    }

    /** Whether any leader at all accounts for a boid steered onto the envelope from here. */
    public boolean isEntryState(int from, int keep, int boidPrior) {
        for (Arc a : arcs) {
            if (a.from() != from || a.keep() != keep) continue;
            for (long k : a.table().keySet()) if ((int) (k >>> 32) == boidPrior) return true;
        }
        return false;
    }

    public List<Exit> exits() { return exits; }
    public long decisions() { return decisions; }
    public long crossings() { return crossings; }

    /**
     * Fires mid-tick, with boids {@code 0..i-1} already moved and the rest not.
     * <p>
     * That is the arrangement the suspect actually decided against, so it is the only one an
     * account of its decision may be built from. A tick has an interior, and reconstructing the
     * decision from the tick's start or end answers a different question.
     */
    @Override
    public void decided(long at, int i, BoidArray boids, int want) {
        decisions++;
        if (entryTick == null) reset(boids.n());

        int s = map.index(boids.x()[i], boids.y()[i], boids.h()[i]);
        int u = map.successor(s, want);
        if (u < 0) return;

        for (int a = 0; a < arcs.size(); a++) {
            Arc arc = arcs.get(a);
            if (edge[s] != arc.from()) continue;

            // Entry first, because a cost-to-leave of exactly one puts both on the same tick:
            // the boid steps off the envelope's outside straight onto the downstream edge.
            if (!arc.inEnvelope()[s] && arc.inEnvelope()[u]) {
                entryTick[i][a] = at;
                entryPrior[i][a] = s;
                entrySeen[i][a] = snapshot(boids, i, entryOthers[i][a]);
            }
            if (edge[u] == arc.keep()) {
                crossings++;
                exits.add(account(at, i, a, arc, s));
            }
        }
    }

    /** Every other boid's state as the suspect saw it, and how many were in flocking vision. */
    private int snapshot(BoidArray boids, int i, int[] into) {
        int seen = 0;
        for (int j = 0; j < boids.n(); j++) {
            if (j == i) { into[j] = -1; continue; }
            into[j] = map.index(boids.x()[j], boids.y()[j], boids.h()[j]);
            double dx = boids.x()[j] - boids.x()[i], dy = boids.y()[j] - boids.y()[i];
            if (dx * dx + dy * dy <= rFlock2) seen++;
        }
        return seen;
    }

    /**
     * The reasons an exit could have happened, best first.
     * <p>
     * The widened table is consulted only when the true constants explain nothing, so a
     * level-3 reason in the output always means level 2 had nothing to say.
     */
    private Exit account(long at, int i, int a, Arc arc, int s) {
        long when = entryTick[i][a];
        int prior = entryPrior[i][a];
        List<Reason> reasons = new ArrayList<>();

        if (when >= 0 && overriddenAt(i, when)) {
            reasons.add(new Reason(Level.PSYBOID, -1, null, new int[0]));
        }
        if (when >= 0) {
            int[] others = entryOthers[i][a];
            for (int j = 0; j < others.length; j++) {
                if (others[j] < 0) continue;
                CriticalEnvelope.Entry e = arc.table().get(key(prior, others[j]));
                if (e == null) continue;
                // Every available mechanism is reported, ranked. Suppressing the diluted account
                // when a plain one exists would hide that a leader accounts for the turn twice
                // over, which is a thing a consumer may want to know.
                reasons.add(new Reason(e.diluted() ? Level.ENVELOPE_WIDENED : Level.ENVELOPE,
                        j, e.cause(), e.leaderPath()));
            }
        }
        reasons.sort(Comparator.comparing(Reason::level));
        // Copied, because the entry record is overwritten by the next entry on this arc.
        int[] states = new int[0];
        if (when >= 0) {
            states = entryOthers[i][a].clone();
            states[i] = prior;
        }
        return new Exit(at, i, arc.from(), arc.keep(), when, prior,
                when < 0 ? 0 : entrySeen[i][a], arc.inEnvelope()[s], s, states, reasons);
    }

    /** Whether an override was steering this boid on that tick. */
    private boolean overriddenAt(int boid, long when) {
        for (PsyboidOverride o : flying) {
            if (o.psyboid() != boid) continue;
            if (when >= o.onset() && when < (long) o.onset() + o.duration()) return true;
        }
        return false;
    }

    /**
     * The same, installing the overrides this run flies.
     * <p>
     * A corpus is one plan per seed, and each plan steers a different boid for different
     * stretches, so the overrides belong to the run rather than to the audit. Attribution at
     * {@link Level#PSYBOID} reads them and nothing else does.
     */
    public void reset(int n, PsyboidOverride[] flying) {
        this.flying = flying == null ? new PsyboidOverride[0] : flying;
        reset(n);
    }

    /** Clears the per-boid entry record; call between runs so one seed cannot bleed into another. */
    public void reset(int n) {
        entryTick = new long[n][arcs.size()];
        entryPrior = new int[n][arcs.size()];
        entryOthers = new int[n][arcs.size()][n];
        entrySeen = new int[n][arcs.size()];
        for (long[] row : entryTick) java.util.Arrays.fill(row, -1);
        for (int[] row : entryPrior) java.util.Arrays.fill(row, -1);
    }

    // ---- reporting ----------------------------------------------------------

    public String summary() {
        int[] byLevel = new int[Level.values().length];
        int none = 0, noEntry = 0;
        Map<String, Integer> byArc = new java.util.TreeMap<>();
        for (Exit e : exits) {
            byArc.merge(e.fromEdge() + "->" + e.toEdge(), 1, Integer::sum);
            if (e.entryTick() < 0) noEntry++;
            if (e.unexplained()) none++; else byLevel[e.best().ordinal()]++;
        }
        StringBuilder s = new StringBuilder();
        s.append(String.format("%,d decisions, %,d exits%n", decisions, exits.size()));
        for (Map.Entry<String, Integer> a : byArc.entrySet()) {
            s.append(String.format("  arc %-6s %,8d%n", a.getKey(), a.getValue()));
        }
        for (Level l : Level.values()) {
            s.append(String.format("  best reason %-18s %,8d%n", l, byLevel[l.ordinal()]));
        }
        s.append(String.format("  UNEXPLAINED %18s %,8d%s%n", "", none,
                none == 0 ? "" : "   <-- the net has a hole"));
        s.append(String.format("  (of which no envelope entry was ever seen: %,d)%n", noEntry));
        return s.toString();
    }

    /** One row per exit, for reading a run rather than aggregating it. */
    public void write(Path file) throws IOException {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        String tab = "\t";
        StringBuilder s = new StringBuilder(String.join(tab, "tick", "suspect", "from", "to",
                "entryTick", "lag", "inside", "crossedFrom", "seen", "best", "leader", "cause",
                "path")).append(System.lineSeparator());
        for (Exit e : exits) {
            Reason r = e.reasons().isEmpty() ? null : e.reasons().get(0);
            s.append(String.join(tab,
                    String.valueOf(e.tick()), String.valueOf(e.suspect()),
                    String.valueOf(e.fromEdge()), String.valueOf(e.toEdge()),
                    String.valueOf(e.entryTick()),
                    String.valueOf(e.entryTick() < 0 ? -1 : e.tick() - e.entryTick()),
                    String.valueOf(e.crossedFromInside()), String.valueOf(e.crossedFrom()),
                    String.valueOf(e.seen()),
                    r == null ? "NONE" : r.level().toString(),
                    String.valueOf(r == null ? -1 : r.leader()),
                    r == null || r.cause() == null ? "-" : r.cause().toString(),
                    r == null ? "-" : java.util.Arrays.toString(r.leaderPath()).replace(" ", "")))
                    .append(System.lineSeparator());
        }
        Files.writeString(file, s.toString());
    }
}
