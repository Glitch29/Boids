package boids;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * The {@link StateSet} algebra for one map, and the sets that start a chain.
 * <p>
 * Everything here is bound to a {@link NavMap} and a {@link Flocking}, because a state set is
 * only meaningful against the physics that generated it. Construct once and keep it: it
 * precomputes the straight-successor table the whole algebra runs on.
 *
 * <h2>The map-wide stable set</h2>
 * <b>Stable</b> was previously only defined per edge — the closure under straight travel from
 * every way into the edge, with one partial tick, closed again. The map-wide version replaces
 * "from every way in" with the only thing a map offers instead:
 * <blockquote>
 * the states that straight travel returns to themselves, all their partial-tick straight
 * successors, closed under straight travel.
 * </blockquote>
 * which is {@code pureStable(1).partialTick(STRAIGHT).closed(STRAIGHT)}, and is everything a boid
 * alone can end up in, with the offset-lock correction the partial tick makes.
 *
 * <h2>Straight travel is a function, so its graph is rho-shaped</h2>
 * Every live state has exactly one straight successor, so following it from anywhere runs into a
 * cycle and stays there. {@link #pureStable} is the union of those cycles, and it therefore
 * <em>decomposes</em> into them — which {@link #cycles} recovers in order, so that advancing a
 * whole set of boids by one tick is an index shift rather than a table lookup each.
 */
public final class MapStates {

    private final NavMap map;
    private final Flocking flock;

    /** Straight successor per state index, {@code -1} for dead or unreachable. */
    private final int[] straight;

    private final int width;
    private final int cells;

    /** How long a single turn may be carried before it is abandoned as non-terminating. */
    private static final int TURN_CAP = 4 * Params.TURNS;

    private int capped;

    private MapStates(NavMap map, Flocking flock) {
        this.map = map;
        this.flock = flock;
        this.width = map.width();
        this.cells = map.width() * map.height() * Params.TURNS;
        this.straight = new int[cells];
        Arrays.fill(straight, -1);
    }

    /**
     * @param live      the viability kernel, as {@code SimTest.Labelling} carries it
     * @param liveCount how many of {@code live} are real
     */
    public static MapStates of(NavMap map, Flocking flock, int[] live, int liveCount) {
        MapStates m = new MapStates(map, flock);
        for (int i = 0; i < liveCount; i++) m.straight[live[i]] = map.successor(live[i], 0);
        return m;
    }

    /** How many turns hit {@link #TURN_CAP} instead of ending, over every expansion so far. */
    public int capped() { return capped; }

    // ---- the sets a chain starts from ---------------------------------------

    /**
     * States that straight travel brings back to themselves.
     * <p>
     * <b>{@code boids} is read as how many boids are in play</b>, so {@code pureStable(1)} is what
     * a boid alone can hold and is the only value defined. The spec that named it did not say,
     * and this is the reading that makes the rest of the sentence true — "all the states a boid
     * could end up in on a map by itself". If it was meant as something else, this is the line to
     * change.
     */
    public StateSet pureStable(int boids) {
        if (boids != 1) {
            throw new IllegalArgumentException("only pureStable(1) is defined; see the javadoc");
        }
        byte[] colour = new byte[cells];
        int[] at = new int[cells];
        Bits found = new Bits(cells);
        List<Integer> path = new ArrayList<>();

        for (int s = 0; s < cells; s++) {
            if (straight[s] < 0 || colour[s] != 0) continue;
            path.clear();
            int cur = s;
            while (cur >= 0 && colour[cur] == 0) {
                colour[cur] = 1;
                at[cur] = path.size();
                path.add(cur);
                cur = straight[cur];
            }
            // Landing on a state of the path being walked closes a cycle; landing on a finished
            // one means this tail runs into a cycle already recorded.
            if (cur >= 0 && colour[cur] == 1) {
                for (int i = at[cur]; i < path.size(); i++) found.set(path.get(i));
            }
            for (int p : path) colour[p] = 2;
        }
        return new Set(found);
    }

    /** A set of exactly these states. */
    public StateSet of(int... states) {
        Bits b = new Bits(cells);
        for (int s : states) if (s >= 0) b.set(s);
        return new Set(b);
    }

    public StateSet empty() { return new Set(new Bits(cells)); }

    /**
     * The straight-travel cycles inside a set, each in travel order.
     * <p>
     * Only meaningful for a set closed under straight travel; states with a successor outside the
     * set are skipped, so a set that merely contains a cycle yields that cycle and nothing else.
     */
    public List<int[]> cycles(StateSet set) {
        List<int[]> out = new ArrayList<>();
        Bits done = new Bits(cells);
        for (int s : set.toArray()) {
            if (done.get(s)) continue;
            List<Integer> loop = new ArrayList<>();
            int cur = s;
            while (cur >= 0 && set.contains(cur) && !done.get(cur)) {
                done.set(cur);
                loop.add(cur);
                cur = straight[cur];
            }
            // A genuine cycle comes back to where it started; anything else is a tail into one
            // that has already been recorded.
            if (cur == s) {
                int[] a = new int[loop.size()];
                for (int i = 0; i < a.length; i++) a[i] = loop.get(i);
                out.add(a);
            }
        }
        out.sort((a, b) -> Integer.compare(b.length, a.length));
        return out;
    }

    // ---- the set implementation ---------------------------------------------

    /** A bitset over state indices. Small: 136k states is 17 KB. */
    private static final class Bits {
        private final long[] words;
        private int count = -1;

        Bits(int cells) { words = new long[(cells + 63) >>> 6]; }

        Bits(Bits from) { words = from.words.clone(); }

        void set(int i) { words[i >>> 6] |= 1L << i; count = -1; }

        boolean get(int i) { return (words[i >>> 6] & (1L << i)) != 0; }

        void or(Bits other) {
            for (int i = 0; i < words.length; i++) words[i] |= other.words[i];
            count = -1;
        }

        void andNot(Bits other) {
            for (int i = 0; i < words.length; i++) words[i] &= ~other.words[i];
            count = -1;
        }

        int count() {
            if (count < 0) {
                int n = 0;
                for (long w : words) n += Long.bitCount(w);
                count = n;
            }
            return count;
        }

        int[] toArray() {
            int[] out = new int[count()];
            int n = 0;
            for (int w = 0; w < words.length; w++) {
                long bits = words[w];
                while (bits != 0) {
                    out[n++] = (w << 6) + Long.numberOfTrailingZeros(bits);
                    bits &= bits - 1;
                }
            }
            return out;
        }
    }

    private final class Set implements StateSet {
        private final Bits bits;

        Set(Bits bits) { this.bits = bits; }

        public int size() { return bits.count(); }

        public boolean contains(int state) { return state >= 0 && bits.get(state); }

        public int[] toArray() { return bits.toArray(); }

        public void forEach(IntConsumer each) { for (int s : toArray()) each.accept(s); }

        public StateSet union(StateSet other) {
            Bits b = new Bits(bits);
            for (int s : other.toArray()) b.set(s);
            return new Set(b);
        }

        public StateSet minus(StateSet other) {
            Bits b = new Bits(bits);
            Bits drop = new Bits(cells);
            for (int s : other.toArray()) drop.set(s);
            b.andNot(drop);
            return new Set(b);
        }

        public StateSet partialTick(Steering how) {
            Bits b = new Bits(bits);
            int turns = Params.TURNS;
            for (int s : toArray()) {
                int d = s % turns, cell = s / turns, x = cell % width, y = cell / width;
                int got = map.constrainTurn(x, y, d, how.turn());
                int nd = Math.floorMod(d + got, turns);
                int[] step = map.stepPath(nd);
                for (int q = 0; q + 1 < step.length; q += 2) {
                    int mx = x + step[q], my = y + step[q + 1];
                    if (mx < 0 || my < 0 || mx >= map.width() || my >= map.height()) continue;
                    if (!map.alive(mx, my, nd)) continue;
                    b.set(map.index(mx, my, nd));
                }
            }
            return new Set(b);
        }

        public StateSet closed(Steering how) {
            Bits b = new Bits(bits);
            ArrayList<Integer> queue = new ArrayList<>();
            for (int s : toArray()) queue.add(s);
            for (int i = 0; i < queue.size(); i++) {
                int u = how == Steering.STRAIGHT
                        ? straight[queue.get(i)] : map.successor(queue.get(i), how.turn());
                if (u < 0 || b.get(u)) continue;
                b.set(u);
                queue.add(u);
            }
            return new Set(b);
        }

        public StateSet expandByAgreement(StateSet influencers, int agreementRatio) {
            int[] inf = influencers.toArray();
            int quorum = inf.length / agreementRatio;
            Bits mid = new Bits(cells), end = new Bits(cells);
            int[] coalition = new int[inf.length];

            for (int b : toArray()) {
                for (int dir = -1; dir <= 1; dir += 2) {
                    int n = agreeing(inf, inf.length, b, dir, coalition);
                    if (n < quorum) continue;
                    int[] posse = Arrays.copyOf(coalition, n);
                    int leave = n / agreementRatio;
                    int at = b;
                    int step = 0;
                    for (; step < TURN_CAP; step++) {
                        for (int i = 0; i < posse.length; i++) {
                            posse[i] = posse[i] < 0 ? -1 : straight[posse[i]];
                        }
                        int next = map.successor(at, dir);
                        if (next < 0) break;
                        at = next;
                        mid.set(at);
                        int still = agreeing(posse, posse.length, at, dir, null);
                        if (n - still >= leave) end.set(at);
                        if (still <= leave) break;
                    }
                    if (step == TURN_CAP) capped++;
                }
            }
            StateSet withEnds = union(new Set(end)).closed(Steering.STRAIGHT);
            return withEnds.union(new Set(mid));
        }

        /**
         * How many of {@code from} ask the boid at {@code b} for {@code dir}, optionally
         * recording which.
         */
        private int agreeing(int[] from, int len, int b, int dir, int[] into) {
            int turns = Params.TURNS;
            int bd = b % turns, cell = b / turns, bx = cell % width, by = cell / width;
            int n = 0;
            for (int i = 0; i < len; i++) {
                int s = from[i];
                if (s < 0) continue;
                int lc = s / turns;
                int dx = lc % width - bx, dy = lc / width - by;
                if (EdgeInfluence.steer(bd, dx, dy, s % turns, flock) != dir) continue;
                if (into != null) into[n] = s;
                n++;
            }
            return n;
        }
    }

    /**
     * How many of {@code influencers} would turn a boid at {@code state} each way.
     * <p>
     * The number behind {@code agreementRatio}: a turn is admitted when this reaches
     * {@code |influencers| / ratio}, so seeing the raw counts along a path says what ratio the
     * path would need rather than leaving it to be found by sweeping.
     *
     * @return {@code {left, right, quorum-denominator}}, the last being the influencer count
     */
    public int[] agreement(StateSet influencers, int state) {
        int turns = Params.TURNS;
        int bd = state % turns, cell = state / turns, bx = cell % width, by = cell / width;
        int left = 0, right = 0;
        for (int s : influencers.toArray()) {
            int lc = s / turns;
            int got = EdgeInfluence.steer(bd, lc % width - bx, lc / width - by, s % turns, flock);
            if (got < 0) left++;
            else if (got > 0) right++;
        }
        return new int[]{left, right, influencers.size()};
    }

    // ---- reporting -----------------------------------------------------------

    /**
     * Which edges a set touches and how many of its states lie on each.
     * <p>
     * The question a stable+ candidate has to answer: it should be <em>bigger</em> than stable —
     * ordinary traffic does move boids off the stable set — while reaching <em>no new edges</em>,
     * because an edge that only becomes reachable once jostling is allowed is a route, not a
     * wobble, and it is exactly the thing the solver is supposed to find remarkable.
     */
    public static String byEdge(StateSet set, int[] edge, int edges) {
        int[] count = new int[edges + 1];
        for (int s : set.toArray()) count[edge[s] < 0 ? edges : edge[s]]++;
        StringBuilder out = new StringBuilder();
        for (int e = 0; e < edges; e++) {
            if (count[e] == 0) continue;
            out.append(out.isEmpty() ? "" : "  ").append(String.format("%d:%,d", e, count[e]));
        }
        if (count[edges] > 0) out.append(String.format("  unlabelled:%,d", count[edges]));
        return out.toString();
    }
}
