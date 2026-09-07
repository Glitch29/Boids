package boids;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Where a psyboid could lead another boid off its route, and whether standing there actually
 * does it.
 *
 * <h2>The direction nobody has tested</h2>
 * Every window in this project was derived, and every check on one has been <b>backwards</b>:
 * {@link ExitAudit} takes exits that happened and asks whether a leader was in position to
 * account for them. That establishes the windows are not too <em>narrow</em> — an exit outside
 * every band would be unexplained — and says nothing at all about whether they are too
 * <em>wide</em>. A band that admits every placement explains every exit and predicts none.
 * <p>
 * <b>The forward question is the one a psyboid needs answered:</b> put a leader inside the band
 * and fly it — does the other boid leave? {@link #trial} asks exactly that, two boids at a time,
 * against a control with the leader outside the band. The gap between the two is the only
 * evidence that a window is a lever rather than a description.
 *
 * <h2>Why an estimate is worth having even so</h2>
 * Whether a chance <em>materialises</em> will not reduce to a table. Exits chain — inducing one
 * can carry a second boid for free — and at four or ten boids the arrangement is chaotic enough
 * that only lookahead settles it. But lookahead is the expensive thing, and most of the timeline
 * offers no chance at all. Knowing cheaply <b>where a lead is even possible</b> is what makes it
 * affordable to branch only there.
 */
public final class Herding {
    private Herding() {}

    /**
     * One row of a window, with what it would cost the psyboid to be standing in it.
     *
     * @param onRoute whether the leader edge is one the price-optimal psyboid already flies. If
     *                it is, leading costs only <em>phase</em> — being there at the right moment —
     *                and if it is not, it costs a detour off the scoring cycle as well
     * @param bias    the price function's value of the leader edge. Differences against the
     *                psyboid's own route are the exchange rate for the detour
     */
    public record Chance(int from, int keep, double tau, int leaderEdge, double lo, double hi,
                         boolean vacuous, boolean onRoute, double bias) {

        public double width() { return hi - lo; }
    }

    /**
     * Every non-vacuous band on the map, and whether the psyboid can reach it cheaply.
     * <p>
     * <b>The headline is the last line</b>: how much of each arc's tau range has a usable band on
     * an edge the psyboid already flies. That is the fraction of a lap during which leading is
     * even geometrically available, and it is the number that says whether a map rewards herding
     * at all.
     */
    public static List<Chance> inventory(PresetScenarioParameter preset, SolverFacts f,
                                         EdgePrice.Price price) {
        boolean[] onCycle = new boolean[f.edges()];
        if (price.best() != null) for (int e : price.best().edges()) onCycle[e] = true;

        List<Chance> all = new ArrayList<>();
        System.out.printf("%n=== %s @%s: where a psyboid could lead ===%n", preset.name(),
                preset.ingest().hash());
        System.out.printf("price-optimal cycle %s, gain %.6f%n",
                price.best() == null ? "none" : Arrays.toString(price.best().edges()),
                price.gain());

        for (SolverFacts.Window w : f.windows()) {
            List<Chance> here = new ArrayList<>();
            for (SolverFacts.Band b : w.bands()) {
                here.add(new Chance(w.from(), w.keep(), b.tau(), b.leaderEdge(), b.lo(), b.hi(),
                        f.vacuous(b), onCycle[b.leaderEdge()], price.bias()[b.leaderEdge()]));
            }
            all.addAll(here);

            long usable = here.stream().filter(c -> !c.vacuous()).count();
            long onRoute = here.stream().filter(c -> !c.vacuous() && c.onRoute()).count();
            System.out.printf("%n-- window %d->%d, opens at tau %.0f, %d bands --%n", w.from(),
                    w.keep(), w.opens(), w.bands().length);
            System.out.printf("   %d non-vacuous, %d of those on the psyboid's own cycle%n",
                    usable, onRoute);

            // Per leader edge, because the question a psyboid asks is "can I be there at all",
            // and that is a property of the edge before it is a property of the tau.
            for (int e = 0; e < f.edges(); e++) {
                final int edge = e;
                List<Chance> on = here.stream().filter(c -> c.leaderEdge() == edge).toList();
                List<Chance> good = on.stream().filter(c -> !c.vacuous()).toList();
                if (on.isEmpty()) continue;
                double loTau = good.stream().mapToDouble(Chance::tau).min().orElse(Double.NaN);
                double hiTau = good.stream().mapToDouble(Chance::tau).max().orElse(Double.NaN);
                double meanWidth = good.stream().mapToDouble(Chance::width).average().orElse(0);
                System.out.printf("   leader on edge %d%s: %d bands, %d usable%s%n", e,
                        onCycle[e] ? " (on the cycle)" : " (OFF the cycle)", on.size(), good.size(),
                        good.isEmpty() ? ""
                                : String.format(", led tau %.0f..%.0f, mean band %.1f ticks wide",
                                        loTau, hiTau, meanWidth));
            }
        }
        return all;
    }

    /**
     * What one trial found.
     *
     * @param placed    arrangements flown
     * @param exited    how many left {@code from} by {@code keep}
     * @param straight  how many left it the way unsteered travel goes
     * @param elsewhere how many left it some third way, or never left at all
     */
    public record Result(String what, int placed, int exited, int straight, int elsewhere) {

        public double rate() { return placed == 0 ? Double.NaN : exited / (double) placed; }

        @Override
        public String toString() {
            return String.format("%-28s %5d placed, %5d exited (%5.1f%%), %5d straight, %5d other",
                    what, placed, exited, 100 * rate(), straight, elsewhere);
        }
    }

    /**
     * Places a leader where a band says it should be, flies two boids, and counts exits.
     * <p>
     * <b>Two boids, and both of them real.</b> The leader is not overridden and not held — it is
     * an ordinary boid standing in the place the window names, which is the only arrangement the
     * claim is about. It also means the leader is itself pushed by the boid it is leading, which
     * is part of the phenomenon rather than noise in it.
     * <p>
     * <b>The control places the leader outside every band at that tau</b> and is the whole point:
     * a band that converts no better than a random placement is a description of exits, not a
     * cause of them.
     *
     * @param sample how many arrangements to draw per band, at most
     */
    public static Result[] trial(PresetScenarioParameter preset, SolverFacts f,
                                 Pipeline.Labelling l, int from, int keep, int sample, long seed)
            throws java.io.IOException {
        SolverFacts.Window window = null;
        for (SolverFacts.Window w : f.windows()) {
            if (w.from() == from && w.keep() == keep) window = w;
        }
        if (window == null) {
            return new Result[]{new Result("no window " + from + "->" + keep, 0, 0, 0, 0)};
        }

        ScenarioParameter pair = new ScenarioParameter() {
            public java.nio.file.Path mapPath() { return preset.mapPath(); }
            public float turningRadius() { return preset.turningRadius(); }
            public int flockSize() { return 2; }
        };
        Boids2DEngine engine = new Boids2DEngine(pair);
        NavMap map = l.map();
        java.util.Random rng = new java.util.Random(seed);

        // States indexed by edge, so a tau can be turned into somewhere to stand.
        List<List<Integer>> byEdge = new ArrayList<>();
        for (int e = 0; e < f.edges(); e++) byEdge.add(new ArrayList<>());
        for (int i = 0; i < l.liveCount(); i++) {
            int s = l.live()[i];
            if (l.edge()[s] >= 0) byEdge.get(l.edge()[s]).add(s);
        }

        int[][] counts = new int[3][3];   // [inBand, control, alone][exited, straight, other]
        int[] placed = new int[3];
        // Per leader edge and per band-width bucket, because the aggregate mixes a 439-tick band
        // with an 8-tick one and a psyboid has to know which of those it is being offered.
        int[] byEdgePlaced = new int[f.edges()], byEdgeExited = new int[f.edges()];
        int[] byEdgeControl = new int[f.edges()], byEdgeBands = new int[f.edges()];
        double[] byEdgeWidth = new double[f.edges()];
        int[] byWidthPlaced = new int[WIDTHS.length + 1];
        int[] byWidthExited = new int[WIDTHS.length + 1];

        // Every band, vacuous or not. The vacuity test asks whether a band spans 90% of its edge,
        // which is the right idea at the wrong scale on a long one — and the conversion measured
        // here is a better answer to the same question than any fraction of an edge could be.
        for (SolverFacts.Band b : window.bands()) {
            List<Integer> targets = near(byEdge.get(from), f, b.tau());
            if (targets.isEmpty()) continue;
            int e = b.leaderEdge(), bucket = bucket(b.width());
            byEdgeBands[e]++;
            byEdgeWidth[e] += b.width();
            for (int k = 0; k < sample; k++) {
                int target = targets.get(rng.nextInt(targets.size()));

                // In the band, outside it on the same edge, and with the partner far away.
                Integer inBand = pick(rng, at(byEdge.get(b.leaderEdge()), f, b.lo(), b.hi()));
                Integer outside = pick(rng, outside(byEdge.get(b.leaderEdge()), f, b.lo(), b.hi()));
                if (inBand == null || outside == null) continue;

                int before = counts[0][0];
                score(engine, map, f, target, inBand, from, keep, counts[0]);
                placed[0]++;
                byEdgePlaced[e]++;
                byWidthPlaced[bucket]++;
                if (counts[0][0] > before) { byEdgeExited[e]++; byWidthExited[bucket]++; }

                int wasControl = counts[1][0];
                score(engine, map, f, target, outside, from, keep, counts[1]);
                placed[1]++;
                if (counts[1][0] > wasControl) byEdgeControl[e]++;

                score(engine, map, f, target, target, from, keep, counts[2]);
                placed[2]++;
            }
        }

        System.out.printf("   %-6s %7s %9s %9s %9s %9s%n", "leader", "bands", "mean w", "placed",
                "exited", "control");
        for (int e = 0; e < f.edges(); e++) {
            if (byEdgePlaced[e] == 0) continue;
            System.out.printf("   %-6d %7d %9.1f %9d %8.1f%% %8.1f%%%n", e, byEdgeBands[e],
                    byEdgeWidth[e] / byEdgeBands[e], byEdgePlaced[e],
                    100.0 * byEdgeExited[e] / byEdgePlaced[e],
                    100.0 * byEdgeControl[e] / byEdgePlaced[e]);
        }
        System.out.printf("   %-14s %9s %9s%n", "band width", "placed", "exited");
        for (int i = 0; i <= WIDTHS.length; i++) {
            if (byWidthPlaced[i] == 0) continue;
            System.out.printf("   %-14s %9d %8.1f%%%n",
                    i == WIDTHS.length ? "over " + WIDTHS[WIDTHS.length - 1]
                            : (i == 0 ? "0" : String.valueOf(WIDTHS[i - 1])) + "-" + WIDTHS[i],
                    byWidthPlaced[i], 100.0 * byWidthExited[i] / byWidthPlaced[i]);
        }

        return new Result[]{
                new Result("leader inside the band", placed[0], counts[0][0], counts[0][1],
                        counts[0][2]),
                new Result("leader outside it, same edge", placed[1], counts[1][0], counts[1][1],
                        counts[1][2]),
                new Result("no leader (alone)", placed[2], counts[2][0], counts[2][1],
                        counts[2][2]),
        };
    }

    /** Band-width buckets, in ticks. A band's width is how much it does not constrain. */
    private static final double[] WIDTHS = {5, 15, 40, 100, 300};

    private static int bucket(double width) {
        for (int i = 0; i < WIDTHS.length; i++) if (width <= WIDTHS[i]) return i;
        return WIDTHS.length;
    }

    /**
     * Flies the pair until the target leaves {@code from}, and says which way it went.
     * <p>
     * The second boid is placed on top of the first for the solitary control, which the engine
     * tolerates and which makes the two see each other as a single coincident neighbour; that is
     * as near to alone as a two-boid array gets without a separate scenario.
     */
    private static void score(Boids2DEngine engine, NavMap map, SolverFacts f, int target,
                              int leader, int from, int keep, int[] into) {
        int turns = Params.TURNS, w = map.width();
        int[] x = {target / turns % w, leader / turns % w};
        int[] y = {target / turns / w, leader / turns / w};
        int[] h = {target % turns, leader % turns};
        boolean alone = target == leader;
        if (alone) { x[1] = x[0]; y[1] = y[0]; h[1] = h[0]; }

        Sim.State s = new Sim.State(2, x, y, h, 0L, 0L, new long[2], "trial");
        for (int t = 0; t < 4096; t++) {
            s = engine.tick(s);
            int now = f.edgeAt(s.x[0], s.y[0], s.h[0]);
            if (now == from || now < 0) continue;
            into[now == keep ? 0 : now == f.straightTo()[from] ? 1 : 2]++;
            return;
        }
        into[2]++;
    }

    /** States on an edge whose tau lies within half a tick of {@code tau}. */
    private static List<Integer> near(List<Integer> states, SolverFacts f, double tau) {
        return at(states, f, tau - 0.5, tau + 0.5);
    }

    /** States on an edge whose tau lies in {@code [lo, hi]}. */
    private static List<Integer> at(List<Integer> states, SolverFacts f, double lo, double hi) {
        List<Integer> out = new ArrayList<>();
        for (int s : states) {
            double t = f.tickOf()[s];
            if (!Double.isNaN(t) && t >= lo && t <= hi) out.add(s);
        }
        return out;
    }

    /** And the complement, which is what a control needs. */
    private static List<Integer> outside(List<Integer> states, SolverFacts f, double lo,
                                         double hi) {
        List<Integer> out = new ArrayList<>();
        for (int s : states) {
            double t = f.tickOf()[s];
            if (!Double.isNaN(t) && (t < lo || t > hi)) out.add(s);
        }
        return out;
    }

    private static Integer pick(java.util.Random rng, List<Integer> from) {
        return from.isEmpty() ? null : from.get(rng.nextInt(from.size()));
    }
}
