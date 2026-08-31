package boids;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * One picture per hand-marked region of the three-boid phase map.
 * <p>
 * {@link ThreeBoidPhase} answers <em>how much</em> of the plane each account covers, and its
 * headline number is that a fifth of the exits have no account at all. That number cannot be
 * argued with and cannot be worked on either: it says the cover is incomplete without saying
 * what is missing. The regions of the plane are the workable unit — the white is not a haze, it
 * is a handful of bands and blobs with hard edges — and the question for each is what
 * arrangement it holds.
 *
 * <h2>The regions come from a human</h2>
 * They are read off {@code analysis/3BoidAreasOfInterest.png}, which is the rendered phase map
 * with regions painted over in two colours by eye. That is deliberate and temporary. Finding
 * these regions programmatically is the actual goal; hand-marking them is how we find out what
 * a region <em>is</em> before writing a detector, and the same file will later be the thing a
 * detector is scored against.
 * <p>
 * Only paint is read from that file. Everything a picture says about physics comes from
 * replaying the arrangement out of {@code phase&lt;f&gt;_&lt;t&gt;-replays.tsv}, which carries all three
 * start states, so a sample is a rerun rather than a reading of a rendered pixel.
 *
 * <h2>What one sample shows</h2>
 * The tick the suspect was steered <b>onto the critical envelope</b>, not the tick it crossed.
 * That is where the decision is visible and where {@link ExitAudit} asks the leader question, so
 * it is the moment an account is missing at. Each tile carries a downscaled crop of the phase
 * map around its own cell, so a tile can be matched to the region it came from by eye without
 * counting rows.
 */
public final class ThreeBoidSamples {
    private ThreeBoidSamples() {}

    /** Roles, as {@link ThreeBoidPhase} assigns them. The suspect decides last. */
    private static final int PSYBOID = 0, OTHER = 1, SUSPECT = 2;

    /** Long enough to be permanent over any run this replays. */
    private static final int FOREVER = 1 << 20;

    /** How long a replay may run before the arrangement is abandoned. */
    private static final int PATIENCE = 2000;

    /** The two colours the overlay is painted in; both are Paint's own defaults. */
    public static final int ROSE = 0xFFAEC9, GREEN = 0x22B14C;

    /**
     * How far apart two painted cells may be and still count as one region.
     * <p>
     * Paint lands only on cells of the class being marked, and those are dithered, so a single
     * stroke arrives as a scatter rather than a solid block and needs closing up. Six cells is
     * three ticks; the nearest two distinct regions on this overlay are 120 cells apart, so
     * there is no setting between the two that is a judgement call.
     */
    private static final int MERGE = 6;

    /** Phase-map crop taken per tile, and the size it is drawn at. */
    private static final int INSET_SRC = 200, INSET_DST = 100;

    // ---- regions -------------------------------------------------------------

    /**
     * One painted region: a connected clump of cells in one panel, in one paint colour.
     *
     * @param base  the account the paint covers, from {@link ThreeBoidPhase}'s palette — what
     *              the region is a region <em>of</em>
     * @param cells packed {@code x << 11 | y}, panel-local
     */
    public record Region(String id, int route, int otherRoute, int paint, int base,
                         Set<Integer> cells, int x0, int x1, int y0, int y1, int cx, int cy) {

        public int size() { return cells.size(); }

        public String paintName() { return paint == ROSE ? "rose" : "green"; }
    }

    /** One replay row, kept only for cells that are painted. */
    private record Row(int route, int otherRoute, int x, int y, String level, int leader,
                       String cause, boolean otherExited, boolean overridden,
                       int psy, int other, int suspect) {

        /** The palette entry this row was drawn as, so a row can be matched to painted colour. */
        int colour() {
            if (level.equals("NONE")) return ThreeBoidPhase.UNEXPLAINED;
            if (level.equals("ENVELOPE_WIDENED")) return ThreeBoidPhase.DILUTED;
            if (level.equals("PSYBOID")) return ThreeBoidPhase.SELF_OVERRIDDEN;
            return leader == PSYBOID ? ThreeBoidPhase.PSYBOID_LED : ThreeBoidPhase.THIRD_LED;
        }
    }

    /** A chosen arrangement, replayed, with the audit's account of it. */
    private record Shot(Region region, String suffix, String note, Row row, ExitAudit.Exit exit,
                        int[] x, int[] y, int[] h, boolean agreed) {}

    // ---- entry point ---------------------------------------------------------

