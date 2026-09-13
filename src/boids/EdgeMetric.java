package boids;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A clock for a decomposed map: how far along its edge every state is, in ticks.
 * <p>
 * The decomposition says which stretch of map a state belongs to but nothing about where in
 * that stretch it sits, so two states on the same edge are incomparable and states on
 * different edges doubly so. Any measurement taken between them then depends on where the
 * edges happened to be cut, which is an artifact of the decomposition rather than a fact
 * about the map. A clock fixes that: give every state a tick and distances become
 * subtractions, including across a boundary and across many of them.
 * <p>
 * Three things, in order, each needing the one before.
 * <ol>
 *   <li><b>Where each arc crosses.</b> One entry state per incoming edge and one exit state
 *       per outgoing edge, so everything arriving from a given edge arrives at the same
 *       place and everything leaving for one leaves from the same place.</li>
 *   <li><b>A length</b> per edge, shared by every way through it. Without that, a distance
 *       carried across a boundary would depend on which way the boid came, and the whole
 *       point is that it does not.</li>
 *   <li><b>A tick</b> per state, fitted so that every transition advances the clock by one.</li>
 * </ol>
 * <p>
 * <b>Lengths add.</b> Join two adjacent edges, shift the downstream ticks up by the upstream
 * length, and the result is a valid clock on the joined edge. Equivalently, and more usefully
 * when several edges meet at once: take the tick of anything arriving at a vertex and subtract
 * its own edge's length, and every edge around that vertex is then measured from the vertex
 * itself. That form generalises — a vertex with four edges on it can be read in one frame,
 * where the pairwise form can only compare two edges at a time.
 * <p>
 * The clock is one least-squares fit over the whole map: every transition wants to advance it
 * by exactly one tick, and where that cannot hold everywhere the error is spread rather than
 * dumped in one place. Nothing is held fixed except a single state to say where zero is,
 * because holding more makes it worse — see {@link #compute(NavMap, int[], int[], int, int,
 * EdgeWeights.Scheme, double[][])}. Stationarity of the squared error is a Laplacian, solved by conjugate gradient.
 * Sweeping instead would move a correction one state per pass, and with only one state
 * anchored the system's conditioning goes as the square of the graph's diameter, so a sweep
 * needs the square of an edge's length to settle where the gradient needs a few thousand
 * steps.
 * <p>
 * <b>A stricter version does not exist, at least not here.</b> Collapsing each edge to a
 * single path — one entry shared by all incoming edges, one exit able to reach every
 * outgoing edge — is tempting because it makes lengths trivially consistent. On dabeone it
 * is infeasible, and not marginally: at the vertex where edges 7 and 8 both feed edge 4,
 * the two land on 42 and 609 states of it and share 33, but a boid entering edge 8 where the
 * fork upstream would have to put it can only ever reach 68 states of edge 4, none of them
 * shared. A single shared entry and a single fork exit cannot both exist there. Hence one
 * entry per arc, which costs nothing that matters: the lengths still agree, so distances
 * still add.
 */
public final class EdgeMetric {
    private EdgeMetric() {}

    /**
     * How far outside its neighbours' band a state may sit before it counts as being outside.
     * <p>
     * Not slack in the requirement — a hundredth of a tick is far below anything a distance is
     * ever read to. It is there because the band has no width at all for a state with one
     * predecessor and one successor: the requirement pins it to the exact midpoint, so any
     * residue left by an iterative solve counts as a violation and the count measures how long
     * the sweep ran rather than whether the clock is ordered. Measured against zero, dabeone
     * reports 6,760 states outside; against a thousandth, 816; against a hundredth, three.
     */
    private static final double BAND_TOLERANCE = 1e-2;

    /** Relative residual the last solve reached, how many steps, and how much was held. */
    private static double residual;
    private static int iterations;
    private static int pinned;
    private static String weighting = "";

    /** Set while the last metric came off disk instead of out of a solve. */
    private static String restored;

    /** How close the last solve got, and how long it took. */
    public static String lastSolve() {
        if (restored != null) return restored;
        return String.format("residual %.2e after %d steps, %d lengths held for gauge; %s",
                residual, iterations, pinned, weighting);
    }

    /**
     * Says the metric just handed out came from store, so the report does not quote residuals
     * and step counts belonging to whatever was solved before it.
     */
    static void restored(String summary, double band) {
        restored = summary;
        worstBand = band;
    }

    /** Worst amount a state falls outside its neighbours' band; reported, never enforced. */
    private static double worstBand;

    /** @see #worstBand */
    public static double lastWorstBand() { return worstBand; }

    /**
     * @param entryFrom {@code [edge][from]} the state a boid arriving from {@code from} enters
     *                  at, or -1 where that is not an arc
     * @param exitTo    {@code [edge][to]} the state the edge hands off to {@code to} from
     * @param length    per edge, shared by every way through it; ticks run {@code 0..length-1},
     *                  so the step off an exit lands on the next edge's entry at tick
     *                  {@code length} once shifted, and lengths add rather than overlap
     * @param tick      per state id, its position along its own edge; NaN where it has none
     * @param slack     ways through an edge whose shortest route is shorter than the edge's
     *                  length, so the clock has to stretch them
     * @param broken    ways through an edge with no route at all, which should be none
     * @param band      states breaking the local ordering requirement
     * @param step      worst deviation from one tick per step along a canonical path
     * @param cost      mean squared deviation from one tick per transition, everywhere
     */
    public record Metric(int[][] entryFrom, int[][] exitTo, double[] length, double[] tick,
                         int slack, int broken, int band, double step, double cost) {}

