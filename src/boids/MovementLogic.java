package boids;

import java.lang.Override;

/**
 * The default boid. A pure function from world state to a turn; it mutates nothing.
 * <p>
 * Reynolds' three rules are reduced to a single desired direction vector, and the
 * boid picks whichever of its three reachable headings best aligns with it.
 */
public final class MovementLogic implements MovementControl{

    /** Turn candidates in fixed evaluation order: hold, left, right. */
    private static final int[] CANDIDATES = {0, -1, +1};

    private final double rSep;
    private final double rFlock;

    /**
     * A different way of condensing several neighbours into one direction, or null for the
     * simulation's own.
     * <p>
     * <b>Null is the default everywhere and the code path below is untouched by this field
     * being present.</b> A survey of alternatives has to be able to fly one, and the only
     * honest way to compare is to leave the thing being compared against exactly as it was —
     * so the alternative is a branch taken only when one is asked for, not a refactor of the
     * original into a special case of something more general.
     */
    private final Aggregation aggregation;

    private final Flocking flock;

    /**
     * Perception radii scale with the turning radius, so this is per-scenario rather
     * than a shared singleton. A boid must begin reacting to a neighbour far enough
     * out that it can actually turn away in time.
     */
    public MovementLogic(double turningRadius) {
        this(turningRadius, null);
    }

    /** The same, deciding with a different aggregation. See {@link Aggregation}. */
    public MovementLogic(double turningRadius, Aggregation aggregation) {
        this.rSep = Params.separation(turningRadius);
        this.rFlock = Params.flock(turningRadius);
        this.aggregation = aggregation;
        this.flock = Flocking.of(turningRadius);
    }

    private static double len(double a, double b) {
        return Math.sqrt(a * a + b * b);
    }

    /**
     * How far away boid {@code j} is, or {@code -1} if boid {@code i} cannot see it.
     * <p>
     * The single definition of what a boid perceives, so that anything wanting to count
     * neighbours counts the same ones the rules act on. Written once rather than twice
     * because the two drifting apart would not fail — it would quietly answer a slightly
     * different question than the one the dynamics answer.
     */
    private double perceived(BoidArray s, int i, int j, double hx, double hy) {
        return perceived(s.x()[i], s.y()[i], hx, hy, s.x()[j], s.y()[j]);
    }

    /**
     * The same test against loose coordinates, for anything holding positions rather than a
     * flock — a diagnostic drawing what a boid could see, say. Same definition, not a copy of
     * it.
     */
    public double perceived(int xi, int yi, int hi, int xj, int yj) {
        return perceived(xi, yi, Params.COS[hi], Params.SIN[hi], xj, yj);
    }

    private double perceived(int xi, int yi, double hx, double hy, int xj, int yj) {
        double dx = xj - xi;
        double dy = yj - yi;
        double d2 = dx * dx + dy * dy;
        if (d2 == 0.0 || d2 > rFlock * rFlock) return -1;

        double d = Math.sqrt(d2);

        // dx*hx + dy*hy is d*cos(angle off the heading), so this drops anything
        // in the rear blind arc.
        return dx * hx + dy * hy < Params.COS_FOV * d ? -1 : d;
    }

    /**
     * How many neighbours a boid can see, and how many of those are close enough to push
     * back.
     *
     * @param close those within the separation radius, always a subset of {@code seen}.
     *              Worth separating because the two regimes steer oppositely: a lone
     *              neighbour out in the flocking annulus is approached, the same
     *              neighbour inside separation is fled
     */
    public record Vision(int seen, int close) {}

    /**
     * What boid {@code i} can see in the given arrangement.
     * <p>
     * Takes the array rather than reading a simulation, so a caller inside a tick sees
     * exactly what the boid saw: boids are advanced in index order, and by the time
     * {@code i} decides, its predecessors have already moved.
     */
    public Vision vision(BoidArray s, int i) {
        double hx = Params.COS[s.h()[i]];
        double hy = Params.SIN[s.h()[i]];
        int seen = 0, close = 0;
        for (int j = 0; j < s.n(); j++) {
            if (j == i) continue;
            double d = perceived(s, i, j, hx, hy);
            if (d < 0) continue;
            seen++;
            if (d < rSep) close++;
        }
        return new Vision(seen, close);
    }

    /**
     * The three rules' contributions separately, as they enter the decision.
     * <p>
     * Exposed because the interesting failures are about <b>superposition</b>: each term is
     * normalised to unit length before weighting, so one neighbour inside the separation radius
     * contributes the whole of {@code W_SEP} while any number of neighbours out in the annulus
     * share a single {@code W_COH} and {@code W_ALI} between them, partially cancelling. A
     * combination can therefore turn a boid that no member of it would turn alone, and reading
     * that off requires the terms apart rather than summed.
     * <p>
     * Vectors are already weighted, so their sum is exactly what the turn is chosen against.
     *
     * @param acted false when nothing was in view and the boid simply holds its heading, which
     *              is not the same as the terms cancelling to zero
     */
    public record Influence(double sepX, double sepY, double cohX, double cohY,
                            double aliX, double aliY, int seen, int close,
                            int turn, boolean acted) {
        public double dirX() { return sepX + cohX + aliX; }
        public double dirY() { return sepY + cohY + aliY; }

        /**
         * A term's component across the boid's heading, positive to its right.
         * <p>
         * The only part of an influence that can change a heading; the component along the
         * heading pushes the boid where it was already going.
         */
        public static double across(double vx, double vy, int heading) {
            int right = Math.floorMod(heading + Params.TURNS / 4, Params.TURNS);
            return vx * Params.COS[right] + vy * Params.SIN[right];
        }
    }

