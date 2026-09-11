package boids;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Whether a gate can be constructed from a single state plus the decomposition algorithm.
 * <p>
 * <b>The claim under test.</b> Take an edge {@code E} and a state {@code S} on it, somewhere away
 * from other features. Split {@code E} into {@code E \ S} and {@code {S}}, then refine. Refinement
 * should cut {@code E \ S} into exactly three pieces —
 * <ul>
 *   <li><b>{@code E<S}</b>, the states that can reach {@code S};</li>
 *   <li><b>{@code E⊥S}</b>, the states that can neither reach {@code S} nor be reached by it;</li>
 *   <li><b>{@code E>S}</b>, the states {@code S} can reach</li>
 * </ul>
 * — so that {@code E} comes apart into four edges, all of which satisfy the axiom. A gate follows
 * from the pieces.
 * <p>
 * <b>Why this would be worth having.</b> A gate is a set fired exactly once per traversal of an
 * edge, and the expensive part of defining one is proving the exactly-once property. If the
 * decomposition algorithm produces the pieces on its own, the property comes from the axiom rather
 * than from a separate proof, and picking a gate is reduced to picking a state.
 * <p>
 * <b>Reachability here means "without leaving {@code E}".</b> On a map whose edges lie on cycles,
 * a boid that leaves an edge may come back to it, so unrestricted reachability would make every
 * state reach every other and the three-way split would be vacuous. Refinement's own notion is the
 * same one — its masks record the <em>first different</em> edge, which closes over travel inside
 * the edge first — so this is the reading the algorithm is already using.
 * <p>
 * Nothing here is part of the psyboid pipeline. It is a test of the decomposition, run by hand.
 */
public final class GateSplit {
    private GateSplit() {}

    /** Where a state of the original edge sits relative to the chosen state. */
    public enum Side {
        /** The chosen state itself. */
        AT("S"),
        /** Can reach {@code S} without leaving the edge. */
        BEFORE("E<S"),
        /** Can be reached from {@code S} without leaving the edge. */
        AFTER("E>S"),
        /** Neither — a different phase of the same corridor. */
        APART("E_|_S");

        public final String label;

        Side(String label) { this.label = label; }
    }

    /**
     * What one original edge came apart into.
     *
     * @param inFront of the states by which a boid <em>enters</em> the edge, the fraction that can
     *                reach {@code S}. <b>The number the construction lives or dies on</b>: a gate
     *                fires exactly once per traversal only if every entrance is behind it
     * @param behind  the same for the states by which a boid <em>leaves</em>, against {@code E>S}
     */
    public record Split(int edge, double length, int states, int sStates, double chosenTau,
                        List<Piece> pieces, Map<Side, Integer> totals, double inFront,
                        double behind) {

        /** Whether refinement produced exactly the predicted pieces and nothing else. */
        public boolean clean() {
            if (pieces.size() != 4) return false;
            for (Piece p : pieces) if (p.mixed()) return false;
            return true;
        }

        /** Whether the gate covers every way into and out of the edge. */
        public boolean covers() { return inFront >= 1.0 && behind >= 1.0; }
    }

    /**
     * One piece refinement produced, and how it lines up with the prediction.
     *
     * @param counts states of this piece falling on each side, so a piece that mixes two sides
     *               shows what it mixed rather than only that it failed
     */
    public record Piece(int id, int states, Map<Side, Integer> counts) {

        /** The side holding most of this piece. */
        public Side dominant() {
            Side best = Side.APART;
            int most = -1;
            for (Map.Entry<Side, Integer> e : counts.entrySet()) {
                if (e.getValue() > most) { most = e.getValue(); best = e.getKey(); }
            }
            return best;
        }

        /** Whether this piece straddles more than one side, which would falsify the claim. */
        public boolean mixed() {
            int nonEmpty = 0;
            for (int v : counts.values()) if (v > 0) nonEmpty++;
            return nonEmpty > 1;
        }
    }

