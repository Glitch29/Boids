package boids;

import java.util.Arrays;
import java.util.Random;

/**
 * Two checks that keep {@link Aggregation} honest, and the sampling they share.
 * <p>
 * {@link #checkFidelity} proves {@link Aggregation#SIMULATION} is what {@link MovementLogic}
 * actually flies, on real arrangements drawn from the map; {@link #checkClosedForm} proves an
 * aggregation restricted to one neighbour is {@link EdgeInfluence#steer}, which every
 * critical-envelope table rests on. Arrangements are drawn from the map, not from the plane: a
 * boid state is a live navmap state and its neighbours are live states it can actually perceive.
 * <p>
 * This class was the seven-way aggregation survey — pseudo-triangle, amplification, chaos,
 * agreement, the invention factor and the residue test — that chose physics 3. The survey and
 * its numbers are recorded in {@code ROADMAP.md} §0a and {@code GLOSSARY.md}; the code was
 * removed 2026-09-13 with the variants it measured, and is in git at {@code 0116abc}.
 */
public final class AggregationSurvey {
    private AggregationSurvey() {}

    /** The across-heading component of a desired direction, positive to the boid's right. */
    public static double across(double vx, double vy, int heading) {
        int right = Math.floorMod(heading + Params.TURNS / 4, Params.TURNS);
        return vx * Params.COS[right] + vy * Params.SIN[right];
    }

