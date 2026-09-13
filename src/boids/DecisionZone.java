package boids;

import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * One edge's decision, as gates: a region the psyboid enters, the options it is offered there,
 * the transitions each option forbids, and the gate that ends the matter.
 * <p>
 * <b>Specified by the user 2026-09-12; {@code ROADMAP.md} §0i.</b> Everything a psyboid does is
 * meant to be highly local and of one shape: it crosses a gate that <em>opens</em> a decision
 * zone; depending on the decision it is handed one or more gates it will not cross; and it
 * crosses a gate that <em>closes</em> the zone. Exits are the first instance; shortcuts and
 * longcuts are meant to be the next, on the same shape with a different region.
 *
 * <h2>The exit construction</h2>
 * Let {@code S} be every state of edge {@code E} that can leave {@code E} in one permitted turn,
 * plus the forward closure of those on {@code E}. Then
 * <ul>
 *   <li>the <b>opening gate</b> is every transition from {@code E−S} to {@code S};</li>
 *   <li>the <b>prohibited gate</b> for choosing successor {@code g} is every transition from
 *       {@code E} into a successor other than {@code g};</li>
 *   <li>the <b>closing gate</b> is every transition off {@code E}.</li>
 * </ul>
 * The opening and closing gates sit on the same edge with the opening one upstream, so a
 * traversal crosses them in pairs — which is the same thing as saying they bound a convex set of
 * states, and that set is {@code S}. {@code S} is forward-closed on {@code E} by construction, so
 * there is no navigation from {@code S} out to {@code E−S} and back, and the interior-perfection
 * step that would otherwise fill such a dent has nothing to add. <b>So the zone is stored as its
 * region</b>, and <em>being inside it</em> stands in for <em>having crossed the opening gate and
 * not yet the closing one</em>. That is what lets {@link DecisionOverride} be stateless. The
 * prohibitions have no such reading and stay transitions.
 *
 * <h2>What makes the opening gate a gate</h2>
 * Exactly once per traversal needs two things, both checked in {@link #exits}: no entrance of
 * {@code E} lies inside {@code S} (a traversal starting inside never crosses in), and every state
 * of {@code E} can reach {@code S} without leaving {@code E} (no traversal misses it). The second
 * follows from the axiom — every state reaches every successor, and only states of {@code S} can
 * step off — and is verified rather than assumed.
 */
public final class DecisionZone {

    private final int edge;
    /** Sorted. */
    private final int[] region;
    private final Gate opens;
    private final Gate closes;
    /** The successor edges, ascending, and per option the transitions into it. */
    private final int[] options;
    private final Gate[] into;

    /** Per option, the union of the other options' transitions; built once. */
    private final Gate[] prohibited;

    /** Diagnostics from the build; see {@link #report}. */
    private final int states, entrances, entrancesInside, bypassing, leaks;

    private DecisionZone(int edge, int[] region, Gate opens, Gate closes, int[] options,
                         Gate[] into, int states, int entrances, int entrancesInside,
                         int bypassing, int leaks) {
        this.edge = edge;
        this.region = region;
        this.opens = opens;
        this.closes = closes;
        this.options = options;
        this.into = into;
        this.states = states;
        this.entrances = entrances;
        this.entrancesInside = entrancesInside;
        this.bypassing = bypassing;
        this.leaks = leaks;
        this.prohibited = new Gate[options.length];
        for (int k = 0; k < options.length; k++) {
            Gate g = Gate.EMPTY;
            for (int j = 0; j < options.length; j++) if (j != k) g = g.union(into[j]);
            prohibited[k] = g;
        }
    }

    public int edge() { return edge; }

    /** Whether {@code state} is inside the zone: past the opening gate, not yet past the closing. */
    public boolean inside(int state) { return Arrays.binarySearch(region, state) >= 0; }

    public int[] region() { return region.clone(); }

    public Gate opens() { return opens; }

    public Gate closes() { return closes; }

    /** The successor edges a boid in this zone can choose between, ascending. */
    public int[] options() { return options.clone(); }

    /** The transitions from this edge into successor {@code g}. */
    public Gate into(int g) { return into[indexOf(g)]; }

    /** The gate a boid that has chosen {@code g} will not cross. */
    public Gate prohibited(int g) { return prohibited[indexOf(g)]; }

    private int indexOf(int g) {
        int k = Arrays.binarySearch(options, g);
        if (k < 0) {
            throw new IllegalArgumentException("edge " + g + " is not a successor of edge " + edge
                    + "; the options are " + Arrays.toString(options));
        }
        return k;
    }

    /** Whether the opening gate is a gate: nothing starts inside and nothing gets round. */
    public boolean sound() { return entrancesInside == 0 && bypassing == 0 && leaks == 0; }

    public String report() {
        StringBuilder s = new StringBuilder(String.format(
                "edge %2d: %6d states, region %5d, opens %5d, closes %4d, entrances %4d"
                        + " (%d inside), bypassing %d, leaks %d, options", edge, states,
                region.length, opens.size(), closes.size(), entrances, entrancesInside,
                bypassing, leaks));
        for (int k = 0; k < options.length; k++) {
            s.append(String.format(" %d:%d", options[k], into[k].size()));
        }
        return s.toString();
    }

    /**
     * The exit decision of edge {@code e}, built from a labelling.
     *
     * @param edgeOf per state, its edge, or negative off the kernel
     */
    public static DecisionZone exits(NavMap map, int[] edgeOf, int[] live, int liveCount, int e) {
        int n = edgeOf.length;
        int[] on = new int[liveCount];
        int count = 0;
        for (int i = 0; i < liveCount; i++) if (edgeOf[live[i]] == e) on[count++] = live[i];
        on = Arrays.copyOf(on, count);

        // S: states that can leave, then everything they reach without leaving.
        boolean[] inS = new boolean[n];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        int[] out = new int[3];
        for (int s : on) {
            int k = map.steeredSuccessors(s, out);
            for (int j = 0; j < k; j++) {
                if (edgeOf[out[j]] != e) { inS[s] = true; queue.add(s); break; }
            }
        }
        while (!queue.isEmpty()) {
            int s = queue.poll();
            int k = map.steeredSuccessors(s, out);
            for (int j = 0; j < k; j++) {
                int u = out[j];
                if (edgeOf[u] == e && !inS[u]) { inS[u] = true; queue.add(u); }
            }
        }
        int[] region = Arrays.stream(on).filter(s -> inS[s]).toArray();
        int[] rest = Arrays.stream(on).filter(s -> !inS[s]).toArray();

        Gate opens = Gate.between(map, rest, u -> inS[u]);
        Gate closes = Gate.between(map, region, u -> edgeOf[u] != e);
        Gate leaksOut = Gate.between(map, region, u -> edgeOf[u] == e && !inS[u]);

        // The options: every edge some transition off E lands on.
        long mask = 0;
        for (int k : closes.keys()) mask |= 1L << edgeOf[map.successor(Gate.stateOf(k), Gate.turnOf(k))];
        int[] options = new int[Long.bitCount(mask)];
        for (int g = 0, k = 0; g < 64; g++) if ((mask & (1L << g)) != 0) options[k++] = g;
        Gate[] into = new Gate[options.length];
        for (int k = 0; k < options.length; k++) {
            final int g = options[k];
            into[k] = Gate.between(map, region, u -> edgeOf[u] == g);
        }

        // Entrances: on-edge states with an off-edge predecessor. None may lie inside S.
        int entrances = 0, entrancesInside = 0;
        for (int s : on) {
            int k = map.steeredPredecessors(s, out);
            boolean entrance = false;
            for (int j = 0; j < k && !entrance; j++) entrance = edgeOf[out[j]] != e;
            if (entrance) { entrances++; if (inS[s]) entrancesInside++; }
        }

        // Unavoidable: everything on E reaches S staying on E. Walk backwards from S.
        boolean[] reaches = new boolean[n];
        for (int s : region) { reaches[s] = true; queue.add(s); }
        while (!queue.isEmpty()) {
            int s = queue.poll();
            int k = map.steeredPredecessors(s, out);
            for (int j = 0; j < k; j++) {
                int p = out[j];
                if (edgeOf[p] == e && !reaches[p]) { reaches[p] = true; queue.add(p); }
            }
        }
        int bypassing = 0;
        for (int s : on) if (!reaches[s]) bypassing++;

        return new DecisionZone(e, region, opens, closes, options, into, count, entrances,
                entrancesInside, bypassing, leaksOut.size());
    }

    /** One zone per edge, from a solver's facts. */
    public static DecisionZone[] exits(NavMap map, SolverFacts f) {
        short[] packed = f.edgeOf();
        int[] edgeOf = new int[packed.length];
        int liveCount = 0;
        for (int i = 0; i < packed.length; i++) {
            edgeOf[i] = packed[i];
            if (packed[i] >= 0) liveCount++;
        }
        int[] live = new int[liveCount];
        for (int i = 0, at = 0; i < packed.length; i++) if (packed[i] >= 0) live[at++] = i;
        DecisionZone[] zones = new DecisionZone[f.edges()];
        for (int e = 0; e < f.edges(); e++) zones[e] = exits(map, edgeOf, live, liveCount, e);
        return zones;
    }

    @Override
    public String toString() {
        return "DecisionZone[edge " + edge + ", region " + region.length + ", options "
                + Arrays.toString(options) + "]";
    }
}
