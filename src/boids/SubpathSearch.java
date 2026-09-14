package boids;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Finds a discrete set of shortcuts and longcuts on a map: paths along an edge that gain, or
 * lose, more tau per tick than the corridor around them.
 * <p>
 * <b>Specified by the user 2026-09-13; finding only.</b> A path {@code P} of {@code N} permitted
 * transitions along one edge is scored by
 * <blockquote>{@code F = ±(tau(P_N) − tau(P_0) − N) / |⋃ᵢ (E⊥Sᵢ ∪ Sᵢ)|}</blockquote>
 * where {@code Sᵢ} is the partial tick of step {@code i} — the samples the step sweeps through
 * at its new heading, landing included — and {@code E⊥Sᵢ} is every state of the edge that can
 * neither reach {@code Sᵢ} nor be reached from it. The numerator is the tau the path gains
 * beyond one per tick (sign flipped for a longcut); the denominator is the path's footprint, the
 * part of the edge that is level with it rather than committed to it. The footprint carries a
 * fixed cost — the slab around a single step is tens of ticks of corridor — so the ratio is an
 * average with an overhead, and it settles at a finite length rather than growing forever.
 *
 * <h2>How the best {@code F} is looked for</h2>
 * The first version seeded at the single tick spanning the most tau and grew one tick at a time.
 * The best single tick is an outlier, not a lane: it grew to four steps, and the one real lane
 * on dabeone was found second, from a weaker seed, only because the first's range was excluded.
 * So now, per edge, a dynamic programme over the edge's transitions — acyclic, since every orbit
 * has been cut — finds the best {@code K}-step run ending at every state, the top few runs by
 * gain per step with disjoint tau ranges are taken as seeds, each is grown by the best one- or
 * three-step extension at either end (three, so a single poor tick does not end a lane), every
 * grown path is scored, and the best {@code F} wins. A found path's tau range on its edge
 * excludes overlapping candidates, so the set that comes back is disjoint.
 * <p>
 * Runs on whatever tau it is handed: the map-wide clock, or a route's own ({@link RouteClock}).
 * Self-inverse edges hold the other direction of travel in every {@code E⊥S}, which inflates
 * their footprint; they are excluded by the caller until phase-complete pathing resolves them.
 * What comes out is a path, not a phase-complete subpath, so it is drawn and reported rather
 * than turned into a {@link DecisionZone}.
 */
public final class SubpathSearch {
    private SubpathSearch() {}

    public enum Kind {
        /** Gains more tau per tick than one. */
        SHORTCUT(+1),
        /** Gains less. */
        LONGCUT(-1);

        final int sign;

        Kind(int sign) { this.sign = sign; }
    }

    /** Steps in a seed run. Long enough to be a rate rather than an outlier, short enough to be local. */
    public static final int SEED_STEPS = 6;

    /** Seeds grown per edge, disjoint in tau. */
    public static final int SEEDS_PER_EDGE = 4;

    /** The longer of the two extensions tried at each end. */
    public static final int CHUNK = 3;

    /**
     * One found path.
     *
     * @param path      the states, {@code P_0 … P_N}
     * @param footprint {@code |⋃ᵢ (E⊥Sᵢ ∪ Sᵢ)|}
     * @param fitness   {@code F} at the length it settled at
     * @param seedRate  gain per step of the run it grew from
     * @param trace     {@code F} after each extension, seed first
     */
    public record Found(Kind kind, int edge, int[] path, double tau0, double tauN, int footprint,
                        double fitness, double seedRate, double[] trace) {
        public int steps() { return path.length - 1; }

        /** Tau gained beyond one per tick, signed for the kind. */
        public double gain() { return kind.sign * (tauN - tau0 - steps()); }

        public double lo() { return Math.min(tau0, tauN); }

        public double hi() { return Math.max(tau0, tauN); }

        public String summary() {
            return String.format("%-8s edge %d: %3d steps, tau %6.1f -> %6.1f (%+.2f beyond one a tick,"
                            + " %+.3f/tick), footprint %6d, F = %.5f, seed %+.3f/tick", kind, edge,
                    steps(), tau0, tauN, kind.sign * gain(), kind.sign * gain() / steps(),
                    footprint, fitness, seedRate);
        }
    }

