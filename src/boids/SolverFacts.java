package boids;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Everything a solver is allowed to know about a map before it is shown a photograph.
 * <p>
 * The solver's whole claim is that a boid's position is nearly always enough, because a boid
 * left alone does not choose. Cashing that in means arriving at a scene already knowing which
 * places a boid can be left in indefinitely, where unsteered travel goes from each of them,
 * how far along its route any given state is, and where a second boid would have to be to
 * have caused each turn that unsteered travel does not explain. All of that is a property of
 * the play area, none of it is a property of the flock, and every piece of it costs minutes to
 * derive — so it is derived once, per map version, and read back.
 * <p>
 * That split is the point rather than an optimisation. A solver that recomputed a
 * decomposition per scene would be answering a different question from the one the evaluation
 * asks, which is what can be concluded from the picture given that the map was studied
 * beforehand. Everything here is therefore keyed to a {@link MapStore.Ingest} and lives inside
 * it, so facts can never outlive the map they describe.
 *
 * @see SolverStore for how these are built and where they are kept
 */
public final class SolverFacts {

    /**
     * The cut the decomposition was taken from.
     * <p>
     * Carried because it is the one input a human chose, and because until now it existed
     * nowhere but the title of a rendered graph. A reader who wants to reproduce these facts
     * needs it; nothing at solve time reads it.
     */
    public record Gate(boolean horizontal, int line, int lo, int hi, int dir) {
        @Override
        public String toString() {
            return "%s=%d %s=[%d,%d] %s".formatted(horizontal ? "y" : "x", line,
                    horizontal ? "x" : "y", lo, hi,
                    dir == 0 ? "both ways" : dir > 0 ? "increasing" : "decreasing");
        }
    }

    /**
     * One row of a window: with the led boid {@code tau} ticks along the edge it is being led
     * off, the leader lies somewhere between {@code lo} and {@code hi} ticks along
     * {@code leaderEdge}.
     * <p>
     * A band rather than a set of states, because a set of states carries the phase it was
     * sampled on and a band carries only distance. Distances are what shift cleanly when the
     * whole arrangement is rebased to an earlier moment, which is all the search ever does to
     * them.
     */
    public record Band(double tau, int leaderEdge, double lo, double hi) {
        public double width() { return hi - lo; }
    }

    /**
     * A band wide enough to say nothing.
     * <p>
     * The leader search asks which placements are compatible with the boid still leaving the
     * right way, and near the end of an envelope the boid is committed — so every placement is
     * compatible and the band comes back as the whole edge. That is not a wide constraint, it
     * is the absence of one, and on dabeone the rows it produces are byte-identical across all
     * three windows: every edge at its full extent. Discarding them is what lets every other
     * {@code tau} be used, which matters because a leader can join a boid part way through and
     * a window anchored on one moment misses it.
     */
    public static final double VACUOUS = 0.9;

    /** Whether this band covers so much of its edge that it excludes nothing. */
    public boolean vacuous(Band b) {
        double span = tickHi[b.leaderEdge()] - tickLo[b.leaderEdge()];
        return span <= 0 || b.width() >= VACUOUS * span;
    }

    /**
     * A turn unsteered travel does not account for, and where a leader has to be to cause it.
     * <p>
     * One window per steered arc of the edge graph: a boid on {@code from} that would have
     * gone straight to {@code straightTo[from]} instead leaves for {@code keep}.
     * <p>
     * <b>A leader can join part way through, so no single {@code tau} is the window.</b> The
     * rows are all kept and a reader is meant to use all of them — except the ones
     * {@link #vacuous} rejects, which are not wide constraints but absent ones. Anchoring on
     * the opening row alone looks tidy and is wrong: on dabeone the {@code 2->1} window has no
     * band at all on edges 7 or 8 at its opening {@code tau} and gains both one tick later,
     * and a real leader standing on edge 8 is therefore invisible to it.
     *
     * @param opens the smallest {@code tau} that has a band. Not the smallest {@code tau} the
     *              envelope covers — an envelope can span ticks over which the leader search
     *              finds nothing, and treating those as the opening leaves a window with no
     *              bands under it at all
     */
    public record Window(int from, int keep, double opens, Band[] bands) {

