package boids;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A psyboid search over the only choice a dab-like map actually offers.
 * <p>
 * The earlier search enumerated overrides — start times, durations, directions — and spent its
 * budget discovering by trial that most of them do nothing. With the edges worked out that is
 * no longer necessary. A boid on this map has exactly two moments per lap where its future is
 * not already settled: near the end of edge 4 it can hold straight for edge 2 or turn off for
 * edge 0, and near the end of edge 2 it can hold straight for edge 7 or turn off for edge 1.
 * Everywhere else it has no choice worth the name. <b>So a psyboid's whole plan is a string of
 * bits, one per visit to a branching edge.</b>
 * <p>
 * Both turns are to the <b>right</b>, and that is measured rather than assumed: over the 216
 * critical states of edge 4 and the 323 of edge 2, holding left reaches the branch exit from
 * none of them at all.
 * <p>
 * <b>A bit becomes an ordinary override.</b> Nothing here needs a new kind of control. At the
 * moment the psyboid enters a branching edge its coasting path is followed forward to the first
 * <em>critical</em> state — the last tick from which holding straight still commits it the wrong
 * way — and a plain {@code (start, duration, right)} override is written to begin there. The
 * duration is the longest hold any critical state on that edge needs rather than the one this
 * state needs, so the turn still lands if flocking has pushed the boid a few ticks off the
 * coasting prediction.
 * <p>
 * <b>What is searched.</b> A receding horizon. From the root the timeline runs forward, forking
 * at every branch-edge entry within {@code spread} ticks, so the tree has one level per decision
 * the psyboid can reach in that window. Every leaf is then run on {@code lookahead} ticks with
 * no further overrides and scored with a per-second discount, so a line that scores now is
 * preferred to one that promises to score later. The root's child holding the best leaf is
 * committed and becomes the new root; its sibling and everything under it is dropped.
 */
public final class PsyboidBits {

    /** Ticks per "second": the time to swing an eighth of a turn. */
    public static final int SECOND = Params.TURNS / 8;

    /**
     * Ticks of slack on each end of a turn request.
     * <p>
     * Where the turn has to begin is predicted by following the boid's <em>coasting</em> path to
     * the last state a straight tick would commit it the wrong way. The boid is not coasting —
     * it is flocking — so it arrives a little early or a little late, and a request aimed at one
     * exact tick misses. Asking for the turn eight ticks sooner and holding it eight ticks
     * longer costs nothing: before the critical window a right turn keeps the boid on the same
     * edge, and after the exit it is committed regardless.
     */
    private static final int MARGIN = 8;

    /**
     * @param warm      ticks of ordinary flight before the psyboid is switched on, and the tick
     *                  the average score is measured from
     * @param spread    how far past the root a new override may start. The compute knob: the
     *                  tree has one level per branch-edge entry inside this window
     * @param lookahead ticks run past the last fork before a leaf is scored
     * @param alpha     per-second discount across the lookahead
     * @param run       how long the psyboid is kept under control, in ticks past the warmup.
     *                  Decisions keep being committed for as long as this asks; there is no
     *                  budget of them, because a psyboid that stops steering part way through
     *                  is a case with a silent boundary in the middle of it
     * @param settle    ticks after the psyboid becomes active before a scene may be sampled.
     *                  The flock has to absorb the first few turns before an arrangement says
     *                  anything about them
     * @param psyboid   which boid is overridden
     */
    public record Config(int warm, int spread, int lookahead, double alpha, int run,
                         int settle, int psyboid) {

        public static Config of(int warm, int spread, int lookahead, double alpha, int run,
                                int settle) {
            return new Config(warm, spread, lookahead, alpha, run, settle, Sim.PSYBOID);
        }
    }

    /**
     * A finished plan, and enough to replay it exactly.
     *
     * @param label     seed, psyboid and every committed override, in the format
     *                  {@link PsyboidOverride#parse} reads back
     * @param perTick    score per tick over the usable window
     * @param control    the same, flying the same seed with no psyboid at all
     * @param usableFrom first tick a scene may be sampled from: far enough past the psyboid
     *                   becoming active that the flock has absorbed it
     * @param usableTo   last such tick: the end of the last committed override. Beyond it the
     *                   boid is coasting, and the root never passed it either
     */
    public record Plan(long seed, int psyboid, PsyboidOverride[] overrides, String label,
                       double perTick, double control, long usableFrom, long usableTo,
                       Scored steered, Scored unsteered) {

        /** How much the psyboid was worth, as a multiple of what the flock does on its own. */
        public double lift() { return control <= 0 ? Double.NaN : perTick / control; }

        /** Ticks a case could be drawn from. Zero means the plan is unusable. */
        public long usable() { return Math.max(0, usableTo - usableFrom); }
    }

