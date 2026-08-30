package boids;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * What three boids do to each other, as a map of the two phase differences between them.
 * <p>
 * The two-boid problem was small enough to answer exactly in {@code (x, y, d)} — {@link TwoBoid}
 * enumerates every reachable arrangement. Three boids is not, and it is also where the
 * interesting failures live: the residue {@link ExitAudit} cannot account for is multi-leader,
 * and a pairwise table cannot represent it at any constants. So this samples rather than
 * enumerates, and it aggregates into {@code (edge, tau)} because that is the only space in which
 * "where is the other boid relative to me" is a single number.
 * <p>
 * <b>Two axes, both phase differences in ticks.</b> Everything is rebased onto the edge the
 * suspect is leaving, by the shortest route, so a boid anywhere on the map reduces to one
 * coordinate: how far ahead or behind the suspect it is along the route. The psyboid supplies
 * one axis and the third boid the other. A cell is sampled once — the first arrangement to land
 * on it decides its colour — because the point is coverage of the plane rather than statistics
 * within a cell.
 * <p>
 * <b>What the picture is expected to show.</b> Bands, horizontal and vertical, where one boid
 * alone induces the exit. A thickening where those bands cross, from arrangements that combine
 * an alignment leader with a separation one. Growths off the bands, which is what a baton pass
 * looks like when the leader changes partway through. And any region unattached to a band is
 * behaviour that exists only with three boids.
 * <p>
 * <b>This is not yet map-independent.</b> The override the psyboid flies is a permanent right
 * turn, which is the useful thing to hold on a dab-like map and is not general. See
 * {@code ROADMAP.md}.
 */
public final class ThreeBoidPhase {
    private ThreeBoidPhase() {}

    /** Index in the flock. The suspect decides last, so both others have already moved. */
    private static final int PSYBOID = 0, OTHER = 1, SUSPECT = 2;

    /** Long enough to be permanent over any run this samples. */
    private static final int FOREVER = 1 << 20;

    /** How long a single trial may run before its arrangement is abandoned as inconclusive. */
    private static final int PATIENCE = 2000;

    /**
     * What became of the suspect, and why.
     *
     * @param reason the account {@link ExitAudit} gave, or {@code null} if it did not exit
     */
    public record Cell(int x, int y, boolean exited, ExitAudit.Reason reason) {}

    /**
     * Samples the plane and writes the picture.
     *
     * @param band       width in ticks of the tau window the suspect starts in, centred on the
     *                   middle of its edge
     * @param k          how many unsteered steps the third boid is carried forward from a random
     *                   state, which keeps it on a coasting trajectory rather than somewhere only
     *                   steering could have put it
     * @param resolution ticks per cell on both axes
     */
    public static void run(PresetScenarioParameter preset, SimTest.Labelling l, SolverFacts f,
                           ExitAudit.Tables tables, int from, int keep, double band, int k,
                           double resolution, int attempts, long seed, Path out)
            throws IOException {
        NavMap map = l.map();
        int[] edge = l.edge(), live = l.live();
        int liveCount = l.liveCount();

        boolean[] settled = CriticalEnvelope.settled(map, edge, live, liveCount, from);
        int[] starts = starts(map, edge, f, settled, live, liveCount, from, band);
        double[] rebase = rebase(f, from);

        System.out.printf("%n=== %s @%s: three-boid phase map, arc %d->%d ===%n", preset.name(),
                preset.ingest().hash(), from, keep);
        System.out.printf("%d suspect starts in a %.0f-tick band, one per phase; k=%d, "
                + "resolution %.0f, %,d attempts%n", starts.length, band, k, resolution, attempts);

        Boids2DEngine engine = new Boids2DEngine(preset);
        Random rng = new Random(seed);
        Map<Long, Cell> cells = new HashMap<>();
        int tried = 0, offMap = 0, inconclusive = 0;

        for (int a = 0; a < attempts; a++) {
            int suspect = starts[rng.nextInt(starts.length)];
            int other = coasted(map, live[rng.nextInt(liveCount)], k);
            int psy = live[rng.nextInt(liveCount)];
            if (other < 0) continue;

            double su = f.tickAt(x(map, suspect), y(map, suspect), h(suspect));
            double po = place(map, f, rebase, psy), bo = place(map, f, rebase, other);
            if (Double.isNaN(po) || Double.isNaN(bo) || Double.isNaN(su)) { offMap++; continue; }

            int cx = (int) Math.round((po - su) / resolution);
            int cy = (int) Math.round((bo - su) / resolution);
            long key = ((long) cx << 32) | (cy & 0xFFFFFFFFL);
            if (cells.containsKey(key)) continue;

            tried++;
            // Half the arrangements get a psyboid that is genuinely steering, half a flock of
            // three ordinary boids. Both belong on the same picture: the question is what the
            // arrangement does, and an override is one of the things that can be true of it.
            PsyboidOverride[] overrides = rng.nextBoolean()
                    ? new PsyboidOverride[]{new PsyboidOverride(0, FOREVER, +1, PSYBOID)}
                    : new PsyboidOverride[0];

            Cell cell = trial(engine, map, edge, f, tables, from, keep, psy, other, suspect,
                    overrides, cx, cy);
            if (cell == null) { inconclusive++; continue; }
            cells.put(key, cell);
        }

        report(cells.values(), tried, offMap, inconclusive);
        draw(cells.values(), resolution, from, keep, out);
        System.out.printf("wrote %s%n", out);
    }