    /**
     * Splits one state off one long enough edge, refines, reports, and repeats for the next edge.
     * <p>
     * <b>One edge at a time, and it has to be.</b> Splitting all nine at once was tried first and
     * is not a harder version of the same test — it is a different one that cannot run.
     * {@link SimTest#refine} carries each state's next-and-previous edge sets as a 64-bit mask, so
     * it gives up above 63 edges; nine splits at once shattered the map past that in a single
     * round and refinement returned a grouping that was never a fixed point. Splitting one state
     * goes in at ten edges and has room to converge.
     *
     * @param minLength edges shorter than this are left alone, since a state near the middle of a
     *                  short edge is not away from anything
     */
    public static List<Split> run(PresetScenarioParameter preset, SolverFacts.Gate gate,
                                  double minLength, boolean phaseBleed, boolean perfect)
            throws IOException {
        EdgeDecomposition.Labelling l = SimTest.labelFor(preset, gate.horizontal(), gate.line(), gate.lo(),
                gate.hi(), gate.dir());
        NavMap map = l.map();
        int[] live = l.live(), base = l.edge();
        int liveCount = l.liveCount(), edges = l.edges();

        EdgeMetric.Metric m = EdgeMetricStore.of(SimTest.structure(preset, gate).at("metric"),
                map, base, live, liveCount, edges, SimTest.SCHEME, SimTest.CHAIN);

        System.out.printf("%n=== %s @%s: gate-from-one-state, edges of length >= %.0f, S = %s ===%n",
                preset.name(), preset.ingest().hash(), minLength,
                (phaseBleed ? "state + partial tick" : "one state")
                        + (perfect ? ", perfected both ways" : ""));

        // 1. One state per long edge, as near the middle of it in tau as the phase comb allows.
        int[] chosen = new int[edges];
        Arrays.fill(chosen, -1);
        double[] chosenTau = new double[edges];
        for (int e = 0; e < edges; e++) {
            if (m.length()[e] < minLength) continue;
            double target = m.length()[e] / 2, best = Double.MAX_VALUE;
            for (int i = 0; i < liveCount; i++) {
                int s = live[i];
                if (base[s] != e || Double.isNaN(m.tick()[s])) continue;
                double d = Math.abs(m.tick()[s] - target);
                if (d < best) { best = d; chosen[e] = s; chosenTau[e] = m.tick()[s]; }
            }
        }

        int turns = Params.TURNS, w = map.width();
        for (int e = 0; e < edges; e++) {
            if (chosen[e] < 0) {
                System.out.printf("edge %-2d length %7.2f   skipped%n", e, m.length()[e]);
                continue;
            }
            int s = chosen[e];
            System.out.printf("edge %-2d length %7.2f   S = (%d,%d,%d) at tau %.2f%n", e,
                    m.length()[e], s / turns % w, s / turns / w, s % turns, chosenTau[e]);
        }

        // 2. Adjacency over the whole map. No gate cuts: those exist only to break the orbits
        // apart for a first decomposition, and the decomposition already exists.
        //
        // A turn the veto would alter is skipped, not followed to where the veto sends it. That
        // is what the decomposition itself does, and the two are different graphs:
        // NavMap.successor returns the *constrained* successor, so asking it for all three turns
        // reports the same state more than once wherever the veto is binding.
        int n = base.length;
        int[] succ = new int[n * 3];
        byte[] degree = new byte[n];
        int turns2 = Params.TURNS;
        for (int i = 0; i < liveCount; i++) {
            int s = live[i];
            int d = s % turns2, cell = s / turns2, x = cell % w, y = cell / w;
            for (int t = -1; t <= 1; t++) {
                if (map.constrainTurn(x, y, d, t) != t) continue;
                int nd = Math.floorMod(d + t, turns2);
                int nx = x + map.stepX(nd), ny = y + map.stepY(nd);
                if (nx < 0 || ny < 0 || nx >= w || ny >= map.height() || map.oob(nx, ny)) continue;
                if (!map.alive(nx, ny, nd)) continue;
                succ[s * 3 + degree[s]++] = (nx + ny * w) * turns2 + nd;
            }
        }
        int[] pred = new int[n * 3];
        byte[] predDegree = new byte[n];
        for (int i = 0; i < liveCount; i++) {
            int s = live[i];
            for (int j = 0; j < degree[s]; j++) {
                int u = succ[s * 3 + j];
                pred[u * 3 + predDegree[u]++] = s;
            }
        }

        // 3. The control, and it has to come first. Everything below reads a change in the edge
        // count as a consequence of the split, which is only sound if the decomposition is
        // already a fixed point of refinement under this adjacency. If it is not, the split is
        // not what is being measured.
        int[] control = base.clone();
        System.out.printf("%ncontrol: refining the decomposition unchanged, %d edges in%n", edges);
        int settled = EdgeDecomposition.refine(live, liveCount, succ, degree, pred, predDegree, control,
                edges);
        System.out.printf("   %d edges out -- %s%n", settled, settled == edges
                ? "a fixed point, so any change below is the split"
                : "NOT A FIXED POINT: every figure below is measuring this instead");

        // 4. One edge at a time: split S off, refine, score the pieces it produced.
        MapStates lattice = MapStates.of(map, Flocking.of(preset.turningRadius()), live, liveCount);
        List<Split> out = new ArrayList<>();
        for (int e = 0; e < edges; e++) {
            if (chosen[e] < 0) continue;

            // S is the chosen state together with its partial unsteered forward tick. A single
            // state occupies one phase of the step lattice, so E<S cannot cover the whole edge
            // entrance and E>S cannot cover the whole exit: the split cuts finer than the grain
            // the edge is made of, and refinement follows it down. The partial tick is the
            // existing model of exactly that phase bleed -- `GLOSSARY.md`, and it is applied
            // once for the same reason it is applied once there.
            StateSet one = lattice.of(chosen[e]);
            StateSet spread = phaseBleed ? one.partialTick(StateSet.Steering.STRAIGHT) : one;
            // One pass of each is a joint fixed point; neither direction can create candidates for
            // the other. See StateSet.forwardsPerfect.
            StateSet all = perfect ? perfected(spread) : spread;
            int[] members = all.toArray();
            final int on = e;
            int[] onEdge = Arrays.stream(members).filter(s -> base[s] == on).toArray();
            if (onEdge.length != members.length) {
                System.out.printf("   note: %d of S's %d states lie off edge %d and are left "
                        + "alone; S has to be a sub-edge of E%n",
                        members.length - onEdge.length, members.length, e);
            }

            int[] edge = base.clone();
            for (int s : onEdge) edge[s] = edges;

            System.out.printf("%n-- edge %d: %d edges + S (%d states, %d before perfecting) --%n",
                    e, edges, onEdge.length, spread.size());

            // StateSet reads the map's constrained relation; this file skips a vetoed turn instead.
            // The reachable sets should agree; checked rather than assumed, because the two came
            // apart once already.
            int inSet = 0;
            for (int s : onEdge) {
                for (int j = 0; j < predDegree[s]; j++) {
                    for (int t : onEdge) if (pred[s * 3 + j] == t) inSet++;
                }
            }
            if (inSet > 0) {
                System.out.printf("   ** %d predecessor link(s) inside S under the decomposition's "
                        + "own graph **%n", inSet);
            }
            int after = EdgeDecomposition.refine(live, liveCount, succ, degree, pred, predDegree, edge,
                    edges + 1);
            System.out.printf("   after refinement: %d edges%n", after);

            Side[] side = sides(base, live, liveCount, e, onEdge, succ, degree, pred, predDegree);

            // Coverage, which is the question the pieces do not answer. A boid enters the edge at
            // a state with a predecessor outside it and leaves from one with a successor outside
            // it; the gate is crossed exactly once per traversal only if every entrance can reach
            // S and every exit is reachable from it.
            int entrances = 0, entrancesCovered = 0, exits = 0, exitsCovered = 0;
            Map<Integer, Map<Side, Integer>> byPiece = new LinkedHashMap<>();
            Map<Side, Integer> totals = new LinkedHashMap<>();
            int states = 0;
            for (int i = 0; i < liveCount; i++) {
                int s = live[i];
                if (base[s] != e) continue;
                states++;
                byPiece.computeIfAbsent(edge[s], k -> new LinkedHashMap<>())
                        .merge(side[s], 1, Integer::sum);
                totals.merge(side[s], 1, Integer::sum);

                boolean entrance = false;
                for (int j = 0; j < predDegree[s]; j++) {
                    if (base[pred[s * 3 + j]] != e) entrance = true;
                }
                boolean exit = false;
                for (int j = 0; j < degree[s]; j++) {
                    if (base[succ[s * 3 + j]] != e) exit = true;
                }
                if (entrance) {
                    entrances++;
                    if (side[s] == Side.BEFORE || side[s] == Side.AT) entrancesCovered++;
                }
                if (exit) {
                    exits++;
                    if (side[s] == Side.AFTER || side[s] == Side.AT) exitsCovered++;
                }
            }
            double inFront = entrances == 0 ? 1 : entrancesCovered / (double) entrances;
            double behind = exits == 0 ? 1 : exitsCovered / (double) exits;
            System.out.printf("   entrances %d, %.1f%% can reach S;  exits %d, %.1f%% reachable "
                    + "from S%n", entrances, 100 * inFront, exits, 100 * behind);

            // G, which is the proposed gate. S is not it and never was: S is a handful of states
            // in a corridor hundreds of states wide, and a boid on another phase walks straight
            // past it. G is the boundary of E<S — the states just outside it that a state inside
            // it steps to.
            //
            // Two facts make G a gate. **E<S is never re-entered**: if s steps to s' and s' can
            // reach S then s can reach S, so a state outside E<S has no successor inside it.
            // Asserted below rather than taken on the argument. Given that, every traversal
            // crosses out of E<S at most once, and exactly once when it entered inside E<S —
            // which is what the entrance percentage above measures. **So G is a gate for this
            // edge exactly when that percentage is 100.**
            int gateSize = 0, leak = 0;
            for (int i = 0; i < liveCount; i++) {
                int t = live[i];
                if (base[t] != e) continue;
                boolean inFrontOf = side[t] == Side.BEFORE;
                boolean fromFront = false;
                for (int j = 0; j < predDegree[t]; j++) {
                    int p = pred[t * 3 + j];
                    if (base[p] == e && side[p] == Side.BEFORE) fromFront = true;
                }
                if (!inFrontOf && fromFront) gateSize++;
                if (inFrontOf) {
                    for (int j = 0; j < predDegree[t]; j++) {
                        int p = pred[t * 3 + j];
                        if (base[p] == e && side[p] != Side.BEFORE) leak++;
                    }
                }
            }
            System.out.printf("   G is %d states; E<S re-entered from outside %d times; "
                    + "G is a gate here: %s%n", gateSize, leak,
                    leak == 0 && inFront >= 1.0 ? "YES" : "no");
            List<Piece> pieces = new ArrayList<>();
            byPiece.forEach((id, counts) -> {
                int total = 0;
                for (int v : counts.values()) total += v;
                pieces.add(new Piece(id, total, counts));
            });
            pieces.sort((a, b) -> Integer.compare(b.states(), a.states()));
            out.add(new Split(e, m.length()[e], states, onEdge.length, chosenTau[e], pieces,
                    totals, inFront, behind));
            draw(map, base, live, liveCount, e, edge, side, pieces, phaseBleed && perfect);
        }

        report(out);
        return out;
    }

