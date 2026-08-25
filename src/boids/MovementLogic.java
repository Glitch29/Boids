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
     * Perception radii scale with the turning radius, so this is per-scenario rather
     * than a shared singleton. A boid must begin reacting to a neighbour far enough
     * out that it can actually turn away in time.
     */
    public MovementLogic(double turningRadius) {
        this.rSep = Params.separation(turningRadius);
        this.rFlock = Params.flock(turningRadius);
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

    @Override
    public void calculate(Movement movement, int i) {
        BoidArray s = movement.boids;
        final double hx = Params.COS[s.h()[i]];
        final double hy = Params.SIN[s.h()[i]];
        final double xi = s.x()[i];
        final double yi = s.y()[i];

        double sepX = 0, sepY = 0;
        double cohX = 0, cohY = 0;
        double aliX = 0, aliY = 0;

        for (int j = 0; j < s.n(); j++) {
            if (j == i) continue;

            double d = perceived(s, i, j, hx, hy);
            if (d < 0) continue;

            double dx = s.x()[j] - xi;
            double dy = s.y()[j] - yi;

            cohX += dx;
            cohY += dy;
            aliX += Params.COS[s.h()[j]];
            aliY += Params.SIN[s.h()[j]];

            if (d < rSep) {
                double falloff = (rSep - d) / rSep;  // 1 at contact, 0 at the rim
                sepX -= dx / d * falloff;
                sepY -= dy / d * falloff;
            }
        }

        // Each term is normalised to unit length before weighting, so the weights are
        // directly comparable and a rule with one contributing neighbour is not
        // silently amplified against one with twenty.
        double dirX = 0, dirY = 0;
        double m;
        if ((m = len(sepX, sepY)) > 0) {
            dirX += Params.W_SEP * sepX / m;
            dirY += Params.W_SEP * sepY / m;
        }
        if ((m = len(cohX, cohY)) > 0) {
            dirX += Params.W_COH * cohX / m;
            dirY += Params.W_COH * cohY / m;
        }
        if ((m = len(aliX, aliY)) > 0) {
            dirX += Params.W_ALI * aliX / m;
            dirY += Params.W_ALI * aliY / m;
        }

        if (dirX == 0.0 && dirY == 0.0) {
            return;     // no neighbours; hold heading
        }

        // Evaluation order plus a strict > means STRAIGHT wins ties against both
        // turns and LEFT wins ties against RIGHT. That ordering is part of the
        // definition, not an implementation detail: changing it changes the corpus.
        int best = 0;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int delta : CANDIDATES) {
            int a = Math.floorMod(s.h()[i] + delta, Params.TURNS);
            double score = Params.COS[a] * dirX + Params.SIN[a] * dirY
                    + (delta == 0 ? Params.STRAIGHT_BIAS : 0.0);
            if (score > bestScore) {
                bestScore = score;
                best = delta;
            }
        }
        movement.movement[i] = best;
    }
}