    public static Metric compute(NavMap map, int[] edge, int[] live, int liveCount, int edges) {
        return compute(map, edge, live, liveCount, edges, EdgeWeights.Scheme.UNIFORM, null);
    }

    public static Metric compute(NavMap map, int[] edge, int[] live, int liveCount, int edges,
                                 EdgeWeights.Scheme scheme) {
        return compute(map, edge, live, liveCount, edges, scheme, null);
    }

    /**
     * <b>Lengths are real numbers, fitted, and nothing here rounds them.</b> There used to be a
     * second solver behind a {@code pinPaths} flag that held the canonical paths at whole ticks
     * and took each length as its whole-step estimate; it was off by default, never called, and
     * was removed on 2026-09-12 because integer lengths were a bug once (half a tick of error at
     * every crossing) and code that can reintroduce one is not worth keeping. The only integer
     * in the fit is {@code estimate}, a count of graph steps used to seed the lengths and as the
     * target the gauge is restored toward; the lengths themselves come out of the solve.
     *
     * @param chain    the steering chain {@link EdgeWeights.Scheme#MOMENTUM} weights by, and
     *                 ignored by every other scheme, which have no notion of what a boid last
     *                 asked for
     */
    public static Metric compute(NavMap map, int[] edge, int[] live, int liveCount, int edges,
                                 EdgeWeights.Scheme scheme, double[][] chain) {
        if (scheme == EdgeWeights.Scheme.MOMENTUM && chain == null) {
            throw new IllegalArgumentException("MOMENTUM needs a steering chain");
        }
        restored = null;
        long[] down = new long[edges], up = new long[edges];
        for (int i = 0; i < liveCount; i++) {
            int s = live[i];
            for (int t = -1; t <= 1; t++) {
                int u = map.successor(s, t);
                if (u < 0 || edge[u] < 0 || edge[u] == edge[s]) continue;
                down[edge[s]] |= 1L << edge[u];
                up[edge[u]] |= 1L << edge[s];
            }
        }

        Reach reach = new Reach(edge.length, map, edge);
        int[][] entryFrom = new int[edges][edges];
        int[][] exitTo = new int[edges][edges];
        for (int[] row : entryFrom) Arrays.fill(row, -1);
        for (int[] row : exitTo) Arrays.fill(row, -1);
        chooseArcs(map, edge, live, liveCount, edges, entryFrom, exitTo, reach);

        // One length per edge: the longest of its shortest ways through. Anything shorter
        // would make some route impossible to fit; anything longer stretches them all.
        // Whole ticks here because it is a count of graph steps; the fit refines it to a real
        // number, and that refinement is the length everything downstream reads.
        int[] estimate = new int[edges];
        double[] length = new double[edges];
        int slack = 0, broken = 0;
        for (int e = 0; e < edges; e++) {
            int worst = 1;
            for (int a = 0; a < edges; a++) {
                if (entryFrom[e][a] < 0) continue;
                for (int c = 0; c < edges; c++) {
                    if (exitTo[e][c] < 0) continue;
                    int d = reach.distance(e, entryFrom[e][a], exitTo[e][c]);
                    if (d < 0) { broken++; continue; }
                    worst = Math.max(worst, d + 1);
                }
            }
            estimate[e] = worst;
        }
        for (int e = 0; e < edges; e++) {
            for (int a = 0; a < edges; a++) {
                if (entryFrom[e][a] < 0) continue;
                for (int c = 0; c < edges; c++) {
                    if (exitTo[e][c] < 0) continue;
                    int d = reach.distance(e, entryFrom[e][a], exitTo[e][c]);
                    if (d >= 0 && d + 1 < estimate[e]) slack++;
                }
            }
        }
        // One state held, and only to fix the origin. The fit determines every difference
        // between ticks but nothing says where zero is, so without an anchor the answer is a
        // family rather than a value. Chosen by lowest state id so a rerun of the same map
        // lands on the same numbers rather than merely the same shape. The lengths are solved
        // for jointly with the ticks — the estimate only seeds them and names where the gauge
        // is restored to — and they are real numbers throughout.
        int gauge = Integer.MAX_VALUE;
        for (int e = 0; e < edges; e++) {
            for (int a = 0; a < edges; a++) {
                if (entryFrom[e][a] >= 0) gauge = Math.min(gauge, entryFrom[e][a]);
            }
        }
        double[] tick = solveJoint(map, edge, live, liveCount, edges, estimate, length, gauge,
                scheme, chain);
        return new Metric(entryFrom, exitTo, length, tick, slack, broken,
                bandViolations(map, edge, live, liveCount, length, tick),
                worstStep(map, edge, live, liveCount, edges, estimate, tick, entryFrom, exitTo, reach),
                cost(map, edge, live, liveCount, length, tick));
    }

    // ---- where each arc crosses ------------------------------------------------------