        /** The bands from when the window opens through {@code span} ticks after that. */
        public Band[] opening(int span) {
            List<Band> out = new ArrayList<>();
            for (Band b : bands) if (b.tau() <= opens + span) out.add(b);
            return out.toArray(new Band[0]);
        }

        @Override
        public String toString() { return from + "->" + keep; }
    }

    private final String map;
    private final String hash;
    private final String scheme;
    private final String flocking;
    private final int width;
    private final int height;
    private final int edges;
    private final Gate gate;

    private final double[] length;
    private final double[] tickLo;
    private final double[] tickHi;
    private final boolean[] stable;
    private final boolean[] scoring;
    private final int[] straightTo;
    private final int[][] exitTurn;
    private final long[] arcs;
    private final Window[] windows;

    /** Per state id, the edge it belongs to, or -1 where no boid can be. */
    private final short[] edgeOf;

    /** Per state id, how far along its own edge it is; NaN where it has no edge. */
    private final double[] tickOf;

    private final int[][] predecessors;
    private final int[][] straightFrom;
    private final Window[][] into;

    SolverFacts(String map, String hash, String scheme, String flocking, int width, int height,
                int edges, Gate gate, double[] length, double[] tickLo, double[] tickHi,
                boolean[] stable, boolean[] scoring, int[] straightTo, int[][] exitTurn,
                long[] arcs, Window[] windows, short[] edgeOf, double[] tickOf) {
        this.map = map;
        this.hash = hash;
        this.scheme = scheme;
        this.flocking = flocking;
        this.width = width;
        this.height = height;
        this.edges = edges;
        this.gate = gate;
        this.length = length;
        this.tickLo = tickLo;
        this.tickHi = tickHi;
        this.stable = stable;
        this.scoring = scoring;
        this.straightTo = straightTo;
        this.exitTurn = exitTurn;
        this.arcs = arcs;
        this.windows = windows;
        this.edgeOf = edgeOf;
        this.tickOf = tickOf;

        // The three lookups the search runs in its innermost loops, inverted once here so it
        // never scans the edge graph to answer them.
        this.predecessors = new int[edges][];
        this.straightFrom = new int[edges][];
        this.into = new Window[edges][];
        for (int e = 0; e < edges; e++) {
            List<Integer> preds = new ArrayList<>(), straights = new ArrayList<>();
            for (int p = 0; p < edges; p++) {
                if ((arcs[p] & (1L << e)) != 0) preds.add(p);
                if (straightTo[p] == e) straights.add(p);
            }
            predecessors[e] = ints(preds);
            straightFrom[e] = ints(straights);
            List<Window> ws = new ArrayList<>();
            for (Window w : windows) if (w.keep() == e) ws.add(w);
            into[e] = ws.toArray(new Window[0]);
        }
    }