    /** The kernel and a clock, whichever clock it is. */
    private static final class Ground {
        final NavMap map;
        final int[] edgeOf, live;
        /** Per state, the region a footprint is measured within: the edge on the map-wide clock, the
         * whole route on a route's own, where the only boundary is the cut. */
        final int[] region;
        /** Transitions a footprint closure may not cross: the route's cut, {@code (from edge, to edge)}. */
        final int cutFrom, cutTo;
        final int liveCount;
        final double[] tau, lo, hi;
        final int turns, width;
        /** Scratch for closures: a stamp per state, so nothing is cleared between walks. */
        final int[] stamp;
        int version;
        final int[] queue;

        Ground(NavMap map, int[] edgeOf, int[] region, int cutFrom, int cutTo, int[] live, int liveCount,
               double[] tau, double[] lo, double[] hi) {
            this.map = map;
            this.edgeOf = edgeOf;
            this.region = region;
            this.cutFrom = cutFrom;
            this.cutTo = cutTo;
            this.live = live;
            this.liveCount = liveCount;
            this.tau = tau;
            this.lo = lo;
            this.hi = hi;
            this.turns = Params.TURNS;
            this.width = map.width();
            this.stamp = new int[edgeOf.length];
            this.queue = new int[edgeOf.length];
        }

        /** The gain of one transition, signed for the kind. */
        double gain(Kind kind, int s, int u) {
            return kind.sign * (tau[u] - tau[s] - 1);
        }

        boolean usable(int s, int e) {
            return edgeOf[s] == e && !Double.isNaN(tau[s]);
        }

        /** The turn that takes {@code s} to {@code u} as a permitted transition, or 2 if none. */
        int turnTo(int s, int u) {
            int d = s % turns, cell = s / turns, x = cell % width, y = cell / width;
            for (int t = -1; t <= 1; t++) {
                if (map.constrainTurn(x, y, d, t) == t && map.successor(s, t) == u) return t;
            }
            return 2;
        }

        /** The partial tick of {@code s → u}: the sweep at the new heading, landing included, on the edge. */
        int[] partialTick(int s, int u, int e) {
            int t = turnTo(s, u);
            int d = s % turns, cell = s / turns, x = cell % width, y = cell / width;
            int nd = Math.floorMod(d + t, turns);
            int[] sweep = map.stepPath(nd);
            int[] out = new int[sweep.length / 2];
            int n = 0;
            for (int q = 0; q + 1 < sweep.length; q += 2) {
                int mx = x + sweep[q], my = y + sweep[q + 1];
                if (mx < 0 || my < 0 || mx >= width || my >= map.height() || !map.alive(mx, my, nd)) continue;
                int st = map.index(mx, my, nd);
                if (region[st] == region[s]) out[n++] = st;
            }
            return Arrays.copyOf(out, n);
        }

        /** Whether {@code s → u} crosses the cut, which no footprint closure may. */
        boolean cut(int s, int u) {
            return cutFrom >= 0 && edgeOf[s] == cutFrom && edgeOf[u] == cutTo;
        }

        /** Stamps everything reachable from {@code seeds} within their region, one way, with {@code mark}. */
        void close(int[] seeds, int e, boolean forward, int mark) {
            int r = region[seeds[0]];
            int head = 0, tail = 0;
            int[] out = new int[3];
            for (int s : seeds) {
                if (stamp[s] == mark) continue;
                stamp[s] = mark;
                queue[tail++] = s;
            }
            while (head < tail) {
                int s = queue[head++];
                int k = forward ? map.steeredSuccessors(s, out) : map.steeredPredecessors(s, out);
                for (int j = 0; j < k; j++) {
                    int u = out[j];
                    if (region[u] != r || stamp[u] == mark) continue;
                    if (forward ? cut(s, u) : cut(u, s)) continue;
                    stamp[u] = mark;
                    queue[tail++] = u;
                }
            }
        }

