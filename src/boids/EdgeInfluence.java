package boids;

import java.util.ArrayList;
import java.util.List;

/**
 * Where a second boid has to be to save the first from committing to the wrong exit.
 * <p>
 * An edge with two ways out has states along it from which one unsteered tick settles the
 * question. Call those <b>critical</b>: a boid there that steers straight for one more tick
 * leaves for an exit that is neither the one being aimed at nor the edge it is on, and after
 * that no amount of steering brings it back. They are the last moment anything can be done.
 * <p>
 * A psyboid does not steer another boid directly. All it can do is be somewhere, and let the
 * flocking rules do the rest. So the question worth answering is not "which turn saves this"
 * but "where would a boid have to be for the rules to produce that turn" — and the answer is
 * a set of states in exactly the same space the navmap lives in, which is why it is drawn
 * the same way.
 * <p>
 * With one neighbour the rules collapse to something small. Separation, cohesion and
 * alignment are each normalised before weighting, so a lone neighbour contributes a unit
 * vector from each rule that fires: cohesion pulls along the line to it, separation pushes
 * back along that same line inside {@code rSep}, and alignment pulls along its heading. The
 * whole influence is {@code (W_COH - [d<rSep] W_SEP) u + W_ALI a}, which is
 * {@code 30u + 70a} out in the flocking annulus and {@code -90u + 70a} once inside
 * separation — the two regimes point opposite ways along {@code u}, so the same neighbour
 * position means opposite things either side of that radius.
 * <p>
 * Being <em>able</em> to induce the turn is not the same as being able to be there. The set
 * is computed over every position and heading, then intersected with the navigable states,
 * because a psyboid that has to occupy an unreachable state to help cannot help.
 */
public final class EdgeInfluence {
    private EdgeInfluence() {}

    /**
     * @param critical    states of {@code from} where one straight tick commits to the wrong exit
     * @param rescuable   those of them some turn still saves
     * @param influence   states a second boid could occupy to induce a saving turn
     * @param navigable   how many of those a boid could actually be in
     */
    public record Result(long[] influence, int critical, int rescuable, int influenceCount,
                         int navigable, int[] byTurn) {}

