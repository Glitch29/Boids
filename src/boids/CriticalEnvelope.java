package boids;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Where a boid was, and where a second boid was, at the moment the first was steered onto a
 * course it cannot come back from.
 * <p>
 * This is the analysis every leader window is built from. It generates its own two-boid data
 * and is deliberately <b>not</b> driven by {@link TwoBoid}: that enumerates the arrangements a
 * run can actually reach, and using it as the leader domain would over-restrict where a leader
 * could have been coming into the turn. Any navigable state is admitted for the leader.
 * <p>
 * <b>The envelope is the unsteered-predecessor closure of the exit edge.</b> Not a band around
 * the coasting path, which is what an earlier version used and which had no principled width.
 * Two properties follow from the definition and are what make it the right object:
 * <ul>
 *   <li><b>It terminates.</b> Unsteered travel from the start of an edge never leaves that edge
 *       by a non-straight transition, so the closure cannot run back as far as the states the
 *       edge is entered by. {@link #envelope} asserts exactly that.</li>
 *   <li><b>It is complete.</b> Every boid that takes the exit was steered onto it. Nothing that
 *       exits can avoid passing through.</li>
 * </ul>
 * It also carries the states on the downstream edge that reverse-navigate to this one in a
 * single tick, because the first steered tick can land straight there when the cost to leave
 * happens to be exactly one.
 * <p>
 * <b>Entry onto the envelope is always a steered move.</b> If unsteered travel from {@code s}
 * lands in the envelope then unsteered travel from {@code s} reaches the exit, so {@code s} was
 * in the envelope already. That is why detection needs no geometry anywhere downstream — a
 * consumer watches envelope membership turn from false to true, and that tick is the decision.
 * <p>
 * Entering is <em>not</em> committing, and the distinction matters. A boid that has entered can
 * be steered the other way and leave again; it is hard on the maps in hand but always available
 * to a psyboid. The commit boundary is the edge boundary itself, which the edges were designed
 * to force, so an exit is reported where the boid crosses and attributed where it entered.
 *
 * @see ExitAudit for the consumer
 */
public final class CriticalEnvelope {
    private CriticalEnvelope() {}

    /** Bumped when the meaning of anything written changes, per the store convention. */
    public static final int FORMAT = 1;

    /**
     * Which influence turned the boid.
     * <p>
     * Alignment and cohesion are one cause rather than two. There is no clean split between
     * them — they act along different lines but arise together and routinely reinforce — so
     * separating them would invent a distinction the physics does not make.
     */
    public enum Cause { SEPARATION, ALIGNMENT_AND_COHESION }

    /**
     * The states a boid must pass through to take one particular exit.
     *
     * @param onFrom  envelope states on the edge being left, ascending
     * @param onKeep  states on the exit edge one unsteered tick in, for the case where the
     *                first steered tick lands there directly
     * @param entered states of {@code onFrom} an unsteered tick from the exit edge — the front
     *                of the envelope, kept for reporting rather than for the search
     */
    public record Envelope(int from, int keep, int[] onFrom, int[] onKeep, int[] entered) {
        public int size() { return onFrom.length + onKeep.length; }
    }

    /**
     * One `(boid, leader)` arrangement that puts a boid onto the envelope, with its account.
     *
     * @param boidPrior   where the boid was on the tick it was steered, before it moved
     * @param leaderPrior where the leader was as the boid decided
     * @param boidAt      the envelope state it landed on
     * @param turn        the turn the rules asked for, {@code -1} or {@code +1}
     * @param cause       which influence dominated, measured at this instant
     * @param leaderPath  the edges the leader occupied on the way here, in travel order,
     *                    minimal under the subpath relation
     * @param diluted     true when only the diluted model produced this entry turn, which is
     *                    what separates a fallback account from an ordinary one
     */
    public record Entry(int boidPrior, int leaderPrior, int boidAt, int turn, Cause cause,
                        int[] leaderPath, boolean diluted) {}

    /**
     * Everything one arc yields.
     *
     * @param settled how many states of the edge count as settled
     * @param probed  candidate pairs the admission search looked at, for cost reporting
     */
    public record Table(Envelope envelope, List<Entry> entries, int settled, long probed,
                        boolean budgetHit) {}

    // ---- the envelope -------------------------------------------------------

    /**
     * The unsteered-predecessor closure of {@code keep}, restricted to {@code from}.
     * <p>
     * Seeded from the states of {@code from} whose unsteered successor is already on
     * {@code keep}, then walked backwards through {@link NavMap#unsteeredPredecessors}, which
     * only ever offers live states — so the closure cannot leak into cells no boid could have
     * occupied, which is the one way this computation goes quietly wrong.
     *
     * @throws IllegalStateException if a state the edge is entered by turns out to be in the
     *         envelope, which would mean unsteered travel takes the exit unaided and would
     *         falsify the completeness argument the whole analysis rests on
     */
    public static Envelope envelope(NavMap map, int[] edge, int[] live, int liveCount,
                                    int from, int keep) {
        boolean[] in = new boolean[edge.length];
        boolean[] onKeep = new boolean[edge.length];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        List<Integer> entered = new ArrayList<>();

        for (int i = 0; i < liveCount; i++) {
            int s = live[i];
            if (edge[s] != from) continue;
            for (int t = -1; t <= 1; t++) {
                int u = map.successor(s, t);
                if (u < 0 || edge[u] != keep) continue;
                // Every way onto the exit edge counts, not only the straight one. A boid whose
                // coasting successor stays on this edge can still turn once and be on the exit
                // edge already — the cost-to-leave-exactly-one case — and it reaches that
                // state without ever standing on an envelope state of this edge. Seeding the
                // downstream part from straight successors alone leaves those exits outside
                // the net entirely, which is what a corpus run found.
                onKeep[u] = true;
                // The front is still the straight crossings: those are the states already
                // committed, and their count is the edge's follow-through population.
                if (t == 0 && !in[s]) { in[s] = true; queue.add(s); entered.add(s); }
            }
        }

        int[] preds = new int[3];
        while (!queue.isEmpty()) {
            int s = queue.poll();
            int n = map.unsteeredPredecessors(s, preds);
            for (int k = 0; k < n; k++) {
                int p = preds[k];
                if (edge[p] != from || in[p]) continue;
                in[p] = true;
                queue.add(p);
            }
        }

        for (int i = 0; i < liveCount; i++) {
            int s = live[i];
            if (edge[s] != from || !in[s] || !inbound(map, edge, s, from)) continue;
            throw new IllegalStateException(String.format(
                    "state %d is both an entry to edge %d and in the %d->%d envelope: unsteered "
                            + "travel would take the exit with nothing steering it", s, from,
                    from, keep));
        }

        return new Envelope(from, keep, collect(in, live, liveCount),
                collect(onKeep, live, liveCount), toArray(entered));
    }

    /** Whether something on another edge steps into this state. */
    private static boolean inbound(NavMap map, int[] edge, int s, int from) {
        int[] preds = new int[3];
        int n = map.steeredPredecessors(s, preds);
        for (int k = 0; k < n; k++) if (edge[preds[k]] != from) return true;
        return false;
    }

    // ---- settled states -----------------------------------------------------

    /**
     * States of an edge where the boid is flying its ordinary course and owes no account.
     * <p>
     * Called <b>settled</b> rather than stable to keep it clear of {@code stable[e]}, which is
     * a property of an edge; this is a property of a state.
     * <p>
     * Built as <b>{@code {closure, partial tick, closure}}</b>, where a closure is unsteered
     * forward travel that stays on the edge:
     * <ol>
     *   <li>coast forward from every state the edge is entered by, giving the tube of
     *       trajectories a boid arrives on;</li>
     *   <li>apply <b>one</b> partial tick;</li>
     *   <li>coast forward again from everything that produced.</li>
     * </ol>
     * <b>The partial tick is there for phase.</b> Boids do not stay evenly spread in tau modulo
     * the step length — four of them bucketed {@code 1111} clump to {@code 0202} after
     * travelling around obstacles — so states that lie on the path of unsteered travel in the
     * general sense get missed by being crossed <em>between</em> ticks. Turning as normal but
     * advancing only partway, onto the samples the collision test itself walks, recovers them.
     * This is the one place in the project where the phase artifact is modelled rather than
     * treated as a thing that looks like a finding and is not.
     * <p>
     * <b>Applied exactly once, and that is load-bearing.</b> Chaining partial ticks would let a
     * boid strafe up to 45° off its heading a pixel at a time. Once, between two closures,
     * captures every state a single partial tick can reach and nothing beyond it.
     * <p>
     * Every state the edge is entered by is settled, by construction — which is what bounds the
     * admission search below to within one edge and means it needs no depth cap.
     */
    public static boolean[] settled(NavMap map, int[] edge, int[] live, int liveCount, int from) {
        int turns = Params.TURNS, w = map.width(), h = map.height();
        boolean[] set = new boolean[edge.length];
        ArrayDeque<Integer> queue = new ArrayDeque<>();

        for (int i = 0; i < liveCount; i++) {
            int s = live[i];
            if (edge[s] != from || !inbound(map, edge, s, from)) continue;
            if (!set[s]) { set[s] = true; queue.add(s); }
        }
        coast(map, edge, from, set, queue);

        // One partial tick: turn as the unsteered rules say, then stop short, landing on any
        // sample of the step the out-of-bounds check walks. The heading is the one flown, which
        // is the heading after the veto has had its say.
        List<Integer> partial = new ArrayList<>();
        for (int i = 0; i < liveCount; i++) {
            int s = live[i];
            if (!set[s]) continue;
            int d = s % turns, cell = s / turns, x = cell % w, y = cell / w;
            int got = map.constrainTurn(x, y, d, 0);
            int nd = Math.floorMod(d + got, turns);
            int[] step = map.stepPath(nd);
            for (int q = 0; q + 1 < step.length; q += 2) {
                int mx = x + step[q], my = y + step[q + 1];
                if (mx < 0 || my < 0 || mx >= w || my >= h) continue;
                if (!map.alive(mx, my, nd)) continue;
                int mid = map.index(mx, my, nd);
                if (edge[mid] != from || set[mid]) continue;
                set[mid] = true;
                partial.add(mid);
            }
        }
        queue.addAll(partial);
        coast(map, edge, from, set, queue);
        return set;
    }

    /** Unsteered forward closure that stays on the edge. */
    private static void coast(NavMap map, int[] edge, int from, boolean[] set,
                              ArrayDeque<Integer> queue) {
        while (!queue.isEmpty()) {
            int s = queue.poll();
            int u = map.successor(s, 0);
            if (u < 0 || edge[u] != from || set[u]) continue;
            set[u] = true;
            queue.add(u);
        }
    }

    // ---- entries and their leaders -----------------------------------------

    /** How many pairs the admission search may look at before it gives up and says so. */
    public static final long BUDGET = 12_000_000L;

    /**
     * Drop leader placements beyond flocking range while walking a pair's history backwards.
     * <p>
     * <b>An approximation, and off by default, because it can lose real histories.</b> A leader
     * beyond {@code rFlock} steers nothing on that tick — but not every tick of the boid's
     * history has to be steered. It coasts between nudges, and during those ticks the leader is
     * free to drift out of range and back, so a history that does exactly that is one this
     * setting cannot find.
     * <p>
     * <b>Measured 2026-08-29, and the reason the option exists at all.</b> With it on, dabeone's
     * three arcs take 7 s, 186 s and 4 s. With it off, arc {@code 4->0} had not finished after
     * fifteen minutes and was holding 8.2 GB, heading for exhaustion rather than an answer — so
     * the memo, which is a large win, is not on its own enough. The pair space is
     * {@code |edge| x |live|}, about 2.2e9 on that arc, and an unconstrained leader really does
     * reach a large fraction of it: whenever the leader is out of range the boid's dynamics do
     * not depend on which out-of-range state it is in, so the search fans out over an enormous
     * set of pairs that all behave identically.
     * <p>
     * <b>That last sentence is also the exact fix, and it is specified rather than built.</b>
     * Collapse every out-of-range leader placement into a single abstract state. It is an exact
     * abstraction, not an approximation, because those placements are genuinely
     * indistinguishable to the boid; re-entering range going backwards then only has to consider
     * leader states within a step or two of the {@code rFlock} boundary, which is a thin shell
     * rather than the whole map. See {@code ROADMAP.md}.
     */
    public static boolean pruneOutOfRangeLeaders = false;

    /**
     * Every way a leader can put a boid onto the envelope, with an account of how it got there.
     * <p>
     * Three steps per candidate. Find the steered transitions that cross onto the envelope from
     * off it. For each, find the leader placements whose single-neighbour influence asks for
     * exactly that turn. Then admit the pair only if it has a plausible history — see
     * {@link #admit}.
     */
    public static Table analyse(NavMap map, int[] edge, int[] live, int liveCount,
                                int from, int keep, Flocking f) {
        return analyse(map, edge, live, liveCount, from, keep, f, f.diluted());
    }

    /**
     * The same, naming the second physics the boid is allowed to choose.
     * <p>
     * <b>The boid picks either model independently on every tick</b> — at the entry and at every
     * step of its history. That is the whole point of two models rather than one widened one: a
     * crowd tips a decision at some particular moments and not at others, and a table built
     * wholly under different constants turns every marginal straight into a turn along the entire
     * backward path. It would admit the wrong <em>shape</em> of history, not merely too many of
     * them.
     * <p>
     * The leader's freedom is unchanged; it could already steer as it liked. What changes is that
     * the exiting boid is no longer a single deterministic function of what it sees.
     */
    public static Table analyse(NavMap map, int[] edge, int[] live, int liveCount,
                                int from, int keep, Flocking f, Flocking alt) {
        return analyse(map, edge, live, liveCount, from, keep, f, alt, null);
    }

    /**
     * The same, naming the ground a history has to reach.
     * <p>
     * Admission walks a pair backwards until the exiting boid stands somewhere it could have been
     * left without explanation, and {@link #settled} is the narrow answer to that: what a boid
     * <b>alone</b> can hold on this edge. No boid in a scene is alone, and a history that merely
     * begins a little off settled is refused for a reason that has nothing to do with the exit —
     * which is the whole of the multi-leader residue on arc {@code 4->0}.
     * <p>
     * A wider ground is passed here instead. <b>It must contain {@link #settled}</b>; a ground
     * that did not would refuse histories the narrow one admits, which is the opposite of the
     * point. Callers that widen should union rather than replace.
     *
     * @param ground states of {@code from} that terminate a history, or null for {@link #settled}
     */
    public static Table analyse(NavMap map, int[] edge, int[] live, int liveCount,
                                int from, int keep, Flocking f, Flocking alt, boolean[] ground) {
        Envelope env = envelope(map, edge, live, liveCount, from, keep);
        boolean[] settled = ground != null ? ground : settled(map, edge, live, liveCount, from);

        boolean[] inEnv = new boolean[edge.length];
        for (int s : env.onFrom()) inEnv[s] = true;
        for (int s : env.onKeep()) inEnv[s] = true;

        int turns = Params.TURNS, w = map.width();
        List<Entry> entries = new ArrayList<>();
        long probed = 0;
        boolean budgetHit = false;

        // Shared across every candidate, and the difference between minutes and seconds. The
        // backward searches overlap almost entirely — different entries reach the same pairs a
        // step or two back — so without a memo the same subgraph is walked hundreds of
        // thousands of times.
        Map<Long, Long> admitted = new HashMap<>();
        Set<Long> rejected = new HashSet<>();

        // A build can run for hours, and without this there is no way to tell one that is
        // working from one that has stalled — which is exactly what happened once, and cost the
        // run. The memo sizes and the heap are here because they are what actually binds: the
        // pair space is |edge| x |live|, and holding a visited pair costs far more memory than
        // visiting it costs time.
        Progress progress = new Progress(from, keep);

        for (int i = 0; i < liveCount; i++) {
            int p = live[i];
            if (edge[p] != from || inEnv[p]) continue;
            progress.entries++;
            int pd = p % turns, pc = p / turns, px = pc % w, py = pc / w;
            for (int t = -1; t <= 1; t += 2) {
                int u = map.successor(p, t);
                if (u < 0 || !inEnv[u]) continue;
                // Straight from here does not reach the envelope: if it did, p would be in the
                // envelope already. So this really is the steered entry.
                for (int j = 0; j < liveCount; j++) {
                    int l = live[j];
                    if (l == p) continue;
                    int lc = l / turns, dx = lc % w - px, dy = lc / w - py;
                    long d2 = (long) dx * dx + (long) dy * dy;
                    if (d2 > (long) (f.rFlock() * f.rFlock())) continue;
                    // The true constants first, so an entry is only labelled a fallback when the
                    // real physics genuinely does not produce it.
                    boolean plain = EdgeInfluence.steer(pd, dx, dy, l % turns, f) == t;
                    boolean thin = !plain
                            && EdgeInfluence.steer(pd, dx, dy, l % turns, alt) == t;
                    if (!plain && !thin) continue;
                    progress.admits++;
                    Admission a = admit(map, edge, settled, from, p, l, f, alt, admitted,
                            rejected, progress);
                    if (a.budgetHit()) progress.budgetHits++;
                    probed += a.probed();
                    budgetHit |= a.budgetHit();
                    if (!a.admitted()) continue;
                    // Cause is read under whichever model carried the turn. Under the diluted
                    // one it can only ever come out SEPARATION, which is correct rather than
                    // uninformative: that is exactly what the fallback models.
                    entries.add(new Entry(p, l, u, t,
                            cause(pd, dx, dy, l % turns, t, plain ? f : alt), a.path(), thin));
                }
            }
        }
        return new Table(env, entries, count(settled, live, liveCount), probed, budgetHit);
    }

    private record Admission(boolean admitted, int[] path, long probed, boolean budgetHit) {}

    /**
     * Whether the pair could have got here, and by what leader route.
     * <p>
     * Walks the pair backwards under the {@link TwoBoid} rules — the leader had a free choice,
     * the boid was a pure function of what it saw — and admits the arrangement if the boid can
     * be traced back to a settled state. <b>No depth cap is needed:</b> every state the edge is
     * entered by is settled, so the walk cannot leave the edge without first arriving somewhere
     * that ends it.
     * <p>
     * Convention, matching {@link EdgeInfluence#lead}: a pair is read at the instant the boid
     * decides, with the leader where the boid sees it. One step back is therefore any
     * {@code (b', l')} where {@code l} is a steered successor of {@code l'} and the boid at
     * {@code b'}, seeing {@code l'}, moves to {@code b}. Branching is at most nine.
     * <p>
     * The leader's edge path is collected as the search runs, and the minimality rule doubles as
     * the pruning rule: a path that strictly contains one already known to work is abandoned
     * unread, which is both what the specification asks for and what keeps the search small.
     */
    /**
     * Solves the whole component this pair sits in, not just this pair.
     * <p>
     * Breadth-first over pairs with a visited set, never depth-first with backtracking: the pair
     * graph has many routes to the same pair, so enumerating paths is exponential where
     * enumerating pairs is linear, and the first version of this did the former and did not
     * finish.
     * <p>
     * <b>Two passes, and the second is what makes the memo worth having.</b> Stopping at the
     * first settled state answers this pair and leaves every other pair the search touched
     * unresolved, so the next candidate walks the same ground again — which is most of the cost,
     * because the components overlap almost entirely. Instead the first pass takes the component
     * to exhaustion and records who reaches whom, and the second propagates <em>reaches a
     * settled state</em> back out from the terminals. Every pair visited comes out of it
     * resolved, one way or the other, so the total work across an arc is one sweep of the union
     * of the components rather than one sweep per candidate.
     * <p>
     * The negative side is sound for the same reason it is cheap: a pair left unmarked after the
     * second pass has its entire backward-reachable set inside this component, none of it
     * settled, so it is genuinely rejected rather than merely unproven.
     *
     * @param admitted pair to the next pair along a route to settled ground, {@code -1} at a
     *                 terminal. Held as a chain rather than a stored path because the paths
     *                 share almost all of their length
     */
    private static Admission admit(NavMap map, int[] edge, boolean[] settled, int from,
                                   int boid, int leader, Flocking f, Flocking alt,
                                   Map<Long, Long> admitted, Set<Long> rejected,
                                   Progress progress) {
        long start = pack(boid, leader);
        if (admitted.containsKey(start)) {
            return new Admission(true, trail(edge, admitted, start), 0, false);
        }
        if (rejected.contains(start)) return new Admission(false, NO_PATH, 0, false);

        Map<Long, List<Long>> reachedBy = new HashMap<>();
        List<Long> seen = new ArrayList<>();
        ArrayDeque<Long> good = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();
        ArrayDeque<Long> queue = new ArrayDeque<>();
        queue.add(start);
        visited.add(start);
        long probed = 0;

        int turns = Params.TURNS, w = map.width();
        double speed = Params.speed(map.radius());
        int[] bPred = new int[3], lPred = new int[3];
        while (!queue.isEmpty()) {
            if (++probed > BUDGET) return new Admission(false, NO_PATH, probed, true);
            if ((probed & 0xFFFF) == 0) progress.tick(probed, admitted.size(), rejected.size());
            if (probed > progress.biggest) progress.biggest = probed;
            long cur = queue.poll();
            // Already known to reach nothing settled, so there is no point walking behind it.
            // Without this the negative memo is written and never read, and every search
            // re-expands ground a previous one already proved barren.
            if (rejected.contains(cur)) continue;
            seen.add(cur);
            int b = (int) (cur >>> 32), l = (int) cur;

            // Settled ground, or a pair an earlier component already resolved. Either way the
            // history is done and there is nothing further back worth walking.
            if (settled[b] || admitted.containsKey(cur)) {
                good.add(cur);
                continue;
            }

            int nb = map.steeredPredecessors(b, bPred);
            int nl = map.steeredPredecessors(l, lPred);
            for (int i = 0; i < nb; i++) {
                int bp = bPred[i];
                if (edge[bp] != from) continue;
                int bd = bp % turns, bc = bp / turns, bx = bc % w, by = bc / w;
                for (int j = 0; j < nl; j++) {
                    int lp = lPred[j], lc = lp / turns;
                    int dx = lc % w - bx, dy = lc / w - by;
                    if (pruneOutOfRangeLeaders && unrecoverable(dx, dy, bd, lp % turns, f, speed)) {
                        continue;
                    }
                    // Either model, chosen freshly on this tick. A history in which the crowd
                    // tipped one decision and not the next is exactly what this admits and what
                    // a uniformly widened table cannot express.
                    int lh = lp % turns;
                    if (map.successor(bp, EdgeInfluence.steer(bd, dx, dy, lh, f)) != b
                            && map.successor(bp, EdgeInfluence.steer(bd, dx, dy, lh, alt)) != b) {
                        continue;
                    }
                    long key = pack(bp, lp);
                    reachedBy.computeIfAbsent(key, k -> new ArrayList<>()).add(cur);
                    if (visited.add(key)) queue.add(key);
                }
            }
        }

        // Propagate outwards from the terminals: whatever steps back to a pair with a history
        // has one too.
        for (long g : good) admitted.putIfAbsent(g, -1L);
        while (!good.isEmpty()) {
            long g = good.poll();
            List<Long> back = reachedBy.get(g);
            if (back == null) continue;
            for (long p : back) {
                if (admitted.containsKey(p)) continue;
                admitted.put(p, g);
                good.add(p);
            }
        }
        for (long s : seen) if (!admitted.containsKey(s)) rejected.add(s);

        return admitted.containsKey(start)
                ? new Admission(true, trail(edge, admitted, start), probed, false)
                : new Admission(false, NO_PATH, probed, false);
    }

    private static final int[] NO_PATH = new int[0];

    /**
     * A running account of a build, printed every ten seconds.
     * <p>
     * Here because a build can run for hours and, without it, one that is working cannot be told
     * from one that has stalled — which happened, and cost a run that was killed at 2h46m with no
     * way to judge how close it was. The three numbers worth watching are the entry state (the
     * outer loop, and the only real measure of progress), whether anything is hitting the budget,
     * and the size of the memo, since that is what the search is trading memory for.
     */
    private static final class Progress {
        private final int from, keep;
        private final long began = System.nanoTime();
        private long nextReport = began + 10_000_000_000L;
        int entries, admits, budgetHits;
        long biggest;

        Progress(int from, int keep) { this.from = from; this.keep = keep; }

        /** Called on a coarse probe boundary, so the clock read is not itself a cost. */
        void tick(long probed, int admitted, int rejected) {
            long now = System.nanoTime();
            if (now <= nextReport) return;
            nextReport = now + 10_000_000_000L;
            Runtime rt = Runtime.getRuntime();
            System.out.printf("    %d->%d %5.0fs  entry %d  admits %,d (%,d hit budget)  "
                            + "this component %,d  biggest %,d  memo %,d/%,d  heap %,d MB%n",
                    from, keep, (now - began) / 1e9, entries, admits, budgetHits, probed,
                    biggest, admitted, rejected, (rt.totalMemory() - rt.freeMemory()) >> 20);
            System.out.flush();
        }
    }

    /**
     * Whether a leader this far out of range is too far out to have been in it.
     * <p>
     * A leader beyond {@code rFlock} steers nothing on that tick, and a boid that has not
     * reached settled ground needs steering to get there — so an out-of-range pair is only worth
     * walking if the two could plausibly have been together a moment ago. Two things say they
     * could not: the gap itself, and how fast it is opening. Headings that differ by a lot are
     * headings that separated quickly, so the pair has been apart for longer than the gap alone
     * suggests.
     * <p>
     * <b>Both terms are in pixels, and the budget is two ticks of travel.</b> The heading term is
     * a small-angle estimate of the ground given up to divergence, {@code speed} times the half
     * turn-step in radians times the squared heading difference. The result is a grace shell that
     * is about eight pixels deep for two boids flying parallel and closes entirely once the
     * headings differ by seven steps — which is the case the hard cutoff got wrong, two boids
     * running just off parallel and staying near each other for a long time.
     * <p>
     * Still an approximation. It is a statement about the pair's <em>current</em> positions and
     * headings, give or take pixel rounding and the small-angle assumption, not a proof about
     * their history. The exact treatment is the out-of-range collapse in {@code ROADMAP.md}.
     */
    static boolean unrecoverable(int dx, int dy, int boidHeading, int leaderHeading,
                                         Flocking f, double speed) {
        double dist2 = (double) dx * dx + (double) dy * dy;
        if (dist2 <= f.rFlock() * f.rFlock()) return false;
        int turns = Params.TURNS;
        int diff = Math.abs(Math.floorMod(leaderHeading - boidHeading + turns / 2, turns)
                - turns / 2);
        double opening = speed * Math.PI / turns * diff * diff;
        return opening + (Math.sqrt(dist2) - f.rFlock()) > 2 * speed;
    }

    /**
     * The leader's edges from settled ground up to this pair, consecutive repeats collapsed.
     * <p>
     * The chain runs backwards in time, so it is collected newest first and turned round at the
     * end. Chains cannot loop: a pair is only ever pointed at one already-resolved pair.
     */
    private static int[] trail(int[] edge, Map<Long, Long> admitted, long start) {
        List<Integer> newestFirst = new ArrayList<>();
        for (long at = start; ; ) {
            push(newestFirst, edge[(int) at]);
            Long next = admitted.get(at);
            if (next == null || next == -1L) break;
            at = next;
        }
        int[] out = new int[newestFirst.size()];
        for (int i = 0; i < out.length; i++) out[i] = newestFirst.get(out.length - 1 - i);
        return out;
    }

    private static void push(List<Integer> out, int e) {
        if (out.isEmpty() || out.get(out.size() - 1) != e) out.add(e);
    }

    private static long pack(int boid, int leader) {
        return ((long) boid << 32) | (leader & 0xFFFFFFFFL);
    }

    /** Whether {@code small} appears contiguously inside {@code big}. */
    static boolean isSubpath(int[] small, int[] big) {
        if (small.length > big.length) return false;
        outer:
        for (int off = 0; off + small.length <= big.length; off++) {
            for (int k = 0; k < small.length; k++) {
                if (big[off + k] != small[k]) continue outer;
            }
            return true;
        }
        return false;
    }

    // ---- cause --------------------------------------------------------------

    /**
     * Which influence turned the boid, at the instant it was turned.
     * <p>
     * Separation on one side, alignment and cohesion together on the other. The measure is the
     * component <b>orthogonal to the direction of travel</b>, signed so that positive means
     * rightward, because that is the only part of an influence that can change a heading — the
     * component along the heading pushes the boid where it was already going. Whichever set has
     * the larger orthogonal component <em>with the sign of the turn actually taken</em> is the
     * cause.
     * <p>
     * Expected to be sharply bimodal: separation and cohesion point opposite ways along the
     * line to the neighbour, so on the tick a turn is produced one of them is normally doing
     * nearly all of the work. Where neither carries the turn's own sign — possible, since the
     * decision is a comparison against the straight bias rather than a sum crossing zero — the
     * larger magnitude wins, which keeps the label defined without inventing a third category.
     */
    static Cause cause(int d, int dx, int dy, int leaderHeading, int turn, Flocking f) {
        double dist = Math.sqrt((double) dx * dx + (double) dy * dy);
        double sx = 0, sy = 0;
        if (dist > 0 && dist < f.rSep()) {
            sx = -f.wSep() * dx / dist;
            sy = -f.wSep() * dy / dist;
        }
        double ax = f.wAli() * Params.COS[leaderHeading] + (dist > 0 ? f.wCoh() * dx / dist : 0);
        double ay = f.wAli() * Params.SIN[leaderHeading] + (dist > 0 ? f.wCoh() * dy / dist : 0);

        int right = Math.floorMod(d + Params.TURNS / 4, Params.TURNS);
        double nx = Params.COS[right], ny = Params.SIN[right];
        double sep = sx * nx + sy * ny, ali = ax * nx + ay * ny;

        boolean sepSigned = Math.signum(sep) == Math.signum(turn) && sep != 0;
        boolean aliSigned = Math.signum(ali) == Math.signum(turn) && ali != 0;
        if (sepSigned != aliSigned) return sepSigned ? Cause.SEPARATION : Cause.ALIGNMENT_AND_COHESION;
        return Math.abs(sep) > Math.abs(ali) ? Cause.SEPARATION : Cause.ALIGNMENT_AND_COHESION;
    }

    // ---- lookup and storage -------------------------------------------------

    /**
     * The table as a consumer reads it: which leaders account for a given entry.
     * <p>
     * Keyed on the boid's state before it moved, because that is what a watcher has — it sees
     * membership flip and looks back one tick. The value is every leader placement that
     * explains it, so a caller with several boids in view intersects rather than guesses.
     */
    public static Map<Integer, List<Entry>> byBoidPrior(Table t) {
        Map<Integer, List<Entry>> out = new HashMap<>();
        for (Entry e : t.entries()) {
            out.computeIfAbsent(e.boidPrior(), k -> new ArrayList<>()).add(e);
        }
        return out;
    }

    /** One row per admitted pair, inside the map's own ingest. */
    public static void write(Path file, Table t) throws IOException {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        StringBuilder s = new StringBuilder();
        s.append("# format ").append(FORMAT).append(", arc ").append(t.envelope().from())
                .append("->").append(t.envelope().keep()).append(System.lineSeparator());
        s.append("boidPrior\tleaderPrior\tboidAt\tturn\tcause\tleaderPath")
                .append(System.lineSeparator());
        for (Entry e : t.entries()) {
            s.append(e.boidPrior()).append('\t').append(e.leaderPrior()).append('\t')
                    .append(e.boidAt()).append('\t').append(e.turn()).append('\t')
                    .append(e.cause()).append('\t')
                    .append(Arrays.toString(e.leaderPath()).replace(" ", ""))
                    .append(System.lineSeparator());
        }
        Files.writeString(file, s.toString());
    }

    // ---- small helpers ------------------------------------------------------

    private static int[] collect(boolean[] set, int[] live, int liveCount) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < liveCount; i++) if (set[live[i]]) out.add(live[i]);
        return toArray(out);
    }

    private static int count(boolean[] set, int[] live, int liveCount) {
        int n = 0;
        for (int i = 0; i < liveCount; i++) if (set[live[i]]) n++;
        return n;
    }

    private static int[] toArray(List<Integer> in) {
        int[] out = new int[in.size()];
        for (int i = 0; i < out.length; i++) out[i] = in.get(i);
        return out;
    }
}