    /**
     * Two rounds of refinement and nothing more, to see what the second one objects to.
     * <p>
     * Round one should take 9 edges to 12: {@code E} becomes {@code E<S}, {@code E⊥S}, {@code E>S}
     * and {@code S}. Anything round two does is a second thought about a piece round one already
     * made, so it can only be a piece that is malformed — and the mask it splits on names exactly
     * what the disagreement is.
     * <p>
     * Run on the partial-tick, both-ways-perfected {@code S} only, since that is the construction
     * under test.
     */
    public static void diagnose(PresetScenarioParameter preset, SolverFacts.Gate gate,
                                double minLength) throws IOException {
        EdgeDecomposition.Labelling l = SimTest.labelFor(preset, gate.horizontal(), gate.line(), gate.lo(),
                gate.hi(), gate.dir());
        NavMap map = l.map();
        int[] live = l.live(), base = l.edge();
        int liveCount = l.liveCount(), edges = l.edges();
        EdgeMetric.Metric m = EdgeMetricStore.of(SimTest.structure(preset, gate).at("metric"),
                map, base, live, liveCount, edges, SimTest.SCHEME, SimTest.CHAIN);
        MapStates lattice = MapStates.of(map, Flocking.of(preset.turningRadius()), live, liveCount);

        int w = map.width(), turns = Params.TURNS;
        int[][] adj = adjacency(map, base, live, liveCount);
        int[] succ = adj[0], pred = adj[2];
        byte[] degree = bytes(adj[1]), predDegree = bytes(adj[3]);

        System.out.printf("%n=== %s @%s: two rounds only, S = partial tick, perfection %s%s ===%n",
                preset.name(), preset.ingest().hash(), mode, unionInverse ? ", + inverse" : "");

        // The inverse convention is taken from SimTest.renderEdges, which uses it to pair edges.
        // Checked here rather than assumed: it has to be an involution, and if edge 8 really is
        // two mutually inverse regions then inverting it must land back on edge 8.
        StateSet all = lattice.of(Arrays.copyOf(live, liveCount));
        StateSet once = all.inverted(), twice = once.inverted();
        System.out.printf("inverse: %d live -> %d -> %d, involution %s%n", all.size(),
                once.size(), twice.size(), twice.size() == all.size() ? "holds" : "FAILS");
        for (int e = 0; e < edges; e++) {
            int[] onE = new int[liveCount];
            int n = 0;
            for (int i = 0; i < liveCount; i++) if (base[live[i]] == e) onE[n++] = live[i];
            StateSet inv = lattice.of(Arrays.copyOf(onE, n)).inverted();
            int[] landed = new int[edges];
            int lost = 0;
            for (int s : inv.toArray()) {
                if (base[s] >= 0) landed[base[s]]++; else lost++;
            }
            StringBuilder where = new StringBuilder();
            for (int f = 0; f < edges; f++) {
                if (landed[f] > 0) where.append(where.length() > 0 ? " " : "")
                        .append(f).append(':').append(landed[f]);
            }
            System.out.printf("   edge %d (%d states) inverts to %s%s%n", e, n, where,
                    lost > 0 ? "  (" + lost + " off-map)" : "");
        }

        for (int e = 0; e < edges; e++) {
            if (m.length()[e] < minLength) continue;
            int chosen = -1;
            double target = m.length()[e] / 2, best = Double.MAX_VALUE;
            for (int i = 0; i < liveCount; i++) {
                int s = live[i];
                if (base[s] != e || Double.isNaN(m.tick()[s])) continue;
                double d = Math.abs(m.tick()[s] - target);
                if (d < best) { best = d; chosen = s; }
            }
            final int on = e;
            StateSet core = perfected(lattice.of(chosen).partialTick(StateSet.Steering.STRAIGHT));
            if (unionInverse) core = perfected(core.union(core.inverted()));
            int[] sMembers = Arrays.stream(core.toArray()).filter(s -> base[s] == on).toArray();

            Side[] side = sides(base, live, liveCount, e, sMembers, succ, degree, pred, predDegree);

            int[] edge = base.clone();
            for (int s : sMembers) edge[s] = edges;

            // Is each operator actually a fixed point on its own, and is the pair one together?
            // Both are worklist loops, but that is a claim about the code rather than a
            // measurement of it, and the outer alternation was removed on the strength of an
            // argument. Re-apply and see whether anything moves.
            StateSet seed = lattice.of(chosen).partialTick(StateSet.Steering.STRAIGHT);
            StateSet b1 = seed.backwardsPerfect();
            StateSet b2 = b1.backwardsPerfect();
            StateSet f1 = seed.forwardsPerfect();
            StateSet f2 = f1.forwardsPerfect();
            StateSet bf = b1.forwardsPerfect();
            StateSet bfb = bf.backwardsPerfect();
            StateSet bfbf = bfb.forwardsPerfect();
            System.out.printf("%n---- edge %d, S = %d states ----%n", e, sMembers.length);
            System.out.printf("settling: seed %d | back %d -> %d | fwd %d -> %d | "
                            + "back.fwd %d -> back %d -> fwd %d  %s | alternated %d%n",
                    seed.size(), b1.size(), b2.size(), f1.size(), f2.size(),
                    bf.size(), bfb.size(), bfbf.size(),
                    b2.size() == b1.size() && f2.size() == f1.size()
                            && bfb.size() == bf.size() && bfbf.size() == bf.size()
                            ? "all settled" : "<-- NOT SETTLED", perfected(seed).size());

            EdgeDecomposition.Refined r1 = EdgeDecomposition.refineOnce(live, liveCount, succ, degree, pred,
                    predDegree, edge, edges + 1);
            int[] afterOne = edge.clone();
            System.out.printf("round 1: %d -> %d edges%s%n", edges + 1, r1.edges(),
                    r1.edges() == edges + 3 ? "  (the expected 12)" : "  <-- not 9 + 3");

            // Name every round-1 piece that came out of E, by which side its states are on.
            Map<Integer, Map<Side, Integer>> named = new LinkedHashMap<>();
            for (int i = 0; i < liveCount; i++) {
                int s = live[i];
                if (base[s] != e) continue;
                named.computeIfAbsent(afterOne[s], k -> new LinkedHashMap<>())
                        .merge(side[s], 1, Integer::sum);
            }
            Map<Integer, String> label = new LinkedHashMap<>();
            named.forEach((id, counts) -> {
                StringBuilder b = new StringBuilder();
                counts.forEach((k, v) -> b.append(b.length() > 0 ? "+" : "").append(k.label));
                label.put(id, b.toString());
            });
            System.out.printf("pieces of edge %d after round 1:%n", e);
            named.forEach((id, counts) -> System.out.printf("   edge %-3d %-12s %s%n", id,
                    label.get(id), counts));

            EdgeDecomposition.Refined r2 = EdgeDecomposition.refineOnce(live, liveCount, succ, degree, pred,
                    predDegree, edge, r1.edges());
            System.out.printf("round 2: %d -> %d edges%n", r1.edges(), r2.edges());
            if (r2.edges() == r1.edges()) { System.out.println("   settled."); continue; }

            // Which round-1 edges split, and on what. Group each one's states by the (next, back)
            // pair round 2 saw, then print the masks as edge lists.
            Map<Integer, Map<String, int[]>> split = new LinkedHashMap<>();
            for (int i = 0; i < liveCount; i++) {
                int s = live[i];
                int was = afterOne[s];
                String key = r2.next()[s] + "/" + r2.back()[s];
                split.computeIfAbsent(was, k -> new LinkedHashMap<>())
                        .computeIfAbsent(key, k -> new int[]{0, s})[0]++;
            }
            for (Map.Entry<Integer, Map<String, int[]>> en : split.entrySet()) {
                if (en.getValue().size() < 2) continue;
                int was = en.getKey();
                System.out.printf("   edge %d (%s) splits %d ways:%n", was,
                        label.getOrDefault(was, "not part of edge " + e), en.getValue().size());
                en.getValue().forEach((key, v) -> {
                    int sample = v[1];
                    System.out.printf("      %6d states  next=%-26s back=%-26s  eg (%d,%d,%d)%n",
                            v[0], mask(r2.next()[sample], label), mask(r2.back()[sample], label),
                            sample / turns % w, sample / turns / w, sample % turns);
                });
            }
        }
    }

    /**
     * Perfected in both directions, alternating to a joint fixed point.
     * <p>
     * <b>Alternating rather than one pass of each.</b> Before the rule counted a successor already
     * in the set's own successors, neither direction could create work for the other and one pass
     * of each was provably enough. It is no longer obvious that it is, so this loops and reports
     * when the loop earns its keep.
     */
    /** Which directions {@link #perfected} applies. */
    enum Perfection { NONE, BACKWARDS, FORWARDS, BOTH }

    /**
     * Which directions are applied. <b>Defaults to BACKWARDS, not BOTH</b>: alternating the two
     * under the widened rule runs away, because each call freezes its allowance against a receiver
     * the previous call already grew. Measured on dabeone edge 0 — backwards alone reaches 27
     * states, forwards alone 18, and alternating reaches all 17,028.
     */
    static Perfection mode = Perfection.BOTH;

