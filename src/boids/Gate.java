package boids;

import java.util.Arrays;
import java.util.function.IntPredicate;

/**
 * A transition-based gate: a set of {@code (state, turn)} pairs, fired when a boid takes one.
 * <p>
 * <b>Canonical statement in {@code EDGES.md} §2a.</b> A gate is a set of trigger conditions with
 * a guarantee attached — that a boid traversing the edge it belongs to fires it exactly once —
 * and the guarantee is a property of how the set was built, not of this class. This class is only
 * the set. Two representations exist; this is the transition-based one, chosen because a
 * state-based gate can be stepped over where a transition-based one cannot, and because the
 * prohibitions a decision installs (<em>do not take this exit</em>) are transitions and have no
 * state-based form. A state-based gate converts to this one by taking every transition that lands
 * in its states — {@link #landingIn}.
 *
 * <h2>Which turn a transition is keyed on</h2>
 * The <b>effective</b> turn: what the boid does after {@link NavMap#constrainTurn} has had its
 * say, not what was requested. A transition here is the same thing the decomposition calls one —
 * a permitted turn followed by a step — so a gate never holds a turn the veto would alter, and a
 * caller asking about a request must constrain it first. {@link DecisionOverride} does.
 *
 * <h2>Storage</h2>
 * Sorted {@code int} keys, {@code state * 3 + (turn + 1)}. A gate on these maps runs to a few
 * thousand transitions; a bitset over every transition on the map would be four megabytes each
 * and there are dozens.
 */
public final class Gate {

    private static final int[] NONE = new int[0];

    /** Sorted, distinct. */
    private final int[] keys;

    private Gate(int[] keys) { this.keys = keys; }

    public static final Gate EMPTY = new Gate(NONE);

    /** The key for {@code turn} taken from {@code state}; {@code turn} is -1, 0 or +1. */
    public static int key(int state, int turn) { return state * 3 + (turn + 1); }

    public static int stateOf(int key) { return key / 3; }

    public static int turnOf(int key) { return key % 3 - 1; }

    /** From keys in any order, duplicates allowed. */
    public static Gate of(int[] keys) {
        int[] k = keys.clone();
        Arrays.sort(k);
        int n = 0;
        for (int i = 0; i < k.length; i++) if (i == 0 || k[i] != k[i - 1]) k[n++] = k[i];
        return new Gate(Arrays.copyOf(k, n));
    }

    /** Whether taking {@code turn} — the effective turn — from {@code state} fires this gate. */
    public boolean crosses(int state, int turn) {
        return Arrays.binarySearch(keys, key(state, turn)) >= 0;
    }

    public int size() { return keys.length; }

    public boolean isEmpty() { return keys.length == 0; }

    /** The keys, ascending. */
    public int[] keys() { return keys.clone(); }

    public Gate union(Gate other) {
        if (other.keys.length == 0) return this;
        if (keys.length == 0) return other;
        int[] all = Arrays.copyOf(keys, keys.length + other.keys.length);
        System.arraycopy(other.keys, 0, all, keys.length, other.keys.length);
        return of(all);
    }

    /** The distinct source states, ascending — the state-based reading of a transition gate. */
    public int[] sources() {
        int[] out = new int[keys.length];
        int n = 0;
        for (int k : keys) {
            int s = stateOf(k);
            if (n == 0 || out[n - 1] != s) out[n++] = s;
        }
        return Arrays.copyOf(out, n);
    }

    /**
     * Every permitted transition from a state in {@code from} that lands in a state satisfying
     * {@code to}.
     * <p>
     * Permitted means what the decomposition means: the turn the veto returns unchanged. A turn
     * the veto would alter is not a transition of its own, only another request for one that is.
     */
    public static Gate between(NavMap map, int[] from, IntPredicate to) {
        int turns = Params.TURNS, w = map.width();
        int[] keys = new int[from.length * 3];
        int n = 0;
        for (int s : from) {
            int d = s % turns, cell = s / turns, x = cell % w, y = cell / w;
            for (int t = -1; t <= 1; t++) {
                if (map.constrainTurn(x, y, d, t) != t) continue;
                int u = map.successor(s, t);
                if (u >= 0 && to.test(u)) keys[n++] = key(s, t);
            }
        }
        return of(Arrays.copyOf(keys, n));
    }

    /**
     * The transition-based form of a state-based gate: every permitted transition, from any live
     * state, that lands in {@code region}.
     */
    public static Gate landingIn(NavMap map, int[] live, int liveCount, IntPredicate region) {
        return between(map, Arrays.copyOf(live, liveCount), region);
    }

    @Override
    public String toString() { return "Gate[" + keys.length + " transitions]"; }
}