    /**
     * One arrangement, flown until the suspect leaves the edge.
     *
     * @return null if it never left, which is a fact about the arrangement rather than a failure
     */
    private static Cell trial(Boids2DEngine engine, NavMap map, int[] edge, SolverFacts f,
                              ExitAudit.Tables tables, int from, int keep, int psy, int other,
                              int suspect, PsyboidOverride[] overrides, int cx, int cy) {
        int[] xs = {x(map, psy), x(map, other), x(map, suspect)};
        int[] ys = {y(map, psy), y(map, other), y(map, suspect)};
        int[] hs = {h(psy), h(other), h(suspect)};
        Sim.State s = new Sim.State(3, xs, ys, hs, 0, 0, new long[3], "phase", overrides);

        ExitAudit audit = new ExitAudit(tables, overrides);
        audit.reset(3, overrides);
        engine.trace(audit);
        try {
            for (int t = 0; t < PATIENCE; t++) {
                s = engine.tick(s);
                int now = f.edgeAt(s.x[SUSPECT], s.y[SUSPECT], s.h[SUSPECT]);
                if (now == from) continue;
                boolean exited = now == keep;
                ExitAudit.Reason reason = null;
                for (ExitAudit.Exit e : audit.exits()) {
                    if (e.suspect() == SUSPECT && !e.reasons().isEmpty()) {
                        reason = e.reasons().get(0);
                    }
                }
                return new Cell(cx, cy, exited, reason);
            }
        } catch (RuntimeException e) {
            return null;                      // a boid left the image; the arrangement is not one
        } finally {
            engine.trace(null);
        }
        return null;
    }

