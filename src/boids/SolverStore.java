package boids;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Builds the facts a solver reads, and keeps them next to the map they describe.
 * <p>
 * Building is minutes of work — a decomposition, a clock, a leader search per steered arc —
 * and every step of it already exists as something invoked by hand from a driver. What is new
 * here is that the answers get written down in one place, in a form that needs no navmap to
 * read, so that showing a scene to a solver costs a file read rather than a rerun.
 * <p>
 * <b>This is the manual half.</b> {@link #build} is meant to be called deliberately, once per
 * map version, by someone who has chosen a gate and looked at the decomposition it produced.
 * {@link #load} is what a solve calls, and it never builds anything: a missing file is an
 * error rather than an invitation, because the alternative is a solver that quietly spends
 * four minutes doing map-level work in the middle of answering a question about a photograph.
 * <p>
 * One file per map version, at a fixed name inside the ingest. Not content-addressed like
 * {@link EdgeMetricStore}, because there is nothing for a reader to key on — the whole point
 * is that the solver arrives knowing only which map it is looking at. Rebuilding overwrites,
 * and the gate and the constants used are recorded inside so a file can say what it is.
 */
public final class SolverStore {
    private SolverStore() {}

    /**
     * Bumped whenever the layout changes <b>or the meaning of anything stored changes</b>.
     * <p>
     * The same rule {@link EdgeMetricStore#FORMAT} carries, and worth restating because the
     * failure it prevents is silent: these facts are the solver's entire model of the map, and
     * a stale one does not look stale, it just answers a slightly different map's questions.
     * <p>
     * 1: first version. 2: per-edge tick spans, so a band can be judged vacuous; window
     * opening anchored on the first tau with a band rather than the first with followers.
     * 3: per-edge-pair exit turns, so a crossing is classified by the pair rather than by what
     * the boid was steering as it crossed.
     */
    private static final int FORMAT = 3;

    private static final String FILE = "facts.bin";

    /** Facts for this map, as they were last built. Never builds; a miss is an error. */
    public static SolverFacts load(PresetScenarioParameter preset) {
        return load(preset.ingest());
    }

    public static SolverFacts load(MapStore.Ingest ingest) {
        Path file = ingest.dir().resolve("solver").resolve(FILE);
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("no solver facts for " + ingest + " at " + file
                    + " — run SolverStore.build for this map first");
        }
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(file)))) {
            return read(in);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read solver facts at " + file, e);
        }
    }

    /** Whether {@link #load} would succeed. */
    public static boolean built(MapStore.Ingest ingest) {
        return Files.isRegularFile(ingest.dir().resolve("solver").resolve(FILE));
    }

    /**
     * The facts, building them if this map version has none.
     * <p>
     * The helper the project actually invokes: everything downstream of it is cached
     * somewhere, so a second call is a file read and a first call is the whole pipeline.
     */
    public static SolverFacts prepare(PresetScenarioParameter preset, SolverFacts.Gate gate,
                                      EdgeWeights.Scheme scheme, double[][] chain,
                                      Flocking flock) throws IOException {
        if (built(preset.ingest())) return load(preset);
        return build(preset, gate, scheme, chain, flock);
    }

    /**
     * Derives every fact from scratch and writes it, replacing whatever was there.
     *
     * @param gate   the cut to decompose from. The one genuinely human choice in the pipeline
     * @param scheme the weighting the clock is solved under; lengths mean nothing across two
     * @param flock  the constants the windows are drawn at. Widening these gives a superset of
     *               the true windows, which is what a cover wants — see {@link Flocking}
     */
    public static SolverFacts build(PresetScenarioParameter preset, SolverFacts.Gate gate,
                                    EdgeWeights.Scheme scheme, double[][] chain,
                                    Flocking flock) throws IOException {
        long began = System.nanoTime();
        SimTest.Labelling l = SimTest.labelFor(preset, gate.horizontal(), gate.line(),
                gate.lo(), gate.hi(), gate.dir());
        SolverFacts.checkEdges(l.edges());

        EdgeNavigation.EdgeNav[] navs = EdgeNavigation.analyse(l.map(), l.live(), l.liveCount(),
                l.edge(), l.edges());
        int[] straightTo = new int[l.edges()];
        for (int e = 0; e < l.edges(); e++) straightTo[e] = navs[e].straight().to();
        long[] arcs = EdgeNavigation.arcs(l.map(), l.live(), l.liveCount(), l.edge(), l.edges());
        int[][] exitTurn = EdgeNavigation.exitTurns(navs, l.edges());
        EdgeNavigation.Properties props = SimTest.properties(preset, l);

        EdgeMetric.Metric metric = EdgeMetricStore.of(preset.ingest().outputDir("metric"),
                l.map(), l.edge(), l.live(), l.liveCount(), l.edges(), scheme, chain);
        SolverFacts.checkLengths(metric.length());

        SolverFacts.Window[] windows = windows(l, metric, straightTo, arcs, flock);

        // The full extent of each edge on the clock. A band as wide as this says nothing, and
        // knowing which bands those are is the difference between a wide window and no window.
        double[] tickLo = new double[l.edges()], tickHi = new double[l.edges()];
        Arrays.fill(tickLo, Double.MAX_VALUE);
        Arrays.fill(tickHi, -Double.MAX_VALUE);
        for (int i = 0; i < l.liveCount(); i++) {
            int s = l.live()[i], e = l.edge()[s];
            if (e < 0 || Double.isNaN(metric.tick()[s])) continue;
            tickLo[e] = Math.min(tickLo[e], metric.tick()[s]);
            tickHi[e] = Math.max(tickHi[e], metric.tick()[s]);
        }

        short[] edgeOf = new short[l.edge().length];
        Arrays.fill(edgeOf, (short) -1);
        double[] tickOf = new double[l.edge().length];
        Arrays.fill(tickOf, Double.NaN);
        for (int i = 0; i < l.liveCount(); i++) {
            int s = l.live()[i];
            edgeOf[s] = (short) l.edge()[s];
            tickOf[s] = metric.tick()[s];
        }

        SolverFacts facts = new SolverFacts(preset.name().toLowerCase(java.util.Locale.ROOT),
                preset.ingest().hash(), scheme + "/" + SolverFacts.describe(chain),
                flock.toString(), l.map().width(), l.map().height(), l.edges(), gate,
                metric.length(), tickLo, tickHi, props.stable(), props.scoring(), straightTo,
                exitTurn, arcs, windows, edgeOf, tickOf);

        Path file = preset.ingest().output("solver", FILE);
        write(file, facts, l.live(), l.liveCount());
        System.out.printf("%nwrote %s in %.1fs%n%s", file, (System.nanoTime() - began) / 1e9,
                facts.summary());
        return facts;
    }

    /**
     * A window for every arc unsteered travel does not account for.
     * <p>
     * Which arcs those are is not a judgement call: an arc {@code p -> c} of the edge graph is
     * steered exactly when {@code c} is not where holding straight from {@code p} goes. On
     * dabeone that picks out {@code 4->0}, {@code 2->1} and {@code 5->6} and nothing else,
     * which is the same three the exhaustive two-boid census found — so the cheap test agrees
     * with the expensive one.
     */
    private static SolverFacts.Window[] windows(SimTest.Labelling l, EdgeMetric.Metric metric,
                                                int[] straightTo, long[] arcs, Flocking flock) {
        List<SolverFacts.Window> out = new ArrayList<>();
        for (int from = 0; from < l.edges(); from++) {
            for (int keep = 0; keep < l.edges(); keep++) {
                if (keep == from || (arcs[from] & (1L << keep)) == 0) continue;
                if (straightTo[from] == keep) continue;
                out.add(window(l, metric, from, keep, flock));
            }
        }
        return out.toArray(new SolverFacts.Window[0]);
    }

    private static SolverFacts.Window window(SimTest.Labelling l, EdgeMetric.Metric metric,
                                             int from, int keep, Flocking flock) {
        EdgeInfluence.Envelope env = EdgeInfluence.envelope(l.map(), l.edge(), l.live(),
                l.liveCount(), from, keep);
        EdgeInfluence.Lead lead = EdgeInfluence.lead(l.map(), l.edge(), l.live(), l.liveCount(),
                from, keep, flock, env);

        double first = Double.MAX_VALUE, last = -Double.MAX_VALUE;
        for (int s : env.envelope()) {
            if (l.edge()[s] != from || Double.isNaN(metric.tick()[s])) continue;
            first = Math.min(first, metric.tick()[s]);
            last = Math.max(last, metric.tick()[s]);
        }

        List<SolverFacts.Band> bands = new ArrayList<>();
        // Opened at the first tau that has a band, not the first that has followers. An
        // envelope can span ticks the leader search finds nothing over -- edge 5 of dabeone
        // has four of them -- and anchoring on those leaves the window empty.
        double opens = Double.NaN;
        for (double tau = Math.ceil(first); tau <= last; tau += 1) {
            EdgeSlice.Slice slice = EdgeSlice.at(l.map(), l.edge(), metric, lead, from, tau,
                    true, l.edges());
            if (slice.followers() == 0 || slice.bands().isEmpty()) continue;
            if (Double.isNaN(opens)) opens = tau;
            for (EdgeSlice.Band b : slice.bands()) {
                bands.add(new SolverFacts.Band(tau, b.edge(), b.lo(), b.hi()));
            }
        }
        if (bands.isEmpty()) {
            throw new IllegalStateException("window " + from + "->" + keep + " has no bands; "
                    + "the arc exists but no leader placement induces it, which means the arc "
                    + "and the envelope disagree about what edge " + from + " does");
        }
        System.out.printf("  window %d->%d: opens at tau %.0f, %d bands%n", from, keep, opens,
                bands.size());
        return new SolverFacts.Window(from, keep, opens,
                bands.toArray(new SolverFacts.Band[0]));
    }

    private static void write(Path file, SolverFacts f, int[] live, int liveCount)
            throws IOException {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".partial");
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(tmp)))) {
            out.writeInt(FORMAT);
            out.writeUTF(f.map());
            out.writeUTF(f.hash());
            out.writeUTF(f.scheme());
            out.writeUTF(f.flocking());
            out.writeInt(f.width());
            out.writeInt(f.height());
            out.writeInt(Params.TURNS);
            out.writeInt(f.edges());
            out.writeBoolean(f.gate().horizontal());
            out.writeInt(f.gate().line());
            out.writeInt(f.gate().lo());
            out.writeInt(f.gate().hi());
            out.writeInt(f.gate().dir());
            for (int e = 0; e < f.edges(); e++) {
                out.writeDouble(f.length()[e]);
                out.writeDouble(f.tickLo()[e]);
                out.writeDouble(f.tickHi()[e]);
                out.writeBoolean(f.stable(e));
                out.writeBoolean(f.scoring(e));
                out.writeInt(f.straightTo()[e]);
                out.writeLong(f.arcs()[e]);
                for (int g = 0; g < f.edges(); g++) out.writeInt(f.exitTurn()[e][g]);
            }
            out.writeInt(f.windows().length);
            for (SolverFacts.Window w : f.windows()) {
                out.writeInt(w.from());
                out.writeInt(w.keep());
                out.writeDouble(w.opens());
                out.writeInt(w.bands().length);
                for (SolverFacts.Band b : w.bands()) {
                    out.writeDouble(b.tau());
                    out.writeInt(b.leaderEdge());
                    out.writeDouble(b.lo());
                    out.writeDouble(b.hi());
                }
            }
            // Live states only. The full arrays are ninety-odd megabytes of mostly nothing on
            // a map whose live states are a tenth of its state space, and the shape is
            // recovered on load from the ids themselves.
            out.writeInt(liveCount);
            for (int i = 0; i < liveCount; i++) out.writeInt(live[i]);
            for (int i = 0; i < liveCount; i++) out.writeByte(f.edgeOf()[live[i]]);
            for (int i = 0; i < liveCount; i++) out.writeDouble(f.tickOf()[live[i]]);
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    private static SolverFacts read(DataInputStream in) throws IOException {
        int format = in.readInt();
        if (format != FORMAT) {
            throw new NoSuchFileException(FILE, null, "solver facts are format " + format
                    + " and this build reads " + FORMAT + " — rebuild them");
        }
        String map = in.readUTF(), hash = in.readUTF(), scheme = in.readUTF(),
                flocking = in.readUTF();
        int width = in.readInt(), height = in.readInt(), turns = in.readInt();
        if (turns != Params.TURNS) {
            throw new NoSuchFileException(FILE, null, "solver facts were built with TURNS="
                    + turns + " and this build uses " + Params.TURNS + " — rebuild them");
        }
        int edges = in.readInt();
        SolverFacts.Gate gate = new SolverFacts.Gate(in.readBoolean(), in.readInt(),
                in.readInt(), in.readInt(), in.readInt());

        double[] length = new double[edges], tickLo = new double[edges], tickHi = new double[edges];
        boolean[] stable = new boolean[edges], scoring = new boolean[edges];
        int[] straightTo = new int[edges];
        int[][] exitTurn = new int[edges][edges];
        long[] arcs = new long[edges];
        for (int e = 0; e < edges; e++) {
            length[e] = in.readDouble();
            tickLo[e] = in.readDouble();
            tickHi[e] = in.readDouble();
            stable[e] = in.readBoolean();
            scoring[e] = in.readBoolean();
            straightTo[e] = in.readInt();
            arcs[e] = in.readLong();
            for (int g = 0; g < edges; g++) exitTurn[e][g] = in.readInt();
        }

        SolverFacts.Window[] windows = new SolverFacts.Window[in.readInt()];
        for (int i = 0; i < windows.length; i++) {
            int from = in.readInt(), keep = in.readInt();
            double opens = in.readDouble();
            SolverFacts.Band[] bands = new SolverFacts.Band[in.readInt()];
            for (int b = 0; b < bands.length; b++) {
                bands[b] = new SolverFacts.Band(in.readDouble(), in.readInt(), in.readDouble(),
                        in.readDouble());
            }
            windows[i] = new SolverFacts.Window(from, keep, opens, bands);
        }

        int states = width * height * Params.TURNS;
        short[] edgeOf = new short[states];
        Arrays.fill(edgeOf, (short) -1);
        double[] tickOf = new double[states];
        Arrays.fill(tickOf, Double.NaN);
        int liveCount = in.readInt();
        int[] live = new int[liveCount];
        for (int i = 0; i < liveCount; i++) live[i] = in.readInt();
        for (int i = 0; i < liveCount; i++) edgeOf[live[i]] = in.readByte();
        for (int i = 0; i < liveCount; i++) tickOf[live[i]] = in.readDouble();

        return new SolverFacts(map, hash, scheme, flocking, width, height, edges, gate, length,
                tickLo, tickHi, stable, scoring, straightTo, exitTurn, arcs, windows, edgeOf,
                tickOf);
    }
}