        /** The states level with one step — {@code E⊥S ∪ S} — not yet in {@code footprint}. */
        int[] slab(int e, int[] s, boolean[] footprint) {
            int before = ++version;
            close(s, e, false, before);
            int after = ++version;
            close(s, e, true, after);
            int inS = ++version;
            for (int st : s) stamp[st] = inS;
            int[] out = new int[liveCount];
            int n = 0;
            for (int i = 0; i < liveCount; i++) {
                int st = live[i];
                if (region[st] != region[s[0]] || footprint[st]) continue;
                int m = stamp[st];
                if (m == inS || (m != before && m != after)) out[n++] = st;
            }
            return Arrays.copyOf(out, n);
        }
    }

    /** A path under construction, with its footprint. */
    private static final class Growth {
        final int edge;
        final List<Integer> path = new ArrayList<>();
        final boolean[] footprint;
        int footprintSize;
        double gain;
        final List<Double> trace = new ArrayList<>();

        Growth(int edge, int states) {
            this.edge = edge;
            this.footprint = new boolean[states];
        }

        double fitness() { return footprintSize == 0 ? 0 : gain / footprintSize; }

        boolean has(int s) { return path.contains(s); }
    }

    /** A candidate extension, tried against the footprint and rolled back if not taken. */
    private static final class Extension {
        final int[] states;         // in path order, excluding the end they attach to
        final boolean forward;
        final double gain;
        final List<int[]> added = new ArrayList<>();
        double fitness = Double.NEGATIVE_INFINITY;

        Extension(int[] states, boolean forward, double gain) {
            this.states = states;
            this.forward = forward;
            this.gain = gain;
        }
    }

    /** Applies the extension's slabs to the footprint, records what was added, and scores it. */
    private static void tryOn(Ground g, Growth gr, Extension x) {
        int at = x.forward ? gr.path.get(gr.path.size() - 1) : gr.path.get(0);
        int[] chain = x.states;
        for (int i = 0; i < chain.length; i++) {
            int from = x.forward ? (i == 0 ? at : chain[i - 1]) : chain[i];
            int to = x.forward ? chain[i] : (i + 1 < chain.length ? chain[i + 1] : at);
            int[] slab = g.slab(gr.edge, g.partialTick(from, to, gr.edge), gr.footprint);
            for (int st : slab) gr.footprint[st] = true;
            gr.footprintSize += slab.length;
            x.added.add(slab);
        }
        x.fitness = gr.footprintSize == 0 ? 0 : (gr.gain + x.gain) / gr.footprintSize;
    }

    private static void rollBack(Growth gr, Extension x) {
        for (int[] slab : x.added) {
            for (int st : slab) gr.footprint[st] = false;
            gr.footprintSize -= slab.length;
        }
        x.added.clear();
    }

    private static void keep(Growth gr, Extension x) {
        gr.gain += x.gain;
        if (x.forward) {
            for (int s : x.states) gr.path.add(s);
        } else {
            for (int i = x.states.length - 1; i >= 0; i--) gr.path.add(0, x.states[i]);
        }
    }

