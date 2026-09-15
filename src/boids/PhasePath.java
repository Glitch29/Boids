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
 * A phase-complete path: the user's definition, the construction that realises it, and the
 * measurement that says whether what was built has the property the definition is for.
 *
 * <h2>The definition, 2026-09-15</h2>
 * For a set of states {@code S} between gate {@code B} and gate {@code Y} on route {@code R}, a
 * phase-complete path {@code P} is a cover of {@code S} such that
 * <ul>
 *   <li>every point of {@code P} can backward-navigate to {@code B} while remaining in {@code P};</li>
 *   <li>every point of {@code P} can forward-navigate to {@code Y} while remaining in {@code P};</li>
 *   <li>the projection of {@code P} to {@code (x, y)} is diagonally (8-)connected.</li>
 * </ul>
 * It rests on the step table: at radius 40 the steps of adjacent headings differ by 0 or 1 pixel,
 * never diagonally, so a boid that turns once lands within a pixel of where it would have landed
 * straight, and a projection with no diagonal gaps is one every phase of the step lattice can
 * stand on. <b>What the definition is for</b>, in the user's words: the shortest way onto
 * {@code P} from anywhere off it should follow roughly the trajectory continuous physics would
 * take — no detour forced by a phase {@code P} lacks, and no lockout on a stretch with no phase
 * bleed. That is philosophical; what is measured here is its shadow, the <b>join field</b>.
 *
 * <h2>The construction</h2>
 * {@code P} is a union of <b>strands</b>. The <b>lane</b> is the set of pixels {@code S} sweeps —
 * its own and, per step, the samples the step passes through ({@link NavMap#stepPath}) — less the
 * dead pixels a step along a wall sweeps. {@code S} is the first strand. While the projection of
 * {@code P} is not 8-connected, take the earliest two consecutive states of {@code S} whose pixels
 * lie in different components, and add the cheapest {@code B → Y} path within {@code [B, Y]}
 * through the first uncovered pixel of the sweep between them (failing that, through any
 * uncovered pixel 8-adjacent to the upstream component), a state costing the square of its
 * pixel's distance from the lane. The navigation conditions hold by construction, since every
 * strand runs gate to gate; connectivity is the loop's exit. The definition does not determine
 * {@code P} — its conditions are not closed under intersection — so the choice of strand is a
 * tie-break, and it is greedy, so minimal in strands only in the greedy sense. The
 * <b>saturated</b> cover, one strand per lane pixel whether needed or not, is built beside it for
 * comparison.
 *
 * <h2>The measurement</h2>
 * The join field: from every state before {@code Y}, the ticks to its first state of {@code P}.
 * Over the states upstream of {@code B} it is reported as how many cannot join at all and as the
 * histogram of the change in join time between 4-adjacent pixels at the same heading — a field
 * that changes by at most one per pixel is a boid steering onto the path the way it would in the
 * continuum; a jump of seven is a detour round a missing phase. Measured for {@code S} alone,
 * the connected cover and the saturated cover, and drawn.
 *
 * <h2>The setup, the user's</h2>
 * A route, cut at one crossing far from the stretch under study, so "between" is well defined;
 * four states {@code a < b < y < z} on the route's coasting path — the straight-travel cycle —
 * with gates around each by phantom edge insertion, the same conditioning {@code GateSplit} uses
 * (partial tick, perfected both ways, unioned with its inverse, perfected again); a gate's states
 * are its core and the landing set of its insertion boundary together. {@code S} is the coasting
 * states from {@code b} to {@code y}; {@code A} and {@code Z} bound the region the join field is
 * measured over.
 *
 * <h2>The first definition, 2026-09-14, kept as a record</h2>
 * It asked in addition that the sets exactly {@code N} steps before and after {@code P}, within
 * {@code [A, Z]}, project to a single region with no holes for every {@code N}, saturating
 * {@code A} and {@code Z} at some {@code N}. Tested once on dabeone edge 4 it failed twice, on
 * neither count a property of {@code P}: at small {@code N} the side-feeders into a thin path sit
 * at discrete pixels and enclose wall-side pixels whose states need 7–24 steps (the comb), and at
 * the saturating {@code N} the landing set of an insertion boundary is speckled by phase in
 * projection, so saturation and the clip contradict. {@link #funnel} and its report and render
 * are kept behind a flag; {@code EDGES.md} §2a has the account.
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
     * @param funnels whether to also run the funnels of the 2026-09-14 definition on the saturated cover
     */
    public static void run(PresetScenarioParameter preset, SolverFacts.Gate gate, int[] route,
                           int edge, int[] ticks, boolean funnels) throws IOException {
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

        // The covers: the construction, and the saturated one beside it.
        Cover[] covers = {coverConnected(c, S, lane, betweenBY, B, Y), coverSaturated(c, S, lane, betweenBY, B, Y)};
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
        drawJoin(c, upstreamOfB, coast, new int[][]{joinS, joins[0], joins[1]}, new String[]{"S alone", covers[0].kind, covers[1].kind},
                dir.resolve(name + "-join.png"));
        System.out.printf("wrote %s-cover.png and -join.png in %s%n", name, dir);

        if (funnels) {
            // The funnels of the definition of 2026-09-14, kept as the record of its first test.
            Funnel pred = funnel(c, covers[1].P, betweenAZ, A.states, false);
            Funnel succ = funnel(c, covers[1].P, betweenAZ, Z.states, true);
            report("predecessors, toward A", pred, A.states.length);
            report("successors, toward Z", succ, Z.states.length);
            diagnose("predecessors", c, pred, A.states, betweenAZ);
            diagnose("successors", c, succ, Z.states, betweenAZ);
            drawFunnels(c, betweenAZ, pred, succ, covers[1].P, dir.resolve(name + "-funnels.png"));
            System.out.printf("wrote %s-funnels.png%n", name);
        }
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

    /**
     * The saturated cover, kept for comparison: {@code S}, then one strand per uncovered lane
     * pixel in sweep order, whether or not the projection is already connected.
     */
    static Cover coverSaturated(Corridor c, int[] S, boolean[] lane, boolean[] within, GateSet B, GateSet Y) {
        int w = c.map.width(), h = c.map.height();
        Strands strands = new Strands(c, lane, within, B, Y);
        Cover cover = new Cover("saturated");
        boolean[] inP = new boolean[c.edgeOf.length];
        boolean[] covered = new boolean[w * h];
        add(cover, S.clone(), 0, inP, covered, c);
        double[] cost = new double[1];
        for (int pixel : sweepOrder(c, S)) {
            if (covered[pixel] || !lane[pixel]) continue;
            int[] strand = strands.through(pixel, cost);
            if (strand == null) {
                System.out.printf("  lane pixel (%d,%d) has no B->Y path through it within [B,Y]%n", pixel % w, pixel / w);
                continue;
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

    // -------------------------------------------------------------------------------- loop

    /**
     * One closed strand: its states in order from the seed, back round to it; how many times it
     * crossed the cut; its cost; and how far off the lane it strayed.
     */
    public record Strand(int[] states, int laps, double cost, int offLane, int maxDistance, int seedPixel) {
        public int ticks() { return states.length; }
    }

    /**
     * What a state off the lane costs per pixel of distance squared, in ticks. Large, so a strand
     * leaves the lane only where its phase cannot pass: at one, a corner cut a pixel inside saves
     * the tick it costs and the strands drift off the lane and never close the gaps.
     */
    static final double OFF_LANE = 64;

    /** What one run of the loop construction produced. */
    public record Loop(int start, int[] coast, int[] P, List<Strand> strands, int pixels, int components) {
        public int coastTicks() { return coast.length; }
    }

    /**
     * The phase-complete loop of a route: the coasting cycle, then closed strands until the
     * projection is 8-connected. No gates — the cut only counts laps.
     * <p>
     * A strand is the cheapest cycle through some state at an uncovered lane pixel, over the
     * route's states with the cut crossing allowed, a state costing {@code 1 + dist²} — a tick,
     * plus the square of its pixel's distance from the lane — so ticks are what is minimised and
     * the lane is where ties go. The gap is chosen as on a stretch: the earliest two consecutive
     * coasting states, counting from {@code start}, whose pixels lie in different components,
     * and the first uncovered pixel of the sweep between them; failing that, any uncovered pixel
     * 8-adjacent to the upstream component. A strand closes wherever the lattice lets it: one lap
     * if a cycle at that phase exists, more if it must slip phase to get round.
     *
     * @param starts coasting indices to begin the gap search from, one run each, so the
     *               dependence on the starting offset is visible
     */
    public static void loop(PresetScenarioParameter preset, SolverFacts.Gate gate, int[] route, int edge,
                            int[] starts) throws IOException {
        Pipeline.Built b = Pipeline.build(preset, gate);
        EdgeDecomposition.Labelling l = b.labelling();
        NavMap map = l.map();
        int[] edgeOf = l.edge();
        int w = map.width(), h = map.height();

        int m = route.length, slot = -1;
        for (int i = 0; i < m; i++) if (route[i] == edge) slot = i;
        if (slot < 0) throw new IllegalArgumentException("edge " + edge + " is not on " + Arrays.toString(route));
        int[] rotated = new int[m];
        int shift = Math.floorMod(slot - m / 2, m);
        for (int i = 0; i < m; i++) rotated[i] = route[(i + shift) % m];
        Corridor c = new Corridor(map, edgeOf, l.live(), l.liveCount(), rotated);
        System.out.printf("%n=== %s @%s: phase-complete loop of route %s, cut %d->%d, %d route states ===%n",
                preset.name(), preset.ingest().hash(), Arrays.toString(rotated), c.cutFrom, c.cutTo, c.states.length);

        MapStates lattice = MapStates.of(map, Flocking.of(preset.turningRadius()), l.live(), l.liveCount());
        int[] cycle = null;
        for (int[] cy : lattice.cycles(lattice.pureStable(1))) {
            boolean onRoute = true;
            for (int s : cy) if (!c.in(s)) { onRoute = false; break; }
            if (onRoute) { cycle = cy; break; }
        }
        if (cycle == null) throw new IllegalStateException("no straight-travel cycle lies on the route");
        int at = -1;
        for (int i = 0; i < cycle.length; i++) if (c.cut(cycle[(i + cycle.length - 1) % cycle.length], cycle[i])) { at = i; break; }
        int[] coast = new int[cycle.length];
        for (int i = 0; i < cycle.length; i++) coast[i] = cycle[(at + i) % cycle.length];
        int N = coast.length;

        // The lane: every pixel the coasting cycle sweeps, the closing step included, on which
        // some route state can stand.
        boolean[] lane = new boolean[w * h];
        for (int k = 0; k < N; k++) {
            int s = coast[k], u = coast[(k + 1) % N];
            lane[c.cell(s)] = true;
            int[] sweep = map.stepPath(c.d(u));
            for (int q = 0; q + 1 < sweep.length; q += 2) {
                int mx = c.x(s) + sweep[q], my = c.y(s) + sweep[q + 1];
                if (mx >= 0 && my >= 0 && mx < w && my < h) lane[mx + my * w] = true;
            }
        }
        boolean[] routePixel = new boolean[w * h];
        for (int s : c.states) routePixel[c.cell(s)] = true;
        int laneCount = 0, dead = 0;
        for (int i = 0; i < lane.length; i++) {
            if (!lane[i]) continue;
            if (routePixel[i]) laneCount++; else { lane[i] = false; dead++; }
        }
        int[] dist = laneDistance(c, lane);
        double[] cost = new double[edgeOf.length];
        for (int s : c.states) { double d = dist[c.cell(s)]; cost[s] = 1 + OFF_LANE * d * d; }
        System.out.printf("coasting cycle: %d ticks; lane %d pixels (%d dead dropped); %.3f lane pixels per tick%n",
                N, laneCount, dead, laneCount / (double) N);

        double[] tau = geometricTau(c, coast, lane);
        Loop first = null;
        for (int start : starts) {
            Loop loop = buildLoop(c, coast, lane, dist, cost, start);
            reportLoop(c, loop, lane, laneCount);
            Loop pruned = prune(c, loop, lane);
            System.out.printf("   pruned to %d strands, P %d states over %d pixels in %d component%s%n", pruned.strands.size(),
                    pruned.P.length, pruned.pixels, pruned.components, pruned.components == 1 ? "" : "s   <-- NOT CONNECTED");
            reportGeometric(c, pruned, tau);
            if (first == null) first = pruned;
        }

        Path dir = Path.of("render", "phase-path");
        Files.createDirectories(dir);
        String name = preset.name().toLowerCase() + "-" + preset.ingest().hash() + "-loop" + Arrays.toString(rotated).replaceAll("[\\[\\] ]", "").replace(',', '-');
        drawLoop(c, lane, first, dir.resolve(name + ".png"));
        System.out.printf("wrote %s.png in %s%n", name, dir);
    }

    static Loop buildLoop(Corridor c, int[] coast, boolean[] lane, int[] dist, double[] cost, int start) {
        int w = c.map.width(), h = c.map.height(), N = coast.length;
        boolean[] inP = new boolean[c.edgeOf.length];
        boolean[] covered = new boolean[w * h];
        List<Strand> strands = new ArrayList<>();
        for (int s : coast) { inP[s] = true; covered[c.cell(s)] = true; }
        int[] label = new int[w * h];
        boolean[] tried = new boolean[w * h];
        int components = 0;
        boolean[] gaveUp = new boolean[N];
        int unfixed = 0;
        for (int round = 0; round < 400; round++) {
            components = label(covered, w, h, true, label, true);
            if (components == 1) break;
            int k = -1;
            for (int i = 0; i < N; i++) {
                int j = (start + i) % N;
                if (!gaveUp[j] && label[c.cell(coast[j])] != label[c.cell(coast[(j + 1) % N])]) { k = j; break; }
            }
            if (k < 0) {
                System.out.printf("  %d gaps could not be closed by any local cycle; %d components remain%n", unfixed, components);
                break;
            }
            Strand strand = null;
            int s = coast[k], u = coast[(k + 1) % N];
            int[] sweep = c.map.stepPath(c.d(u));
            for (int q = 0; q + 1 < sweep.length && strand == null; q += 2) {
                int mx = c.x(s) + sweep[q], my = c.y(s) + sweep[q + 1];
                if (mx < 0 || my < 0 || mx >= w || my >= h) continue;
                int pixel = mx + my * w;
                if (covered[pixel] || tried[pixel]) continue;
                tried[pixel] = true;
                strand = cheapestCycleThrough(c, pixel, cost, dist, lane);
            }
            // Then the pixels round either end of the gap.
            for (int end : new int[]{s, u}) {
                for (int dy = -1; dy <= 1 && strand == null; dy++) {
                    for (int dx = -1; dx <= 1 && strand == null; dx++) {
                        int nx = c.x(end) + dx, ny = c.y(end) + dy;
                        if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                        int pixel = nx + ny * w;
                        if (covered[pixel] || tried[pixel]) continue;
                        tried[pixel] = true;
                        strand = cheapestCycleThrough(c, pixel, cost, dist, lane);
                    }
                }
            }
            if (strand == null) {
                // Nothing local closes it; leave the gap and look for the next one.
                gaveUp[k] = true;
                unfixed++;
                continue;
            }
            for (int t : strand.states) { inP[t] = true; covered[c.cell(t)] = true; }
            strands.add(strand);
        }
        int[] P = Arrays.stream(c.states).filter(t -> inP[t]).toArray();
        int pixels = 0;
        for (boolean v : covered) if (v) pixels++;
        return new Loop(start, coast, P, strands, pixels, components);
    }

    /**
     * The cheapest cycle through any state at {@code pixel}: Dijkstra from each successor of the
     * state, round the route with the cut crossing allowed, back to the state itself.
     */
    static Strand cheapestCycleThrough(Corridor c, int pixel, double[] cost, int[] dist, boolean[] lane) {
        int n = c.edgeOf.length;
        Strand best = null;
        int[] out = new int[3];
        for (int d = 0; d < c.turns; d++) {
            int s = pixel * c.turns + d;
            if (!c.in(s)) continue;
            double[] bestCost = new double[n];
            int[] from = new int[n];
            Arrays.fill(bestCost, Double.POSITIVE_INFINITY);
            Arrays.fill(from, -1);
            PriorityQueue<long[]> queue = new PriorityQueue<>((a, b) -> Double.compare(
                    Double.longBitsToDouble(a[0]), Double.longBitsToDouble(b[0])));
            int k = c.succRound(s, out);
            for (int j = 0; j < k; j++) {
                int u = out[j];
                bestCost[u] = cost[u];
                from[u] = s;
                queue.add(new long[]{Double.doubleToLongBits(bestCost[u]), u});
            }
            double closed = Double.POSITIVE_INFINITY;
            int last = -1;
            while (!queue.isEmpty()) {
                long[] top = queue.poll();
                double dd = Double.longBitsToDouble(top[0]);
                int v = (int) top[1];
                if (dd > bestCost[v]) continue;
                if (dd >= closed) break;
                int kk = c.succRound(v, out);
                for (int j = 0; j < kk; j++) {
                    int u = out[j];
                    if (u == s) { if (dd + cost[s] < closed) { closed = dd + cost[s]; last = v; } continue; }
                    double nd = dd + cost[u];
                    if (nd < bestCost[u]) {
                        bestCost[u] = nd;
                        from[u] = v;
                        queue.add(new long[]{Double.doubleToLongBits(nd), u});
                    }
                }
            }
            if (last < 0 || (best != null && closed >= best.cost)) continue;
            List<Integer> states = new ArrayList<>();
            for (int v = last; v != s; v = from[v]) states.add(0, v);
            states.add(0, s);
            int[] st = states.stream().mapToInt(Integer::intValue).toArray();
            int laps = 0, off = 0, far = 0;
            for (int i = 0; i < st.length; i++) {
                int a = st[i], bb = st[(i + 1) % st.length];
                if (c.cut(a, bb)) laps++;
                if (!lane[c.cell(a)]) off++;
                far = Math.max(far, dist[c.cell(a)]);
            }
            best = new Strand(st, laps, closed, off, far, pixel);
        }
        return best;
    }

    static void reportLoop(Corridor c, Loop loop, boolean[] lane, int laneCount) {
        int N = loop.coastTicks();
        System.out.printf("%n-- from coasting index %d: %d strands beside the coast, P %d states over %d pixels in %d component%s --%n",
                loop.start, loop.strands.size(), loop.P.length, loop.pixels, loop.components,
                loop.components == 1 ? "" : "s   <-- NOT CONNECTED");
        System.out.printf("   %4s %5s %4s %9s %6s %4s %4s  %s%n", "#", "ticks", "laps", "ticks/lap", "cost", "off", "far", "seed pixel, first state, and the lap lengths between cut crossings");
        for (int i = 0; i < loop.strands.size(); i++) {
            Strand s = loop.strands.get(i);
            // Lap lengths: ticks between successive cut crossings, starting from the first.
            List<Integer> crossings = new ArrayList<>();
            for (int k = 0; k < s.states.length; k++) if (c.cut(s.states[k], s.states[(k + 1) % s.states.length])) crossings.add(k);
            StringBuilder lapLengths = new StringBuilder();
            for (int k = 0; k < crossings.size(); k++) {
                int a = crossings.get(k), bb = crossings.get((k + 1) % crossings.size());
                int len = Math.floorMod(bb - a, s.states.length);
                lapLengths.append(k == 0 ? "" : " ").append(len == 0 ? s.states.length : len);
            }
            int seed = s.states[0];
            System.out.printf("   %4d %5d %4d %9.2f %6.0f %4d %4d  (%d,%d) (%d,%d,%d) laps %s%n", i, s.ticks(), s.laps,
                    s.laps == 0 ? Double.NaN : s.ticks() / (double) s.laps, s.cost, s.offLane, s.maxDistance,
                    s.seedPixel % c.map.width(), s.seedPixel / c.map.width(), c.x(seed), c.y(seed), c.d(seed), lapLengths);
        }
        // Flow within P: every state should have a successor and a predecessor in P.
        boolean[] inP = new boolean[c.edgeOf.length];
        for (int s : loop.P) inP[s] = true;
        int noSucc = 0, noPred = 0;
        int[] out = new int[3];
        for (int s : loop.P) {
            boolean ok = false;
            int k = c.succRound(s, out);
            for (int j = 0; j < k && !ok; j++) ok = inP[out[j]];
            if (!ok) noSucc++;
            ok = false;
            k = c.predRound(s, out);
            for (int j = 0; j < k && !ok; j++) ok = inP[out[j]];
            if (!ok) noPred++;
        }
        int total = 0, laps = 0;
        for (Strand s : loop.strands) { total += s.ticks(); laps += s.laps; }
        System.out.printf("   flow within P: %d states without a successor in P, %d without a predecessor%n", noSucc, noPred);
        System.out.printf("   strand ticks in all %d over %d laps = %.3f per lap against the coast's %d; lane pixels covered %d of %d%n",
                total, laps, laps == 0 ? Double.NaN : total / (double) laps, N, coveredLane(c, loop.P, lane), laneCount);
        System.out.printf("   states/tick %.3f, ticks/pixel %.3f, states/pixel %.3f  (tick = one coasting tick, pixel = one of P's projection)%n",
                loop.P.length / (double) N, N / (double) loop.pixels, loop.P.length / (double) loop.pixels);
    }

    /**
     * Drops every strand the projection can do without, dearest first: a strand is kept only if
     * removing it disconnects the projection or uncovers a lane pixel nothing else covers.
     */
    static Loop prune(Corridor c, Loop loop, boolean[] lane) {
        int w = c.map.width(), h = c.map.height();
        List<Strand> kept = new ArrayList<>(loop.strands);
        Integer[] order = new Integer[kept.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Double.compare(kept.get(b).cost, kept.get(a).cost));
        boolean[] drop = new boolean[kept.size()];
        int[] label = new int[w * h];
        for (int i : order) {
            drop[i] = true;
            boolean[] covered = new boolean[w * h];
            for (int s : loop.coast) covered[c.cell(s)] = true;
            for (int j = 0; j < kept.size(); j++) if (!drop[j]) for (int s : kept.get(j).states) covered[c.cell(s)] = true;
            if (label(covered, w, h, true, label, true) != 1) drop[i] = false;
        }
        List<Strand> left = new ArrayList<>();
        for (int i = 0; i < kept.size(); i++) if (!drop[i]) left.add(kept.get(i));
        boolean[] inP = new boolean[c.edgeOf.length];
        for (int s : loop.coast) inP[s] = true;
        for (Strand s : left) for (int t : s.states) inP[t] = true;
        int[] P = Arrays.stream(c.states).filter(t -> inP[t]).toArray();
        boolean[] covered = new boolean[w * h];
        for (int s : P) covered[c.cell(s)] = true;
        int pixels = 0;
        for (boolean v : covered) if (v) pixels++;
        return new Loop(loop.start, loop.coast, P, left, pixels, label(covered, w, h, true, label, true));
    }

    /**
     * The geometric clock: every lane pixel labelled with the coasting tick at which the coast
     * sweeps it — {@code k + j/n} for the {@code j}th of the {@code n} samples of step {@code k},
     * the landing being {@code k + 1} — and every other pixel with its nearest lane pixel's.
     */
    static double[] geometricTau(Corridor c, int[] coast, boolean[] lane) {
        int w = c.map.width(), h = c.map.height(), N = coast.length;
        double[] tau = new double[w * h];
        Arrays.fill(tau, Double.NaN);
        for (int k = 0; k < N; k++) {
            int s = coast[k], u = coast[(k + 1) % N];
            if (Double.isNaN(tau[c.cell(s)])) tau[c.cell(s)] = k;
            int[] sweep = c.map.stepPath(c.d(u));
            int n = sweep.length / 2;
            for (int q = 0; q + 1 < sweep.length; q += 2) {
                int mx = c.x(s) + sweep[q], my = c.y(s) + sweep[q + 1];
                if (mx < 0 || my < 0 || mx >= w || my >= h) continue;
                int i = mx + my * w;
                if (lane[i] && Double.isNaN(tau[i])) tau[i] = (k + (q / 2 + 1) / (double) n) % N;
            }
        }
        // Everything else takes the nearest labelled pixel's value, by 8-neighbour flood.
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int i = 0; i < tau.length; i++) if (!Double.isNaN(tau[i])) queue.add(i);
        while (!queue.isEmpty()) {
            int i = queue.poll();
            int x = i % w, y = i / w;
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int nx = x + dx, ny = y + dy;
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                    int j = nx + ny * w;
                    if (Double.isNaN(tau[j])) { tau[j] = tau[i]; queue.add(j); }
                }
            }
        }
        return tau;
    }

    /** How much geometric tau each transition of each strand advances: the clock's residual, strand by strand. */
    static void reportGeometric(Corridor c, Loop loop, double[] tau) {
        int N = loop.coastTicks();
        System.out.printf("   geometric tau along each strand: advance per transition, min / mean / max, and the count off by half a tick or more%n");
        for (int i = 0; i < loop.strands.size(); i++) {
            Strand s = loop.strands.get(i);
            double min = Double.MAX_VALUE, max = -Double.MAX_VALUE, sum = 0;
            int bad = 0;
            double first = tau[c.cell(s.states[0])];
            for (int k = 0; k < s.states.length; k++) {
                double a = tau[c.cell(s.states[k])], b = tau[c.cell(s.states[(k + 1) % s.states.length])];
                double dt = b - a;
                if (dt < -N / 2.0) dt += N;
                if (dt > N / 2.0) dt -= N;
                min = Math.min(min, dt);
                max = Math.max(max, dt);
                sum += dt;
                if (Math.abs(dt - 1) >= 0.5) bad++;
            }
            System.out.printf("   %4d %5d ticks: %+.2f / %.4f / %+.2f, %3d transitions off; tau at seed %.2f (frac %.2f)%n",
                    i, s.ticks(), min, sum / s.states.length, max, bad, first, first - Math.floor(first));
        }
    }

    static int coveredLane(Corridor c, int[] P, boolean[] lane) {
        boolean[] seen = new boolean[lane.length];
        int n = 0;
        for (int s : P) {
            int i = c.cell(s);
            if (lane[i] && !seen[i]) { seen[i] = true; n++; }
        }
        return n;
    }

    /** The whole route with the coast in white and each strand in its own colour, drawn last first so the coast stays visible. */
    static void drawLoop(Corridor c, boolean[] lane, Loop loop, Path out) throws IOException {
        int w = c.map.width(), h = c.map.height();
        boolean[] any = new boolean[c.edgeOf.length];
        for (int s : c.states) any[s] = true;
        int[] box = crop(c, any, 4);
        int scale = 3;
        int[] paint = new int[w * h];
        for (int s : c.states) paint[c.cell(s)] = 0x30343C;
        for (int i = 0; i < lane.length; i++) if (lane[i]) paint[i] = 0x25408F;
        for (int i = loop.strands.size() - 1; i >= 0; i--) {
            int rgb = PALETTE[1 + (i % (PALETTE.length - 1))];
            for (int s : loop.strands.get(i).states) paint[c.cell(s)] = rgb;
        }
        boolean[] inStrand = new boolean[c.edgeOf.length];
        for (Strand s : loop.strands) for (int t : s.states) inStrand[t] = true;
        for (int s : loop.P) if (!inStrand[s]) paint[c.cell(s)] = 0xFFFFFF;
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

    // --------------------------------------------------------------------------------- lap

    /** One lap search: from {@code from}, the fewest ticks to a state 0–3 px ahead on the same row and heading. */
    public record Lap(int from, int ticks, int ahead, int[] path, int[] reachable) {
        /** The lap in ticks: a pixel ahead on a horizontal step is a quarter tick. */
        public double length() { return ticks - ahead / 4.0; }
        public int to() { return path[path.length - 1]; }
    }

    /**
     * The shortest lap of a route, measured where a pixel is exactly a quarter tick.
     * <p>
     * <b>The user's construction, 2026-09-15.</b> From a state {@code S} on a horizontal straight,
     * find the minimal number of ticks to reach {@code S} itself or a state a fractional tick in
     * front of it — the same row and heading, {@code 0} to {@code 3} px ahead — with ties broken by
     * the furthest spot reachable in that many ticks. A lap is then {@code N − k/4}. Chaining laps
     * from the landing state walks the phases; the chain closes on {@code S}'s own straight-travel
     * line once the pixels advanced sum to a multiple of four, and the union of its laps is the
     * shortest closed path through {@code S}'s phase class — phase-complete if the chain visits
     * every residue, which it does iff {@code k} is odd.
     *
     * @param x the column on the straight; every route state there with a horizontal step
     *          (headings 63, 0, 1), at every row, is searched from
     */
    public static void lap(PresetScenarioParameter preset, SolverFacts.Gate gate, int[] route, int edge, int x)
            throws IOException {
        Pipeline.Built b = Pipeline.build(preset, gate);
        EdgeDecomposition.Labelling l = b.labelling();
        NavMap map = l.map();
        int[] edgeOf = l.edge();
        int w = map.width(), h = map.height();

        int m = route.length, slot = -1;
        for (int i = 0; i < m; i++) if (route[i] == edge) slot = i;
        int[] rotated = new int[m];
        int shift = Math.floorMod(slot - m / 2, m);
        for (int i = 0; i < m; i++) rotated[i] = route[(i + shift) % m];
        Corridor c = new Corridor(map, edgeOf, l.live(), l.liveCount(), rotated);
        System.out.printf("%n=== %s @%s: shortest lap of route %s from the column x = %d ===%n",
                preset.name(), preset.ingest().hash(), Arrays.toString(rotated), x);

        List<Integer> column = new ArrayList<>();
        for (int y = 0; y < h; y++) {
            for (int d : new int[]{63, 0, 1}) {
                if (map.stepX(d) != 4 || map.stepY(d) != 0) throw new IllegalStateException("heading " + d + " is not a horizontal step");
                if (!map.alive(x, y, d)) continue;
                int s = map.index(x, y, d);
                if (c.in(s) && edgeOf[s] == edge) column.add(s);
            }
        }
        System.out.printf("%d states in the column with a horizontal step%n", column.size());

        Lap best = null;
        List<Lap> bestChain = null;
        java.util.Map<String, List<String>> rows = new java.util.LinkedHashMap<>();
        for (int s : column) {
            Lap first = lapFrom(c, s);
            if (first == null) {
                System.out.printf("%5d %3d   none%n", c.y(s), c.d(s));
                continue;
            }
            // Chain: from the landing state, again, until the pixels advanced sum to a multiple of four.
            List<Lap> chain = new ArrayList<>();
            chain.add(first);
            int advanced = first.ahead;
            Lap cur = first;
            while (advanced % 4 != 0 && chain.size() < 16) {
                cur = lapFrom(c, cur.to());
                if (cur == null) break;
                chain.add(cur);
                advanced += cur.ahead;
            }
            StringBuilder sb = new StringBuilder();
            double total = 0;
            for (int i = 0; i < chain.size(); i++) {
                Lap lp = chain.get(i);
                if (i < 5) sb.append(String.format(" %d-%d/4", lp.ticks, lp.ahead));
                else if (i == 5) sb.append(" …");
                total += lp.length();
            }
            StringBuilder reach = new StringBuilder();
            for (int i = 0; i <= EXTRA; i++) {
                reach.append(i == 0 ? "" : " ").append(first.ticks + i).append(":{");
                for (int k = 0; k < 4; k++) if ((first.reachable[i] & (1 << k)) != 0) reach.append(k);
                reach.append('}');
            }
            String row = String.format("%6d %6d %8.2f   reachable %s   chain%s = %.2f over %d laps%s", first.ticks, first.ahead,
                    first.length(), reach, sb, total, chain.size(), advanced % 4 == 0 ? "" : " (did not close)");
            rows.computeIfAbsent(row, k -> new ArrayList<>()).add(c.y(s) + "/" + c.d(s));
            if (best == null || first.length() < best.length()) { best = first; bestChain = chain; }
        }
        for (var e : rows.entrySet()) System.out.printf("  %2d states (y/d %s%s): %s%n", e.getValue().size(), e.getValue().get(0),
                e.getValue().size() > 1 ? " … " + e.getValue().get(e.getValue().size() - 1) : "", e.getKey());
        if (best == null) return;
        System.out.printf("%nshortest lap: %.2f ticks from (%d,%d,%d): %d ticks landing %d px ahead%n", best.length(),
                c.x(best.from), c.y(best.from), c.d(best.from), best.ticks, best.ahead);

        // The chain as one closed walk: its states, its projection's connectivity, and how many
        // of the four phases it visits on the straight.
        boolean[] inWalk = new boolean[edgeOf.length];
        boolean[] pix = new boolean[w * h];
        int states = 0, walkTicks = 0;
        for (Lap lp : bestChain) {
            walkTicks += lp.ticks;
            for (int i = 0; i + 1 < lp.path.length; i++) {
                int s = lp.path[i];
                if (!inWalk[s]) { inWalk[s] = true; states++; }
                pix[c.cell(s)] = true;
            }
        }
        int[] label = new int[w * h];
        int comps = label(pix, w, h, true, label, true);
        int pixels = 0;
        for (boolean v : pix) if (v) pixels++;
        boolean[] phase = new boolean[4];
        for (Lap lp : bestChain) phase[Math.floorMod(c.x(lp.from) - x, 4)] = true;
        int phases = 0;
        for (boolean v : phase) if (v) phases++;
        System.out.printf("the chain as a closed walk: %d laps, %d ticks, %d distinct states over %d pixels in %d component%s,"
                        + " %d of 4 phases on the straight%s%n", bestChain.size(), walkTicks, states, pixels, comps,
                comps == 1 ? "" : "s", phases, phases == 4 && comps == 1 ? " — phase-complete" : "");
        System.out.printf("states/tick %.3f, ticks/pixel %.3f, states/pixel %.3f  (tick = one lap of %.2f)%n",
                states / best.length(), best.length() / pixels, states / (double) pixels, best.length());

        Path dir = Path.of("render", "phase-path");
        Files.createDirectories(dir);
        String name = preset.name().toLowerCase() + "-" + preset.ingest().hash() + "-lap" + Arrays.toString(rotated).replaceAll("[\\[\\] ]", "").replace(',', '-');
        drawLaps(c, bestChain, dir.resolve(name + ".png"));
        System.out.printf("wrote %s.png in %s%n", name, dir);
    }

    /** How many depths past the first hit the search keeps counting reachable advances for. */
    static final int EXTRA = 3;

    /**
     * Breadth-first from {@code from} round the route, the cut crossing allowed, until some state
     * on the same row and heading 0–3 px ahead is reached; at that depth the furthest ahead wins.
     */
    static Lap lapFrom(Corridor c, int from) {
        int n = c.edgeOf.length, w = c.map.width();
        int x0 = c.x(from), y0 = c.y(from), d0 = c.d(from);
        int[] target = new int[4];
        for (int k = 0; k < 4; k++) {
            target[k] = x0 + k < w && c.map.alive(x0 + k, y0, d0) && c.in(c.map.index(x0 + k, y0, d0))
                    ? c.map.index(x0 + k, y0, d0) : -1;
        }
        int[] depth = new int[n], parent = new int[n];
        Arrays.fill(depth, -1);
        Arrays.fill(parent, -1);
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        depth[from] = 0;
        queue.add(from);
        int[] out = new int[3];
        int found = -1, foundK = -1, hitStart = -1, startParent = -1;
        int[] reachable = new int[EXTRA + 1];   // per depth from the first hit, a bitmask of the k reached
        while (!queue.isEmpty() && (found < 0 || depth[queue.peek()] < found + EXTRA)) {
            int layer = depth[queue.peek()];
            List<Integer> next = new ArrayList<>();
            while (!queue.isEmpty() && depth[queue.peek()] == layer) {
                int s = queue.poll();
                int k = c.succRound(s, out);
                for (int j = 0; j < k; j++) {
                    int u = out[j];
                    if (u == from) {
                        if (hitStart < 0) { hitStart = layer + 1; startParent = s; }
                        continue;
                    }
                    if (depth[u] >= 0) continue;
                    depth[u] = layer + 1;
                    parent[u] = s;
                    next.add(u);
                }
            }
            queue.addAll(next);
            int mask = hitStart == layer + 1 ? 1 : 0, bestK = hitStart == layer + 1 ? 0 : -1;
            for (int k = 1; k < 4; k++) if (target[k] >= 0 && depth[target[k]] == layer + 1) { mask |= 1 << k; bestK = k; }
            if (bestK >= 0 && found < 0) { found = layer + 1; foundK = bestK; }
            if (found >= 0 && layer + 1 - found <= EXTRA) reachable[layer + 1 - found] = mask;
        }
        if (found < 0) return null;
        List<Integer> path = new ArrayList<>();
        int t = foundK == 0 ? from : target[foundK];
        path.add(t);
        int cur = foundK == 0 ? startParent : parent[t];
        while (cur != from) { path.add(0, cur); cur = parent[cur]; }
        path.add(0, from);
        return new Lap(from, found, foundK, path.stream().mapToInt(Integer::intValue).toArray(), reachable);
    }

    /** The chain of laps on the route, each lap in its own colour, drawn last first. */
    static void drawLaps(Corridor c, List<Lap> chain, Path out) throws IOException {
        int w = c.map.width(), h = c.map.height();
        boolean[] any = new boolean[c.edgeOf.length];
        for (int s : c.states) any[s] = true;
        int[] box = crop(c, any, 4);
        int scale = 3;
        int[] paint = new int[w * h];
        for (int s : c.states) paint[c.cell(s)] = 0x30343C;
        for (int i = chain.size() - 1; i >= 0; i--) {
            int rgb = PALETTE[(i + 1) % PALETTE.length];
            for (int s : chain.get(i).path) paint[c.cell(s)] = rgb;
        }
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

        // F and R, each the minimum over the four quarter-tick offsets of the line.
        int[] F = new int[n], R = new int[n];
        Arrays.fill(F, Integer.MAX_VALUE);
        Arrays.fill(R, Integer.MAX_VALUE);
        for (int k = 0; k < 4; k++) {
            // The line lies between columns x0-1 and x0: a start at x0+k is k quarter-ticks past it, a
            // finish at x0-1-k is k+1 short of it, and no state is on both sides.
            int[] ahead = columnStates(c, edge, x0 + k), behind = columnStates(c, edge, x0 - 1 - k);
            int[] df = bfs(c, ahead, true), dr = bfs(c, behind, false);
            for (int s : c.states) {
                if (df[s] >= 0) F[s] = Math.min(F[s], 4 * df[s] + k);
                if (dr[s] >= 0) R[s] = Math.min(R[s], 4 * dr[s] + k + 1);
            }
            System.out.printf("offset %d: %d states on the line ahead, %d behind%n", k, ahead.length, behind.length);
        }
        int[] L = new int[n];
        int reached = 0, minL = Integer.MAX_VALUE;
        for (int s : c.states) {
            if (F[s] == Integer.MAX_VALUE || R[s] == Integer.MAX_VALUE) { L[s] = Integer.MAX_VALUE; continue; }
            L[s] = F[s] + R[s];
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

    /** Every route state on edge {@code edge} at column {@code x}, any row, any heading. */
    static int[] columnStates(Corridor c, int edge, int x) {
        List<Integer> out = new ArrayList<>();
        for (int y = 0; y < c.map.height(); y++) {
            for (int d = 0; d < c.turns; d++) {
                if (!c.map.alive(x, y, d)) continue;
                int s = c.map.index(x, y, d);
                if (c.in(s) && c.edgeOf[s] == edge) out.add(s);
            }
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
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