    /**
     * Picks, for every arc, the one state the upstream edge leaves from and the one state the
     * downstream edge is entered at.
     * <p>
     * Any crossing pair would satisfy the rules, so the choice is made on how much room it
     * leaves: the pair scored highest is the one whose upstream half the most of the upstream
     * edge can reach and whose downstream half reaches the most of the downstream edge. A
     * crossing tucked into a corner of either edge would leave some way through with no route
     * at all, and this is exactly the pressure against that.
     */
    private static void chooseArcs(NavMap map, int[] edge, int[] live, int liveCount, int edges,
                                   int[][] entryFrom, int[][] exitTo, Reach reach) {
        // All the places each arc could cross.
        List<List<List<int[]>>> crossings = new ArrayList<>();
        for (int a = 0; a < edges; a++) {
            List<List<int[]>> row = new ArrayList<>();
            for (int b = 0; b < edges; b++) row.add(new ArrayList<>());
            crossings.add(row);
        }
        int[] succs = new int[3];
        for (int i = 0; i < liveCount; i++) {
            int x = live[i], a = edge[x];
            if (a < 0) continue;
            int n = map.steeredSuccessors(x, succs);
            for (int k = 0; k < n; k++) {
                int y = succs[k];
                if (edge[y] < 0 || edge[y] == a) continue;
                crossings.get(a).get(edge[y]).add(new int[]{x, y});
            }
        }

        // Exits first, each as late in its edge as it can be — the state the most of the edge
        // can reach. Then entries, each placed as far back from those exits as it can be.
        // Distance is the whole point: an edge that loops has every state reaching every
        // other, so "late" and "early" tie everywhere and the choice would otherwise fall out
        // arbitrary — which lands entry on top of exit and calls a 38,000-state edge one tick
        // long.
        for (int a = 0; a < edges; a++) {
            for (int b = 0; b < edges; b++) {
                List<int[]> options = crossings.get(a).get(b);
                if (options.isEmpty()) continue;
                int best = -1, bestScore = -1;
                for (int[] pair : options) {
                    int score = reach.backwardSize(a, pair[0]);
                    if (score > bestScore) { bestScore = score; best = pair[0]; }
                }
                exitTo[a][b] = best;
            }
        }
        for (int e = 0; e < edges; e++) {
            List<int[]> dists = new ArrayList<>();
            for (int c = 0; c < edges; c++) {
                if (exitTo[e][c] >= 0) dists.add(reach.distancesTo(e, exitTo[e][c]));
            }
            for (int a = 0; a < edges; a++) {
                List<int[]> options = crossings.get(a).get(e);
                if (options.isEmpty()) continue;
                int best = -1, bestScore = -1;
                for (int[] pair : options) {
                    int y = pair[1], worst = Integer.MAX_VALUE;
                    for (int[] d : dists) {
                        int v = d[y];
                        if (v < 0) { worst = -1; break; }
                        worst = Math.min(worst, v);
                    }
                    if (worst > bestScore) { bestScore = worst; best = y; }
                }
                entryFrom[e][a] = best;
                // The exit the upstream edge leaves from has to be one that actually steps
                // here, so it is re-picked against the entry rather than left as chosen above.
                for (int[] pair : options) {
                    if (pair[1] == best) { exitTo[a][e] = pair[0]; break; }
                }
            }
        }
    }

    // ---- the clock -------------------------------------------------------------------

    /**
     * Spreads tick values over every state, holding the path endpoints where they belong.
     * <p>
     * Every transition should advance the clock by one. Within an edge that reads
     * {@code t(u) = t(s) + 1}; across a boundary the downstream edge restarts at zero, so it
     * reads {@code t(u) = t(s) + 1 - length(edge of s)}, which is exactly zero error where an
     * exit steps to an entry and is what ties the per-edge clocks into one.
     */
    /**
     * Solves for the ticks and the edge lengths together, in one least-squares system.
     * <p>
     * A length is not a fact about an edge that can be looked up before the clock exists. It
     * is defined by the clock: for the clock to be continuous, an edge's length has to equal
     * the drop in tick across its boundary, which is not knowable until the ticks are. Fixing
     * it beforehand — from the shortest way through, say — makes it an extra constraint the
     * ticks have to bend around, and in a corridor with no alternative route there is nowhere
     * for that to go except into the rate, which is why plait's bottleneck came out uniformly
     * off.
     * <p>
     * So the lengths join the unknowns. Every transition contributes one residual,
     * {@code t(u) - t(s) - 1} plus the length of the edge being left if it is being left, and
     * that is linear in the ticks <em>and</em> the lengths alike. Differentiating by a length
     * rather than a tick just gives another equation of the same system: an edge's length
     * settles at the mean tick drop over its crossings, which is what it was supposed to mean
     * all along.
     * <p>
     * Solved as normal equations by conjugate gradient, matrix-free — the matrix is one row
     * per transition and four hundred thousand by a hundred and thirty thousand, so it is
     * never built. An explicit inversion would be the same answer; this is the same
     * computation with the zeros left out.
     */
    /**
     * The rows of the fit: one per transition, plus the way back from a request to its row.
     *
     * @param cross     per row, the edge being left if it leaves one, else -1
     * @param rowOf     per {@code (live index, request + 1)}, the row that request lands on.
     *                  Rows are per distinct successor, because two requests the veto collapses
     *                  together are one transition and fitting it twice would weight it double
     *                  — which is exactly why the way back cannot be recovered afterwards
     * @param index     per state id, its live index, or -1
     */
    record Rows(int[] from, int[] to, int[] cross, boolean[] unsteered, int[] rowOf,
                int[] index, int count) {}