    /** Whether S is unioned with its own inverse before the split. */
    static boolean unionInverse = false;

    private static StateSet perfected(StateSet s) {
        if (mode == Perfection.NONE) return s;
        StateSet at = s;
        int rounds = 0;
        for (int was = -1; was != at.size(); rounds++) {
            was = at.size();
            if (mode != Perfection.FORWARDS) at = at.backwardsPerfect();
            if (mode != Perfection.BACKWARDS) at = at.forwardsPerfect();
        }
        if (rounds > 2) {
            System.out.printf("   note: %s took %d rounds to settle%n", mode, rounds);
        }
        return at;
    }

    /** A first-different-edge mask as a readable edge list. */
    private static String mask(long bits, Map<Integer, String> label) {
        StringBuilder b = new StringBuilder("{");
        for (int i = 0; i < 64; i++) {
            if ((bits & (1L << i)) == 0) continue;
            if (b.length() > 1) b.append(',');
            b.append(i);
            String name = label.get(i);
            if (name != null) b.append('=').append(name);
        }
        return b.append('}').toString();
    }

    private static byte[] bytes(int[] v) {
        byte[] out = new byte[v.length];
        for (int i = 0; i < v.length; i++) out[i] = (byte) v[i];
        return out;
    }

    /** Successor and predecessor lists, exactly as {@code SimTest.label} builds them. */
    private static int[][] adjacency(NavMap map, int[] base, int[] live, int liveCount) {
        int n = base.length, turns = Params.TURNS, w = map.width();
        int[] succ = new int[n * 3], degree = new int[n];
        for (int i = 0; i < liveCount; i++) {
            int s = live[i];
            int d = s % turns, cell = s / turns, x = cell % w, y = cell / w;
            for (int t = -1; t <= 1; t++) {
                if (map.constrainTurn(x, y, d, t) != t) continue;
                int nd = Math.floorMod(d + t, turns);
                int nx = x + map.stepX(nd), ny = y + map.stepY(nd);
                if (nx < 0 || ny < 0 || nx >= w || ny >= map.height() || map.oob(nx, ny)) continue;
                if (!map.alive(nx, ny, nd)) continue;
                succ[s * 3 + degree[s]++] = (nx + ny * w) * turns + nd;
            }
        }
        int[] pred = new int[n * 3], predDegree = new int[n];
        for (int i = 0; i < liveCount; i++) {
            int s = live[i];
            for (int j = 0; j < degree[s]; j++) {
                int u = succ[s * 3 + j];
                pred[u * 3 + predDegree[u]++] = s;
            }
        }
        return new int[][]{succ, degree, pred, predDegree};
    }

    /** How a seed state is grown into the set that gets inserted. */
    public enum Shape {
        /** The construction that reaches nine of nine: partial tick, perfected, plus its inverse. */
        GOOD,
        /**
         * Deliberately concave: the state and its unsteered successor, partial-ticked together,
         * then the successor taken back out. That leaves a dent — a state reachable from one
         * member and reaching another, but not itself a member — which is exactly what
         * {@link StateSet#interiorPerfect} exists to fill.
         */
        CONCAVE,
        /** The same dent, with interior perfection applied. Should behave like {@link #GOOD}. */
        CONCAVE_FIXED,
    }

    /**
     * Inserts one set into one edge and checks everything the insertion is supposed to guarantee.
     * <p>
     * Reports, per edge: whether the reachability <b>assertion</b> holds on the supplied set, how
     * many pieces refinement produced, and how many connected components {@code E⊥S} falls into.
     * That last is the open question — the axiom splits on predecessor and successor edges and
     * never on connectivity, so two regions with identical neighbours stay one edge however
     * disconnected they are.
     */
    public static void insert(PresetScenarioParameter preset, SolverFacts.Gate gate,
                              double minLength, Shape shape) throws IOException {
        EdgeDecomposition.Labelling l = SimTest.labelFor(preset, gate.horizontal(), gate.line(),
                gate.lo(), gate.hi(), gate.dir());
        NavMap map = l.map();
        int[] live = l.live(), base = l.edge();
        int liveCount = l.liveCount(), edges = l.edges();
        EdgeMetric.Metric m = EdgeMetricStore.of(SimTest.structure(preset, gate).at("metric"),
                map, base, live, liveCount, edges, SimTest.SCHEME, SimTest.CHAIN);
        MapStates lattice = MapStates.of(map, Flocking.of(preset.turningRadius()), live, liveCount);

        int[][] adj = adjacency(map, base, live, liveCount);
        int[] succ = adj[0], pred = adj[2];
        byte[] degree = bytes(adj[1]), predDegree = bytes(adj[3]);

        System.out.printf("%n=== %s @%s: edge insertion, S = %s ===%n", preset.name(),
                preset.ingest().hash(), shape);
        System.out.printf("%-5s %9s %5s %6s %8s %8s %7s  %s%n", "edge", "|S| dent>fix", "OOB",
                "pieces", "entrances", "exits", "perp", "notes");

        for (int e = 0; e < edges; e++) {
            if (m.length()[e] < minLength) continue;
            final int on = e;
            StateSet within = lattice.of(statesOf(base, live, liveCount, e));
            int chosen = middleOf(base, live, liveCount, m, e);

            StateSet raw = grow(map, lattice, within, chosen, Shape.CONCAVE);
            StateSet core = grow(map, lattice, within, chosen, shape);
            int[] sOn = Arrays.stream(core.toArray()).filter(s -> base[s] == on).toArray();

            // Does S touch a wall? A state with fewer than three predecessors or successors has
            // had turns taken away by the veto, which only happens next to out of bounds.
            boolean wall = false;
            for (int s : sOn) if (degree[s] < 3 || predDegree[s] < 3) wall = true;

            // The assertion. An entrance is an on-edge state with an off-edge predecessor; an
            // exit is an OFF-edge state with an on-edge predecessor. Every entrance must reach S
            // and every exit be reachable from it, or the result is not a gate.
            Assertion a = assertReach(base, live, liveCount, e, sOn, succ, degree, pred,
                    predDegree);

            int[] edge = base.clone();
            for (int s : sOn) edge[s] = edges;
            int after = EdgeDecomposition.refine(live, liveCount, succ, degree, pred, predDegree, edge,
                    edges + 1);

            // E perp S: on E, not in S, neither reaching S nor reached by it. Split it into
            // connected components of the transition graph, ignoring direction.
            Side[] side = sides(base, live, liveCount, e, sOn, succ, degree, pred, predDegree);
            int[] perp = new int[liveCount];
            int np = 0;
            for (int i = 0; i < liveCount; i++) {
                int s = live[i];
                if (base[s] == e && side[s] == Side.APART) perp[np++] = s;
            }
            int[] perpStates = Arrays.copyOf(perp, np);
            int[] comps = components(perpStates, succ, degree, base, e, side);

            // Give every component of E perp S an edge of its own, then let mergeAlongside put
            // back together whatever its looser rule says belongs together. The hope is that what
            // survives is one edge per side of S, so one or two.
            int[] edge2 = edge.clone();
            int[] own = componentOf(perpStates, succ, degree);
            int first = after;
            for (int i = 0; i < perpStates.length; i++) edge2[perpStates[i]] = first + own[i];
            int total = first + comps.length;
            EdgeDecomposition.mergeAlongside(map, live, liveCount, edge2, total, first);
            java.util.TreeSet<Integer> left = new java.util.TreeSet<>();
            for (int s : perpStates) left.add(edge2[s]);

            System.out.printf("%-5d %4d>%-4d %5s %6d %8s %8s %7s  %s%n", e, raw.size(), sOn.length,
                    wall ? "wall" : "-", after,
                    a.entrances() + (a.entrancesOk() ? " ok" : " BAD"),
                    a.exits() + (a.exitsOk() ? " ok" : " BAD"),
                    np + "/" + comps.length + ">" + left.size(),
                    after == edges + 3 ? "" : "<-- not 9 + 3");

            // Where each component sits in tau against S. Components on both sides that merge into
            // one mean the merge reached across S, which is a different thing from one side
            // being empty.
            double sLo = Double.MAX_VALUE, sHi = -Double.MAX_VALUE;
            for (int s : sOn) {
                if (Double.isNaN(m.tick()[s])) continue;
                sLo = Math.min(sLo, m.tick()[s]);
                sHi = Math.max(sHi, m.tick()[s]);
            }
            double[] lo = new double[comps.length], hi = new double[comps.length];
            Arrays.fill(lo, Double.MAX_VALUE);
            Arrays.fill(hi, -Double.MAX_VALUE);
            int[] cnt = new int[comps.length];
            for (int i = 0; i < perpStates.length; i++) {
                int c = own[i];
                double t = m.tick()[perpStates[i]];
                if (Double.isNaN(t)) continue;
                lo[c] = Math.min(lo[c], t);
                hi[c] = Math.max(hi[c], t);
                cnt[c]++;
            }
            StringBuilder where = new StringBuilder();
            for (int c = 0; c < comps.length; c++) {
                where.append(String.format("  [%.0f-%.0f]x%d%s", lo[c], hi[c], cnt[c],
                        hi[c] < sLo ? "before" : lo[c] > sHi ? "after" : "SPANS"));
            }
            System.out.printf("        S tau %.0f-%.0f;%s%n", sLo, sHi, where);
        }
    }