    /**
     * Marks up, replays and draws one sample per region.
     *
     * @param overlay    the hand-annotated copy of the phase map
     * @param plain      the phase map as rendered, read for what each painted cell used to be
     * @param replays    the {@code -replays.tsv} written beside {@code plain}
     * @param resolution ticks per cell, which must be the value {@code plain} was drawn at
     * @param columns    tiles per row of the contact sheet
     */
    public static void run(PresetScenarioParameter preset, SolverFacts f, ExitAudit.Tables tables,
                           int from, int keep, double resolution, Path overlay, Path plain,
                           Path replays, int columns, int scale, Path out) throws IOException {
        List<ThreeBoidPhase.Route> routes = ThreeBoidPhase.loops(f, from);
        ThreeBoidPhase.Layout lay = ThreeBoidPhase.layout(routes, resolution);

        BufferedImage marked = javax.imageio.ImageIO.read(overlay.toFile());
        BufferedImage base = javax.imageio.ImageIO.read(plain.toFile());
        System.out.printf("%n=== %s @%s: sampling the marked regions of arc %d->%d ===%n",
                preset.name(), preset.ingest().hash(), from, keep);
        // The overlay is only meaningful against the layout that produced it. Checking the size
        // is cheap and catches a resolution or route-set mismatch before it becomes a picture of
        // the wrong cells.
        if (marked.getWidth() != lay.width() || marked.getHeight() != lay.height()) {
            throw new IOException(String.format("overlay is %dx%d but the layout at resolution "
                            + "%s is %dx%d — wrong resolution or a different route set",
                    marked.getWidth(), marked.getHeight(), resolution, lay.width(),
                    lay.height()));
        }
        if (base.getWidth() != marked.getWidth() || base.getHeight() != marked.getHeight()) {
            throw new IOException("overlay and plain phase maps are different sizes");
        }

        List<Region> regions = regions(marked, base, lay, routes);
        Set<Integer> painted = new HashSet<>();
        for (Region r : regions) {
            for (int c : r.cells()) painted.add(panelKey(r.route(), r.otherRoute(), c));
        }
        Map<Integer, Row> rows = rows(replays, painted);

        System.out.printf("  %,d painted cells in %d regions; %,d of them have a replay row%n",
                painted.size(), regions.size(), rows.size());
        for (Region r : regions) {
            System.out.printf("  %-4s %-5s over %-11s  psy %-12s x third %-12s  %,5d cells  "
                            + "x[%d..%d] y[%d..%d]%n", r.id(), r.paintName(), name(r.base()),
                    routes.get(r.route()).label(), routes.get(r.otherRoute()).label(), r.size(),
                    r.x0(), r.x1(), r.y0(), r.y1());
        }

        List<Shot> shots = new ArrayList<>();
        Boids2DEngine engine = new Boids2DEngine(preset);
        for (Region r : regions) {
            if (r.paint() == GREEN) {
                // The one green region is believed to hold two behaviours that the picture
                // cannot separate, because whether the psyboid was actually steering is not one
                // of the axes. Take one of each rather than choosing between them here.
                shots.add(shoot(engine, f, tables, from, r, rows, Boolean.TRUE,
                        "psyboid under an override", "a"));
                shots.add(shoot(engine, f, tables, from, r, rows, Boolean.FALSE,
                        "psyboid free-flying", "b"));
            } else {
                shots.add(shoot(engine, f, tables, from, r, rows, null, "", ""));
            }
        }
        shots.removeIf(java.util.Objects::isNull);

        sheet(preset, f, marked, lay, routes, shots, from, keep, columns, scale, out);
    }

    // ---- finding the regions -------------------------------------------------

    /**
     * Every connected clump of paint, panel by panel and colour by colour.
     * <p>
     * Panels are separated first rather than clumping the whole sheet, because two panels are
     * different coordinate systems that happen to be drawn next to each other — a component
     * spanning the seam would be a claim about nothing.
     */
    private static List<Region> regions(BufferedImage marked, BufferedImage plain,
                                        ThreeBoidPhase.Layout lay,
                                        List<ThreeBoidPhase.Route> routes) {
        int n = routes.size();
        List<Region> found = new ArrayList<>();
        for (int rp = 0; rp < n; rp++) {
            for (int rb = 0; rb < n; rb++) {
                for (int paint : new int[]{ROSE, GREEN}) {
                    Set<Integer> all = new HashSet<>();
                    for (int y = 0; y < lay.span(rb); y++) {
                        for (int x = 0; x < lay.span(rp); x++) {
                            int rgb = marked.getRGB(lay.px(rp, x), lay.py(rb, y)) & 0xFFFFFF;
                            if (rgb == paint) all.add(x << 11 | y);
                        }
                    }
                    for (Set<Integer> clump : clumps(all)) {
                        found.add(describe(plain, lay, rp, rb, paint, clump));
                    }
                }
            }
        }
        // Reading order down the sheet, so a tile's number says roughly where on the map it is.
        found.sort(Comparator.<Region>comparingInt(Region::route)
                .thenComparingInt(Region::otherRoute)
                .thenComparingInt(Region::y0)
                .thenComparingInt(Region::x0));
        List<Region> out = new ArrayList<>();
        for (int i = 0; i < found.size(); i++) {
            Region r = found.get(i);
            out.add(new Region(String.format("%s%02d", r.paint() == ROSE ? "R" : "G", i + 1),
                    r.route(), r.otherRoute(), r.paint(), r.base(), r.cells(), r.x0(), r.x1(),
                    r.y0(), r.y1(), r.cx(), r.cy()));
        }
        return out;
    }

    /** Flood fill over cells, joining anything within {@link #MERGE} in both axes. */
    private static List<Set<Integer>> clumps(Set<Integer> cells) {
        List<Set<Integer>> out = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (int seed : cells) {
            if (!seen.add(seed)) continue;
            Set<Integer> clump = new HashSet<>();
            Deque<Integer> queue = new ArrayDeque<>();
            queue.push(seed);
            while (!queue.isEmpty()) {
                int at = queue.pop();
                clump.add(at);
                int x = at >>> 11, y = at & 0x7FF;
                for (int dx = -MERGE; dx <= MERGE; dx++) {
                    for (int dy = -MERGE; dy <= MERGE; dy++) {
                        int nx = x + dx, ny = y + dy;
                        if (nx < 0 || ny < 0 || nx >= 2048 || ny >= 2048) continue;
                        int k = nx << 11 | ny;
                        if (cells.contains(k) && seen.add(k)) queue.push(k);
                    }
                }
            }
            out.add(clump);
        }
        return out;
    }

    /** Bounds, centroid, and which account the paint was laid over. */
    private static Region describe(BufferedImage plain, ThreeBoidPhase.Layout lay, int rp, int rb,
                                   int paint, Set<Integer> clump) {
        int x0 = Integer.MAX_VALUE, x1 = -1, y0 = Integer.MAX_VALUE, y1 = -1;
        long sx = 0, sy = 0;
        Map<Integer, Integer> under = new HashMap<>();
        for (int c : clump) {
            int x = c >>> 11, y = c & 0x7FF;
            x0 = Math.min(x0, x); x1 = Math.max(x1, x);
            y0 = Math.min(y0, y); y1 = Math.max(y1, y);
            sx += x; sy += y;
            int was = plain.getRGB(lay.px(rp, x), lay.py(rb, y)) & 0xFFFFFF;
            under.merge(was, 1, Integer::sum);
        }
        int best = 0, bestN = -1;
        for (Map.Entry<Integer, Integer> e : under.entrySet()) {
            if (e.getValue() > bestN) { bestN = e.getValue(); best = e.getKey(); }
        }
        return new Region("", rp, rb, paint, best, clump, x0, x1, y0, y1,
                (int) (sx / clump.size()), (int) (sy / clump.size()));
    }

