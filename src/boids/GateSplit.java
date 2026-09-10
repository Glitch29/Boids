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
                                  double minLength, boolean phaseBleed) throws IOException {
        SimTest.Labelling l = SimTest.labelFor(preset, gate.horizontal(), gate.line(), gate.lo(),
                gate.hi(), gate.dir());
        NavMap map = l.map();
        int[] live = l.live(), base = l.edge();
        int liveCount = l.liveCount(), edges = l.edges();

        EdgeMetric.Metric m = EdgeMetricStore.of(SimTest.structure(preset, gate).at("metric"),
                map, base, live, liveCount, edges, SimTest.SCHEME, SimTest.CHAIN);

        System.out.printf("%n=== %s @%s: gate-from-one-state, edges of length >= %.0f, S = %s ===%n",
                preset.name(), preset.ingest().hash(), minLength,
                phaseBleed ? "state + partial unsteered tick" : "one state");

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
        int settled = SimTest.refine(live, liveCount, succ, degree, pred, predDegree, control,
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
            StateSet all = phaseBleed ? one.partialTick(StateSet.Steering.STRAIGHT) : one;
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

            System.out.printf("%n-- edge %d: %d edges + S (%d states) going in --%n", e, edges,
                    onEdge.length);
            int after = SimTest.refine(live, liveCount, succ, degree, pred, predDegree, edge,
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
            draw(map, base, live, liveCount, e, edge, side, pieces, phaseBleed);
        }

        report(out);
        return out;
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