    static Rows rows(NavMap map, int[] edge, int[] live, int liveCount) {
        int[] index = new int[edge.length];
        Arrays.fill(index, -1);
        for (int i = 0; i < liveCount; i++) index[live[i]] = i;

        int[] fromOf = new int[liveCount * 3], toOf = new int[liveCount * 3];
        int[] crossOf = new int[liveCount * 3];
        boolean[] unsteered = new boolean[liveCount * 3];
        int[] rowOf = new int[liveCount * 3];
        Arrays.fill(rowOf, -1);
        int rows = 0;
        int[] succs = new int[3];
        int[] rowFor = new int[3];
        for (int i = 0; i < liveCount; i++) {
            int s = live[i];
            if (edge[s] < 0) continue;
            int ns = map.steeredSuccessors(s, succs);
            for (int k = 0; k < ns; k++) {
                int u = succs[k];
                if (index[u] < 0 || edge[u] < 0) { rowFor[k] = -1; continue; }
                fromOf[rows] = i;
                toOf[rows] = index[u];
                crossOf[rows] = edge[u] != edge[s] ? edge[s] : -1;
                unsteered[rows] = u == map.successor(s, 0);
                rowFor[k] = rows;
                rows++;
            }
            for (int t = -1; t <= 1; t++) {
                int u = map.successor(s, t);
                for (int k = 0; k < ns; k++) if (succs[k] == u) rowOf[i * 3 + t + 1] = rowFor[k];
            }
        }
        return new Rows(fromOf, toOf, crossOf, unsteered, rowOf, index, rows);
    }

    /**
     * A weight is how much a transition's residual counts. Applied as the square root by the
     * caller, because the fit squares what it is given.
     */
    static double[] weights(NavMap map, int[] live, int liveCount, Rows r,
                            EdgeWeights.Scheme scheme, double[][] chain) {
        return scheme == EdgeWeights.Scheme.MOMENTUM
                ? EdgeWeights.momentum(map, live, liveCount, r.index(), r.rowOf(), chain,
                        r.count())
                : EdgeWeights.of(scheme, r.from(), r.to(), r.unsteered(), r.count(), liveCount);
    }

    /**
     * Where the clock's worst single-tick steps are, and what they have in common.
     * <p>
     * A transition whose weight is near zero contributes near nothing to the sum being
     * minimised, so the two states it joins are all but unconstrained relative to each other
     * and the gradient has almost nothing to say about where they go. That predicts the
     * extremes sit on starved transitions rather than being spread evenly, and it is a
     * different claim from a bug: a bug would put them anywhere.
     * <p>
     * Reported by weight decile so the two can be told apart. If the worst steps are flat
     * across deciles the weighting is not the cause; if they pile up in the bottom decile it
     * is conditioning, and the answer is a floor on the weights rather than a hunt.
     */
    public static void diagnose(NavMap map, int[] edge, int[] live, int liveCount, Metric m,
                                EdgeWeights.Scheme scheme, double[][] chain) {
        Rows r = rows(map, edge, live, liveCount);
        double[] w = weights(map, live, liveCount, r, scheme, chain);
        double[] tick = m.tick();
        double[] length = m.length();
        int rows = r.count();

        double[] step = new double[rows];
        for (int k = 0; k < rows; k++) {
            step[k] = tick[live[r.to()[k]]] - tick[live[r.from()[k]]]
                    + (r.cross()[k] >= 0 ? length[r.cross()[k]] : 0);
        }

        Integer[] order = new Integer[rows];
        for (int k = 0; k < rows; k++) order[k] = k;
        Arrays.sort(order, (x, y) -> Double.compare(w[x], w[y]));

        System.out.printf("%n  worst single-tick step by weight decile, %s%n", scheme);
        System.out.printf("  %-8s %11s %11s %9s %9s %9s %9s%n", "decile", "weight lo",
                "weight hi", "mean |e|", "p99 |e|", "max |e|", "crossing");
        for (int d = 0; d < 10; d++) {
            int from = (int) ((long) d * rows / 10), to = (int) ((long) (d + 1) * rows / 10);
            double sum = 0, worst = 0;
            int crossing = 0;
            double[] errs = new double[to - from];
            for (int j = from; j < to; j++) {
                double e = Math.abs(step[order[j]] - 1);
                errs[j - from] = e;
                sum += e;
                worst = Math.max(worst, e);
                if (r.cross()[order[j]] >= 0) crossing++;
            }
            Arrays.sort(errs);
            System.out.printf("  %-8d %11.3g %11.3g %9.4f %9.4f %9.4f %8.2f%%%n", d,
                    w[order[from]], w[order[to - 1]], sum / errs.length,
                    errs[(int) (errs.length * 0.99)], worst,
                    100.0 * crossing / errs.length);
        }

        Arrays.sort(order, (x, y) -> Double.compare(Math.abs(step[y] - 1), Math.abs(step[x] - 1)));
        System.out.printf("  ten worst steps: ");
        for (int j = 0; j < Math.min(10, rows); j++) {
            System.out.printf("%.2f@w=%.2g%s ", step[order[j]], w[order[j]],
                    r.cross()[order[j]] >= 0 ? "(x)" : "");
        }
        System.out.println();

        // The fit chooses a length as a real number and it is then stored as a whole tick.
        // Nothing inside an edge notices, because a length only enters where a boid leaves —
        // but there it enters in full, so every crossing out of an edge inherits the whole
        // rounding error at once. What the crossings imply the length should have been says
        // how much of the worst steps is that and how much is the clock genuinely straining.
        int edges = length.length;
        double[] sum = new double[edges];
        int[] seen = new int[edges];
        for (int k = 0; k < rows; k++) {
            int e = r.cross()[k];
            if (e < 0) continue;
            sum[e] += 1 - (step[k] - length[e]);
            seen[e]++;
        }
        System.out.printf("  %-5s %8s %10s %9s %9s%n", "edge", "stored", "implied", "rounding",
                "crossings");
        double[] implied = new double[edges];
        for (int e = 0; e < edges; e++) {
            implied[e] = seen[e] > 0 ? sum[e] / seen[e] : length[e];
            System.out.printf("  %-5d %8.3f %10.3f %+9.3f %9d%n", e, length[e], implied[e],
                    implied[e] - length[e], seen[e]);
        }
        double roundedWorst = 0, impliedWorst = 0;
        for (int k = 0; k < rows; k++) {
            int e = r.cross()[k];
            roundedWorst = Math.max(roundedWorst, Math.abs(step[k] - 1));
            double asReal = e < 0 ? step[k] : step[k] - length[e] + implied[e];
            impliedWorst = Math.max(impliedWorst, Math.abs(asReal - 1));
        }
        System.out.printf("  worst step with stored lengths %.3f, with implied lengths %.3f%n",
                roundedWorst, impliedWorst);
    }

