package boids;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A receding-horizon search over psyboid overrides.
 * <p>
 * At each decision point the psyboid faces {@code branches[0]} candidate overrides.
 * Each of those is explored {@code branches[1]} ways one split later, and so on, until
 * a level where the branch factor is 1: there no override is created, the timeline is
 * simply run on for {@code lookahead} ticks and scored. The best leaf's value flows
 * back up, the winning first move is committed, and the whole tree shifts down a level
 * — the subtree under the winner is kept rather than rebuilt.
 * <p>
 * Everything that builds the tree is idempotent. A node is told how many children it
 * ought to have, not how many to add, so a node preserved across a commit simply tops
 * itself up when the shallower depth calls for a wider fan-out.
 * <p>
 * Splits are spaced {@code maxDelay + duration} apart, which is exactly the longest an
 * override can occupy. Consecutive overrides therefore tile the timeline without ever
 * overlapping, and the psyboid is under near-continuous control.
 */
public final class PsyboidSearch {

    /**
     * Ceiling on {@link Config#budget()} — a measure of state calculations rather than
     * of concurrency, so shapes of different depths are compared at equal cost.
     */
    public static final int MAX_BUDGET = 4096;

    /** Ticks per "second": the time to swing an eighth of a turn. */
    public static final int SECOND = Params.TURNS / 8;