    // ---- the replay rows -----------------------------------------------------

    /** Reads only the rows for painted cells; the file is 39 MB and mostly irrelevant here. */
    private static Map<Integer, Row> rows(Path replays, Set<Integer> painted) throws IOException {
        Map<Integer, Row> out = new HashMap<>();
        try (BufferedReader in = Files.newBufferedReader(replays)) {
            String line = in.readLine();                       // header
            while ((line = in.readLine()) != null) {
                String[] p = line.split("\t");
                if (p.length < 12) continue;
                int rp = Integer.parseInt(p[0]), rb = Integer.parseInt(p[1]);
                int x = Integer.parseInt(p[2]), y = Integer.parseInt(p[3]);
                int key = panelKey(rp, rb, x << 11 | y);
                if (!painted.contains(key)) continue;
                out.put(key, new Row(rp, rb, x, y, p[4], Integer.parseInt(p[5]), p[6],
                        Boolean.parseBoolean(p[7]), Boolean.parseBoolean(p[8]),
                        Integer.parseInt(p[9]), Integer.parseInt(p[10]),
                        Integer.parseInt(p[11])));
            }
        }
        return out;
    }

    private static int panelKey(int rp, int rb, int cell) { return (rp * 8 + rb) << 22 | cell; }

    // ---- choosing and replaying one arrangement ------------------------------

    /**
     * Picks one arrangement out of a region and flies it again.
     * <p>
     * <b>The pick is the cell nearest the region's centroid</b> whose recorded account matches
     * what the region is a region of. Nearest-the-middle rather than random because a region has
     * an edge where it shades into its neighbours, and a sample taken there would be a picture
     * of the boundary rather than of the thing.
     *
     * @param wantOverride restrict to trials where the psyboid was or was not steering, or null
     *                     to take whichever is nearest the centroid
     */
    private static Shot shoot(Boids2DEngine engine, SolverFacts f, ExitAudit.Tables tables,
                              int from, Region r, Map<Integer, Row> rows, Boolean wantOverride,
                              String note, String suffix) {
        Row pick = null;
        long bestD = Long.MAX_VALUE;
        for (int c : r.cells()) {
            Row row = rows.get(panelKey(r.route(), r.otherRoute(), c));
            if (row == null || row.colour() != r.base()) continue;
            if (wantOverride != null && row.overridden() != wantOverride) continue;
            long dx = row.x() - r.cx(), dy = row.y() - r.cy();
            long d = dx * dx + dy * dy;
            if (d < bestD) { bestD = d; pick = row; }
        }
        if (pick == null) {
            System.out.printf("  %s%s: no replayable cell of class %s%s — skipped%n", r.id(),
                    suffix, name(r.base()),
                    wantOverride == null ? "" : " with override=" + wantOverride);
            return null;
        }

        PsyboidOverride[] overrides = pick.overridden()
                ? new PsyboidOverride[]{new PsyboidOverride(0, FOREVER, +1, PSYBOID)}
                : new PsyboidOverride[0];
        NavMap map = tables.map();
        int[] xs = {x(map, pick.psy()), x(map, pick.other()), x(map, pick.suspect())};
        int[] ys = {y(map, pick.psy()), y(map, pick.other()), y(map, pick.suspect())};
        int[] hs = {h(pick.psy()), h(pick.other()), h(pick.suspect())};
        Sim.State s = new Sim.State(3, xs, ys, hs, 0, 0, new long[3], "sample", overrides);

        ExitAudit audit = new ExitAudit(tables, overrides);
        audit.reset(3, overrides);
        engine.trace(audit);
        try {
            for (int t = 0; t < PATIENCE; t++) {
                s = engine.tick(s);
                if (f.edgeAt(s.x[SUSPECT], s.y[SUSPECT], s.h[SUSPECT]) != from) break;
            }
        } catch (RuntimeException e) {
            System.out.printf("  %s%s: replay left the map — skipped%n", r.id(), suffix);
            return null;
        } finally {
            engine.trace(null);
        }

        ExitAudit.Exit exit = null;
        for (ExitAudit.Exit e : audit.exits()) {
            if (e.suspect() == SUSPECT && e.fromEdge() == from) exit = e;
        }
        if (exit == null) {
            System.out.printf("  %s%s: replay produced no exit off edge %d — skipped%n", r.id(),
                    suffix, from);
            return null;
        }

        // The row was written by a different run of the same physics; if the account has moved,
        // the tile is still a real arrangement but it is no longer a sample of the painted class,
        // and that has to show on the picture rather than be swallowed.
        String now = exit.reasons().isEmpty() ? "NONE" : exit.best().toString();
        int nowLeader = exit.reasons().isEmpty() ? -1 : exit.reasons().get(0).leader();
        boolean agreed = now.equals(pick.level()) && nowLeader == pick.leader();
        if (!agreed) {
            System.out.printf("  %s%s: recorded %s/leader %d, replayed %s/leader %d%n", r.id(),
                    suffix, pick.level(), pick.leader(), now, nowLeader);
        }

        int n = exit.entryStates().length;
        String where = note;
        int[] px, py, ph;
        if (n == 0) {
            // Nothing in the plan corpus has done this, but a picture of the crossing is still
            // worth more than no picture, so say plainly that this is not the entry moment.
            px = new int[]{s.x[0], s.x[1], s.x[2]};
            py = new int[]{s.y[0], s.y[1], s.y[2]};
            ph = new int[]{s.h[0], s.h[1], s.h[2]};
            where = (note.isEmpty() ? "" : note + "; ") + "NEVER ENTERED THE ENVELOPE -- drawn at "
                    + "the crossing instead";
        } else {
            px = new int[n];
            py = new int[n];
            ph = new int[n];
            for (int j = 0; j < n; j++) {
                px[j] = x(map, exit.entryStates()[j]);
                py[j] = y(map, exit.entryStates()[j]);
                ph[j] = h(exit.entryStates()[j]);
            }
        }
        return new Shot(r, suffix, where, pick, exit, px, py, ph, agreed);
    }

