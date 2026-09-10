package boids;

import java.util.Arrays;

/**
 * An override that carries a <b>route</b> rather than a schedule: one target exit per edge, and a
 * turn asked for only on the ticks where coasting would leave it.
 * <p>
 * <b>What a held turn cannot do.</b> A held turn is a turn at an absolute tick, so a plan made of
 * them has to know in advance exactly when the boid will arrive somewhere. Under flocking it will
 * not: neighbours push it a few ticks either way, and on a map whose edges run 750 ticks the
 * accumulated slip is larger than the window the turn has to land in. The search that emitted
 * them covered that with eight ticks of margin on each end, which worked on dabeone's
 * hundred-tick edges and was hopeless on plait's. Both were deleted on 2026-09-08.
 * <p>
 * A pilot has no schedule to slip against. Each tick it asks where the boid actually is, what it
 * would take to leave that edge by the target exit, and whether coasting does it. Nearly always
 * coasting does, and the pilot asks for nothing — <b>an override that is inert on 99% of ticks is
 * the point</b>, because those are exactly the ticks on which the boid is indistinguishable from
 * an ordinary one.
 *
 * <h2>Why one step of lookahead is the whole of navigation</h2>
 * Every point of an edge has the same successor set, so from <em>every</em> state of {@code e} the
 * target {@code g} is reachable. Any move that keeps the boid on {@code e} therefore preserves
 * that, and if all three moves left {@code e} for wrong edges then {@code g} was never reachable
 * from there — a contradiction. <b>So a rule that only avoids stepping onto a wrong edge cannot
 * get stuck, and needs no plan beyond the next tick.</b>
 * <p>
 * This replaced a 0-1 BFS over each edge computing the fewest non-straight ticks to the exit. That
 * worked and was unnecessary, and worse, it framed navigation as something that could be done
 * badly: it had a "no route from here" case that <b>returned silently</b>. There is no such case.
 * If no turn is safe the decomposition is wrong, and this throws.
 * <p>
 * <b>Nothing here may assume a hand of turn.</b> The axiom does not promise that holding right, or
 * left, or straight reaches anything in particular — a specific alternation may be required. An
 * override that holds one turn for a fixed span is therefore not navigation, and can miss an exit
 * the decomposition guarantees is reachable.
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
    private final long from;
    private final long to;

    private EdgePilot(int psyboid, int[] route, NavMap map, SolverFacts facts,
                      long from, long to) {
        this.psyboid = psyboid;
        this.route = route;
        this.map = map;
        this.facts = facts;
        this.from = from;
        this.to = to;
    }

    /**
     * Builds a pilot for one route, over all time.
     *
     * @param route one target edge per edge index, or -1 to leave that edge to coasting
     */
    public static EdgePilot of(int psyboid, int[] route, NavMap map, SolverFacts f,
                               EdgeDecomposition.Labelling l) {
        return of(psyboid, route, map, f, l, Long.MIN_VALUE, Long.MAX_VALUE);
    }

    /** The same, confined to {@code [from, to)} so a plan can hand control back. */
    public static EdgePilot of(int psyboid, int[] route, NavMap map, SolverFacts f,
                               EdgeDecomposition.Labelling l, long from, long to) {
        return new EdgePilot(psyboid, route.clone(), map, f, from, to);
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
        return new EdgePilot(psyboid, route, map, f, from, to);
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
        if (edge < 0 || edge >= route.length) return;
        int target = route[edge];
        if (target < 0 || target == edge) return;

        // Leave the flock alone if what it already wants is safe. This is the whole of the
        // difference between a psyboid and a robot: on the great majority of ticks the rules'
        // own request keeps the boid on its edge, and overriding it there would spend a tick,
        // make the boid visible, and change nothing about where it ends up.
        if (safe(map.successor(state, clamp(movement.movement[i])), edge, target)) return;

        for (int turn : ORDER) {
            if (safe(map.successor(state, turn), edge, target)) {
                movement.movement[i] = turn;
                return;
            }
        }
        throw new IllegalStateException(String.format(
                "no turn from (%d,%d,%d) on edge %d stays on it or reaches edge %d, which the "
                        + "decomposition says is reachable from every state of %d. Either the "
                        + "edge axiom is violated here or %d is not really an arc out of %d.",
                x, y, h, edge, target, edge, target, edge));
    }

    /**
     * Straight first, then left, then right.
     * <p>
     * Order matters only for which of several safe turns is taken, and straight is preferred
     * because it is the one the collision layer is least likely to alter and the one that spends
     * nothing. Left before right is arbitrary and deliberately so — <b>nothing here may assume a
     * particular hand of turn works</b>, which is exactly the assumption a held-turn override
     * makes and the reason one can miss an exit the decomposition guarantees.
     */
    private static final int[] ORDER = {0, -1, 1};

    /** Whether stepping to {@code u} keeps the plan alive. */
    private boolean safe(int u, int edge, int target) {
        if (u < 0) return false;
        int on = facts.edgeOf()[u];
        return on == edge || on == target;
    }

    private static int clamp(int turn) { return Math.max(-1, Math.min(1, turn)); }

    @java.lang.Override
    public String toString() {
        return "EdgePilot" + Arrays.toString(route);
    }
}