    /** What the reachability assertion found. */
    private record Assertion(int entrances, boolean entrancesOk, int exits, boolean exitsOk) {}

    private static Assertion assertReach(int[] base, int[] live, int liveCount, int e, int[] s,
                                         int[] succ, byte[] degree, int[] pred, byte[] predDegree) {
        boolean[] reachesS = walk(base, e, s, pred, predDegree);   // can get to S
        boolean[] fromS = walk(base, e, s, succ, degree);          // S can get to
        boolean[] inS = new boolean[base.length];
        for (int t : s) inS[t] = true;

        int entrances = 0, entrancesOk = 0, exits = 0, exitsOk = 0;
        for (int i = 0; i < liveCount; i++) {
            int t = live[i];
            if (base[t] == e) {
                boolean entrance = false;
                for (int j = 0; j < predDegree[t]; j++) {
                    if (base[pred[t * 3 + j]] != e) entrance = true;
                }
                if (entrance) {
                    entrances++;
                    if (reachesS[t] || inS[t]) entrancesOk++;
                }
            } else {
                // An exit is off the edge, with a predecessor on it.
                boolean exit = false, from = false;
                for (int j = 0; j < predDegree[t]; j++) {
                    int p = pred[t * 3 + j];
                    if (base[p] != e) continue;
                    exit = true;
                    if (fromS[p] || inS[p]) from = true;
                }
                if (exit) {
                    exits++;
                    if (from) exitsOk++;
                }
            }
        }
        return new Assertion(entrances, entrancesOk == entrances, exits, exitsOk == exits);
    }

    /** Connected components of a state set, ignoring the direction of travel. */
    private static int[] components(int[] states, int[] succ, byte[] degree, int[] base, int e,
                                    Side[] side) {
        Map<Integer, Integer> index = new LinkedHashMap<>();
        for (int i = 0; i < states.length; i++) index.put(states[i], i);
        int[] parent = new int[states.length];
        for (int i = 0; i < parent.length; i++) parent[i] = i;
        for (int s : states) {
            for (int j = 0; j < degree[s]; j++) {
                Integer to = index.get(succ[s * 3 + j]);
                if (to == null) continue;
                int a = find(parent, index.get(s)), b = find(parent, to);
                if (a != b) parent[a] = b;
            }
        }
        Map<Integer, Integer> size = new LinkedHashMap<>();
        for (int i = 0; i < parent.length; i++) size.merge(find(parent, i), 1, Integer::sum);
        int[] out = new int[size.size()];
        int k = 0;
        for (int v : size.values()) out[k++] = v;
        Arrays.sort(out);
        return out;
    }

    /** Which component each state belongs to, numbered from zero in first-seen order. */
    private static int[] componentOf(int[] states, int[] succ, byte[] degree) {
        Map<Integer, Integer> index = new LinkedHashMap<>();
        for (int i = 0; i < states.length; i++) index.put(states[i], i);
        int[] parent = new int[states.length];
        for (int i = 0; i < parent.length; i++) parent[i] = i;
        for (int s : states) {
            for (int j = 0; j < degree[s]; j++) {
                Integer to = index.get(succ[s * 3 + j]);
                if (to == null) continue;
                int a = find(parent, index.get(s)), b = find(parent, to);
                if (a != b) parent[a] = b;
            }
        }
        Map<Integer, Integer> label = new LinkedHashMap<>();
        int[] out = new int[states.length];
        for (int i = 0; i < states.length; i++) {
            out[i] = label.computeIfAbsent(find(parent, i), k -> label.size());
        }
        return out;
    }