    /** The turn a boid facing {@code heading} picks, given a desired direction. */
    public static int turn(double dirX, double dirY, int heading, Flocking f) {
        if (dirX == 0 && dirY == 0) return 0;
        int best = 0;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int delta : new int[]{0, -1, +1}) {
            int a = Math.floorMod(heading + delta, Params.TURNS);
            double score = Params.COS[a] * dirX + Params.SIN[a] * dirY
                    + (delta == 0 ? f.straightBias() : 0.0);
            if (score > bestScore) { bestScore = score; best = delta; }
        }
        return best;
    }

    /**
     * Fills in the geometry for one deciding boid and a chosen set of neighbours.
     *
     * @return the neighbour count actually perceived, which may be fewer than offered
     */
    public static Aggregation.Neighbours see(NavMap map, MovementLogic rules, int self,
                                             int[] others, int count,
                                             Aggregation.Neighbours into) {
        int turns = Params.TURNS, w = map.width();
        int hd = self % turns, cell = self / turns, x0 = cell % w, y0 = cell / w;
        int n = 0;
        for (int k = 0; k < count; k++) {
            int s = others[k];
            int c = s / turns, x = c % w, y = c / w;
            // The real perception test, not a copy of it. A survey that decided for itself which
            // neighbours were visible would be comparing aggregations over a different flock than
            // the one the simulation flies, and the difference would look like a result.
            double d = rules.perceived(x0, y0, hd, x, y);
            if (d < 0) continue;
            into.ux()[n] = (x - x0) / d;
            into.uy()[n] = (y - y0) / d;
            into.d()[n] = d;
            into.ax()[n] = Params.COS[s % turns];
            into.ay()[n] = Params.SIN[s % turns];
            n++;
        }
        return into.count(n);
    }

    /** The desired direction one aggregation produces. */
    private static void desired(Aggregation a, Aggregation.Neighbours nb, Flocking f,
                                double[] scratch, double[] dir) {
        Arrays.fill(scratch, 0);
        if (nb.n() > 0) a.combine(nb, f, scratch);
        dir[0] = scratch[0] + scratch[2] + scratch[4];
        dir[1] = scratch[1] + scratch[3] + scratch[5];
    }

    /**
     * Proves {@link Aggregation#SIMULATION} really is what {@link MovementLogic} flies.
     * <p>
     * Every comparison in the survey is against a baseline, so a baseline that had drifted from
     * the simulation would make the whole thing a measurement of a transcription error. Checked
     * against the real thing on real arrangements, and loudly.
     */
    public static void checkFidelity(NavMap map, int[] live, int liveCount, Flocking f,
                                      double turningRadius, long seed) {
        MovementLogic rules = new MovementLogic(turningRadius);
        Random rng = new Random(seed ^ 0x5eed);
        double[] scratch = new double[6], dir = new double[2];
        Aggregation.Neighbours nb = Aggregation.Neighbours.of(8);
        int turns = Params.TURNS, w = map.width();
        int checked = 0, disagreed = 0;
        double worst = 0;

        for (int t = 0; t < 200_000 && checked < 20_000; t++) {
            int n = 2 + rng.nextInt(3);
            int[] states = new int[n + 1];
            for (int i = 0; i <= n; i++) states[i] = live[rng.nextInt(liveCount)];
            int[] xs = new int[n + 1], ys = new int[n + 1], hs = new int[n + 1];
            for (int i = 0; i <= n; i++) {
                int c = states[i] / turns;
                xs[i] = c % w;
                ys[i] = c / w;
                hs[i] = states[i] % turns;
            }
            MovementLogic.Influence in = rules.decompose(new BoidArray(n + 1, xs, ys, hs, 0), 0);
            if (in.seen() < 2) continue;
            checked++;

            int[] others = new int[n];
            System.arraycopy(states, 1, others, 0, n);
            Aggregation.Neighbours seen = see(map, rules, states[0], others, n, nb);
            desired(Aggregation.SIMULATION, seen, f, scratch, dir);
            double gap = Math.hypot(dir[0] - in.dirX(), dir[1] - in.dirY());
            worst = Math.max(worst, gap);
            if (turn(dir[0], dir[1], hs[0], f) != in.turn()) disagreed++;
        }
        System.out.printf("%nfidelity of Aggregation.SIMULATION against MovementLogic over %,d "
                        + "arrangements: %d turn disagreements, worst vector gap %.3e%n",
                checked, disagreed, worst);
        if (disagreed > 0 || worst > 1e-9) {
            System.out.println("  *** THE BASELINE IS NOT THE SIMULATION -- every number below "
                    + "is measured against the wrong thing ***");
        }
    }

    /**
     * Proves the single-neighbour closed form is the aggregation restricted to one neighbour.
     * <p>
     * {@link EdgeInfluence#steer} is what the whole critical-envelope analysis reasons with, and
     * {@link Aggregation} is what the simulation flies. They are two implementations of the same
     * thing at {@code n = 1}, coupled only by {@link Aggregation#separationFalloffAtOne()} — which
     * is precisely the kind of coupling that drifts silently and invalidates every table built
     * afterwards. So it is checked rather than trusted, for every variant.
     *
     * @return true if all of them agree
     */
    public static boolean checkClosedForm(NavMap map, int[] live, int liveCount, Flocking base,
                                          double turningRadius, long seed, int trials) {
        return checkClosedForm(map, live, liveCount, base, turningRadius, seed, trials,
                Aggregation.ALL);
    }

    /**
     * The same, over a chosen set.
     * <p>
     * <b>Not every aggregation has a closed form, and that is a property worth reporting rather
     * than a failure to route around.</b> {@code VOTE_NORM} renormalises the total, so its
     * one-neighbour behaviour is not {@link EdgeInfluence#steer} at any setting — which
     * disqualifies it from being adopted without rewriting the whole critical-envelope analysis,
     * and is the reason it exists only as a control.
     */
    public static boolean checkClosedForm(NavMap map, int[] live, int liveCount, Flocking base,
                                          double turningRadius, long seed, int trials,
                                          Aggregation... which) {
        MovementLogic rules = new MovementLogic(turningRadius);
        Random rng = new Random(seed ^ 0xc10ed);
        double[] scratch = new double[6], dir = new double[2];
        Aggregation.Neighbours nb = Aggregation.Neighbours.of(2);
        boolean allWell = true;
        System.out.printf("%nclosed form vs aggregation at n=1:%n");
        for (Aggregation a : which) {
            Flocking f = base.sepFalloff(a.separationFalloffAtOne());
            int checked = 0, turnGap = 0;
            double worst = 0;
            for (int t = 0; t < trials * 8 && checked < trials; t++) {
                int self = live[rng.nextInt(liveCount)];
                Aggregation.Neighbours one = see(map, rules, self,
                        new int[]{live[rng.nextInt(liveCount)]}, 1, nb);
                if (one.n() != 1) continue;
                checked++;
                int heading = self % Params.TURNS;
                desired(a, one, f, scratch, dir);
                int mine = turn(dir[0], dir[1], heading, f);
                int theirs = EdgeInfluence.steer(heading,
                        (int) Math.round(one.d()[0] * one.ux()[0]),
                        (int) Math.round(one.d()[0] * one.uy()[0]), headingOf(one), f);
                if (mine != theirs) turnGap++;
                worst = Math.max(worst, Math.abs(mine - theirs));
            }
            boolean ok = turnGap == 0;
            allWell &= ok;
            System.out.printf("  %-16s falloff=%-5s %,6d checked, %d disagreements%s%n", a.id(),
                    a.separationFalloffAtOne(), checked, turnGap,
                    ok ? "" : "   *** THE CLOSED FORM AND THE SIMULATION DISAGREE ***");
        }
        return allWell;
    }

    /** The heading index a neighbour's unit heading vector came from. */
    private static int headingOf(Aggregation.Neighbours nb) {
        double best = -2;
        int at = 0;
        for (int h = 0; h < Params.TURNS; h++) {
            double dot = Params.COS[h] * nb.ax()[0] + Params.SIN[h] * nb.ay()[0];
            if (dot > best) { best = dot; at = h; }
        }
        return at;
    }

}
