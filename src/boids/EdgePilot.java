package boids;

import java.util.Arrays;

/**
 * An override that carries a <b>route</b> rather than a schedule: one target exit per edge, and a
 * turn asked for only on the ticks where coasting would leave it.
 * <p>
 * <b>What a held turn cannot do.</b> {@link HeldTurn} is a turn at an absolute tick, so a plan
 * made of them has to know in advance exactly when the boid will arrive somewhere. Under flocking
 * it will not: neighbours push it a few ticks either way, and on a map whose edges run 750 ticks
 * the accumulated slip is larger than the window the turn has to land in. The existing search
 * covers that with eight ticks of margin on each end, which works on dabeone's hundred-tick edges
 * and is hopeless on plait's.
 * <p>
 * A pilot has no schedule to slip against. Each tick it asks where the boid actually is, what it
 * would take to leave that edge by the target exit, and whether coasting does it. Nearly always
 * coasting does, and the pilot asks for nothing — <b>an override that is inert on 99% of ticks is
 * the point</b>, because those are exactly the ticks on which the boid is indistinguishable from
 * an ordinary one.
 *
 * <h2>Where the steering comes from</h2>
 * {@link EdgeNavigation#steerCostTo} is a 0-1 BFS over the states of one edge giving, per state,
 * the fewest non-straight ticks needed to leave by a chosen exit. That is the whole navigation
 * problem solved in advance, and it makes the per-tick decision a table lookup and three
 * successor queries. It also makes the pilot <b>self-correcting</b>: pushed off the cheapest line,
 * it reads the cost from wherever it now is rather than from where it expected to be.
 * <p>
 * <b>Only the edges that need it.</b> A cost table is built for an edge only where the route's
 * exit differs from {@link SolverFacts#straightTo} — everywhere else coasting is the route. On
 * both maps in the project that is a single edge, so the pilot costs one BFS to set up.
 *
 * <h2>Idempotence</h2>
 * A pilot writes a turn or writes nothing, and writing nothing leaves whatever the flocking rules
 * asked for. Two pilots on the same boid with the same route therefore agree; two with different
 * routes fight, and the later one in the array wins, which is the same rule that already governs
 * two held turns overlapping.
 */
public final class EdgePilot implements PsyboidOverride {

    /** Costs are per state and small, so a byte holds them and an edge's table stays cheap. */
    private static final int UNREACHABLE = -1;

    private final int psyboid;
    private final int[] route;
    private final NavMap map;
    private final SolverFacts facts;
    private final int[][] cost;
    private final long from;
    private final long to;

    private EdgePilot(int psyboid, int[] route, NavMap map, SolverFacts facts, int[][] cost,
                      long from, long to) {
        this.psyboid = psyboid;
        this.route = route;
        this.map = map;
        this.facts = facts;
        this.cost = cost;
        this.from = from;
        this.to = to;
    }

    /**
     * Builds a pilot for one route, over all time.
     *
     * @param route one target edge per edge index, or -1 to leave that edge to coasting
     */
    public static EdgePilot of(int psyboid, int[] route, NavMap map, SolverFacts f,
                               Pipeline.Labelling l) {
        return of(psyboid, route, map, f, l, Long.MIN_VALUE, Long.MAX_VALUE);
    }

    /** The same, confined to {@code [from, to)} so a plan can hand control back. */
    public static EdgePilot of(int psyboid, int[] route, NavMap map, SolverFacts f,
                               Pipeline.Labelling l, long from, long to) {
        int[][] cost = new int[f.edges()][];
        for (int e = 0; e < f.edges(); e++) {
            int target = route[e];
            // Coasting already goes there, or the route says nothing about this edge.
            if (target < 0 || target == f.straightTo()[e]) continue;
            cost[e] = EdgeNavigation.steerCostTo(map, l.live(), l.liveCount(), l.edge(),
                    f.edges(), e, target);
        }
        return new EdgePilot(psyboid, route.clone(), map, f, cost, from, to);
    }

    @java.lang.Override
    public int psyboid() { return psyboid; }

    @java.lang.Override
    public long from() { return from; }

    @java.lang.Override
    public long to() { return to; }

    /**
     * True whenever the route asks for anything other than coasting anywhere.
     * <p>
     * Deliberately a property of the route rather than of what happened: whether a given flight
     * ever needed a turn is a fact about that flight, and {@code asks} is asked of the plan.
     */
    @java.lang.Override
    public boolean asks() {
        for (int e = 0; e < route.length; e++) {
            if (route[e] >= 0 && route[e] != facts.straightTo()[e]) return true;
        }
        return false;
    }

    /** The route, so a caller can see where the pilot is trying to go. */
    public int[] route() { return route.clone(); }

    @java.lang.Override
    public String label() {
        StringBuilder s = new StringBuilder("q").append(psyboid).append(':');
        for (int e = 0; e < route.length; e++) {
            s.append(e == 0 ? "" : ".").append(route[e]);
        }
        if (from != Long.MIN_VALUE || to != Long.MAX_VALUE) {
            s.append('@').append(from).append('-').append(to);
        }
        return s.toString();
    }