    private static double[] solveJoint(NavMap map, int[] edge, int[] live, int liveCount,
                                       int edges, int[] estimate, double[] length,
                                       int anchorState, EdgeWeights.Scheme scheme,
                                       double[][] chain) {
        Rows built = rows(map, edge, live, liveCount);
        int[] index = built.index(), fromOf = built.from(), toOf = built.to();
        int[] crossOf = built.cross();
        boolean[] unsteered = built.unsteered();
        int rows = built.count();

        double[] w = weights(map, live, liveCount, built, scheme, chain);
        double[] root = new double[rows];
        for (int k = 0; k < rows; k++) root[k] = Math.sqrt(w[k]);
        double[] balance = EdgeWeights.imbalance(w, fromOf, toOf, rows, liveCount);
        weighting = String.format("%s: net flow worst %.3f, mean %.5f; %s%s", scheme,
                balance[0], balance[1], EdgeWeights.lastWeights(),
                scheme == EdgeWeights.Scheme.MOMENTUM ? "; " + EdgeWeights.lastLifted() : "");

        int n = liveCount + edges;
        double[] x = new double[n];
        boolean[] held = new boolean[n];

        // Exactly enough held to make the answer unique, chosen from the graph rather than
        // bought with a penalty: a spanning forest's worth of lengths, and one tick.
        long[] down = new long[edges];
        for (int k = 0; k < rows; k++) if (crossOf[k] >= 0) down[crossOf[k]] |= 1L << edge[live[toOf[k]]];
        boolean[] tree = new boolean[edges];
        int[] cls = gauge(edges, down, tree);
        int pinnedLengths = 0;
        for (int e = 0; e < edges; e++) {
            if (tree[e]) { held[liveCount + e] = true; x[liveCount + e] = 0; pinnedLengths++; }
            else x[liveCount + e] = estimate[e];
        }
        if (index[anchorState] >= 0) held[index[anchorState]] = true;

        double[] r = new double[rows], q = new double[rows];
        double[] g = new double[n], p = new double[n];
        forward(x, r, fromOf, toOf, crossOf, rows, liveCount, root);
        for (int k = 0; k < rows; k++) r[k] = root[k] - r[k];       // one tick per transition
        backward(r, g, fromOf, toOf, root, crossOf, rows, n, liveCount);
        for (int i = 0; i < n; i++) if (held[i]) g[i] = 0;
        System.arraycopy(g, 0, p, 0, n);
        double gg = dot(g, g), gg0 = gg;
        int step = 0;
        for (; step < 100000 && gg > 1e-16 * Math.max(1, gg0); step++) {
            forward(p, q, fromOf, toOf, crossOf, rows, liveCount, root);
            double qq = dot(q, q);
            if (qq <= 0) break;
            double alpha = gg / qq;
            for (int i = 0; i < n; i++) if (!held[i]) x[i] += alpha * p[i];
            for (int k = 0; k < rows; k++) r[k] -= alpha * q[k];
            backward(r, g, fromOf, toOf, root, crossOf, rows, n, liveCount);
            for (int i = 0; i < n; i++) if (held[i]) g[i] = 0;
            double next = dot(g, g);
            double beta = next / gg;
            gg = next;
            for (int i = 0; i < n; i++) p[i] = held[i] ? 0 : g[i] + beta * p[i];
        }
        residual = Math.sqrt(gg / Math.max(1e-300, gg0));
        iterations = step;
        pinned = pinnedLengths;

        double[] tick = new double[edge.length];
        Arrays.fill(tick, Double.NaN);
        for (int i = 0; i < liveCount; i++) tick[live[i]] = x[i];
        double[] settled = new double[edges];
        for (int e = 0; e < edges; e++) settled[e] = x[liveCount + e];
        regauge(edges, down, cls, settled, estimate, tick, edge, live, liveCount);
        System.arraycopy(settled, 0, length, 0, edges);
        // Regauging moves the lengths and drags the whole clock with them, which leaves the
        // absolute ticks somewhere arbitrary. Shifting them all back is free — nothing reads
        // an absolute tick, only differences — and it keeps the numbers legible.
        double zero = tick[anchorState];
        if (!Double.isNaN(zero)) {
            for (int i = 0; i < liveCount; i++) tick[live[i]] -= zero;
        }
        return tick;
    }