    /**
     * The best chain of {@code n} steps off one end by total gain, or null if there is none:
     * forward, successors of the head; backward, predecessors of the tail, listed in path order.
     */
    private static Extension bestChain(Ground g, Growth gr, Kind kind, int n, boolean forward) {
        int at = forward ? gr.path.get(gr.path.size() - 1) : gr.path.get(0);
        int[] best = null;
        double bestGain = Double.NEGATIVE_INFINITY;
        int[] chain = new int[n];
        int[][] outs = new int[n][3];
        int[] counts = new int[n], at2 = new int[n];
        // Depth-first over at most 3^n chains, which is 27 at n = 3.
        int depth = 0;
        int from = at;
        counts[0] = forward ? g.map.steeredSuccessors(from, outs[0]) : g.map.steeredPredecessors(from, outs[0]);
        at2[0] = 0;
        while (depth >= 0) {
            if (at2[depth] >= counts[depth]) { depth--; continue; }
            int s = outs[depth][at2[depth]++];
            int prev = depth == 0 ? at : chain[depth - 1];
            if (!g.usable(s, gr.edge) || gr.has(s)) continue;
            boolean repeat = false;
            for (int i = 0; i < depth && !repeat; i++) repeat = chain[i] == s;
            if (repeat) continue;
            chain[depth] = s;
            if (depth == n - 1) {
                double gain = 0;
                for (int i = 0; i < n; i++) {
                    int a = i == 0 ? at : chain[i - 1], b = chain[i];
                    gain += forward ? g.gain(kind, a, b) : g.gain(kind, b, a);
                }
                if (gain > bestGain) { bestGain = gain; best = chain.clone(); }
            } else {
                depth++;
                counts[depth] = forward ? g.map.steeredSuccessors(s, outs[depth])
                        : g.map.steeredPredecessors(s, outs[depth]);
                at2[depth] = 0;
            }
        }
        if (best == null) return null;
        if (!forward) {
            // Found tail-first; a backward extension is listed in path order, furthest first.
            int[] ordered = new int[n];
            for (int i = 0; i < n; i++) ordered[i] = best[n - 1 - i];
            best = ordered;
        }
        return new Extension(best, forward, bestGain);
    }

    /** A seed run: {@code K} steps ending at a state, with its gain. */
    private record Seed(int edge, int[] path, double gain) {}

