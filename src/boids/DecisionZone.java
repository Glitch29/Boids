package boids;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One decision, as gates: a region the psyboid enters, the options it is offered there, the
 * transitions each option forbids, and the gate that ends the matter.
 * <p>
 * <b>Specified by the user 2026-09-12; {@code EDGES.md} §2a "Decision zones".</b> Everything a
 * psyboid does is meant to be local and of one shape: it crosses a gate that <em>opens</em> a
 * decision zone; depending on the decision it is handed one or more gates it will not cross; and
 * it crosses a gate that <em>closes</em> the zone. Two kinds exist so far, built by
 * {@link #exits} and {@link #subpath}, and both are stored the same way.
 *
 * <h2>Exits</h2>
 * Let {@code S} be every state of edge {@code E} that can leave {@code E} in one permitted turn,
 * plus the forward closure of those on {@code E}. The opening gate is every transition from
 * {@code E−S} to {@code S}; the prohibited gate for choosing successor {@code g} is every
 * transition from {@code E} into a successor other than {@code g}; the closing gate is every
 * transition off {@code E}. Options are the successor edges.
 *
 * <h2>Subpaths — shortcuts and longcuts</h2>
 * A subpath is a sequence of states {@code s_0 → s_1 → … → s_N} along one edge, consecutive by
 * permitted transitions. For each transition take the <b>partial tick</b> of it — the samples the
 * step sweeps through, at the new heading, landing included — as {@code S_k}; that is what makes
 * the construction phase-complete, since a boid on any phase of the step lattice crossing that
 * stretch lands on one of the samples. Around each {@code S_k} the edge falls into the four
 * <b>phantom edges</b> of edge insertion, {@code E<S_k}, {@code S_k}, {@code E⊥S_k},
 * {@code E>S_k}, which never join the decomposition. Taking the subpath means: <b>from
 * {@code E<S_k}, never step anywhere but {@code E<S_k} or {@code S_k}</b>, for every {@code k} —
 * which forbids {@code E<S_k → E⊥S_k}, and thereby forces {@code E<S_k → S_k}, without ever
 * needing {@code E⊥S_k} to be cut in two. The zone opens at the states of {@code E<S_1} that can
 * step out of it, forward-closed on {@code E<S_1}, exactly as an exit zone opens at the states
 * that can step off the edge; it closes on the transition off {@code E<S_N}. Options are
 * {@link #SKIP} (forbid nothing) and {@link #TAKE}.
 *
 * <h2>Why the zone is a region</h2>
 * The opening and closing gates sit on the same edge with the opening one upstream, so a
 * traversal crosses them in pairs — which is the same thing as saying they bound a convex set of
 * states, one with no navigation from it out and back in. So <em>being inside that set</em>
 * stands in for <em>having crossed the opening gate and not yet the closing one</em>, the zone is
 * stored as its region, and {@link DecisionOverride} needs no memory of what it crossed. It could
 * not keep any: an override is shared by every timeline that descends from the state it was
 * installed on. The prohibitions have no such reading and stay transitions.
 *
 * <h2>What is checked</h2>
 * Every build records the counts that would make the opening gate fail to be a gate — a
 * traversal that starts past it, one that gets round it, one that comes back through it — and,
 * for a subpath, whether the first {@code S} is reachable from every entrance, the last reaches
 * every exit, each {@code S_k} reaches the next on every phase, and no state is left with every
 * turn forbidden. {@link #sound} is all of them zero; {@link #report} prints them.
 */
public final class DecisionZone {

    /** The subpath options. */
    public static final int SKIP = 0, TAKE = 1;

    private final int edge;
    private final String kind;
    /** Sorted. */
    private final int[] region;
    private final Gate opens;
    private final Gate closes;
    /** Option ids, ascending, and per option the gate it forbids. */
    private final int[] options;
    private final Gate[] prohibited;
    /** What the build measured, in order; values that must be zero are listed in {@link #failures}. */
    private final Map<String, Integer> measured;
    private final List<String> failures;
    /** Subpaths only: every state of some {@code S_k}, sorted, and the {@code k} of each. */
    private final int[] sampleStates;
    private final int[] sampleStep;

    private DecisionZone(int edge, String kind, int[] region, Gate opens, Gate closes,
                         int[] options, Gate[] prohibited, Map<String, Integer> measured,
                         List<String> failures, int[] sampleStates, int[] sampleStep) {
        this.edge = edge;
        this.kind = kind;
        this.region = region;
        this.opens = opens;
        this.closes = closes;
        this.options = options;
        this.prohibited = prohibited;
        this.measured = measured;
        this.failures = failures;
        this.sampleStates = sampleStates;
        this.sampleStep = sampleStep;
    }

    /** For a subpath zone, which {@code S_k} holds {@code state} (the lowest {@code k}), or 0. */
    public int stepOf(int state) {
        int i = Arrays.binarySearch(sampleStates, state);
        if (i < 0) return 0;
        while (i > 0 && sampleStates[i - 1] == state) i--;
        return sampleStep[i];
    }

    /** For a subpath zone, the number of transitions {@code N}; 0 for an exit zone. */
    public int steps() { return measured.getOrDefault("steps", 0); }

    public int edge() { return edge; }

    public String kind() { return kind; }

    /** Whether {@code state} is inside the zone: past the opening gate, not yet past the closing. */
    public boolean inside(int state) { return Arrays.binarySearch(region, state) >= 0; }

    public int[] region() { return region.clone(); }

    public Gate opens() { return opens; }

    public Gate closes() { return closes; }

    /** The option ids, ascending: successor edges for an exit zone, {@code SKIP}/{@code TAKE} for a subpath. */
    public int[] options() { return options.clone(); }

    /** The gate a boid that has chosen {@code option} will not cross. */
    public Gate prohibited(int option) {
        int k = Arrays.binarySearch(options, option);
        if (k < 0) {
            throw new IllegalArgumentException(option + " is not an option of " + this
                    + "; the options are " + Arrays.toString(options));
        }
        return prohibited[k];
    }

    /** Whether every count that must be zero is. */
    public boolean sound() { return failures.isEmpty(); }

    public String report() {
        StringBuilder s = new StringBuilder(String.format("edge %2d %-8s region %5d, opens %5d,"
                + " closes %4d", edge, kind, region.length, opens.size(), closes.size()));
        for (var m : measured.entrySet()) s.append(", ").append(m.getKey()).append(' ').append(m.getValue());
        s.append(", forbids");
        for (int k = 0; k < options.length; k++) {
            s.append(String.format(" %d:%d", options[k], prohibited[k].size()));
        }
        if (!failures.isEmpty()) s.append("   <-- NOT SOUND: ").append(failures);
        return s.toString();
    }

    /** A live list and per-state edge from a solver's facts, so zones can be built from those alone. */
    record Kernel(int[] edgeOf, int[] live, int liveCount) {
        static Kernel of(SolverFacts f) {
            short[] packed = f.edgeOf();
            int[] edgeOf = new int[packed.length];
            int liveCount = 0;
            for (int i = 0; i < packed.length; i++) {
                edgeOf[i] = packed[i];
                if (packed[i] >= 0) liveCount++;
            }
            int[] live = new int[liveCount];
            for (int i = 0, at = 0; i < packed.length; i++) if (packed[i] >= 0) live[at++] = i;
            return new Kernel(edgeOf, live, liveCount);
        }
    }

    // ------------------------------------------------------------------ exits

    /**
     * The exit decision of edge {@code e}, built from a labelling.
     *
     * @param edgeOf per state, its edge, or negative off the kernel
     */
    public static DecisionZone exits(NavMap map, int[] edgeOf, int[] live, int liveCount, int e) {
        int n = edgeOf.length;
        int[] on = statesOf(edgeOf, live, liveCount, e);

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
        closeForward(map, edgeOf, e, inS, queue);
        int[] region = Arrays.stream(on).filter(s -> inS[s]).toArray();
        int[] rest = Arrays.stream(on).filter(s -> !inS[s]).toArray();

        Gate opens = Gate.between(map, rest, u -> inS[u]);
        Gate closes = Gate.between(map, region, u -> edgeOf[u] != e);
        Gate leaks = Gate.between(map, region, u -> edgeOf[u] == e && !inS[u]);

        // The options: every edge some transition off E lands on.
        long mask = 0;
        for (int k : closes.keys()) mask |= 1L << edgeOf[map.successor(Gate.stateOf(k), Gate.turnOf(k))];
        int[] options = new int[Long.bitCount(mask)];
        for (int g = 0, k = 0; g < 64; g++) if ((mask & (1L << g)) != 0) options[k++] = g;
        Gate[] into = new Gate[options.length], prohibited = new Gate[options.length];
        for (int k = 0; k < options.length; k++) {
            final int g = options[k];
            into[k] = Gate.between(map, region, u -> edgeOf[u] == g);
        }
        for (int k = 0; k < options.length; k++) {
            Gate g = Gate.EMPTY;
            for (int j = 0; j < options.length; j++) if (j != k) g = g.union(into[j]);
            prohibited[k] = g;
        }

        int[] entrances = entrances(map, edgeOf, on, e);
        int entrancesInside = 0;
        for (int s : entrances) if (inS[s]) entrancesInside++;
        int bypassing = unreaching(map, edgeOf, e, on, region, s -> true);

        Map<String, Integer> measured = new LinkedHashMap<>();
        measured.put("states", on.length);
        measured.put("entrances", entrances.length);
        measured.put("inside", entrancesInside);
        measured.put("bypassing", bypassing);
        measured.put("leaks", leaks.size());
        List<String> failures = new ArrayList<>();
        if (entrancesInside > 0) failures.add("entrances inside");
        if (bypassing > 0) failures.add("bypassing");
        if (leaks.size() > 0) failures.add("leaks");
        return new DecisionZone(e, "exit", region, opens, closes, options, prohibited, measured,
                failures, new int[0], new int[0]);
    }

    /** One exit zone per edge, from a solver's facts. */
    public static DecisionZone[] exits(NavMap map, SolverFacts f) {
        Kernel k = Kernel.of(f);
        DecisionZone[] zones = new DecisionZone[f.edges()];
        for (int e = 0; e < f.edges(); e++) zones[e] = exits(map, k.edgeOf(), k.live(), k.liveCount(), e);
        return zones;
    }

    // --------------------------------------------------------------- subpaths

    /**
     * The decision to take a subpath of edge {@code e}, built from a labelling.
     *
     * @param path states {@code s_0 … s_N} on {@code e}, each the landing of a permitted turn from
     *             the one before
     */
    public static DecisionZone subpath(NavMap map, int[] edgeOf, int[] live, int liveCount,
                                       int e, int[] path) {
        int turns = Params.TURNS, w = map.width();
        int[] on = statesOf(edgeOf, live, liveCount, e);
        int[] slot = new int[edgeOf.length];
        Arrays.fill(slot, -1);
        for (int i = 0; i < on.length; i++) slot[on[i]] = i;
        for (int s : path) {
            if (slot[s] < 0) throw new IllegalArgumentException("path state " + s + " is not on edge " + e);
        }
        int steps = path.length - 1;
        if (steps < 1) throw new IllegalArgumentException("a subpath needs at least one transition");

        // The turn each step takes.
        int[] turn = new int[steps + 1];
        for (int k = 1; k <= steps; k++) {
            int a = path[k - 1], b = path[k];
            int d = a % turns, cell = a / turns, x = cell % w, y = cell / w;
            turn[k] = Integer.MIN_VALUE;
            for (int t = -1; t <= 1; t++) {
                if (map.constrainTurn(x, y, d, t) == t && map.successor(a, t) == b) turn[k] = t;
            }
            if (turn[k] == Integer.MIN_VALUE) {
                throw new IllegalArgumentException(String.format("path step %d: no permitted turn "
                        + "takes (%d,%d,%d) to state %d", k, x, y, d, b));
            }
        }

        // S_1 is the partial tick of the first step: the samples it sweeps through at the new
        // heading, landing included, one per phase of the step lattice. Every later S_k is where
        // the states of S_{k-1} land taking the path's turn — the phases followed forward by the
        // physics rather than by the sample formula, which at a turn rounds a phase onto the
        // wrong pixel about one step in four and breaks the chain.
        BitSet[] inS = new BitSet[steps + 1];
        int altered = 0;
        {
            int a = path[0];
            int d = a % turns, cell = a / turns, x = cell % w, y = cell / w;
            int nd = Math.floorMod(d + turn[1], turns);
            int[] sweep = map.stepPath(nd);
            BitSet set = new BitSet(on.length);
            for (int q = 0; q + 1 < sweep.length; q += 2) {
                int mx = x + sweep[q], my = y + sweep[q + 1];
                if (mx < 0 || my < 0 || mx >= w || my >= map.height() || !map.alive(mx, my, nd)) continue;
                int s = map.index(mx, my, nd);
                if (slot[s] >= 0) set.set(slot[s]);
            }
            inS[1] = set;
        }
        for (int k = 2; k <= steps; k++) {
            BitSet set = new BitSet(on.length);
            for (int i = inS[k - 1].nextSetBit(0); i >= 0; i = inS[k - 1].nextSetBit(i + 1)) {
                int s = on[i];
                int d = s % turns, cell = s / turns, x = cell % w, y = cell / w;
                if (map.constrainTurn(x, y, d, turn[k]) != turn[k]) altered++;
                int u = map.successor(s, turn[k]);
                if (u >= 0 && slot[u] >= 0) set.set(slot[u]);
            }
            inS[k] = set;
        }

        // E<S_k: what can reach S_k without leaving E, S_k itself excluded.
        BitSet[] before = new BitSet[steps + 1];
        int[] out = new int[3];
        for (int k = 1; k <= steps; k++) {
            BitSet b = new BitSet(on.length);
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            for (int i = inS[k].nextSetBit(0); i >= 0; i = inS[k].nextSetBit(i + 1)) queue.add(on[i]);
            while (!queue.isEmpty()) {
                int s = queue.poll();
                int c = map.steeredPredecessors(s, out);
                for (int j = 0; j < c; j++) {
                    int p = out[j];
                    if (slot[p] < 0 || inS[k].get(slot[p]) || b.get(slot[p])) continue;
                    b.set(slot[p]);
                    queue.add(p);
                }
            }
            before[k] = b;
        }
        final BitSet first = before[1], last = before[steps];

        // Taking the subpath: from E<S_k, only E<S_k or S_k.
        int[] keys = new int[on.length * 3];
        int n = 0;
        int stuck = 0;
        for (int i = 0; i < on.length; i++) {
            int s = on[i];
            int d = s % turns, cell = s / turns, x = cell % w, y = cell / w;
            boolean member = false, escape = false;
            for (int t = -1; t <= 1; t++) {
                if (map.constrainTurn(x, y, d, t) != t) continue;
                int u = map.successor(s, t);
                if (u < 0) continue;
                boolean allowed = true;
                for (int k = 1; k <= steps && allowed; k++) {
                    if (!before[k].get(i)) continue;
                    member = true;
                    allowed = slot[u] >= 0 && (before[k].get(slot[u]) || inS[k].get(slot[u]));
                }
                if (allowed) escape = true; else keys[n++] = Gate.key(s, t);
            }
            if (member && !escape) stuck++;
        }
        Gate take = Gate.of(Arrays.copyOf(keys, n));

        // The zone opens where E<S_1 can be left, forward-closed on E<S_1; closes on leaving E<S_N.
        boolean[] inZ1 = new boolean[edgeOf.length];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int i = first.nextSetBit(0); i >= 0; i = first.nextSetBit(i + 1)) {
            int s = on[i];
            int c = map.steeredSuccessors(s, out);
            for (int j = 0; j < c; j++) {
                int u = out[j];
                if (slot[u] < 0 || !first.get(slot[u])) { inZ1[s] = true; queue.add(s); break; }
            }
        }
        while (!queue.isEmpty()) {
            int s = queue.poll();
            int c = map.steeredSuccessors(s, out);
            for (int j = 0; j < c; j++) {
                int u = out[j];
                if (slot[u] >= 0 && first.get(slot[u]) && !inZ1[u]) { inZ1[u] = true; queue.add(u); }
            }
        }
        int[] notYet = Arrays.stream(on).filter(s -> first.get(slot[s]) && !inZ1[s]).toArray();
        Gate opens = Gate.between(map, notYet, u -> inZ1[u]);
        int[] lastStates = Arrays.stream(on).filter(s -> last.get(slot[s])).toArray();
        Gate closes = Gate.between(map, lastStates, u -> slot[u] < 0 || !last.get(slot[u]));
        int[] region = Arrays.stream(on)
                .filter(s -> inZ1[s] || (last.get(slot[s]) && !first.get(slot[s]))).toArray();

        // Saturation at the entrance and the exit, the chain between, and the gate itself.
        int[] entrances = entrances(map, edgeOf, on, e);
        int missingFirst = 0;
        for (int s : entrances) if (!first.get(slot[s]) && !inS[1].get(slot[s])) missingFirst++;
        boolean[] fromLast = new boolean[edgeOf.length];
        for (int i = inS[steps].nextSetBit(0); i >= 0; i = inS[steps].nextSetBit(i + 1)) {
            fromLast[on[i]] = true;
            queue.add(on[i]);
        }
        closeForward(map, edgeOf, e, fromLast, queue);
        Gate allExits = Gate.between(map, on, u -> edgeOf[u] != e);
        Gate fromLastExits = Gate.between(map, Arrays.stream(on).filter(s -> fromLast[s]).toArray(),
                u -> edgeOf[u] != e);
        int missingLast = allExits.size() - fromLastExits.size();
        long allEdges = 0, lastEdges = 0;
        for (int key : allExits.keys()) allEdges |= 1L << edgeOf[map.successor(Gate.stateOf(key), Gate.turnOf(key))];
        for (int key : fromLastExits.keys()) lastEdges |= 1L << edgeOf[map.successor(Gate.stateOf(key), Gate.turnOf(key))];
        int missingEdges = Long.bitCount(allEdges) - Long.bitCount(lastEdges);
        int breaks = 0;
        for (int k = 1; k < steps; k++) {
            for (int i = inS[k].nextSetBit(0); i >= 0; i = inS[k].nextSetBit(i + 1)) {
                if (!before[k + 1].get(i) && !inS[k + 1].get(i)) breaks++;
            }
        }
        int entrancesInside = 0;
        for (int s : entrances) if (Arrays.binarySearch(region, s) >= 0) entrancesInside++;
        int bypassing = unreaching(map, edgeOf, e, Arrays.stream(on).filter(s -> first.get(slot[s])).toArray(),
                Arrays.stream(on).filter(s -> inZ1[s]).toArray(), s -> first.get(slot[s]));
        int sizeS = 0;
        for (int k = 1; k <= steps; k++) sizeS += inS[k].cardinality();

        Map<String, Integer> measured = new LinkedHashMap<>();
        measured.put("steps", steps);
        measured.put("|S|", sizeS);
        measured.put("E<S_1", first.cardinality());
        measured.put("E<S_N", last.cardinality());
        measured.put("entrances", entrances.length);
        measured.put("missing S_1", missingFirst);
        measured.put("exit edges missing", missingEdges);
        measured.put("exit transitions missing", missingLast);
        measured.put("chain breaks", breaks);
        measured.put("veto-altered", altered);
        measured.put("stuck", stuck);
        measured.put("inside", entrancesInside);
        measured.put("bypassing", bypassing);
        List<String> failures = new ArrayList<>();
        if (missingFirst > 0) failures.add("entrances missing S_1");
        if (missingEdges > 0) failures.add("exit edges missing S_N");
        if (breaks > 0) failures.add("chain breaks");
        if (stuck > 0) failures.add("stuck");
        if (entrancesInside > 0) failures.add("entrances inside");
        if (bypassing > 0) failures.add("bypassing");
        // The samples by state, lowest k first where a landing is also the next step's origin.
        int[] sampleStates = new int[sizeS], sampleStep = new int[sizeS];
        int m = 0;
        for (int k = steps; k >= 1; k--) {
            for (int i = inS[k].nextSetBit(0); i >= 0; i = inS[k].nextSetBit(i + 1)) {
                sampleStates[m] = on[i];
                sampleStep[m++] = k;
            }
        }
        Integer[] order = new Integer[m];
        for (int i = 0; i < m; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> sampleStates[a] != sampleStates[b]
                ? Integer.compare(sampleStates[a], sampleStates[b])
                : Integer.compare(sampleStep[a], sampleStep[b]));
        int[] sortedStates = new int[m], sortedStep = new int[m];
        for (int i = 0; i < m; i++) { sortedStates[i] = sampleStates[order[i]]; sortedStep[i] = sampleStep[order[i]]; }
        return new DecisionZone(e, "subpath", region, opens, closes, new int[]{SKIP, TAKE},
                new Gate[]{Gate.EMPTY, take}, measured, failures, sortedStates, sortedStep);
    }

    /** The same, from a solver's facts. */
    public static DecisionZone subpath(NavMap map, SolverFacts f, int e, int[] path) {
        Kernel k = Kernel.of(f);
        return subpath(map, k.edgeOf(), k.live(), k.liveCount(), e, path);
    }

    // ---------------------------------------------------------------- helpers

    private static int[] statesOf(int[] edgeOf, int[] live, int liveCount, int e) {
        int[] on = new int[liveCount];
        int count = 0;
        for (int i = 0; i < liveCount; i++) if (edgeOf[live[i]] == e) on[count++] = live[i];
        return Arrays.copyOf(on, count);
    }

    /** Grows {@code in} forward along transitions that stay on {@code e}, from what is queued. */
    private static void closeForward(NavMap map, int[] edgeOf, int e, boolean[] in,
                                     ArrayDeque<Integer> queue) {
        int[] out = new int[3];
        while (!queue.isEmpty()) {
            int s = queue.poll();
            int k = map.steeredSuccessors(s, out);
            for (int j = 0; j < k; j++) {
                int u = out[j];
                if (edgeOf[u] == e && !in[u]) { in[u] = true; queue.add(u); }
            }
        }
    }

    /** On-edge states with an off-edge predecessor. */
    private static int[] entrances(NavMap map, int[] edgeOf, int[] on, int e) {
        int[] out = new int[3];
        List<Integer> found = new ArrayList<>();
        for (int s : on) {
            int k = map.steeredPredecessors(s, out);
            for (int j = 0; j < k; j++) {
                if (edgeOf[out[j]] != e) { found.add(s); break; }
            }
        }
        return found.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * How many of {@code from} cannot reach {@code target} by transitions staying on edge
     * {@code e} and within {@code within}.
     */
    private static int unreaching(NavMap map, int[] edgeOf, int e, int[] from, int[] target,
                                  java.util.function.IntPredicate within) {
        boolean[] reaches = new boolean[edgeOf.length];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int s : target) { reaches[s] = true; queue.add(s); }
        int[] out = new int[3];
        while (!queue.isEmpty()) {
            int s = queue.poll();
            int k = map.steeredPredecessors(s, out);
            for (int j = 0; j < k; j++) {
                int p = out[j];
                if (edgeOf[p] == e && within.test(p) && !reaches[p]) { reaches[p] = true; queue.add(p); }
            }
        }
        int missing = 0;
        for (int s : from) if (!reaches[s]) missing++;
        return missing;
    }

    @Override
    public String toString() {
        return "DecisionZone[" + kind + " on edge " + edge + ", region " + region.length
                + ", options " + Arrays.toString(options) + "]";
    }
}