    /**
     * Which lengths to hold so the system has exactly one answer, worked out from the graph.
     * <p>
     * The fit is underdetermined, and in a way that is fully describable. A direction that
     * costs nothing has {@code t} shifted by a constant on each edge — call it {@code c(e)} —
     * because any transition inside an edge sees both ends move together. A crossing out of
     * {@code e} into {@code f} then moves by {@code c(e) - c(f)}, which the length of
     * {@code e} has to absorb, so {@code c} must be equal across everything downstream of the
     * same edge, and the whole free direction is a potential on those classes.
     * <p>
     * That makes the fix combinatorial rather than numerical. Quotient the edges by "shares a
     * source", draw one arc per edge between the classes it joins, and take a spanning forest:
     * pinning the length of each tree arc removes exactly one degree of freedom and no more,
     * and one tick per component removes the last. The system is then square, and the solve
     * has no near-null direction to crawl along.
     * <p>
     * Doing it with a small penalty instead works in principle and badly in practice — the
     * free directions get curvature the square of the penalty weight, so the iteration count
     * grows as its reciprocal, and there is no weight that is both light enough not to bias
     * the answer and heavy enough to converge. The pinned values are meaningless on their own;
     * {@link #regauge} puts them back afterwards, exactly and for free.
     *
     * @param tree  filled with which edges had their length pinned
     * @return the class of each edge
     */
    private static int[] gauge(int edges, long[] down, boolean[] tree) {
        int[] parent = new int[edges];
        for (int e = 0; e < edges; e++) parent[e] = e;
        for (int e = 0; e < edges; e++) {
            int first = -1;
            for (int f = 0; f < edges; f++) {
                if ((down[e] & (1L << f)) == 0) continue;
                if (first < 0) first = f;
                else union(parent, first, f);
            }
        }
        int[] cls = new int[edges];
        for (int e = 0; e < edges; e++) cls[e] = find(parent, e);

        // Spanning forest over the classes: one arc per edge, joining the class it sits in to
        // the class of whatever is downstream of it. An arc that joins two classes not already
        // connected is a tree arc, and its edge's length is one of the free choices.
        int[] forest = new int[edges];
        for (int i = 0; i < edges; i++) forest[i] = i;
        for (int e = 0; e < edges; e++) {
            if (down[e] == 0) continue;
            int a = find(forest, cls[e]);
            int b = find(forest, cls[Long.numberOfTrailingZeros(down[e])]);
            if (a != b) { forest[a] = b; tree[e] = true; }
        }
        return cls;
    }

    /**
     * Puts the pinned lengths back to something meaningful, without touching the fit.
     * <p>
     * The pinned lengths were held at zero to make the system square, which is a legitimate
     * answer and a useless one to read. Every other answer differs from it by a free
     * direction, and those are exactly a potential {@code c} on the edge classes: add
     * {@code c} to every tick on an edge and {@code c(e) - c(downstream)} to every length, and
     * not one residual changes. So the lengths can be moved to wherever they are most sensible
     * afterwards, at no cost to the fit at all.
     * <p>
     * "Most sensible" is nearest the traversal estimate, over all the edges at once — which is
     * itself a least squares, but over one unknown per class rather than one per state, so it
     * is a handful of equations solved directly.
     */
    private static void regauge(int edges, long[] down, int[] cls, double[] length,
                                int[] estimate, double[] tick, int[] edgeOf, int[] live,
                                int liveCount) {
        int[] label = new int[edges];
        Arrays.fill(label, -1);
        int n = 0;
        for (int e = 0; e < edges; e++) if (label[cls[e]] < 0) label[cls[e]] = n++;

        // Normal equations for min over c of sum over e of (L(e) + c(e) - c(down e) - L0(e))^2.
        double[][] a = new double[n + 1][n + 2];
        for (int e = 0; e < edges; e++) {
            if (down[e] == 0) continue;
            int i = label[cls[e]], j = label[cls[Long.numberOfTrailingZeros(down[e])]];
            double r = estimate[e] - length[e];
            a[i][i] += 1; a[i][j] -= 1; a[i][n + 1] += r;
            a[j][j] += 1; a[j][i] -= 1; a[j][n + 1] -= r;
        }
        for (int i = 0; i < n; i++) a[i][i] += 1e-9;             // fixes the overall offset
        double[] c = solveSmall(a, n);

        for (int e = 0; e < edges; e++) {
            if (down[e] == 0) continue;
            length[e] += c[label[cls[e]]] - c[label[cls[Long.numberOfTrailingZeros(down[e])]]];
        }
        for (int i = 0; i < liveCount; i++) {
            int s = live[i];
            if (edgeOf[s] >= 0) tick[s] += c[label[cls[edgeOf[s]]]];
        }
    }