    /**
     * The best {@code K}-step runs on edge {@code e} by gain per step, disjoint in tau, within
     * {@code margin} ticks of neither end of the edge's stretch of the clock: a dynamic programme
     * over the edge's transitions.
     */
    private static List<Seed> seeds(Ground g, Kind kind, int e, int margin, int perEdge) {
        int n = 0;
        int[] on = new int[g.liveCount];
        for (int i = 0; i < g.liveCount; i++) if (g.usable(g.live[i], e)) on[n++] = g.live[i];
        on = Arrays.copyOf(on, n);
        int[] pos = new int[g.edgeOf.length];
        Arrays.fill(pos, -1);
        for (int i = 0; i < n; i++) pos[on[i]] = i;

        int K = SEED_STEPS;
        double[][] best = new double[K + 1][n];
        int[][] back = new int[K + 1][n];
        for (double[] row : best) Arrays.fill(row, Double.NEGATIVE_INFINITY);
        Arrays.fill(best[0], 0);
        int[] preds = new int[3];
        for (int k = 1; k <= K; k++) {
            for (int i = 0; i < n; i++) {
                int u = on[i];
                int c = g.map.steeredPredecessors(u, preds);
                for (int j = 0; j < c; j++) {
                    int p = preds[j];
                    if (pos[p] < 0 || best[k - 1][pos[p]] == Double.NEGATIVE_INFINITY) continue;
                    double v = best[k - 1][pos[p]] + g.gain(kind, p, u);
                    if (v > best[k][i]) { best[k][i] = v; back[k][i] = pos[p]; }
                }
            }
        }

        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Double.compare(best[K][b], best[K][a]));
        List<Seed> out = new ArrayList<>();
        for (int idx : order) {
            if (best[K][idx] == Double.NEGATIVE_INFINITY) break;
            int[] path = new int[K + 1];
            int at = idx;
            for (int k = K; k >= 0; k--) {
                path[k] = on[at];
                if (k > 0) at = back[k][at];
            }
            double lo = Math.min(g.tau[path[0]], g.tau[path[K]]), hi = Math.max(g.tau[path[0]], g.tau[path[K]]);
            if (lo < g.lo[e] + margin || hi > g.hi[e] - margin) continue;
            boolean clash = false;
            for (Seed s : out) {
                double slo = Math.min(g.tau[s.path()[0]], g.tau[s.path()[K]]);
                double shi = Math.max(g.tau[s.path()[0]], g.tau[s.path()[K]]);
                if (hi >= slo && lo <= shi) { clash = true; break; }
            }
            if (clash) continue;
            out.add(new Seed(e, path, best[K][idx]));
            if (out.size() >= perEdge) break;
        }
        return out;
    }

    /** Grows one seed to the length that maximises {@code F}. */
    private static Found grow(Ground g, Kind kind, Seed seed) {
        Growth gr = new Growth(seed.edge(), g.edgeOf.length);
        for (int s : seed.path()) gr.path.add(s);
        gr.gain = seed.gain();
        for (int i = 0; i + 1 < seed.path().length; i++) {
            int[] slab = g.slab(gr.edge, g.partialTick(seed.path()[i], seed.path()[i + 1], gr.edge), gr.footprint);
            for (int st : slab) gr.footprint[st] = true;
            gr.footprintSize += slab.length;
        }
        gr.trace.add(gr.fitness());

        for (int round = 0; round < 2000; round++) {
            Extension best = null;
            for (boolean forward : new boolean[]{true, false}) {
                for (int n : new int[]{1, CHUNK}) {
                    Extension x = bestChain(g, gr, kind, n, forward);
                    if (x == null) continue;
                    tryOn(g, gr, x);
                    rollBack(gr, x);
                    if (best == null || x.fitness > best.fitness) best = x;
                }
            }
            if (best == null || best.fitness <= gr.fitness()) break;
            tryOn(g, gr, best);
            keep(gr, best);
            gr.trace.add(gr.fitness());
        }
        int[] path = gr.path.stream().mapToInt(Integer::intValue).toArray();
        double[] trace = gr.trace.stream().mapToDouble(Double::doubleValue).toArray();
        return new Found(kind, gr.edge, path, g.tau[path[0]], g.tau[path[path.length - 1]],
                gr.footprintSize, gr.fitness(), seed.gain() / SEED_STEPS, trace);
    }

    /**
     * Grows every seed on every edge and returns the grown paths, best {@code F} first, with
     * the {@code count} winners — disjoint in tau on any one edge — at the front.
     *
     * @param edges  the edges to search
     * @param skip   edges to leave alone, self-inverse ones for now
     * @param region per state, what a footprint is measured within — the edge on the map-wide
     *               clock; the whole route on a route's own, whose one boundary is the cut
     * @param cutFrom the edge the cut is left from, and {@code cutTo} the edge it enters, or -1
     * @param lo     per edge, where its stretch of the clock begins; {@code hi} where it ends
     * @param margin ticks of the clock from either end of an edge a seed may not lie within; 0
     *               for none, which on a route's own clock is the honest setting, there being no
     *               edge boundaries in that clock to keep away from
     */
    public static List<Found> find(NavMap map, int[] edgeOf, int[] region, int cutFrom, int cutTo,
                                   int[] live, int liveCount, double[] tau, double[] lo, double[] hi,
                                   int[] edges, int[] skip, Kind kind, int count, int margin) {
        Ground g = new Ground(map, edgeOf, region, cutFrom, cutTo, live, liveCount, tau, lo, hi);
        List<Found> grown = new ArrayList<>();
        for (int e : edges) {
            if (Arrays.stream(skip).anyMatch(x -> x == e)) continue;
            for (Seed s : seeds(g, kind, e, margin, SEEDS_PER_EDGE)) grown.add(grow(g, kind, s));
        }
        grown.sort(Comparator.comparingDouble((Found f) -> -f.fitness()));
        List<Found> out = new ArrayList<>();
        List<Found> rest = new ArrayList<>();
        for (Found f : grown) {
            boolean clash = false;
            for (Found w : out) {
                if (w.edge() == f.edge() && f.hi() >= w.lo() && f.lo() <= w.hi()) { clash = true; break; }
            }
            if (!clash && out.size() < count) out.add(f); else rest.add(f);
        }
        out.addAll(rest);
        return out;
    }

    /** The same on a solver's map-wide clock, over every edge. */
    public static List<Found> find(NavMap map, SolverFacts f, int[] skip, Kind kind, int count, int margin) {
        DecisionZone.Kernel k = DecisionZone.Kernel.of(f);
        int[] edges = new int[f.edges()];
        for (int e = 0; e < edges.length; e++) edges[e] = e;
        return find(map, k.edgeOf(), k.edgeOf(), -1, -1, k.live(), k.liveCount(), f.tickOf(),
                new double[f.edges()], f.length(), edges, skip, kind, count, margin);
    }

    /**
     * The same on a route's own clock, over the route's edges, with the footprint measured over
     * the whole route and bounded only by the cut — the crossing from the route's last edge into
     * its first. Within a decomposition edge the footprint of a step near the edge's end is
     * otherwise cut short by the boundary, which halves the denominator and doubles {@code F} for
     * paths that hug a crossing; on the route there is no boundary there.
     */
    public static List<Found> find(NavMap map, RouteClock.Fit fit, int[] skip, Kind kind, int count,
                                   int margin) {
        double[] hi = new double[fit.lo().length];
        for (int e = 0; e < hi.length; e++) hi[e] = fit.lo()[e] + fit.length()[e];
        int[] region = new int[fit.edgeOf().length];
        Arrays.fill(region, -1);
        for (int s : fit.live()) region[s] = 0;
        int m = fit.route().length;
        return find(map, fit.edgeOf(), region, fit.route()[m - 1], fit.route()[0], fit.live(),
                fit.liveCount(), fit.tau(), fit.lo(), hi, fit.route(), skip, kind, count, margin);
    }

    /** The colours the paths are drawn in: warm for shortcuts, cool for longcuts. */
    private static final Color[] WARM = {new Color(0xE6194B), new Color(0xF58231), new Color(0xFFE119)};
    private static final Color[] COOL = {new Color(0x4363D8), new Color(0x42D4F4), new Color(0x911EB4)};

    /**
     * Draws the paths over the map's display image at {@code scale}, each as a polyline with a
     * ring at its start, and writes it.
     */
    public static void draw(PresetScenarioParameter preset, NavMap map, List<Found> paths, int scale,
                            Path out) throws IOException {
        BufferedImage base = javax.imageio.ImageIO.read(preset.ingest().dir().resolve("display.png").toFile());
        int w = base.getWidth(), h = base.getHeight();
        BufferedImage img = new BufferedImage(w * scale, h * scale, BufferedImage.TYPE_INT_RGB);
        Graphics2D gfx = img.createGraphics();
        gfx.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        gfx.drawImage(base, 0, 0, w * scale, h * scale, null);
        gfx.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int warm = 0, cool = 0;
        int turns = Params.TURNS;
        for (Found f : paths) {
            Color c = f.kind() == Kind.SHORTCUT ? WARM[warm++ % WARM.length] : COOL[cool++ % COOL.length];
            gfx.setColor(c);
            gfx.setStroke(new BasicStroke(Math.max(1.5f, scale * 0.9f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            int[] p = f.path();
            for (int i = 0; i + 1 < p.length; i++) {
                int a = p[i] / turns, b = p[i + 1] / turns;
                gfx.drawLine((a % w) * scale + scale / 2, (a / w) * scale + scale / 2,
                        (b % w) * scale + scale / 2, (b / w) * scale + scale / 2);
            }
            int s = p[0] / turns;
            int r = scale * 3;
            gfx.setStroke(new BasicStroke(Math.max(1f, scale * 0.5f)));
            gfx.drawOval((s % w) * scale + scale / 2 - r, (s / w) * scale + scale / 2 - r, 2 * r, 2 * r);
        }
        gfx.dispose();
        Files.createDirectories(out.getParent());
        javax.imageio.ImageIO.write(img, "png", out.toFile());
    }
}