    // ---- why one region's exit has no account ---------------------------------

    /**
     * The suspect's state at every tick from the start of a sampled trial to its envelope entry.
     * <p>
     * Separate from {@link #explain} because the interesting question about a definition of
     * "ordinary ground" is which of these states it contains, and answering that for a dozen
     * candidate definitions should not mean flying the arrangement a dozen times.
     */
    public static int[] suspectPath(PresetScenarioParameter preset, SolverFacts f,
                                    ExitAudit.Tables tables, int from, int route, int otherRoute,
                                    int cellX, int cellY, Path replays) throws IOException {
        int key = panelKey(route, otherRoute, cellX << 11 | cellY);
        Row row = rows(replays, java.util.Set.of(key)).get(key);
        if (row == null) return new int[0];
        NavMap map = tables.map();
        PsyboidOverride[] overrides = row.overridden()
                ? new PsyboidOverride[]{new PsyboidOverride(0, FOREVER, +1, PSYBOID)}
                : new PsyboidOverride[0];
        int[] xs = {x(map, row.psy()), x(map, row.other()), x(map, row.suspect())};
        int[] ys = {y(map, row.psy()), y(map, row.other()), y(map, row.suspect())};
        int[] hs = {h(row.psy()), h(row.other()), h(row.suspect())};

        Boids2DEngine engine = new Boids2DEngine(preset);
        ExitAudit audit = new ExitAudit(tables, overrides);
        audit.reset(3, overrides);
        engine.trace(audit);
        Sim.State s = new Sim.State(3, xs, ys, hs, 0, 0, new long[3], "path", overrides);
        try {
            for (int t = 0; t < PATIENCE; t++) {
                s = engine.tick(s);
                if (f.edgeAt(s.x[SUSPECT], s.y[SUSPECT], s.h[SUSPECT]) != from) break;
            }
        } finally {
            engine.trace(null);
        }
        ExitAudit.Exit exit = null;
        for (ExitAudit.Exit e : audit.exits()) {
            if (e.suspect() == SUSPECT && e.fromEdge() == from) exit = e;
        }
        if (exit == null) return new int[0];

        long entry = exit.entryTick();
        int[] path = new int[(int) entry + 1];
        Boids2DEngine.Trace recorder = (tick, i, boids, want) -> {
            if (i != SUSPECT || tick > entry) return;
            path[(int) tick] = map.index(boids.x()[i], boids.y()[i], boids.h()[i]);
        };
        engine.trace(recorder);
        s = new Sim.State(3, xs, ys, hs, 0, 0, new long[3], "path", overrides);
        try {
            for (int t = 0; t < PATIENCE; t++) {
                s = engine.tick(s);
                if (f.edgeAt(s.x[SUSPECT], s.y[SUSPECT], s.h[SUSPECT]) != from) break;
            }
        } finally {
            engine.trace(null);
        }
        return path;
    }