    /**
     * The full decision, with the terms kept apart. {@link #calculate} is this plus an assignment,
     * so there is one implementation of the rules rather than a diagnostic copy that can drift.
     */
    public Influence decompose(BoidArray s, int i) {
        if (aggregation != null) return variant(s, i);
        final double hx = Params.COS[s.h()[i]];
        final double hy = Params.SIN[s.h()[i]];
        final double xi = s.x()[i];
        final double yi = s.y()[i];

        double sepX = 0, sepY = 0, cohX = 0, cohY = 0, aliX = 0, aliY = 0;
        int seen = 0, close = 0;
        for (int j = 0; j < s.n(); j++) {
            if (j == i) continue;
            double d = perceived(s, i, j, hx, hy);
            if (d < 0) continue;
            seen++;
            double dx = s.x()[j] - xi, dy = s.y()[j] - yi;
            cohX += dx;
            cohY += dy;
            aliX += Params.COS[s.h()[j]];
            aliY += Params.SIN[s.h()[j]];
            if (d < rSep) {
                close++;
                double falloff = (rSep - d) / rSep;
                sepX -= dx / d * falloff;
                sepY -= dy / d * falloff;
            }
        }

        double m;
        double wSepX = 0, wSepY = 0, wCohX = 0, wCohY = 0, wAliX = 0, wAliY = 0;
        if ((m = len(sepX, sepY)) > 0) { wSepX = Params.W_SEP * sepX / m; wSepY = Params.W_SEP * sepY / m; }
        if ((m = len(cohX, cohY)) > 0) { wCohX = Params.W_COH * cohX / m; wCohY = Params.W_COH * cohY / m; }
        if ((m = len(aliX, aliY)) > 0) { wAliX = Params.W_ALI * aliX / m; wAliY = Params.W_ALI * aliY / m; }

        double dirX = wSepX + wCohX + wAliX, dirY = wSepY + wCohY + wAliY;
        if (dirX == 0.0 && dirY == 0.0) {
            return new Influence(0, 0, 0, 0, 0, 0, seen, close, 0, false);
        }

        int best = 0;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int delta : CANDIDATES) {
            int a = Math.floorMod(s.h()[i] + delta, Params.TURNS);
            double score = Params.COS[a] * dirX + Params.SIN[a] * dirY
                    + (delta == 0 ? Params.STRAIGHT_BIAS : 0.0);
            if (score > bestScore) { bestScore = score; best = delta; }
        }
        return new Influence(wSepX, wSepY, wCohX, wCohY, wAliX, wAliY, seen, close, best, true);
    }

    /**
     * The same decision under an alternative {@link Aggregation}.
     * <p>
     * The perception test, the turn candidates and the straight bias are the originals; only the
     * step between "these are the neighbours" and "this is the desired direction" differs. That
     * is the whole point — a variant that also changed what a boid can see would not be a
     * comparison of aggregations.
     */
    private Influence variant(BoidArray s, int i) {
        double hx = Params.COS[s.h()[i]], hy = Params.SIN[s.h()[i]];
        Aggregation.Neighbours nb = Aggregation.Neighbours.of(s.n());
        int n = 0, close = 0;
        for (int j = 0; j < s.n(); j++) {
            if (j == i) continue;
            double d = perceived(s, i, j, hx, hy);
            if (d < 0) continue;
            double dx = s.x()[j] - s.x()[i], dy = s.y()[j] - s.y()[i];
            nb.ux()[n] = dx / d;
            nb.uy()[n] = dy / d;
            nb.d()[n] = d;
            nb.ax()[n] = Params.COS[s.h()[j]];
            nb.ay()[n] = Params.SIN[s.h()[j]];
            n++;
            if (d < rSep) close++;
        }
        if (n == 0) return new Influence(0, 0, 0, 0, 0, 0, 0, 0, 0, false);

        double[] out = new double[6];
        aggregation.combine(nb.count(n), flock, out);
        double dirX = out[0] + out[2] + out[4], dirY = out[1] + out[3] + out[5];
        if (dirX == 0.0 && dirY == 0.0) {
            return new Influence(0, 0, 0, 0, 0, 0, n, close, 0, false);
        }
        int best = 0;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int delta : CANDIDATES) {
            int a = Math.floorMod(s.h()[i] + delta, Params.TURNS);
            double score = Params.COS[a] * dirX + Params.SIN[a] * dirY
                    + (delta == 0 ? Params.STRAIGHT_BIAS : 0.0);
            if (score > bestScore) { bestScore = score; best = delta; }
        }
        return new Influence(out[0], out[1], out[2], out[3], out[4], out[5], n, close, best, true);
    }

    @Override
    public void calculate(Movement movement, int i) {
        Influence in = decompose(movement.boids, i);
        // Nothing in view holds the heading, which is not the same as writing a zero: another
        // control may already have proposed a turn and this rule has nothing to say about it.
        if (in.acted()) movement.movement[i] = in.turn();
    }
}
