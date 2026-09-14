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
 * <p>
 * <b>Grown greedily from the best single tick.</b> The seed is the on-edge transition spanning
 * the most tau (least, for a longcut). Each round the best transition off either end — the
 * successor of {@code P_N}, or the predecessor of {@code P_0}, with the largest gain — is scored
 * as an extension; the better of the two is taken if it raises {@code F}, and the path is done
 * when neither does. A found path's tau range on its edge is excluded from seeding the next of
 * its kind; growing into it is allowed, to see whether that happens.
 * <p>
 * What comes out is a path, not a phase-complete subpath, so it is drawn and reported rather
 * than turned into a {@link DecisionZone}. Every tau is the map-wide clock's.
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

    /**
     * One found path.
     *
     * @param path      the states, {@code P_0 … P_N}
     * @param footprint {@code |⋃ᵢ (E⊥Sᵢ ∪ Sᵢ)|}
     * @param fitness   {@code F} at the length it settled at
     * @param trace     {@code F} after each extension, seed first
     */
    public record Found(Kind kind, int edge, int[] path, double tau0, double tauN, int footprint,
                        double fitness, double[] trace) {
        public int steps() { return path.length - 1; }

        /** Tau gained beyond one per tick, signed for the kind. */
        public double gain() { return kind.sign * (tauN - tau0 - steps()); }

        public String summary() {
            return String.format("%-8s edge %d: %3d steps, tau %6.1f -> %6.1f (%+.2f beyond one a tick),"
                            + " footprint %6d, F = %.5f", kind, edge, steps(), tau0, tauN,
                    kind.sign * gain(), footprint, fitness);
        }
    }

    /** The map's kernel and clock, shared across finds. */
    private static final class Ground {
        final NavMap map;
        final int[] edgeOf, live;
        final int liveCount;
        final double[] tau;
        final int turns, width;
        /** Scratch for closures: a stamp per state, so nothing is cleared between walks. */
        final int[] stamp;
        int version;
        final int[] queue;

        Ground(NavMap map, SolverFacts f) {
            this.map = map;
            DecisionZone.Kernel k = DecisionZone.Kernel.of(f);
            this.edgeOf = k.edgeOf();
            this.live = k.live();
            this.liveCount = k.liveCount();
            this.tau = f.tickOf();
            this.turns = Params.TURNS;
            this.width = map.width();
            this.stamp = new int[edgeOf.length];
            this.queue = new int[edgeOf.length];
        }

        /** The gain of one transition, signed for the kind; NaN where either end has no tau. */
        double gain(Kind kind, int s, int u) {
            return kind.sign * (tau[u] - tau[s] - 1);
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
                if (edgeOf[st] == e) out[n++] = st;
            }
            return Arrays.copyOf(out, n);
        }

        /**
         * Stamps everything reachable from {@code seeds} within edge {@code e}, forwards or
         * backwards, with {@code mark}; returns how many were stamped, seeds included.
         */
        int close(int[] seeds, int e, boolean forward, int mark) {
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
                    if (edgeOf[u] != e || stamp[u] == mark) continue;
                    stamp[u] = mark;
                    queue[tail++] = u;
                }
            }
            return tail;
        }
    }

    /** A path under construction, with its footprint. */
    private static final class Growth {
        final Kind kind;
        final int edge;
        final List<Integer> path = new ArrayList<>();
        final boolean[] footprint;
        int footprintSize;
        double gain;
        final List<Double> trace = new ArrayList<>();

        Growth(Kind kind, int edge, int states) {
            this.kind = kind;
            this.edge = edge;
            this.footprint = new boolean[states];
        }

        double fitness() { return footprintSize == 0 ? 0 : gain / footprintSize; }
    }

    /**
     * The states level with one step — {@code E⊥S ∪ S} — that are not yet in the footprint.
     * Stamps them with a fresh mark and returns them, so a rejected candidate costs nothing.
     */
    private static int[] slab(Ground g, int e, int[] s, boolean[] footprint) {
        int before = ++g.version;
        g.close(s, e, false, before);
        int after = ++g.version;
        g.close(s, e, true, after);
        int inS = ++g.version;
        for (int st : s) g.stamp[st] = inS;
        // Stamping S last means a state of S carries inS; the two closures include S too, so a
        // state is level with S exactly when it carries neither closure's mark, or carries inS.
        int[] out = new int[g.liveCount];
        int n = 0;
        for (int i = 0; i < g.liveCount; i++) {
            int st = g.live[i];
            if (g.edgeOf[st] != e || footprint[st]) continue;
            int m = g.stamp[st];
            if (m == inS || (m != before && m != after)) out[n++] = st;
        }
        return Arrays.copyOf(out, n);
    }

    /**
     * Grows the {@code count} best paths of one kind, each seeded outside the tau ranges of
     * those before it.
     */
    public static List<Found> find(NavMap map, SolverFacts f, Kind kind, int count) {
        return find(map, f, kind, count, 0);
    }

    /**
     * The same, seeding only at least {@code margin} ticks of tau from either end of an edge.
     * <p>
     * With no margin the seeds land on the clock's boundary artifacts — a tick at tau 0.3 of an
     * edge, or past its length at the far end — where tau is least trustworthy and a single
     * tick can read as 1.7 tau. The interior is where a lane is a lane.
     */
    public static List<Found> find(NavMap map, SolverFacts f, Kind kind, int count, double margin) {
        Ground g = new Ground(map, f);
        List<Found> found = new ArrayList<>();
        int[] out = new int[3];
        for (int round = 0; round < count; round++) {
            // The seed: the on-edge transition with the most gain, outside every found range.
            int seedFrom = -1, seedTo = -1;
            double best = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < g.liveCount; i++) {
                int s = g.live[i];
                int e = g.edgeOf[s];
                if (Double.isNaN(g.tau[s]) || excluded(found, e, g.tau[s])) continue;
                if (g.tau[s] < margin || g.tau[s] > f.length()[e] - margin) continue;
                int k = map.steeredSuccessors(s, out);
                for (int j = 0; j < k; j++) {
                    int u = out[j];
                    if (g.edgeOf[u] != e || Double.isNaN(g.tau[u])) continue;
                    double gain = g.gain(kind, s, u);
                    if (gain > best) { best = gain; seedFrom = s; seedTo = u; }
                }
            }
            if (seedFrom < 0) break;
            int e = g.edgeOf[seedFrom];

            Growth gr = new Growth(kind, e, g.edgeOf.length);
            gr.path.add(seedFrom);
            gr.path.add(seedTo);
            gr.gain = best;
            commit(g, gr, slab(g, e, g.partialTick(seedFrom, seedTo, e), gr.footprint));
            gr.trace.add(gr.fitness());

            // Grow at whichever end raises F more, until neither does.
            for (int step = 0; step < 2000; step++) {
                int head = gr.path.get(gr.path.size() - 1), tail = gr.path.get(0);
                int fwd = -1, bwd = -1;
                double fwdGain = Double.NEGATIVE_INFINITY, bwdGain = Double.NEGATIVE_INFINITY;
                int k = map.steeredSuccessors(head, out);
                for (int j = 0; j < k; j++) {
                    int u = out[j];
                    if (g.edgeOf[u] != e || Double.isNaN(g.tau[u]) || gr.path.contains(u)) continue;
                    double gain = g.gain(kind, head, u);
                    if (gain > fwdGain) { fwdGain = gain; fwd = u; }
                }
                k = map.steeredPredecessors(tail, out);
                for (int j = 0; j < k; j++) {
                    int p = out[j];
                    if (g.edgeOf[p] != e || Double.isNaN(g.tau[p]) || gr.path.contains(p)) continue;
                    double gain = g.gain(kind, p, tail);
                    if (gain > bwdGain) { bwdGain = gain; bwd = p; }
                }
                int[] fwdSlab = fwd < 0 ? null : slab(g, e, g.partialTick(head, fwd, e), gr.footprint);
                int[] bwdSlab = bwd < 0 ? null : slab(g, e, g.partialTick(bwd, tail, e), gr.footprint);
                double fwdF = fwd < 0 ? Double.NEGATIVE_INFINITY
                        : (gr.gain + fwdGain) / (gr.footprintSize + fwdSlab.length);
                double bwdF = bwd < 0 ? Double.NEGATIVE_INFINITY
                        : (gr.gain + bwdGain) / (gr.footprintSize + bwdSlab.length);
                double now = gr.fitness();
                if (Math.max(fwdF, bwdF) <= now) break;
                if (fwdF >= bwdF) {
                    gr.path.add(fwd);
                    gr.gain += fwdGain;
                    commit(g, gr, fwdSlab);
                } else {
                    gr.path.add(0, bwd);
                    gr.gain += bwdGain;
                    commit(g, gr, bwdSlab);
                }
                gr.trace.add(gr.fitness());
            }

            int[] path = gr.path.stream().mapToInt(Integer::intValue).toArray();
            double[] trace = gr.trace.stream().mapToDouble(Double::doubleValue).toArray();
            found.add(new Found(kind, e, path, g.tau[path[0]], g.tau[path[path.length - 1]],
                    gr.footprintSize, gr.fitness(), trace));
        }
        return found;
    }

    private static void commit(Ground g, Growth gr, int[] slab) {
        for (int st : slab) {
            if (!gr.footprint[st]) { gr.footprint[st] = true; gr.footprintSize++; }
        }
    }

    /** Whether a tau on an edge lies within a found path's range there. */
    private static boolean excluded(List<Found> found, int e, double tau) {
        for (Found f : found) {
            if (f.edge() != e) continue;
            double lo = Math.min(f.tau0(), f.tauN()), hi = Math.max(f.tau0(), f.tauN());
            if (tau >= lo && tau <= hi) return true;
        }
        return false;
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