    /**
     * The whole approach to one sampled exit, tick by tick, and what the tables make of it.
     * <p>
     * The sheet says an exit has no account. This says <em>where the account was lost</em>, which
     * is a different question and the only one that can be acted on. It is
     * {@link SimTest#steeringHistory} for an arrangement out of the phase map rather than out of
     * the plan corpus, plus the two things that decide admission: whether the suspect was on
     * settled ground, and whether the prune would have cut the step.
     * <p>
     * <b>Every trial starts settled by construction</b> — {@code ThreeBoidPhase.starts} takes only
     * settled states — so the history here is complete rather than a window into a longer run.
     * If a single neighbour reproduces every tick from the last settled state to the entry, the
     * pairwise tables ought to have admitted it, and the reason they did not is the finding.
     */
    public static void explain(PresetScenarioParameter preset, SimTest.Labelling l, SolverFacts f,
                               ExitAudit.Tables tables, int from, int keep, int route,
                               int otherRoute, int cellX, int cellY, Path replays,
                               Flocking normal, Flocking alt, StateSet ground, Path out)
            throws IOException {
        NavMap map = l.map();
        boolean[] settled = CriticalEnvelope.settled(map, l.edge(), l.live(), l.liveCount(), from);
        Set<Integer> one = Set.of(panelKey(route, otherRoute, cellX << 11 | cellY));
        Row row = rows(replays, one).get(panelKey(route, otherRoute, cellX << 11 | cellY));
        if (row == null) { System.out.println("no replay row at that cell"); return; }

        System.out.printf("%n=== %s @%s: arc %d->%d, panel %d x %d, cell (%d,%d) ===%n",
                preset.name(), preset.ingest().hash(), from, keep, route, otherRoute, cellX,
                cellY);
        System.out.printf("recorded %s, leader %d, override %s%n", row.level(), row.leader(),
                row.overridden());

        PsyboidOverride[] overrides = row.overridden()
                ? new PsyboidOverride[]{new PsyboidOverride(0, FOREVER, +1, PSYBOID)}
                : new PsyboidOverride[0];
        int[] xs = {x(map, row.psy()), x(map, row.other()), x(map, row.suspect())};
        int[] ys = {y(map, row.psy()), y(map, row.other()), y(map, row.suspect())};
        int[] hs = {h(row.psy()), h(row.other()), h(row.suspect())};

        Boids2DEngine engine = new Boids2DEngine(preset);
        ExitAudit audit = new ExitAudit(tables, overrides);
        audit.reset(3, overrides);
        engine.trace(audit);
        Sim.State s = new Sim.State(3, xs, ys, hs, 0, 0, new long[3], "explain", overrides);
        try {
            for (int t = 0; t < PATIENCE; t++) {
                s = engine.tick(s);
                if (f.edgeAt(s.x[SUSPECT], s.y[SUSPECT], s.h[SUSPECT]) != from) break;
            }
        } finally {
            engine.trace(null);
        }
        ExitAudit.Exit exit = null;
        for (ExitAudit.Exit e : audit.exits()) {
            if (e.suspect() == SUSPECT && e.fromEdge() == from) exit = e;
        }
        if (exit == null) { System.out.println("the suspect never left the edge"); return; }
        System.out.printf("crossed at tick %d into edge %d, steered onto the envelope at tick %d, "
                        + "%d reasons%n", exit.tick(), exit.toEdge(), exit.entryTick(),
                exit.reasons().size());
        System.out.printf("entry state %d is %s the tables%n", exit.entryPrior(),
                audit.isEntryState(from, keep, exit.entryPrior()) ? "IN" : "ABSENT FROM");
        for (int j = 0; j < exit.entryStates().length; j++) {
            if (j == SUSPECT) continue;
            System.out.printf("  pair (entry, boid %d) is %s the tables%n", j,
                    audit.lookup(from, keep, exit.entryPrior(), exit.entryStates()[j]) == null
                            ? "ABSENT FROM" : "IN");
        }

        // Second pass with a recorder, because one trace tap is installed at a time and the
        // audit had to run first to say which tick the entry was.
        long entry = exit.entryTick();
        boolean[][] holds = new boolean[3][(int) entry + 1];
        boolean[] demands = new boolean[(int) entry + 1];
        int[][] frames = new int[(int) entry + 1][];
        long[] lastSettled = {-1}, lastGround = {-1};
        int[] ticks = {0};
        double speed = Params.speed(map.radius());
        System.out.printf("%n%6s %5s %6s %5s %8s %8s %9s  %-8s", "tick", "want", "actual", "edge",
                "settled", "stable+", "leader?", "accounts");
        for (int j = 0; j < 3; j++) {
            if (j != SUSPECT) System.out.printf("   %-30s", "boid " + j + " d/dil dist edge prune");
        }
        System.out.println();

        Boids2DEngine.Trace recorder = (tick, i, boids, want) -> {
            if (i != SUSPECT || tick > entry) return;
            ticks[0]++;
            int bx = boids.x()[i], by = boids.y()[i], bd = boids.h()[i];
            int state = map.index(bx, by, bd);
            int went = map.successor(state, want);
            // A tick only needs a leader if coasting would not have produced the move. Where it
            // would — because nothing was steering, or because the veto overrode the request and
            // every turn collapsed to the same successor — every neighbour "accounts" for it,
            // including one on the far side of the map asking for nothing. Those ticks are free
            // and must not be counted as coverage.
            demands[(int) tick] = map.successor(state, 0) != went;
            if (settled[state]) lastSettled[0] = tick;
            boolean onGround = ground != null && ground.contains(state);
            if (onGround) lastGround[0] = tick;
            frames[(int) tick] = new int[]{boids.x()[0], boids.y()[0], boids.h()[0],
                    boids.x()[1], boids.y()[1], boids.h()[1], bx, by, bd};
            StringBuilder cells = new StringBuilder(), who = new StringBuilder();
            for (int j = 0; j < 3; j++) {
                if (j == SUSPECT) continue;
                int lx = boids.x()[j], ly = boids.y()[j], lh = boids.h()[j];
                int dx = lx - bx, dy = ly - by;
                int p1 = EdgeInfluence.steer(bd, dx, dy, lh, normal);
                int p2 = EdgeInfluence.steer(bd, dx, dy, lh, alt);
                boolean ok = map.successor(state, p1) == went || map.successor(state, p2) == went;
                boolean cut = CriticalEnvelope.unrecoverable(dx, dy, bd, lh, normal, speed);
                holds[j][(int) tick] = ok;
                if (ok) who.append(who.isEmpty() ? "" : ",").append(j);
                cells.append(String.format("   %+2d/%+2d %5.0f %4d %5s", p1, p2,
                        Math.hypot(dx, dy), l.edge()[map.index(lx, ly, lh)], cut ? "CUT" : ""));
            }
            System.out.printf("%6d %+5d %+6d %5d %8s %8s %9s  %-8s%s%s%n", tick, want,
                    map.constrainTurn(bx, by, bd, want), l.edge()[state],
                    settled[state] ? "yes" : "", onGround ? "yes" : "",
                    demands[(int) tick] ? "DEMANDS" : "free",
                    who.isEmpty() ? "NOBODY" : "{" + who + "}",
                    cells, tick == entry ? "   ENTERS" : "");
        };
        engine.trace(recorder);
        s = new Sim.State(3, xs, ys, hs, 0, 0, new long[3], "explain", overrides);
        try {
            for (int t = 0; t < PATIENCE; t++) {
                s = engine.tick(s);
                if (f.edgeAt(s.x[SUSPECT], s.y[SUSPECT], s.h[SUSPECT]) != from) break;
            }
        } finally {
            engine.trace(null);
        }

        // The decisive number. Admission holds one leader for a whole history, so it needs a
        // single boid accounting for every tick from settled ground to the entry; anything
        // shorter is a window no setting of any pairwise constants reaches.
        int lo = (int) lastSettled[0], hi = (int) entry;
        System.out.printf("%n%d ticks flown; last settled at tick %d, so the window admission "
                + "must cover is %d..%d (%d ticks)%n", ticks[0], lo, lo, hi, hi - lo + 1);
        boolean any = false;
        for (int j = 0; j < 3; j++) {
            if (j == SUSPECT) continue;
            int held = 0, first = -1, last = -1, gaps = 0;
            for (int t = lo; t <= hi; t++) {
                if (!holds[j][t]) { gaps++; continue; }
                held++;
                if (first < 0) first = t;
                last = t;
            }
            any |= held == hi - lo + 1;
            System.out.printf("  boid %d holds %d of %d, ticks %d..%d, %d not accounted for%n",
                    j, held, hi - lo + 1, first, last, gaps);
        }
        int both = 0, handLo = -1, handHi = -1;
        for (int t = lo; t <= hi; t++) {
            if (!(holds[0][t] && holds[1][t])) continue;
            both++;
            if (handLo < 0) handLo = t;
            handHi = t;
        }
        System.out.printf("  %s%n", any
                ? "one boid covers settled ground to entry -- a pairwise table could hold this"
                : "NO SINGLE BOID COVERS SETTLED GROUND TO ENTRY -- multi-leader");
        System.out.printf("  both account on %d ticks, %d..%d%n", both, handLo, handHi);

        // The same window with the free ticks taken out. Coverage counts over all ticks flatter
        // every candidate equally; what a leader has to explain is only the demanding ones.
        int need = 0, overlap = 0;
        StringBuilder demanded = new StringBuilder();
        for (int t = lo; t <= hi; t++) {
            if (!demands[t]) continue;
            need++;
            demanded.append(demanded.isEmpty() ? "" : ",").append(t);
            if (holds[0][t] && holds[1][t]) overlap++;
        }
        System.out.printf("%nof those %d ticks only %d actually demand a leader: %s%n",
                hi - lo + 1, need, demanded);
        for (int j = 0; j < 3; j++) {
            if (j == SUSPECT) continue;
            int held = 0, first = -1, last = -1;
            for (int t = lo; t <= hi; t++) {
                if (!demands[t] || !holds[j][t]) continue;
                held++;
                if (first < 0) first = t;
                last = t;
            }
            System.out.printf("  boid %d explains %d of the %d demanding ticks, %d..%d%n", j,
                    held, need, first, last);
        }
        System.out.printf("  %d demanding ticks admit both, so the handover window on the ticks "
                + "that matter is %s%n", overlap,
                overlap == 0 ? "EMPTY -- the two coverages do not touch" : "non-empty");

        // The same question with stable+ as the ground a history has to reach. If ordinary
        // traffic can leave a boid there, a chain back to it is a complete account, and a window
        // that no single leader could cover from settled ground may be covered from here.
        if (ground != null) {
            int glo = (int) lastGround[0];
            System.out.printf("%nwith stable+ as the ground: last on it at tick %d, so the window "
                    + "is %d..%d (%d ticks)%n", glo, glo, hi, hi - glo + 1);
            if (glo < 0) {
                System.out.println("  never on stable+ -- this arrangement is not reachable that "
                        + "way and the question does not arise");
            } else {
                boolean covered = false;
                for (int j = 0; j < 3; j++) {
                    if (j == SUSPECT) continue;
                    int held = 0, onDemanding = 0;
                    for (int t = glo; t <= hi; t++) {
                        if (holds[j][t]) held++;
                        if (demands[t]) onDemanding++;
                    }
                    boolean all = held == hi - glo + 1;
                    covered |= all;
                    System.out.printf("  boid %d holds %d of %d (%d of them demanding)%s%n", j,
                            held, hi - glo + 1, onDemanding, all ? "  <-- covers the whole window" : "");
                }
                System.out.printf("  %s%n", covered
                        ? "A SINGLE LEADER COVERS IT from stable+ -- this is a one-leader exit "
                                + "once stable+ is the ground"
                        : "still no single leader, even from stable+");
            }
        }

        if (out != null) {
            // Four moments, because the finding is a sequence rather than a state: the last tick
            // on settled ground, the two ends of the range where both boids account, and the
            // entry. If it is a handover, it is visible as one neighbour closing while the other
            // opens.
            int[] at = {lo, handLo, handHi, hi};
            String[] caption = new String[at.length];
            for (int i = 0; i < at.length; i++) {
                StringBuilder who = new StringBuilder();
                for (int j = 0; j < 3; j++) {
                    if (j != SUSPECT && holds[j][at[i]]) {
                        who.append(who.isEmpty() ? "" : " and ").append("boid ").append(j);
                    }
                }
                caption[i] = String.format("tick %d — %s", at[i],
                        who.isEmpty() ? "nobody accounts" : who + " accounts");
            }
            strip(preset, exit, frames, at, caption, out);
        }

        MovementLogic rules = new MovementLogic(preset.turningRadius());
        int[] ex = new int[3], ey = new int[3], eh = new int[3];
        for (int j = 0; j < 3; j++) {
            ex[j] = x(map, exit.entryStates()[j]);
            ey[j] = y(map, exit.entryStates()[j]);
            eh[j] = h(exit.entryStates()[j]);
        }
        MovementLogic.Influence in = rules.decompose(new BoidArray(3, ex, ey, eh, entry), SUSPECT);
        double sep = MovementLogic.Influence.across(in.sepX(), in.sepY(), eh[SUSPECT]);
        double coh = MovementLogic.Influence.across(in.cohX(), in.cohY(), eh[SUSPECT]);
        double ali = MovementLogic.Influence.across(in.aliX(), in.aliY(), eh[SUSPECT]);
        System.out.printf("%nat entry: %d seen, %d inside separation, flock asked %+d%n",
                in.seen(), in.close(), in.turn());
        System.out.printf("  across-heading, positive right: separation %+8.2f  cohesion %+7.2f"
                        + "  alignment %+7.2f  total %+8.2f  against a straight bias of %.4f%n",
                sep, coh, ali, sep + coh + ali, Params.STRAIGHT_BIAS);
    }

