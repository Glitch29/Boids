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
import java.util.Arrays;
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
 * and no pairwise table represents that at any constants. So this samples rather than
 * enumerates, and aggregates into {@code (edge, tau)} because that is the only space in which
 * "where is the other boid relative to me" is a single number.
 *
 * <h2>Routes are part of the coordinate</h2>
 * There is no single lap length to wrap against — how long a lap takes depends on which way
 * round the boid went. So a sample lives in {@code (x, y, route, route)}: one route for the
 * psyboid, one for the third boid, each a <b>simple loop</b> from the suspect's edge back to
 * itself. A route <em>does</em> have a length, so within a panel the axes wrap cleanly and the
 * same phase relationship appears once instead of once per lap.
 * <p>
 * <b>Rebasing follows the route, not the shortest path.</b> A boid's position is its tau plus
 * the lengths of the route's edges before its own. The earlier shortest-path rebasing gave one
 * number per state, which silently mixed boids that were on the same edge for different reasons.
 * <p>
 * <b>One simulation can fill several cells.</b> Edges are shared between routes, so a boid on a
 * common stretch belongs to every route through it — and its phase difference is <em>different
 * in each</em>, because the offsets differ. The trial is run once and every panel it speaks to
 * is filled from it.
 * <p>
 * Edges outside every simple loop are dropped automatically, which is the intended behaviour:
 * on dabeone that is edge 6, reachable only during warmup and otherwise noise.
 *
 * <h2>Reading it</h2>
 * A vertical band is a phase at which the psyboid alone induces the exit; a horizontal band, the
 * third boid alone. Where they cross, arrangements that combine two influences. Growths off a
 * band are what a leader handover looks like. A region attached to neither is behaviour that
 * exists only with three boids.
 * <p>
 * <b>Cells are sampled once and never averaged.</b> Density shows as dithering, which carries
 * the actual data; a mean over a cell would invent a value no arrangement had.
 * <p>
 * <b>Not yet map-independent.</b> The override the psyboid flies is a permanent right turn,
 * which is the useful thing to hold on a dab-like map and is not general.
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
     * One sampled arrangement and what became of it.
     * <p>
     * The three start states and the override flag are carried so any cell in the picture can be
     * replayed exactly. A region on this map is a claim about arrangements, and a claim nobody
     * can re-run is one nobody can check.
     *
     * @param reason      the account {@link ExitAudit} gave, or null if the suspect did not exit
     * @param otherExited whether the <em>third</em> boid also took an exit during the trial,
     *                    which separates an arrangement that works directly from one that works
     *                    by first moving somebody else
     */
    public record Cell(int route, int otherRoute, int x, int y, boolean exited,
                       ExitAudit.Reason reason, boolean otherExited,
                       int psyStart, int otherStart, int suspectStart, boolean overridden) {}

    /** A simple loop from the suspect's edge back to itself, with where each edge sits on it. */
    public record Route(int[] edges, double[] offset, double length) {
        public String label() { return Arrays.toString(edges); }
    }

    /**
     * Samples the plane and writes the grid.
     *
     * <h3>How long it runs</h3>
     * Not a fixed attempt count and not a clock, because neither says anything about the thing
     * that matters, which is coverage. Instead {@code targetFill}: stop after
     * {@code 1 / (1 - targetFill)} consecutive attempts that landed only on cells already known.
     * At a true fill of {@code p} the chance of a fresh cell is {@code 1 - p}, so that many
     * misses in a row is about where {@code p} has been reached. It also costs nothing when part
     * of the grid is unreachable: an unfillable region never contributes hits, so the run simply
     * ends when the reachable part is done.
     *
     * <h3>Annealing k</h3>
     * A high {@code k} keeps the other boids on coasting trajectories, which is realistic and
     * also refuses to sample the early part of an unstable edge at all. In a larger flock a
     * fourth boid could put somebody there, so those regions are worth filling even though
     * reaching them here implicates recent steering. The first time the miss counter fills,
     * {@code k} drops to zero rather than the run ending; the second time, it ends. Coverage
     * from plausible placements first, then from any.
     *
     * @param band       width in ticks of the tau window the suspect starts in
     * @param kBoid      unsteered steps the third boid is carried on from a random state
     * @param kPsy       the same for the psyboid, advanced by its override's turn where it has
     *                   one, since a permanently right-steering psyboid does not coast
     * @param targetFill desired saturation of the reachable grid, in [0, 1)
     */
    public static void run(PresetScenarioParameter preset, SimTest.Labelling l, SolverFacts f,
                           ExitAudit.Tables tables, int from, int keep, double band, int kBoid,
                           int kPsy, double resolution, double targetFill, long seed, Path out)
            throws IOException {
        NavMap map = l.map();
        int[] edge = l.edge(), live = l.live();
        int liveCount = l.liveCount();

        List<Route> routes = loops(f, from);
        List<int[]>[] where = where(f, routes);
        boolean[] settled = CriticalEnvelope.settled(map, edge, live, liveCount, from);
        int[] starts = starts(map, edge, f, settled, live, liveCount, from, band);

        System.out.printf("%n=== %s @%s: three-boid phase map, arc %d->%d ===%n", preset.name(),
                preset.ingest().hash(), from, keep);
        for (int r = 0; r < routes.size(); r++) {
            System.out.printf("  route %d  %-22s length %.2f%n", r, routes.get(r).label(),
                    routes.get(r).length());
        }
        List<Integer> orphan = new ArrayList<>();
        for (int e = 0; e < f.edges(); e++) if (where[e].isEmpty()) orphan.add(e);
        System.out.printf("  edges on no loop, therefore never sampled: %s%n", orphan);
        long patience = (long) Math.ceil(1 / (1 - targetFill));
        System.out.printf("%d suspect starts in a %.0f-tick band; kBoid=%d kPsyboid=%d, "
                        + "resolution %.2f, target fill %.4f (%,d consecutive misses)%n",
                starts.length, band, kBoid, kPsy, resolution, targetFill, patience);

        Boids2DEngine engine = new Boids2DEngine(preset);
        Random rng = new Random(seed);
        Map<Long, Cell> cells = new HashMap<>();
        long began = System.nanoTime();
        long tried = 0, simulated = 0, inconclusive = 0, misses = 0;
        int kb = kBoid, kp = kPsy;
        boolean annealed = false;

        while (true) {
            tried++;
            boolean override = rng.nextBoolean();
            int suspect = starts[rng.nextInt(starts.length)];
            int other = advance(map, live[rng.nextInt(liveCount)], kb, 0);
            // A psyboid holding a right turn is not coasting, so carrying it forward unsteered
            // would place it where it could not have arrived from. Advance it as it flies.
            int psy = advance(map, live[rng.nextInt(liveCount)], kp, override ? +1 : 0);
            if (other < 0 || psy < 0) continue;

            int eb = f.edgeAt(x(map, other), y(map, other), h(other));
            int ep = f.edgeAt(x(map, psy), y(map, psy), h(psy));
            if (eb < 0 || ep < 0 || where[eb].isEmpty() || where[ep].isEmpty()) continue;

            double su = f.tickAt(x(map, suspect), y(map, suspect), h(suspect));
            double tb = f.tickAt(x(map, other), y(map, other), h(other));
            double tp = f.tickAt(x(map, psy), y(map, psy), h(psy));
            if (Double.isNaN(su) || Double.isNaN(tb) || Double.isNaN(tp)) continue;

            // Every panel this one arrangement speaks to. The phase differs per route because
            // the offsets do, so each target carries its own coordinates.
            List<int[]> targets = new ArrayList<>();
            for (int[] pw : where[ep]) {
                Route rp = routes.get(pw[0]);
                int cx = cell(tp + rp.offset()[pw[1]] - su, rp.length(), resolution);
                for (int[] bw : where[eb]) {
                    Route rb = routes.get(bw[0]);
                    int cy = cell(tb + rb.offset()[bw[1]] - su, rb.length(), resolution);
                    long key = key(pw[0], bw[0], cx, cy);
                    if (!cells.containsKey(key)) targets.add(new int[]{pw[0], bw[0], cx, cy});
                }
            }
            if (targets.isEmpty()) {
                if (++misses < patience) continue;
                misses = 0;
                if (annealed) break;
                annealed = true;
                kb = 0;
                kp = 0;
                System.out.printf("  %,d cells: dropping k to 0 to reach placements a coasting "
                        + "boid cannot occupy%n", cells.size());
                continue;
            }
            misses = 0;

            simulated++;
            PsyboidOverride[] overrides = override
                    ? new PsyboidOverride[]{new PsyboidOverride(0, FOREVER, +1, PSYBOID)}
                    : new PsyboidOverride[0];
            Outcome o = trial(engine, f, tables, from, keep, psy, other, suspect, overrides);
            if (o == null) { inconclusive++; continue; }
            for (int[] t : targets) {
                cells.put(key(t[0], t[1], t[2], t[3]), new Cell(t[0], t[1], t[2], t[3],
                        o.exited(), o.reason(), o.otherExited(), psy, other, suspect, override));
            }
        }

        double secs = (System.nanoTime() - began) / 1e9;
        report(cells.values(), tried, simulated, inconclusive, secs);
        fill(cells.values(), routes, resolution);
        draw(cells.values(), routes, resolution, from, keep, out);
        replays(cells.values(), out.resolveSibling(
                out.getFileName().toString().replace(".png", "-replays.tsv")));
        System.out.printf("wrote %s%n", out);
    }

    private record Outcome(boolean exited, ExitAudit.Reason reason, boolean otherExited) {}

    /** One arrangement, flown until the suspect leaves the edge. Null if it never did. */
    private static Outcome trial(Boids2DEngine engine, SolverFacts f, ExitAudit.Tables tables,
                                 int from, int keep, int psy, int other, int suspect,
                                 PsyboidOverride[] overrides) {
        NavMap map = tables.map();
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
                ExitAudit.Reason reason = null;
                boolean otherExited = false;
                for (ExitAudit.Exit e : audit.exits()) {
                    if (e.suspect() == SUSPECT && !e.reasons().isEmpty()) {
                        reason = e.reasons().get(0);
                    }
                    if (e.suspect() == OTHER) otherExited = true;
                }
                return new Outcome(now == keep, reason, otherExited);
            }
        } catch (RuntimeException e) {
            return null;                      // a boid left the image; not an arrangement
        } finally {
            engine.trace(null);
        }
        return null;
    }

    /**
     * Every cell that produced an exit, with enough to fly it again.
     * <p>
     * Exits only. Writing every cell would be a file the size of the picture and almost all of it
     * would say "continued", which is the one outcome nobody needs to re-examine.
     */
    private static void replays(java.util.Collection<Cell> cells, Path file) throws IOException {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        String tab = "\t";
        StringBuilder s = new StringBuilder(String.join(tab, "route", "otherRoute", "x", "y",
                "reason", "leader", "cause", "otherExited", "overridden", "psyState",
                "otherState", "suspectState")).append(System.lineSeparator());
        int n = 0;
        for (Cell c : cells) {
            if (!c.exited()) continue;
            n++;
            ExitAudit.Reason r = c.reason();
            s.append(String.join(tab, String.valueOf(c.route()), String.valueOf(c.otherRoute()),
                    String.valueOf(c.x()), String.valueOf(c.y()),
                    r == null ? "NONE" : r.level().toString(),
                    String.valueOf(r == null ? -1 : r.leader()),
                    r == null || r.cause() == null ? "-" : r.cause().toString(),
                    String.valueOf(c.otherExited()), String.valueOf(c.overridden()),
                    String.valueOf(c.psyStart()), String.valueOf(c.otherStart()),
                    String.valueOf(c.suspectStart()))).append(System.lineSeparator());
        }
        Files.writeString(file, s.toString());
        System.out.printf("  wrote %,d replayable exits to %s%n", n, file.getFileName());
    }

    /** Where a boid ends up after {@code k} ticks of holding {@code turn}, or -1 if it leaves. */
    private static int advance(NavMap map, int state, int k, int turn) {
        int at = state;
        for (int i = 0; i < k; i++) {
            at = map.successor(at, turn);
            if (at < 0) return -1;
        }
        return at;
    }

    // ---- routes -------------------------------------------------------------

    /**
     * Every simple loop from {@code from} back to itself, as edge lists with running offsets.
     * <p>
     * Simple meaning no edge repeats. That is what makes a route's length a lap length worth
     * wrapping against: a route that revisited an edge would offer two different offsets for the
     * same state and the coordinate would stop being a function.
     */
    public static List<Route> loops(SolverFacts f, int from) {
        int n = f.edges();
        List<List<Integer>> succ = new ArrayList<>();
        for (int e = 0; e < n; e++) succ.add(new ArrayList<>());
        for (int e = 0; e < n; e++) for (int p : f.predecessors(e)) succ.get(p).add(e);

        List<int[]> found = new ArrayList<>();
        walk(succ, from, from, new ArrayList<>(List.of(from)), new boolean[n], found);

        List<Route> out = new ArrayList<>();
        for (int[] edges : found) {
            double[] offset = new double[edges.length];
            double run = 0;
            for (int i = 0; i < edges.length; i++) { offset[i] = run; run += f.length()[edges[i]]; }
            out.add(new Route(edges, offset, run));
        }
        return out;
    }

    private static void walk(List<List<Integer>> succ, int from, int at, List<Integer> path,
                             boolean[] on, List<int[]> out) {
        on[at] = true;
        for (int v : succ.get(at)) {
            if (v == from) {
                int[] r = new int[path.size()];
                for (int i = 0; i < r.length; i++) r[i] = path.get(i);
                out.add(r);
            } else if (!on[v]) {
                path.add(v);
                walk(succ, from, v, path, on, out);
                path.remove(path.size() - 1);
            }
        }
        on[at] = false;
    }

    /** Per edge, every {route, position} it occupies. Empty for an edge on no loop. */
    @SuppressWarnings("unchecked")
    private static List<int[]>[] where(SolverFacts f, List<Route> routes) {
        List<int[]>[] out = new List[f.edges()];
        for (int e = 0; e < out.length; e++) out[e] = new ArrayList<>();
        for (int r = 0; r < routes.size(); r++) {
            int[] edges = routes.get(r).edges();
            for (int i = 0; i < edges.length; i++) out[edges[i]].add(new int[]{r, i});
        }
        return out;
    }

    /** Phase difference folded into one lap of the route, then binned. */
    private static int cell(double delta, double length, double resolution) {
        double wrapped = ((delta % length) + length) % length;
        return (int) Math.floor(wrapped / resolution);
    }

    private static long key(int rp, int rb, int x, int y) {
        return ((long) (rp * 8 + rb) << 44) | ((long) x << 22) | y;
    }

    // ---- starts -------------------------------------------------------------

    /**
     * One start per phase, in a tau band across the middle of the edge.
     * <p>
     * A ~4 px step means states a tick apart along one trajectory sit four pixels apart, and the
     * states between belong to trajectories that never touch it. Dropping any state whose own
     * unsteered successor is also in the band keeps the last of each chain, which is one
     * representative per phase.
     */
    private static int[] starts(NavMap map, int[] edge, SolverFacts f, boolean[] settled,
                                int[] live, int liveCount, int from, double band) {
        double mid = (f.tickLo()[from] + f.tickHi()[from]) / 2, half = band / 2;
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
    private static final int PANEL = 0x1E2129;
    private static final int TEXT = 0xB9C1CE;
    private static final int FAINT = 0x6C7583;
    private static final int CONTINUED = 0x2A2F38;

    /**
     * The account palette. Public because the picture is also an input: {@link ThreeBoidSamples}
     * reads a hand-annotated copy back and has to know which class each pixel underneath stood
     * for, and a second transcription of these values would be a silent way to mislabel a region.
     */
    public static final int UNEXPLAINED = 0xFFFFFF, PSYBOID_LED = 0xC8913F,
            THIRD_LED = 0x4FA8C8, DILUTED = 0xE040D0, SELF_OVERRIDDEN = 0x66C070;

    /**
     * One colour per account, tonally close so structure reads rather than any one class
     * shouting — except an exit nothing explains, which is the whole reason for looking.
     */
    private static int colour(Cell c) {
        if (!c.exited()) return CONTINUED;
        if (c.reason() == null) return UNEXPLAINED;
        if (c.reason().level() == ExitAudit.Level.PSYBOID) return SELF_OVERRIDDEN;
        // Leader identity is the amber/cyan axis. The diluted model is rare and needs to be
        // findable rather than tonally polite, so it gets magenta whichever boid led.
        if (c.reason().level() == ExitAudit.Level.ENVELOPE_WIDENED) return DILUTED;
        return c.reason().leader() == PSYBOID ? PSYBOID_LED : THIRD_LED;
    }

    private static void report(java.util.Collection<Cell> cells, long tried, long simulated,
                               long inconclusive, double secs) {
        int exits = 0, none = 0, byPsy = 0, byOther = 0, wide = 0;
        for (Cell c : cells) {
            if (!c.exited()) continue;
            exits++;
            if (c.reason() == null) { none++; continue; }
            if (c.reason().level() == ExitAudit.Level.ENVELOPE_WIDENED) wide++;
            if (c.reason().leader() == PSYBOID) byPsy++; else byOther++;
        }
        System.out.printf("%,d cells from %,d simulations out of %,d attempts in %.0fs "
                + "(%,d never left)%n", cells.size(), simulated, tried, secs, inconclusive);
        System.out.printf("  %,d exited, %,d continued%n", exits, cells.size() - exits);
        System.out.printf("  of the exits: %,d led by the psyboid, %,d by the third boid, "
                + "%,d needed the diluted model, %,d UNEXPLAINED%n", byPsy, byOther, wide, none);
    }

    /**
     * How much of each panel got sampled.
     * <p>
     * A panel is a fixed rectangle — one lap of each route — so an unfilled cell is either a
     * phase pair nothing reached in the budget or one no arrangement can produce. Reporting the
     * rate keeps those two from being read off the picture as if they were the same thing.
     */
    private static void fill(java.util.Collection<Cell> cells, List<Route> routes,
                             double resolution) {
        int n = routes.size();
        int[][] have = new int[n][n];
        for (Cell c : cells) have[c.route()][c.otherRoute()]++;
        System.out.println("  panel fill:");
        for (int rp = 0; rp < n; rp++) {
            for (int rb = 0; rb < n; rb++) {
                long total = (long) Math.ceil(routes.get(rp).length() / resolution)
                        * (long) Math.ceil(routes.get(rb).length() / resolution);
                System.out.printf("    psyboid %-14s x boid %-14s %,9d of %,9d  %5.1f%%%n",
                        routes.get(rp).label(), routes.get(rb).label(), have[rp][rb], total,
                        100.0 * have[rp][rb] / total);
            }
        }
    }

    /**
     * Where every panel sits in the rendered sheet.
     * <p>
     * Extracted from {@link #draw} so that reading the picture back — mapping a
     * {@code (route, route, x, y)} cell to the pixel carrying it, which is what
     * {@link ThreeBoidSamples} does — runs the same arithmetic that drew it. A second copy of
     * these constants somewhere else is a silent misalignment waiting to happen.
     */
    public record Layout(int[] dim, int[] origin, int pad, int head, int foot, int gap) {
        /** Image column carrying cell {@code x} of the panel whose psyboid route is {@code r}. */
        public int px(int r, int x) { return pad + origin[r] + x; }

        /** Image row carrying cell {@code y} of the panel whose third-boid route is {@code r}. */
        public int py(int r, int y) { return head + origin[r] + y; }

        /** A panel's side in cells; panels are square because both axes are the same route. */
        public int span(int r) { return dim[r]; }

        public int width() { return pad + origin[dim.length] + pad; }

        public int height() { return head + origin[dim.length] + foot; }
    }

    /** The panel geometry for one set of routes at one resolution. */
    public static Layout layout(List<Route> routes, double resolution) {
        int n = routes.size();
        int gap = 34;
        int[] dim = new int[n], origin = new int[n + 1];
        for (int r = 0; r < n; r++) {
            dim[r] = (int) Math.ceil(routes.get(r).length() / resolution);
            origin[r + 1] = origin[r] + dim[r] + gap;
        }
        return new Layout(dim, origin, 108, 66, 44, gap);
    }

    private static void draw(java.util.Collection<Cell> cells, List<Route> routes,
                             double resolution, int from, int keep, Path out) throws IOException {
        int n = routes.size();
        Layout lay = layout(routes, resolution);
        int pad = lay.pad(), head = lay.head();

        BufferedImage img = new BufferedImage(lay.width(), lay.height(),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(GROUND));
        g.fillRect(0, 0, img.getWidth(), img.getHeight());

        for (int rp = 0; rp < n; rp++) {
            for (int rb = 0; rb < n; rb++) {
                g.setColor(new Color(PANEL));
                g.fillRect(lay.px(rp, 0), lay.py(rb, 0), lay.span(rp), lay.span(rb));
            }
        }
        for (Cell c : cells) {
            g.setColor(new Color(colour(c)));
            int px = lay.px(c.route(), c.x()), py = lay.py(c.otherRoute(), c.y());
            if (c.x() < lay.span(c.route()) && c.y() < lay.span(c.otherRoute())) {
                img.setRGB(px, py, colour(c));
            }
        }

        g.setColor(new Color(TEXT));
        g.setFont(new Font("SansSerif", Font.BOLD, 15));
        // %.0f would round a half-tick cell up to "1" and misreport the scale of the picture.
        g.drawString(String.format("three-boid phase map, arc %d->%d   %s tick(s) per cell   "
                + "columns: psyboid route, rows: third-boid route", from, keep,
                new java.math.BigDecimal(String.valueOf(resolution)).stripTrailingZeros()
                        .toPlainString()),
                pad, 24);
        g.setFont(new Font("SansSerif", Font.PLAIN, 12));
        g.setColor(new Color(FAINT));
        g.drawString("each panel wraps one lap of its own route, so a phase relationship appears "
                + "once rather than once per lap", pad, 42);

        g.setFont(new Font("SansSerif", Font.PLAIN, 11));
        for (int rp = 0; rp < n; rp++) {
            g.setColor(new Color(TEXT));
            g.drawString(routes.get(rp).label(), lay.px(rp, 0), head - 6);
        }
        for (int rb = 0; rb < n; rb++) {
            g.setColor(new Color(TEXT));
            g.drawString(routes.get(rb).label(), 6, lay.py(rb, 12));
        }

        String[] key = {"nothing accounts for it", "led by the psyboid", "led by the third boid",
                "diluted model needed", "continued"};
        int[] swatch = {UNEXPLAINED, PSYBOID_LED, THIRD_LED, DILUTED, CONTINUED};
        int usable = img.getWidth() - pad * 2, per = Math.max(1, usable / 220), pitch = usable / per;
        for (int i = 0; i < key.length; i++) {
            int lx = pad + (i % per) * pitch, ly = head + lay.origin()[n] + 20 + (i / per) * 18;
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
