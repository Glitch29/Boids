package boids;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Phase-complete paths, the shortest loops of a route, and the longcuts that fall out of them.
 * Everything here was specified by the user between 2026-09-14 and 16 and is canonical in
 * {@code EDGES.md} §2a "Phase-complete paths" and §5 from "The shortest lap" on. Four entry
 * points, all run by hand:
 * <ul>
 *   <li>{@link #run} — a stretch of a route between two gates: the phase-complete cover of its
 *       coasting path as strands, and the <b>join field</b> that measures what the definition is
 *       for.</li>
 *   <li>{@link #shortestLoops} — the user's construction of the set of all shortest loops of a
 *       route from a starting line, {@code P*}, and the least {@code N} at which it loops.</li>
 *   <li>{@link #clock} — the route's clock anchored on the states of exactly the stable lap, and
 *       its texture through {@link TauSlices}.</li>
 *   <li>{@link #longcuts} — the basins of the excess field, over the routes {@link #routes}
 *       finds. Needs no clock.</li>
 * </ul>
 *
 * <h2>Phase-complete, the definition</h2>
 * For a set of states {@code S} between gate {@code B} and gate {@code Y} on route {@code R}, a
 * phase-complete path {@code P} is a cover of {@code S} such that every point of {@code P} can
 * backward-navigate to {@code B} and forward-navigate to {@code Y} while remaining in {@code P},
 * and the projection of {@code P} to {@code (x, y)} is diagonally (8-)connected. It rests on the
 * step table: at radius 40 the steps of adjacent headings differ by 0 or 1 pixel, never
 * diagonally. What it is for, in the user's words: the shortest way onto {@code P} from anywhere
 * off it should follow roughly the trajectory continuous physics would take — no detour round a
 * missing phase, no lockout where phase does not bleed. {@link #coverConnected} builds one as
 * strands; {@link #joinField} measures it. A first definition, with a funnel condition, failed on
 * the lattice rather than on the path and was removed 2026-09-16 (git before {@code f162f76}).
 *
 * <h2>The shortest loops, and the excess field</h2>
 * Draw a <b>starting line</b> across a cardinal straight ({@link Line}, found by
 * {@link #findLine}). {@code F(s)} is the fewest quarter-ticks from the line to {@code s}
 * travelling forward, {@code R(s)} from {@code s} forward to the line, minima over the line's four
 * offsets; {@code L = F + R} is the shortest cycle through {@code s}, and the line is only the
 * device that gets it for every state in eight searches. The <b>stable lap</b> is the fewest ticks
 * for four cut crossings back to the same state, over four ({@link #fewestTicksForLaps}); the
 * <b>excess</b> {@code E = L − 4T} is how far behind it a boid at {@code s} has unavoidably
 * fallen, and {@code E ≤ 0} is the fast band. None of this is a clock.
 *
 * <h2>The clock</h2>
 * {@code F/4} on the states whose shortest loop is exactly the stable lap, least squares over
 * every transition for the rest with the line as the seam, unit weights ({@link #build},
 * {@link #anchoredLeastSquares}). The anchor set is not connected to the cut on most routes,
 * which is why the anchors are a distance and not a search within the set.
 *
 * <h2>Longcuts</h2>
 * A longcut is a basin of {@code E}: watershed over transitions, peaks less than a tick proud
 * merged into a higher neighbour, then basins that are one place seen at different phases —
 * touching in projection, {@code F} ranges overlapping by half the shorter — merged greedily, best
 * pair first ({@link #basins}). Its depth is the largest excess in it; its loss the longest path
 * through it by the user's formula for a forced path, {@code F(start) + length + R(end)}, less the
 * stable lap; its shortcut the fast band over the same range. Every bend's outer wall is one.
 */
public final class PhasePath {
    private PhasePath() {}

    /** The four gates in route order. */
    public enum Which { A, B, Y, Z }

    /**
     * One gate: its seed on the coasting path, its conditioned core, {@code E<core}, its landing set,
     * and its states — the core and the landing set together, the first states past the boundary.
     */
    public record GateSet(Which which, int seed, int[] core, boolean[] before, int[] landing, int[] states) {}

    /** The route as a corridor: which of its edges each state is on, and the one cut crossing. */
    static final class Corridor {
        final NavMap map;
        final int[] edgeOf;
        final int[] route;
        final int cutFrom, cutTo;
        final int[] states;
        final int turns, width;

        Corridor(NavMap map, int[] edgeOf, int[] live, int liveCount, int[] route) {
            this.map = map;
            this.edgeOf = edgeOf;
            this.route = route;
            this.cutFrom = route[route.length - 1];
            this.cutTo = route[0];
            this.turns = Params.TURNS;
            this.width = map.width();
            boolean[] on = new boolean[edgeOf.length];
            int n = 0;
            for (int i = 0; i < liveCount; i++) {
                int s = live[i];
                for (int e : route) if (edgeOf[s] == e) { on[s] = true; n++; break; }
            }
            states = new int[n];
            for (int i = 0, k = 0; i < liveCount; i++) if (on[live[i]]) states[k++] = live[i];
        }

        boolean in(int s) {
            if (s < 0) return false;
            for (int e : route) if (edgeOf[s] == e) return true;
            return false;
        }

        boolean cut(int s, int u) { return edgeOf[s] == cutFrom && edgeOf[u] == cutTo; }

        /** Successors of {@code s} inside the corridor, not across the cut. */
        int succ(int s, int[] out) {
            int[] tmp = new int[3];
            int k = map.steeredSuccessors(s, tmp), n = 0;
            for (int j = 0; j < k; j++) if (in(tmp[j]) && !cut(s, tmp[j])) out[n++] = tmp[j];
            return n;
        }

        int pred(int s, int[] out) {
            int[] tmp = new int[3];
            int k = map.steeredPredecessors(s, tmp), n = 0;
            for (int j = 0; j < k; j++) if (in(tmp[j]) && !cut(tmp[j], s)) out[n++] = tmp[j];
            return n;
        }

        /** Successors on the route, the cut crossing included: the loop as a boid flies it. */
        int succRound(int s, int[] out) {
            int[] tmp = new int[3];
            int k = map.steeredSuccessors(s, tmp), n = 0;
            for (int j = 0; j < k; j++) if (in(tmp[j])) out[n++] = tmp[j];
            return n;
        }

        int predRound(int s, int[] out) {
            int[] tmp = new int[3];
            int k = map.steeredPredecessors(s, tmp), n = 0;
            for (int j = 0; j < k; j++) if (in(tmp[j])) out[n++] = tmp[j];
            return n;
        }

        int x(int s) { return (s / turns) % width; }
        int y(int s) { return (s / turns) / width; }
        int d(int s) { return s % turns; }
        int cell(int s) { return s / turns; }
    }

    // --------------------------------------------------------------------------- the driver

    /**
     * @param route  a simple loop of edges; it is rotated so {@code edge} sits in the middle and
     *               the cut falls between its last edge and its first
     * @param edge   the edge the four seeds are measured from
     * @param ticks  four offsets, in ticks along the coasting path from its entry onto the
     *               corridor (the cut landing), for {@code a, b, y, z}
     */
    public static void run(PresetScenarioParameter preset, SolverFacts.Gate gate, int[] route,
                           int edge, int[] ticks) throws IOException {
        Pipeline.Built b = Pipeline.build(preset, gate);
        SolverFacts f = b.facts();
        EdgeDecomposition.Labelling l = b.labelling();
        NavMap map = l.map();
        int[] edgeOf = l.edge();

        // Rotate the route so the cut is as far round the loop from `edge` as it can be.
        int m = route.length, slot = -1;
        for (int i = 0; i < m; i++) if (route[i] == edge) slot = i;
        if (slot < 0) throw new IllegalArgumentException("edge " + edge + " is not on " + Arrays.toString(route));
        int[] rotated = new int[m];
        int shift = Math.floorMod(slot - m / 2, m);
        for (int i = 0; i < m; i++) rotated[i] = route[(i + shift) % m];
        Corridor c = new Corridor(map, edgeOf, l.live(), l.liveCount(), rotated);
        System.out.printf("%n=== %s @%s: phase-complete path on route %s, cut %d->%d, %d corridor states ===%n",
                preset.name(), preset.ingest().hash(), Arrays.toString(rotated), c.cutFrom, c.cutTo,
                c.states.length);

        // The coasting path: the straight-travel cycle, read from the cut landing round to the cut.
        MapStates lattice = MapStates.of(map, Flocking.of(preset.turningRadius()), l.live(), l.liveCount());
        StateSet pure = lattice.pureStable(1);
        int[] cycle = null;
        for (int[] cy : lattice.cycles(pure)) {
            boolean onRoute = true;
            for (int s : cy) if (!c.in(s)) { onRoute = false; break; }
            if (onRoute) { cycle = cy; break; }
        }
        if (cycle == null) throw new IllegalStateException("no straight-travel cycle lies on the route");
        int start = -1;
        for (int i = 0; i < cycle.length; i++) {
            int p = cycle[(i + cycle.length - 1) % cycle.length];
            if (c.cut(p, cycle[i])) { start = i; break; }
        }
        if (start < 0) throw new IllegalStateException("the cycle never crosses the cut");
        int[] coast = new int[cycle.length];
        for (int i = 0; i < cycle.length; i++) coast[i] = cycle[(start + i) % cycle.length];
        System.out.printf("coasting path: %d ticks round the corridor, entering edge %d at (%d,%d,%d)%n",
                coast.length, edgeOf[coast[0]], c.x(coast[0]), c.y(coast[0]), c.d(coast[0]));

        // Gates around a, b, y, z.
        GateSet[] gates = new GateSet[4];
        for (int g = 0; g < 4; g++) {
            int seed = coast[ticks[g]];
            gates[g] = gateAround(c, lattice, Which.values()[g], seed);
            GateSet gs = gates[g];
            int before = 0;
            for (int s : c.states) if (gs.before[s]) before++;
            System.out.printf("gate %s at tick %3d, edge %d tau %6.1f (%d,%d,%d): core %3d, E<core %6d, landing %4d%n",
                    gs.which, ticks[g], edgeOf[seed], f.tickOf()[seed], c.x(seed), c.y(seed), c.d(seed),
                    gs.core.length, before, gs.landing.length);
        }
        GateSet A = gates[0], B = gates[1], Y = gates[2], Z = gates[3];

        // Does every corridor entrance reach every gate's core? If not the insertion boundary is
        // not a gate, and everything after is measured against a fiction.
        int[] entrances = Arrays.stream(c.states).filter(s -> {
            int[] tmp = new int[3];
            int k = map.steeredPredecessors(s, tmp);
            for (int j = 0; j < k; j++) if (c.cut(tmp[j], s)) return true;
            return false;
        }).toArray();
        for (GateSet gs : gates) {
            int missing = 0;
            boolean[] inCore = new boolean[edgeOf.length];
            for (int s : gs.core) inCore[s] = true;
            for (int s : entrances) if (!gs.before[s] && !inCore[s]) missing++;
            System.out.printf("  %d of %d corridor entrances cannot reach %s%s%n", missing, entrances.length,
                    gs.which, missing > 0 ? "   <-- not a gate" : "");
        }

        // [B, Y] and [A, Z].
        boolean[] betweenBY = between(c, B, Y), betweenAZ = between(c, A, Z);
        int nBY = 0, nAZ = 0;
        for (int s : c.states) { if (betweenBY[s]) nBY++; if (betweenAZ[s]) nAZ++; }
        System.out.printf("[B,Y] %d states, [A,Z] %d states%n", nBY, nAZ);

        // S: the coasting states from b to y.
        int[] S = Arrays.copyOfRange(coast, ticks[1], ticks[2] + 1);
        for (int s : S) if (!betweenBY[s]) System.out.printf("  S state (%d,%d,%d) is outside [B,Y]%n", c.x(s), c.y(s), c.d(s));

        // The lane, and the cover.
        boolean[] lane = lane(c, S);
        // A step along a wall sweeps through dead pixels, which no state can stand on; the lane
        // is what a boid on another phase could occupy, so those are not part of it.
        int laneCount = 0, dead = 0;
        for (int i = 0; i < lane.length; i++) {
            if (!lane[i]) continue;
            boolean any = false;
            for (int d = 0; d < c.turns && !any; d++) any = betweenBY[i * c.turns + d];
            if (any) laneCount++; else { lane[i] = false; dead++; }
        }
        System.out.printf("lane: %d pixels swept, %d of them dead or outside [B,Y] and dropped%n",
                laneCount + dead, dead);

        // The cover.
        Cover[] covers = {coverConnected(c, S, lane, betweenBY, B, Y)};
        for (Cover cover : covers) {
            System.out.printf("%n%s cover: %d states in %d strands, projection %d pixels in %d component%s,"
                            + " %d of %d lane pixels covered, %d states off the lane%n", cover.kind, cover.P.length,
                    cover.strands.size(), cover.pixels, cover.components, cover.components == 1 ? "" : "s   <-- NOT CONNECTED",
                    cover.laneCovered, laneCount, cover.offLane);
            for (int i = 0; i < cover.strands.size(); i++) {
                int[] st = cover.strands.get(i);
                System.out.printf("  strand %2d: %3d states, from (%d,%d,%d) to (%d,%d,%d), cost %.0f%n", i, st.length,
                        c.x(st[0]), c.y(st[0]), c.d(st[0]), c.x(st[st.length - 1]), c.y(st[st.length - 1]),
                        c.d(st[st.length - 1]), cover.costs.get(i));
            }
            // The two navigation conditions, checked rather than assumed.
            System.out.printf("  navigation within P: %d of %d states cannot reach Y forward, %d cannot reach B backward%n",
                    unreaching(c, cover.P, Y.states, true), cover.P.length, unreaching(c, cover.P, B.states, false));
        }

        // The join field: from every state before Y, how many ticks to the first state of P.
        // Reported over the states upstream of B, where every one should be able to join.
        boolean[] beforeY = new boolean[edgeOf.length], upstreamOfB = new boolean[edgeOf.length];
        for (int s : c.states) {
            beforeY[s] = betweenAZ[s] && (Y.before[s] || betweenBY[s]);
            upstreamOfB[s] = betweenAZ[s] && B.before[s];
        }
        System.out.println();
        int[] joinS = joinField(c, S, beforeY);
        reportJoin("S alone", c, joinS, upstreamOfB);
        int[][] joins = new int[covers.length][];
        for (int i = 0; i < covers.length; i++) {
            joins[i] = joinField(c, covers[i].P, beforeY);
            reportJoin(covers[i].kind, c, joins[i], upstreamOfB);
        }

        Path dir = Path.of("render", "phase-path");
        Files.createDirectories(dir);
        String name = preset.name().toLowerCase() + "-" + preset.ingest().hash() + "-e" + edge;
        drawCover(c, betweenAZ, lane, covers[0].P, gates, dir.resolve(name + "-cover.png"));
        drawJoin(c, upstreamOfB, coast, new int[][]{joinS, joins[0]}, new String[]{"S alone", covers[0].kind},
                dir.resolve(name + "-join.png"));
        System.out.printf("wrote %s-cover.png and -join.png in %s%n", name, dir);
    }

    // ------------------------------------------------------------------------------ gates

    /** The insertion around one seed: conditioned core, its backward closure, and the landing set. */
    static GateSet gateAround(Corridor c, MapStates lattice, Which which, int seed) {
        StateSet p = lattice.of(seed).partialTick(StateSet.Steering.STRAIGHT)
                .backwardsPerfect().forwardsPerfect();
        p = p.union(p.inverted()).backwardsPerfect().forwardsPerfect();
        int[] core = Arrays.stream(p.toArray()).filter(c::in).toArray();
        boolean[] inCore = new boolean[c.edgeOf.length];
        for (int s : core) inCore[s] = true;

        boolean[] before = new boolean[c.edgeOf.length];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        int[] out = new int[3];
        for (int s : core) queue.add(s);
        while (!queue.isEmpty()) {
            int s = queue.poll();
            int k = c.pred(s, out);
            for (int j = 0; j < k; j++) {
                int u = out[j];
                if (inCore[u] || before[u]) continue;
                before[u] = true;
                queue.add(u);
            }
        }
        List<Integer> landing = new ArrayList<>();
        for (int s : c.states) {
            if (before[s]) continue;
            int k = c.pred(s, out);
            for (int j = 0; j < k; j++) if (before[out[j]]) { landing.add(s); break; }
        }
        int[] landed = landing.stream().mapToInt(Integer::intValue).toArray();
        java.util.TreeSet<Integer> all = new java.util.TreeSet<>();
        for (int s : core) all.add(s);
        for (int s : landed) all.add(s);
        return new GateSet(which, seed, core, before, landed, all.stream().mapToInt(Integer::intValue).toArray());
    }

    /** States past {@code from}'s gate and not past {@code to}'s: not in {@code E<from}, in {@code E<to} or its landing. */
    static boolean[] between(Corridor c, GateSet from, GateSet to) {
        boolean[] in = new boolean[c.edgeOf.length];
        boolean[] toLanding = new boolean[c.edgeOf.length];
        for (int s : to.states) toLanding[s] = true;
        for (int s : c.states) in[s] = !from.before[s] && (to.before[s] || toLanding[s]);
        return in;
    }

    // ------------------------------------------------------------------------------- lane

    /** The pixels {@code S} sweeps: its own, and every sample its steps pass through. */
    static boolean[] lane(Corridor c, int[] S) {
        boolean[] lane = new boolean[c.map.width() * c.map.height()];
        lane[c.cell(S[0])] = true;
        for (int k = 1; k < S.length; k++) {
            int nd = c.d(S[k]);
            int x = c.x(S[k - 1]), y = c.y(S[k - 1]);
            int[] sweep = c.map.stepPath(nd);
            for (int q = 0; q + 1 < sweep.length; q += 2) {
                int mx = x + sweep[q], my = y + sweep[q + 1];
                if (mx >= 0 && my >= 0 && mx < c.map.width() && my < c.map.height()) lane[mx + my * c.map.width()] = true;
            }
            lane[c.cell(S[k])] = true;
        }
        return lane;
    }

    /** Chebyshev distance from every pixel to the nearest lane pixel. */
    static int[] laneDistance(Corridor c, boolean[] lane) {
        int w = c.map.width(), h = c.map.height();
        int[] dist = new int[w * h];
        Arrays.fill(dist, Integer.MAX_VALUE);
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int i = 0; i < dist.length; i++) if (lane[i]) { dist[i] = 0; queue.add(i); }
        while (!queue.isEmpty()) {
            int i = queue.poll();
            int x = i % w, y = i / w;
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int nx = x + dx, ny = y + dy;
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                    int j = nx + ny * w;
                    if (dist[j] == Integer.MAX_VALUE) { dist[j] = dist[i] + 1; queue.add(j); }
                }
            }
        }
        return dist;
    }

    // ------------------------------------------------------------------------------ cover

    static final class Cover {
        final String kind;
        int[] P;
        final List<int[]> strands = new ArrayList<>();
        final List<Double> costs = new ArrayList<>();
        int laneCovered, offLane, pixels, components;

        Cover(String kind) { this.kind = kind; }
    }

    /** The two Dijkstra fields a strand is read off, computed once per {@code [B, Y]}. */
    static final class Strands {
        final Corridor c;
        final boolean[] within, lane;
        final double[] cost, fwd, bwd;
        final int[] fwdFrom, bwdTo;

        Strands(Corridor c, boolean[] lane, boolean[] within, GateSet B, GateSet Y) {
            this.c = c;
            this.within = within;
            this.lane = lane;
            int n = c.edgeOf.length;
            int[] dist = laneDistance(c, lane);
            cost = new double[n];
            for (int s : c.states) if (within[s]) { double d = dist[c.cell(s)]; cost[s] = d * d; }
            fwd = new double[n];
            bwd = new double[n];
            fwdFrom = new int[n];
            bwdTo = new int[n];
            dijkstra(c, within, cost, B.states, true, fwd, fwdFrom);
            dijkstra(c, within, cost, Y.states, false, bwd, bwdTo);
        }

        /** The cheapest {@code B → Y} path within {@code [B, Y]} through a state at {@code pixel}, or null. */
        int[] through(int pixel, double[] costOut) {
            int best = -1;
            double bestCost = Double.POSITIVE_INFINITY;
            for (int d = 0; d < c.turns; d++) {
                int s = pixel * c.turns + d;
                if (!within[s] || fwd[s] == Double.POSITIVE_INFINITY || bwd[s] == Double.POSITIVE_INFINITY) continue;
                double total = fwd[s] + bwd[s] - cost[s];
                if (total < bestCost) { bestCost = total; best = s; }
            }
            if (best < 0) return null;
            List<Integer> strand = new ArrayList<>();
            for (int s = best; s >= 0; s = fwdFrom[s]) strand.add(0, s);
            for (int s = bwdTo[best]; s >= 0; s = bwdTo[s]) strand.add(s);
            costOut[0] = bestCost;
            return strand.stream().mapToInt(Integer::intValue).toArray();
        }
    }

    /** The pixels of the lane in the order {@code S} sweeps them, each once. */
    static List<Integer> sweepOrder(Corridor c, int[] S) {
        int w = c.map.width();
        List<Integer> order = new ArrayList<>();
        boolean[] listed = new boolean[w * c.map.height()];
        for (int k = 0; k < S.length; k++) {
            if (k > 0) {
                int nd = c.d(S[k]);
                int x = c.x(S[k - 1]), y = c.y(S[k - 1]);
                int[] sweep = c.map.stepPath(nd);
                for (int q = 0; q + 1 < sweep.length; q += 2) {
                    int mx = x + sweep[q], my = y + sweep[q + 1];
                    if (mx < 0 || my < 0 || mx >= w || my >= c.map.height()) continue;
                    int i = mx + my * w;
                    if (!listed[i]) { listed[i] = true; order.add(i); }
                }
            }
            int i = c.cell(S[k]);
            if (!listed[i]) { listed[i] = true; order.add(i); }
        }
        return order;
    }

    private static void add(Cover cover, int[] strand, double cost, boolean[] inP, boolean[] covered, Corridor c) {
        for (int s : strand) { inP[s] = true; covered[c.cell(s)] = true; }
        cover.strands.add(strand);
        cover.costs.add(cost);
    }

    private static void finish(Cover cover, Corridor c, boolean[] inP, boolean[] covered, boolean[] lane) {
        int w = c.map.width(), h = c.map.height();
        cover.P = Arrays.stream(c.states).filter(s -> inP[s]).toArray();
        for (int s : cover.P) if (!lane[c.cell(s)]) cover.offLane++;
        for (int i = 0; i < covered.length; i++) if (lane[i] && covered[i]) cover.laneCovered++;
        int[] topo = topology(covered, w, h);
        cover.components = topo[0];
        cover.pixels = topo[2];
    }

    /**
     * <b>The construction for the definition of 2026-09-15:</b> {@code S}, then strands until the
     * projection of {@code P} is 8-connected. Each strand is the cheapest {@code B → Y} path within
     * {@code [B, Y]} through the first uncovered pixel of the sweep between the earliest two
     * consecutive states of {@code S} whose pixels lie in different components; failing every
     * pixel of that sweep, through any uncovered pixel 8-adjacent to the upstream component.
     * Greedy, so minimal in the number of strands only in the greedy sense.
     */
    static Cover coverConnected(Corridor c, int[] S, boolean[] lane, boolean[] within, GateSet B, GateSet Y) {
        int w = c.map.width(), h = c.map.height();
        Strands strands = new Strands(c, lane, within, B, Y);
        Cover cover = new Cover("connected");
        boolean[] inP = new boolean[c.edgeOf.length];
        boolean[] covered = new boolean[w * h];
        add(cover, S.clone(), 0, inP, covered, c);
        int[] label = new int[w * h];
        double[] cost = new double[1];
        boolean[] tried = new boolean[w * h];
        for (int round = 0; round < 200; round++) {
            int comps = label(covered, w, h, true, label, true);
            if (comps == 1) break;
            int k = -1;
            for (int i = 0; i + 1 < S.length; i++) {
                if (label[c.cell(S[i])] != label[c.cell(S[i + 1])]) { k = i; break; }
            }
            if (k < 0) throw new IllegalStateException("projection in " + comps + " components but S never changes component");
            int[] strand = null;
            // The sweep of that step first, nearest the upstream state first.
            int nd = c.d(S[k + 1]), x = c.x(S[k]), y = c.y(S[k]);
            int[] sweep = c.map.stepPath(nd);
            for (int q = 0; q + 1 < sweep.length && strand == null; q += 2) {
                int mx = x + sweep[q], my = y + sweep[q + 1];
                if (mx < 0 || my < 0 || mx >= w || my >= h) continue;
                int pixel = mx + my * w;
                if (covered[pixel] || tried[pixel]) continue;
                tried[pixel] = true;
                strand = strands.through(pixel, cost);
            }
            // Then anything 8-adjacent to the upstream component.
            int upstream = label[c.cell(S[k])];
            for (int i = 0; i < w * h && strand == null; i++) {
                if (covered[i] || tried[i]) continue;
                int px = i % w, py = i / w;
                boolean adjacent = false;
                for (int dy = -1; dy <= 1 && !adjacent; dy++) {
                    for (int dx = -1; dx <= 1 && !adjacent; dx++) {
                        int nx = px + dx, ny = py + dy;
                        if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                        adjacent = covered[nx + ny * w] && label[nx + ny * w] == upstream;
                    }
                }
                if (!adjacent) continue;
                tried[i] = true;
                strand = strands.through(i, cost);
            }
            if (strand == null) {
                System.out.printf("  no strand joins the components at S[%d] (%d,%d) -> S[%d]; giving up%n",
                        k, x, y, k + 1);
                break;
            }
            add(cover, strand, cost[0], inP, covered, c);
        }
        finish(cover, c, inP, covered, lane);
        return cover;
    }

    // ------------------------------------------------------------------------- join field

    /**
     * Ticks from every state to its first state in {@code P}, navigating within {@code domain}:
     * 0 on {@code P}, {@code -1} where {@code P} is unreachable. The field the philosophical
     * definition is about — from anywhere off the path, is the way onto it the way continuous
     * physics would take, or a detour forced by a phase the path lacks, or no way at all.
     */
    static int[] joinField(Corridor c, int[] P, boolean[] domain) {
        int[] d = new int[c.edgeOf.length];
        Arrays.fill(d, -1);
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int s : P) { d[s] = 0; queue.add(s); }
        int[] out = new int[3];
        while (!queue.isEmpty()) {
            int s = queue.poll();
            int k = c.pred(s, out);
            for (int j = 0; j < k; j++) {
                int p = out[j];
                if (!domain[p] || d[p] >= 0) continue;
                d[p] = d[s] + 1;
                queue.add(p);
            }
        }
        return d;
    }

    /**
     * What the join field looks like over the states upstream of {@code B}: how many cannot join
     * at all, and how the join time changes between 4-adjacent pixels at the same heading — a
     * smooth field is continuous physics, a jagged one is the lattice showing through.
     */
    static void reportJoin(String title, Corridor c, int[] d, boolean[] upstream) {
        int w = c.map.width(), h = c.map.height();
        int states = 0, locked = 0, maxJoin = 0;
        long[] hist = new long[8];
        int mixed = 0, pairs = 0;
        for (int s : c.states) {
            if (!upstream[s]) continue;
            states++;
            if (d[s] < 0) { locked++; continue; }
            maxJoin = Math.max(maxJoin, d[s]);
            int x = c.x(s), y = c.y(s), dd = c.d(s);
            for (int[] n : new int[][]{{1, 0}, {0, 1}}) {
                int nx = x + n[0], ny = y + n[1];
                if (nx >= w || ny >= h) continue;
                int t = c.map.index(nx, ny, dd);
                if (!c.map.alive(nx, ny, dd) || !upstream[t]) continue;
                pairs++;
                if (d[t] < 0) { mixed++; continue; }
                int diff = Math.abs(d[s] - d[t]);
                hist[Math.min(diff, hist.length - 1)]++;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hist.length; i++) sb.append(String.format(" %s%d:%d", i == hist.length - 1 ? ">=" : "", i, hist[i]));
        System.out.printf("%-10s upstream of B: %5d states, %5d cannot join P, longest join %3d;"
                + " same-heading neighbours: %d pairs, %d with one side locked out, |delta| histogram%s%n",
                title, states, locked, maxJoin, pairs, mixed, sb);
    }

    /** Single-source-set Dijkstra over the corridor within {@code within}; a state's cost is paid on entering it. */
    static void dijkstra(Corridor c, boolean[] within, double[] cost, int[] sources, boolean forward,
                         double[] best, int[] from) {
        Arrays.fill(best, Double.POSITIVE_INFINITY);
        Arrays.fill(from, -1);
        PriorityQueue<long[]> queue = new PriorityQueue<>((a, b) -> Double.compare(
                Double.longBitsToDouble(a[0]), Double.longBitsToDouble(b[0])));
        for (int s : sources) {
            if (!within[s]) continue;
            best[s] = cost[s];
            queue.add(new long[]{Double.doubleToLongBits(best[s]), s});
        }
        int[] out = new int[3];
        while (!queue.isEmpty()) {
            long[] top = queue.poll();
            double d = Double.longBitsToDouble(top[0]);
            int s = (int) top[1];
            if (d > best[s]) continue;
            int k = forward ? c.succ(s, out) : c.pred(s, out);
            for (int j = 0; j < k; j++) {
                int u = out[j];
                if (!within[u]) continue;
                double nd = d + cost[u];
                if (nd < best[u]) {
                    best[u] = nd;
                    from[u] = s;
                    queue.add(new long[]{Double.doubleToLongBits(nd), u});
                }
            }
        }
    }

    /** How many of {@code P} cannot reach {@code target} by transitions staying inside {@code P}. */
    static int unreaching(Corridor c, int[] P, int[] target, boolean forward) {
        boolean[] inP = new boolean[c.edgeOf.length];
        for (int s : P) inP[s] = true;
        boolean[] reaches = new boolean[c.edgeOf.length];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int s : target) if (inP[s]) { reaches[s] = true; queue.add(s); }
        int[] out = new int[3];
        while (!queue.isEmpty()) {
            int s = queue.poll();
            int k = forward ? c.pred(s, out) : c.succ(s, out);
            for (int j = 0; j < k; j++) {
                int u = out[j];
                if (inP[u] && !reaches[u]) { reaches[u] = true; queue.add(u); }
            }
        }
        int missing = 0;
        for (int s : P) if (!reaches[s]) missing++;
        return missing;
    }

    /**
     * {@code {8-connected components, holes, pixels}} of a pixel set: holes are the 4-connected
     * components of the complement within a one-pixel-padded bounding box, less the outside.
     */
    static int[] topology(boolean[] pix, int w, int h) {
        int lx = w, hx = -1, ly = h, hy = -1, count = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!pix[x + y * w]) continue;
                count++;
                lx = Math.min(lx, x); hx = Math.max(hx, x);
                ly = Math.min(ly, y); hy = Math.max(hy, y);
            }
        }
        if (count == 0) return new int[]{0, 0, 0};
        int bw = hx - lx + 3, bh = hy - ly + 3;
        boolean[] box = new boolean[bw * bh];
        for (int y = ly; y <= hy; y++) {
            for (int x = lx; x <= hx; x++) if (pix[x + y * w]) box[(x - lx + 1) + (y - ly + 1) * bw] = true;
        }
        int[] label = new int[bw * bh];
        int components = label(box, bw, bh, true, label, true);
        boolean[] inverse = new boolean[bw * bh];
        for (int i = 0; i < inverse.length; i++) inverse[i] = !box[i];
        int[] label2 = new int[bw * bh];
        int background = label(inverse, bw, bh, false, label2, true);
        return new int[]{components, background - 1, count};
    }

    /** Labels components of {@code set} on a {@code bw x bh} grid, 8- or 4-connected; returns how many. */
    static int label(boolean[] set, int bw, int bh, boolean eight, int[] label, boolean fill) {
        Arrays.fill(label, 0);
        int next = 0;
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int i = 0; i < set.length; i++) {
            if (!set[i] || label[i] != 0) continue;
            next++;
            label[i] = next;
            queue.add(i);
            while (!queue.isEmpty()) {
                int p = queue.poll();
                int x = p % bw, y = p / bw;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0) continue;
                        if (!eight && dx != 0 && dy != 0) continue;
                        int nx = x + dx, ny = y + dy;
                        if (nx < 0 || ny < 0 || nx >= bw || ny >= bh) continue;
                        int q = nx + ny * bw;
                        if (set[q] && label[q] == 0) { label[q] = next; queue.add(q); }
                    }
                }
            }
        }
        return next;
    }

    /** The pixels of the holes of a pixel set: 4-connected components of the complement not touching pixel 0. */
    // --------------------------------------------------------------------------------- lap

    /** One lap search: from {@code from}, the fewest ticks to a state 0–3 px ahead on the same row and heading. */
    public record Lap(int from, int ticks, int ahead, int[] path) {
        /** The lap in ticks: a pixel ahead on a horizontal step is a quarter tick. */
        public double length() { return ticks - ahead / 4.0; }
        public int to() { return path[path.length - 1]; }
    }

    // ---------------------------------------------------------------------- shortest loops

    /**
     * The set of all shortest phase-complete loops of a route, by the user's construction of
     * 2026-09-15.
     * <p>
     * Draw a <b>starting line</b> across a straight, the column {@code x0}. For every route state
     * {@code s}, {@code F(s)} is the fewest quarter-ticks from the line to {@code s} travelling
     * forward — a tick is four quarter-ticks, and a start {@code k} px past the line contributes
     * {@code k} — and {@code R(s)} the fewest from {@code s} forward to the line, a finish {@code k}
     * px short of it contributing {@code k}. The line lies between columns {@code x0 - 1} and
     * {@code x0}, so starts are at {@code x0 … x0 + 3}, finishes at {@code x0 - 4 … x0 - 1}, and no
     * state is both — a state on the line has a loop through it, not a loop of length zero. {@code L(s) = F(s) + R(s)} is then the length of the
     * shortest loop through {@code s}, in quarter-ticks. {@code P_N} is every state on a loop of
     * length {@code N} or less; the least {@code N} at which the projection of {@code P_N} holds an
     * 8-connected loop round the route names the set of all the shortest phase-complete loops, and
     * <b>the whole of that set is the answer</b> — the shortest, not the smallest.
     * <p>
     * Whether a projection holds a loop is tested by removing the line's own pixels from it — the
     * column {@code x0} within the straight's edge — and asking whether pixels just before and
     * just after the line are still 8-connected, which they can only be the long way round.
     */
    public static void shortestLoops(PresetScenarioParameter preset, SolverFacts.Gate gate, int[] route,
                                     int edge, int x0) throws IOException {
        Pipeline.Built b = Pipeline.build(preset, gate);
        EdgeDecomposition.Labelling l = b.labelling();
        NavMap map = l.map();
        int[] edgeOf = l.edge();
        int w = map.width(), h = map.height(), n = edgeOf.length;

        int m = route.length, slot = -1;
        for (int i = 0; i < m; i++) if (route[i] == edge) slot = i;
        int[] rotated = new int[m];
        int shift = Math.floorMod(slot - m / 2, m);
        for (int i = 0; i < m; i++) rotated[i] = route[(i + shift) % m];
        Corridor c = new Corridor(map, edgeOf, l.live(), l.liveCount(), rotated);
        System.out.printf("%n=== %s @%s: shortest loops of route %s, starting line x = %d on edge %d ===%n",
                preset.name(), preset.ingest().hash(), Arrays.toString(rotated), x0, edge);

        // F and R from a line across the whole edge at x0, travelling +x, each the minimum over the
        // four quarter-tick offsets; L their sum.
        Distances dist = distances(c, new Line(edge, 0, x0, 1));
        int[] F = dist.F, R = dist.R, L = dist.L;
        int reached = 0, minL = Integer.MAX_VALUE;
        for (int s : c.states) {
            if (L[s] == Integer.MAX_VALUE) continue;
            reached++;
            minL = Math.min(minL, L[s]);
        }
        System.out.printf("%d of %d route states lie on some loop through the line; the shortest is %d quarter-ticks = %.2f ticks%n",
                reached, c.states.length, minL, minL / 4.0);

        // The line's own pixels within this edge, to cut out of the projection for the loop test.
        boolean[] cut = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            for (int d = 0; d < c.turns; d++) {
                int s = map.index(x0, y, d);
                if (map.alive(x0, y, d) && c.in(s) && edgeOf[s] == edge) { cut[x0 + y * w] = true; break; }
            }
        }

        // P_N for N ascending over the distinct loop lengths, until the projection closes.
        java.util.TreeSet<Integer> lengths = new java.util.TreeSet<>();
        for (int s : c.states) if (L[s] != Integer.MAX_VALUE) lengths.add(L[s]);
        System.out.printf("%8s %8s %7s %7s %6s  %s%n", "N (q-t)", "ticks", "states", "pixels", "comps", "loop?");
        int star = -1;
        boolean[] inP = new boolean[n];
        for (int N : lengths) {
            if (N > minL + 64) break;
            for (int s : c.states) inP[s] = L[s] <= N;
            int states = 0;
            boolean[] pix = new boolean[w * h];
            for (int s : c.states) if (inP[s]) { states++; pix[c.cell(s)] = true; }
            int pixels = 0;
            for (boolean v : pix) if (v) pixels++;
            int[] label = new int[w * h];
            int comps = label(pix, w, h, true, label, true);
            boolean loop = closesRound(c, pix, cut, x0, w, h);
            System.out.printf("%8d %8.2f %7d %7d %6d  %s%n", N, N / 4.0, states, pixels, comps, loop ? "yes" : "no");
            if (loop) { star = N; break; }
        }
        if (star < 0) { System.out.println("no loop closed within 16 ticks of the shortest; stopping"); return; }

        for (int s : c.states) inP[s] = L[s] <= star;
        int[] P = Arrays.stream(c.states).filter(s -> inP[s]).toArray();
        boolean[] pix = new boolean[w * h];
        for (int s : P) pix[c.cell(s)] = true;
        int pixels = 0;
        for (boolean v : pix) if (v) pixels++;
        System.out.printf("%nN* = %d quarter-ticks = %.2f ticks: %d states over %d pixels; states/tick %.3f, ticks/pixel %.3f, states/pixel %.3f%n",
                star, star / 4.0, P.length, pixels, P.length / (star / 4.0), (star / 4.0) / pixels, P.length / (double) pixels);
        int[] byLength = new int[star - minL + 1];
        for (int s : P) byLength[L[s] - minL]++;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < byLength.length; i++) if (byLength[i] > 0) sb.append(String.format(" %d:%d", minL + i, byLength[i]));
        System.out.println("states by loop length:" + sb);

        // Flow within P: every state should have a successor and a predecessor in P, if P is a union of loops.
        int noSucc = 0, noPred = 0;
        int[] out = new int[3];
        for (int s : P) {
            boolean ok = false;
            int k = c.succRound(s, out);
            for (int j = 0; j < k && !ok; j++) ok = inP[out[j]];
            if (!ok) noSucc++;
            ok = false;
            k = c.predRound(s, out);
            for (int j = 0; j < k && !ok; j++) ok = inP[out[j]];
            if (!ok) noPred++;
        }
        System.out.printf("flow within P: %d states without a successor in P, %d without a predecessor%n", noSucc, noPred);

        // F as a clock on P: how many quarter-ticks each transition inside P advances it, the line crossing aside.
        int[] hist = new int[12];
        int crossing = 0, other = 0;
        for (int s : P) {
            int k = c.succRound(s, out);
            for (int j = 0; j < k; j++) {
                int u = out[j];
                if (!inP[u]) continue;
                int dF = F[u] - F[s];
                if (dF < 0) { crossing++; continue; }
                if (dF < hist.length) hist[dF]++; else other++;
            }
        }
        sb = new StringBuilder();
        for (int i = 0; i < hist.length; i++) if (hist[i] > 0) sb.append(String.format(" %d:%d", i, hist[i]));
        System.out.printf("F as a clock: quarter-ticks advanced per transition within P —%s; %d crossing the line, %d beyond 11%n",
                sb, crossing, other);
        // The slices of the clock: how many states carry each value of F, and whether any value is empty.
        int[] slice = new int[star + 1];
        for (int s : P) if (F[s] <= star) slice[F[s]]++;
        int empty = 0, least = Integer.MAX_VALUE, most = 0;
        for (int i = 0; i < star; i++) { if (slice[i] == 0) empty++; least = Math.min(least, slice[i]); most = Math.max(most, slice[i]); }
        long digest = 0;
        for (int s : P) digest = digest * 1000003L + s;
        System.out.printf("slices of F over 0..%d: %d empty, %d to %d states each, mean %.1f; P digest %016x%n", star - 1, empty, least, most,
                P.length / (double) star, digest);

        Path dir = Path.of("render", "phase-path");
        Files.createDirectories(dir);
        String name = preset.name().toLowerCase() + "-" + preset.ingest().hash() + "-loops"
                + Arrays.toString(rotated).replaceAll("[\\[\\] ]", "").replace(',', '-') + "-x" + x0;
        drawLoops(c, P, L, minL, star, x0, dir.resolve(name + ".png"));
        System.out.printf("wrote %s.png in %s%n", name, dir);
    }


    /** Ticks from the nearest source to every state, forward or backward round the route; -1 unreached. */
    static int[] bfs(Corridor c, int[] sources, boolean forward) {
        int[] depth = new int[c.edgeOf.length];
        Arrays.fill(depth, -1);
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int s : sources) { depth[s] = 0; queue.add(s); }
        int[] out = new int[3];
        while (!queue.isEmpty()) {
            int s = queue.poll();
            int k = forward ? c.succRound(s, out) : c.predRound(s, out);
            for (int j = 0; j < k; j++) {
                int u = out[j];
                if (depth[u] >= 0) continue;
                depth[u] = depth[s] + 1;
                queue.add(u);
            }
        }
        return depth;
    }

    /** Whether the pixel set, with the line's column cut out, still joins the columns either side of it. */
    static boolean closesRound(Corridor c, boolean[] pix, boolean[] cut, int x0, int w, int h) {
        boolean[] rest = new boolean[w * h];
        for (int i = 0; i < rest.length; i++) rest[i] = pix[i] && !cut[i];
        int[] label = new int[w * h];
        label(rest, w, h, true, label, true);
        java.util.Set<Integer> left = new java.util.HashSet<>(), right = new java.util.HashSet<>();
        for (int y = 0; y < h; y++) {
            if (!cut[x0 + y * w]) continue;
            if (x0 > 0 && rest[x0 - 1 + y * w]) left.add(label[x0 - 1 + y * w]);
            if (x0 + 1 < w && rest[x0 + 1 + y * w]) right.add(label[x0 + 1 + y * w]);
        }
        for (int a : left) if (right.contains(a)) return true;
        return false;
    }

    /** {@code P} coloured by loop length: the shortest white, then warmer with each quarter-tick; the line in blue. */
    static void drawLoops(Corridor c, int[] P, int[] L, int minL, int star, int x0, Path out) throws IOException {
        int w = c.map.width(), h = c.map.height();
        boolean[] any = new boolean[c.edgeOf.length];
        for (int s : c.states) any[s] = true;
        int[] box = crop(c, any, 4);
        int scale = 3;
        int[] paint = new int[w * h];
        for (int s : c.states) paint[c.cell(s)] = 0x30343C;
        int[] ramp = {0xFFFFFF, 0xFFE119, 0xF58231, 0xE6194B, 0xF032E6, 0x911EB4, 0x4363D8, 0x46F0F0, 0x3CB44B, 0xBCF60C};
        // Longest loops first so the shortest paint over them.
        Integer[] order = new Integer[P.length];
        for (int i = 0; i < P.length; i++) order[i] = P[i];
        Arrays.sort(order, (a, bb) -> Integer.compare(L[bb], L[a]));
        for (int s : order) paint[c.cell(s)] = ramp[Math.min(ramp.length - 1, L[s] - minL)];
        for (int y = 0; y < h; y++) if (c.in(c.map.index(x0, y, 0)) || c.in(c.map.index(x0, y, 1))) paint[x0 + y * w] = 0x25408F;
        BufferedImage img = new BufferedImage((box[2] - box[0]) * scale, (box[3] - box[1]) * scale, BufferedImage.TYPE_INT_RGB);
        for (int y = box[1]; y < box[3]; y++) {
            for (int x = box[0]; x < box[2]; x++) {
                int rgb = c.map.oob(x, y) ? 0x000000 : paint[x + y * w];
                for (int sy = 0; sy < scale; sy++) for (int sx = 0; sx < scale; sx++) {
                    img.setRGB((x - box[0]) * scale + sx, (y - box[1]) * scale + sy, rgb);
                }
            }
        }
        javax.imageio.ImageIO.write(img, "png", out.toFile());
    }

    // ------------------------------------------------------------------------- the clock

    /**
     * A starting line across a cardinal straight of a route: on {@code edge}, at coordinate
     * {@code at} along {@code axis} (0 for {@code x}, 1 for {@code y}), travel in direction
     * {@code dir} ({@code +1} or {@code -1}) along that axis. The line lies between coordinates
     * {@code at - dir} and {@code at}: a state {@code k} px past it is at {@code at + dir*k}, one
     * {@code k+1} px short of it at {@code at - dir*(k+1)}, and no state is both. On a cardinal
     * straight a pixel along the axis is exactly a quarter tick.
     */
    public record Line(int edge, int axis, int at, int dir, int otherLo, int otherHi) {
        /** A line across a whole edge, unbounded across: only where the edge crosses the coordinate once. */
        public Line(int edge, int axis, int at, int dir) { this(edge, axis, at, dir, Integer.MIN_VALUE, Integer.MAX_VALUE); }

        int coord(Corridor c, int s) { return axis == 0 ? c.x(s) : c.y(s); }

        int other(Corridor c, int s) { return axis == 0 ? c.y(s) : c.x(s); }

        /** Whether a state lies within the straight the line was drawn across, not another crossing of the coordinate. */
        boolean within(Corridor c, int s) { int o = other(c, s); return o >= otherLo && o <= otherHi; }

        /** Whether heading {@code d} steps four pixels along the axis in the line's direction and none across. */
        boolean cardinal(NavMap map, int d) {
            int along = axis == 0 ? map.stepX(d) : map.stepY(d), across = axis == 0 ? map.stepY(d) : map.stepX(d);
            return along == 4 * dir && across == 0;
        }

        /** Every route state on the edge {@code k} px past the line (negative {@code k}: short of it), any row, any heading. */
        int[] states(Corridor c, int k) {
            int v = at + dir * k;
            List<Integer> out = new ArrayList<>();
            for (int s : c.states) if (c.edgeOf[s] == edge && coord(c, s) == v && within(c, s)) out.add(s);
            return out.stream().mapToInt(Integer::intValue).toArray();
        }

        /** Whether the transition {@code s -> u} crosses the line. */
        boolean crosses(Corridor c, int s, int u) {
            return c.edgeOf[s] == edge && c.edgeOf[u] == edge && within(c, s) && within(c, u)
                    && (coord(c, s) - at) * dir < 0 && (coord(c, u) - at) * dir >= 0;
        }

        /** The state {@code k} px along the axis from {@code s}, same other coordinate and heading, or -1. */
        int shifted(Corridor c, int s, int k) {
            int x = c.x(s) + (axis == 0 ? dir * k : 0), y = c.y(s) + (axis == 1 ? dir * k : 0);
            if (x < 0 || y < 0 || x >= c.map.width() || y >= c.map.height() || !c.map.alive(x, y, c.d(s))) return -1;
            int t = c.map.index(x, y, c.d(s));
            return c.in(t) ? t : -1;
        }

        @Override
        public String toString() {
            return String.format("edge %d, %s = %d, travelling %s%s", edge, axis == 0 ? "x" : "y", at,
                    axis == 0 ? (dir > 0 ? "+x" : "-x") : (dir > 0 ? "+y" : "-y"),
                    otherLo == Integer.MIN_VALUE ? "" : String.format(", %s in [%d, %d]", axis == 0 ? "y" : "x", otherLo, otherHi));
        }
    }

    /**
     * The longest cardinal straight on the route, and a line across its middle. Per edge, axis
     * and direction, a coordinate counts if some route state on the edge there has a cardinal
     * step along the axis in that direction; the longest run of consecutive counting coordinates
     * wins, and the line is at its midpoint. Reports the runner-up too, so the choice can be
     * judged; returns null if no run reaches {@code MIN_STRAIGHT} px.
     */
    static Line findLine(Corridor c) {
        Line best = null, second = null;
        int bestRun = 0, secondRun = 0;
        int w = c.map.width(), h = c.map.height();
        for (int e : c.route) {
            for (int axis = 0; axis < 2; axis++) {
                for (int dir = -1; dir <= 1; dir += 2) {
                    // The pixels carrying a cardinal state of this edge, axis and direction, in
                    // 8-connected pieces: each piece is one straight stretch.
                    Line probe = new Line(e, axis, 0, dir);
                    boolean[] pix = new boolean[w * h];
                    for (int s : c.states) if (c.edgeOf[s] == e && probe.cardinal(c.map, c.d(s))) pix[c.cell(s)] = true;
                    int[] label = new int[w * h];
                    int pieces = label(pix, w, h, true, label, true);
                    int[] vLo = new int[pieces + 1], vHi = new int[pieces + 1], oLo = new int[pieces + 1], oHi = new int[pieces + 1];
                    Arrays.fill(vLo, Integer.MAX_VALUE);
                    Arrays.fill(vHi, Integer.MIN_VALUE);
                    Arrays.fill(oLo, Integer.MAX_VALUE);
                    Arrays.fill(oHi, Integer.MIN_VALUE);
                    for (int i = 0; i < pix.length; i++) {
                        if (!pix[i]) continue;
                        int p = label[i], x = i % w, y = i / w, v = axis == 0 ? x : y, o = axis == 0 ? y : x;
                        vLo[p] = Math.min(vLo[p], v); vHi[p] = Math.max(vHi[p], v);
                        oLo[p] = Math.min(oLo[p], o); oHi[p] = Math.max(oHi[p], o);
                    }
                    for (int p = 1; p <= pieces; p++) {
                        int run = vHi[p] - vLo[p] + 1;
                        Line line = new Line(e, axis, (vLo[p] + vHi[p]) / 2, dir, oLo[p] - 2, oHi[p] + 2);
                        if (run > bestRun) { second = best; secondRun = bestRun; best = line; bestRun = run; }
                        else if (run > secondRun) { second = line; secondRun = run; }
                    }
                }
            }
        }
        System.out.printf("longest cardinal straight: %d px on %s; runner-up %d px on %s%n", bestRun, best, secondRun, second);
        return bestRun >= MIN_STRAIGHT ? best : null;
    }

    /** The shortest straight a line is drawn across: eight ticks of cardinal travel. */
    static final int MIN_STRAIGHT = 32;

    /** {@code F}, {@code R} and {@code L} for every route state from the line, in quarter-ticks. */
    record Distances(int[] F, int[] R, int[] L) {}

    static Distances distances(Corridor c, Line line) {
        int n = c.edgeOf.length;
        int[] F = new int[n], R = new int[n], L = new int[n];
        Arrays.fill(F, Integer.MAX_VALUE);
        Arrays.fill(R, Integer.MAX_VALUE);
        for (int k = 0; k < 4; k++) {
            int[] df = bfs(c, line.states(c, k), true), dr = bfs(c, line.states(c, -(k + 1)), false);
            for (int s : c.states) {
                if (df[s] >= 0) F[s] = Math.min(F[s], 4 * df[s] + k);
                if (dr[s] >= 0) R[s] = Math.min(R[s], 4 * dr[s] + k + 1);
            }
        }
        for (int s : c.states) L[s] = F[s] == Integer.MAX_VALUE || R[s] == Integer.MAX_VALUE ? Integer.MAX_VALUE : F[s] + R[s];
        return new Distances(F, R, L);
    }

    /** The states just past the cut: on the route's first edge, with a predecessor on its last. */
    static int[] cutLandings(Corridor c) {
        List<Integer> out = new ArrayList<>();
        int[] tmp = new int[3];
        for (int s : c.states) {
            int k = c.map.steeredPredecessors(s, tmp);
            for (int j = 0; j < k; j++) if (c.in(tmp[j]) && c.cut(tmp[j], s)) { out.add(s); break; }
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * The fewest ticks in which a boid can cross the cut {@code laps} times and be back in the
     * state it started from, over every state just past the cut: breadth-first on
     * {@code (state, crossings)}. Returns {@code {ticks, start, how many starts achieve it}}.
     */
    static int[] fewestTicksForLaps(Corridor c, int[] starts, int laps) {
        int n = c.edgeOf.length;
        int best = Integer.MAX_VALUE, bestStart = -1, achieving = 0;
        int[] depth = new int[n * (laps + 1)];
        int[] out = new int[3];
        for (int t : starts) {
            Arrays.fill(depth, -1);
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            depth[t] = 0;
            queue.add(t);
            int found = -1;
            while (!queue.isEmpty() && found < 0) {
                int node = queue.poll();
                int s = node % n, crossed = node / n;
                if (depth[node] >= best) break;   // cannot beat what another start already did
                int k = c.succRound(s, out);
                for (int j = 0; j < k; j++) {
                    int u = out[j];
                    int cr = crossed + (c.cut(s, u) ? 1 : 0);
                    if (cr > laps) continue;
                    int next = u + cr * n;
                    if (depth[next] >= 0) continue;
                    depth[next] = depth[node] + 1;
                    if (u == t && cr == laps) { found = depth[next]; break; }
                    queue.add(next);
                }
            }
            if (found < 0) continue;
            if (found < best) { best = found; bestStart = t; achieving = 1; }
            else if (found == best) achieving++;
        }
        return new int[]{best, bestStart, achieving};
    }

    /** Breadth-first from {@code from} round the route until a state 0–3 px past it along the line's axis is reached, furthest first on ties. */
    static Lap lapAlong(Corridor c, int from, Line line) {
        int n = c.edgeOf.length;
        int[] target = new int[4];
        for (int k = 0; k < 4; k++) target[k] = line.shifted(c, from, k);
        int[] depth = new int[n], parent = new int[n];
        Arrays.fill(depth, -1);
        Arrays.fill(parent, -1);
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        depth[from] = 0;
        queue.add(from);
        int[] out = new int[3];
        int found = -1, foundK = -1, hitStart = -1, startParent = -1;
        while (!queue.isEmpty() && found < 0) {
            int layer = depth[queue.peek()];
            List<Integer> next = new ArrayList<>();
            while (!queue.isEmpty() && depth[queue.peek()] == layer) {
                int s = queue.poll();
                int k = c.succRound(s, out);
                for (int j = 0; j < k; j++) {
                    int u = out[j];
                    if (u == from) { if (hitStart < 0) { hitStart = layer + 1; startParent = s; } continue; }
                    if (depth[u] >= 0) continue;
                    depth[u] = layer + 1;
                    parent[u] = s;
                    next.add(u);
                }
            }
            queue.addAll(next);
            int bestK = hitStart == layer + 1 ? 0 : -1;
            for (int k = 1; k < 4; k++) if (target[k] >= 0 && depth[target[k]] == layer + 1) bestK = k;
            if (bestK >= 0) { found = layer + 1; foundK = bestK; }
        }
        if (found < 0) return null;
        List<Integer> path = new ArrayList<>();
        int t = foundK == 0 ? from : target[foundK];
        path.add(t);
        int cur = foundK == 0 ? startParent : parent[t];
        while (cur != from) { path.add(0, cur); cur = parent[cur]; }
        path.add(0, from);
        return new Lap(from, found, foundK, path.stream().mapToInt(Integer::intValue).toArray());
    }

    /**
     * A route's clock, built: the corridor it was built on, the line and the stable lap, the
     * distances from the line, the anchors and tau on every route state.
     *
     * @param T4   the stable lap in quarter-ticks
     * @param inS  per state, whether it is an anchor
     * @param tau  per state, the clock; NaN off the route
     */
    public record Clocked(Pipeline.Built built, Corridor c, Line line, int T4, Distances dist, boolean[] inS, double[] tau) {
        public double T() { return T4 / 4.0; }

        /** Quarter-ticks the shortest loop through {@code s} exceeds the stable lap by; MAX_VALUE off every loop. */
        public int excess(int s) { return dist.L[s] == Integer.MAX_VALUE ? Integer.MAX_VALUE : dist.L[s] - T4; }

        public double advance(int s, int u) { return PhasePath.advance(c, line, tau, s, u, T()); }
    }

    /**
     * Builds the route's clock, anchored on the set of states whose shortest loop is exactly the
     * stable lap — the user's construction of 2026-09-15.
     * <ol>
     *   <li>A <b>starting line</b> across the longest cardinal straight on the route, found by
     *       {@link #findLine}, or the one given; the route is cut at the crossing furthest round
     *       from it.</li>
     *   <li>The <b>stable lap</b>: the fewest ticks for a closed walk of four cut crossings from a
     *       state back to itself, over four.</li>
     *   <li>{@code S}: every route state whose shortest loop through the line is exactly that many
     *       quarter-ticks. Labelled {@code F/4}, the quarter-tick distance from the line — a
     *       breadth-first search from a cut over the whole route, which needs no connectivity of
     *       {@code S}; whether a search within {@code S} would have reached it is reported, since
     *       the user asked to be told, and on the first route it would not have. The line is the
     *       clock's seam.</li>
     *   <li>Every other route state by least squares over the transitions — each asks its
     *       endpoints to differ by one tick, one lap less across the seam — with {@code S} held
     *       fixed. Unit weights.</li>
     * </ol>
     *
     * @param given the line to use, or null to find one
     * @param solve whether to solve the clock off the anchors at all; the longcuts do not need it
     * @return null where no line could be found or no state closes on itself
     */
    public static Clocked build(PresetScenarioParameter preset, SolverFacts.Gate gate, int[] route, Line given, boolean solve)
            throws IOException {
        Pipeline.Built b = Pipeline.build(preset, gate);
        EdgeDecomposition.Labelling l = b.labelling();
        NavMap map = l.map();
        int[] edgeOf = l.edge();
        int n = edgeOf.length;

        Corridor whole = new Corridor(map, edgeOf, l.live(), l.liveCount(), route);
        System.out.printf("%n=== %s @%s: the clock of route %s anchored on its stable lap ===%n",
                preset.name(), preset.ingest().hash(), Arrays.toString(route));
        Line line = given != null ? given : findLine(whole);
        if (line == null) { System.out.println("no cardinal straight of " + MIN_STRAIGHT + " px on this route: a line has to be chosen by hand"); return null; }
        System.out.printf("starting line: %s%s%n", line, given != null ? " (given)" : " (found)");
        int m = route.length, slot = -1;
        for (int i = 0; i < m; i++) if (route[i] == line.edge) slot = i;
        int[] rotated = new int[m];
        int shift = Math.floorMod(slot - m / 2, m);
        for (int i = 0; i < m; i++) rotated[i] = route[(i + shift) % m];
        Corridor c = new Corridor(map, edgeOf, l.live(), l.liveCount(), rotated);
        System.out.printf("route as %s, cut %d->%d, %d route states%n", Arrays.toString(rotated), c.cutFrom, c.cutTo, c.states.length);

        int[] landings = cutLandings(c);
        long t0 = System.currentTimeMillis();
        int[] four = fewestTicksForLaps(c, landings, 4);
        if (four[0] == Integer.MAX_VALUE) { System.out.println("no state closes four laps on itself"); return null; }
        int T4 = four[0];
        int one = fewestTicksForLaps(c, landings, 1)[0];
        System.out.printf("four laps back to the same state: fewest %d ticks, from (%d,%d,%d), %d of %d cut landings achieve it (%.1f s);"
                        + " one lap: fewest %d%n", T4, c.x(four[1]), c.y(four[1]), c.d(four[1]), four[2], landings.length,
                (System.currentTimeMillis() - t0) / 1000.0, one);
        double T = T4 / 4.0;
        System.out.printf("stable lap %.2f ticks%s%n", T, T4 % 4 == 0 ? "" : "   <-- not a whole number of ticks");

        Distances dist = distances(c, line);
        boolean[] inS = new boolean[n];
        int sizeS = 0, onLoops = 0, minL = Integer.MAX_VALUE;
        for (int s : c.states) {
            if (dist.L[s] == Integer.MAX_VALUE) continue;
            onLoops++;
            minL = Math.min(minL, dist.L[s]);
            if (dist.L[s] == T4) { inS[s] = true; sizeS++; }
        }
        System.out.printf("S: %d states whose shortest loop is exactly %d quarter-ticks (shortest loop through any state %d = %.2f; %d of %d states on some loop)%n",
                sizeS, T4, minL, minL / 4.0, onLoops, c.states.length);
        {
            StringBuilder sb = new StringBuilder();
            for (int q = minL; q <= minL + 12; q++) { int cnt = 0; for (int s : c.states) if (dist.L[s] == q) cnt++; sb.append(String.format(" %d:%d", q, cnt)); }
            System.out.println("states by shortest loop, from the shortest:" + sb);
        }
        if (sizeS == 0) {
            // A half-integer lap: single laps alternate either side of it, and no loop is exactly it.
            // Anchor on the states within half a tick of it instead, and say so.
            for (int s : c.states) if (dist.L[s] != Integer.MAX_VALUE && Math.abs(dist.L[s] - T4) <= 2) { inS[s] = true; sizeS++; }
            System.out.printf("no state has a shortest loop of exactly the stable lap; anchoring on the %d within half a tick of it instead%n", sizeS);
            if (sizeS == 0) { System.out.println("none within half a tick either; stopping"); return null; }
        }
        // Does the stable lap score? The cheapest scoring state says.
        {
            int scoringStates = 0, minScoring = Integer.MAX_VALUE, fastScoring = 0;
            for (int s : c.states) {
                if (c.map.score(c.x(s), c.y(s)) <= 0 || dist.L[s] == Integer.MAX_VALUE) continue;
                scoringStates++;
                minScoring = Math.min(minScoring, dist.L[s] - T4);
                if (dist.L[s] <= T4) fastScoring++;
            }
            if (scoringStates > 0) System.out.printf("scoring states on the route: %d, of them %d on the fast band; the cheapest scoring state is %.2f ticks behind the stable lap%s%n",
                    scoringStates, fastScoring, minScoring / 4.0, fastScoring == 0 ? "   <-- the stable lap does not score" : "");
        }
        int[] out = new int[3];
        {
            int[] fwd = new int[n], back = new int[n];
            Arrays.fill(fwd, -1);
            Arrays.fill(back, -1);
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            for (int s : landings) if (inS[s]) { fwd[s] = 0; queue.add(s); }
            while (!queue.isEmpty()) {
                int s = queue.poll();
                int k = c.succ(s, out);
                for (int j = 0; j < k; j++) if (inS[out[j]] && fwd[out[j]] < 0) { fwd[out[j]] = fwd[s] + 1; queue.add(out[j]); }
            }
            for (int s : c.states) {
                if (!inS[s]) continue;
                int k = c.succRound(s, out);
                for (int j = 0; j < k; j++) if (c.cut(s, out[j])) { back[s] = 0; queue.add(s); break; }
            }
            while (!queue.isEmpty()) {
                int s = queue.poll();
                int k = c.pred(s, out);
                for (int j = 0; j < k; j++) if (inS[out[j]] && back[out[j]] < 0) { back[out[j]] = back[s] + 1; queue.add(out[j]); }
            }
            int f = 0, bb = 0, neither = 0;
            for (int s : c.states) {
                if (!inS[s]) continue;
                if (fwd[s] >= 0) f++;
                if (back[s] >= 0) bb++;
                if (fwd[s] < 0 && back[s] < 0) neither++;
            }
            System.out.printf("a search within S from the cut would reach %d forward, %d backward, %d by neither%s%n", f, bb, neither,
                    neither > 0 ? "   <-- S is not connected to the cut; anchors are F/4 regardless" : "");
        }
        double[] tau = new double[n];
        Arrays.fill(tau, Double.NaN);
        for (int s : c.states) if (inS[s]) tau[s] = dist.F[s] / 4.0;
        if (!solve) return new Clocked(b, c, line, T4, dist, inS, tau);
        int iterations = anchoredLeastSquares(c, inS, tau, T, line);
        double rms = 0;
        int edges = 0, worst = -1;
        double worstR = 0;
        for (int s : c.states) {
            int k = c.succRound(s, out);
            for (int j = 0; j < k; j++) {
                double r = advance(c, line, tau, s, out[j], T) - 1;
                rms += r * r;
                edges++;
                if (Math.abs(r) > Math.abs(worstR)) { worstR = r; worst = s; }
            }
        }
        System.out.printf("clock: %d free states solved in %d iterations; over %d transitions the advance is 1 %+.4f rms, worst %+.2f at (%d,%d,%d)%n",
                c.states.length - sizeS, iterations, edges, Math.sqrt(rms / edges), worstR, c.x(worst), c.y(worst), c.d(worst));
        return new Clocked(b, c, line, T4, dist, inS, tau);
    }

    /**
     * Builds the clock and reports what the user asked to see: the advance of tau per tick along
     * the fastest loop and, where a straight-travel cycle lies on the route, along the coasting
     * cycle; the spread of tau among each state's successors; and the texture, slice by slice,
     * beside the map-wide clock's.
     */
    public static Clocked clock(PresetScenarioParameter preset, SolverFacts.Gate gate, int[] route, Line given)
            throws IOException {
        Clocked k = build(preset, gate, route, given, true);
        if (k == null) return null;
        Corridor c = k.c;
        Line line = k.line;
        NavMap map = c.map;
        EdgeDecomposition.Labelling l = k.built.labelling();
        int w = map.width(), h = map.height(), n = c.edgeOf.length;
        double T = k.T();
        double[] tau = k.tau;
        int[] out = new int[3];

        int[] fast = null;
        for (int s : line.states(c, -2)) {
            if (!line.cardinal(map, c.d(s))) continue;
            Lap lp = lapAlong(c, s, line);
            if (lp != null && lp.ahead == 3 && (fast == null || lp.ticks < fast.length)) fast = lp.path;
        }
        if (fast != null) {
            System.out.printf("%n-- the %d-tick loop landing 3 px ahead (%.2f): advance of tau per tick --%n", fast.length - 1, fast.length - 1 - 0.75);
            reportAlong(c, line, tau, fast, T);
        } else {
            System.out.println("\n-- no lap from two pixels short of the line lands three ahead; the fast loop is not reported --");
        }

        MapStates lattice = MapStates.of(map, Flocking.of(preset.turningRadius()), l.live(), l.liveCount());
        int[] coast = null;
        for (int[] cy : lattice.cycles(lattice.pureStable(1))) {
            boolean onRoute = true;
            for (int s : cy) if (!c.in(s)) { onRoute = false; break; }
            if (onRoute) { coast = cy; break; }
        }
        double[] coastAdvance = null;
        if (coast != null) {
            int at = 0;
            for (int i = 0; i < coast.length; i++) if (c.cut(coast[(i + coast.length - 1) % coast.length], coast[i])) { at = i; break; }
            int[] rolled = new int[coast.length + 1];
            for (int i = 0; i <= coast.length; i++) rolled[i] = coast[(at + i) % coast.length];
            System.out.printf("%n-- the coasting cycle, %d ticks: advance of tau per tick --%n", coast.length);
            coastAdvance = reportAlong(c, line, tau, rolled, T);
        } else {
            System.out.println("\n-- no straight-travel cycle lies on this route; the coasting report needs a flown lap, which is not built --");
        }

        double[] spread = new double[n];
        double maxSpread = 0, meanSpread = 0;
        int counted = 0;
        for (int s : c.states) {
            int kk = c.succRound(s, out);
            if (kk < 2) continue;
            double sum = 0;
            int pairs = 0;
            for (int i = 0; i < kk; i++) {
                for (int j = i + 1; j < kk; j++) {
                    double d = k.advance(s, out[i]) - k.advance(s, out[j]);
                    sum += d * d;
                    pairs++;
                }
            }
            spread[s] = sum / pairs;
            maxSpread = Math.max(maxSpread, spread[s]);
            meanSpread += spread[s];
            counted++;
        }
        System.out.printf("%nsuccessor spread (mean squared difference of tau between a state's successors): mean %.4f, max %.3f over %d states with a choice%n",
                meanSpread / counted, maxSpread, counted);

        Path dir = Path.of("render", "phase-path");
        Files.createDirectories(dir);
        String name = preset.name().toLowerCase() + "-" + preset.ingest().hash() + "-clock"
                + Arrays.toString(route).replaceAll("[\\[\\] ]", "").replace(',', '-');
        drawClock(c, tau, k.inS, T, dir.resolve(name + "-tau.png"));
        if (coast != null) drawRate(c, coast, coastAdvance, dir.resolve(name + "-coast.png"));
        drawSpread(c, spread, dir.resolve(name + "-spread.png"));
        System.out.printf("wrote %s-{tau,%sspread}.png in %s%n", name, coast != null ? "coast," : "", dir);

        boolean[] routePixel = new boolean[w * h];
        for (int s : c.states) routePixel[c.cell(s)] = true;
        boolean[] any = new boolean[n];
        for (int s : c.states) any[s] = true;
        int[] box = crop(c, any, 3);
        TauSlices.draw(map, c.states, tau, 16, true, routePixel, box, 2, dir.resolve(name + "-slices.png"));
        double[] fitted = new double[n];
        Arrays.fill(fitted, Double.NaN);
        for (int s : c.states) fitted[s] = k.built.facts().tickOf()[s];
        TauSlices.draw(map, c.states, fitted, 16, true, routePixel, box, 2, dir.resolve(name + "-slices-fitted.png"));
        return k;
    }

    /** {@code tau(u) - tau(s)}, plus a lap where the transition crosses the line. */
    static double advance(Corridor c, Line line, double[] tau, int s, int u, double T) {
        return tau[u] - tau[s] + (line.crosses(c, s, u) ? T : 0);
    }

    /**
     * Least squares over every transition of the route, {@code tau(u) - tau(s) = 1} (less a lap
     * across the seam), the anchored states fixed: conjugate gradient on the normal equations.
     * Returns the iterations used.
     */
    static int anchoredLeastSquares(Corridor c, boolean[] fixed, double[] tau, double T, Line line) {
        int n = c.edgeOf.length;
        // The transitions, once.
        int[] out = new int[3];
        int count = 0;
        for (int s : c.states) count += c.succRound(s, out);
        int[] from = new int[count], to = new int[count];
        double[] target = new double[count];
        int e = 0;
        for (int s : c.states) {
            int k = c.succRound(s, out);
            for (int j = 0; j < k; j++) { from[e] = s; to[e] = out[j]; target[e] = 1 - (line.crosses(c, s, out[j]) ? T : 0); e++; }
        }
        boolean[] free = new boolean[n];
        int[] idx = new int[n];
        Arrays.fill(idx, -1);
        int nf = 0;
        for (int s : c.states) if (!fixed[s]) { free[s] = true; idx[s] = nf++; }
        // Start every free state from the mean of what its anchored neighbours imply, else zero.
        double[] x = new double[nf];
        for (int s : c.states) if (free[s]) x[idx[s]] = 0;
        // b = A^T (target - A_fixed tau_fixed); operator: A^T A x.
        double[] bvec = new double[nf];
        for (int i = 0; i < count; i++) {
            double r = target[i] - (fixed[to[i]] ? tau[to[i]] : 0) + (fixed[from[i]] ? tau[from[i]] : 0);
            if (free[to[i]]) bvec[idx[to[i]]] += r;
            if (free[from[i]]) bvec[idx[from[i]]] -= r;
        }
        double[] r = new double[nf], p = new double[nf], Ap = new double[nf];
        applyNormal(from, to, free, idx, x, Ap, count);
        double rr = 0;
        for (int i = 0; i < nf; i++) { r[i] = bvec[i] - Ap[i]; p[i] = r[i]; rr += r[i] * r[i]; }
        double rr0 = rr;
        int it = 0;
        for (; it < 20000 && rr > 1e-18 * Math.max(1, rr0); it++) {
            applyNormal(from, to, free, idx, p, Ap, count);
            double pAp = 0;
            for (int i = 0; i < nf; i++) pAp += p[i] * Ap[i];
            if (pAp == 0) break;
            double alpha = rr / pAp;
            double rrNew = 0;
            for (int i = 0; i < nf; i++) { x[i] += alpha * p[i]; r[i] -= alpha * Ap[i]; rrNew += r[i] * r[i]; }
            double beta = rrNew / rr;
            rr = rrNew;
            for (int i = 0; i < nf; i++) p[i] = r[i] + beta * p[i];
        }
        for (int s : c.states) if (free[s]) tau[s] = x[idx[s]];
        return it;
    }

    /** {@code y = A^T A x} over the free variables, with {@code A} the transition difference matrix. */
    static void applyNormal(int[] from, int[] to, boolean[] free, int[] idx, double[] x, double[] y, int count) {
        Arrays.fill(y, 0);
        for (int i = 0; i < count; i++) {
            double ax = (free[to[i]] ? x[idx[to[i]]] : 0) - (free[from[i]] ? x[idx[from[i]]] : 0);
            if (free[to[i]]) y[idx[to[i]]] += ax;
            if (free[from[i]]) y[idx[from[i]]] -= ax;
        }
    }

    /** The advance of tau along a path, tick by tick: min, max, mean, the count over one, and where it strays. Returns the advances. */
    static double[] reportAlong(Corridor c, Line line, double[] tau, int[] path, double T) {
        int steps = path.length - 1;
        double[] adv = new double[steps];
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE, sum = 0;
        int over = 0, overBy = 0;
        int[] hist = new int[9];   // < 0.8, 0.8-0.9, ..., 1.2-1.3, >= 1.3
        for (int i = 0; i < steps; i++) {
            adv[i] = advance(c, line, tau, path[i], path[i + 1], T);
            min = Math.min(min, adv[i]);
            max = Math.max(max, adv[i]);
            sum += adv[i];
            if (adv[i] > 1 + 1e-9) over++;
            if (adv[i] > 1.05) overBy++;
            int bin = (int) Math.floor((adv[i] - 0.8) / 0.1) + 1;
            hist[Math.max(0, Math.min(hist.length - 1, bin))]++;
        }
        System.out.printf("   %d ticks: tau advances %.3f in all (%.4f per tick), min %.3f, max %.3f; %d ticks over 1, %d over 1.05%n",
                steps, sum, sum / steps, min, max, over, overBy);
        StringBuilder sb = new StringBuilder("   histogram: <0.8:" + hist[0]);
        for (int i = 1; i < hist.length - 1; i++) sb.append(String.format(" %.1f-%.1f:%d", 0.7 + i * 0.1, 0.8 + i * 0.1, hist[i]));
        sb.append(" >=1.3:").append(hist[hist.length - 1]);
        System.out.println(sb);
        // Where it strays from one by more than a twentieth, in runs.
        StringBuilder runs = new StringBuilder();
        int shown = 0;
        for (int i = 0; i < steps && shown < 40; i++) {
            if (Math.abs(adv[i] - 1) <= 0.05) continue;
            int j = i;
            double s2 = 0;
            while (j < steps && Math.abs(adv[j] - 1) > 0.05) { s2 += adv[j] - 1; j++; }
            runs.append(String.format(" [%d..%d]%+.2f", i, j - 1, s2));
            shown++;
            i = j;
        }
        System.out.println("   runs off one by more than 0.05 (tick range, net):" + runs);
        return adv;
    }

    /** The whole route coloured by tau round the lap, the anchors brighter. */
    static void drawClock(Corridor c, double[] tau, boolean[] inS, double T, Path out) throws IOException {
        int w = c.map.width(), h = c.map.height();
        boolean[] any = new boolean[c.edgeOf.length];
        for (int s : c.states) any[s] = true;
        int[] box = crop(c, any, 4);
        int scale = 3;
        int[] paint = new int[w * h];
        double[] sum = new double[w * h];
        int[] cnt = new int[w * h];
        boolean[] anchor = new boolean[w * h];
        for (int s : c.states) { sum[c.cell(s)] += tau[s]; cnt[c.cell(s)]++; if (inS[s]) anchor[c.cell(s)] = true; }
        for (int i = 0; i < paint.length; i++) {
            if (cnt[i] == 0) continue;
            double t = (sum[i] / cnt[i]) / T;
            t -= Math.floor(t);
            paint[i] = hue(t, anchor[i] ? 1.0 : 0.55);
        }
        write(c, paint, box, scale, out);
    }

    /** The coasting cycle coloured by its advance of tau per tick — blue under one, white at one, red over — with a strip chart beneath. */
    static void drawRate(Corridor c, int[] coast, double[] adv, Path out) throws IOException {
        int w = c.map.width(), h = c.map.height();
        boolean[] any = new boolean[c.edgeOf.length];
        for (int s : c.states) any[s] = true;
        int[] box = crop(c, any, 4);
        int scale = 3;
        int[] paint = new int[w * h];
        for (int s : c.states) paint[c.cell(s)] = 0x30343C;
        int at = 0;
        for (int i = 0; i < coast.length; i++) if (c.cut(coast[(i + coast.length - 1) % coast.length], coast[i])) { at = i; break; }
        for (int i = 0; i < coast.length; i++) {
            int s = coast[(at + i) % coast.length];
            paint[c.cell(s)] = diverging(adv[i]);
            // Thicken to the 8-neighbours so the line reads at 3x.
            for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                int nx = c.x(s) + dx, ny = c.y(s) + dy;
                if (nx < 0 || ny < 0 || nx >= w || ny >= h || c.map.oob(nx, ny)) continue;
                if (paint[nx + ny * w] == 0x30343C) paint[nx + ny * w] = diverging(adv[i]);
            }
        }
        int bw = (box[2] - box[0]) * scale, bh = (box[3] - box[1]) * scale, stripH = 120, gap = 10;
        BufferedImage img = new BufferedImage(bw, bh + gap + stripH + 20, BufferedImage.TYPE_INT_RGB);
        for (int y = box[1]; y < box[3]; y++) {
            for (int x = box[0]; x < box[2]; x++) {
                int rgb = c.map.oob(x, y) ? 0x000000 : paint[x + y * w];
                for (int sy = 0; sy < scale; sy++) for (int sx = 0; sx < scale; sx++) img.setRGB((x - box[0]) * scale + sx, (y - box[1]) * scale + sy, rgb);
            }
        }
        // The strip: tick along x, advance along y from 0.6 to 1.4, a line at one.
        java.awt.Graphics2D g2 = img.createGraphics();
        g2.setColor(new java.awt.Color(0x14161A));
        g2.fillRect(0, bh + gap, bw, stripH + 20);
        g2.setColor(new java.awt.Color(0x50545C));
        int y1 = bh + gap + stripH - (int) ((1 - 0.6) / 0.8 * stripH);
        g2.drawLine(0, y1, bw, y1);
        g2.setColor(java.awt.Color.WHITE);
        g2.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 11));
        g2.drawString("advance of tau per tick along the coasting cycle, from the cut: 0.6 to 1.4, line at 1", 4, bh + gap + 12);
        double px = bw / (double) adv.length;
        for (int i = 0; i < adv.length; i++) {
            double v = Math.max(0.6, Math.min(1.4, adv[i]));
            int y = bh + gap + stripH - (int) ((v - 0.6) / 0.8 * stripH);
            g2.setColor(new java.awt.Color(diverging(adv[i])));
            g2.fillRect((int) (i * px), Math.min(y, y1), Math.max(1, (int) Math.ceil(px)), Math.abs(y1 - y) + 1);
        }
        g2.dispose();
        javax.imageio.ImageIO.write(img, "png", out.toFile());
    }

    /** The successor spread, averaged over the states at each pixel, black to yellow. */
    static void drawSpread(Corridor c, double[] spread, Path out) throws IOException {
        int w = c.map.width(), h = c.map.height();
        boolean[] any = new boolean[c.edgeOf.length];
        for (int s : c.states) any[s] = true;
        int[] box = crop(c, any, 4);
        int[] paint = new int[w * h];
        double[] sum = new double[w * h];
        int[] cnt = new int[w * h];
        int[] tmp = new int[3];
        for (int s : c.states) if (c.succRound(s, tmp) >= 2) { sum[c.cell(s)] += spread[s]; cnt[c.cell(s)]++; }
        double top = 0;
        for (int i = 0; i < paint.length; i++) if (cnt[i] > 0) top = Math.max(top, sum[i] / cnt[i]);
        for (int i = 0; i < paint.length; i++) {
            if (cnt[i] == 0) { paint[i] = 0x30343C; continue; }
            double t = Math.sqrt(sum[i] / cnt[i] / top);
            int r = (int) (255 * Math.min(1, 1.5 * t)), g = (int) (255 * Math.max(0, Math.min(1, 1.5 * t - 0.3))), bb = (int) (80 * Math.max(0, 1 - 2 * t));
            paint[i] = (r << 16) | (g << 8) | bb;
        }
        write(c, paint, box, 3, out);
        System.out.printf("   spread heatmap: black 0 to yellow %.3f (per-pixel mean over headings, square-root scale)%n", top);
    }

    static int hue(double t, double v) {
        double r = Math.max(0, Math.min(1, Math.abs(6 * t - 3) - 1)), g = Math.max(0, Math.min(1, 2 - Math.abs(6 * t - 2))), b = Math.max(0, Math.min(1, 2 - Math.abs(6 * t - 4)));
        return ((int) (255 * v * r) << 16) | ((int) (255 * v * g) << 8) | (int) (255 * v * b);
    }

    /** Blue at 0.7 and below, white at 1, red at 1.3 and above. */
    static int diverging(double v) {
        double t = Math.max(-1, Math.min(1, (v - 1) / 0.3));
        int r = (int) (255 * (t >= 0 ? 1 : 1 + t)), g = (int) (255 * (1 - Math.abs(t))), b = (int) (255 * (t <= 0 ? 1 : 1 - t));
        return (r << 16) | (g << 8) | b;
    }

    static void write(Corridor c, int[] paint, int[] box, int scale, Path out) throws IOException {
        int w = c.map.width();
        BufferedImage img = new BufferedImage((box[2] - box[0]) * scale, (box[3] - box[1]) * scale, BufferedImage.TYPE_INT_RGB);
        for (int y = box[1]; y < box[3]; y++) {
            for (int x = box[0]; x < box[2]; x++) {
                int rgb = c.map.oob(x, y) ? 0x000000 : paint[x + y * w];
                for (int sy = 0; sy < scale; sy++) for (int sx = 0; sx < scale; sx++) img.setRGB((x - box[0]) * scale + sx, (y - box[1]) * scale + sy, rgb);
            }
        }
        javax.imageio.ImageIO.write(img, "png", out.toFile());
    }

    // ---------------------------------------------------------------------------- longcuts

    /**
     * One longcut: a connected region of states whose shortest loop exceeds the stable lap.
     *
     * @param states   its states
     * @param pixels   distinct pixels under them
     * @param maxE     the largest excess in it, quarter-ticks
     * @param deepest  a state with that excess
     * @param tauLo    its range of {@code F/4}, the quarter-tick distance from the line in ticks
     * @param loss     the most tau a path through it can lose, in ticks: the longest lag path from
     *                 an entry to an exit
     * @param lossPath that path
     * @param entries  transitions into it from outside; {@code exits} out of it
     * @param beside   states of the fast band ({@code E <= 0}) whose tau lies in its range — the
     *                 shortcut alongside
     * @param byEdge   per route edge, how many of its states lie there
     */
    public record Region(int id, int[] states, int pixels, int maxE, int deepest, double tauLo, double tauHi,
                         double loss, int[] lossPath, int entries, int exits, int beside, java.util.Map<Integer, Integer> byEdge) {
        public int size() { return states.length; }
    }

    /** A state is in a longcut when its shortest loop is at least this many quarter-ticks over the stable lap: one tick. */
    static final int LONGCUT_THRESHOLD = 4;

    /**
     * Finds the longcuts of a route from its clock: the connected regions (over transitions, the
     * cut excluded) of states whose shortest loop through the line exceeds the stable lap by at
     * least a tick. For each, the depth (largest excess), the longest lag path through it — the
     * most a boid can lose by way of it, under the anchored clock — and the fast band beside it,
     * which is the shortcut: the states that do not lose tau over the same range. Ranked by loss.
     */
    public static List<Region> longcuts(PresetScenarioParameter preset, SolverFacts.Gate gate, int[] route, Line given)
            throws IOException {
        Clocked k = build(preset, gate, route, given, false);
        if (k == null) return List.of();
        Corridor c = k.c;
        NavMap map = c.map;
        int w = map.width(), h = map.height(), n = c.edgeOf.length;
        int[] out = new int[3];

        boolean[] member = new boolean[n];
        int members = 0, fast = 0, behind = 0;
        for (int s : c.states) {
            int e = k.excess(s);
            if (e == Integer.MAX_VALUE) continue;
            if (e >= LONGCUT_THRESHOLD) { member[s] = true; members++; }
            else if (e <= 0) fast++;
            else behind++;
        }
        System.out.printf("%nlongcuts of route %s: %d states at least a tick behind the stable lap, %d within a tick, %d on the fast band%n",
                Arrays.toString(route), members, behind, fast);

        // Basins of the excess field, one per peak that stands a tick proud.
        List<Integer> peaks = new ArrayList<>();
        int[] peakOf = new int[n];
        int[] comp = basins(k, peakOf, peaks);
        List<List<Integer>> comps = new ArrayList<>();
        for (int i = 0; i < peaks.size(); i++) comps.add(new ArrayList<>());
        for (int s : c.states) if (member[s] && comp[s] >= 0) comps.get(comp[s]).add(s);

        // Per component, everything the record holds; the loss path by memoised longest path on
        // the component's own transitions, which are acyclic once the cut is left out.
        List<Region> regions = new ArrayList<>();
        double[] best = new double[n];
        int[] bestNext = new int[n];
        for (int id = 0; id < comps.size(); id++) {
            List<Integer> found = comps.get(id);
            int[] states = found.stream().mapToInt(Integer::intValue).toArray();
            Arrays.sort(states);
            boolean[] pix = new boolean[w * h];
            int maxE = Integer.MIN_VALUE, deepest = -1, entries = 0, exits = 0;
            double tauLo = Double.MAX_VALUE, tauHi = -Double.MAX_VALUE;
            java.util.Map<Integer, Integer> byEdge = new java.util.TreeMap<>();
            for (int s : states) {
                pix[c.cell(s)] = true;
                int e = k.excess(s);
                if (e > maxE) { maxE = e; deepest = s; }
                tauLo = Math.min(tauLo, k.dist.F[s] / 4.0);
                tauHi = Math.max(tauHi, k.dist.F[s] / 4.0);
                byEdge.merge(c.edgeOf[s], 1, Integer::sum);
                int kk = c.pred(s, out);
                for (int j = 0; j < kk; j++) if (comp[out[j]] != id) entries++;
                kk = c.succ(s, out);
                for (int j = 0; j < kk; j++) if (comp[out[j]] != id) exits++;
            }
            int pixels = 0;
            for (boolean v : pix) if (v) pixels++;
            // The loss of a forced path, the user's formula of 2026-09-16: the shortest loop containing it is
            // F(start) + length + R(end), so the most a path through the basin can lose is the longest
            // path in it by that measure, less the stable lap. Clock-free.
            for (int s : states) { best[s] = Double.NaN; }
            double loss = 0;
            int start = -1;
            for (int s : states) {
                double v = (dist(k, s) + longestForced(c, k, comp, id, s, best, bestNext) - k.T4) / 4.0;
                if (v > loss) { loss = v; start = s; }
            }
            List<Integer> path = new ArrayList<>();
            for (int s = start; s >= 0 && path.size() < 5000; s = bestNext[s]) path.add(s);
            int beside = 0;
            for (int s : c.states) {
                int e = k.excess(s);
                if (e == Integer.MAX_VALUE || e > 0) continue;
                if (k.dist.F[s] / 4.0 >= tauLo && k.dist.F[s] / 4.0 <= tauHi) beside++;
            }
            regions.add(new Region(id, states, pixels, maxE, deepest, tauLo, tauHi, loss,
                    path.stream().mapToInt(Integer::intValue).toArray(), entries, exits, beside, byEdge));
        }
        regions.sort((a, b) -> Double.compare(b.loss, a.loss));

        System.out.printf("%d basins after merging peaks less than a tick proud; the %d worth a tick or more of loss, by loss:%n", regions.size(),
                regions.stream().filter(r -> r.loss >= 1).count());
        System.out.printf("%4s %6s %6s %6s %6s %5s %5s %14s %6s   %s%n", "#", "loss", "depth", "states", "pixels", "in", "out", "F/4", "beside", "edges, and the deepest state");
        int shown = 0;
        for (Region r : regions) {
            if (r.loss < 1 && shown >= 10) break;
            if (shown++ >= 30) break;
            StringBuilder edges = new StringBuilder();
            for (var e : r.byEdge.entrySet()) edges.append(edges.length() == 0 ? "" : " ").append(e.getKey()).append(':').append(e.getValue());
            // The loss path's net turn, in headings: positive is a positive turn in screen coordinates.
            int turn = 0;
            for (int i = 0; i + 1 < r.lossPath.length; i++) turn += Math.floorMod(c.d(r.lossPath[i + 1]) - c.d(r.lossPath[i]) + 32, 64) - 32;
            System.out.printf("%4d %6.2f %6.2f %6d %6d %5d %5d %6.1f-%6.1f %6d   %s  (%d,%d,%d)  path %d ticks, net turn %+d%n", r.id, r.loss, r.maxE / 4.0,
                    r.size(), r.pixels, r.entries, r.exits, r.tauLo, r.tauHi, r.beside, edges, c.x(r.deepest), c.y(r.deepest), c.d(r.deepest),
                    r.lossPath.length - 1, turn);
        }
        // Per edge: how much of it is longcut, and how deep.
        System.out.println("per edge: states, of them at least a tick behind, the deepest excess in ticks");
        for (int e : c.route) {
            int total = 0, slow = 0, deep = 0;
            for (int s : c.states) {
                if (c.edgeOf[s] != e) continue;
                total++;
                int ex = k.excess(s);
                if (ex == Integer.MAX_VALUE) continue;
                if (ex >= LONGCUT_THRESHOLD) slow++;
                deep = Math.max(deep, ex);
            }
            // The fast band's headings, in octants: on a self-inverse edge this is the preferred direction.
            int[] octant = new int[8];
            for (int s : c.states) if (c.edgeOf[s] == e && k.excess(s) != Integer.MAX_VALUE && k.excess(s) <= 0) octant[c.d(s) / 8]++;
            System.out.printf("   edge %d: %6d states, %6d behind (%.0f%%), deepest %.2f; fast band by heading octant %s%n", e, total, slow,
                    100.0 * slow / total, deep / 4.0, Arrays.toString(octant));
        }

        // On a self-inverse edge — one with states a half-turn apart at one pixel — the two senses
        // of travel round it are told apart by the sign of the turn about its centroid, and the
        // sense the fast band favours is the preferred direction round the ring.
        for (int e : c.route) {
            boolean selfInverse = false;
            double cx = 0, cy = 0;
            int count = 0;
            for (int s : c.states) {
                if (c.edgeOf[s] != e) continue;
                cx += c.x(s); cy += c.y(s); count++;
                if (!selfInverse) {
                    int opposite = c.map.index(c.x(s), c.y(s), (c.d(s) + 32) % 64);
                    selfInverse = c.map.alive(c.x(s), c.y(s), (c.d(s) + 32) % 64) && c.in(opposite) && c.edgeOf[opposite] == e;
                }
            }
            if (!selfInverse) continue;
            cx /= count; cy /= count;
            int[] fastBy = new int[2], behindBy = new int[2], all = new int[2];
            double[] sumE = new double[2];
            for (int s : c.states) {
                if (c.edgeOf[s] != e || k.excess(s) == Integer.MAX_VALUE) continue;
                double bearing = Math.atan2(c.y(s) - cy, c.x(s) - cx), heading = c.d(s) * 2 * Math.PI / 64;
                int sense = Math.sin(heading - bearing) >= 0 ? 0 : 1;   // 0: turning one way about the centre, 1: the other
                all[sense]++;
                sumE[sense] += k.excess(s) / 4.0;
                if (k.excess(s) <= 0) fastBy[sense]++;
                if (k.excess(s) >= LONGCUT_THRESHOLD) behindBy[sense]++;
            }
            System.out.printf("   edge %d is self-inverse (centroid %.0f,%.0f): sense A %d states, %d fast, %d behind, mean excess %.2f;"
                            + " sense B %d states, %d fast, %d behind, mean excess %.2f  (A: positive turn about the centroid in screen coordinates)%n",
                    e, cx, cy, all[0], fastBy[0], behindBy[0], sumE[0] / Math.max(1, all[0]), all[1], fastBy[1], behindBy[1], sumE[1] / Math.max(1, all[1]));
            // The pass round the ring, sense by sense: the fewest ticks from the states entered
            // from the edge before to those leaving for the edge after, over states of one sense
            // only, against the fewest with no restriction. If the unrestricted pass is far
            // shorter than either, the fast lap clips the ring rather than going round it.
            {
                int before = -1, after = -1;
                for (int i = 0; i < c.route.length; i++) if (c.route[i] == e) { before = c.route[(i + c.route.length - 1) % c.route.length]; after = c.route[(i + 1) % c.route.length]; }
                boolean[] entry = new boolean[c.edgeOf.length], exit = new boolean[c.edgeOf.length];
                int[] tmp = new int[3];
                for (int s : c.states) {
                    if (c.edgeOf[s] != e) continue;
                    int kk = c.map.steeredPredecessors(s, tmp);
                    for (int j = 0; j < kk; j++) if (c.in(tmp[j]) && c.edgeOf[tmp[j]] == before) { entry[s] = true; break; }
                    kk = c.map.steeredSuccessors(s, tmp);
                    for (int j = 0; j < kk; j++) if (c.in(tmp[j]) && c.edgeOf[tmp[j]] == after) { exit[s] = true; break; }
                }
                int[] senseOf = new int[c.edgeOf.length];
                Arrays.fill(senseOf, -1);
                for (int s : c.states) {
                    if (c.edgeOf[s] != e) continue;
                    double bearing = Math.atan2(c.y(s) - cy, c.x(s) - cx), heading = c.d(s) * 2 * Math.PI / 64;
                    senseOf[s] = Math.sin(heading - bearing) >= 0 ? 0 : 1;
                }
                int[] passes = new int[3];
                for (int restrict = -1; restrict <= 1; restrict++) {
                    int[] depth = new int[c.edgeOf.length];
                    Arrays.fill(depth, -1);
                    ArrayDeque<Integer> queue = new ArrayDeque<>();
                    for (int s : c.states) if (entry[s] && (restrict < 0 || senseOf[s] == restrict)) { depth[s] = 0; queue.add(s); }
                    int found = -1;
                    while (!queue.isEmpty() && found < 0) {
                        int s = queue.poll();
                        if (exit[s] && depth[s] > 0) { found = depth[s]; break; }
                        int kk = c.map.steeredSuccessors(s, tmp);
                        for (int j = 0; j < kk; j++) {
                            int u = tmp[j];
                            if (!c.in(u) || c.edgeOf[u] != e || depth[u] >= 0) continue;
                            if (restrict >= 0 && senseOf[u] != restrict) continue;
                            depth[u] = depth[s] + 1;
                            queue.add(u);
                        }
                    }
                    passes[restrict + 1] = found;
                }
                System.out.printf("   edge %d, entered from %d and left for %d: fewest ticks across it %d unrestricted, %d in sense A only, %d in sense B only%s%n",
                        e, before, after, passes[0], passes[1], passes[2],
                        passes[0] > 0 && Math.min(passes[1], passes[2]) > passes[0] + 8 ? "   <-- the fast lap clips the ring; a pass round it is a longcut" : "");
            }
        }

        Path dir = Path.of("render", "phase-path");
        Files.createDirectories(dir);
        String name = preset.name().toLowerCase() + "-" + preset.ingest().hash() + "-longcuts"
                + Arrays.toString(route).replaceAll("[\\[\\] ]", "").replace(',', '-');
        drawExcess(c, k, regions, dir.resolve(name + ".png"));
        boolean[] routePixel = new boolean[w * h];
        for (int s : c.states) routePixel[c.cell(s)] = true;
        boolean[] any = new boolean[n];
        for (int s : c.states) any[s] = true;
        int[] box = crop(c, any, 3);
        double[] excess = new double[n];
        Arrays.fill(excess, Double.NaN);
        for (int s : c.states) { int e = k.excess(s); if (e != Integer.MAX_VALUE) excess[s] = e / 4.0; }
        TauSlices.draw(map, c.states, excess, 16, false, routePixel, box, 2, dir.resolve(name + "-slices.png"));
        System.out.printf("wrote %s.png and -slices.png in %s%n", name, dir);
        return regions;
    }

    /**
     * Basins of the excess field: every state at least a tick behind the stable lap is assigned
     * to a peak by descending flood — states in order of falling excess, each joining the basin of
     * its highest already-labelled neighbour, a state with none starting a basin of its own — and
     * basins whose peak stands less than {@link #PROMINENCE} above the saddle to a higher
     * neighbour are merged into that neighbour, until none is left. Neighbours are successors and
     * predecessors either way, the cut included.
     *
     * @return per state, its basin, or -1
     */
    static int[] basins(Clocked k, int[] peakOf, List<Integer> peaks) {
        Corridor c = k.c;
        int n = c.edgeOf.length;
        List<Integer> members = new ArrayList<>();
        for (int s : c.states) { int e = k.excess(s); if (e != Integer.MAX_VALUE && e >= LONGCUT_THRESHOLD) members.add(s); }
        members.sort((a, b) -> Integer.compare(k.excess(b), k.excess(a)));
        int[] label = new int[n];
        Arrays.fill(label, -1);
        int[] out = new int[3];
        for (int s : members) {
            int best = -1, bestE = Integer.MIN_VALUE;
            for (int pass = 0; pass < 2; pass++) {
                int kk = pass == 0 ? c.succRound(s, out) : c.predRound(s, out);
                for (int j = 0; j < kk; j++) {
                    int u = out[j];
                    if (label[u] < 0) continue;
                    int e = k.excess(u);
                    if (e > bestE || (e == bestE && label[u] < best)) { bestE = e; best = label[u]; }
                }
            }
            if (best < 0) { best = peaks.size(); peaks.add(s); }
            label[s] = best;
        }
        // Saddles between basins, then prominence merging until every basin stands a tick proud.
        while (true) {
            java.util.Map<Long, Integer> saddle = new java.util.HashMap<>();
            for (int s : members) {
                int kk = c.succRound(s, out);
                for (int j = 0; j < kk; j++) {
                    int u = out[j];
                    if (label[u] < 0 || label[u] == label[s]) continue;
                    long key = Math.min(label[s], label[u]) * 1_000_000L + Math.max(label[s], label[u]);
                    saddle.merge(key, Math.min(k.excess(s), k.excess(u)), Math::max);
                }
            }
            int[] into = new int[peaks.size()];
            Arrays.fill(into, -1);
            int[] bestSaddle = new int[peaks.size()];
            Arrays.fill(bestSaddle, Integer.MIN_VALUE);
            for (var e : saddle.entrySet()) {
                int a = (int) (e.getKey() / 1_000_000L), b = (int) (e.getKey() % 1_000_000L), sad = e.getValue();
                int pa = k.excess(peaks.get(a)), pb = k.excess(peaks.get(b));
                // The lower peak (ties: the higher id) may merge into the higher.
                int low = pa < pb || (pa == pb && a > b) ? a : b, high = low == a ? b : a;
                if (sad > bestSaddle[low]) { bestSaddle[low] = sad; into[low] = high; }
            }
            int merged = 0;
            int[] target = new int[peaks.size()];
            for (int a = 0; a < peaks.size(); a++) target[a] = a;
            for (int a = 0; a < peaks.size(); a++) {
                if (into[a] < 0 || peaks.get(a) < 0) continue;
                if (k.excess(peaks.get(a)) - bestSaddle[a] < PROMINENCE) { target[a] = into[a]; merged++; }
            }
            if (merged == 0) break;
            // Resolve chains, then relabel; a merged basin keeps the higher peak.
            for (int a = 0; a < peaks.size(); a++) {
                int t = a, guard = 0;
                while (target[t] != t && guard++ < 1000) t = target[t];
                target[a] = t;
            }
            for (int s : members) label[s] = target[label[s]];
            for (int a = 0; a < peaks.size(); a++) if (target[a] != a) peaks.set(a, -1);
        }
        // Then merge basins that are the same place seen at different phases: pixel sets that
        // touch, and tau ranges that overlap by at least half of the shorter. Greedy, best pair
        // first, the merged range recomputed each time, so a small basin bridging two bends joins
        // one and then no longer half-overlaps the other. Two sides of a bulb do not touch.
        {
            int nb = peaks.size();
            double[] lo = new double[nb], hi = new double[nb];
            Arrays.fill(lo, Double.MAX_VALUE);
            Arrays.fill(hi, -Double.MAX_VALUE);
            java.util.Map<Integer, List<Integer>> atPixel = new java.util.HashMap<>();
            for (int s : members) {
                int a = label[s];
                lo[a] = Math.min(lo[a], k.dist.F[s] / 4.0);
                hi[a] = Math.max(hi[a], k.dist.F[s] / 4.0);
                List<Integer> here = atPixel.computeIfAbsent(c.cell(s), q -> new ArrayList<>());
                if (!here.contains(a)) here.add(a);
            }
            List<java.util.Set<Integer>> adj = new ArrayList<>();
            for (int a = 0; a < nb; a++) adj.add(new java.util.HashSet<>());
            int w = c.map.width(), h = c.map.height();
            for (var e : atPixel.entrySet()) {
                int cell = e.getKey(), x = cell % w, y = cell / w;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = x + dx, ny = y + dy;
                        if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                        List<Integer> there = atPixel.get(nx + ny * w);
                        if (there == null) continue;
                        for (int a : e.getValue()) for (int b : there) if (a != b) { adj.get(a).add(b); adj.get(b).add(a); }
                    }
                }
            }
            int[] alias = new int[nb];
            for (int a = 0; a < nb; a++) alias[a] = a;
            while (true) {
                int ba = -1, bb = -1;
                double best = OVERLAP;
                for (int a = 0; a < nb; a++) {
                    if (alias[a] != a) continue;
                    for (int b : adj.get(a)) {
                        if (b <= a || alias[b] != b) continue;
                        double overlap = Math.min(hi[a], hi[b]) - Math.max(lo[a], lo[b]);
                        double shorter = Math.min(hi[a] - lo[a], hi[b] - lo[b]);
                        double f = overlap < 0 ? -1 : shorter <= 0 ? 1 : overlap / shorter;
                        if (f >= best) { best = f; ba = a; bb = b; }
                    }
                }
                if (ba < 0) break;
                // Keep the higher peak's id.
                int keep = k.excess(peaks.get(ba)) >= k.excess(peaks.get(bb)) ? ba : bb, drop = keep == ba ? bb : ba;
                lo[keep] = Math.min(lo[keep], lo[drop]);
                hi[keep] = Math.max(hi[keep], hi[drop]);
                for (int o : adj.get(drop)) { if (o == keep) continue; adj.get(o).remove(drop); adj.get(o).add(keep); adj.get(keep).add(o); }
                adj.get(keep).remove(drop);
                adj.get(drop).clear();
                alias[drop] = keep;
            }
            for (int a = 0; a < nb; a++) { int t = a, guard = 0; while (alias[t] != t && guard++ < nb) t = alias[t]; alias[a] = t; }
            for (int s : members) label[s] = alias[label[s]];
            for (int a = 0; a < nb; a++) if (alias[a] != a) peaks.set(a, -1);
        }
        // Compact the labels.
        int[] fresh = new int[peaks.size()];
        Arrays.fill(fresh, -1);
        List<Integer> kept = new ArrayList<>();
        for (int a = 0; a < peaks.size(); a++) if (peaks.get(a) >= 0) { fresh[a] = kept.size(); kept.add(peaks.get(a)); }
        for (int s : members) label[s] = fresh[label[s]];
        peaks.clear();
        peaks.addAll(kept);
        for (int s : members) peakOf[s] = peaks.get(label[s]);
        return label;
    }

    private static int find(int[] parent, int a) {
        while (parent[a] != a) { parent[a] = parent[parent[a]]; a = parent[a]; }
        return a;
    }

    /** Two touching basins are one place at different phases when their tau ranges overlap by this much of the shorter. */
    static final double OVERLAP = 0.5;

    /** A basin's peak must stand this many quarter-ticks above its saddle to a higher basin to be a longcut of its own: one tick. */
    static final int PROMINENCE = 4;

    /** {@code F(s)} in quarter-ticks. */
    private static int dist(Clocked k, int s) { return k.dist.F[s]; }

    /**
     * From {@code s}, the most quarter-ticks a path can take within its basin before returning to
     * the line: {@code R(s)} to stop here, or four more and the best from a successor in the
     * basin. Memoised; the corridor without its cut is acyclic, and a revisit reads zero.
     */
    private static double longestForced(Corridor c, Clocked k, int[] comp, int id, int s, double[] best, int[] bestNext) {
        if (!Double.isNaN(best[s])) return best[s];
        best[s] = 0;
        bestNext[s] = -1;
        int[] out = new int[3];
        int kk = c.succ(s, out);
        double top = k.dist.R[s];
        int next = -1;
        for (int j = 0; j < kk; j++) {
            int u = out[j];
            if (comp[u] != id) continue;
            double v = 4 + longestForced(c, k, comp, id, u, best, bestNext);
            if (v > top) { top = v; next = u; }
        }
        best[s] = top;
        bestNext[s] = next;
        return top;
    }

    /** The excess per pixel — the largest over its headings, in ticks, black to yellow — with each region's id at its deepest pixel. */
    static void drawExcess(Corridor c, Clocked k, List<Region> regions, Path out) throws IOException {
        int w = c.map.width(), h = c.map.height();
        boolean[] any = new boolean[c.edgeOf.length];
        for (int s : c.states) any[s] = true;
        int[] box = crop(c, any, 4);
        int scale = 3;
        int[] paint = new int[w * h];
        double[] top = new double[w * h];
        Arrays.fill(top, Double.NaN);
        for (int s : c.states) {
            int e = k.excess(s);
            if (e == Integer.MAX_VALUE) continue;
            int i = c.cell(s);
            if (Double.isNaN(top[i]) || e > top[i]) top[i] = e;
        }
        double cap = 4;
        for (double v : top) if (!Double.isNaN(v)) cap = Math.max(cap, v);   // the route's own deepest excess saturates
        for (int i = 0; i < paint.length; i++) {
            if (Double.isNaN(top[i])) { paint[i] = c.map.oob(i % w, i / w) ? 0 : 0x14161A; continue; }
            double t = Math.max(0, Math.min(1, top[i] / cap));
            int r = (int) (255 * Math.min(1, 1.5 * t)), g = (int) (255 * Math.max(0, Math.min(1, 1.5 * t - 0.3))), b = (int) (90 * Math.max(0, 1 - 2 * t));
            paint[i] = top[i] <= 0 ? 0x30343C : (r << 16) | (g << 8) | b;
        }
        BufferedImage img = new BufferedImage((box[2] - box[0]) * scale, (box[3] - box[1]) * scale, BufferedImage.TYPE_INT_RGB);
        for (int y = box[1]; y < box[3]; y++) {
            for (int x = box[0]; x < box[2]; x++) {
                int rgb = c.map.oob(x, y) ? 0x000000 : paint[x + y * w];
                for (int sy = 0; sy < scale; sy++) for (int sx = 0; sx < scale; sx++) img.setRGB((x - box[0]) * scale + sx, (y - box[1]) * scale + sy, rgb);
            }
        }
        java.awt.Graphics2D g2 = img.createGraphics();
        g2.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 12));
        int labelled = 0;
        for (Region r : regions) {
            if (r.loss < 1 || labelled++ >= 30) continue;
            int x = (c.x(r.deepest) - box[0]) * scale, y = (c.y(r.deepest) - box[1]) * scale;
            g2.setColor(java.awt.Color.WHITE);
            g2.drawString(String.valueOf(r.id), x + 4, y - 2);
        }
        g2.dispose();
        javax.imageio.ImageIO.write(img, "png", out.toFile());
    }

    // ------------------------------------------------------------------------------ routes

    /**
     * Every simple cycle of a map's edge graph, each listed from its lowest edge, with whether it
     * is the unsteered cycle (every edge stable) and whether it scores. The routes a clock or a
     * longcut search runs over, found rather than typed.
     */
    public static List<int[]> routes(SolverFacts f) {
        int edges = f.edges();
        List<List<Integer>> succ = new ArrayList<>();
        for (int e = 0; e < edges; e++) succ.add(new ArrayList<>());
        for (int e = 0; e < edges; e++) for (int p : f.predecessors(e)) succ.get(p).add(e);
        List<int[]> found = new ArrayList<>();
        for (int start = 0; start < edges; start++) {
            ArrayDeque<Integer> path = new ArrayDeque<>();
            boolean[] on = new boolean[edges];
            cycles(start, start, succ, path, on, found);
        }
        System.out.printf("%d simple cycles in the edge graph:%n", found.size());
        for (int[] r : found) {
            boolean stable = true, scoring = false;
            for (int e : r) { stable &= f.stable(e); scoring |= f.scoring(e); }
            System.out.printf("   %s%s%s%n", Arrays.toString(r), stable ? "  stable" : "", scoring ? "  scoring" : "");
        }
        return found;
    }

    private static void cycles(int start, int at, List<List<Integer>> succ, ArrayDeque<Integer> path, boolean[] on, List<int[]> found) {
        path.addLast(at);
        on[at] = true;
        for (int next : succ.get(at)) {
            if (next == start) {
                found.add(path.stream().mapToInt(Integer::intValue).toArray());
            } else if (next > start && !on[next]) {
                cycles(start, next, succ, path, on, found);
            }
        }
        on[at] = false;
        path.removeLast();
    }

    // ---------------------------------------------------------------------------- renders

    private static final int[] PALETTE = {0xFFFFFF, 0x3CB44B, 0xFFE119, 0xF032E6, 0x46F0F0, 0xF58231,
            0x911EB4, 0xBCF60C, 0xAAFFC3, 0xE6BEFF};

    /** The crop: the bounding box of {@code [A, Z]}, padded. */
    static int[] crop(Corridor c, boolean[] within, int pad) {
        int w = c.map.width(), h = c.map.height();
        int lx = w, hx = 0, ly = h, hy = 0;
        for (int s : c.states) {
            if (!within[s]) continue;
            int x = c.x(s), y = c.y(s);
            lx = Math.min(lx, x); hx = Math.max(hx, x);
            ly = Math.min(ly, y); hy = Math.max(hy, y);
        }
        return new int[]{Math.max(0, lx - pad), Math.max(0, ly - pad), Math.min(w, hx + pad + 1), Math.min(h, hy + pad + 1)};
    }

    /**
     * The join field, one panel per path, over the states upstream of {@code B}. Three rows per
     * panel: per pixel the <b>shortest</b> join over its live headings; per pixel the join at the
     * <b>coasting heading</b> — that of the nearest state of the coasting path — which is a boid
     * coasting down the corridor on the wrong phase; and per pixel the <b>jaggedness</b>, the
     * largest change in join time to a 4-adjacent pixel at the same heading, over headings. Hue
     * runs blue (0) to yellow (the longest join in any panel; for jaggedness, 8); red is a state
     * that cannot join; dark is a pixel with no such state.
     */
    static void drawJoin(Corridor c, boolean[] upstream, int[] coast, int[][] fields, String[] titles, Path out)
            throws IOException {
        int w = c.map.width(), h = c.map.height();
        int[] box = crop(c, upstream, 3);
        int bw = box[2] - box[0], bh = box[3] - box[1];
        int scale = 3, gap = 8, label = 14, rows = 3;

        // The coasting heading at every pixel: that of the nearest coasting state, by pixel distance.
        int[] coastHeading = new int[w * h];
        Arrays.fill(coastHeading, -1);
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int s : coast) { coastHeading[c.cell(s)] = c.d(s); queue.add(c.cell(s)); }
        while (!queue.isEmpty()) {
            int i = queue.poll();
            int x = i % w, y = i / w;
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int nx = x + dx, ny = y + dy;
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                    int j = nx + ny * w;
                    if (coastHeading[j] < 0) { coastHeading[j] = coastHeading[i]; queue.add(j); }
                }
            }
        }

        int longest = 1;
        for (int[] f : fields) for (int s : c.states) if (upstream[s]) longest = Math.max(longest, f[s]);
        final int JAG_SCALE = 8;

        BufferedImage img = new BufferedImage(fields.length * (bw * scale + gap), rows * (bh * scale + gap + label),
                BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g2 = img.createGraphics();
        g2.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 11));
        g2.setColor(java.awt.Color.WHITE);
        for (int p = 0; p < fields.length; p++) {
            int[] f = fields[p];
            for (int row = 0; row < rows; row++) {
                int ox = p * (bw * scale + gap), oy = row * (bh * scale + gap + label) + label;
                for (int y = 0; y < bh; y++) {
                    for (int x = 0; x < bw; x++) {
                        int px = x + box[0], py = y + box[1], cell = px + py * w;
                        int rgb = c.map.oob(px, py) ? 0x000000 : 0x14161A;
                        int value = Integer.MIN_VALUE;   // MIN: no state; -1: locked; else the value
                        if (row == 0) {
                            for (int d = 0; d < c.turns; d++) {
                                int s = cell * c.turns + d;
                                if (!c.map.alive(px, py, d) || !upstream[s]) continue;
                                if (f[s] < 0) { if (value == Integer.MIN_VALUE) value = -1; }
                                else if (value < 0) value = f[s];
                                else value = Math.min(value, f[s]);
                            }
                        } else if (row == 1) {
                            int d = coastHeading[cell];
                            if (d >= 0 && c.map.alive(px, py, d) && upstream[cell * c.turns + d]) value = f[cell * c.turns + d];
                        } else {
                            for (int d = 0; d < c.turns; d++) {
                                int s = cell * c.turns + d;
                                if (!c.map.alive(px, py, d) || !upstream[s] || f[s] < 0) continue;
                                for (int[] n : new int[][]{{1, 0}, {0, 1}, {-1, 0}, {0, -1}}) {
                                    int nx = px + n[0], ny = py + n[1];
                                    if (nx < 0 || ny < 0 || nx >= w || ny >= h || !c.map.alive(nx, ny, d)) continue;
                                    int t = c.map.index(nx, ny, d);
                                    if (!upstream[t]) continue;
                                    int jag = f[t] < 0 ? JAG_SCALE : Math.abs(f[s] - f[t]);
                                    value = Math.max(value == Integer.MIN_VALUE ? 0 : value, jag);
                                }
                            }
                        }
                        int top = row == 2 ? JAG_SCALE : longest;
                        if (value == -1) rgb = 0xE6194B;
                        else if (value >= 0) {
                            double t = Math.min(1.0, value / (double) top);
                            int r = (int) (40 + 215 * t), g = (int) (80 + 175 * t), b = (int) (220 - 200 * t);
                            rgb = (r << 16) | (g << 8) | b;
                        } else if (!c.map.oob(px, py)) {
                            boolean corridor = false;
                            for (int d = 0; d < c.turns && !corridor; d++) corridor = upstream[cell * c.turns + d];
                            rgb = corridor ? 0x30343C : 0x14161A;
                        }
                        for (int sy = 0; sy < scale; sy++) for (int sx = 0; sx < scale; sx++) {
                            img.setRGB(ox + x * scale + sx, oy + y * scale + sy, rgb);
                        }
                    }
                }
                String what = row == 0 ? "shortest join over headings (0..." + longest + ")"
                        : row == 1 ? "join at the coasting heading (0..." + longest + ")"
                        : "jaggedness: largest step in join time to a same-heading neighbour (0..." + JAG_SCALE + "+)";
                g2.drawString(titles[p] + ": " + what, ox + 2, oy - 3);
            }
        }
        g2.dispose();
        javax.imageio.ImageIO.write(img, "png", out.toFile());
    }

    static void drawCover(Corridor c, boolean[] within, boolean[] lane, int[] P, GateSet[] gates, Path out)
            throws IOException {
        int w = c.map.width();
        int[] box = crop(c, within, 4);
        int scale = 4;
        int[] paint = new int[w * c.map.height()];
        Arrays.fill(paint, 0x000000);
        for (int s : c.states) paint[c.cell(s)] = within[s] ? 0x30343C : 0x1A1C20;
        int[] gateColour = {0xF58231, 0x3CB44B, 0x46F0F0, 0xE6194B};
        for (int g = 0; g < 4; g++) for (int s : gates[g].states) paint[c.cell(s)] = gateColour[g];
        for (int i = 0; i < paint.length; i++) if (lane[i]) paint[i] = 0x25408F;
        for (int s : P) paint[c.cell(s)] = lane[c.cell(s)] ? 0xFFFFFF : 0xFFE119;
        BufferedImage img = new BufferedImage((box[2] - box[0]) * scale, (box[3] - box[1]) * scale, BufferedImage.TYPE_INT_RGB);
        for (int y = box[1]; y < box[3]; y++) {
            for (int x = box[0]; x < box[2]; x++) {
                int rgb = paint[x + y * w];
                for (int sy = 0; sy < scale; sy++) for (int sx = 0; sx < scale; sx++) {
                    img.setRGB((x - box[0]) * scale + sx, (y - box[1]) * scale + sy, rgb);
                }
            }
        }
        javax.imageio.ImageIO.write(img, "png", out.toFile());
    }

}