    private static int find(int[] parent, int x) {
        while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x]; }
        return x;
    }

    private static StateSet grow(NavMap map, MapStates lattice, StateSet within, int chosen,
                                 Shape shape) {
        if (shape == Shape.GOOD) {
            StateSet p = lattice.of(chosen).partialTick(StateSet.Steering.STRAIGHT)
                    .backwardsPerfect().forwardsPerfect();
            return p.union(p.inverted()).backwardsPerfect().forwardsPerfect();
        }
        // The state and its unsteered successor, partial-ticked together, then the successor
        // taken back out. The hole it leaves is reachable from one member and reaches another,
        // which is exactly the dent interiorPerfect is for.
        int straight = map.successor(chosen, 0);
        StateSet dented = lattice.of(chosen, straight)
                .partialTick(StateSet.Steering.STRAIGHT).minus(lattice.of(straight));
        if (shape == Shape.CONCAVE) return dented;
        // Interior perfection first, then exactly the chain GOOD gets, so the only difference
        // between the two rows is the dent and whether it was filled.
        StateSet p = dented.interiorPerfect(within).backwardsPerfect().forwardsPerfect();
        return p.union(p.inverted()).backwardsPerfect().forwardsPerfect();
    }

    private static int[] statesOf(int[] base, int[] live, int liveCount, int e) {
        int[] out = new int[liveCount];
        int n = 0;
        for (int i = 0; i < liveCount; i++) if (base[live[i]] == e) out[n++] = live[i];
        return Arrays.copyOf(out, n);
    }

    private static int middleOf(int[] base, int[] live, int liveCount, EdgeMetric.Metric m, int e) {
        double target = m.length()[e] / 2, best = Double.MAX_VALUE;
        int chosen = -1;
        for (int i = 0; i < liveCount; i++) {
            int s = live[i];
            if (base[s] != e || Double.isNaN(m.tick()[s])) continue;
            double d = Math.abs(m.tick()[s] - target);
            if (d < best) { best = d; chosen = s; }
        }
        return chosen;
    }

    /** Taus to try, as fractions of an edge's length. Weighted towards the ends. */
    private static final double[] FRACTIONS =
            {0.01, 0.03, 0.06, 0.12, 0.25, 0.50, 0.75, 0.88, 0.94, 0.97, 0.99};

    /**
     * Inserts a minimal {@code S} at a spread of taus along each edge, to see whether choosing one
     * near an existing boundary always blows up.
     * <p>
     * <b>Fully conditioned</b>: partial tick, perfected both ways, unioned with its inverse and
     * perfected again — the chain that settles nine of nine at the midpoint. That is deliberate.
     * With a raw partial tick almost every placement blows up, midpoints included, so the
     * conditioning has to be held fixed for the placement to be what is being measured.
     */
    public static void sweep(PresetScenarioParameter preset, SolverFacts.Gate gate,
                             double minLength, int skipEdge) throws IOException {
        Ground g = ground(preset, gate);
        System.out.printf("%n=== %s @%s: near-edge sweep, S = one state + partial tick ===%n",
                preset.name(), preset.ingest().hash());
        System.out.printf("%-5s %7s %6s %5s %7s %7s  %s%n", "edge", "length", "tau", "|S|",
                "to end", "pieces", "");

        for (int e = 0; e < g.edges(); e++) {
            if (g.m().length()[e] < minLength || e == skipEdge) continue;
            for (double f : FRACTIONS) {
                double want = g.m().length()[e] * f;
                int chosen = nearestTau(g, e, want);
                if (chosen < 0) continue;
                final int on = e;
                StateSet p = g.lattice().of(chosen).partialTick(StateSet.Steering.STRAIGHT)
                        .backwardsPerfect().forwardsPerfect();
                StateSet core = p.union(p.inverted()).backwardsPerfect().forwardsPerfect();
                int[] sOn = Arrays.stream(core.toArray()).filter(s -> g.base()[s] == on).toArray();

                int[] edge = g.base().clone();
                for (int s : sOn) edge[s] = g.edges();
                int after = EdgeDecomposition.refine(g.live(), g.liveCount(), g.succ(), g.degree(),
                        g.pred(), g.predDegree(), edge, g.edges() + 1);

                double tau = g.m().tick()[chosen];
                double toEnd = Math.min(tau, g.m().length()[e] - tau);
                System.out.printf("%-5d %7.1f %6.1f %5d %7.1f %7d  %s%n", e, g.m().length()[e],
                        tau, sOn.length, toEnd, after,
                        after == g.edges() + 3 ? "settles" : "blows up");
            }
        }
    }

    /**
     * Inserts a minimal {@code S} at the most open point of each edge, to try to make {@code E⊥S}
     * come apart into a piece on either side.
     * <p>
     * <b>Most open</b> means the largest {@code r} for which every {@code (x±r, y±r, d)} is a live
     * state at the same heading — a square of clearance at fixed heading, which is a cheap stand-in
     * for how far the state sits from a wall. A state in the middle of the corridor's width leaves
     * room on both sides of it; one against a wall does not, and then {@code E⊥S} has only one
     * side to be on.
     */
    public static void centred(PresetScenarioParameter preset, SolverFacts.Gate gate,
                               double minLength, int skipEdge) throws IOException {
        Ground g = ground(preset, gate);
        System.out.printf("%n=== %s @%s: S at the most open point of each edge ===%n",
                preset.name(), preset.ingest().hash());
        System.out.printf("%-5s %10s %5s %6s %5s %7s  %s%n", "edge", "state", "clear", "tau",
                "|S|", "pieces", "E_|_S components, tau range");

        for (int e = 0; e < g.edges(); e++) {
            if (g.m().length()[e] < minLength || e == skipEdge) continue;
            int best = -1, bestR = -1;
            for (int i = 0; i < g.liveCount(); i++) {
                int s = g.live()[i];
                if (g.base()[s] != e || Double.isNaN(g.m().tick()[s])) continue;
                // Mid-edge only. The largest clearance anywhere on an edge is at a junction,
                // where corridors cross and four edges share the pixels - the worst place to put
                // S, and not what "room on either side" was asking for.
                double t = g.m().tick()[s], len = g.m().length()[e];
                if (t < 0.3 * len || t > 0.7 * len) continue;
                int r = clearance(g.map(), s);
                if (r > bestR) { bestR = r; best = s; }
            }
            if (best < 0) continue;

            final int on = e;
            StateSet core = g.lattice().of(best).partialTick(StateSet.Steering.STRAIGHT);
            int[] sOn = Arrays.stream(core.toArray()).filter(s -> g.base()[s] == on).toArray();

            int[] edge = g.base().clone();
            for (int s : sOn) edge[s] = g.edges();
            int after = EdgeDecomposition.refine(g.live(), g.liveCount(), g.succ(), g.degree(),
                    g.pred(), g.predDegree(), edge, g.edges() + 1);

            Side[] side = sides(g.base(), g.live(), g.liveCount(), e, sOn, g.succ(), g.degree(),
                    g.pred(), g.predDegree());
            int[] perp = new int[g.liveCount()];
            int np = 0;
            for (int i = 0; i < g.liveCount(); i++) {
                int s = g.live()[i];
                if (g.base()[s] == e && side[s] == Side.APART) perp[np++] = s;
            }
            int[] perpStates = Arrays.copyOf(perp, np);
            int[] own = componentOf(perpStates, g.succ(), g.degree());
            int comps = 0;
            for (int c : own) comps = Math.max(comps, c + 1);

            double sLo = Double.MAX_VALUE, sHi = -Double.MAX_VALUE;
            for (int s : sOn) {
                if (Double.isNaN(g.m().tick()[s])) continue;
                sLo = Math.min(sLo, g.m().tick()[s]);
                sHi = Math.max(sHi, g.m().tick()[s]);
            }
            double[] lo = new double[comps], hi = new double[comps];
            int[] cnt = new int[comps];
            Arrays.fill(lo, Double.MAX_VALUE);
            Arrays.fill(hi, -Double.MAX_VALUE);
            for (int i = 0; i < perpStates.length; i++) {
                double t = g.m().tick()[perpStates[i]];
                if (Double.isNaN(t)) continue;
                lo[own[i]] = Math.min(lo[own[i]], t);
                hi[own[i]] = Math.max(hi[own[i]], t);
                cnt[own[i]]++;
            }
            StringBuilder where = new StringBuilder();
            for (int c = 0; c < comps; c++) {
                where.append(String.format(" [%.0f-%.0f]x%d%s", lo[c], hi[c], cnt[c],
                        hi[c] < sLo ? "bef" : lo[c] > sHi ? "aft" : "spans"));
            }
            if (comps > 1) {
                drawComponents(g.map(), g.base(), g.live(), g.liveCount(), e, sOn, side,
                        perpStates, own, comps, "edge" + e);
                drawCrossSections(g.map(), g.base(), g.live(), g.liveCount(), e, sOn, side,
                        perpStates, own, g.m(), "edge" + e, 12, 4, "-cross");
                drawCrossSections(g.map(), g.base(), g.live(), g.liveCount(), e, sOn, side,
                        perpStates, own, g.m(), "edge" + e, 3, 16, "-cross-zoom");
            }
            int turns = Params.TURNS, w = g.map().width();
            System.out.printf("%-5d %10s %5d %6.1f %5d %7d  %d:%s%n", e,
                    "(" + best / turns % w + "," + best / turns / w + "," + best % turns + ")",
                    bestR, g.m().tick()[best], sOn.length, after, comps, where);
        }
    }

    /** Largest {@code r} with every {@code (x±r, y±r, d)} alive at this state's own heading. */
    private static int clearance(NavMap map, int s) {
        int turns = Params.TURNS, w = map.width();
        int d = s % turns, cell = s / turns, x = cell % w, y = cell / w;
        for (int r = 1; r <= 8; r++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    int nx = x + dx, ny = y + dy;
                    if (nx < 0 || ny < 0 || nx >= w || ny >= map.height()
                            || !map.alive(nx, ny, d)) {
                        return r - 1;
                    }
                }
            }
        }
        return 8;
    }

    private static int nearestTau(Ground g, int e, double want) {
        int chosen = -1;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < g.liveCount(); i++) {
            int s = g.live()[i];
            if (g.base()[s] != e || Double.isNaN(g.m().tick()[s])) continue;
            double d = Math.abs(g.m().tick()[s] - want);
            if (d < best) { best = d; chosen = s; }
        }
        return chosen;
    }

    /** The decomposition, the clock and the adjacency, built once and shared by both sweeps. */
    private record Ground(NavMap map, int[] live, int[] base, int liveCount, int edges,
                          EdgeMetric.Metric m, MapStates lattice, int[] succ, byte[] degree,
                          int[] pred, byte[] predDegree) {}

    private static Ground ground(PresetScenarioParameter preset, SolverFacts.Gate gate)
            throws IOException {
        EdgeDecomposition.Labelling l = SimTest.labelFor(preset, gate.horizontal(), gate.line(),
                gate.lo(), gate.hi(), gate.dir());
        EdgeMetric.Metric m = EdgeMetricStore.of(SimTest.structure(preset, gate).at("metric"),
                l.map(), l.edge(), l.live(), l.liveCount(), l.edges(), SimTest.SCHEME,
                SimTest.CHAIN);
        int[][] adj = adjacency(l.map(), l.edge(), l.live(), l.liveCount());
        return new Ground(l.map(), l.live(), l.edge(), l.liveCount(), l.edges(), m,
                MapStates.of(l.map(), Flocking.of(preset.turningRadius()), l.live(), l.liveCount()),
                adj[0], bytes(adj[1]), adj[2], bytes(adj[3]));
    }

    /**
     * Draws one edge coloured by what the insertion made of it, with {@code E⊥S}'s connected
     * components each in their own colour.
     * <p>
     * <b>Two pictures, because the question is whether the components are phases.</b> The whole
     * map at 2x says where they lie; a crop at 8x says whether they interleave pixel by pixel — an
     * evenly spread speckle is phase and not structure, §8. A pixel carries up to 64 headings, so
     * where several states of the edge share one the component with the lowest index wins; nothing
     * may be read from the shadow beyond whether it is speckled.
     */
    private static void drawComponents(NavMap map, int[] base, int[] live, int liveCount, int e,
                                       int[] sOn, Side[] side, int[] perpStates, int[] own,
                                       int comps, String name) throws IOException {
        int w = map.width(), h = map.height(), turns = Params.TURNS;
        int[] paint = new int[w * h];
        Arrays.fill(paint, -1);
        boolean[] inS = new boolean[base.length];
        for (int s : sOn) inS[s] = true;

        // E<S and E>S first, components over them, and S last of all. S is a handful of states
        // sharing pixels with hundreds of others, so anything painted after it hides it - which
        // it was, until 2026-09-11.
        for (int i = 0; i < liveCount; i++) {
            int s = live[i];
            if (base[s] != e || inS[s]) continue;
            int cell = s / turns;
            int role = side[s] == Side.BEFORE ? 1 : side[s] == Side.AFTER ? 2 : -1;
            if (role >= 0 && paint[cell] < 0) paint[cell] = role;
        }
        for (int i = 0; i < perpStates.length; i++) {
            int cell = perpStates[i] / turns;
            int c = 3 + own[i];
            if (paint[cell] < 3 || c < paint[cell]) paint[cell] = c;
        }
        for (int s : sOn) paint[s / turns] = 0;

        int[] roleColour = {0xFFFFFF, 0x25408F, 0x8F2525};
        int[] compColour = {0x3CB44B, 0xFFE119, 0xF032E6, 0x46F0F0, 0xF58231, 0x911EB4,
                            0xBCF60C, 0xAAFFC3};

        // The crop follows the components, since that is what is being looked at.
        int lx = w, hx = 0, ly = h, hy = 0;
        for (int s : perpStates) {
            int cell = s / turns, x = cell % w, y = cell / w;
            lx = Math.min(lx, x); hx = Math.max(hx, x);
            ly = Math.min(ly, y); hy = Math.max(hy, y);
        }

        java.nio.file.Path dir = java.nio.file.Path.of("render", "gate-split");
        java.nio.file.Files.createDirectories(dir);
        write(map, paint, roleColour, compColour, 0, 0, w, h, 2,
                dir.resolve(name + "-components.png"));
        int pad = 6;
        write(map, paint, roleColour, compColour, Math.max(0, lx - pad), Math.max(0, ly - pad),
                Math.min(w, hx + pad + 1), Math.min(h, hy + pad + 1), 8,
                dir.resolve(name + "-components-zoom.png"));
        System.out.printf("        wrote %s-components.png and -zoom.png (crop %d,%d to %d,%d)%n",
                name, lx, ly, hx, hy);
    }

    private static void write(NavMap map, int[] paint, int[] roleColour, int[] compColour,
                              int x0, int y0, int x1, int y1, int scale,
                              java.nio.file.Path out) throws IOException {
        int w = map.width();
        java.awt.image.BufferedImage img =
                new java.awt.image.BufferedImage((x1 - x0) * scale, (y1 - y0) * scale, 1);
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int p = paint[x + y * w];
                int rgb = map.oob(x, y) ? 0x000000
                        : p < 0 ? 0x191C22
                        : p < 3 ? roleColour[p]
                        : compColour[(p - 3) % compColour.length];
                for (int sy = 0; sy < scale; sy++) {
                    for (int sx = 0; sx < scale; sx++) {
                        img.setRGB((x - x0) * scale + sx, (y - y0) * scale + sy, rgb);
                    }
                }
            }
        }
        javax.imageio.ImageIO.write(img, "png", out.toFile());
    }

    /**
     * The edge's cross-section, sliced by tau and taken perpendicular to travel.
     * <p>
     * <b>A corridor's cross-section is two-dimensional</b> — one axis across it, and {@code d}.
     * That is the shape {@code S} has to cut in two for {@code E⊥S} to come apart into sides, and
     * it cannot be seen in a map view, where {@code d} is collapsed and every state at a pixel is
     * painted over by whichever is drawn last.
     * <p>
     * <b>Perpendicular, because an edge bends.</b> Slicing on a fixed screen axis gives tiles as
     * wide as the edge's whole bounding box, nearly all of it empty. So each slice is a band of
     * tau, and within it the across-coordinate is the offset perpendicular to the band's mean
     * heading: {@code -(x-x̄) sin θ + (y-ȳ) cos θ}. Headings are averaged as angles rather than as
     * numbers, since 0 and 63 are neighbours.
     * <p>
     * One tile per band, left to right in travel order; horizontal is across, vertical is all 64
     * headings with 0 at the top. A tile is the parallelogram. <b>If {@code S} — white — does not
     * reach two sides of it, {@code E⊥S} can get around {@code S} and will not split.</b>
     */
    private static void drawCrossSections(NavMap map, int[] base, int[] live, int liveCount, int e,
                                          int[] sOn, Side[] side, int[] perpStates, int[] own,
                                          EdgeMetric.Metric m, String name, int SPAN, int SCALE,
                                          String SUFFIX) throws IOException {
        int w = map.width(), turns = Params.TURNS;
        boolean[] inS = new boolean[base.length];
        for (int s : sOn) inS[s] = true;
        int[] comp = new int[base.length];
        Arrays.fill(comp, -1);
        for (int i = 0; i < perpStates.length; i++) comp[perpStates[i]] = own[i];

        double sMid = 0;
        int sn = 0;
        for (int s : sOn) if (!Double.isNaN(m.tick()[s])) { sMid += m.tick()[s]; sn++; }
        sMid = sn == 0 ? 0 : sMid / sn;

        // A band per tick of tau, for a dozen either side of S.
        int span = SPAN;
        List<List<Integer>> bands = new ArrayList<>();
        for (int k = -span; k <= span; k++) bands.add(new ArrayList<>());
        for (int i = 0; i < liveCount; i++) {
            int s = live[i];
            if (base[s] != e || Double.isNaN(m.tick()[s])) continue;
            int k = (int) Math.round(m.tick()[s] - sMid);
            if (k >= -span && k <= span) bands.get(k + span).add(s);
        }

        int scale = SCALE, gap = 3, across = 15, tileW = across * scale, tileH = turns * scale;
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                bands.size() * (tileW + gap), tileH + 14, 1);
        int[] roleColour = {0xFFFFFF, 0x25408F, 0x8F2525};
        int[] compColour = {0x3CB44B, 0xFFE119, 0xF032E6, 0x46F0F0, 0xF58231, 0x911EB4,
                            0xBCF60C, 0xAAFFC3};
        for (int p = 0; p < img.getWidth(); p++) {
            for (int q = 0; q < img.getHeight(); q++) img.setRGB(p, q, 0x000000);
        }

        for (int b = 0; b < bands.size(); b++) {
            List<Integer> band = bands.get(b);
            if (band.isEmpty()) continue;
            double cx = 0, cy = 0, sinSum = 0, cosSum = 0;
            for (int s : band) {
                int cell = s / turns;
                cx += cell % w;
                cy += cell / w;
                double a = 2 * Math.PI * (s % turns) / turns;
                sinSum += Math.sin(a);
                cosSum += Math.cos(a);
            }
            cx /= band.size();
            cy /= band.size();
            double th = Math.atan2(sinSum, cosSum);
            double nx = -Math.sin(th), ny = Math.cos(th);

            int col = b * (tileW + gap);
            boolean hasS = false;
            for (int s : band) {
                int cell = s / turns, d = s % turns;
                double off = (cell % w - cx) * nx + (cell / w - cy) * ny;
                int u = (int) Math.round(off) + across / 2;
                if (u < 0 || u >= across) continue;
                int rgb = inS[s] ? roleColour[0]
                        : comp[s] >= 0 ? compColour[comp[s] % compColour.length]
                        : side[s] == Side.BEFORE ? roleColour[1] : roleColour[2];
                if (inS[s]) hasS = true;
                for (int sy = 0; sy < scale; sy++) {
                    for (int sx = 0; sx < scale; sx++) {
                        img.setRGB(col + u * scale + sx, d * scale + sy, rgb);
                    }
                }
            }
            if (hasS) {
                for (int sy = tileH + 4; sy < tileH + 10; sy++) {
                    for (int sx = 0; sx < tileW; sx++) img.setRGB(col + sx, sy, 0xFFFFFF);
                }
            }
        }
        java.nio.file.Path dir = java.nio.file.Path.of("render", "gate-split");
        java.nio.file.Files.createDirectories(dir);
        javax.imageio.ImageIO.write(img, "png", dir.resolve(name + SUFFIX + ".png").toFile());
        System.out.printf("        wrote %s%s.png: %d tau bands around %.1f, %d across x %d "
                + "headings%n", name, SUFFIX, bands.size(), sMid, across, turns);
    }

    private static final int[] PALETTE = {
        0xE6194B, 0x3CB44B, 0xFFE119, 0x4363D8, 0xF58231, 0x911EB4, 0x46F0F0, 0xF032E6,
        0xBCF60C, 0xFABEBE, 0x008080, 0xE6BEFF, 0x9A6324, 0x800000, 0xAAFFC3, 0x808000,
    };

    /**
     * Two pictures of one edge: its states coloured by which side of {@code S} they are on, and
     * the same states coloured by which piece refinement put them in.
     * <p>
     * <b>The pair is the point.</b> If the piece picture is a speckle where the side picture is
     * three regions, the extra pieces are phase rather than structure — `EDGES.md` §8 — and the
     * question becomes whether that matters for a gate. If it is regions, they are real.
     * <p>
     * A renderer, so {@code (x, y)} is allowed. A pixel carries up to 64 headings on several
     * edges, so it is painted only where a state of <em>this</em> edge sits on it, and where two
     * such states disagree the lower piece id wins; nothing may be concluded from the shadow
     * beyond whether it is speckled.
     */
    private static void draw(NavMap map, int[] base, int[] live, int liveCount, int e, int[] edge,
                             Side[] side, List<Piece> pieces, boolean phaseBleed)
            throws IOException {
        int w = map.width(), h = map.height(), turns = Params.TURNS, scale = 2;
        Map<Integer, Integer> rank = new LinkedHashMap<>();
        for (Piece p : pieces) rank.put(p.id(), rank.size());

        java.awt.image.BufferedImage bySide =
                new java.awt.image.BufferedImage(w * scale, h * scale, 1);
        java.awt.image.BufferedImage byPiece =
                new java.awt.image.BufferedImage(w * scale, h * scale, 1);
        int[] sideAt = new int[w * h], pieceAt = new int[w * h];
        Arrays.fill(sideAt, -1);
        Arrays.fill(pieceAt, -1);
        for (int i = 0; i < liveCount; i++) {
            int s = live[i];
            if (base[s] != e) continue;
            int cell = s / turns;
            int r = rank.getOrDefault(edge[s], 0);
            if (pieceAt[cell] < 0 || r < pieceAt[cell]) pieceAt[cell] = r;
            int sd = switch (side[s]) {
                case AT -> 0; case BEFORE -> 1; case AFTER -> 2; case APART -> 3;
            };
            if (sideAt[cell] < 0 || sd < sideAt[cell]) sideAt[cell] = sd;
        }
        int[] sideColour = {0xFFFFFF, 0x4363D8, 0xE6194B, 0x404040};
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int cell = x + y * w;
                int a = map.oob(x, y) ? 0x000000
                        : sideAt[cell] < 0 ? 0x14171C : sideColour[sideAt[cell]];
                int b = map.oob(x, y) ? 0x000000
                        : pieceAt[cell] < 0 ? 0x14171C
                        : PALETTE[pieceAt[cell] % PALETTE.length];
                for (int sy = 0; sy < scale; sy++) {
                    for (int sx = 0; sx < scale; sx++) {
                        bySide.setRGB(x * scale + sx, y * scale + sy, a);
                        byPiece.setRGB(x * scale + sx, y * scale + sy, b);
                    }
                }
            }
        }
        java.nio.file.Path dir = java.nio.file.Path.of("render",
                phaseBleed ? "gate-split-bleed" : "gate-split");
        java.nio.file.Files.createDirectories(dir);
        javax.imageio.ImageIO.write(bySide, "png", dir.resolve("edge" + e + "-sides.png").toFile());
        javax.imageio.ImageIO.write(byPiece, "png",
                dir.resolve("edge" + e + "-pieces.png").toFile());
    }

    /**
     * Which side of {@code S} each state of edge {@code e} is on, by breadth-first search that
     * never leaves the edge.
     */
    private static Side[] sides(int[] base, int[] live, int liveCount, int e, int[] s,
                                int[] succ, byte[] degree, int[] pred, byte[] predDegree) {
        Side[] side = new Side[base.length];
        for (int i = 0; i < liveCount; i++) if (base[live[i]] == e) side[live[i]] = Side.APART;

        boolean[] forward = walk(base, e, s, succ, degree);
        boolean[] backward = walk(base, e, s, pred, predDegree);
        boolean[] inS = new boolean[base.length];
        for (int t : s) inS[t] = true;
        for (int i = 0; i < liveCount; i++) {
            int t = live[i];
            if (base[t] != e || inS[t]) continue;
            if (backward[t]) side[t] = Side.BEFORE;
            else if (forward[t]) side[t] = Side.AFTER;
        }
        for (int t : s) side[t] = Side.AT;
        return side;
    }

    /** Everything reachable from any of {@code s} over {@code adj}, without leaving edge {@code e}. */
    private static boolean[] walk(int[] base, int e, int[] s, int[] adj, byte[] adjDegree) {
        boolean[] seen = new boolean[base.length];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int t : s) { seen[t] = true; queue.add(t); }
        while (!queue.isEmpty()) {
            int v = queue.poll();
            for (int j = 0; j < adjDegree[v]; j++) {
                int u = adj[v * 3 + j];
                if (seen[u] || base[u] != e) continue;
                seen[u] = true;
                queue.add(u);
            }
        }
        return seen;
    }

    private static void report(List<Split> splits) {
        System.out.printf("%n%-5s %8s %8s %6s %7s %7s %8s  %s%n", "edge", "length", "states",
                "pieces", "in", "out", "apart", "how it came apart");
        int clean = 0, covered = 0;
        for (Split s : splits) {
            if (s.clean()) clean++;
            if (s.covers()) covered++;
            StringBuilder how = new StringBuilder();
            for (Piece p : s.pieces()) {
                if (how.length() > 0) how.append("  ");
                how.append(String.format("%s:%d", p.mixed() ? "MIXED" : p.dominant().label,
                        p.states()));
            }
            System.out.printf("%-5d %8.2f %8d %6d %6.1f%% %6.1f%% %8d  %s%s%n", s.edge(),
                    s.length(), s.states(), s.pieces().size(), 100 * s.inFront(),
                    100 * s.behind(), s.totals().getOrDefault(Side.APART, 0), how,
                    s.clean() ? "" : "   <-- more than four pieces");
        }
        System.out.printf("%n%d of %d edges came apart into exactly the four predicted pieces%n",
                clean, splits.size());
        System.out.printf("%d of %d have every entrance behind S and every exit ahead of it%n",
                covered, splits.size());

        for (Split s : splits) {
            if (s.clean()) continue;
            System.out.printf("%nedge %d, in full:%n", s.edge());
            for (Piece p : s.pieces()) {
                System.out.printf("  piece %-3d %7d states  %s%n", p.id(), p.states(),
                        p.counts());
            }
        }
    }
}
