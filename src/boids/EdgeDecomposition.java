package boids;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cutting a map into edges: the axiom, and the construction that satisfies it.
 * <p>
 * <b>Canonical statement in {@code EDGES.md} §1 and §3.</b> A set of edges is a valid
 * decomposition when every live point is on exactly one edge and all points on the same edge have
 * the same set of predecessor edges and the same set of successor edges. That is a <em>test</em>
 * and not a constructor — every point as its own edge satisfies it, and so does every point as one
 * edge — so {@link #of} picks a useful answer, in the six steps §3 lays out:
 * <ol>
 *   <li>{@link #orbits} — the points that return to themselves without crossing the cut line, one
 *       edge per strongly connected component;</li>
 *   <li>{@link #mergePhases} — collapse copies of one loop that differ only by phase;</li>
 *   <li>{@link #complementComponents} — everything else, split into connected candidates;</li>
 *   <li>{@link #mergeAlongside} — merge candidates running alongside one another. Plait-only; see
 *       {@link #mergeAlongsideEdges};</li>
 *   <li>{@link #refine} — split any edge whose points disagree, to a fixed point;</li>
 *   <li>{@link #splitOrbits} — cut each orbit with a line of its own, since the axiom cannot
 *       split something strongly connected.</li>
 * </ol>
 *
 * <h2>The cut line is the only human input</h2>
 * It lives in {@code (x, y)}, it is a bootstrap and nothing else, and step 1 is the only place it
 * is read. {@code EDGES.md} §2.
 *
 * <h2>Where this came from</h2>
 * Extracted from {@code SimTest} on 2026-09-10, where it had grown up among fifteen unrelated
 * entry points and was hard to find. A pure move: every labelling it produces is byte-identical to
 * what {@code SimTest} produced before it, checked on all three maps. {@code SimTest} keeps the
 * driver — {@code decompose}, and the reporting and rendering that hang off it.
 */
public final class EdgeDecomposition {
    private EdgeDecomposition() {}

    /** A decomposition's result, for analyses that want to run on top of one. */
    record Labelling(NavMap map, int[] live, int liveCount, int[] edge, int edges) {}

    public static Labelling of(NavMap map, String what, boolean horizontal, int line,
                              int lo, int hi, int dir) {
        int w = map.width(), h = map.height(), turns = Params.TURNS, n = w * h * turns;

        int[] edge = new int[n];
        Arrays.fill(edge, -1);
        int[] live = new int[n];
        int liveCount = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (map.oob(x, y)) continue;
                for (int d = 0; d < turns; d++) {
                    if (map.alive(x, y, d)) live[liveCount++] = (x + y * w) * turns + d;
                }
            }
        }
        System.out.printf("%n=== %s: decomposition from gate %s=%d, %s=[%d,%d], %s ===%n",
                what, horizontal ? "y" : "x", line,
                horizontal ? "x" : "y", lo, hi,
                dir == 0 ? "both ways" : dir > 0 ? "increasing" : "decreasing");
        System.out.printf("%d live states%n", liveCount);

        // Successor lists, with the gate-crossing ones flagged.
        int[] succ = new int[n * 3];
        byte[] degree = new byte[n];
        boolean[] cuts = new boolean[n * 3];
        int crossings = 0;
        for (int i = 0; i < liveCount; i++) {
            int s = live[i];
            int d = s % turns, cell = s / turns, x = cell % w, y = cell / w;
            for (int t = -1; t <= 1; t++) {
                if (map.constrainTurn(x, y, d, t) != t) continue;
                int nd = Math.floorMod(d + t, turns);
                int nx = x + map.stepX(nd), ny = y + map.stepY(nd);
                if (nx < 0 || ny < 0 || nx >= w || ny >= h || map.oob(nx, ny)) continue;
                if (!map.alive(nx, ny, nd)) continue;
                // The gate is a line segment; a transition cuts it when the step crosses
                // that line while inside the segment's span. dir 0 counts either way.
                int from = horizontal ? y : x, to = horizontal ? ny : nx;
                int alongFrom = horizontal ? x : y, alongTo = horizontal ? nx : ny;
                boolean inSpan = (alongFrom >= lo && alongFrom <= hi)
                        || (alongTo >= lo && alongTo <= hi);
                boolean forward = from < line && to >= line;
                boolean backward = from > line && to <= line;
                boolean cut = inSpan && (dir > 0 ? forward : dir < 0 ? backward
                        : forward || backward);
                if (cut) crossings++;
                int k = s * 3 + degree[s];
                succ[k] = (nx + ny * w) * turns + nd;
                cuts[k] = cut;
                degree[s]++;
            }
        }
        System.out.printf("%d transitions cross the gate%n", crossings);

        // Reverse adjacency, so refinement can look backwards as well as forwards. Every
        // predecessor of a state sits at one pixel on one of three headings, so three
        // slots is always enough.
        byte[] predDegree = new byte[n];
        int[] pred = new int[n * 3];
        for (int i = 0; i < liveCount; i++) {
            int s = live[i];
            for (int j = 0; j < degree[s]; j++) {
                int u = succ[s * 3 + j];
                pred[u * 3 + predDegree[u]++] = s;
            }
        }

        int edges = orbits(live, liveCount, succ, degree, cuts, edge);
        System.out.printf("orbits (SCCs of the gate-cut graph): %d%n", edges);
        int distinct = mergePhases(map, live, liveCount, edge, edges);
        System.out.printf("after collapsing orbit phases: %d -> %d orbits%n", edges, distinct);
        edges = distinct;
        int orbitCount = edges;
        edges = complementComponents(map, live, liveCount, succ, degree, edge, edges);
        System.out.printf("plus complement components: %d edges before refinement%n", edges);
        if (mergeAlongsideEdges) {
            int alongside = mergeAlongside(map, live, liveCount, edge, edges, orbitCount);
            System.out.printf("after merging edges that run alongside: %d -> %d edges%n",
                    edges, alongside);
            edges = alongside;
        } else {
            System.out.printf("alongside merge SKIPPED, %d edges%n", edges);
        }
        edges = refine(live, liveCount, succ, degree, pred, predDegree, edge, edges);
        System.out.printf("after refinement: %d edges%n", edges);
        edges = splitOrbits(map, live, liveCount, succ, degree, pred, predDegree, edge, edges);
        System.out.printf("%nafter cutting orbits: %d edges%n", edges);
        return new Labelling(map, live, liveCount, edge, edges);
    }

    /** Strongly connected components of the gate-cut graph; each cycle-bearing one is an edge. */
    private static int orbits(int[] live, int liveCount, int[] succ, byte[] degree,
                              boolean[] cuts, int[] edge) {
        int n = edge.length;
        int[] index = new int[n], low = new int[n], comp = new int[n];
        Arrays.fill(index, -1);
        Arrays.fill(comp, -1);
        boolean[] onStack = new boolean[n];
        int[] tarjan = new int[liveCount + 1], frame = new int[liveCount + 1];
        int[] stack = new int[liveCount + 1];
        int counter = 0, sp = 0, tp = 0, nextComp = 0;
        int[] compSize = new int[liveCount + 1];
        boolean[] compCyclic = new boolean[liveCount + 1];

        for (int i = 0; i < liveCount; i++) {
            int root = live[i];
            if (index[root] >= 0) continue;
            tarjan[tp] = root; frame[tp] = 0; tp++;
            index[root] = low[root] = counter++;
            stack[sp++] = root; onStack[root] = true;
            while (tp > 0) {
                int v = tarjan[tp - 1];
                if (frame[tp - 1] < degree[v]) {
                    int k = v * 3 + frame[tp - 1]++;
                    if (cuts[k]) continue;                 // gate-crossing edges are removed
                    int u = succ[k];
                    if (index[u] < 0) {
                        index[u] = low[u] = counter++;
                        stack[sp++] = u; onStack[u] = true;
                        tarjan[tp] = u; frame[tp] = 0; tp++;
                    } else if (onStack[u]) {
                        low[v] = Math.min(low[v], index[u]);
                    }
                } else {
                    tp--;
                    if (tp > 0) low[tarjan[tp - 1]] = Math.min(low[tarjan[tp - 1]], low[v]);
                    if (low[v] == index[v]) {
                        int size = 0;
                        boolean self = false;
                        int mark = sp;
                        int u;
                        do {
                            u = stack[--sp];
                            onStack[u] = false;
                            comp[u] = nextComp;
                            size++;
                        } while (u != v);
                        if (size == 1) {                   // a lone state is an orbit only
                            for (int j = 0; j < degree[v]; j++) {   // if it loops to itself
                                if (!cuts[v * 3 + j] && succ[v * 3 + j] == v) self = true;
                            }
                        }
                        compSize[nextComp] = size;
                        compCyclic[nextComp] = size > 1 || self;
                        nextComp++;
                    }
                }
            }
        }

        int[] remap = new int[nextComp];
        Arrays.fill(remap, -1);
        int edges = 0;
        for (int c = 0; c < nextComp; c++) if (compCyclic[c]) remap[c] = edges++;
        for (int i = 0; i < liveCount; i++) {
            int s = live[i];
            if (comp[s] >= 0 && remap[comp[s]] >= 0) edge[s] = remap[comp[s]];
        }
        return edges;
    }

    /**
     * Components of the complement, joined by travel in either direction within it — and
     * by direct adjacency in {@code (x,y,d)}.
     * <p>
     * Movement is a fixed ~4px step, so a boid cannot drift between phases: where an edge
     * connecting two orbits has tight tolerances, the four phase offsets never mix and
     * come out as four separate branches. That is an artifact of discretisation, not
     * structure, and it compounds — sixteen combinations of four branches, then
     * combinations of those, fragmenting everything upstream.
     * <p>
     * Treating neighbouring states as connected collapses the phases back together. At
     * worst it under-splits, which the refinement immediately puts right.
     */
    private static int complementComponents(NavMap map, int[] live, int liveCount, int[] succ,
                                            byte[] degree, int[] edge, int edges) {
        int[] parent = new int[edge.length];
        Arrays.fill(parent, -1);
        for (int i = 0; i < liveCount; i++) if (edge[live[i]] < 0) parent[live[i]] = live[i];
        int[] nb = new int[MAX_PHASE_NEIGHBOURS];
        for (int i = 0; i < liveCount; i++) {
            int s = live[i];
            if (edge[s] >= 0) continue;
            for (int j = 0; j < degree[s]; j++) {
                int u = succ[s * 3 + j];
                if (edge[u] < 0) union(parent, s, u);
            }
            int n = phaseNeighbours(map, s, nb);
            for (int k = 0; k < n; k++) if (edge[nb[k]] < 0) union(parent, s, nb[k]);
        }
        Map<Integer, Integer> label = new java.util.HashMap<>();
        for (int i = 0; i < liveCount; i++) {
            int s = live[i];
            if (edge[s] >= 0) continue;
            edge[s] = label.computeIfAbsent(find(parent, s), k -> label.size() + edges);
        }
        return edges + label.size();
    }

    /**
     * The states that are the same motion as {@code s}, differing only in where within a
     * step the boid happens to have been sampled.
     * <p>
     * Two boids one pixel apart on the same heading take identical turn sequences and stay
     * one pixel apart forever, so where the play area is wide enough to hold both copies
     * but too tight for a turn to cross between them, one manoeuvre appears as several. The
     * step is the reason: at ~4px a tick, a pixel and the pixel four along are the same
     * trajectory sampled a tick apart, while the three pixels between them belong to
     * trajectories that never touch it.
     * <p>
     * So the neighbourhood is one pixel and one heading either side. Following the step's
     * own sample set instead — {@link NavMap#stepPath}, the partial steps the collision test
     * uses — was tried on plait and dabeone and changed nothing either way: copies that are
     * a whole step apart are already joined by travel, so the only ones needing a kernel are
     * the ones sitting side by side.
     */
    private static int phaseNeighbours(NavMap map, int s, int[] out) {
        int w = map.width(), turns = Params.TURNS;
        int d = s % turns, cell = s / turns, x = cell % w, y = cell / w;
        int n = 0;
        for (int k = 0; k < 6; k++) {
            int nx = x + ADJ_X[k], ny = y + ADJ_Y[k], nd = Math.floorMod(d + ADJ_D[k], turns);
            if (map.alive(nx, ny, nd)) out[n++] = (nx + ny * w) * turns + nd;
        }
        return n;
    }

    private static final int[] ADJ_X = {1, -1, 0, 0, 0, 0};
    private static final int[] ADJ_Y = {0, 0, 1, -1, 0, 0};
    private static final int[] ADJ_D = {0, 0, 0, 0, 1, -1};

    /** One pixel or one heading either side. */
    private static final int MAX_PHASE_NEIGHBOURS = 6;

    /** Refinement carries each state's successor edges in a {@code long}, so 63 is the ceiling. */
    private static final int MASK_LIMIT = 63;

    /**
     * Merges complement edges that run alongside one another, whichever way they are flown.
     * <p>
     * Two edges occupying the same corridor are one piece of the map, and keeping them apart
     * makes every state upstream distinguishable by which of the two it reaches — the same
     * fragmentation {@link #mergePhases} takes out of the orbits, one level up. On plait a
     * pair of 330-state edges around the bulb refined the other 351,000 states into 63 edges
     * and then 1281; joining that one pair brings the whole map down to nine.
     * <p>
     * Alongside means within a tick's travel in {@code (x, y)} and within one heading
     * <em>modulo half a turn</em>, so a corridor flown one way and the same corridor flown
     * the other way count as the same corridor. That is the single place in the
     * decomposition where direction is deliberately ignored; everywhere else {@code d} and
     * {@code d + TURNS/2} are as far apart as two states get.
     * <p>
     * Orbits are excluded — they are labelled before this step and have already been through
     * {@link #mergePhases} with direction intact, and the two counter-rotating loops that
     * carry most of a map would otherwise be joined on the first comparison.
     * <p>
     * One arbitrary point per edge is enough to test with. The theoretically right measure is
     * the Hausdorff distance, but edges lying alongside anywhere lie alongside throughout,
     * and anything wrongly joined is separated again by refinement, which stops seeing the
     * two halves as agreeing about where they can go next.
     */
    private static int mergeAlongside(NavMap map, int[] live, int liveCount, int[] edge,
                                      int edges, int firstComplement) {
        int w = map.width(), turns = Params.TURNS, half = turns / 2;
        double reach = Params.speed(map.radius());
        double reach2 = reach * reach;

        int[] rep = new int[edges];
        Arrays.fill(rep, -1);
        for (int i = 0; i < liveCount; i++) {
            int s = live[i], e = edge[s];
            if (e >= firstComplement && rep[e] < 0) rep[e] = s;
        }

        int[] parent = new int[edges];
        for (int i = 0; i < edges; i++) parent[i] = i;
        for (int i = 0; i < liveCount; i++) {
            int s = live[i], e = edge[s];
            if (e < firstComplement) continue;
            int sd = s % turns, sc = s / turns, sx = sc % w, sy = sc / w;
            for (int a = firstComplement; a < edges; a++) {
                if (rep[a] < 0 || find(parent, a) == find(parent, e)) continue;
                int r = rep[a], rd = r % turns, rc = r / turns;
                int dx = sx - rc % w, dy = sy - rc / w;
                if (dx * dx + dy * dy > reach2) continue;
                int dd = Math.floorMod(sd - rd, half);
                if (Math.min(dd, half - dd) > 1) continue;
                union(parent, a, e);
            }
        }

        Map<Integer, Integer> label = new java.util.HashMap<>();
        for (int i = 0; i < liveCount; i++) {
            int s = live[i];
            if (edge[s] < 0) continue;
            edge[s] = label.computeIfAbsent(find(parent, edge[s]), k -> label.size());
        }
        return label.size();
    }


    /**
     * Collapses labelled components that differ only by phase, and renumbers from zero.
     * <p>
     * Run on the orbits, where the artifact does its real damage: an orbit is found by
     * strong connectivity, and phase copies of one loop are not strongly connected to each
     * other, so each copy becomes an edge of its own. Plait's tapering wedge holds two
     * minimum-radius circles a pixel apart, each flown both ways, and the four came out as
     * four edges — after which every state upstream split by which of them it could reach,
     * turning six edges into a hundred and four.
     * <p>
     * Merging across a real boundary is not much of a risk here: refinement splits any edge
     * whose points disagree about where they can go next, so an over-merge is undone on the
     * next pass. Only the phase copies, which agree by construction, survive it.
     */
    private static int mergePhases(NavMap map, int[] live, int liveCount, int[] edge, int edges) {
        int[] parent = new int[edges];
        for (int i = 0; i < edges; i++) parent[i] = i;
        int[] nb = new int[MAX_PHASE_NEIGHBOURS];
        for (int i = 0; i < liveCount; i++) {
            int s = live[i];
            if (edge[s] < 0) continue;
            int n = phaseNeighbours(map, s, nb);
            for (int k = 0; k < n; k++) {
                if (edge[nb[k]] >= 0) union(parent, edge[s], edge[nb[k]]);
            }
        }
        Map<Integer, Integer> label = new java.util.HashMap<>();
        for (int i = 0; i < liveCount; i++) {
            int s = live[i];
            if (edge[s] < 0) continue;
            edge[s] = label.computeIfAbsent(find(parent, edge[s]), k -> label.size());
        }
        return label.size();
    }

    private static int find(int[] parent, int x) {
        while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x]; }
        return x;
    }

    private static void union(int[] parent, int a, int b) {
        int ra = find(parent, a), rb = find(parent, b);
        if (ra != rb) parent[ra] = rb;
    }

    /**
     * Splits any edge whose points disagree on the edges either side of them.
     * <p>
     * The axiom is symmetric: all points of an edge must share both their successor edges
     * and their predecessor edges. Forward alone finds branches but is blind to merges,
     * since arriving from somewhere new creates no forward distinction â€” so an A loop with
     * two branches and two merges comes out as two segments instead of four.
     */
    static int refine(int[] live, int liveCount, int[] succ, byte[] degree,
                      int[] pred, byte[] predDegree, int[] edge, int edges) {
        for (int round = 1; round <= 12; round++) {
            if (edges > MASK_LIMIT) { System.out.println("  too many edges to mask"); return edges; }
            Refined r = refineOnce(live, liveCount, succ, degree, pred, predDegree, edge, edges);
            System.out.printf("  refinement round %d: %d -> %d edges%n", round, edges, r.edges());
            if (r.edges() == edges) return edges;
            edges = r.edges();
        }
        return edges;
    }

    /**
     * One round of refinement, with the masks it grouped on.
     * <p>
     * <b>The masks name the edges as they were on entry</b>, while {@code edge} comes back holding
     * the new numbering — which is what makes them readable as an explanation. A split says "these
     * states of edge X can reach Y and those cannot", and Y is only meaningful in the numbering
     * that was in force when the question was asked.
     * <p>
     * They are the masks as {@link #absorb} left them, which is what the grouping actually saw.
     * Reporting the raw ones would show disagreements the refinement had already forgiven.
     */
    record Refined(int edges, long[] next, long[] back) {}

    /**
     * Whether a mask is reduced before it is grouped on. See {@link #absorb}.
     * <p>
     * A flag rather than a straight change because it alters the decomposition algorithm itself,
     * and every artifact in the tree is addressed by a decomposition. Off reproduces every
     * recorded figure; on is the corrected rule.
     */
    static boolean absorbEquivalentMasks = true;

    /**
     * Whether edges running alongside one another are merged before refinement.
     * <p>
     * The step identifies headings mod 32, so it can merge an edge with its own inverse. Measured
     * 2026-09-10 by running every map with it off:
     * <ul>
     *   <li><b>dabeone and dabnt: it merges nothing at all</b> — two edges in, two out, and the
     *       labelling comes back byte-identical with it skipped. So dabeone edge 8 being two
     *       mutually inverse regions is <em>not</em> this step's doing. It is what the axiom
     *       produces: the scoring corridor has the same predecessor and successor edges travelled
     *       either way, so it is one edge.</li>
     *   <li><b>plait still needs it</b>, six edges to four. Skipped there, orbit 0 refines 7, 12,
     *       14, 19, 45, 296, runs past the 63-edge mask and {@link #splitOrbits} throws. The
     *       {@link #absorb} fix did not remove the need for it.</li>
     * </ul>
     */
    static boolean mergeAlongsideEdges = true;

    /**
     * Reduces a first-different-edge mask to its equivalence class.
     * <p>
     * <b>The rule.</b> Reaching {@code X} and reaching {@code X}'s successors are not different
     * facts: {@code {X}}, {@code {all of X's successors}} and {@code {X} + any of X's successors}
     * all say the same thing about where a state can get to. So a mask holding both {@code X} and
     * something {@code X} steps to is reduced by dropping the latter, repeatedly, until nothing
     * more can go.
     * <p>
     * <b>What it fixes.</b> Without it, two states of one edge that agree about everything except
     * whether they can slip directly into an edge already downstream of one they both reach are
     * split apart, and refinement follows that down. Observed on dabeone as {@code E<S} disagreeing
     * over {@code E>S} while agreeing that both reach {@code S} — and {@code E>S} is downstream of
     * {@code S}, so the disagreement was never real. The rule was known to be needed when the
     * algorithm was specified and deferred, because it can only bite on edges shorter than one
     * tick and there were none.
     * <p>
     * <b>The antisymmetry guard is load-bearing.</b> {@code Y} is dropped for being downstream of
     * {@code X} only when {@code X} is not also downstream of {@code Y}: two edges that step to
     * each other are each other's successors, and without the guard a mask holding both would
     * empty itself.
     */
    static long absorb(long mask, long[] arcs, int edges) {
        long out = mask;
        for (boolean changed = true; changed; ) {
            changed = false;
            long keep = out;
            for (int x = 0; x < edges; x++) {
                if ((out & (1L << x)) == 0) continue;
                for (int y = 0; y < edges; y++) {
                    if (x == y || (out & (1L << y)) == 0) continue;
                    boolean forward = (arcs[x] & (1L << y)) != 0;
                    boolean backward = (arcs[y] & (1L << x)) != 0;
                    if (forward && !backward) keep &= ~(1L << y);
                }
            }
            if (keep != out) { out = keep; changed = true; }
        }
        return out;
    }

    static Refined refineOnce(int[] live, int liveCount, int[] succ, byte[] degree,
                              int[] pred, byte[] predDegree, int[] edge, int edges) {
        long[] next = new long[edge.length], back = new long[edge.length];
        boolean changed = true;
        while (changed) {                       // fixed point: first different edge
            changed = false;
            for (int i = 0; i < liveCount; i++) {
                int s = live[i];
                long acc = 0;
                for (int j = 0; j < degree[s]; j++) {
                    int u = succ[s * 3 + j];
                    acc |= edge[u] != edge[s] ? 1L << edge[u] : next[u];
                }
                if (acc != next[s]) { next[s] = acc; changed = true; }
                long bcc = 0;
                for (int j = 0; j < predDegree[s]; j++) {
                    int p = pred[s * 3 + j];
                    bcc |= edge[p] != edge[s] ? 1L << edge[p] : back[p];
                }
                if (bcc != back[s]) { back[s] = bcc; changed = true; }
            }
        }

        // Which edges each edge steps to, and is stepped to from, at the current labelling. The
        // masks are reduced against these before anything is grouped on them.
        long[] outArcs = new long[edges], inArcs = new long[edges];
        if (absorbEquivalentMasks) {
            for (int i = 0; i < liveCount; i++) {
                int s = live[i];
                for (int j = 0; j < degree[s]; j++) {
                    int u = succ[s * 3 + j];
                    if (edge[u] == edge[s]) continue;
                    outArcs[edge[s]] |= 1L << edge[u];
                    inArcs[edge[u]] |= 1L << edge[s];
                }
            }
        }

        Map<String, Integer> groups = new java.util.HashMap<>();
        int[] fresh = new int[liveCount];
        for (int i = 0; i < liveCount; i++) {
            int s = live[i];
            long f = next[s], b = back[s];
            if (absorbEquivalentMasks) {
                f = absorb(f, outArcs, edges);
                b = absorb(b, inArcs, edges);
            }
            next[s] = f;
            back[s] = b;
            final long ff = f, bb = b;
            fresh[i] = groups.computeIfAbsent(edge[s] + "/" + ff + "/" + bb, k -> groups.size());
        }
        for (int i = 0; i < liveCount; i++) edge[live[i]] = fresh[i];
        return new Refined(groups.size(), next, back);
    }

    /**
     * Cuts each orbit with a gate of its own, then heals the artificial seam.
     * <p>
     * An orbit is strongly connected, so the axiom cannot split it: every point agrees
     * about where it can go next, and the loop stays one edge. Cutting it at a single
     * state breaks that symmetry and lets refinement find the real structure â€” but the cut
     * itself is arbitrary, so it leaves a false boundary. Merging the cut state with the
     * edges either side of it removes the artifact; if the merged edge then satisfies the
     * axiom, the cut was placed somewhere harmless.
     * <p>
     * The cut goes as far as possible, in graph steps, from any state adjacent to another
     * edge. A cut sitting on a real boundary would merge across it and destroy structure.
     */
    private static int splitOrbits(NavMap map, int[] live, int liveCount, int[] succ,
                                   byte[] degree, int[] pred, byte[] predDegree,
                                   int[] edge, int edges) {
        // Refinement renumbers every edge, so the orbits have to be found again each pass
        // rather than listed once. Edges that resist cutting are remembered by their
        // lowest-numbered state, which survives renumbering.
        Set<Integer> skip = new java.util.HashSet<>();
        for (int pass = 1; pass <= 8; pass++) {
            Map<Integer, List<Integer>> byEdge = new java.util.HashMap<>();
            for (int i = 0; i < liveCount; i++) {
                byEdge.computeIfAbsent(edge[live[i]], k -> new ArrayList<>()).add(live[i]);
            }
            int orbit = -1, rep = -1;
            List<Integer> states = null;
            for (var entry : byEdge.entrySet()) {
                int low = Integer.MAX_VALUE;
                for (int s : entry.getValue()) low = Math.min(low, s);
                if (skip.contains(low)) continue;
                // An orbit is any edge some point of which can forward-navigate back to
                // itself â€” a property of the edge, not of which stage produced it.
                if (!cyclic(entry.getValue(), succ, degree, edge, entry.getKey(), null)) continue;
                orbit = entry.getKey();
                states = entry.getValue();
                rep = low;
                break;
            }
            if (orbit < 0) {
                System.out.printf("  pass %d: no orbits left to cut%n", pass);
                break;
            }
            boolean[] inO = new boolean[edge.length];
            for (int s : states) inO[s] = true;

            // Distance from the orbit's own boundary, so the cut lands as far from any
            // real edge border as the loop allows.
            int[] dist = new int[edge.length];
            Arrays.fill(dist, Integer.MAX_VALUE);
            ArrayDeque<Integer> q = new ArrayDeque<>();
            // Both directions of traffic across the boundary count: a gate near where the
            // complement flows back in is as bad as one near where the orbit flows out.
            // The busiest junctions are exactly where several edges meet, and a gate there
            // cuts raggedly and shatters the refinement.
            for (int s : states) {
                boolean border = false;
                for (int j = 0; j < degree[s]; j++) if (!inO[succ[s * 3 + j]]) border = true;
                for (int j = 0; j < predDegree[s]; j++) if (!inO[pred[s * 3 + j]]) border = true;
                if (border) { dist[s] = 0; q.add(s); }
            }
            while (!q.isEmpty()) {
                int s = q.poll();
                for (int j = 0; j < degree[s]; j++) {
                    int u = succ[s * 3 + j];
                    if (inO[u] && dist[u] == Integer.MAX_VALUE) { dist[u] = dist[s] + 1; q.add(u); }
                }
                for (int j = 0; j < predDegree[s]; j++) {
                    int u = pred[s * 3 + j];
                    if (inO[u] && dist[u] == Integer.MAX_VALUE) { dist[u] = dist[s] + 1; q.add(u); }
                }
            }
            // Candidates furthest from the border first; a gate on a real boundary would
            // merge across it and destroy structure.
            List<Integer> candidates = new ArrayList<>(states);
            candidates.sort((a, b) -> Integer.compare(
                    dist[b] == Integer.MAX_VALUE ? -1 : dist[b],
                    dist[a] == Integer.MAX_VALUE ? -1 : dist[a]));

            boolean[] arrival = null;
            int chosen = -1;
            for (int attempt = 0; attempt < 8 && attempt < candidates.size(); attempt++) {
                int c = candidates.get(attempt * Math.max(1, candidates.size() / 64));
                boolean[] hits = gateArrivals(map, c, states, succ, degree, edge, orbit);
                if (hits == null) continue;
                // A gate is only a gate if every cycle in the orbit crosses it.
                if (cyclic(states, succ, degree, edge, orbit, hits)) continue;
                arrival = hits;
                chosen = c;
                break;
            }
            if (arrival == null) {
                System.out.printf("  orbit %d (%d states): no gate cut every cycle, skipping%n",
                        orbit, states.size());
                skip.add(rep);
                continue;
            }
            int d = chosen % Params.TURNS, cell = chosen / Params.TURNS;
            int arrivals = 0;
            for (int s : states) if (arrival[s]) arrivals++;
            System.out.printf("  orbit %d (%d states): gate through (%d,%d,%d), %d steps from "
                            + "its border, %d arrival states%n", orbit, states.size(),
                    cell % map.width(), cell / map.width(), d,
                    dist[chosen] == Integer.MAX_VALUE ? -1 : dist[chosen], arrivals);

            int gPrime = edges++;
            for (int s : states) if (arrival[s]) edge[s] = gPrime;
            edges = refine(live, liveCount, succ, degree, pred, predDegree, edge, edges);

            int cut = chosen;

            // Heal: merge the cut with whatever now sits either side of it inside O.
            int sample = -1;
            for (int s : states) if (arrival[s]) { sample = s; break; }
            int gs = edge[sample], before = -1, after = -1;
            for (int s : states) {
                if (edge[s] == gs) {
                    for (int j = 0; j < degree[s]; j++) {
                        int u = succ[s * 3 + j];
                        if (inO[u] && edge[u] != gs) after = edge[u];
                    }
                } else {
                    for (int j = 0; j < degree[s]; j++) {
                        if (inO[succ[s * 3 + j]] && edge[succ[s * 3 + j]] == gs) before = edge[s];
                    }
                }
            }
            System.out.printf("    merging %d + %d + %d%n", before, gs, after);
            for (int i = 0; i < liveCount; i++) {
                if (edge[live[i]] == before || edge[live[i]] == after) edge[live[i]] = gs;
            }
            // The healed edge must survive the axiom as it stands. Splitting later through
            // cascading refinement is fine; splitting immediately means the gate landed on
            // a real boundary and merged across it, destroying structure. Cut selection is
            // good enough that this is a hard error rather than a retry.
            int parts = groupsWithin(live, liveCount, succ, degree, pred, predDegree, edge, gs);
            if (parts > 1) {
                // Above the mask limit refinement returns without having run, so the heal
                // merged into a labelling that was never refined and the split says nothing
                // about where the gate landed. Distinguishing the two matters: the second
                // reading sent a whole afternoon looking for a bad gate on plait, where the
                // cause was a braid that genuinely refines into more edges than fit a long.
                throw new IllegalStateException((edges > MASK_LIMIT
                        ? "gate through (%d,%d,%d) for orbit %d left a merged edge that splits "
                          + "%d ways under the axiom — but refinement had already given up above "
                          + "%6$d edges, so this is unrefined rather than misplaced"
                        : "gate through (%d,%d,%d) for orbit %d left a merged edge that splits "
                          + "%d ways under the axiom — it sits on a real edge boundary")
                        .formatted(cell % map.width(), cell / map.width(), d, orbit, parts,
                                MASK_LIMIT));
            }
            edges = refine(live, liveCount, succ, degree, pred, predDegree, edge, edges);
            skip.add(rep);
        }
        return edges;
    }

    /** How many ways one edge would split if the axiom were applied right now. */
    private static int groupsWithin(int[] live, int liveCount, int[] succ, byte[] degree,
                                    int[] pred, byte[] predDegree, int[] edge, int target) {
        long[] next = new long[edge.length], back = new long[edge.length];
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 0; i < liveCount; i++) {
                int s = live[i];
                long acc = 0;
                for (int j = 0; j < degree[s]; j++) {
                    int u = succ[s * 3 + j];
                    acc |= edge[u] != edge[s] ? 1L << edge[u] : next[u];
                }
                if (acc != next[s]) { next[s] = acc; changed = true; }
                long bcc = 0;
                for (int j = 0; j < predDegree[s]; j++) {
                    int p = pred[s * 3 + j];
                    bcc |= edge[p] != edge[s] ? 1L << edge[p] : back[p];
                }
                if (bcc != back[s]) { back[s] = bcc; changed = true; }
            }
        }
        Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < liveCount; i++) {
            int s = live[i];
            if (edge[s] == target) seen.add(next[s] + "/" + back[s]);
        }
        return seen.size();
    }

    /**
     * Can any point of this edge forward-navigate back to itself, staying inside it?
     * Transitions arriving at {@code blocked} states are removed first, which is how a
     * candidate gate is tested: if cycles survive it, it is not a cut.
     */
    private static boolean cyclic(List<Integer> states, int[] succ, byte[] degree,
                                  int[] edge, int self, boolean[] blocked) {
        Map<Integer, Integer> colour = new java.util.HashMap<>();
        for (int s : states) colour.put(s, 0);
        ArrayDeque<int[]> stack = new ArrayDeque<>();
        for (int root : states) {
            if (colour.get(root) != 0) continue;
            stack.push(new int[]{root, 0});
            colour.put(root, 1);
            while (!stack.isEmpty()) {
                int[] top = stack.peek();
                int v = top[0];
                if (top[1] < degree[v]) {
                    int u = succ[v * 3 + top[1]++];
                    if (edge[u] != self) continue;
                    if (blocked != null && blocked[u]) continue;   // the gate cuts this
                    Integer c = colour.get(u);
                    if (c == null) continue;
                    if (c == 1) return true;                       // back edge: a cycle
                    if (c == 0) { colour.put(u, 1); stack.push(new int[]{u, 0}); }
                } else {
                    colour.put(v, 2);
                    stack.pop();
                }
            }
        }
        return false;
    }

    /**
     * The states arrived at by crossing a line drawn through {@code at}, perpendicular to
     * its heading and grown both ways until it meets wall.
     * <p>
     * A gate has to be a cut of the whole corridor, not a single state: an orbit is
     * strongly connected, so removing one state leaves it strongly connected by some other
     * path and the axiom finds nothing to split on.
     */
    private static boolean[] gateArrivals(NavMap map, int at, List<Integer> states,
                                          int[] succ, byte[] degree, int[] edge, int self) {
        int turns = Params.TURNS, w = map.width();
        int d = at % turns, cell = at / turns, cx = cell % w, cy = cell / w;
        double nx = map.stepX(d), ny = map.stepY(d);
        double len = Math.hypot(nx, ny);
        if (len == 0) return null;
        nx /= len; ny /= len;
        double px = -ny, py = nx;                     // along the gate line

        // Grow to the walls on both sides, so nothing slips past an end.
        double half = 0;
        while (half < 200) {
            int ax = (int) Math.round(cx + px * (half + 1)), ay = (int) Math.round(cy + py * (half + 1));
            int bx = (int) Math.round(cx - px * (half + 1)), by = (int) Math.round(cy - py * (half + 1));
            if (!map.traversable(ax, ay) && !map.traversable(bx, by)) break;
            half++;
        }
        final double h2 = half + 2, ox = cx + 0.5, oy = cy + 0.5, fnx = nx, fny = ny;

        boolean[] arrival = new boolean[edge.length];
        boolean any = false;
        for (int s : states) {
            int sd = s % turns, sc = s / turns, sx = sc % w, sy = sc / w;
            for (int j = 0; j < degree[s]; j++) {
                int u = succ[s * 3 + j];
                if (edge[u] != self) continue;
                int uc = u / turns, ux = uc % w, uy = uc / w;
                double f0 = (sx - ox) * fnx + (sy - oy) * fny;
                double f1 = (ux - ox) * fnx + (uy - oy) * fny;
                if (!(f0 < 0 && f1 >= 0)) continue;               // must cross forwards
                double t = f1 == f0 ? 0 : -f0 / (f1 - f0);
                double hitX = sx + (ux - sx) * t - ox, hitY = sy + (uy - sy) * t - oy;
                if (Math.abs(hitX * -fny + hitY * fnx) > h2) continue;   // past the ends
                arrival[u] = true;
                any = true;
            }
        }
        return any ? arrival : null;
    }


}
