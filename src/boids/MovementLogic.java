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

    @Override
    public void calculate(Movement movement) {
        BoidArray s = movement.boids;
        for (int i = 0; i < s.n(); i++) {
            final double hx = Params.COS[s.h()[i]];
            final double hy = Params.SIN[s.h()[i]];
            final double xi = s.x()[i];
            final double yi = s.y()[i];

            double sepX = 0, sepY = 0;
            double cohX = 0, cohY = 0;
            double aliX = 0, aliY = 0;

            for (int j = 0; j < s.n(); j++) {
                if (j == i) continue;

                double dx = s.x()[j] - xi;
                double dy = s.y()[j] - yi;
                double d2 = dx * dx + dy * dy;
                if (d2 == 0.0 || d2 > rFlock * rFlock) continue;

                double d = Math.sqrt(d2);

                // dx*hx + dy*hy is d*cos(angle off the heading), so this drops anything
                // in the rear blind arc.
                if (dx * hx + dy * hy < Params.COS_FOV * d) continue;

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
                continue;   // no neighbours; hold heading
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
}
