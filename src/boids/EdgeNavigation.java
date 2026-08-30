package boids;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * What a boid entering an edge does next, and what it costs to make it do otherwise.
 * <p>
 * A decomposition says which states belong together; it does not say what a boid actually
 * does with them. Two questions here, both about movement. First the free one: come in at
 * the edge's boundary, try to hold left, straight or right, and see where you come out.
 * Then the priced one: for each way out of the edge, how many ticks does something have to
 * steer to leave that way.
 * <p>
 * Only <b>inbound</b> points are asked. A point is inbound when something on another edge
 * steps into it, so it is a way the edge is entered rather than somewhere partway along it.
 * Interior points would answer for wherever they happen to sit and the answers would
 * average into noise; the boundary is where a boid's history actually puts it.
 * <p>
 * <em>Tries to</em> is the operative word. {@link NavMap#constrainTurn} is in the loop for
 * every real boid, so one asked to turn into a wall turns as far as it safely can and keeps
 * flying. That is the behaviour being measured, and it is why holding a turn is not the
 * same as flying a circle: against a wall the veto converts most of the request into
 * straight, and a hold can usefully run far longer than the {@code TURNS} ticks a free boid
 * would need to come back round to its own heading.
 * <p>
 * The price is counted on the <em>request</em>, before the veto sees it. A tick spent
 * asking for a turn that the map then refuses is a tick spent all the same, which is what a
 * steering budget actually pays for. It costs nothing to be right: a refused turn lands
 * exactly where straight would have, so asking for it is never worth a tick and the
 * cheapest route never does.
 * <p>
 * Everything is a fixed point over the navmap rather than a set of simulated runs. Holding
 * one turn makes the state graph a function, so where a hold ends up is one memoised pass
 * over it. The price is a shortest path where asking for straight is free and asking for a
 * turn costs one, which makes it a 0-1 traversal: linear in states, exact, and with no
 * assumption that the steering has to be consecutive or all in one direction.
 */
public final class EdgeNavigation {
    private EdgeNavigation() {}

    /** No amount of steering gets there. */
    private static final int NEVER = Integer.MAX_VALUE;

    /**
     * Where the inbound points of one edge end up while trying to hold one turn.
     *
     * @param to       the edge arrived at, or -1 if the answer is mixed or nothing left
     * @param outcomes every edge any inbound point reached, as a bitmask
     * @param pure     whether all inbound points agreed
     * @param stuck    inbound points that never leave the edge at all under this turn
     */
    public record Hold(int to, long outcomes, boolean pure, int stuck) {}

    /**
     * What it costs to leave an edge a particular way.
     *
     * @param to          the edge left for
     * @param minTicks    fewest ticks a boid must ask for something other than straight, over
     *                    the inbound points; the ticks need not be consecutive or agree in
     *                    direction. -1 if no inbound point can get there at all
     * @param maxTicks    the same, from whichever inbound point finds it hardest
     * @param unreachable inbound points that cannot leave this way however they steer
     */
    public record Exit(int to, int minTicks, int maxTicks, int unreachable) {}

    /** @param inbound points on this edge that something on another edge steps into */
    public record EdgeNav(int edge, int inbound, Hold left, Hold straight, Hold right,
                          List<Exit> exits) {}

    /**
     * Two properties of an edge that depend on where unsteered travel goes, not on geometry.
     *
     * @param stable  unsteered travel from this edge comes back to it without ever scoring,
     *                so a boid left alone here stays on a loop
     * @param scoring a boid here has scored, or cannot avoid scoring
     */
    public record Properties(boolean[] stable, boolean[] scoring) {}

    /**
     * Which edges are stable and which are scoring.
     * <p>
     * Scoring is a least fixed point of three rules. An edge holding scoring states is
     * scoring outright. An edge whose unsteered travel leads somewhere scoring is scoring,
     * because a boid left alone there ends up scoring whether it meant to or not. And an
     * edge every way into which comes from somewhere scoring is scoring, because anything
     * arriving has already done it. The last rule needs at least one way in: an edge nothing
     * reaches satisfies "all of them" vacuously without that guard, and would be marked for
     * having no history rather than a scoring one.
     * <p>
     * Stability is then a cycle in the unsteered map. Holding straight makes the edge graph
     * a function, so following it from an edge either comes back round to that edge or never
     * does, and only an edge on such a loop that touches no scoring states is somewhere a
     * boid can be left indefinitely. An edge whose unsteered travel is not one single place
     * is never stable — there is no "left alone" behaviour to speak of.
     *
     * @param scoringStates how many states of each edge lie in the scoring region
     * @param straightTo    where unsteered travel leads, per edge, or -1 if not one place
     * @param arcs          one bitmask per edge of the edges it can step to
     */
    public static Properties classify(int[] scoringStates, int[] straightTo, long[] arcs,
                                      int edges) {
        boolean[] scoring = new boolean[edges];
        for (int e = 0; e < edges; e++) scoring[e] = scoringStates[e] > 0;
        for (boolean changed = true; changed; ) {
            changed = false;
            for (int e = 0; e < edges; e++) {
                if (scoring[e]) continue;
                if (straightTo[e] >= 0 && scoring[straightTo[e]]) { scoring[e] = true; changed = true; continue; }
                boolean any = false, all = true;
                for (int f = 0; f < edges; f++) {
                    if ((arcs[f] & (1L << e)) == 0) continue;
                    any = true;
                    if (!scoring[f]) { all = false; break; }
                }
                if (any && all) { scoring[e] = true; changed = true; }
            }
        }

        boolean[] stable = new boolean[edges];
        for (int e = 0; e < edges; e++) {
            int at = e;
            for (int hops = 0; hops <= edges; hops++) {
                if (scoringStates[at] > 0 || straightTo[at] < 0) break;
                at = straightTo[at];
                if (at == e) { stable[e] = true; break; }
            }
        }
        return new Properties(stable, scoring);
    }

    public static EdgeNav[] analyse(NavMap map, int[] live, int liveCount, int[] edge, int edges) {
        List<List<Integer>> inbound = inbound(map, live, liveCount, edge, edges);
        int[][] byEdge = byEdge(live, liveCount, edge, edges);

        int[] straightOut = firstOut(map, edge, live, liveCount, 0);
        int[] leftOut = firstOut(map, edge, live, liveCount, -1);
        int[] rightOut = firstOut(map, edge, live, liveCount, 1);

        Reverse rev = reverse(map, live, liveCount, edge.length);
        int[] cost = new int[edge.length];
        Frontier now = new Frontier(), next = new Frontier();

        EdgeNav[] out = new EdgeNav[edges];
        for (int e = 0; e < edges; e++) {
            List<Integer> points = inbound.get(e);
            List<Exit> exits = new ArrayList<>();
            for (int f : exitsOf(map, edge, byEdge[e], e)) {
                steerCost(map, rev, edge, byEdge[e], e, f, cost, now, next);
                exits.add(price(f, points, cost));
            }
            out[e] = new EdgeNav(e, points.size(), gather(points, leftOut),
                    gather(points, straightOut), gather(points, rightOut), exits);
        }
        return out;
    }

    /**
     * The points of each edge that something on another edge steps into.
     * <p>
     * Found forwards rather than by inverting the transition relation, because a step counts
     * only if the veto allows it and that is cheapest to ask in the direction the boid
     * actually travels.
     */
    private static List<List<Integer>> inbound(NavMap map, int[] live, int liveCount,
                                               int[] edge, int edges) {
        int turns = Params.TURNS, w = map.width();
        List<List<Integer>> inbound = new ArrayList<>();
        for (int e = 0; e < edges; e++) inbound.add(new ArrayList<>());
        boolean[] seen = new boolean[edge.length];
        for (int i = 0; i < liveCount; i++) {
            int s = live[i];
            int d = s % turns, cell = s / turns, x = cell % w, y = cell / w;
            for (int t = -1; t <= 1; t++) {
                if (map.constrainTurn(x, y, d, t) != t) continue;   // not a legal transition
                int u = step(map, s, t);
                if (u < 0 || edge[u] == edge[s] || seen[u]) continue;
                seen[u] = true;
                inbound.get(edge[u]).add(u);
            }
        }
        return inbound;
    }

    private static int[][] byEdge(int[] live, int liveCount, int[] edge, int edges) {
        int[] count = new int[edges];
        for (int i = 0; i < liveCount; i++) count[edge[live[i]]]++;
        int[][] byEdge = new int[edges][];
        for (int e = 0; e < edges; e++) byEdge[e] = new int[count[e]];
        int[] fill = new int[edges];
        for (int i = 0; i < liveCount; i++) {
            int s = live[i], e = edge[s];
            byEdge[e][fill[e]++] = s;
        }
        return byEdge;
    }

    /** No turn reaches this edge from that one, so the pair is not an exit at all. */
    public static final int NO_EXIT = Integer.MIN_VALUE;

    /**
     * Which turn a crossing between two edges counts as: {@code -1} left, {@code 0} straight,
     * {@code +1} right.
     * <p>
     * <b>A property of the pair of edges, not of what the boid was doing at the moment it
     * crossed.</b> On dabeone {@code 4 -> 0} is a right exit and {@code 4 -> 2} a straight one,
     * and that stays true whether the boid arrived at the boundary mid-turn, holding straight,
     * or fighting a wall that vetoed half its request. Reading the classification off the
     * instantaneous steering instead is how a boid deliberately turned a corner over thirty
     * ticks ends up looking like it went straight: by the time it reaches the boundary the turn
     * is long since made, and the last tick before crossing asks for nothing in particular.
     * <p>
     * Straight is applied last so it wins where more than one turn reaches the same edge. A
     * destination unsteered travel can reach is a straight exit by definition; that some
     * steering also arrives there does not make it a turn.
     */
    public static int[][] exitTurns(EdgeNav[] navs, int edges) {
        int[][] turn = new int[edges][edges];
        for (int[] row : turn) Arrays.fill(row, NO_EXIT);
        for (int e = 0; e < edges; e++) {
            mark(turn[e], navs[e].left(), -1);
            mark(turn[e], navs[e].right(), 1);
            mark(turn[e], navs[e].straight(), 0);
        }
        return turn;
    }

    private static void mark(int[] row, Hold hold, int turn) {
        for (int f = 0; f < row.length; f++) {
            if ((hold.outcomes() & (1L << f)) != 0) row[f] = turn;
        }
    }

    /**
     * The edge graph: one bitmask per edge of the edges it can step to, itself excluded.
     * <p>
     * The arcs are what says which transitions exist at all, and comparing them against
     * {@code straightTo} is what says which of those unsteered travel does not account for —
     * so this is the cheap census of the turns that need explaining. Self-arcs are left out
     * because staying on an edge is not a transition anything has to explain.
     */
    public static long[] arcs(NavMap map, int[] live, int liveCount, int[] edge, int edges) {
        long[] out = new long[edges];
        for (int i = 0; i < liveCount; i++) {
            int s = live[i];
            for (int t = -1; t <= 1; t++) {
                int u = step(map, s, t);
                if (u >= 0 && edge[u] != edge[s] && edge[u] >= 0) out[edge[s]] |= 1L << edge[u];
            }
        }
        return out;
    }

    /** Every edge one step from this one. A refused turn lands where another request does,
     *  so post-veto successors are exactly the legal transitions and this matches the arcs. */
    private static int[] exitsOf(NavMap map, int[] edge, int[] states, int home) {
        long mask = 0;
        for (int s : states) {
            for (int t = -1; t <= 1; t++) {
                int u = step(map, s, t);
                if (u >= 0 && edge[u] != home && edge[u] < 64) mask |= 1L << edge[u];
            }
        }
        int n = Long.bitCount(mask), k = 0;
        int[] out = new int[n];
        for (int f = 0; f < 64; f++) if ((mask & (1L << f)) != 0) out[k++] = f;
        return out;
    }

    /** One state on, with the veto resolving the request; -1 if there is nowhere to go. */
    private static int step(NavMap map, int s, int turn) {
        int turns = Params.TURNS, w = map.width();
        int d = s % turns, cell = s / turns, x = cell % w, y = cell / w;
        int got = map.constrainTurn(x, y, d, turn);
        int nd = Math.floorMod(d + got, turns);
        int nx = x + map.stepX(nd), ny = y + map.stepY(nd);
        if (nx < 0 || ny < 0 || nx >= w || ny >= map.height() || !map.alive(nx, ny, nd)) return -1;
        return (nx + ny * w) * turns + nd;
    }

    /**
     * For every live state, the first edge other than its own that holding {@code turn}
     * reaches, or -1 where it never leaves.
     * <p>
     * A chain walk rather than recursion, since a hold can run for thousands of ticks before
     * it crosses out. Every state on the walk belongs to the same edge — the walk stops at
     * the moment it would leave — so they all share one answer, and a chain that closes on
     * itself is a boid that never gets out.
     */
    private static int[] firstOut(NavMap map, int[] edge, int[] live, int liveCount, int turn) {
        int[] result = new int[edge.length];
        byte[] mark = new byte[edge.length];             // 0 fresh, 1 on this walk, 2 settled
        List<Integer> path = new ArrayList<>();
        for (int i = 0; i < liveCount; i++) {
            if (mark[live[i]] != 0) continue;
            path.clear();
            int cur = live[i], value;
            while (true) {
                if (mark[cur] == 1) { value = -1; break; }             // closed on itself
                if (mark[cur] == 2) { value = result[cur]; break; }
                mark[cur] = 1;
                path.add(cur);
                int u = step(map, cur, turn);
                if (u < 0) { value = -1; break; }
                if (edge[u] != edge[cur]) { value = edge[u]; break; }
                cur = u;
            }
            for (int p : path) { result[p] = value; mark[p] = 2; }
        }
        return result;
    }

    /** The three requests, reversed, as CSR over state ids, carrying what each request costs. */
    private record Reverse(int[] start, int[] from, byte[] cost) {}

    private static Reverse reverse(NavMap map, int[] live, int liveCount, int n) {
        int[] start = new int[n + 1];
        for (int i = 0; i < liveCount; i++) {
            for (int t = -1; t <= 1; t++) {
                int u = step(map, live[i], t);
                if (u >= 0) start[u + 1]++;
            }
        }
        for (int i = 0; i < n; i++) start[i + 1] += start[i];
        int[] from = new int[start[n]];
        byte[] cost = new byte[start[n]];
        int[] cursor = new int[n];
        for (int i = 0; i < liveCount; i++) {
            int s = live[i];
            for (int t = -1; t <= 1; t++) {
                int u = step(map, s, t);
                if (u < 0) continue;
                int at = start[u] + cursor[u]++;
                from[at] = s;
                cost[at] = (byte) (t == 0 ? 0 : 1);
            }
        }
        return new Reverse(start, from, cost);
    }

    /** A growable list of states at one price. */
    private static final class Frontier {
        int[] items = new int[1024];
        int size;

        void clear() { size = 0; }

        void add(int s) {
            if (size == items.length) items = Arrays.copyOf(items, size * 2);
            items[size++] = s;
        }
    }

    /**
     * For every state of edge {@code home}, the fewest ticks a boid must ask for something
     * other than straight in order to leave by {@code target}.
     * <p>
     * Backwards from the exit, because the answer wanted is for every state at once rather
     * than from one start. Asking for straight is free and asking for a turn costs one, so
     * the search runs a price at a time: settle everything reachable for what is already
     * paid, then spend one tick to open the next band. That is what keeps it linear — a
     * general shortest path would need a heap to order two distinct costs, and with only
     * two there is nothing to order.
     * <p>
     * The turn ticks fall wherever the route wants them. Nothing requires them to be
     * consecutive, to come at the start, or to agree in direction, so an exit reached by
     * turning left once early and right once late costs two like any other pair.
     */
    private static void steerCost(NavMap map, Reverse rev, int[] edge, int[] states, int home,
                                  int target, int[] cost, Frontier now, Frontier next) {
        for (int s : states) cost[s] = NEVER;
        now.clear();
        next.clear();
        for (int s : states) {
            for (int t = -1; t <= 1; t++) {
                int u = step(map, s, t);
                if (u < 0 || edge[u] != target) continue;
                int price = t == 0 ? 0 : 1;
                if (price < cost[s]) cost[s] = price;
            }
            if (cost[s] == 0) now.add(s);
            else if (cost[s] == 1) next.add(s);
        }

        for (int price = 0; now.size > 0 || next.size > 0; price++) {
            // Free moves first, to a fixed point: anything that can reach this band by
            // asking for straight belongs in it, including states only just added.
            for (int i = 0; i < now.size; i++) {
                int u = now.items[i];
                if (cost[u] != price) continue;                    // superseded by a cheaper route
                for (int a = rev.start()[u]; a < rev.start()[u + 1]; a++) {
                    int s = rev.from()[a];
                    if (rev.cost()[a] != 0 || edge[s] != home || cost[s] <= price) continue;
                    cost[s] = price;
                    now.add(s);
                }
            }
            // Then one tick of steering buys the next band.
            for (int i = 0; i < now.size; i++) {
                int u = now.items[i];
                if (cost[u] != price) continue;
                for (int a = rev.start()[u]; a < rev.start()[u + 1]; a++) {
                    int s = rev.from()[a];
                    if (rev.cost()[a] == 0 || edge[s] != home || cost[s] <= price + 1) continue;
                    cost[s] = price + 1;
                    next.add(s);
                }
            }
            Frontier swap = now;
            now = next;
            next = swap;
            next.clear();
        }
    }

    private static Exit price(int target, List<Integer> points, int[] cost) {
        int min = NEVER, max = -1, unreachable = 0;
        for (int s : points) {
            if (cost[s] == NEVER) { unreachable++; continue; }
            min = Math.min(min, cost[s]);
            max = Math.max(max, cost[s]);
        }
        return new Exit(target, min == NEVER ? -1 : min, max, unreachable);
    }

    /** What a set of inbound points agree, or fail to agree, about where they end up. */
    private static Hold gather(List<Integer> points, int[] out) {
        long outcomes = 0;
        int stuck = 0, only = -1, distinct = 0;
        for (int s : points) {
            int e = out[s];
            if (e < 0) { stuck++; continue; }
            if ((outcomes & (1L << e)) == 0) { outcomes |= 1L << e; only = e; distinct++; }
        }
        return new Hold(distinct == 1 ? only : -1, outcomes, distinct == 1 && stuck == 0, stuck);
    }

    /** One line per edge, in the same shape as the decomposition's own report. */
    public static void report(EdgeNav[] navs) {
        System.out.printf("%n%-5s %8s  %-14s %-14s %-14s %s%n",
                "edge", "inbound", "left", "straight", "right", "exits (steering ticks)");
        for (EdgeNav n : navs) {
            if (n == null || n.inbound() == 0) continue;
            StringBuilder exits = new StringBuilder();
            for (Exit x : n.exits()) {
                if (exits.length() > 0) exits.append("   ");
                exits.append(x.to()).append(':');
                if (x.minTicks() < 0) exits.append("unreachable");
                else exits.append(x.minTicks() == x.maxTicks() ? String.valueOf(x.minTicks())
                        : x.minTicks() + "-" + x.maxTicks());
                if (x.unreachable() > 0) exits.append('+');
            }
            System.out.printf("%-5d %8d  %-14s %-14s %-14s %s%n", n.edge(), n.inbound(),
                    describe(n.left()), describe(n.straight()), describe(n.right()), exits);
        }
        System.out.println("  exits: fewest ticks asking for a turn, over the inbound points, "
                + "to leave that way; + means some inbound point cannot");
    }

    private static String describe(Hold hold) {
        return hold.pure() ? "-> " + hold.to()
                : hold.outcomes() == 0 ? "never leaves"
                : "mixed " + list(hold.outcomes()) + (hold.stuck() > 0 ? "+stuck" : "");
    }

    static String list(long mask) {
        StringBuilder b = new StringBuilder();
        for (int e = 0; e < 64; e++) {
            if ((mask & (1L << e)) != 0) b.append(b.length() > 0 ? "," : "").append(e);
        }
        return b.length() == 0 ? "-" : b.toString();
    }
}
