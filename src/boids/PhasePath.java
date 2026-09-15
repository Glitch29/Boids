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
 * A phase-complete path: the user's non-constructive definition, a construction tried against
 * it, and the check that says whether what was built satisfies it.
 *
 * <h2>The definition, as given 2026-09-14</h2>
 * For a set of states {@code S} between gate {@code B} and gate {@code Y} on route {@code R}, a
 * phase-complete path {@code P} is a cover of {@code S} such that
 * <ul>
 *   <li>every point of {@code P} can backward-navigate to {@code B} while remaining in {@code P};</li>
 *   <li>every point of {@code P} can forward-navigate to {@code Y} while remaining in {@code P};</li>
 *   <li>for some gates {@code A} and {@code Z} on {@code R}: the set of {@code N}th predecessors
 *       of {@code P} within {@code AZ}, projected to {@code (x, y)}, has a single exterior border
 *       with no holes, and for some {@code N} it saturates {@code A}; and the set of {@code N}th
 *       successors of {@code P} within {@code AZ}, projected, has a single exterior border with no
 *       holes, and for some {@code N} it saturates {@code Z}.</li>
 * </ul>
 * The user's text paired the successor funnel with {@code A} and the predecessor funnel with
 * {@code Z}; the pairing is read the other way round here, since a funnel of successors runs
 * forward and can only meet the downstream gate. <b>Read literally: the {@code N}th-successor set
 * is exactly {@code N} steps on, not within {@code N}.</b> The cumulative sets are reported beside
 * the exact ones so the difference is visible.
 *
 * <h2>The construction tried</h2>
 * {@code P} is a union of <b>strands</b>. The <b>lane</b> is the set of pixels {@code S} sweeps:
 * its own pixels and, for each step, the pixels the step passes through ({@link NavMap#stepPath}).
 * A single-phase path covers one pixel in four of its lane; the other three are what a boid on
 * another phase of the step lattice stands on. So, while some lane pixel is uncovered by
 * {@code P}, add the cheapest path from {@code B} to {@code Y} within {@code [B, Y]} through any
 * state at that pixel, the cost of a state being the squared distance of its pixel from the lane.
 * {@code S} itself is the first strand. Each strand starts in {@code B} and ends in {@code Y}, so
 * the two navigation conditions hold by construction; the funnel conditions are then measured,
 * not assumed. The definition does not determine {@code P} uniquely — the conditions are not
 * closed under intersection — so the strand rule is a choice, and is named as one.
 *
 * <h2>The setup, the user's</h2>
 * A route, cut at one crossing far from the stretch under study, so "between" is well defined;
 * four states {@code a < b < y < z} on the route's coasting path — the straight-travel cycle —
 * with gates around each by phantom edge insertion, the same conditioning {@code GateSplit} uses
 * (partial tick, perfected both ways, unioned with its inverse, perfected again); a gate's states
 * are the landing set of its insertion boundary, the first states past it. {@code S} is the
 * coasting states from {@code b} to {@code y}.
 */
public final class PhasePath {
    private PhasePath() {}

    /** The four gates in route order. */
    public enum Which { A, B, Y, Z }

    /** One gate: its seed on the coasting path, its conditioned core, {@code E<core}, and its landing set. */
    public record GateSet(Which which, int seed, int[] core, boolean[] before, int[] landing) {}

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
        Cover cover = cover(c, S, lane, betweenBY, B, Y);
        System.out.printf("S: %d states; lane %d pixels; P: %d states in %d strands, covering %d lane pixels,"
                        + " %d pixels off the lane%n", S.length, laneCount, cover.P.length, cover.strands.size(),
                cover.laneCovered, cover.offLane);
        for (int i = 0; i < cover.strands.size(); i++) {
            int[] st = cover.strands.get(i);
            System.out.printf("  strand %d: %3d states, from (%d,%d,%d) to (%d,%d,%d), cost %.0f%n", i, st.length,
                    c.x(st[0]), c.y(st[0]), c.d(st[0]), c.x(st[st.length - 1]), c.y(st[st.length - 1]),
                    c.d(st[st.length - 1]), cover.costs.get(i));
        }

        // The two navigation conditions, checked rather than assumed.
        System.out.printf("navigation within P: %d of %d states cannot reach Y forward, %d cannot reach B backward%n",
                unreaching(c, cover.P, Y.landing, true), cover.P.length, unreaching(c, cover.P, B.landing, false));

        // The funnels.
        Funnel pred = funnel(c, cover.P, betweenAZ, A.landing, false);
        Funnel succ = funnel(c, cover.P, betweenAZ, Z.landing, true);
        report("predecessors, toward A", pred, A.landing.length);
        report("successors, toward Z", succ, Z.landing.length);
        diagnose("predecessors", c, pred, A.landing, betweenAZ);
        diagnose("successors", c, succ, Z.landing, betweenAZ);

        Path dir = Path.of("render", "phase-path");
        Files.createDirectories(dir);
        String name = preset.name().toLowerCase() + "-" + preset.ingest().hash() + "-e" + edge;
        drawCover(c, betweenAZ, lane, cover.P, gates, dir.resolve(name + "-cover.png"));
        drawFunnels(c, betweenAZ, pred, succ, cover.P, dir.resolve(name + "-funnels.png"));
        System.out.printf("wrote %s-cover.png and -funnels.png in %s%n", name, dir);
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
        return new GateSet(which, seed, core, before, landing.stream().mapToInt(Integer::intValue).toArray());
    }

    /** States past {@code from}'s gate and not past {@code to}'s: not in {@code E<from}, in {@code E<to} or its landing. */
    static boolean[] between(Corridor c, GateSet from, GateSet to) {
        boolean[] in = new boolean[c.edgeOf.length];
        boolean[] toLanding = new boolean[c.edgeOf.length];
        for (int s : to.landing) toLanding[s] = true;
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
        int[] P;
        final List<int[]> strands = new ArrayList<>();
        final List<Double> costs = new ArrayList<>();
        int laneCovered, offLane;
    }

    /**
     * {@code S}, then one strand per uncovered lane pixel in lane order: the cheapest path from
     * {@code B}'s landing set to {@code Y}'s within {@code [B, Y]} through some state at that
     * pixel, a state costing the square of its pixel's distance from the lane.
     */
    static Cover cover(Corridor c, int[] S, boolean[] lane, boolean[] within, GateSet B, GateSet Y) {
        int n = c.edgeOf.length, w = c.map.width();
        int[] dist = laneDistance(c, lane);
        double[] cost = new double[n];
        for (int s : c.states) if (within[s]) { double d = dist[c.cell(s)]; cost[s] = d * d; }

        double[] fwd = new double[n], bwd = new double[n];
        int[] fwdFrom = new int[n], bwdTo = new int[n];
        dijkstra(c, within, cost, B.landing, true, fwd, fwdFrom);
        dijkstra(c, within, cost, Y.landing, false, bwd, bwdTo);

        Cover cover = new Cover();
        boolean[] inP = new boolean[n];
        boolean[] covered = new boolean[w * c.map.height()];
        for (int s : S) { inP[s] = true; covered[c.cell(s)] = true; }
        cover.strands.add(S.clone());
        cover.costs.add(0.0);

        // Lane pixels in the order S sweeps them.
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

        for (int pixel : order) {
            if (covered[pixel]) continue;
            int best = -1;
            double bestCost = Double.POSITIVE_INFINITY;
            for (int d = 0; d < c.turns; d++) {
                int s = pixel * c.turns + d;
                if (!within[s] || fwd[s] == Double.POSITIVE_INFINITY || bwd[s] == Double.POSITIVE_INFINITY) continue;
                double total = fwd[s] + bwd[s] - cost[s];
                if (total < bestCost) { bestCost = total; best = s; }
            }
            if (best < 0) {
                System.out.printf("  lane pixel (%d,%d) has no B->Y path through it within [B,Y]%n",
                        pixel % w, pixel / w);
                continue;
            }
            List<Integer> strand = new ArrayList<>();
            for (int s = best; s >= 0; s = fwdFrom[s]) strand.add(0, s);
            for (int s = bwdTo[best]; s >= 0; s = bwdTo[s]) strand.add(s);
            int[] st = strand.stream().mapToInt(Integer::intValue).toArray();
            for (int s : st) { inP[s] = true; covered[c.cell(s)] = true; }
            cover.strands.add(st);
            cover.costs.add(bestCost);
        }

        cover.P = Arrays.stream(c.states).filter(s -> inP[s]).toArray();
        for (int s : cover.P) if (!lane[c.cell(s)]) cover.offLane++;
        for (int i = 0; i < covered.length; i++) if (lane[i] && covered[i]) cover.laneCovered++;
        return cover;
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

    // ---------------------------------------------------------------------------- funnels

    /** One funnel: per {@code N}, the exact set, its projection's topology, and the gate's saturation. */
    static final class Funnel {
        final List<int[]> exact = new ArrayList<>();
        final List<Integer> components = new ArrayList<>(), holes = new ArrayList<>(), saturated = new ArrayList<>();
        final List<Integer> cumComponents = new ArrayList<>(), cumHoles = new ArrayList<>(), cumSaturated = new ArrayList<>();
        final List<Integer> pixels = new ArrayList<>();
    }

    /**
     * The {@code N}th predecessors (or successors) of {@code P} within {@code [A, Z]}, exactly
     * {@code N} steps away, for every {@code N} until the set empties.
     */
    static Funnel funnel(Corridor c, int[] P, boolean[] within, int[] gate, boolean forward) {
        int n = c.edgeOf.length, w = c.map.width(), h = c.map.height();
        boolean[] inGate = new boolean[n];
        for (int s : gate) inGate[s] = true;
        Funnel fn = new Funnel();
        int[] current = Arrays.stream(P).filter(s -> within[s]).toArray();
        boolean[] cumulative = new boolean[w * h];
        boolean[] cumGate = new boolean[n];
        int cumSat = 0;
        int[] out = new int[3];
        for (int N = 0; current.length > 0 && N <= 2000; N++) {
            boolean[] pix = new boolean[w * h];
            int sat = 0;
            for (int s : current) {
                pix[c.cell(s)] = true;
                cumulative[c.cell(s)] = true;
                if (inGate[s]) { sat++; if (!cumGate[s]) { cumGate[s] = true; cumSat++; } }
            }
            int[] topo = topology(pix, w, h), cum = topology(cumulative, w, h);
            fn.exact.add(current);
            fn.pixels.add(topo[2]);
            fn.components.add(topo[0]);
            fn.holes.add(topo[1]);
            fn.saturated.add(sat);
            fn.cumComponents.add(cum[0]);
            fn.cumHoles.add(cum[1]);
            fn.cumSaturated.add(cumSat);

            boolean[] next = new boolean[n];
            int count = 0;
            for (int s : current) {
                int k = forward ? c.succ(s, out) : c.pred(s, out);
                for (int j = 0; j < k; j++) {
                    int u = out[j];
                    if (within[u] && !next[u]) { next[u] = true; count++; }
                }
            }
            int[] following = new int[count];
            for (int s = 0, k = 0; s < n && k < count; s++) if (next[s]) following[k++] = s;
            current = following;
        }
        return fn;
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
    static int[] holePixels(boolean[] pix, int w, int h) {
        int[] label2 = new int[w * h];
        boolean[] inv = new boolean[w * h];
        for (int i = 0; i < inv.length; i++) inv[i] = !pix[i];
        label(inv, w, h, false, label2, true);
        int outside = label2[0];
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < inv.length; i++) if (inv[i] && label2[i] != outside) out.add(i);
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * For the first {@code N} at which the exact set has holes, and the first at which it is
     * not one component, says what stands on the pixels concerned — a diagnostic for whether a
     * failure is the path's or the lattice's.
     */
    static void diagnose(String title, Corridor c, Funnel fn, int[] gate, boolean[] within) {
        int w = c.map.width(), h = c.map.height();
        boolean[] inGate = new boolean[c.edgeOf.length];
        for (int s : gate) inGate[s] = true;
        // Every N at which each state appears, so a state's range of distances can be read.
        java.util.Map<Integer, List<Integer>> at = new java.util.HashMap<>();
        for (int N = 0; N < fn.exact.size(); N++) {
            for (int s : fn.exact.get(N)) at.computeIfAbsent(s, k -> new ArrayList<>()).add(N);
        }
        boolean holesShown = false;
        int splitsShown = 0;
        for (int N = 0; N < fn.exact.size() && (!holesShown || splitsShown < 1); N++) {
            boolean[] pix = new boolean[w * h];
            for (int s : fn.exact.get(N)) pix[c.cell(s)] = true;
            if (fn.holes.get(N) > 0 && !holesShown) {
                holesShown = true;
                System.out.printf("%n%s N=%d: %d holes; what stands on them:%n", title, N, fn.holes.get(N));
                for (int i : holePixels(pix, w, h)) {
                    StringBuilder sb = new StringBuilder(String.format("  (%d,%d):", i % w, i / w));
                    int live = 0;
                    for (int d = 0; d < c.turns; d++) {
                        int s = i * c.turns + d;
                        if (!c.map.alive(i % w, i / w, d)) continue;
                        live++;
                        List<Integer> ns = at.get(s);
                        sb.append(String.format(" d%d%s%s", d, within[s] ? "" : "(outside AZ)",
                                ns == null ? "" : "@" + ns.get(0) + ".." + ns.get(ns.size() - 1)));
                    }
                    System.out.println(live == 0 ? sb + " dead" : sb);
                }
            }
            if (fn.components.get(N) == 1) continue;
            splitsShown++;
            int[] lab = new int[w * h];
            int comps = label(pix, w, h, true, lab, true);
            int[] size = new int[comps + 1];
            for (int v : lab) size[v]++;
            int largest = 1;
            for (int k = 2; k <= comps; k++) if (size[k] > size[largest]) largest = k;
            System.out.printf("%n%s N=%d: %d components; the small ones:%n", title, N, comps);
            for (int s : fn.exact.get(N)) {
                if (lab[c.cell(s)] == largest) continue;
                List<Integer> ns = at.get(s);
                System.out.printf("  comp %d (%d,%d,%d)%s at N=%d..%d (%d values)%n", lab[c.cell(s)], c.x(s), c.y(s),
                        c.d(s), inGate[s] ? " gate" : "", ns.get(0), ns.get(ns.size() - 1), ns.size());
            }
            // And the nearest pixel of the largest component, with what stands between.
            for (int s : fn.exact.get(N)) {
                if (lab[c.cell(s)] == largest) continue;
                int best = -1, bd = Integer.MAX_VALUE;
                for (int t : fn.exact.get(N)) {
                    if (lab[c.cell(t)] != largest) continue;
                    int d = Math.max(Math.abs(c.x(t) - c.x(s)), Math.abs(c.y(t) - c.y(s)));
                    if (d < bd) { bd = d; best = t; }
                }
                if (best < 0) break;
                System.out.printf("    nearest of the largest to (%d,%d): (%d,%d,%d), %d px; between them:%n",
                        c.x(s), c.y(s), c.x(best), c.y(best), c.d(best), bd);
                int x0 = Math.min(c.x(s), c.x(best)), x1 = Math.max(c.x(s), c.x(best));
                int y0 = Math.min(c.y(s), c.y(best)), y1 = Math.max(c.y(s), c.y(best));
                for (int y = y0; y <= y1; y++) {
                    for (int x = x0; x <= x1; x++) {
                        StringBuilder sb = new StringBuilder(String.format("      (%d,%d):", x, y));
                        for (int d = 0; d < c.turns; d++) {
                            if (!c.map.alive(x, y, d)) continue;
                            int t = c.map.index(x, y, d);
                            sb.append(String.format(" d%d%s", d, !c.in(t) ? "(off route)" : !within[t] ? "(outside AZ)"
                                    : inGate[t] ? "(gate)" : ""));
                        }
                        System.out.println(sb);
                    }
                }
                break;
            }
        }
    }

    static void report(String title, Funnel fn, int gateSize) {
        System.out.printf("%n-- %s: %d steps until the funnel leaves [A,Z] --%n", title, fn.exact.size());
        System.out.printf("%4s %7s %6s %5s %5s %6s   %5s %5s %6s%n", "N", "states", "pixels", "comps",
                "holes", "gate", "comps", "holes", "gate");
        System.out.printf("%4s %7s %6s %5s %5s %6s   %5s %5s %6s%n", "", "", "", "exact", "", "", "cumul", "", "");
        int firstBad = -1, firstSat = -1, firstCumSat = -1, lastBad = -1;
        for (int N = 0; N < fn.exact.size(); N++) {
            boolean bad = fn.components.get(N) != 1 || fn.holes.get(N) != 0;
            if (bad && firstBad < 0) firstBad = N;
            if (bad) lastBad = N;
            if (fn.saturated.get(N) == gateSize && firstSat < 0) firstSat = N;
            if (fn.cumSaturated.get(N) == gateSize && firstCumSat < 0) firstCumSat = N;
            boolean show = N < 6 || N % 5 == 0 || bad || N == fn.exact.size() - 1
                    || (N > 0 && (fn.components.get(N - 1) != 1 || fn.holes.get(N - 1) != 0));
            if (!show) continue;
            System.out.printf("%4d %7d %6d %5d %5d %3d/%-3d  %5d %5d %3d/%-3d%s%n", N, fn.exact.get(N).length,
                    fn.pixels.get(N), fn.components.get(N), fn.holes.get(N), fn.saturated.get(N), gateSize,
                    fn.cumComponents.get(N), fn.cumHoles.get(N), fn.cumSaturated.get(N), gateSize,
                    bad ? "   <--" : "");
        }
        System.out.printf("exact: %s; gate fully saturated at N = %s. cumulative: gate saturated at N = %s%n",
                firstBad < 0 ? "every N simply connected" : "not simply connected at N = " + firstBad + " .. " + lastBad,
                firstSat < 0 ? "never" : String.valueOf(firstSat), firstCumSat < 0 ? "never" : String.valueOf(firstCumSat));
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

    static void drawCover(Corridor c, boolean[] within, boolean[] lane, int[] P, GateSet[] gates, Path out)
            throws IOException {
        int w = c.map.width();
        int[] box = crop(c, within, 4);
        int scale = 4;
        int[] paint = new int[w * c.map.height()];
        Arrays.fill(paint, 0x000000);
        for (int s : c.states) paint[c.cell(s)] = within[s] ? 0x30343C : 0x1A1C20;
        int[] gateColour = {0xF58231, 0x3CB44B, 0x46F0F0, 0xE6194B};
        for (int g = 0; g < 4; g++) for (int s : gates[g].landing) paint[c.cell(s)] = gateColour[g];
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

    /**
     * A sheet of tiles, one per chosen {@code N}: the predecessor funnel first, then the
     * successor funnel, each tile cropped to its own set. Components in their own colours, the
     * largest white; holes red; {@code P} outlined in blue where it is not in the set; the
     * corridor dark.
     */
    static void drawFunnels(Corridor c, boolean[] within, Funnel pred, Funnel succ, int[] P, Path out)
            throws IOException {
        int w = c.map.width(), h = c.map.height();
        int scale = 3, gap = 6, perRow = 6, label = 14;
        List<int[]> tiles = new ArrayList<>();   // {row kind, N}
        for (int kind = 0; kind < 2; kind++) {
            Funnel fn = kind == 0 ? pred : succ;
            int last = -1;
            for (int N = 0; N < fn.exact.size(); N++) {
                boolean bad = fn.components.get(N) != 1 || fn.holes.get(N) != 0;
                boolean wasBad = N > 0 && (fn.components.get(N - 1) != 1 || fn.holes.get(N - 1) != 0);
                if (N <= 2 || N % 10 == 0 || (bad && N - last >= 3) || (bad != wasBad)) {
                    tiles.add(new int[]{kind, N});
                    last = N;
                }
            }
        }
        boolean[] inP = new boolean[w * h];
        for (int s : P) inP[c.cell(s)] = true;

        // Crop every tile to its set, but at one common size per kind so the eye can compare.
        int[][] boxes = new int[tiles.size()][];
        int tw = 0, th = 0;
        for (int t = 0; t < tiles.size(); t++) {
            Funnel fn = tiles.get(t)[0] == 0 ? pred : succ;
            int[] set = fn.exact.get(tiles.get(t)[1]);
            int lx = w, hx = 0, ly = h, hy = 0;
            for (int s : set) {
                lx = Math.min(lx, c.x(s)); hx = Math.max(hx, c.x(s));
                ly = Math.min(ly, c.y(s)); hy = Math.max(hy, c.y(s));
            }
            boxes[t] = new int[]{Math.max(0, lx - 3), Math.max(0, ly - 3), Math.min(w, hx + 4), Math.min(h, hy + 4)};
            tw = Math.max(tw, boxes[t][2] - boxes[t][0]);
            th = Math.max(th, boxes[t][3] - boxes[t][1]);
        }
        int rows = (tiles.size() + perRow - 1) / perRow;
        BufferedImage img = new BufferedImage(perRow * (tw * scale + gap), rows * (th * scale + gap + label),
                BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g2 = img.createGraphics();
        g2.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 11));
        for (int t = 0; t < tiles.size(); t++) {
            int kind = tiles.get(t)[0], N = tiles.get(t)[1];
            Funnel fn = kind == 0 ? pred : succ;
            int[] box = boxes[t];
            int bw = box[2] - box[0], bh = box[3] - box[1];
            int ox = (t % perRow) * (tw * scale + gap), oy = (t / perRow) * (th * scale + gap + label) + label;
            boolean[] pix = new boolean[bw * bh];
            for (int s : fn.exact.get(N)) {
                int x = c.x(s) - box[0], y = c.y(s) - box[1];
                if (x >= 0 && y >= 0 && x < bw && y < bh) pix[x + y * bw] = true;
            }
            // Components on a padded copy, so a hole against the crop edge still counts.
            int pw = bw + 2, ph = bh + 2;
            boolean[] padded = new boolean[pw * ph];
            for (int y = 0; y < bh; y++) for (int x = 0; x < bw; x++) padded[(x + 1) + (y + 1) * pw] = pix[x + y * bw];
            int[] lab = new int[pw * ph];
            int comps = label(padded, pw, ph, true, lab, true);
            int[] size = new int[comps + 1];
            for (int v : lab) size[v]++;
            int largest = 1;
            for (int k = 2; k <= comps; k++) if (size[k] > size[largest]) largest = k;
            boolean[] inv = new boolean[pw * ph];
            for (int i = 0; i < inv.length; i++) inv[i] = !padded[i];
            int[] lab2 = new int[pw * ph];
            label(inv, pw, ph, false, lab2, true);
            int outside = lab2[0];
            for (int y = 0; y < bh; y++) {
                for (int x = 0; x < bw; x++) {
                    int cell = (x + box[0]) + (y + box[1]) * w;
                    int i = (x + 1) + (y + 1) * pw;
                    int rgb = 0x000000;
                    if (padded[i]) {
                        rgb = lab[i] == largest ? 0xFFFFFF : PALETTE[1 + (lab[i] % (PALETTE.length - 1))];
                    } else if (lab2[i] != outside) {
                        rgb = 0xE6194B;
                    } else if (inP[cell]) {
                        rgb = 0x25408F;
                    } else if (!c.map.oob(x + box[0], y + box[1])) {
                        boolean corridor = false;
                        for (int d = 0; d < c.turns && !corridor; d++) corridor = within[cell * c.turns + d];
                        rgb = corridor ? 0x30343C : 0x14161A;
                    }
                    for (int sy = 0; sy < scale; sy++) for (int sx = 0; sx < scale; sx++) {
                        img.setRGB(ox + x * scale + sx, oy + y * scale + sy, rgb);
                    }
                }
            }
            g2.setColor(java.awt.Color.WHITE);
            g2.drawString((kind == 0 ? "pred " : "succ ") + N + ": " + fn.components.get(N) + " comp, "
                    + fn.holes.get(N) + " holes, " + fn.pixels.get(N) + " px", ox + 2, oy - 3);
        }
        g2.dispose();
        javax.imageio.ImageIO.write(img, "png", out.toFile());
    }
}