    private static int x(NavMap map, int state) { return (state / Params.TURNS) % map.width(); }

    private static int y(NavMap map, int state) { return (state / Params.TURNS) / map.width(); }

    private static int h(int state) { return state % Params.TURNS; }

    // ---- the contact sheet ---------------------------------------------------

    private static final int GROUND = 0x14171C;
    private static final int CARD = 0x1B1F26;
    private static final int TEXT = 0xE8ECF3;
    private static final int FAINT = 0x8B94A2;
    private static final int MARK = 0xFFE14D;

    private static String name(int colour) {
        if (colour == ThreeBoidPhase.UNEXPLAINED) return "unexplained";
        if (colour == ThreeBoidPhase.PSYBOID_LED) return "psyboid-led";
        if (colour == ThreeBoidPhase.THIRD_LED) return "third-led";
        if (colour == ThreeBoidPhase.DILUTED) return "diluted";
        if (colour == ThreeBoidPhase.SELF_OVERRIDDEN) return "overridden";
        return String.format("#%06X", colour);
    }

    /** Every sample on one page, because nineteen files is nineteen things to keep in order. */
    private static void sheet(PresetScenarioParameter preset, SolverFacts f, BufferedImage marked,
                              ThreeBoidPhase.Layout lay, List<ThreeBoidPhase.Route> routes,
                              List<Shot> shots, int from, int keep, int columns, int scale,
                              Path out) throws IOException {
        if (shots.isEmpty()) { System.out.println("nothing to draw"); return; }
        MovementLogic rules = new MovementLogic(preset.turningRadius());

        List<BufferedImage> tiles = new ArrayList<>();
        for (Shot s : shots) {
            tiles.add(tile(preset, marked, lay, routes, s, rules, scale));
        }
        int tw = tiles.get(0).getWidth(), th = tiles.get(0).getHeight();
        int gap = 10, head = 64;
        int rows = (tiles.size() + columns - 1) / columns;

        BufferedImage img = new BufferedImage(gap + columns * (tw + gap),
                head + gap + rows * (th + gap), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(GROUND));
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.setColor(new Color(TEXT));
        g.setFont(new Font("SansSerif", Font.BOLD, 18));
        g.drawString(String.format("%s @%s — arc %d->%d: one arrangement per marked region of "
                        + "the three-boid phase map", preset.name(), preset.ingest().hash(),
                from, keep), gap + 4, 26);
        g.setFont(new Font("SansSerif", Font.PLAIN, 12));
        g.setColor(new Color(FAINT));
        g.drawString("drawn at the tick the suspect was steered onto the critical envelope."
                + "   rings: amber = psyboid (boid 0), cyan = third boid (1), red = suspect (2)."
                + "   inset: 200 cells of the marked phase map around this sample's own cell, "
                + "crosshair ticks at its centre", gap + 4, 46);

        for (int i = 0; i < tiles.size(); i++) {
            g.drawImage(tiles.get(i), gap + (i % columns) * (tw + gap),
                    head + gap + (i / columns) * (th + gap), null);
        }
        g.dispose();
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        javax.imageio.ImageIO.write(img, "png", out.toFile());

        System.out.printf("%n  %d tiles, %d per row, %dx%d%n", tiles.size(), columns,
                img.getWidth(), img.getHeight());
        Map<String, Integer> byClass = new TreeMap<>();
        for (Shot s : shots) byClass.merge(name(s.region().base()), 1, Integer::sum);
        System.out.printf("  by class: %s%n", byClass);
        System.out.printf("wrote %s%n", out);
    }

