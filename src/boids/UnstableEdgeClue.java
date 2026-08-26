package boids;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The clue that a boid is somewhere unsteered travel would not have left it.
 * <p>
 * A boid does not choose. Left alone it holds its heading and follows a fixed route, so an
 * edge it cannot stay on is a place it can only be if something acted on it — and there are
 * exactly two things that can: it was overridden, or another boid was positioned to turn it.
 * Reading a photograph is then a matter of chasing that question backwards until it lands
 * somewhere no explanation is owed.
 * <p>
 * <b>The recursion.</b> A node is an arrangement written as {@code (edge, tick)} per boid.
 * That pair is not a place, it is a place <em>and</em> a choice of what to measure it from:
 * rebasing a boid from {@code (e, t)} to {@code (p, t + length[p])} leaves it exactly where it
 * was and only changes which crossing the clock is counting since. So the search never moves
 * anything. It re-reads the same arrangement further and further into the past, and a boid
 * that started on an edge it cannot hold ends up expressed relative to one it can.
 * <p>
 * At each node, every boid on an unstable edge owes an account of itself. The accounts are
 * <b>ORed</b> — one boid can have arrived several ways and any of them will do — and one
 * disjunct is always available, namely that this boid is the psyboid. Across boids the results
 * are <b>ANDed</b>, because one psyboid has to explain all of them at once. An AND over
 * nothing is every boid and an OR over nothing is none, which is what makes a scene where
 * nothing needs explaining come back "could be anyone" rather than empty.
 * <p>
 * <b>Two ways back.</b> A boid can have coasted onto its edge, which costs nothing to believe
 * and just rebases it one edge earlier. Or it was led there, which costs a leader: some other
 * boid that a {@link SolverFacts.Window} says could have been in the right place at the right
 * moment. A leader that is already on the right edge lets the suspect be backed up; a leader
 * that is not gets backed up itself, one edge at a time, and the node is re-read.
 * <p>
 * <b>Rebasing hides steered arrivals, so they are conjoined by hand.</b> Backing a leader from
 * edge 0 to edge 4 on dabeone describes the same boid in the same place, but edge 4 is stable
 * and edge 0 is not — the demand for an explanation vanishes with the relabelling even though
 * the turn it referred to certainly happened. Wherever a backwards step crosses an arc
 * unsteered travel does not account for, that arc's own account is ANDed into the branch.
 * <p>
 * <b>Drift is a constant, and a measured one.</b> The clock is a continuous fit to a discrete
 * walk, so a tick value is right to about a tick and edge boundaries carry their own slop —
 * dabeone's edge 0 runs from -2.4 to 164.4 against a length of 158.1. Against 60 turns whose
 * true leader the full-information audit had already named, every leader fell within <b>1.69
 * ticks</b> of the opening band, median 0.65. {@link #TOLERANCE} is set from that. It is not a
 * fudge factor and it is not free to grow: at two ticks the bands cover 7.5% of a leader edge,
 * and 76% once the window is instead opened wide enough in {@code tau} to admit the same 60.
 */
public final class UnstableEdgeClue implements Clue {

    /**
     * Depth, in ticks of history, at which a search says so.
     * <p>
     * Depth is measured in the clock rather than in recursion levels because that is what
     * bounds it: every step backwards adds a whole edge length, and an edge length is the
     * natural unit of "how much past is being invented".
     */
    public static final double NOTE_DEPTH = 1000;

    /** Depth at which a search gives up. Nothing on a dab-like map should come near it. */
    public static final double STOP_DEPTH = 1500;

    /**
     * Headroom over the depth limit for the leader test.
     * <p>
     * A leader has to be found at a moment the suspect has already been rebased back to, so
     * the route it is looked for along is longer than the depth itself by up to a lap of the
     * edge graph.
     */
    private static final double ROUTE_HEADROOM = 900;

    /** Guards against a map whose edge graph makes the route enumeration blow up. */
    private static final int ROUTE_LIMIT = 1 << 16;

    /**
     * How many ticks past a window's opening still count as the window.
     * <p>
     * Zero by default, and that is a real decision rather than a placeholder. The bands widen
     * as the led boid advances and saturate to whole edges within a few ticks — a boid at the
     * end of the envelope is already committed, so every leader placement "suffices" from
     * there and the band stops saying anything. The opening band is the one that carries the
     * constraint.
     */
    private final int span;

    /**
     * Ticks of slack allowed on either side of a band.
     * <p>
     * Two, because 1.69 was the worst miss over the 60 turns the audit had ground truth for
     * and rounding up is the only honest thing to do with a maximum drawn from sixty samples.
     * Raising it costs discrimination roughly linearly and buys nothing that was measured;
     * lowering it to one loses four of the sixty.
     */
    public static final double TOLERANCE = 2.0;

    private final double tolerance;

    private final double stopDepth;

    public UnstableEdgeClue() { this(0, TOLERANCE, STOP_DEPTH); }

    public UnstableEdgeClue(int span, double tolerance) { this(span, tolerance, STOP_DEPTH); }

    /**
     * @param stopDepth how much history the search may invent before giving up. Settable so a
     *                  run can find out whether a search that hit the limit was going to close
     *                  eventually or was never going to — not the same answer on every map,
     *                  and guessing it is how a limit becomes a lie
     */
    public UnstableEdgeClue(int span, double tolerance, double stopDepth) {
        this.span = span;
        this.tolerance = tolerance;
        this.stopDepth = stopDepth;
    }

    @Override
    public String name() { return "unstable-edge"; }

    @Override
    public double[] odds(SolverFacts facts, Sim.State state) {
        boolean[] possible = solve(facts, state).candidate();
        double[] odds = new double[state.n];
        for (int i = 0; i < state.n; i++) odds[i] = possible[i] ? 1 : 0;
        return odds;
    }

    /**
     * @param candidate per boid, whether some consistent history has it as the psyboid
     * @param owed      per boid, the account it owed for being where it is, or null if it owed
     *                  none. {@code candidate} is these ANDed together, so a boid missing from
     *                  one of them is a boid this row ruled out — which is the only place an
     *                  answer can be traced to
     * @param nodes     arrangements examined, before the cache
     * @param cached    of those, ones already answered
     * @param deepest   furthest back any boid had to be rebased, in ticks
     */
    public record Result(boolean[] candidate, boolean[][] owed, long nodes, long cached,
                         double deepest) {}

    /** The same answer with the numbers that say whether the search was cheap. */
    public Result solve(SolverFacts facts, Sim.State state) {
        return solve(facts, state, null);
    }

    /**
     * Somewhere to send an account of the reasoning, one indented line per step.
     * <p>
     * The evaluation this feeds is about <em>why</em> a boid can be named, not only that it
     * can, so a solver that cannot say what it concluded from is only half of the answer. This
     * is also the only practical way to find out which branch of a several-hundred-node search
     * refused to close.
     */
    public interface Trace {
        void step(int depth, String message);
    }

    public Result solve(SolverFacts facts, Sim.State state, Trace trace) {
        return new Search(facts, state, span, tolerance, stopDepth, trace).run();
    }

    /**
     * One arrangement, as a rebasing.
     * <p>
     * Records compare array components by identity, which would make every node distinct and
     * the cache useless, so both halves of the contract are written out. The tick values are
     * compared bit for bit on purpose: two nodes agreeing to the last bit are the same
     * rebasing arrived at by a different order of steps, which is exactly what the cache is
     * there to collapse.
     */
    private record Node(int[] edge, double[] tick) {

        Node rebased(int boid, int toEdge, double toTick) {
            int[] e = edge.clone();
            double[] t = tick.clone();
            e[boid] = toEdge;
            t[boid] = toTick;
            return new Node(e, t);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Node n && Arrays.equals(edge, n.edge)
                    && Arrays.equals(tick, n.tick);
        }

        @Override
        public int hashCode() {
            return 31 * Arrays.hashCode(edge) + Arrays.hashCode(tick);
        }
    }

    /** A boid's arrival on its current edge, at one node. Keyed to stop mutual regress. */
    private record Arrival(Node node, int boid) {}

    private static final class Search {

        private final SolverFacts f;
        private final Sim.State state;
        private final int n;
        private final int span;
        private final double tolerance;
        private final double stopDepth;
        private final double routeCap;
        private final double[] base;

        private final Map<Node, boolean[]> answered = new HashMap<>();
        private final Set<Node> solving = new HashSet<>();
        private final Set<Arrival> arriving = new HashSet<>();
        private final Map<Integer, double[]> routes = new HashMap<>();
        private final Map<SolverFacts.Window, SolverFacts.Band[]> opening = new HashMap<>();

        private final Trace trace;

        private long nodes, cached;
        private double deepest;
        private boolean noted;
        private int depth;

        Search(SolverFacts f, Sim.State state, int span, double tolerance, double stopDepth,
               Trace trace) {
            this.f = f;
            this.state = state;
            this.n = state.n;
            this.span = span;
            this.tolerance = tolerance;
            this.stopDepth = stopDepth;
            this.routeCap = stopDepth + ROUTE_HEADROOM;
            this.trace = trace;
            this.base = new double[n];
        }

        private void say(String format, Object... args) {
            if (trace != null) trace.step(depth, String.format(format, args));
        }

        Result run() {
            int[] edge = new int[n];
            double[] tick = new double[n];
            for (int i = 0; i < n; i++) {
                edge[i] = f.edgeAt(state.x[i], state.y[i], state.h[i]);
                tick[i] = f.tickAt(state.x[i], state.y[i], state.h[i]);
                if (edge[i] < 0 || Double.isNaN(tick[i])) {
                    throw new IllegalStateException("boid " + i + " at (" + state.x[i] + ","
                            + state.y[i] + "," + state.h[i] + ") is on no edge of "
                            + f.map() + "@" + f.hash() + " — the facts do not describe this "
                            + "map, or this arrangement never came from it");
                }
                base[i] = tick[i];
            }
            // The root's AND is run here rather than through solve, so each boid's own account
            // survives to be reported. Everything below the root goes through solve as usual.
            Node root = new Node(edge, tick);
            boolean[][] owed = new boolean[n][];
            boolean[] out = new boolean[n];
            Arrays.fill(out, true);
            nodes++;
            for (int b = 0; b < n; b++) {
                if (f.stable(edge[b])) continue;
                owed[b] = explain(root, b);
                for (int i = 0; i < n; i++) out[i] &= owed[b][i];
            }
            if (!any(out)) {
                throw new Solver.Unexplained("no single boid accounts for this arrangement on "
                        + f.map() + "@" + f.hash() + "; " + describe(edge, tick));
            }
            return new Result(out, owed, nodes, cached, deepest);
        }

        private String describe(int[] edge, double[] tick) {
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < n; i++) {
                s.append(i > 0 ? ", " : "").append("boid ").append(i).append(" on edge ")
                        .append(edge[i]).append(f.stable(edge[i]) ? "" : " (unstable)")
                        .append(String.format(" at %.1f", tick[i]));
            }
            return s.toString();
        }

        /**
         * Who could be the psyboid, given this arrangement and everything it implies about
         * the past.
         * <p>
         * The AND runs over boids owing an explanation. It can come out empty at a node deep
         * in the search, and that is not a failure: it means the hypothesis that led here is
         * refuted, and the parent's OR simply gains nothing from this branch. Only the root
         * treats an empty answer as something to complain about, because only at the root
         * does it mean the scene itself has no account.
         */
        private boolean[] solve(Node node) {
            boolean[] done = answered.get(node);
            if (done != null) { cached++; return done; }
            // Lengths are positive, so ticks strictly increase and a node cannot recur. The
            // guard is here for the map where that stops being true rather than for this one.
            if (!solving.add(node)) return new boolean[n];
            nodes++;
            budget(node);

            boolean[] all = new boolean[n];
            Arrays.fill(all, true);
            depth++;
            for (int b = 0; b < n; b++) {
                if (f.stable(node.edge()[b])) continue;
                boolean[] e = explain(node, b);
                for (int i = 0; i < n; i++) all[i] &= e[i];
                if (!any(all)) break;                  // nothing left for a later boid to cut
            }
            depth--;
            say("node %s -> %s", show(node), show(all));

            solving.remove(node);
            answered.put(node, all);
            return all;
        }

        private String show(Node node) {
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < n; i++) {
                s.append(i > 0 ? " " : "").append(node.edge()[i])
                        .append(f.stable(node.edge()[i]) ? "" : "*")
                        .append('@').append(String.format("%.0f", node.tick()[i]));
            }
            return s.toString();
        }

        private String show(boolean[] bits) {
            StringBuilder s = new StringBuilder("{");
            for (int i = 0; i < bits.length; i++) {
                if (bits[i]) s.append(s.length() > 1 ? "," : "").append(i);
            }
            return s.append('}').toString();
        }

        /**
         * Every way this boid could be here, plus the standing option that it is the one.
         * <p>
         * The disjuncts are tried nearest first — an account that lands the boid straight onto
         * an edge it could have been left on settles the question, and once the OR holds every
         * boid nothing further can add to it. Without that the search follows the first branch
         * it is handed all the way down, and dabeone has a loop of edges (3, 5, 6) that a boid
         * can go round indefinitely so long as something leads it over {@code 5->6} each lap.
         * Every lap is a consistent history, they never run out, and the depth limit is what
         * ends them. Preferring the accounts that close means the loop is only walked when
         * nothing else works.
         */
        private boolean[] explain(Node node, int b) {
            int e = node.edge()[b];
            double t = node.tick()[b];
            boolean[] acc = new boolean[n];
            acc[b] = true;
            // Coasted in. Costs nothing to believe, so it is only a rebasing.
            for (int p : nearestFirst(f.straightFrom(e))) {
                say("boid %d on edge %d at %.0f: could have coasted from edge %d", b, e, t, p);
                or(acc, solve(node.rebased(b, p, t + f.length()[p])));
                if (every(acc)) return acc;
            }
            or(acc, led(node, b));
            say("boid %d on edge %d at %.0f owes %s", b, e, t, show(acc));
            return acc;
        }

        /** Stable edges first: those are the ones a rebasing can finish on. */
        private int[] nearestFirst(int[] edges) {
            if (edges.length < 2) return edges;
            int[] out = edges.clone();
            for (int i = 1; i < out.length; i++) {
                for (int j = i; j > 0 && rank(out[j]) < rank(out[j - 1]); j--) {
                    int swap = out[j];
                    out[j] = out[j - 1];
                    out[j - 1] = swap;
                }
            }
            return out;
        }

        private int rank(int edge) {
            if (f.stable(edge)) return 0;
            // An edge whose own account can end on a stable one is the next best thing.
            for (int p : f.straightFrom(edge)) if (f.stable(p)) return 1;
            for (SolverFacts.Window w : f.windowsInto(edge)) if (f.stable(w.from())) return 1;
            return 2;
        }

        /**
         * Every way another boid could have turned this one onto the edge it is on.
         * <p>
         * Kept separate from {@link #explain} because it is also the account owed for a
         * steered arc crossed while backing a leader up, where the boid's current edge no
         * longer advertises that anything happened.
         */
        private boolean[] led(Node node, int b) {
            Arrival key = new Arrival(node, b);
            // Two boids can each be proposed as the other's leader. Without this the pair
            // walks back and forth over one arrangement forever without deepening it.
            if (!arriving.add(key)) return new boolean[n];
            boolean[] acc = new boolean[n];
            try {
                int e = node.edge()[b];
                double t = node.tick()[b];
                for (SolverFacts.Window w : f.windowsInto(e)) {
                    // Where the suspect is, measured from the crossing before the one it made.
                    double back = t + f.length()[w.from()];
                    for (SolverFacts.Band band : bands(w)) {
                        // The band was recorded against the suspect being tau along that edge.
                        // The suspect is now `back` along it, so the band belongs that much
                        // later.
                        double shift = back - band.tau();
                        double lo = band.lo() + shift - tolerance;
                        double hi = band.hi() + shift + tolerance;
                        int on = band.leaderEdge();
                        for (int j = 0; j < n; j++) {
                            if (j == b) continue;
                            boolean[] one = candidate(node, b, j, w.from(), back, on, lo, hi);
                            if (any(one)) {
                                say("boid %d led out of %d by boid %d on edge %d in %.0f..%.0f"
                                        + " -> %s", b, w.from(), j, on, lo, hi, show(one));
                            }
                            or(acc, one);
                            if (every(acc)) return acc;
                        }
                    }
                }
            } finally {
                arriving.remove(key);
            }
            return acc;
        }

        /**
         * What follows from supposing boid {@code j} led boid {@code b} out of {@code from}.
         *
         * @param back  where the suspect sits once rebased onto {@code from}
         * @param on    the edge the leader has to be on
         * @param lo    earliest it can be along that edge, as of now
         * @param hi    latest
         */
        private boolean[] candidate(Node node, int b, int j, int from, double back, int on,
                                    double lo, double hi) {
            int at = node.edge()[j];
            double now = node.tick()[j];
            if (at == on && now >= lo && now <= hi) {
                // The leader is where it needs to be. Nothing left to establish about it, so
                // the suspect's own arrival is the thing that gets rebased away.
                return solve(node.rebased(b, from, back));
            }
            boolean[] acc = new boolean[n];
            for (int q : nearestFirst(f.predecessors(at))) {
                double when = now + f.length()[q];
                if (!reaches(q, when, on, lo, hi)) continue;
                boolean[] r = solve(node.rebased(j, q, when)).clone();
                if (f.straightTo()[q] != at) {
                    // The leader turned to get onto the edge it is on. Backing it up past that
                    // turn spends the evidence of it, so the account for it is conjoined here
                    // rather than left for a node that will no longer ask.
                    boolean[] arrival = led(node, j);
                    arrival[j] = true;
                    for (int i = 0; i < n; i++) r[i] &= arrival[i];
                }
                or(acc, r);
                if (every(acc)) return acc;
            }
            return acc;
        }

        /**
         * Could this boid, standing {@code when} ticks past the start of edge {@code from},
         * have been between {@code lo} and {@code hi} ticks along edge {@code on}?
         * <p>
         * Backwards along the edge graph only. Rebasing adds a whole edge length every step so
         * the tick value only grows, which makes the pruning exact: once it is past the top of
         * the band there is no route back into it.
         */
        private boolean reaches(int from, double when, int on, double lo, double hi) {
            if (when > hi) return false;
            if (hi - when > routeCap) {
                throw new IllegalStateException("leader test needs routes " + (hi - when)
                        + " ticks long and only " + routeCap + " are enumerated");
            }
            for (double route : routes(from, on)) {
                if (when + route > hi) return false;          // sorted; nothing later fits
                if (when + route >= lo) return true;
            }
            return false;
        }

        /** Every total length a backwards route from one edge to another can have. */
        private double[] routes(int from, int on) {
            int key = from * f.edges() + on;
            double[] cache = routes.get(key);
            if (cache != null) return cache;
            List<Double> found = new ArrayList<>();
            if (from == on) found.add(0.0);
            walk(from, 0, on, found);
            double[] out = new double[found.size()];
            for (int i = 0; i < out.length; i++) out[i] = found.get(i);
            Arrays.sort(out);
            routes.put(key, out);
            return out;
        }

        private void walk(int at, double sum, int on, List<Double> found) {
            if (found.size() > ROUTE_LIMIT) {
                throw new IllegalStateException("more than " + ROUTE_LIMIT + " backwards "
                        + "routes within " + routeCap + " ticks; this map's edge graph is "
                        + "too tangled for the leader test as written");
            }
            for (int p : f.predecessors(at)) {
                double next = sum + f.length()[p];
                if (next > routeCap) continue;
                if (p == on) found.add(next);
                walk(p, next, on, found);
            }
        }

        private SolverFacts.Band[] bands(SolverFacts.Window w) {
            return opening.computeIfAbsent(w, k -> k.opening(span));
        }

        private void budget(Node node) {
            double deep = 0;
            for (int b = 0; b < n; b++) deep = Math.max(deep, node.tick()[b] - base[b]);
            deepest = Math.max(deepest, deep);
            if (deep > stopDepth) {
                throw new Solver.TooDeep("the search reached " + String.format("%.0f", deep)
                        + " ticks of history without every boid landing somewhere stable; "
                        + "either the map has a loop of steered arcs or the windows are wide "
                        + "enough to keep one open forever");
            }
            if (deep > NOTE_DEPTH && !noted) {
                noted = true;
                System.out.printf("  (unstable-edge search is %.0f ticks deep)%n", deep);
            }
        }

        private void or(boolean[] into, boolean[] add) {
            for (int i = 0; i < n; i++) into[i] |= add[i];
        }

        private boolean any(boolean[] bits) {
            for (boolean b : bits) if (b) return true;
            return false;
        }

        /** An OR that already holds for every boid cannot be added to. */
        private boolean every(boolean[] bits) {
            for (boolean b : bits) if (!b) return false;
            return true;
        }
    }
}