    /**
     * @param from the edge being left
     * @param keep the exit worth keeping; leaving for anything else is the wrong commit
     */
    public static Result analyse(NavMap map, int[] edge, int[] live, int liveCount,
                                 int from, int keep, Flocking f) {
        int turns = Params.TURNS, w = map.width(), h = map.height();
        int reach = (int) Math.floor(f.rFlock());

        // Critical: straight from here leaves the edge for something that is not the exit
        // being kept. One more unsteered tick and the question is settled the wrong way.
        List<Integer> critical = new ArrayList<>();
        for (int i = 0; i < liveCount; i++) {
            int s = live[i];
            if (edge[s] != from) continue;
            int u = map.successor(s, 0);
            if (u < 0) continue;
            int landed = edge[u];
            if (landed != from && landed != keep) critical.add(s);
        }

        // A turn is worth inducing when it does not itself commit to the wrong exit —
        // either it buys another tick on the edge, or it takes the exit being kept.
        List<int[]> want = new ArrayList<>();          // {state, turn}
        int[] byTurn = new int[2];
        for (int s : critical) {
            for (int t = -1; t <= 1; t += 2) {
                int u = map.successor(s, t);
                if (u < 0) continue;
                int landed = edge[u];
                if (landed != from && landed != keep) continue;
                want.add(new int[]{s, t});
                byTurn[t < 0 ? 0 : 1]++;
            }
        }

        long[] influence = new long[(w * h * turns + 63) >>> 6];
        // The rules do not care where the pair is, only how they sit relative to each
        // other, so one kernel per (heading, turn) serves every critical state that shares
        // them. Without that the same 4.5 million placements get re-derived per state.
        java.util.Map<Integer, long[]> kernels = new java.util.HashMap<>();
        for (int[] pair : want) {
            int s = pair[0], turn = pair[1];
            int d = s % turns, cell = s / turns, x = cell % w, y = cell / w;
            long[] kernel = kernels.computeIfAbsent(d * 4 + (turn < 0 ? 0 : 1),
                    k -> kernel(d, turn, reach, f));
            int span = 2 * reach + 1;
            for (int dy = -reach; dy <= reach; dy++) {
                int ny = y + dy;
                if (ny < 0 || ny >= h) continue;
                for (int dx = -reach; dx <= reach; dx++) {
                    int nx = x + dx;
                    if (nx < 0 || nx >= w) continue;
                    int at = ((dy + reach) * span + (dx + reach)) * turns;
                    int to = (nx + ny * w) * turns;
                    for (int hj = 0; hj < turns; hj++) {
                        if (get(kernel, at + hj)) set(influence, to + hj);
                    }
                }
            }
        }

        int count = 0, navigable = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                for (int hj = 0; hj < turns; hj++) {
                    if (!get(influence, (x + y * w) * turns + hj)) continue;
                    count++;
                    if (map.alive(x, y, hj)) navigable++;
                }
            }
        }
        return new Result(influence, critical.size(), want.size(), count, navigable, byTurn);
    }

    /**
     * The states a boid can be in on the way to committing the wrong way, and the two ends
     * of that stretch.
     *
     * @param envelope forward closure of the critical states within the edge, so a boid in
     *                 it stays in it until it leaves the edge altogether. Critical states
     *                 alone do not have that property, and without it "kept in the envelope"
     *                 would not be a condition a leader could hold onto tick after tick
     * @param terminal envelope states one steered tick from the exit worth keeping
     * @param source   where a boid enters the envelope: what an unsteered boid arriving on
     *                 the edge runs into, plus anything in the envelope that can reach it
     */
    public record Envelope(int[] envelope, int[] terminal, int[] source, int critical) {}

    public static Envelope envelope(NavMap map, int[] edge, int[] live, int liveCount,
                                    int from, int keep) {
        int turns = Params.TURNS, w = map.width();
        int[] preds = new int[3];

        boolean[] in = new boolean[edge.length];
        java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
        int critical = 0;
        for (int i = 0; i < liveCount; i++) {
            int s = live[i];
            if (edge[s] != from) continue;
            int u = map.successor(s, 0);
            if (u < 0 || edge[u] == from || edge[u] == keep) continue;
            critical++;
            if (!in[s]) { in[s] = true; queue.add(s); }
            // One tick wider at the back, so a boid a step short of committing is inside
            // the envelope rather than about to arrive in it out of nowhere.
            int n = map.steeredPredecessors(s, preds);
            for (int k = 0; k < n; k++) {
                if (edge[preds[k]] == from && !in[preds[k]]) { in[preds[k]] = true; queue.add(preds[k]); }
            }
        }
        // Forward closure, but only through states that stay on the edge: leaving the edge
        // is the end of the story either way, so it is not something to close over.
        while (!queue.isEmpty()) {
            int s = queue.poll();
            for (int t = -1; t <= 1; t++) {
                int u = map.successor(s, t);
                if (u < 0 || edge[u] != from || in[u]) continue;
                in[u] = true;
                queue.add(u);
            }
        }

        // Sources: where an unsteered boid that has just arrived on the edge runs into the
        // envelope, then everything in the envelope that can navigate to one of those.
        boolean[] src = new boolean[edge.length];
        java.util.ArrayDeque<Integer> back = new java.util.ArrayDeque<>();
        for (int i = 0; i < liveCount; i++) {
            int s = live[i];
            if (edge[s] != from || !inbound(map, edge, s, from)) continue;
            for (int at = s, guard = 0; guard < 1 << 16; guard++) {
                if (in[at] && !src[at]) { src[at] = true; back.add(at); }
                int u = map.successor(at, 0);
                if (u < 0 || edge[u] != from) break;
                at = u;
            }
        }
        // Unsteered only, both for the closure and for the pixels between. A source is
        // somewhere a boid that nothing has touched yet can be, so walking back through a
        // turn would be inventing a history that no unsteered boid has.
        while (!back.isEmpty()) {
            int s = back.poll();
            int n = map.unsteeredPredecessors(s, preds);
            for (int k = 0; k < n; k++) {
                int p = preds[k];
                if (!in[p]) continue;
                if (!src[p]) { src[p] = true; back.add(p); }
                // A step covers up to four pixels, so consecutive sources sit four apart
                // and the three pixels between them belong to trajectories on other phases
                // doing the same thing. Those are sources too, or the set only describes
                // the one phase the trajectory happened to land on. The pixels are the
                // step's own samples — the ones the collision test walks — on the heading
                // flown, which is the heading after the veto has had its say.
                int d = s % turns, cell = p / turns, px = cell % w, py = cell / w;
                int[] between = map.stepPath(d);
                for (int q = 0; q + 1 < between.length; q += 2) {
                    int mx = px + between[q], my = py + between[q + 1];
                    if (mx < 0 || my < 0 || mx >= w || my >= map.height()) continue;
                    if (!map.alive(mx, my, d)) continue;
                    int mid = map.index(mx, my, d);
                    if (src[mid]) continue;
                    src[mid] = true;
                    back.add(mid);
                }
            }
        }

        // The pixels between land on other phases, so some of them sit outside the envelope
        // the critical states generated. They still have to be in it — the search starts at
        // sources — and putting them in means closing forwards again, or the envelope stops
        // being something a boid cannot leave except by leaving the edge.
        int outside = 0;
        for (int i = 0; i < liveCount; i++) {
            int s = live[i];
            if (!src[s] || in[s] || edge[s] != from) continue;
            in[s] = true;
            outside++;
            queue.add(s);
        }
        while (!queue.isEmpty()) {
            int s = queue.poll();
            for (int t = -1; t <= 1; t++) {
                int u = map.successor(s, t);
                if (u < 0 || edge[u] != from || in[u]) continue;
                in[u] = true;
                queue.add(u);
            }
        }
        if (outside > 0) {
            System.out.printf("  %d sources lay outside the envelope; re-closed forwards%n", outside);
        }

        // A source everything downstream of which is also a source is behind the frontier
        // and carries no information: every route out of it runs through a source nearer the
        // front, which the search already starts from with nothing assumed about the leader.
        // Judged against the source set as it stands, all at once, so the answer does not
        // depend on which order they are visited in.
        boolean[] redundant = new boolean[edge.length];
        for (int i = 0; i < liveCount; i++) {
            int s = live[i];
            if (!src[s] || !in[s]) continue;
            boolean allSource = true;
            for (int t = -1; t <= 1 && allSource; t++) {
                int u = map.successor(s, t);
                if (u >= 0 && !src[u]) allSource = false;
            }
            redundant[s] = allSource;
        }
        for (int i = 0; i < liveCount; i++) {
            if (redundant[live[i]]) { src[live[i]] = false; in[live[i]] = false; }
        }

        // Trim: envelope states no source can reach are bloat, and the leader search is
        // priced per envelope state.
        boolean[] reach = new boolean[edge.length];
        for (int i = 0; i < liveCount; i++) {
            if (src[live[i]] && in[live[i]] && !reach[live[i]]) { reach[live[i]] = true; queue.add(live[i]); }
        }
        while (!queue.isEmpty()) {
            int s = queue.poll();
            for (int t = -1; t <= 1; t++) {
                int u = map.successor(s, t);
                if (u < 0 || !in[u] || reach[u]) continue;
                reach[u] = true;
                queue.add(u);
            }
        }

        List<Integer> envelope = new ArrayList<>(), terminal = new ArrayList<>(),
                source = new ArrayList<>();
        for (int i = 0; i < liveCount; i++) {
            int s = live[i];
            if (!reach[s]) continue;
            envelope.add(s);
            if (src[s]) source.add(s);
            for (int t = -1; t <= 1; t += 2) {
                int u = map.successor(s, t);
                if (u >= 0 && edge[u] == keep) { terminal.add(s); break; }
            }
        }
        return new Envelope(toArray(envelope), toArray(terminal), toArray(source), critical);
    }

    /**
     * How the sources break down by what can precede them.
     *
     * @param withSteered   sources something could have steered into
     * @param withUnsteered sources something could have coasted into
     * @param inEnvelope    the same two, counting only predecessors inside the envelope
     */
    public record SourceKinds(int total, int withSteered, int withUnsteered,
                              int steeredInEnvelope, int unsteeredInEnvelope) {}

    public static SourceKinds sourceKinds(NavMap map, Envelope env) {
        boolean[] inEnv = new boolean[map.width() * map.height() * Params.TURNS];
        for (int s : env.envelope()) inEnv[s] = true;
        int[] preds = new int[3];
        int steered = 0, unsteered = 0, steeredIn = 0, unsteeredIn = 0;
        for (int s : env.source()) {
            int n = map.steeredPredecessors(s, preds);
            if (n > 0) steered++;
            for (int k = 0; k < n; k++) if (inEnv[preds[k]]) { steeredIn++; break; }
            int m = map.unsteeredPredecessors(s, preds);
            if (m > 0) unsteered++;
            for (int k = 0; k < m; k++) if (inEnv[preds[k]]) { unsteeredIn++; break; }
        }
        return new SourceKinds(env.source().length, steered, unsteered, steeredIn, unsteeredIn);
    }

    /**
     * Whether every terminal state can be reached backwards from some source.
     * <p>
     * The check that says the source set is not missing a phase. A terminal nothing can
     * reach is one a boid arrives at by a route the sources do not describe, which is
     * exactly what dropping the sub-step pixels would cause.
     */
    public static int unreachableTerminals(NavMap map, Envelope env) {
        boolean[] inEnv = new boolean[map.width() * map.height() * Params.TURNS];
        for (int s : env.envelope()) inEnv[s] = true;
        boolean[] fromSource = new boolean[inEnv.length];
        java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
        for (int s : env.source()) if (!fromSource[s]) { fromSource[s] = true; queue.add(s); }
        while (!queue.isEmpty()) {
            int s = queue.poll();
            for (int t = -1; t <= 1; t++) {
                int u = map.successor(s, t);
                if (u < 0 || !inEnv[u] || fromSource[u]) continue;
                fromSource[u] = true;
                queue.add(u);
            }
        }
        int missing = 0;
        for (int s : env.terminal()) if (!fromSource[s]) missing++;
        return missing;
    }

    /** Whether something on another edge steps into this state. */
    private static boolean inbound(NavMap map, int[] edge, int s, int from) {
        int turns = Params.TURNS, w = map.width();
        int d = s % turns, cell = s / turns, x = cell % w, y = cell / w;
        int back = (d + turns / 2) % turns;
        // Everything arriving here sits one step back down the heading, on one of the three
        // headings that can turn into this one.
        int px = x + map.stepX(back), py = y + map.stepY(back);
        if (px < 0 || py < 0 || px >= w || py >= map.height()) return false;
        for (int t = -1; t <= 1; t++) {
            int pd = Math.floorMod(d - t, turns);
            if (!map.alive(px, py, pd)) continue;
            if (map.constrainTurn(px, py, pd, t) != t) continue;
            if (edge[(px + py * w) * turns + pd] != from) return true;
        }
        return false;
    }

    private static int[] toArray(List<Integer> list) {
        int[] out = new int[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = list.get(i);
        return out;
    }

    /**
     * @param atSources   leaders that, standing where a boid enters the envelope, can hold it
     *                    all the way out by the exit worth keeping
     * @param atTerminals leaders standing one tick from that exit who could have been leading
     *                    ever since the boid entered
     */
    /**
     * @param forward  per envelope state, the leaders that could have led a boid to it since a
     *                 source, as bits over live indices
     * @param backward per envelope state, the leaders that can still see it out by the right
     *                 exit
     * @param liveOf   live index to state id, for reading {@code forward} and {@code backward}
     */
    public record Lead(long[] atSources, long[] atTerminals, int sourcesLed, int terminalsLed,
                       long[][] forward, long[][] backward, int[] liveOf, int[] envelope) {}

    /**
     * Who can lead a boid the whole way out, rather than merely turn it once.
     * <p>
     * Turning a boid at one moment is cheap and most of the map can do it. Holding it from
     * where it enters the envelope to where it leaves by the right exit is not, because the
     * leader is a boid too: it has to be somewhere it could have flown to from wherever it
     * was a tick ago, while the boid it is leading is somewhere new as well. So the thing to
     * search is the pair — follower state and leader state together — and the question is
     * which pairs lie on a run that goes all the way through.
     * <p>
     * Two passes over that pair graph, in opposite directions and answering different
     * questions. Forwards from the sources gives, at each terminal state, the leaders that
     * could have got a boid there. Backwards from the exit gives, at each source, the
     * leaders that can still finish the job. A leader in the first set has a past; one in the
     * second has a future; neither implies the other.
     */
    public static Lead lead(NavMap map, int[] edge, int[] live, int liveCount, int from,
                            int keep, Flocking f, Envelope env) {
        int turns = Params.TURNS, w = map.width();
        int n = env.envelope().length, words = (liveCount + 63) >>> 6;

        int[] envIndex = new int[edge.length];
        java.util.Arrays.fill(envIndex, -1);
        for (int i = 0; i < n; i++) envIndex[env.envelope()[i]] = i;
        int[] liveIndex = new int[edge.length];
        java.util.Arrays.fill(liveIndex, -1);
        for (int i = 0; i < liveCount; i++) liveIndex[live[i]] = i;

        // The leader has to fly too, so its own moves are part of the search.
        int[] leaderSucc = new int[liveCount * 3];
        for (int i = 0; i < liveCount; i++) {
            for (int t = -1; t <= 1; t++) {
                int u = map.successor(live[i], t);
                leaderSucc[i * 3 + t + 1] = u < 0 ? -1 : liveIndex[u];
            }
        }

        // Keep: leader placements that do not let the boid out of the envelope the wrong way.
        long[][] keepSet = new long[n][words];
        for (int i = 0; i < n; i++) {
            int s = env.envelope()[i];
            int d = s % turns, cell = s / turns, x = cell % w, y = cell / w;
            for (int pi = 0; pi < liveCount; pi++) {
                int p = live[pi];
                int pd = p % turns, pc = p / turns;
                int t = steer(d, pc % w - x, pc / w - y, pd, f);
                int u = map.successor(s, t);
                if (u < 0) continue;
                if (envIndex[u] >= 0 || edge[u] == keep) keepSet[i][pi >>> 6] |= 1L << (pi & 63);
            }
        }

        long[][] forward = new long[n][words];
        boolean[] queued = new boolean[n];
        java.util.ArrayDeque<Integer> work = new java.util.ArrayDeque<>();
        for (int s : env.source()) {
            int i = envIndex[s];
            System.arraycopy(keepSet[i], 0, forward[i], 0, words);
            if (!queued[i]) { queued[i] = true; work.add(i); }
        }
        while (!work.isEmpty()) {
            int i = work.poll();
            queued[i] = false;
            int s = env.envelope()[i];
            int d = s % turns, cell = s / turns, x = cell % w, y = cell / w;
            long[][] add = new long[n][];
            for (int pi = 0; pi < liveCount; pi++) {
                if ((forward[i][pi >>> 6] & (1L << (pi & 63))) == 0) continue;
                int p = live[pi], pd = p % turns, pc = p / turns;
                int t = steer(d, pc % w - x, pc / w - y, pd, f);
                int u = map.successor(s, t);
                if (u < 0) continue;
                int j = envIndex[u];
                if (j < 0) continue;                       // left the envelope; nothing to carry
                for (int k = 0; k < 3; k++) {
                    int pj = leaderSucc[pi * 3 + k];
                    if (pj < 0) continue;
                    if ((keepSet[j][pj >>> 6] & (1L << (pj & 63))) == 0) continue;
                    if ((forward[j][pj >>> 6] & (1L << (pj & 63))) != 0) continue;
                    if (add[j] == null) add[j] = new long[words];
                    add[j][pj >>> 6] |= 1L << (pj & 63);
                }
            }
            for (int j = 0; j < n; j++) {
                if (add[j] == null) continue;
                boolean grew = false;
                for (int q = 0; q < words; q++) {
                    long merged = forward[j][q] | add[j][q];
                    if (merged != forward[j][q]) { forward[j][q] = merged; grew = true; }
                }
                if (grew && !queued[j]) { queued[j] = true; work.add(j); }
            }
        }

        // Backwards: a leader counts if the boid exits now, or if it can move somewhere that
        // still counts for wherever the boid ends up next.
        int[][] envPred = envPredecessors(map, env.envelope(), envIndex, n);
        long[][] backward = new long[n][words];
        java.util.Arrays.fill(queued, true);
        for (int i = 0; i < n; i++) work.add(i);
        while (!work.isEmpty()) {
            int i = work.poll();
            queued[i] = false;
            int s = env.envelope()[i];
            int d = s % turns, cell = s / turns, x = cell % w, y = cell / w;
            boolean grew = false;
            for (int pi = 0; pi < liveCount; pi++) {
                if ((keepSet[i][pi >>> 6] & (1L << (pi & 63))) == 0) continue;
                if ((backward[i][pi >>> 6] & (1L << (pi & 63))) != 0) continue;
                int p = live[pi], pd = p % turns, pc = p / turns;
                int t = steer(d, pc % w - x, pc / w - y, pd, f);
                int u = map.successor(s, t);
                if (u < 0) continue;
                boolean ok = edge[u] == keep;
                int j = envIndex[u];
                for (int k = 0; k < 3 && !ok && j >= 0; k++) {
                    int pj = leaderSucc[pi * 3 + k];
                    if (pj >= 0 && (backward[j][pj >>> 6] & (1L << (pj & 63))) != 0) ok = true;
                }
                if (ok) { backward[i][pi >>> 6] |= 1L << (pi & 63); grew = true; }
            }
            if (!grew) continue;
            for (int j : envPred[i]) if (!queued[j]) { queued[j] = true; work.add(j); }
        }

        long[] atSources = new long[(map.width() * map.height() * turns + 63) >>> 6];
        long[] atTerminals = new long[atSources.length];
        int sourcesLed = 0, terminalsLed = 0;
        for (int s : env.source()) {
            int i = envIndex[s];
            if (spread(backward[i], live, liveCount, atSources)) sourcesLed++;
        }
        for (int s : env.terminal()) {
            int i = envIndex[s];
            if (spread(forward[i], live, liveCount, atTerminals)) terminalsLed++;
        }
        int[] liveOf = new int[liveCount];
        System.arraycopy(live, 0, liveOf, 0, liveCount);
        return new Lead(atSources, atTerminals, sourcesLed, terminalsLed,
                forward, backward, liveOf, env.envelope());
    }

    /** Copies a set held over live indices out into one held over states. */
    private static boolean spread(long[] byLive, int[] live, int liveCount, long[] byState) {
        boolean any = false;
        for (int pi = 0; pi < liveCount; pi++) {
            if ((byLive[pi >>> 6] & (1L << (pi & 63))) == 0) continue;
            any = true;
            int s = live[pi];
            byState[s >>> 6] |= 1L << (s & 63);
        }
        return any;
    }

    /** Which envelope states step to which, ignoring who caused it. */
    private static int[][] envPredecessors(NavMap map, int[] envelope, int[] envIndex, int n) {
        List<List<Integer>> pred = new ArrayList<>();
        for (int i = 0; i < n; i++) pred.add(new ArrayList<>());
        for (int i = 0; i < n; i++) {
            for (int t = -1; t <= 1; t++) {
                int u = map.successor(envelope[i], t);
                if (u < 0) continue;
                int j = envIndex[u];
                if (j >= 0 && !pred.get(j).contains(i)) pred.get(j).add(i);
            }
        }
        int[][] out = new int[n][];
        for (int i = 0; i < n; i++) out[i] = toArray(pred.get(i));
        return out;
    }

    /**
     * Every offset and heading a lone neighbour could have that makes the rules ask for
     * {@code turn}, for a boid on heading {@code d}.
     */
    private static long[] kernel(int d, int turn, int reach, Flocking f) {
        int turns = Params.TURNS, span = 2 * reach + 1;
        long[] bits = new long[(span * span * turns + 63) >>> 6];
        for (int dy = -reach; dy <= reach; dy++) {
            for (int dx = -reach; dx <= reach; dx++) {
                int at = ((dy + reach) * span + (dx + reach)) * turns;
                for (int hj = 0; hj < turns; hj++) {
                    if (steer(d, dx, dy, hj, f) == turn) set(bits, at + hj);
                }
            }
        }
        return bits;
    }

    /**
     * The turn the flocking rules ask for, with exactly one neighbour.
     * <p>
     * The same arithmetic as {@link MovementLogic#calculate}, specialised to a single
     * neighbour so it can run a few hundred million times. Evaluation order and the strict
     * comparison are reproduced exactly, because they decide every tie: straight beats both
     * turns, and left beats right.
     */
    static int steer(int d, int dx, int dy, int hj, Flocking f) {
        if (dx == 0 && dy == 0) return 0;
        double d2 = (double) dx * dx + (double) dy * dy;
        if (d2 > f.rFlock() * f.rFlock()) return 0;              // out of range: holds heading
        double dist = Math.sqrt(d2);
        double hx = Params.COS[d], hy = Params.SIN[d];
        if (dx * hx + dy * hy < Params.COS_FOV * dist) return 0;  // behind: never seen

        double dirX = 0, dirY = 0;
        if (dist < f.rSep()) {                                    // separation, already unit
            // The falloff survives here only if the aggregation lets it. Under physics 2 the
            // separation sum is renormalised, which erases the length of a single vector, so the
            // falloff cancels exactly and a neighbour a pixel inside rSep pushes as hard as one
            // on top of the boid. Under a clamped aggregation a short sum stays short and the
            // falloff is real. See Flocking.sepFalloff.
            double w = f.sepFalloff() ? f.wSep() * (f.rSep() - dist) / f.rSep() : f.wSep();
            dirX -= w * dx / dist;
            dirY -= w * dy / dist;
        }
        dirX += f.wCoh() * dx / dist;                             // cohesion, already unit
        dirY += f.wCoh() * dy / dist;
        dirX += f.wAli() * Params.COS[hj];                        // alignment, already unit
        dirY += f.wAli() * Params.SIN[hj];
        if (dirX == 0.0 && dirY == 0.0) return 0;

        int best = 0;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int delta : new int[]{0, -1, +1}) {
            int a = Math.floorMod(d + delta, turnsOf());
            double score = Params.COS[a] * dirX + Params.SIN[a] * dirY
                    + (delta == 0 ? f.straightBias() : 0.0);
            if (score > bestScore) { bestScore = score; best = delta; }
        }
        return best;
    }

    private static int turnsOf() { return Params.TURNS; }

    private static void set(long[] bits, int i) { bits[i >>> 6] |= 1L << (i & 63); }

    private static boolean get(long[] bits, int i) {
        return (bits[i >>> 6] & (1L << (i & 63))) != 0;
    }
}