    /** One region's sample: a caption, the arrangement, and where on the map it came from. */
    private static BufferedImage tile(PresetScenarioParameter preset, BufferedImage marked,
                                      ThreeBoidPhase.Layout lay, List<ThreeBoidPhase.Route> routes,
                                      Shot s, MovementLogic rules, int scale) throws IOException {
        BufferedImage shot = ExitRender.image(preset.ingest().display(), s.exit(), s.x(), s.y(),
                s.h(), s.x().length, rules, preset.turningRadius(), scale);

        int cap = 72;
        BufferedImage img = new BufferedImage(shot.getWidth(), shot.getHeight() + cap,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(CARD));
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.drawImage(shot, 0, cap, null);

        Region r = s.region();
        Row row = s.row();
        g.setColor(new Color(s.region().paint()));
        g.fillRect(8, 12, 12, 12);
        g.setColor(new Color(TEXT));
        g.setFont(new Font("SansSerif", Font.BOLD, 15));
        g.drawString(String.format("%s%s  %s over %s  (%,d cells)", r.id(), s.suffix(),
                r.paintName(), name(r.base()), r.size()), 28, 24);
        g.setFont(new Font("SansSerif", Font.PLAIN, 11));
        g.setColor(new Color(FAINT));
        // Kept short enough to finish before the inset; the enum names spelled out do not fit.
        String cause = row.cause().equals("SEPARATION") ? "sep"
                : row.cause().equals("ALIGNMENT_AND_COHESION") ? "ali+coh" : "";
        g.drawString(String.format("psy %s x third %s   cell (%d,%d)   override %s   %s%s%s%s",
                routes.get(r.route()).label(), routes.get(r.otherRoute()).label(), row.x(),
                row.y(), row.overridden() ? "ON" : "off",
                row.level().equals("NONE") ? "no account"
                        : "led by " + (row.leader() == PSYBOID ? "psyboid" : "third boid"),
                cause.isEmpty() ? "" : " via " + cause,
                row.otherExited() ? ", third exited too" : "",
                s.agreed() ? "" : "   [REPLAY DISAGREES]"), 28, 42);
        if (!s.note().isEmpty()) {
            g.setColor(new Color(MARK));
            g.drawString(s.note(), 28, 60);
        }

        highlight(g, s, preset.turningRadius(), scale, cap, shot.getWidth(), shot.getHeight());
        inset(g, marked, lay, r.route(), r.otherRoute(), row.x(), row.y(),
                img.getWidth() - INSET_DST - 10, 10);
        g.dispose();
        return img;
    }