    /**
     * One start per phase, in a tau band across the middle of the edge.
     * <p>
     * A ~4 px step means states a tick apart along one trajectory sit four pixels apart, and the
     * states between them belong to trajectories that never touch it. Keeping every settled state
     * in the band would therefore sample the same trajectory several times over. Dropping any
     * state whose own unsteered successor is also in the band keeps the last of each chain, which
     * is one representative per phase.
     */
    private static int[] starts(NavMap map, int[] edge, SolverFacts f, boolean[] settled,
                                int[] live, int liveCount, int from, double band) {
        double lo = f.tickLo()[from], hi = f.tickHi()[from];
        double mid = (lo + hi) / 2, half = band / 2;
        List<Integer> in = new ArrayList<>();
        for (int i = 0; i < liveCount; i++) {
            int s = live[i];
            if (edge[s] != from || !settled[s]) continue;
            double tau = f.tickAt(x(map, s), y(map, s), h(s));
            if (tau >= mid - half && tau <= mid + half) in.add(s);
        }
        boolean[] inBand = new boolean[edge.length];
        for (int s : in) inBand[s] = true;
        List<Integer> keep = new ArrayList<>();
        for (int s : in) {
            int u = map.successor(s, 0);
            if (u < 0 || !inBand[u]) keep.add(s);
        }
        int[] out = new int[keep.size()];
        for (int i = 0; i < out.length; i++) out[i] = keep.get(i);
        return out;
    }

    /**
     * The shortest route-sum from {@code from} to every edge, so any state can be expressed as a
     * distance along the suspect's own route.
     * <p>
     * Dijkstra over the edge graph, weighted by the length of the edge being left — which is the
     * same accumulation {@link EdgeDistance} makes, since lengths add and a route contributes the
     * length of everything on it except the last.
     */
    private static double[] rebase(SolverFacts f, int from) {
        int n = f.edges();
        List<List<Integer>> succ = new ArrayList<>();
        for (int e = 0; e < n; e++) succ.add(new ArrayList<>());
        for (int e = 0; e < n; e++) for (int p : f.predecessors(e)) succ.get(p).add(e);

        double[] dist = new double[n];
        java.util.Arrays.fill(dist, Double.POSITIVE_INFINITY);
        dist[from] = 0;
        boolean[] done = new boolean[n];
        for (int round = 0; round < n; round++) {
            int at = -1;
            for (int e = 0; e < n; e++) if (!done[e] && (at < 0 || dist[e] < dist[at])) at = e;
            if (at < 0 || Double.isInfinite(dist[at])) break;
            done[at] = true;
            for (int v : succ.get(at)) {
                double through = dist[at] + f.length()[at];
                if (through < dist[v]) dist[v] = through;
            }
        }
        return dist;
    }

    /** A state's position measured along the suspect's route, or NaN if it is not on one. */
    private static double place(NavMap map, SolverFacts f, double[] rebase, int state) {
        int e = f.edgeAt(x(map, state), y(map, state), h(state));
        if (e < 0 || Double.isInfinite(rebase[e])) return Double.NaN;
        return f.tickAt(x(map, state), y(map, state), h(state)) + rebase[e];
    }

    /** Where a boid ends up after coasting {@code k} ticks, or -1 if it leaves the map. */
    private static int coasted(NavMap map, int state, int k) {
        int at = state;
        for (int i = 0; i < k; i++) {
            at = map.successor(at, 0);
            if (at < 0) return -1;
        }
        return at;
    }

    private static int x(NavMap map, int state) { return (state / Params.TURNS) % map.width(); }
    private static int y(NavMap map, int state) { return (state / Params.TURNS) / map.width(); }
    private static int h(int state) { return state % Params.TURNS; }

    // ---- output -------------------------------------------------------------

    private static final int GROUND = 0x14171C;
    private static final int AXIS = 0x39404D;
    private static final int TEXT = 0xB9C1CE;
    private static final int CONTINUED = 0x2A2F38;

    /**
     * One colour per account, tonally close so that structure reads rather than any one class
     * shouting — except an exit nothing explains, which is the whole reason for looking.
     */
    private static int colour(Cell c) {
        if (!c.exited()) return CONTINUED;
        if (c.reason() == null) return 0xFFFFFF;                    // nothing accounts for it
        if (c.reason().level() == ExitAudit.Level.PSYBOID) return 0x7A6BB5;
        boolean byPsyboid = c.reason().leader() == PSYBOID;
        boolean widened = c.reason().level() == ExitAudit.Level.ENVELOPE_WIDENED;
        if (byPsyboid) return widened ? 0xB5734A : 0xC8913F;        // led by the psyboid
        return widened ? 0x3F7FA8 : 0x4FA8C8;                       // led by the third boid
    }