    /**
     * Everything about a map that says where the choices are.
     * <p>
     * Derived once from the navmap and the edge labelling, since none of it depends on the
     * flock. {@code branch} names the edges with a decision on them; for each state of one,
     * {@code coast} says how many ticks of coasting remain before the last chance to turn.
     */
    public record Branches(int[] branch, int[] exit, int[] hold, int[] coast) {

        /** Whether a boid arriving here has a decision to make. */
        public int branchIndex(int edge) {
            for (int i = 0; i < branch.length; i++) if (branch[i] == edge) return i;
            return -1;
        }
    }

    private PsyboidBits() {}

    /**
     * Where the choices are, and what taking one costs in ticks.
     * <p>
     * A state is critical when one unsteered tick would commit the boid to an exit that is
     * neither the branch it might take nor the edge it is on. Coasting from anywhere on the edge
     * reaches one, so every visit is a real decision.
     */
    public static Branches branches(NavMap map, SolverFacts f) {
        List<Integer> from = new ArrayList<>(), keep = new ArrayList<>();
        for (int a = 0; a < f.edges(); a++) {
            for (int b = 0; b < f.edges(); b++) {
                if (a == b || f.straightTo()[a] == b) continue;
                if ((f.arcs()[a] & (1L << b)) != 0) { from.add(a); keep.add(b); }
            }
        }
        int n = from.size();
        int[] branch = new int[n], exit = new int[n], hold = new int[n];
        int[] coast = new int[f.edgeOf().length];
        Arrays.fill(coast, -1);
        for (int i = 0; i < n; i++) {
            branch[i] = from.get(i);
            exit[i] = keep.get(i);
        }

        int turns = Params.TURNS, w = map.width();
        for (int s = 0; s < coast.length; s++) {
            int e = f.edgeOf()[s];
            if (e < 0) continue;
            int i = -1;
            for (int k = 0; k < n; k++) if (branch[k] == e) i = k;
            if (i < 0) continue;
            int d = s % turns, cell = s / turns, x = cell % w, y = cell / w;
            if (!map.alive(x, y, d)) continue;
            coast[s] = ticksToCritical(map, f, s, branch[i], exit[i]);
            int u = map.successor(s, 0);
            if (u >= 0 && coast[s] == 0) {
                hold[i] = Math.max(hold[i], rightHold(map, f, s, branch[i], exit[i]));
            }
        }
        // An arc no amount of holding right actually takes is not a choice. dabeone lists 5-6
        // as an arc because it happens during a cold start, but from no critical state of edge
        // 5 does turning right reach edge 6 — so forking there would spend a level of the tree
        // on a bit that cannot mean anything.
        int keptCount = 0;
        for (int i = 0; i < n; i++) if (hold[i] > 0) keptCount++;
        int[] kb = new int[keptCount], ke = new int[keptCount], kh = new int[keptCount];
        for (int i = 0, k = 0; i < n; i++) {
            if (hold[i] == 0) continue;
            kb[k] = branch[i];
            ke[k] = exit[i];
            kh[k] = hold[i];
            k++;
        }
        for (int s = 0; s < coast.length; s++) {
            int e = coast[s] < 0 ? -1 : f.edgeOf()[s];
            boolean kept = false;
            for (int x : kb) if (x == e) kept = true;
            if (!kept) coast[s] = -1;
        }
        return new Branches(kb, ke, kh, coast);
    }

    /** Ticks of coasting until the last state from which the branch can still be taken. */
    private static int ticksToCritical(NavMap map, SolverFacts f, int s, int from, int keep) {
        int at = s;
        for (int k = 0; k < 4096; k++) {
            int u = map.successor(at, 0);
            if (u < 0) return -1;
            int e = f.edgeOf()[u];
            if (e != from && e != keep) return k;
            if (e != from) return -1;                 // already gone by the branch exit
            at = u;
        }
        return -1;
    }