    private static int find(int[] parent, int x) {
        while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x]; }
        return x;
    }

    private static void union(int[] parent, int a, int b) {
        int ra = find(parent, a), rb = find(parent, b);
        if (ra != rb) parent[ra] = rb;
    }

    /** Gaussian elimination, for a system with one unknown per edge class. */
    private static double[] solveSmall(double[][] a, int n) {
        for (int col = 0; col < n; col++) {
            int best = col;
            for (int r = col + 1; r < n; r++) {
                if (Math.abs(a[r][col]) > Math.abs(a[best][col])) best = r;
            }
            double[] swap = a[col]; a[col] = a[best]; a[best] = swap;
            if (Math.abs(a[col][col]) < 1e-12) continue;
            for (int r = 0; r < n; r++) {
                if (r == col) continue;
                double f = a[r][col] / a[col][col];
                for (int k = col; k <= n + 1; k++) a[r][k] -= f * a[col][k];
            }
        }
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            out[i] = Math.abs(a[i][i]) < 1e-12 ? 0 : a[i][n + 1] / a[i][i];
        }
        return out;
    }

    /** One residual per transition, before subtracting the target of one tick. */
    private static void forward(double[] x, double[] out, int[] fromOf, int[] toOf,
                                int[] crossOf, int rows, int liveCount, double[] root) {
        for (int k = 0; k < rows; k++) {
            double v = x[toOf[k]] - x[fromOf[k]];
            if (crossOf[k] >= 0) v += x[liveCount + crossOf[k]];
            out[k] = root[k] * v;
        }
    }

    /**
     * The same matrix applied the other way, accumulating each row back onto its unknowns:
     * the two states it joins, and the length of the edge it leaves if it leaves one.
     */
    private static void backward(double[] r, double[] out, int[] fromOf, int[] toOf, double[] root,
                                 int[] crossOf, int rows, int n, int liveCount) {
        Arrays.fill(out, 0);
        for (int k = 0; k < rows; k++) {
            double v = root[k] * r[k];
            out[toOf[k]] += v;
            out[fromOf[k]] -= v;
            if (crossOf[k] >= 0) out[liveCount + crossOf[k]] += v;
        }
    }

    private static double dot(double[] a, double[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) sum += a[i] * b[i];
        return sum;
    }

    // ---- checks ----------------------------------------------------------------------

    /**
     * States whose tick does not sit between its neighbours'.
     * <p>
     * A state has to fall between the average of its slowest predecessor and slowest
     * successor and the average of its fastest of each. On a plain chain that pins it to the
     * midpoint; where an edge branches it leaves a band. Checked rather than enforced,
     * because the least-squares fit satisfies it wherever the graph is not pathological and a
     * violation is worth seeing rather than quietly clamping away.
     */
    private static int bandViolations(NavMap map, int[] edge, int[] live, int liveCount,
                                      double[] length, double[] tick) {
        int[] preds = new int[3], succs = new int[3];
        int bad = 0;
        worstBand = 0;
        atBoundary = 0;
        Arrays.fill(histogram, 0);
        double[] loP = new double[64], hiP = new double[64], loS = new double[64], hiS = new double[64];
        int[] before = new int[4], after = new int[4];
        for (int i = 0; i < liveCount; i++) {
            int s = live[i], home = edge[s];
            if (home < 0) continue;

            // Grouped by which edge the neighbour is on, because a composite is only ever
            // formed with one neighbouring edge at a time. Pooling two upstream edges of
            // different lengths would compare ticks that were never in the same frame — and
            // would invent a band no composite actually asks for.
            int nb = 0, na = 0;
            int np = map.steeredPredecessors(s, preds);
            for (int k = 0; k < np; k++) {
                int p = preds[k], pe = edge[p];
                if (pe < 0) continue;
                double v = tick[p] - (pe != home ? length[pe] : 0);
                if (!seen(before, nb, pe)) { before[nb++] = pe; loP[pe] = v; hiP[pe] = v; }
                else { loP[pe] = Math.min(loP[pe], v); hiP[pe] = Math.max(hiP[pe], v); }
            }
            int ns = map.steeredSuccessors(s, succs);
            for (int k = 0; k < ns; k++) {
                int u = succs[k], ue = edge[u];
                if (ue < 0) continue;
                double v = tick[u] + (ue != home ? length[home] : 0);
                if (!seen(after, na, ue)) { after[na++] = ue; loS[ue] = v; hiS[ue] = v; }
                else { loS[ue] = Math.min(loS[ue], v); hiS[ue] = Math.max(hiS[ue], v); }
            }
            if (nb == 0 || na == 0) continue;

            // Each composite is its own claim, and the state has to satisfy all of them.
            double off = 0;
            for (int a = 0; a < nb; a++) {
                for (int c = 0; c < na; c++) {
                    int A = before[a], C = after[c];
                    double low = (loP[A] + loS[C]) / 2, high = (hiP[A] + hiS[C]) / 2;
                    off = Math.max(off, Math.max(low - tick[s], tick[s] - high));
                }
            }
            if (off > BAND_TOLERANCE) {
                bad++;
                worstBand = Math.max(worstBand, off);
                if (nb > 1 || na > 1 || before[0] != home || after[0] != home) atBoundary++;
                for (int k = 0; k < SPREAD.length; k++) if (off > SPREAD[k]) histogram[k]++;
            }
        }
        return bad;
    }

    /** Thresholds the band report is bucketed at, in ticks. */
    private static final double[] SPREAD = {0.01, 0.1, 0.5, 1, 2, 5};
    private static final int[] histogram = new int[SPREAD.length];
    private static int atBoundary;

    /** How the last run's band violations were distributed, as a line of text. */
    public static String lastBandSpread() {
        StringBuilder b = new StringBuilder();
        for (int k = 0; k < SPREAD.length; k++) {
            b.append(b.length() > 0 ? ", " : "").append("> ").append(SPREAD[k]).append(": ")
                    .append(histogram[k]);
        }
        return b + "; " + atBoundary + " of them where an edge meets another";
    }

    private static boolean seen(int[] list, int n, int value) {
        for (int i = 0; i < n; i++) if (list[i] == value) return true;
        return false;
    }

    /** The worst a step along a canonical path deviates from advancing the clock by one. */
    private static double worstStep(NavMap map, int[] edge, int[] live, int liveCount, int edges,
                                    int[] estimate, double[] tick, int[][] entryFrom,
                                    int[][] exitTo, Reach reach) {
        double worst = 0;
        for (int e = 0; e < edges; e++) {
            for (int a = 0; a < edges; a++) {
                if (entryFrom[e][a] < 0) continue;
                for (int c = 0; c < edges; c++) {
                    if (exitTo[e][c] < 0) continue;
                    // Only routes that fill the edge exactly. One with slack has to cover the
                    // difference somewhere, and measuring it here would report the slack
                    // rather than the fit. Against the whole-step estimate, not the fitted
                    // length: a real length equals a step count only by accident, and
                    // comparing against it had quietly emptied this diagnostic.
                    int[] path = reach.path(e, entryFrom[e][a], exitTo[e][c]);
                    if (path == null || path.length != estimate[e]) continue;
                    for (int i = 0; i + 1 < path.length; i++) {
                        worst = Math.max(worst, Math.abs(tick[path[i + 1]] - tick[path[i]] - 1));
                    }
                }
            }
        }
        return worst;
    }

    /** Mean squared deviation from one tick per transition. */
    private static double cost(NavMap map, int[] edge, int[] live, int liveCount,
                               double[] length, double[] tick) {
        double sum = 0;
        long n = 0;
        for (int i = 0; i < liveCount; i++) {
            int s = live[i];
            if (edge[s] < 0) continue;
            for (int t = -1; t <= 1; t++) {
                int u = map.successor(s, t);
                if (u < 0 || edge[u] < 0) continue;
                double r = tick[u] - tick[s] - 1 + (edge[u] != edge[s] ? length[edge[s]] : 0);
                sum += r * r;
                n++;
            }
        }
        return n == 0 ? 0 : sum / n;
    }

    /** Distances and reachable counts within a single edge, computed once per question asked. */
    private static final class Reach {
        private final NavMap map;
        private final int[] edge;
        private final int[] stamp, queue, dist, from;
        private int version;
        private final java.util.Map<Long, int[]> forward = new java.util.HashMap<>();

        Reach(int states, NavMap map, int[] edge) {
            this.map = map;
            this.edge = edge;
            stamp = new int[states];
            queue = new int[states];
            dist = new int[states];
            from = new int[states];
        }

        /** How much of edge {@code e} is reachable going forwards from {@code s}. */
        int forwardSize(int e, int s) {
            int mark = ++version;
            int head = 0, tail = 0;
            stamp[s] = mark;
            queue[tail++] = s;
            while (head < tail) {
                int at = queue[head++];
                for (int t = -1; t <= 1; t++) {
                    int u = map.successor(at, t);
                    if (u < 0 || edge[u] != e || stamp[u] == mark) continue;
                    stamp[u] = mark;
                    queue[tail++] = u;
                }
            }
            return tail;
        }

        /** Steps from every state of edge {@code e} to {@code target}, or -1 where none. */
        int[] distancesTo(int e, int target) {
            int[] out = new int[stamp.length];
            Arrays.fill(out, -1);
            int head = 0, tail = 0;
            out[target] = 0;
            queue[tail++] = target;
            int[] preds = new int[3];
            while (head < tail) {
                int at = queue[head++];
                int n = map.steeredPredecessors(at, preds);
                for (int k = 0; k < n; k++) {
                    int p = preds[k];
                    if (edge[p] != e || out[p] >= 0) continue;
                    out[p] = out[at] + 1;
                    queue[tail++] = p;
                }
            }
            return out;
        }

        /** How much of edge {@code e} can reach {@code s}. */
        int backwardSize(int e, int s) {
            int mark = ++version;
            int head = 0, tail = 0;
            stamp[s] = mark;
            queue[tail++] = s;
            int[] preds = new int[3];
            while (head < tail) {
                int at = queue[head++];
                int n = map.steeredPredecessors(at, preds);
                for (int k = 0; k < n; k++) {
                    int p = preds[k];
                    if (edge[p] != e || stamp[p] == mark) continue;
                    stamp[p] = mark;
                    queue[tail++] = p;
                }
            }
            return tail;
        }

        /** Steps from {@code s} to {@code t} inside edge {@code e}, or -1 if there are none. */
        int distance(int e, int s, int t) {
            int[] p = path(e, s, t);
            return p == null ? -1 : p.length - 1;
        }

        int[] path(int e, int s, int t) {
            long key = (long) e << 46 ^ (long) s << 23 ^ t;
            if (forward.containsKey(key)) return forward.get(key);
            int mark = ++version;
            int head = 0, tail = 0;
            stamp[s] = mark;
            dist[s] = 0;
            from[s] = -1;
            queue[tail++] = s;
            int[] result = null;
            while (head < tail && result == null) {
                int at = queue[head++];
                if (at == t) {
                    int n = dist[at] + 1;
                    result = new int[n];
                    for (int cur = at, i = n - 1; i >= 0; i--, cur = from[cur]) result[i] = cur;
                    break;
                }
                for (int turn = -1; turn <= 1; turn++) {
                    int u = map.successor(at, turn);
                    if (u < 0 || edge[u] != e || stamp[u] == mark) continue;
                    stamp[u] = mark;
                    dist[u] = dist[at] + 1;
                    from[u] = at;
                    queue[tail++] = u;
                }
            }
            forward.put(key, result);
            return result;
        }
    }
}