    private static int[] ints(List<Integer> list) {
        int[] out = new int[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = list.get(i);
        return out;
    }

    public String map() { return map; }

    public String hash() { return hash; }

    /** The weighting the clock was solved under. Lengths are only comparable within one. */
    public String scheme() { return scheme; }

    /** The flocking constants the windows were drawn at, which need not be the simulation's. */
    public String flocking() { return flocking; }

    public int width() { return width; }

    public int height() { return height; }

    public int edges() { return edges; }

    public Gate gate() { return gate; }

    public double[] length() { return length; }

    /** Per edge, the smallest and largest tick value any of its states has. */
    public double[] tickLo() { return tickLo; }

    public double[] tickHi() { return tickHi; }

    public int[] straightTo() { return straightTo; }

    /**
     * Which turn a crossing between two edges counts as: -1 left, 0 straight, +1 right, or
     * {@link EdgeNavigation#NO_EXIT} where the pair is not an arc at all.
     * <p>
     * A fact about the map rather than about any flight through it, which is what makes it
     * safe to read off a photograph. See {@link EdgeNavigation#exitTurns} for why it must not
     * be inferred from what a boid was steering when it crossed.
     */
    public int[][] exitTurn() { return exitTurn; }

    public Window[] windows() { return windows; }

    /** Unsteered travel from here comes back here without ever scoring. */
    public boolean stable(int edge) { return stable[edge]; }

    /** A boid here has scored, or cannot now avoid scoring. */
    public boolean scoring(int edge) { return scoring[edge]; }

    /** Every edge one step upstream of this one. */
    public int[] predecessors(int edge) { return predecessors[edge]; }

    /** Every edge unsteered travel leads from, into this one. */
    public int[] straightFrom(int edge) { return straightFrom[edge]; }

    /** Every window that puts a boid onto this edge. */
    public Window[] windowsInto(int edge) { return into[edge]; }

    public int state(int x, int y, int heading) {
        return (x + y * width) * Params.TURNS + heading;
    }

    /** Which edge a boid here is on, or -1 if it is nowhere a boid can be. */
    public int edgeAt(int x, int y, int heading) {
        int s = state(x, y, heading);
        return s < 0 || s >= edgeOf.length ? -1 : edgeOf[s];
    }

    /** How far along that edge, by the clock; NaN if there is no answer. */
    public double tickAt(int x, int y, int heading) {
        int s = state(x, y, heading);
        return s < 0 || s >= tickOf.length ? Double.NaN : tickOf[s];
    }

    short[] edgeOf() { return edgeOf; }

    double[] tickOf() { return tickOf; }

    boolean[] stableFlags() { return stable; }

    boolean[] scoringFlags() { return scoring; }

    long[] arcs() { return arcs; }

    /** A line per edge, for confirming by eye that the right facts got loaded. */
    public String summary() {
        StringBuilder s = new StringBuilder();
        s.append("%s @%s, gate %s%n".formatted(map, hash, gate));
        s.append("  clock %s, windows drawn at %s%n".formatted(scheme, flocking));
        s.append("  %-5s %8s %10s %9s %s%n".formatted("edge", "length", "straightTo", "stable",
                "scoring"));
        for (int e = 0; e < edges; e++) {
            s.append("  %-5d %8.2f %10d %9b %b%n".formatted(e, length[e], straightTo[e],
                    stable[e], scoring[e]));
        }
        for (Window w : windows) {
            s.append("  window %d->%d opens at tau %.0f, %d bands over %d ticks%n".formatted(
                    w.from(), w.keep(), w.opens(), w.bands().length, taus(w)));
        }
        return s.toString();
    }

    private static int taus(Window w) {
        double last = w.opens();
        for (Band b : w.bands()) last = Math.max(last, b.tau());
        return (int) Math.round(last - w.opens()) + 1;
    }

    @Override
    public String toString() {
        return "SolverFacts[" + map + "@" + hash + ", " + edges + " edges, "
                + windows.length + " windows]";
    }

    /**
     * Where a decomposition stops fitting the storage.
     * <p>
     * The same 63 the refiner gives up at, for the same reason: an edge set is carried as a
     * 64-bit mask. Checked here as well so a map that got past the refiner does not fail later
     * as a silently truncated label.
     */
    public static final int MAX_EDGES = 63;

    static void checkEdges(int edges) {
        if (edges > MAX_EDGES) {
            throw new IllegalStateException("decomposition has " + edges + " edges; the "
                    + "solver stores an edge label per state as a byte and cannot hold more "
                    + "than " + MAX_EDGES);
        }
    }

    static void checkLengths(double[] length) {
        for (int e = 0; e < length.length; e++) {
            if (!(length[e] > 0)) {
                throw new IllegalStateException("edge " + e + " has length " + length[e]
                        + "; the search rebases by adding lengths and would not terminate");
            }
        }
    }

    static String describe(double[][] chain) {
        return chain == null ? "none" : Arrays.deepToString(chain);
    }
}