    /** Ticks of holding right before the branch exit is reached; 0 if it never is. */
    private static int rightHold(NavMap map, SolverFacts f, int s, int from, int keep) {
        int at = s;
        for (int k = 1; k <= Params.TURNS; k++) {
            int u = map.successor(at, 1);
            if (u < 0) return 0;
            int e = f.edgeOf()[u];
            if (e == keep) return k;
            if (e != from) return 0;
            at = u;
        }
        return 0;
    }

    /** One decision: the psyboid has just arrived on a branching edge. */
    private record Fork(Sim.State before, PsyboidOverride taken) {}

    /**
     * Searches one seed and returns the plan it committed to.
     * <p>
     * Sequential rather than concurrent, and deliberately so: the whole point of searching bits
     * instead of override parameters is that the tree is now small enough not to need it.
     * <p>
     * <b>Takes a {@link ScenarioParameter} rather than a preset</b> so the same search can be run
     * on a flock of one. That is the only sensible way to ask what a psyboid is worth by itself —
     * the same algorithm, the same map, the same seed, and nothing to herd — and the answer is
     * the floor a corpus's scoring rate should sit on. See {@code SimTest.scoringFloor}.
     */
    public static Plan search(ScenarioParameter preset, NavMap map, SolverFacts f,
                              Branches b, Config config, long seed) throws java.io.IOException {
        Boids2DEngine engine = new Boids2DEngine(preset);
        MovementLogic rules = new MovementLogic(preset.turningRadius());

        Sim.State root = engine.init(seed);
        for (int t = 0; t < config.warm(); t++) root = engine.tick(root);
        long began = root.tick;
        long baseScore = root.score;

        List<PsyboidOverride> committed = new ArrayList<>();
        // Committed for as long as the run asks, with no budget of decisions. A cap would end
        // the psyboid at an arbitrary tick and leave the rest of the timeline holding a boid
        // that used to be one, which is a boundary nothing downstream could see.
        long stopBy = began + config.run();
        while (root.tick < stopBy) {
            Fork best = choose(engine, f, b, config, root);
            if (best == null) {
                // No decision inside the window is not the end of the psyboid. Gaps between
                // branch-edge visits run to 545 ticks on dabeone, well past a 320-tick spread,
                // so a search that gave up here would abandon the boid to coast for the rest of
                // the run — and a plan that stops steering after seven hundred ticks loses to
                // its own control.
                Sim.State on = root;
                for (int t = 0; t < Math.max(1, config.spread()) && on.tick < stopBy; t++) {
                    on = engine.tick(on);
                }
                if (on.tick == root.tick) break;
                root = on;
                continue;
            }
            committed.add(best.taken());
            // With the override attached, not without it. `before` is the arrangement as the
            // psyboid arrived at the decision, which is deliberately the state the bit had not
            // yet been applied to — advancing that directly runs the boid on past a turn it was
            // just told to make, so every later decision would be planned against a timeline
            // where none of the earlier ones happened.
            root = SimTest.withOverrides(best.before(),
                    append(best.before().psyboidOverrides, best.taken()));
            // Commit means run past the decision so the next search starts after it, otherwise
            // the same fork is found again and the horizon never recedes.
            long until = best.taken().onset() + best.taken().duration() + 1;
            while (root.tick < until) root = engine.tick(root);
        }

        // What a replay may be sampled from. It opens once the flock has had time to absorb the
        // psyboid becoming active, and shuts at the last committed override — past that the boid
        // is coasting and the timeline says nothing about a psyboid, whatever the label claims.
        // A replay run longer than the plan does not extend it; the plan's length is fixed at
        // the moment it was searched.
        PsyboidOverride[] plan = committed.toArray(new PsyboidOverride[0]);
        long usableFrom = began + config.settle();
        long usableTo = began;
        for (PsyboidOverride o : plan) {
            usableTo = Math.max(usableTo, o.onset() + Math.max(1, o.duration()));
        }
        usableTo = Math.min(usableTo, root.tick);

        // The plan is only worth what it scores when flown straight through, so the number
        // reported is a replay rather than anything the search believed along the way, and it
        // is measured over exactly the window a case may be drawn from.
        Scored steered = replay(engine, map, rules, seed, config.warm(), usableFrom, usableTo,
                plan, config.psyboid());
        Scored unsteered = replay(engine, map, rules, seed, config.warm(), usableFrom, usableTo,
                new PsyboidOverride[0], config.psyboid());

        StringBuilder label = new StringBuilder("seed" + seed);
        for (PsyboidOverride o : plan) label.append('|').append(o.label());
        return new Plan(seed, config.psyboid(), plan, label.toString(), steered.flock(),
                unsteered.flock(), usableFrom, usableTo, steered, unsteered);
    }