    /** Inverse of {@link #label()}. Needs the map, because a route is a fact about one. */
    static EdgePilot parsePilot(String label, NavMap map, SolverFacts f) {
        int colon = label.indexOf(':');
        int at = label.indexOf('@');
        int psyboid = Integer.parseInt(label, 1, colon, 10);
        String body = at < 0 ? label.substring(colon + 1) : label.substring(colon + 1, at);
        String[] parts = body.split("\\.");
        int[] route = new int[parts.length];
        for (int i = 0; i < parts.length; i++) route[i] = Integer.parseInt(parts[i]);

        long from = Long.MIN_VALUE, to = Long.MAX_VALUE;
        if (at >= 0) {
            int dash = label.indexOf('-', at + 2);
            from = Long.parseLong(label, at + 1, dash, 10);
            to = Long.parseLong(label, dash + 1, label.length(), 10);
        }
        int[][] cost = new int[f.edges()][];
        for (int e = 0; e < f.edges() && e < route.length; e++) {
            if (route[e] < 0 || route[e] == f.straightTo()[e]) continue;
            cost[e] = EdgeNavigation.steerCostTo(map, live(f), liveCount(f), edgeOf(f),
                    f.edges(), e, route[e]);
        }
        return new EdgePilot(psyboid, route, map, f, cost, from, to);
    }

    // The BFS wants the live list and the per-state edge labels, both of which the facts already
    // carry; rebuilding them here keeps a pilot constructible from a label plus the map alone.
    private static int[] edgeOf(SolverFacts f) {
        short[] packed = f.edgeOf();
        int[] out = new int[packed.length];
        for (int i = 0; i < packed.length; i++) out[i] = packed[i];
        return out;
    }

    private static int[] live(SolverFacts f) {
        short[] packed = f.edgeOf();
        int n = 0;
        for (short s : packed) if (s >= 0) n++;
        int[] out = new int[n];
        int at = 0;
        for (int i = 0; i < packed.length; i++) if (packed[i] >= 0) out[at++] = i;
        return out;
    }

    private static int liveCount(SolverFacts f) {
        int n = 0;
        for (short s : f.edgeOf()) if (s >= 0) n++;
        return n;
    }

    /**
     * The turn, if one is needed.
     * <p>
     * Reads the flock's <em>current</em> arrays, so it sees the boid where it actually is rather
     * than where a plan expected it. Silent — writing nothing — on every tick where coasting stays
     * on the route, which is nearly all of them.
     */
    @java.lang.Override
    public void calculate(Movement movement, int i) {
        if (i != psyboid || !actsAt((long) movement.boids.tick())) return;
        BoidArray boids = movement.boids;
        int x = boids.x()[i], y = boids.y()[i], h = boids.h()[i];
        int state = facts.state(x, y, h);
        if (state < 0 || state >= facts.edgeOf().length) return;
        int edge = facts.edgeOf()[state];
        if (edge < 0 || edge >= cost.length || cost[edge] == null) return;

        int here = cost[edge][state];
        if (here == EdgeNavigation.NEVER) return;   // off the route; nothing useful to ask for
        if (here == 0) return;                      // coasting takes the exit: stay quiet

        // Turn now rather than at the last moment. The cost is the fewest non-straight ticks still
        // needed, and whenever it is positive some turn reduces it by one — so the turns can be
        // spent immediately, at no extra cost in ticks, leaving the rest of the edge as slack for
        // the flock to push the boid around in.
        //
        // Waiting was the first design and it measurably lost: in a flock this pilot flew 56% of
        // the price gain on dabnt and 76% on dabeone against 91% for a scheduled held turn, while
        // alone it flew 94-100%. Being inert until steering is strictly necessary means arriving
        // at "strictly necessary" already displaced.
        int best = 0, bestCost = Integer.MAX_VALUE;
        for (int turn = -1; turn <= 1; turn += 2) {
            int next = at(map.successor(state, turn), edge, cost[edge]);
            if (next == EdgeNavigation.NEVER) continue;
            if (next < bestCost) { bestCost = next; best = turn; }
        }
        if (bestCost < here) movement.movement[i] = best;
    }

    /**
     * The cost at a successor, or {@link EdgeNavigation#NEVER}.
     * <p>
     * A successor that has already left the edge has left it <em>by the route's exit</em> if it
     * landed on the target — that is a cost of zero, the goal reached — and by something else
     * otherwise, which is unreachable as far as this table is concerned.
     */
    private int at(int state, int edge, int[] table) {
        if (state < 0) return EdgeNavigation.NEVER;
        int on = facts.edgeOf()[state];
        if (on == edge) return table[state];
        return on == route[edge] ? 0 : EdgeNavigation.NEVER;
    }

    @java.lang.Override
    public String toString() {
        return "EdgePilot" + Arrays.toString(route);
    }
}
