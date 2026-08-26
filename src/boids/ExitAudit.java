package boids;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Watches a run and accounts for every edge a boid leaves by a route holding straight would
 * not have taken.
 * <p>
 * A boid does not choose. Left alone it holds its heading, so a boid that changes edge in a
 * way straight travel would not is evidence that something acted on it, and the point of this
 * is to name what. Two things can: the boid was a psyboid under an override, or another boid
 * was placed so as to turn it. If neither holds, the classification has failed and something
 * is wrong — either in the account of what can steer a boid, or in the belief that a single
 * neighbour is always enough.
 * <p>
 * <b>Sufficiency is tested directly, not inferred from a window.</b> For each candidate the
 * suspect's decision is recomputed with that candidate as its only neighbour, and the
 * candidate counts as a leader when doing so lands the suspect on the edge it actually
 * reached. That is exact and needs no tolerances. Windows are the version of this question
 * answerable from a single photograph, which is a different and later problem; what is
 * recorded here is enough to check a window against afterwards.
 * <p>
 * <b>Where a failure would come from.</b> Flocking sums its neighbours before choosing, so two
 * boids can between them produce a turn neither would produce alone. Nothing rules that out,
 * and a failure here is the signal that it happened — which is why the failure is reported
 * with its whole arrangement rather than merely counted.
 */
public final class ExitAudit implements Boids2DEngine.Trace {

    /**
     * @param suspectTick where along its own edge the suspect was, by the clock
     * @param leaders     how many single neighbours would each have been enough on their own
     * @param seen        neighbours in the suspect's flocking vision at the moment it decided
     */
    public record Exit(long tick, int suspect, int x, int y, int heading, int want,
                       int fromEdge, int toEdge, int straightEdge, double suspectTick,
                       boolean overridden, int leaders, int firstLeader, int leaderEdge,
                       double leaderTick, int seen, int widened, int separating) {

        /** Whether widening the constants found a leader the true ones did not. */
        public boolean coveredByWidening() {
            return leaders == 0 && widened > 0;
        }

        /** An exit nothing accounts for. */
        public boolean unexplained() {
            return !overridden && leaders == 0;
        }
    }

    private final NavMap map;
    private final int[] edge;
    private final double[] tick;
    private final MovementLogic rules;
    private final PsyboidOverride[] overrides;
    private final int[] straightTo;

    private final List<Exit> exits = new ArrayList<>();
    private long decisions, edgeChanges;

    private final double turningRadius;
    private final Flocking strict;
    private Flocking relaxed;
    private Path background, renderDir;
    private int drawn, perRun;

    public ExitAudit(NavMap map, int[] edge, double[] tick, int[] straightTo,
                     double turningRadius, PsyboidOverride[] overrides) {
        this.map = map;
        this.edge = edge;
        this.tick = tick;
        this.straightTo = straightTo;
        this.turningRadius = turningRadius;
        this.rules = new MovementLogic(turningRadius);
        this.strict = Flocking.of(turningRadius);
        this.overrides = overrides == null ? new PsyboidOverride[0] : overrides;
    }

    /**
     * Also ask the leader question at widened constants, so an exit no true leader accounts
     * for can still be checked against the window a cover would actually use.
     * <p>
     * A window is not the set of neighbours that would each suffice on their own — it is the
     * set a cover is willing to call leaders, drawn wide enough to survive the influences a
     * one-neighbour model cannot see. Counting how many failures the widened constants pick up
     * is the test of whether that widening is enough.
     */
    public ExitAudit widenedBy(Flocking relaxed) {
        this.relaxed = relaxed;
        return this;
    }

    /**
     * Draw the arrangement whenever an exit goes unexplained.
     *
     * @param dir    where the pictures go; {@code render/recent} by convention, whose files
     *               are overwritten freely and are for glancing at rather than keeping
     * @param perRun how many to draw before giving up on the rest
     */
    public ExitAudit rendering(Path background, Path dir, int perRun) {
        this.background = background;
        this.renderDir = dir;
        this.perRun = perRun;
        return this;
    }

    public List<Exit> exits() { return exits; }