    /**
     * The settings the corpus is generated under.
     * <p>
     * <b>The spread saturates at 640 and the saturation is structural.</b> Decisions arrive
     * about every 140 ticks, so 320 is a two-decision tree of five leaves and 640 is four or
     * five — roughly one circuit of the scoring branch. Past that the answers stop moving at
     * all: 640, 960, 1280 and 1920 all return 0.03275 per tick to five decimals while the cost
     * runs from three seconds to a hundred and five. Once the tree can see a whole lap, more
     * depth shows it the same lap again. Below it there is real signal — 320 gives 0.02671 —
     * so this is a floor rather than a free parameter.
     * <p>
     * Lookahead and discount are worth much less. Over 300 seeds a lookahead of 160 scores
     * 0.0288 against 0.0335 at 640 on a standard error of 0.0012, and alpha at 0.85, 0.90, 0.95
     * and 1.00 all land inside one standard error of each other. Neither justifies a knob.
     */
    public static Config standard(int warm, int run) {
        return Config.of(warm, 640, 320, 0.95, run, SETTLE);
    }

    /**
     * Ticks after the psyboid becomes active before a scene may be sampled from a replay.
     * <p>
     * The flock needs time to absorb the first turns, and more to the point the solver reads
     * position rather than motion — a boid one tick into a plan is indistinguishable from one
     * that was never touched.
     */
    public static final int SETTLE = 500;

    /**
     * How long a flock has to fly before its own scoring stops depending on when you started
     * watching.
     * <p>
     * <b>Re-measured 2026-09-06 under physics 3 over 2,000 seeds, and the figures this constant
     * was chosen on were wrong in the one way that mattered.</b> Percentage of seeds accruing any
     * score with no psyboid, over a 4,000-tick run beginning at each tick:
     * <pre>
     *   start        0     500   1,000   2,000   5,000  10,000
     *   scoring   98.8%   16.4%   8.5%    7.0%    4.8%    2.7%
     * </pre>
     * The superseded figures — 40 seeds under physics 2, quoted as 40/40, 18/40 at tick 500,
     * 10/40 at 2,000 and 5/40 at both 5,000 and 10,000 — read every level about 2.7x high, and
     * asserted a <em>floor</em> at 5/40: "seeds that score unsteered however long you wait, a
     * property of the map rather than of the warmup". <b>There is no floor.</b> The rate is still
     * falling at 10,000 and reaches 0.2% of 250-tick windows by tick 20,000. Five of forty was a
     * sample too small to see it still moving.
     * <p>
     * <b>So this criterion never terminates, and cannot choose a warm-up.</b> Longer is always
     * better on it, which is how the number reached 5,000; and what it is improving is the flock
     * settling into a formation whose members no longer push each other off the stable cycle,
     * which is exactly the homogenisation {@code CORPUS.md}'s minimality argument says not to
     * optimise for. <b>Edge-occupancy decay does terminate</b>, at tick 500 — see
     * {@link EdgeOccupancy} and {@code ROADMAP.md} §0f. This constant is unchanged pending that
     * decision, because it sits in the {@link CorpusPreset} fingerprint and moving it re-addresses
     * every corpus.
     * <p>
     * <b>Scoring and leaving the stable cycle are the same event.</b> Dabeone's stable edges
     * {2, 4, 7} hold no scoring state and its scoring edges {0, 1, 3, 5, 6, 8} are exactly the
     * rest, so a flock that stays on the cycle cannot score. The two counts differ by under a
     * point at every start above — excursions that left and returned without reaching a scoring
     * region — which is why edge occupancy can answer a question about score at all.
     */
    public static final int WARM = 5000;

    /** A plan read back off its label, which is all a corpus row stores. */
    public record Replay(long seed, PsyboidOverride[] overrides) {}