    /**
     * @param branches   non-increasing and positive; every entry is a real split, and
     *                   the lookahead begins after the last one whatever its value
     * @param lookahead  ticks run past the final split before scoring
     * @param alpha      per-second discount applied across the lookahead, so the score
     *                   earned in its n-th second counts for {@code alpha^n}
     */
    public record Config(int[] branches, int lookahead, double alpha,
                         int maxDelay, int duration, int segments, int psyboid,
                         OverridePlan plan) {

        /** A sampled search: overrides are drawn at random from the ranges given. */
        public Config(int[] branches, int lookahead, double alpha,
                      int maxDelay, int duration, int segments, int psyboid) {
            this(branches, lookahead, alpha, maxDelay, duration, segments, psyboid, null);
        }

        /** Steers {@link Sim#PSYBOID} unless told otherwise. */
        public Config(int[] branches, int lookahead, double alpha,
                      int maxDelay, int duration, int segments) {
            this(branches, lookahead, alpha, maxDelay, duration, segments, Sim.PSYBOID);
        }

        /**
         * An enumerated search: every branch is a distinct choice from {@code plan}, so
         * the tree is exhaustive at each level rather than a sample of it.
         */
        public Config(int depth, int lookahead, double alpha, int psyboid, OverridePlan plan) {
            this(filled(depth, plan.size()), lookahead, alpha,
                    plan.interval(), plan.durationHi(), 1, psyboid, plan);
        }

        private static int[] filled(int depth, int width) {
            int[] out = new int[depth];
            java.util.Arrays.fill(out, width);
            return out;
        }

        public Config {
            if (branches.length == 0) throw new IllegalArgumentException("branches is empty");
            for (int i = 0; i < branches.length; i++) {
                if (branches[i] < 1) throw new IllegalArgumentException("branches must be positive");
                if (i > 0 && branches[i] > branches[i - 1]) {
                    throw new IllegalArgumentException("branches must be non-increasing");
                }
                // An enumerated level narrower than the plan would silently drop choices,
                // and one wider would ask for branches that do not exist.
                if (plan != null && branches[i] != plan.size()) {
                    throw new IllegalArgumentException("enumerated branches must all equal "
                            + plan.size() + ", was " + branches[i]);
                }
            }
            if (lookahead < 0) throw new IllegalArgumentException("lookahead must not be negative");
        }

        /**
         * Nodes built per decision, weighting the deepest level by the extra ticks its
         * lookahead costs. Proportional to the state calculations a commit performs, so
         * a wide shallow tree and a narrow deep one can be given the same allowance.
         * <p>
         * {@code b0 + b0*b1 + ... + b0*..*bn * k}, with {@code k} the lookahead's cost
         * relative to one branching interval.
         */
        public double budget() {
            double total = 0;
            double partial = 1;
            for (int i = 0; i < branches.length; i++) {
                partial *= branches[i];
                total += (i == branches.length - 1) ? partial * lookaheadFactor() : partial;
            }
            return total;
        }

        /** How many branching intervals' worth of work one leaf's lookahead adds. */
        public double lookaheadFactor() {
            return (lookahead + splitSpacing()) / (double) splitSpacing();
        }

        /**
         * Ticks between consecutive commits.
         * <p>
         * For a sampled search this is the span an override can occupy, delay plus
         * duration. An enumerated plan instead sets the interval directly and lets
         * overrides run past it, so that no tick falls outside every commit's reach.
         */
        public int splitSpacing() {
            return plan != null ? plan.interval() : maxDelay + duration;
        }

        public String describe() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < branches.length; i++) {
                if (i > 0) sb.append('x');
                sb.append(branches[i]);
            }
            return sb.toString();
        }
    }

    /**
     * One timeline in the tree. Its state sits at that level's split tick; the lookahead
     * used to score it as a leaf is speculative and never becomes the node's own state.
     */
    private static final class Node {
        final Sim.State state;
        /** The overrides that produced this node from its parent; null at the root. */
        final PsyboidOverride[] overrides;
        final List<Node> children = new ArrayList<>();

        /** Cached leaf value. Depends only on the state, so it survives a re-rooting. */
        double leafValue = Double.NaN;

        Node(Sim.State state, PsyboidOverride[] overrides) {
            this.state = state;
            this.overrides = overrides;
        }
    }

    private final Engine engine;
    private final Config config;
    private final Random rng;

    private Node root;

    /**
     * The canonical line's provenance, extended at each commit.
     * <p>
     * Held here rather than written into every node's state: only the committed branch
     * ever needs it, and stamping it onto each of the thousands of speculative children
     * would allocate a string per node per commit for nothing.
     */
    private String label;

    /** This search's row in the journal; its label is kept current on every commit. */
    private final SearchJournal.Entry journal;

    /**
     * Registers with {@link SearchJournal} on construction, so a search cannot run without
     * its canonical line being recorded. There is no opt-in to forget and no call site to
     * miss: replaying a saved label costs milliseconds, re-running a search costs minutes.
     */
    public PsyboidSearch(Engine engine, Config config, Sim.State start, long seed) {
        this.engine = engine;
        this.config = config;
        this.rng = new Random(seed);
        this.root = new Node(start, null);
        this.label = start.label;
        this.journal = SearchJournal.open(config, seed, start.label);
    }

    /**
     * The committed timeline, carrying a label that records the seed it started from and
     * every override committed since — enough to reconstruct it exactly, given the
     * scenario. See {@link #replay}.
     */
    public Sim.State canonical() {
        Sim.State s = root.state;
        return new Sim.State(s.n, s.x, s.y, s.h, s.tick, s.score, s.boidScore,
                label, s.psyboidOverrides);
    }

    public String canonicalLabel() { return label; }

    /** Segments of one commit, joined; commits are separated by {@code |} in the label. */
    private static String describe(PsyboidOverride[] overrides) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < overrides.length; i++) {
            if (i > 0) sb.append(';');
            sb.append(overrides[i].label());
        }
        return sb.toString();
    }

    /**
     * Rebuilds a canonical line from its label alone.
     * <p>
     * The seed fixes the starting positions and headings, the warm-up is deterministic,
     * and the overrides are the only other randomness — so the label is a complete
     * record. Every override is installed at once rather than a commit at a time, which
     * is equivalent because each one is gated on its own tick window and consecutive
     * windows never overlap.
     */
    public static Sim.State replay(Engine engine, String label, int warmup, long targetTick) {
        Sim.State s = engine.init(seedOf(label));
        while (s.tick < warmup) s = engine.tick(s);

        s = new Sim.State(s.n, s.x, s.y, s.h, s.tick, 0L, new long[s.n], label,
                overridesOf(label));
        while (s.tick < targetTick) s = engine.tick(s);
        return s;
    }

    /** The seed a labelled line started from. */
    public static long seedOf(String label) {
        int end = label.indexOf('|');
        String head = end < 0 ? label : label.substring(0, end);
        return Long.parseLong(head.substring("seed".length()));
    }

    /** Every override a labelled line committed, in order. */
    public static PsyboidOverride[] overridesOf(String label) {
        String[] parts = label.split("\\|");
        List<PsyboidOverride> overrides = new ArrayList<>();
        for (int i = 1; i < parts.length; i++) {
            for (String segment : parts[i].split(";")) {
                overrides.add(PsyboidOverride.parse(segment));
            }
        }
        return overrides.toArray(new PsyboidOverride[0]);
    }

    /**
     * Builds or tops up the tree, commits the best first move, and re-roots on it.
     * The discarded siblings take their subtrees with them.
     */
    public void commit() {
        evaluate(root, 0);

        Node best = null;
        double bestValue = Double.NEGATIVE_INFINITY;
        for (Node child : root.children) {
            double value = evaluate(child, 1);
            if (value > bestValue) {
                bestValue = value;
                best = child;
            }
        }
        label = label + "|" + describe(best.overrides);
        root = best;
        journal.record(label);
    }

    /**
     * The best value reachable from this node, building whatever the tree is missing on
     * the way down.
     */
    private double evaluate(Node node, int depth) {
        // The lookahead begins past the last branching level, whatever that level's
        // width. A trailing 1 is therefore an ordinary split that happens to offer no
        // choice, extending the planned horizon rather than terminating it.
        if (depth == config.branches.length) {
            if (Double.isNaN(node.leafValue)) {
                node.leafValue = node.state.score + discountedLookahead(node.state);
            }
            return node.leafValue;
        }

        ensureChildren(node, config.branches[depth]);

        double best = Double.NEGATIVE_INFINITY;
        for (Node child : node.children) {
            best = Math.max(best, evaluate(child, depth + 1));
        }
        return best;
    }

    /**
     * Tops the node up to at least {@code wanted} children. Safe to call repeatedly.
     * <p>
     * Under an enumerated plan the children are the plan's branches in its own fixed
     * order, so topping up resumes exactly where it left off and a node built across two
     * calls is identical to one built in a single call.
     */
    private void ensureChildren(Node node, int wanted) {
        PsyboidOverride[][] enumerated = config.plan() == null ? null
                : config.plan().branches((int) node.state.tick, config.psyboid());

        while (node.children.size() < wanted) {
            PsyboidOverride[] overrides = enumerated != null
                    ? enumerated[node.children.size()]
                    : Sim.segmentedOverride((int) node.state.tick, config.maxDelay,
                            config.duration, config.segments, config.psyboid(), rng);

            Sim.State branched = new Sim.State(node.state.n, node.state.x, node.state.y,
                    node.state.h, node.state.tick, node.state.score, node.state.boidScore,
                    node.state.label, overrides);

            node.children.add(new Node(advance(branched, config.splitSpacing()), overrides));
        }
    }

    /**
     * Score over the lookahead, with each second worth {@code alpha} times the one
     * before it. The taper is what keeps a distant, speculative gain from outweighing a
     * near-certain one.
     */
    private double discountedLookahead(Sim.State from) {
        Sim.State s = from;
        long previous = s.score;
        double weight = config.alpha;
        double total = 0;

        // Whole seconds where the lookahead allows, then whatever is left. A lookahead
        // that is not a round number of seconds is legitimate — short overrides want a
        // short horizon — and the alternative was silently dropping the remainder.
        for (int elapsed = 0; elapsed < config.lookahead; elapsed += SECOND) {
            s = advance(s, Math.min(SECOND, config.lookahead - elapsed));
            total += weight * (s.score - previous);
            previous = s.score;
            weight *= config.alpha;
        }
        return total;
    }

    private Sim.State advance(Sim.State s, int ticks) {
        for (int i = 0; i < ticks; i++) s = engine.tick(s);
        return s;
    }
}