    @Override
    public void decided(long at, int i, BoidArray boids, int want) {
        decisions++;
        int s = map.index(boids.x()[i], boids.y()[i], boids.h()[i]);
        int actual = map.successor(s, want);
        if (actual < 0) return;
        int straight = map.successor(s, 0);
        if (straight < 0) return;
        int from = edge[s], to = edge[actual];
        // Only route changes. Leaving an edge a tick sooner or later than straight travel
        // would have is a perturbation along the route the boid was already on, not a
        // different route, and there is no window to be found in it.
        if (from < 0 || to < 0 || to == from || to == straightTo[from]) return;
        // And only the turn that commits the boid, not the straight step that finishes the
        // job. Once a leader has turned a boid into the wrong branch it is still labelled on
        // the edge it is leaving for a few ticks, and straight travel from there completes
        // the crossing -- 16 such states on edge 4, 20 on edge 2, 18 on edge 5. Those are the
        // same event seen a tick later, and they have no leader of their own to find.
        if (to == edge[straight]) return;
        edgeChanges++;

        boolean overridden = false;
        for (PsyboidOverride o : overrides) {
            if (o.psyboid() == i && at >= o.onset() && at < o.onset() + o.duration()) {
                overridden = true;
                break;
            }
        }

        // Each neighbour tried on its own, at the true constants and then again at the
        // widened ones. The arrangement handed in is the one the suspect decided against --
        // boids before it this tick have moved, boids after it have not -- so the candidates
        // are placed exactly where the suspect saw them.
        int leaders = 0, firstLeader = -1, widened = 0, separating = 0;
        for (int j = 0; j < boids.n(); j++) {
            if (j == i) continue;
            if (alone(boids, i, j, s, to, strict)) {
                if (leaders++ == 0) firstLeader = j;
            }
            if (relaxed != null && alone(boids, i, j, s, to, relaxed)) {
                widened++;
                double dx = boids.x()[j] - boids.x()[i], dy = boids.y()[j] - boids.y()[i];
                if (strict.separating(Math.sqrt(dx * dx + dy * dy))) separating++;
            }
        }

        int leaderEdge = -1;
        double leaderTick = Double.NaN;
        if (firstLeader >= 0) {
            int p = map.index(boids.x()[firstLeader], boids.y()[firstLeader],
                    boids.h()[firstLeader]);
            leaderEdge = edge[p];
            leaderTick = tick[p];
        }

        Exit e = new Exit(at, i, boids.x()[i], boids.y()[i], boids.h()[i], want,
                from, to, edge[straight], tick[s], overridden, leaders,
                firstLeader, leaderEdge, leaderTick, rules.vision(boids, i).seen(),
                widened, separating);
        exits.add(e);

        // Drawn here rather than afterwards: the arrangement handed in is the live mid-tick
        // one and is overwritten as soon as this returns, so a picture of it has to be taken
        // now or not at all.
        if (renderDir != null && e.unexplained() && drawn < perRun) {
            int seq = drawn++;
            try {
                ExitRender.write(background,
                        renderDir.resolve(String.format("exit_%d_%d_%d.png", e.fromEdge(), e.toEdge(), seq)), e,
                        boids.x(), boids.y(), boids.h(), boids.n(), rules, turningRadius, 4);
            } catch (IOException io) {
                // A diagnostic that cannot be written is not a reason to stop the run it is
                // diagnosing.
                System.out.printf("  (could not draw exit: %s)%n", io.getMessage());
            }
        }
    }

    /**
     * Would this one neighbour, alone, have sent the suspect to the edge it reached?
     * <p>
     * Run through {@link EdgeInfluence#steer}, which is the single-neighbour specialisation of
     * the flocking rules and takes its constants as an argument. That is what lets the same
     * question be asked twice — once at what the boids actually do, once at the widened
     * constants a window is drawn under.
     */
    private boolean alone(BoidArray boids, int i, int j, int s, int landed, Flocking f) {
        int turn = EdgeInfluence.steer(boids.h()[i], boids.x()[j] - boids.x()[i],
                boids.y()[j] - boids.y()[i], boids.h()[j], f);
        int to = map.successor(s, turn);
        return to >= 0 && edge[to] == landed;
    }

    /** What the run showed, and whether anything went unaccounted for. */
    public String summary() {
        java.util.Map<String, long[]> byMove = new java.util.TreeMap<>();
        long[] all = new long[6];
        for (Exit e : exits) {
            long[] row = byMove.computeIfAbsent(e.fromEdge() + "->" + e.toEdge(),
                    k -> new long[6]);
            for (long[] r : new long[][]{all, row}) {
                r[0]++;
                if (e.overridden()) r[1]++;
                if (e.leaders() > 0) r[2]++;
                if (e.unexplained()) r[3]++;
                if (e.unexplained() && e.coveredByWidening()) r[4]++;
                if (e.unexplained() && e.coveredByWidening() && e.separating() > 0) r[5]++;
            }
        }
        StringBuilder s = new StringBuilder();
        s.append(String.format("%,d decisions, %,d route changes%n", decisions, edgeChanges));
        s.append(String.format("  %-10s %8s %8s %8s %12s %9s %11s%n", "move", "count",
                "psyboid", "led", "unexplained", "widened", "of those sep"));
        byMove.put("ALL", all);
        for (var it : byMove.entrySet()) {
            long[] r = it.getValue();
            s.append(String.format("  %-10s %8d %8d %8d %12d %9d %11d%s%n", it.getKey(),
                    r[0], r[1], r[2], r[3], r[4], r[5],
                    r[3] > r[4] ? "   <-- " + (r[3] - r[4]) + " STILL UNCOVERED" : ""));
        }
        return s.toString();
    }

    /** Every exit, one per row, with enough to check a window against later. */
    public void write(Path file) throws IOException {
        StringBuilder rows = new StringBuilder("tick\tsuspect\tx\ty\td\twant\tfromEdge"
                + "\ttoEdge\tstraightEdge\tsuspectTick\toverridden\tleaders\tfirstLeader"
                + "\tleaderEdge\tleaderTick\tseen\twidened\tseparating\n");
        for (Exit e : exits) {
            rows.append(e.tick()).append('\t').append(e.suspect()).append('\t')
                    .append(e.x()).append('\t').append(e.y()).append('\t').append(e.heading())
                    .append('\t').append(e.want()).append('\t').append(e.fromEdge())
                    .append('\t').append(e.toEdge()).append('\t').append(e.straightEdge())
                    .append('\t').append(String.format("%.3f", e.suspectTick())).append('\t')
                    .append(e.overridden()).append('\t').append(e.leaders()).append('\t')
                    .append(e.firstLeader()).append('\t').append(e.leaderEdge()).append('\t')
                    .append(String.format("%.3f", e.leaderTick())).append('\t')
                    .append(e.seen()).append('\t').append(e.widened()).append('\t')
                    .append(e.separating()).append('\n');
        }
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Files.writeString(file, rows.toString());
    }
}
