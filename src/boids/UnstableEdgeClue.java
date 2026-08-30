package boids;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The clue that a boid is somewhere unsteered travel would not have left it.
 * <p>
 * A boid does not choose. Left alone it holds its heading and follows a fixed route, so an edge
 * it cannot stay on is a place it can only be if something acted on it — and there are exactly
 * two things that can: it was overridden, or another boid was positioned to turn it. Reading a
 * photograph is chasing that question backwards until it lands somewhere no explanation is
 * owed.
 * <p>
 * <b>Positions are rebasings, not places.</b> A boid is carried as {@code (edge, tick)}, and
 * rebasing it from {@code (e, t)} to {@code (p, t + length[p])} leaves it exactly where it is —
 * only the crossing the clock counts from has changed. So the search never moves anything. It
 * re-reads one arrangement further and further into the past, and a boid that started on an
 * edge it cannot hold ends up expressed relative to one it can.
 * <p>
 * <b>Two recursions, and what each is for.</b> {@link Search#state} asks what follows from one
 * boid being where it is; it bottoms out at once if that boid is on a stable edge, since a boid
 * there could have been there forever. {@link Search#navigation} asks what follows from one
 * particular way of having arrived. A straight arrival costs nothing to believe and hands the
 * question back to {@code state} one edge earlier. A steered arrival has to be paid for, either
 * by a leader that a window puts in the right place or by the boid having steered itself.
 * <p>
 * <b>A steered arrival ends the suspect's thread.</b> Once a turn is explained the search
 * follows the boid that explains it, not the one that was turned. That is what keeps the
 * recursion finite on a map like dabeone, whose edges 3, 5 and 6 form a loop a boid can go
 * round indefinitely so long as something leads it over {@code 5->6} each lap: chasing the
 * suspect round it produces consistent histories forever, and there is no depth at which
 * stopping is principled.
 * <p>
 * <b>Self-steering is always available, which is what makes the lazy AND exact.</b> Every
 * account of a backwards navigation contains the navigating boid, because "it steered itself"
 * is never ruled out. So an AND of such accounts always contains that boid, and once it holds
 * nothing else, no further term can change it — the rest of the chain can be abandoned unread.
 * Without that, setting up a leader that turns out to have needed steering of its own sends the
 * search off down a branch whose answer is already known.
 */
public final class UnstableEdgeClue implements Clue {

    /**
     * Depth, in ticks of history, at which a search says so.
     * <p>
     * Measured on the clock rather than in recursion levels, because that is what bounds it:
     * every step backwards adds a whole edge length, and an edge length is the natural unit of
     * how much past is being invented.
     */
    public static final double NOTE_DEPTH = 1000;

    /** Depth at which a search gives up. Nothing on a dab-like map should come near it. */
    public static final double STOP_DEPTH = 1500;

    /** Longest backwards route the leader search will enumerate, in ticks. */
    private static final double ROUTE_CAP = STOP_DEPTH + 900;

    /** Guard against a map whose edge graph makes route enumeration blow up. */
    private static final int ROUTE_LIMIT = 1 << 14;

    /**
     * The windows this solver has been given, and nothing else.
     * <p>
     * A window is not free: it is a claim that a turn can be accounted for without the boid
     * having steered itself, and every such claim widens the answer. Running with none gives a
     * baseline whose behaviour is completely determined — see {@link #odds} — and each window
     * added afterwards can be scored against it.
     * <p>
     * <b>These five were read off the classifier by hand and are placeholders.</b> The bounds
     * came from a classifier since found unsound, and the {@code SEP} / {@code ALIGN} suffixes
     * are assumed rather than derived. Windows are to be populated from a training corpus
     * instead — see {@link #PAYING}.
     * <p>
     * An enum because the set is small, fixed, and worth being able to read as a list of what
     * has been acknowledged. It stops being an enum on the day they are derived rather than
     * chosen.
     * <p>
     * A constant would read:
     * <pre>
     *     W_4_0_L3_ALIGN("4-0 L3 ALIGN", 4, 0, new int[]{3}, 48.6, 89.4),
     * </pre>
     * naming the classifier window it came from, the arc it explains, the edges the leader
     * moves along while the turn is being set up, and where along the first of those the leader
     * has to be at the moment of the turn.
     */
    private enum Window {

        /**
         * A boid at the end of edge 4 leaves for edge 0 instead of coasting on to edge 2,
         * because another boid is part way down edge 1 and it turns to follow.
         * <p>
         * The bounds are the classifier window's own: the union of every row that is not
         * {@link SolverFacts#vacuous}, each shifted to the instant of the turn. The leader never
         * leaves edge 1 while the turn is being set up, so the path is one edge.
         * <p>
         * <b>The term is assumed, not derived.</b> Nothing yet works out whether separation or
         * cohesion-and-alignment dominated, so {@code ALIGN} here is a name rather than a
         * finding, and the label should be re-checked once the classifier reports it.
         */
        W_4_0_L1_ALIGN("4-0 L1 ALIGN", 4, 0, new int[]{1}, 35.73, 170.44),

        /**
         * A boid near the end of edge 2 is pushed onto edge 1 by one just ahead of it on edge 7,
         * close enough for separation to dominate.
         * <p>
         * Cheap to believe: the band is a seventh of edge 7, and edge 7 is stable, so a leader
         * standing there owes no account of its own and the search stops dead rather than
         * asking who led the leader.
         */
        W_2_1_L7_SEP("2-1 L7 SEP", 2, 1, new int[]{7}, -1.01, 13.26),

        /** Leader edge is stable, so no account is owed for it. */
        W_4_0_L2_SEP("4-0 L2 SEP", 4, 0, new int[]{2}, -0.62, 81.26),

        /** Its leader edge runs back through the very arc it explains. */
        W_4_0_L3_ALIGN("4-0 L3 ALIGN", 4, 0, new int[]{3}, 63.57, 113.70),

        W_2_1_L8_ALIGN("2-1 L8 ALIGN", 2, 1, new int[]{8}, 12.05, 36.92),
        ;

        /** Ties this back to the classifier window it was read off. */
        private final String label;

        /** The arc being explained: a boid on {@code from} that ends up on {@code keep}. */
        private final int from;
        private final int keep;

        /**
         * The edges the leader occupies while the suspect crosses the critical envelope, in
         * travel order. Bounds below are measured along {@code path[0]}.
         */
        private final int[] path;

        private final double baseLo;
        private final double baseHi;

        Window(String label, int from, int keep, int[] path, double baseLo, double baseHi) {
            this.label = label;
            this.from = from;
            this.keep = keep;
            this.path = path;
            this.baseLo = baseLo;
            this.baseHi = baseHi;
        }

        /**
         * Where the leader has to be now, given how far the suspect has come since the turn.
         * <p>
         * The suspect's tick along the edge it was led onto is very nearly the number of ticks
         * since the turn was induced, so the leader — which has been flying the whole time —
         * sits that much further along than it did at the moment itself. Everything beyond that
         * shift is {@link #spread}, and that is where the shape lives: a classifier window is a
         * fixed interval read off one moment, while a solver window is allowed to open up as
         * the evidence gets older.
         */
        double lo(double suspectTick) { return baseLo + suspectTick - spread(suspectTick); }

        double hi(double suspectTick) { return baseHi + suspectTick + spread(suspectTick); }

        /**
         * How much slack to allow either side, as a function of how stale the evidence is.
         * <p>
         * Flat zero for now. The clock is a fit rather than a measurement and its error grows
         * with the interval being measured, so this will not stay flat — but it should change
         * once, deliberately, with the effect measured, rather than being tuned per window.
         */
        double spread(double suspectTick) { return 0; }

        @Override
        public String toString() { return label; }
    }

    private static final Window[] NONE = new Window[0];

    /**
     * Which of the windows above this solver is actually using.
     * <p>
     * The enum is the set that has been written down; this is the set switched on. They are
     * separate because a window has to be measured before it can be judged, and measuring one
     * means running the corpus with it and without it — which is not something to do by editing
     * an enum and recompiling. What a window is worth is the true positives it recovers against
     * the true negatives it spends, and neither is predictable from how often it is the right
     * answer: a window fires whenever any boid falls inside its band, not only when that boid
     * was really the leader.
     */
    private final java.util.Set<Window> enabled;

    /** The windows this solver has been given, by label. Empty is the baseline. */
    public static List<String> acknowledged() {
        List<String> out = new ArrayList<>();
        for (Window w : Window.values()) out.add(w.label);
        return out;
    }

    /**
     * The windows this solver runs with.
     * <p>
     * <b>Empty by design, not by measurement.</b> Solver windows are meant to be populated from
     * a training corpus — only the windows that actually occur in it, over only the ranges that
     * occur in it. Several windows the critical-envelope analysis can derive will never appear
     * in any corpus, because reaching them requires the psyboid to act against its own
     * interest, and a window covering behaviour no psyboid exhibits is pure cost.
     * <p>
     * Pure cost because of what a window can do: it admits every boid inside its band, not only
     * the one that actually led. So a window can only ever <em>spend</em> true negatives, and
     * has to buy a true positive to be worth anything at all. How often a window is the right
     * answer says nothing about what it costs.
     * <p>
     * The corpus-derived population does not exist yet, and neither does a trustworthy corpus
     * to derive it from. Earlier grading figures were taken against a broken classifier and a
     * corpus since found unsound, and have been removed rather than carried forward.
     */
    private static final String[] PAYING = {};

    /** The windows measured to be worth having. */
    public UnstableEdgeClue() {
        this(PAYING);
    }

    /** Only the named windows, by label. An empty list is the no-window baseline. */
    public UnstableEdgeClue(String... labels) {
        this.enabled = java.util.EnumSet.noneOf(Window.class);
        for (String want : labels) {
            boolean found = false;
            for (Window w : Window.values()) {
                if (w.label.equals(want)) { enabled.add(w); found = true; }
            }
            if (!found) {
                throw new IllegalArgumentException("no window labelled \"" + want + "\"; have "
                        + acknowledged());
            }
        }
    }

    /** The windows this solver is using, by label. */
    public java.util.List<String> using() {
        java.util.List<String> out = new ArrayList<>();
        for (Window w : Window.values()) if (enabled.contains(w)) out.add(w.label);
        return out;
    }

    @Override
    public String name() { return "unstable-edge"; }

    /**
     * Relative odds, per boid, that it is the psyboid.
     * <p>
     * With no windows the answer is completely determined by which edges are unstable, which
     * makes it worth stating as a test rather than a description. No boid on an unstable edge
     * gives all ones — nothing needs explaining, so nothing is ruled out. Exactly one gives a
     * one for that boid and zeroes elsewhere: it is the only thing that can account for itself.
     * More than one gives all zeroes, because each demands to be the psyboid and there is only
     * one psyboid to go round.
     */
    @Override
    public int[] odds(SolverFacts facts, Sim.State state) {
        return solve(facts, state).odds();
    }

    /**
     * @param odds    per boid, its relative odds of being the psyboid
     * @param owed    per boid, the account its own position demanded; {@code odds} is these
     *                ANDed, so a boid missing from one row is a boid that row ruled out
     * @param nodes   questions asked, before the cache
     * @param cached  of those, ones already answered
     * @param deepest furthest back any boid had to be rebased, in ticks
     */
    public record Result(int[] odds, boolean[][] owed, long nodes, long cached, double deepest) {}

    public Result solve(SolverFacts facts, Sim.State state) {
        return solve(facts, state, null);
    }

    /**
     * Somewhere to send an account of the reasoning, one indented line per step.
     * <p>
     * The evaluation this feeds is about <em>why</em> a boid can be named, not only that it can,
     * so a solver that cannot say what it concluded from is half an answer. It is also the only
     * practical way to find which branch of a several-hundred-node search refused to close.
     */
    public interface Trace {
        void step(int depth, String message);
    }

    public Result solve(SolverFacts facts, Sim.State state, Trace trace) {
        return new Search(facts, state, enabled, trace).run();
    }

    /**
     * One arrangement, written as a rebasing.
     * <p>
     * Records compare array components by identity, which would make every node distinct and the
     * cache useless, so both halves of the contract are written out. Tick values compare bit for
     * bit on purpose: two nodes agreeing to the last bit are the same rebasing reached by a
     * different order of steps, which is exactly what the cache exists to collapse.
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

    /** One question about one boid at one arrangement, for the cache. */
    private record Ask(Node node, int boid) {}

    private static final class Search {

        private final SolverFacts f;
        private final Sim.State scene;
        private final int n;
        private final java.util.Set<Window> enabled;
        private final Trace trace;
        private final double[] base;

        private final Map<Ask, boolean[]> answered = new HashMap<>();
        private final Map<Integer, List<int[]>> routes = new HashMap<>();

        private long nodes, cached;
        private double deepest;
        private boolean noted;
        private int depth;

        Search(SolverFacts f, Sim.State scene, java.util.Set<Window> enabled, Trace trace) {
            this.enabled = enabled;
            this.f = f;
            this.scene = scene;
            this.n = scene.n;
            this.trace = trace;
            this.base = new double[n];
        }

        Result run() {
            int[] edge = new int[n];
            double[] tick = new double[n];
            for (int i = 0; i < n; i++) {
                edge[i] = f.edgeAt(scene.x[i], scene.y[i], scene.h[i]);
                tick[i] = f.tickAt(scene.x[i], scene.y[i], scene.h[i]);
                if (edge[i] < 0 || Double.isNaN(tick[i])) {
                    throw new IllegalStateException("boid " + i + " at (" + scene.x[i] + ","
                            + scene.y[i] + "," + scene.h[i] + ") is on no edge of " + f.map()
                            + "@" + f.hash() + " — these facts do not describe this map, or "
                            + "this arrangement never came from it");
                }
                base[i] = tick[i];
            }

            Node root = new Node(edge, tick);
            boolean[][] owed = new boolean[n][];
            boolean[] all = new boolean[n];
            Arrays.fill(all, true);
            for (int b = 0; b < n; b++) {
                owed[b] = state(root, b);
                for (int i = 0; i < n; i++) all[i] &= owed[b][i];
            }

            int[] odds = new int[n];
            for (int i = 0; i < n; i++) odds[i] = all[i] ? 1 : 0;
            return new Result(odds, owed, nodes, cached, deepest);
        }

        /**
         * What follows from this boid being where this arrangement puts it.
         * <p>
         * A boid on a stable edge could have been going round that loop since before anyone was
         * watching, so its position accuses nobody and the answer is every boid. Otherwise the
         * question is how it got there, and every edge that steps onto its own is a way it
         * could have — so each is asked and the answers are ORed, one arrival being enough.
         */
        private boolean[] state(Node node, int suspect) {
            int e = node.edge()[suspect];
            if (f.stable(e)) return every();

            Ask ask = new Ask(node, suspect);
            boolean[] done = answered.get(ask);
            if (done != null) { cached++; return done; }
            nodes++;
            budget(node);

            // Provisionally: reached again while still being computed, the honest answer is the
            // one that adds nothing to the OR above it. Lengths are positive so ticks strictly
            // increase and this should be unreachable; it is here for the map where it is not.
            answered.put(ask, none());

            boolean[] acc = none();
            depth++;
            for (int p : f.predecessors(e)) {
                or(acc, navigation(node, suspect, p));
                if (all(acc)) break;                    // an OR that holds for everyone is done
            }
            depth--;

            say("boid %d on edge %d at %.0f -> %s", suspect, e, node.tick()[suspect], show(acc));
            answered.put(ask, acc);
            return acc;
        }

        /**
         * What follows from this boid having arrived on its edge from {@code from}.
         * <p>
         * Coasting is free: nothing chose it, so the arrangement is simply re-read with the boid
         * measured from one crossing earlier and the same question asked again. A turn is not
         * free, and {@link #steered} is where it gets paid for.
         */
        private boolean[] navigation(Node node, int boid, int from) {
            int e = node.edge()[boid];
            if (f.straightTo()[from] == e) {
                return state(node.rebased(boid, from, node.tick()[boid] + f.length()[from]),
                        boid);
            }
            return steered(node, boid, from, e);
        }

        /**
         * What follows from this boid having been turned out of {@code from} onto {@code to}.
         * <p>
         * Windowless — the boid steered itself — is always one of the answers, and on its own it
         * names that boid and nobody else. Each window is another, and says instead that some
         * other boid was where it had to be. The answers are ORed, since any one of them would
         * do.
         */
        private boolean[] steered(Node node, int boid, int from, int to) {
            boolean[] acc = only(boid);                 // windowless: it steered itself
            double since = node.tick()[boid];
            Node backed = node.rebased(boid, from, since + f.length()[from]);

            depth++;
            for (Window w : windows(from, to)) {
                double lo = w.lo(since), hi = w.hi(since);
                for (int j = 0; j < n; j++) {
                    if (j == boid) continue;
                    for (double[] landing : landings(node, j, w, lo, hi)) {
                        // Three separate demands, and all of them have to hold: that the leader
                        // could have got to where the window wants it, that its being there is
                        // itself accountable, and that the suspect's own history up to the turn
                        // is accountable too. The last is easy to forget because explaining the
                        // turn feels like explaining the boid — but the turn happened on
                        // `from`, and how the boid came to be on `from` is a separate question
                        // that only disappears when `from` is stable. On dabeone it always is
                        // except for 5->6, so this costs nothing here and is load-bearing on
                        // any map whose steered arcs leave from an edge a boid cannot hold.
                        boolean[] chain = chain(node, j, landing);
                        if (!any(chain)) continue;
                        Node led = backed.rebased(j, w.path[0], landing[0]);
                        boolean[] both = state(led, j);
                        and(both, chain);
                        if (any(both)) and(both, state(led, boid));
                        if (any(both)) {
                            say("%s: boid %d led by boid %d at %.0f in %.0f..%.0f -> %s",
                                    w, boid, j, landing[0], lo, hi, show(both));
                        }
                        or(acc, both);
                        if (all(acc)) { depth--; return acc; }
                    }
                }
            }
            depth--;
            return acc;
        }

        /**
         * Every way this boid could be standing where the window wants a leader.
         * <p>
         * The search runs backwards over the edge graph to the <em>last</em> edge of the
         * leader's path, because that is the one the leader is on when the turn happens and so
         * the first the search meets coming back from now. Landing there, it is rebased forwards
         * along the path to the first edge, which is the coordinate the window's bounds are
         * written in.
         *
         * @return one entry per way, holding the rebased tick along {@code path[0]} followed by
         *         the edges walked through to get there
         */
        private List<double[]> landings(Node node, int leader, Window w, double lo, double hi) {
            int target = w.path[w.path.length - 1];
            double along = 0;
            for (int i = 0; i < w.path.length - 1; i++) along += f.length()[w.path[i]];

            List<double[]> out = new ArrayList<>();
            double at = node.tick()[leader];
            for (int[] route : routes(node.edge()[leader], target)) {
                double sum = at;
                for (int e : route) sum += f.length()[e];
                double where = sum + along;
                if (where < lo || where > hi) continue;
                double[] entry = new double[route.length + 1];
                entry[0] = where;
                for (int i = 0; i < route.length; i++) entry[i + 1] = route[i];
                out.add(entry);
            }
            return out;
        }

        /**
         * Whether this boid could have made the backwards journey, and who that implicates.
         * <p>
         * Each crossing on the way is accounted for separately and the accounts are ANDed. The
         * AND is lazy and exactly so: every account contains the travelling boid, because it
         * could always have steered itself, so an accumulator holding only that boid can never
         * be changed by another term and the rest of the journey need not be read at all.
         */
        private boolean[] chain(Node node, int leader, double[] landing) {
            boolean[] acc = every();
            Node walk = node;
            for (int k = 1; k < landing.length; k++) {
                int onto = (int) landing[k];
                int was = walk.edge()[leader];
                boolean[] step = f.straightTo()[onto] == was
                        ? every()                       // coasted; nothing chose it
                        : steered(walk, leader, onto, was);
                and(acc, step);
                if (only(leader, acc)) return acc;       // nothing left for later terms to say
                walk = walk.rebased(leader, onto, walk.tick()[leader] + f.length()[onto]);
            }
            return acc;
        }

        /** The solver's windows for one arc. Empty until windows are acknowledged one by one. */
        private Window[] windows(int from, int to) {
            List<Window> out = null;
            for (Window w : enabled) {
                if (w.from != from || w.keep != to) continue;
                if (out == null) out = new ArrayList<>();
                out.add(w);
            }
            return out == null ? NONE : out.toArray(NONE);
        }

        /**
         * Every backwards route between two edges, as the edges stepped onto in order.
         * <p>
         * Capped by total length rather than by hops, since length is what the bounds are
         * written in and a route longer than the cap could not satisfy any of them.
         */
        private List<int[]> routes(int from, int to) {
            int key = from * f.edges() + to;
            List<int[]> cache = routes.get(key);
            if (cache != null) return cache;
            List<int[]> found = new ArrayList<>();
            if (from == to) found.add(new int[0]);
            walk(from, 0, to, new ArrayList<>(), found);
            routes.put(key, found);
            return found;
        }

        private void walk(int at, double sum, int to, List<Integer> so, List<int[]> found) {
            if (found.size() > ROUTE_LIMIT) {
                throw new IllegalStateException("more than " + ROUTE_LIMIT + " backwards routes "
                        + "within " + ROUTE_CAP + " ticks; this map's edge graph is too tangled "
                        + "for the leader search as written");
            }
            for (int p : f.predecessors(at)) {
                double next = sum + f.length()[p];
                if (next > ROUTE_CAP) continue;
                so.add(p);
                if (p == to) {
                    int[] route = new int[so.size()];
                    for (int i = 0; i < route.length; i++) route[i] = so.get(i);
                    found.add(route);
                }
                walk(p, next, to, so, found);
                so.removeLast();
            }
        }

        private void budget(Node node) {
            double deep = 0;
            for (int b = 0; b < n; b++) deep = Math.max(deep, node.tick()[b] - base[b]);
            deepest = Math.max(deepest, deep);
            if (deep > STOP_DEPTH) {
                throw new Solver.TooDeep("the search reached " + String.format("%.0f", deep)
                        + " ticks of history without settling; either the map has a loop of "
                        + "steered arcs the windows keep open, or a window is far too wide");
            }
            if (deep > NOTE_DEPTH && !noted) {
                noted = true;
                System.out.printf("  (unstable-edge search is %.0f ticks deep)%n", deep);
            }
        }

        private void say(String format, Object... args) {
            if (trace != null) trace.step(depth, String.format(format, args));
        }

        private boolean[] every() {
            boolean[] b = new boolean[n];
            Arrays.fill(b, true);
            return b;
        }

        private boolean[] none() { return new boolean[n]; }

        private boolean[] only(int boid) {
            boolean[] b = new boolean[n];
            b[boid] = true;
            return b;
        }

        /** Whether this set holds the given boid and nothing else. */
        private boolean only(int boid, boolean[] bits) {
            for (int i = 0; i < n; i++) if (bits[i] != (i == boid)) return false;
            return true;
        }

        private void or(boolean[] into, boolean[] add) {
            for (int i = 0; i < n; i++) into[i] |= add[i];
        }

        private void and(boolean[] into, boolean[] with) {
            for (int i = 0; i < n; i++) into[i] &= with[i];
        }

        private boolean any(boolean[] bits) {
            for (boolean b : bits) if (b) return true;
            return false;
        }

        private boolean all(boolean[] bits) {
            for (boolean b : bits) if (!b) return false;
            return true;
        }

        private String show(boolean[] bits) {
            StringBuilder s = new StringBuilder("{");
            for (int i = 0; i < n; i++) if (bits[i]) s.append(s.length() > 1 ? "," : "").append(i);
            return s.append('}').toString();
        }
    }
}