    /**
     * The same arrangement at several ticks, side by side.
     * <p>
     * A handover is not visible in any one frame — every frame just shows three boids. It is
     * visible as one neighbour closing while the other opens, which needs the frames next to
     * each other.
     */
    private static void strip(PresetScenarioParameter preset, ExitAudit.Exit exit, int[][] frames,
                              int[] at, String[] caption, Path out) throws IOException {
        MovementLogic rules = new MovementLogic(preset.turningRadius());
        int scale = 2, cap = 34;
        List<BufferedImage> panels = new ArrayList<>();
        for (int i = 0; i < at.length; i++) {
            int[] fr = frames[at[i]];
            int[] px = {fr[0], fr[3], fr[6]}, py = {fr[1], fr[4], fr[7]},
                    ph = {fr[2], fr[5], fr[8]};
            BufferedImage shot = ExitRender.image(preset.ingest().display(), exit, px, py, ph, 3,
                    rules, preset.turningRadius(), scale);
            BufferedImage one = new BufferedImage(shot.getWidth(), shot.getHeight() + cap,
                    BufferedImage.TYPE_INT_RGB);
            Graphics2D g = one.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(new Color(CARD));
            g.fillRect(0, 0, one.getWidth(), one.getHeight());
            g.drawImage(shot, 0, cap, null);
            g.setColor(new Color(TEXT));
            g.setFont(new Font("SansSerif", Font.BOLD, 15));
            g.drawString(caption[i], 10, 23);
            rings(g, px, py, preset.turningRadius(), scale, cap, shot.getWidth(),
                    shot.getHeight());
            g.dispose();
            panels.add(one);
        }
        int pw = panels.get(0).getWidth(), ph = panels.get(0).getHeight(), gap = 10, head = 44;
        BufferedImage img = new BufferedImage(gap + panels.size() * (pw + gap), head + ph + gap,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(GROUND));
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.setColor(new Color(TEXT));
        g.setFont(new Font("SansSerif", Font.BOLD, 17));
        g.drawString(String.format("%s @%s — the approach to the %d->%d exit at cell R20: "
                        + "rings amber = psyboid (0), cyan = third boid (1), red = suspect (2)",
                preset.name(), preset.ingest().hash(), exit.fromEdge(), exit.toEdge()),
                gap + 4, 27);
        for (int i = 0; i < panels.size(); i++) {
            g.drawImage(panels.get(i), gap + i * (pw + gap), head, null);
        }
        g.dispose();
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        javax.imageio.ImageIO.write(img, "png", out.toFile());
        System.out.printf("%nwrote %s%n", out);
    }

    /** Role rings for one arrangement, given the raw positions. */
    private static void rings(Graphics2D g, int[] px, int[] py, double turningRadius, int scale,
                              int cap, int w, int h) {
        ExitRender.Frame frame = ExitRender.frame(px[SUSPECT], py[SUSPECT], turningRadius, scale);
        int[] role = {ThreeBoidPhase.PSYBOID_LED, ThreeBoidPhase.THIRD_LED, 0xE6194B};
        java.awt.Shape clip = g.getClip();
        g.setClip(0, cap, w, h);
        g.setStroke(new BasicStroke(2f));
        for (int j = 0; j < px.length && j < role.length; j++) {
            g.setColor(new Color(role[j]));
            g.drawOval(frame.px(px[j]) - 12, frame.py(py[j]) + cap - 12, 24, 24);
        }
        g.setClip(clip);
    }

    /**
     * A ring round each of the three boids, coloured by role.
     * <p>
     * On this map the flocking radius reaches almost every pixel, so the crop
     * {@link ExitRender} takes is very nearly the whole play area and three boids in it are
     * three specks. Every tile then looks like every other tile, which defeats the point of a
     * sheet meant to be read at a glance. The rings carry the phase map's own amber/cyan, so
     * "the psyboid did it" and "the third boid did it" mean the same colour in both pictures.
     */
    private static void highlight(Graphics2D g, Shot s, double turningRadius, int scale, int cap,
                                  int w, int h) {
        rings(g, s.x(), s.y(), turningRadius, scale, cap, w, h);
    }

    /**
     * The marked phase map around this sample's cell, in the corner of its own picture.
     * <p>
     * Without it a tile is an arrangement with a serial number, and matching nineteen of those
     * back to nineteen painted blobs is exactly the bookkeeping the sheet exists to remove.
     */
    private static void inset(Graphics2D g, BufferedImage marked, ThreeBoidPhase.Layout lay,
                              int rp, int rb, int cx, int cy, int at, int top) {
        BufferedImage crop = new BufferedImage(INSET_SRC, INSET_SRC, BufferedImage.TYPE_INT_RGB);
        int left = lay.px(rp, cx) - INSET_SRC / 2, up = lay.py(rb, cy) - INSET_SRC / 2;
        for (int y = 0; y < INSET_SRC; y++) {
            for (int x = 0; x < INSET_SRC; x++) {
                int sx = left + x, sy = up + y;
                crop.setRGB(x, y, sx < 0 || sy < 0 || sx >= marked.getWidth()
                        || sy >= marked.getHeight() ? GROUND : marked.getRGB(sx, sy));
            }
        }
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(crop, at, top, INSET_DST, INSET_DST, null);

        g.setStroke(new BasicStroke(1f));
        g.setColor(new Color(FAINT));
        g.drawRect(at - 1, top - 1, INSET_DST + 1, INSET_DST + 1);
        // Ticks pointing at the centre rather than a cross over it, so the cell the tile is a
        // sample of stays visible.
        g.setColor(new Color(MARK));
        int mid = INSET_DST / 2, arm = 12;
        g.drawLine(at + mid, top, at + mid, top + arm);
        g.drawLine(at + mid, top + INSET_DST - arm, at + mid, top + INSET_DST);
        g.drawLine(at, top + mid, at + arm, top + mid);
        g.drawLine(at + INSET_DST - arm, top + mid, at + INSET_DST, top + mid);
    }
}