    /**
     * Reads a label back into something replayable.
     * <p>
     * The label is the artifact. Everything else in a corpus row — the score, the settings, the
     * date — is commentary that can be recomputed or discarded, but a row that cannot be
     * replayed exactly is worthless, so the format stays the one
     * {@link PsyboidOverride#parse} already round-trips.
     */
    public static Replay parse(String label) {
        String[] parts = label.split("\\|");
        if (parts.length == 0 || !parts[0].startsWith("seed")) {
            throw new IllegalArgumentException("not a plan label: " + label);
        }
        long seed = Long.parseLong(parts[0].substring(4));
        PsyboidOverride[] overrides = new PsyboidOverride[parts.length - 1];
        for (int i = 1; i < parts.length; i++) overrides[i - 1] = PsyboidOverride.parse(parts[i]);
        return new Replay(seed, overrides);
    }

    /**
     * The arrangement a label names, at one tick.
     * <p>
     * Deterministic and independent of anything the search did: the seed fixes the start, the
     * warmup is ordinary flight, and the overrides are absolute-tick, so this reproduces the
     * timeline exactly however it was originally found.
     */
    public static Sim.State replay(PresetScenarioParameter preset, String label, int warm,
                                   long atTick) throws java.io.IOException {
        Replay plan = parse(label);
        Boids2DEngine engine = new Boids2DEngine(preset);
        Sim.State s = engine.init(plan.seed());
        for (int t = 0; t < warm; t++) s = engine.tick(s);
        s = SimTest.withOverrides(s, plan.overrides());
        while (s.tick < atTick) s = engine.tick(s);
        return s;
    }

    /** Score per tick over the usable window alone, flying a fixed plan. */
    /**
     * What one replay was worth, in the units analysis actually wants.
     *
     * @param flock     flock score per tick over the window. One point is one boid in a scoring
     *                  zone for one tick, so dividing by the flock size gives an occupancy rate
     * @param psy       the psyboid's own share of that, which is already a rate because it is
     *                  one boid
     * @param impactful ticks on which the psyboid's turn <b>after the collision veto</b> differed
     *                  from what the flocking rules alone would have produced. The override is a
     *                  request, and a request the map refuses or that the flock would have obeyed
     *                  anyway costs nothing and changes nothing — so this, not the number of
     *                  overrides, is what a psyboid spends
     * @param n         flock size, carried so a rate can be taken without asking elsewhere
     */
    public record Scored(double flock, double psy, int impactful, int n) {

        /** Mean fraction of the flock in a scoring zone. */
        public double occupancy() { return n == 0 ? 0 : flock / n; }

        /** The same for the psyboid alone. */
        public double psyOccupancy() { return psy; }

        /** And for everyone else, which is what a psyboid is supposed to be moving. */
        public double othersOccupancy() { return n <= 1 ? 0 : (flock - psy) / (n - 1); }
    }

    /**
     * Flies a plan straight through and measures it.
     * <p>
     * <b>Measured on a replay rather than on anything the search believed.</b> A plan is only
     * worth what it scores when flown as written, over exactly the window a case may be drawn
     * from.
     */
    private static Scored replay(Boids2DEngine engine, NavMap map, MovementLogic rules, long seed,
                                 int warm, long from, long to, PsyboidOverride[] plan,
                                 int psyboid) {
        Sim.State s = engine.init(seed);
        for (int t = 0; t < warm; t++) s = engine.tick(s);
        s = SimTest.withOverrides(s, plan);
        while (s.tick < from) s = engine.tick(s);
        long base = s.score, basePsy = s.boidScore[psyboid];
        int n = s.n;

        // Counted from inside the tick, because the comparison is between two turns the boid
        // could have taken from the same mid-tick arrangement — and a tick has an interior.
        int[] impactful = {0};
        long window = to;
        engine.trace((tick, i, boids, want) -> {
            if (i != psyboid || tick < from || tick >= window) return;
            int x = boids.x()[i], y = boids.y()[i], h = boids.h()[i];
            int steered = map.constrainTurn(x, y, h, want);
            int unsteered = map.constrainTurn(x, y, h, rules.decompose(boids, i).turn());
            if (steered != unsteered) impactful[0]++;
        });
        try {
            while (s.tick < to) s = engine.tick(s);
        } finally {
            engine.trace(null);
        }
        double ticks = Math.max(1, s.tick - from);
        return new Scored((s.score - base) / ticks, (s.boidScore[psyboid] - basePsy) / ticks,
                impactful[0], n);
    }

