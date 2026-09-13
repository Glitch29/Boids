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

    /**
     * Inserts one set into one edge and checks everything the insertion is supposed to guarantee.
     * <p>
     * Reports, per edge: whether the reachability <b>assertion</b> holds on the supplied set, how
     * many pieces refinement produced, and how many connected components {@code E⊥S} falls into,
     * and draws the components ({@code render/gate-split/edge<e>-components.png}, {@code -zoom}).
     * The axiom splits on predecessor and successor edges and never on connectivity, so those
     * components stay one edge however disconnected they are; on dabeone they are the phases
     * that bypass {@code S}, and merge to one.
     * <p>
     * {@code S} is the construction that settles nine of nine: the seed's partial tick, perfected
     * both ways, unioned with its inverse, perfected again. The shapes it was tested against —
     * a deliberately concave seed, with and without interior perfection — are recorded in
     * {@code EDGES.md} §2a and were removed with the rest of the experiment drivers on 2026-09-13.
     */
    public static void insert(PresetScenarioParameter preset, SolverFacts.Gate gate,
                              double minLength) throws IOException {
        EdgeDecomposition.Labelling l = SimTest.labelFor(preset, gate.horizontal(), gate.line(),
                gate.lo(), gate.hi(), gate.dir());
        NavMap map = l.map();
        int[] live = l.live(), base = l.edge();
        int liveCount = l.liveCount(), edges = l.edges();
        EdgeMetric.Metric m = EdgeMetricStore.of(SimTest.structure(preset, gate).at("metric"),
                map, base, live, liveCount, edges);
        MapStates lattice = MapStates.of(map, Flocking.of(preset.turningRadius()), live, liveCount);

        int[][] adj = adjacency(map, base, live, liveCount);
        int[] succ = adj[0], pred = adj[2];
        byte[] degree = bytes(adj[1]), predDegree = bytes(adj[3]);

        System.out.printf("%n=== %s @%s: edge insertion ===%n", preset.name(),
                preset.ingest().hash());
        System.out.printf("%-5s %9s %5s %6s %8s %8s %7s  %s%n", "edge", "|S|", "OOB",
                "pieces", "entrances", "exits", "perp", "notes");

        for (int e = 0; e < edges; e++) {
            if (m.length()[e] < minLength) continue;
            final int on = e;
            StateSet within = lattice.of(statesOf(base, live, liveCount, e));
            int chosen = middleOf(base, live, liveCount, m, e);

            StateSet core = grow(map, lattice, within, chosen);
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
            drawComponents(map, base, live, liveCount, e, sOn, side, perpStates, own, comps.length,
                    "edge" + e);

            System.out.printf("%-5d %9d %5s %6d %8s %8s %7s  %s%n", e, sOn.length,
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

    /**
     * The set inserted around a seed: its partial tick, perfected both ways, unioned with its
     * inverse, and perfected again — the chain that settles nine of nine. A concave seed would
     * want {@link StateSet#interiorPerfect} first ({@code EDGES.md} §2a); a partial tick does not.
     */
    private static StateSet grow(NavMap map, MapStates lattice, StateSet within, int chosen) {
        StateSet p = lattice.of(chosen).partialTick(StateSet.Steering.STRAIGHT)
                .backwardsPerfect().forwardsPerfect();
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
    /** The decomposition, the clock and the adjacency, built once and shared by both sweeps. */
    private record Ground(NavMap map, int[] live, int[] base, int liveCount, int edges,
                          EdgeMetric.Metric m, MapStates lattice, int[] succ, byte[] degree,
                          int[] pred, byte[] predDegree) {}

    private static Ground ground(PresetScenarioParameter preset, SolverFacts.Gate gate)
            throws IOException {
        EdgeDecomposition.Labelling l = SimTest.labelFor(preset, gate.horizontal(), gate.line(),
                gate.lo(), gate.hi(), gate.dir());
        EdgeMetric.Metric m = EdgeMetricStore.of(SimTest.structure(preset, gate).at("metric"),
                l.map(), l.edge(), l.live(), l.liveCount(), l.edges());
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

    /**
     * Draws lines across one cross-section of a straight, axis-aligned edge and uses each as
     * {@code S}, to see which shapes bifurcate {@code E⊥S}.
     * <p>
     * The cross-section is every state of the edge at one {@code x}, which on a corridor running
     * along {@code x} with no phase bleed is one tau band exactly. Within it a state is
     * {@code (y, d)}: {@code y} across the corridor, {@code d} the heading. A line is a set of those,
     * and the question is what a line has to look like to cut the cross-section in two.
     * <p>
     * <b>What the step geometry says should happen.</b> A tick changes {@code d} by at most one,
     * so a line at <em>constant {@code d}</em> spanning every {@code y} cannot be stepped over:
     * any path from below it to above it lands on {@code d0} at some tick, and at the tau of the
     * line that landing is in {@code S}. A line at <em>constant {@code y}</em> is different:
     * {@code y} moves by {@code stepY(d)} per tick, which is 0 near the along-track heading and
     * grows to 2 a few headings off it, so at the top and bottom of the cross-section a boid can
     * jump {@code y0} without touching it. Diagonals sit in between. That is the prediction; the
     * table is the test.
     */
    public static void lines(PresetScenarioParameter preset, SolverFacts.Gate gate, int e,
                             int x) throws IOException {
        Ground g = ground(preset, gate);
        int turns = Params.TURNS, w = g.map().width();

        // The cross-section, and its extent in y and d.
        List<Integer> cross = new ArrayList<>();
        int yLo = Integer.MAX_VALUE, yHi = -1, dLo = Integer.MAX_VALUE, dHi = -1;
        for (int i = 0; i < g.liveCount(); i++) {
            int s = g.live()[i];
            if (g.base()[s] != e) continue;
            int cell = s / turns;
            if (cell % w != x) continue;
            int y = cell / w, d = s % turns;
            cross.add(s);
            yLo = Math.min(yLo, y); yHi = Math.max(yHi, y);
            dLo = Math.min(dLo, d); dHi = Math.max(dHi, d);
        }
        System.out.printf("%n=== %s: edge %d cross-section at x=%d: %d states, y %d-%d, d %d-%d "
                + "===%n", preset.name(), e, x, cross.size(), yLo, yHi, dLo, dHi);
        for (int d = dLo; d <= dHi; d++) {
            StringBuilder row = new StringBuilder(String.format("   d=%-3d ", d));
            for (int y = yLo; y <= yHi; y++) {
                int s = (x + y * w) * turns + d;
                row.append(g.base()[s] == e && g.map().alive(x, y, d) ? '#' : '.');
            }
            System.out.printf("%s   stepY=%d%n", row, g.map().stepY(d));
        }
        int y0 = (yLo + yHi) / 2, d0 = (dLo + dHi) / 2;

        // A line is a signed function of (y, d): negative below, zero on, positive above. The
        // thin form is where it is zero; the thick form adds one. Signing it is what lets each
        // surviving piece of E_|_S be called above or below, which is the whole test.
        record Line(String name, java.util.function.IntBinaryOperator f, boolean thick) {}
        List<Line> specs = List.of(
                new Line("horizontal d=" + d0, (y, d) -> d - d0, false),
                new Line("horizontal 2 thick", (y, d) -> d - d0, true),
                new Line("vertical y=" + y0, (y, d) -> y - y0, false),
                new Line("vertical 2 thick", (y, d) -> y - y0, true),
                new Line("diagonal y-y0 = d-d0", (y, d) -> (y - y0) - (d - d0), false),
                new Line("diagonal 2 thick", (y, d) -> (y - y0) - (d - d0), true),
                new Line("anti-diagonal y-y0 = d0-d", (y, d) -> (y - y0) + (d - d0), false),
                new Line("anti-diagonal 2 thick", (y, d) -> (y - y0) + (d - d0), true),
                new Line("shallow d-d0 = 2(y-y0)", (y, d) -> (d - d0) - 2 * (y - y0), false),
                new Line("steep y-y0 = 2(d-d0)", (y, d) -> (y - y0) - 2 * (d - d0), false));

        System.out.printf("%n%-28s %4s %5s %5s %9s  %s%n", "line", "|S|", "in", "out",
                "2 rounds", "E_|_S after merge: below / straddling / above  (sizes)");
        for (Line spec : specs) {
            int[] raw = cross.stream().filter(s -> {
                int cell = s / turns;
                int v = spec.f().applyAsInt(cell / w, s % turns);
                return v == 0 || (spec.thick() && v == 1);
            }).mapToInt(Integer::intValue).toArray();
            if (raw.length == 0) continue;
            // Phase-complete the line. A step is 4 px, so a line at one x is reachable by one
            // phase in four; the partial tick adds the intermediate samples of each state's own
            // step, which on an axis-aligned stretch is the same (y, d) at the three other x's.
            // "No phase bleed" means this is essential rather than unnecessary: nothing else
            // will bring the other phases onto S.
            StateSet set = g.lattice().of(raw).partialTick(StateSet.Steering.STRAIGHT);
            final int on = e;
            int[] sOn = Arrays.stream(set.toArray()).filter(s -> g.base()[s] == on).toArray();

            Side[] side = sides(g.base(), g.live(), g.liveCount(), e, sOn, g.succ(), g.degree(),
                    g.pred(), g.predDegree());

            int entrances = 0, entrancesOk = 0, exits = 0, exitsOk = 0;
            List<Integer> perp = new ArrayList<>();
            for (int i = 0; i < g.liveCount(); i++) {
                int s = g.live()[i];
                if (g.base()[s] != e) continue;
                if (side[s] == Side.APART) perp.add(s);
                boolean entrance = false, exit = false;
                for (int j = 0; j < g.predDegree()[s]; j++) {
                    if (g.base()[g.pred()[s * 3 + j]] != e) entrance = true;
                }
                for (int j = 0; j < g.degree()[s]; j++) {
                    if (g.base()[g.succ()[s * 3 + j]] != e) exit = true;
                }
                if (entrance) { entrances++; if (side[s] != Side.APART) entrancesOk++; }
                if (exit) { exits++; if (side[s] == Side.AFTER || side[s] == Side.AT) exitsOk++; }
            }
            int[] perpStates = perp.stream().mapToInt(Integer::intValue).toArray();
            int[] own = componentOf(perpStates, g.succ(), g.degree());
            int comps = 0;
            for (int c : own) comps = Math.max(comps, c + 1);

            // Merge the phase copies the way the insertion does, then call each survivor by the
            // sign of the line at its states: all negative is below, all positive is above, and
            // any mix means the piece straddles the line - which is the line failing to cut.
            int[] edge2 = g.base().clone();
            int first = g.edges() + 1;
            for (int i = 0; i < perpStates.length; i++) edge2[perpStates[i]] = first + own[i];
            EdgeDecomposition.mergeAlongside(g.map(), g.live(), g.liveCount(), edge2,
                    first + comps, first);
            Map<Integer, int[]> tally = new LinkedHashMap<>();   // id -> {below, above, size}
            for (int s : perpStates) {
                int cell = s / turns;
                int v = spec.f().applyAsInt(cell / w, s % turns);
                int[] t = tally.computeIfAbsent(edge2[s], k -> new int[3]);
                if (v < 0) t[0]++; else if (v > (spec.thick() ? 1 : 0)) t[1]++;
                t[2]++;
            }
            int below = 0, above = 0, straddle = 0;
            StringBuilder sizes = new StringBuilder();
            for (int[] t : tally.values()) {
                String tag;
                if (t[0] > 0 && t[1] > 0) { straddle++; tag = "~"; }
                else if (t[0] > 0) { below++; tag = "-"; }
                else { above++; tag = "+"; }
                sizes.append(' ').append(tag).append(t[2]);
            }

            int[] edge = g.base().clone();
            for (int s : sOn) edge[s] = g.edges();
            int r1 = EdgeDecomposition.refineOnce(g.live(), g.liveCount(), g.succ(), g.degree(),
                    g.pred(), g.predDegree(), edge, g.edges() + 1).edges();
            int r2 = EdgeDecomposition.refineOnce(g.live(), g.liveCount(), g.succ(), g.degree(),
                    g.pred(), g.predDegree(), edge, r1).edges();

            System.out.printf("%-28s %4d %4.0f%% %4.0f%% %4d->%-4d  %d / %d / %d %s%n",
                    spec.name(), sOn.length, 100.0 * entrancesOk / Math.max(1, entrances),
                    100.0 * exitsOk / Math.max(1, exits), r1, r2, below, straddle, above, sizes);
        }
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
}