    private static void report(java.util.Collection<Cell> cells, int tried, int offMap,
                               int inconclusive) {
        int exits = 0, none = 0, byPsy = 0, byOther = 0, wide = 0;
        for (Cell c : cells) {
            if (!c.exited()) continue;
            exits++;
            if (c.reason() == null) { none++; continue; }
            if (c.reason().level() == ExitAudit.Level.ENVELOPE_WIDENED) wide++;
            if (c.reason().leader() == PSYBOID) byPsy++; else byOther++;
        }
        System.out.printf("%,d cells filled from %,d trials (%,d off the route, %,d never left)%n",
                cells.size(), tried, offMap, inconclusive);
        System.out.printf("  %,d exited, %,d continued%n", exits, cells.size() - exits);
        System.out.printf("  of the exits: %,d led by the psyboid, %,d by the third boid, "
                + "%,d needed the diluted model, %,d UNEXPLAINED%n", byPsy, byOther, wide, none);
    }

    private static void draw(java.util.Collection<Cell> cells, double resolution, int from,
                             int keep, Path out) throws IOException {
        int lox = 0, hix = 0, loy = 0, hiy = 0;
        for (Cell c : cells) {
            lox = Math.min(lox, c.x()); hix = Math.max(hix, c.x());
            loy = Math.min(loy, c.y()); hiy = Math.max(hiy, c.y());
        }
        int w = hix - lox + 1, h = hiy - loy + 1;
        int scale = Math.max(1, Math.min(6, 1400 / Math.max(w, h)));
        int pad = 60, foot = 96;
        BufferedImage img = new BufferedImage(w * scale + pad * 2, h * scale + pad + foot,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(GROUND));
        g.fillRect(0, 0, img.getWidth(), img.getHeight());

        g.setColor(new Color(AXIS));
        int zx = pad + (0 - lox) * scale, zy = pad + (0 - loy) * scale;
        g.drawLine(zx, pad, zx, pad + h * scale);
        g.drawLine(pad, zy, pad + w * scale, zy);

        for (Cell c : cells) {
            g.setColor(new Color(colour(c)));
            g.fillRect(pad + (c.x() - lox) * scale, pad + (c.y() - loy) * scale, scale, scale);
        }

        g.setColor(new Color(TEXT));
        g.setFont(new Font("SansSerif", Font.BOLD, 14));
        g.drawString(String.format("three-boid phase map, arc %d->%d   (%.0f ticks per cell)",
                from, keep, resolution), pad, 26);
        g.setFont(new Font("SansSerif", Font.PLAIN, 12));
        g.drawString("x: psyboid tau minus suspect tau        y: third boid tau minus suspect tau",
                pad, 44);
        String[] key = {"exit, nothing accounts for it", "exit led by the psyboid",
                "exit led by the third boid", "diluted model needed", "suspect continued"};
        int[] swatch = {0xFFFFFF, 0xC8913F, 0x4FA8C8, 0xB5734A, CONTINUED};
        // Laid out from the width actually available rather than a fixed pitch, so a narrow
        // picture wraps to a second row instead of losing entries off the right edge.
        int usable = img.getWidth() - pad * 2;
        int perRow = Math.max(1, usable / 240);
        int pitch = usable / perRow;
        for (int i = 0; i < key.length; i++) {
            int col = i % perRow, row = i / perRow;
            int lx = pad + col * pitch, ly = pad + h * scale + 24 + row * 18;
            g.setColor(new Color(swatch[i]));
            g.fillRect(lx, ly - 9, 10, 10);
            g.setColor(new Color(TEXT));
            g.drawString(key[i], lx + 16, ly);
        }
        g.dispose();
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        javax.imageio.ImageIO.write(img, "png", out.toFile());
    }
}