    /**
     * The best first decision reachable within the spread, by discounted lookahead.
     * <p>
     * Returns the state as it stood the moment the psyboid arrived at that first branching
     * edge, together with the override the winning line takes there — which is the whole of
     * what gets committed. A zero bit commits nothing, and is represented by an override of
     * zero duration so that a plan reads back as a complete record of every decision rather
     * than only of the ones that did something.
     */
    private static Fork choose(Boids2DEngine engine, SolverFacts f, Branches b, Config config,
                               Sim.State root) {
        Best best = walk(engine, f, b, config, root, root.tick + config.spread(), null);
        return best == null ? null : best.fork();
    }

    /** The best leaf under one subtree, carrying up the first decision that leads to it. */
    private record Best(Fork fork, double value) {}

    /**
     * Runs the timeline forward, forking wherever the psyboid arrives somewhere it has a choice,
     * and returns the best leaf found below.
     * <p>
     * {@code first} is the decision this subtree hangs off — null until the first fork, and
     * then carried down unchanged, so whichever leaf wins reports the move at the top of its
     * own line rather than the move nearest to it. That is what makes this a receding horizon
     * and not just a deep lookahead.
     */
    private static Best walk(Boids2DEngine engine, SolverFacts f, Branches b, Config config,
                             Sim.State at, long until, Fork first) {
        int p = config.psyboid();
        Sim.State s = at;
        int was = f.edgeAt(s.x[p], s.y[p], s.h[p]);
        while (s.tick < until) {
            Sim.State next = engine.tick(s);
            int now = f.edgeAt(next.x[p], next.y[p], next.h[p]);
            int i = now == was ? -1 : b.branchIndex(now);
            int lead = i < 0 ? -1 : b.coast()[f.state(next.x[p], next.y[p], next.h[p])];
            if (lead >= 0) {
                // One bit. Taking the branch is a right turn beginning at the last moment it
                // still works; leaving it is an override of no duration, written down so a plan
                // records every decision rather than only the ones that did something.
                // Declining comes first, and the winner is taken on a strict improvement, so a
                // tie goes to doing nothing. That matters more than it looks: a turn the boid
                // fails to make is indistinguishable in outcome from never asking, so the two
                // bits tie constantly. Ordered the other way the plan fills with requests the
                // search already knew were inert, and stops being a record of what it decided.
                PsyboidOverride[] bits = {
                        new PsyboidOverride((int) next.tick, 0, 0, p),
                        new PsyboidOverride((int) next.tick + lead - MARGIN,
                                b.hold()[i] + 2 * MARGIN, 1, p),
                };
                Best best = null;
                for (PsyboidOverride bit : bits) {
                    Sim.State forked = SimTest.withOverrides(next,
                            append(next.psyboidOverrides, bit));
                    Best under = walk(engine, f, b, config, forked, until,
                            first == null ? new Fork(next, bit) : first);
                    if (under != null && (best == null || under.value() > best.value())) {
                        best = under;
                    }
                }
                return best;
            }
            was = now;
            s = next;
        }
        // No decision anywhere in this window means there is nothing to commit and nothing to
        // compare; only a line that actually forked has a value worth reporting.
        return first == null ? null : new Best(first, value(engine, config, first, s));
    }

    /**
     * What a line is worth, counted from the decision that started it.
     * <p>
     * <b>From the fork, not from the leaf.</b> Scoring only the lookahead leaves everything
     * earned between the decision and the end of the spread window uncounted, and that blind
     * window grows with the spread — so a wider search would be judged on a more distant and
     * more weakly-connected stretch of the future, and would rank its own choices worse for it.
     * Since the objective is score per tick over the whole run, every tick after the decision
     * has to count.
     * <p>
     * Replayed from the fork with the line's overrides rather than accumulated during the walk,
     * because the walk shares its prefix between siblings and the discount does not: two leaves
     * under one fork must be weighted from the same instant.
     */
    private static double value(Boids2DEngine engine, Config config, Fork first, Sim.State leaf) {
        Sim.State s = SimTest.withOverrides(first.before(), leaf.psyboidOverrides);
        long was = s.score;
        double total = 0, weight = 1;
        int ticks = (int) (leaf.tick - first.before().tick) + config.lookahead();
        for (int t = 0; t < ticks; t++) {
            s = engine.tick(s);
            total += (s.score - was) * weight;
            was = s.score;
            if ((t + 1) % SECOND == 0) weight *= config.alpha();
        }
        return total;
    }

    private static PsyboidOverride[] append(PsyboidOverride[] have, PsyboidOverride add) {
        PsyboidOverride[] out = Arrays.copyOf(have, have.length + 1);
        out[have.length] = add;
        return out;
    }
}
