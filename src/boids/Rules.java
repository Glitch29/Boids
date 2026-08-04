package boids;

/**
 * The default boid. A pure function from world state to a turn; it mutates nothing.
 * <p>
 * Reynolds' three rules are reduced to a single desired direction vector, and the
 * boid picks whichever of its three reachable headings best aligns with it.
 */
public final class Rules {
    private Rules() {}

    /** Turn candidates in fixed evaluation order: hold, left, right. */
    private static final int[] CANDIDATES = {0, -1, +1};

    /**
     * @return -1 to turn left, 0 to hold heading, +1 to turn right.
     */
    public static int decide(Sim s, int i) {
        final double hx = Sim.COS[s.h[i]];
        final double hy = Sim.SIN[s.h[i]];
        final double xi = s.x[i];
        final double yi = s.y[i];

        double sepX = 0, sepY = 0;
        double cohX = 0, cohY = 0;
        double aliX = 0, aliY = 0;

        for (int j = 0; j < s.n; j++) {
            if (j == i) continue;

            // Perception is deliberately NOT toroidal even though position is: a boid
            // at x=5 does not see one at x=475. Flocks therefore tear at the wrap seam
            // and re-form, which stands in for the obstacles a bounded play area will
            // provide later.
            double dx = s.x[j] - xi;
            double dy = s.y[j] - yi;
            double d2 = dx * dx + dy * dy;
            if (d2 == 0.0 || d2 > Params.R_FLOCK * Params.R_FLOCK) continue;

            double d = Math.sqrt(d2);

            // dx*hx + dy*hy is d*cos(angle off the heading), so this drops anything
            // in the rear blind arc.
            if (dx * hx + dy * hy < Params.COS_FOV * d) continue;

            cohX += dx;
            cohY += dy;
            aliX += Sim.COS[s.h[j]];
            aliY += Sim.SIN[s.h[j]];

            if (d < Params.R_SEP) {
                double falloff = (Params.R_SEP - d) / Params.R_SEP;  // 1 at contact, 0 at the rim
                sepX -= dx / d * falloff;
                sepY -= dy / d * falloff;
            }
        }

        // Each term is normalised to unit length before weighting, so the weights are
        // directly comparable and a rule with one contributing neighbour is not
        // silently amplified against one with twenty.
        double dirX = 0, dirY = 0;
        double m;
        if ((m = len(sepX, sepY)) > 0) { dirX += Params.W_SEP * sepX / m; dirY += Params.W_SEP * sepY / m; }
        if ((m = len(cohX, cohY)) > 0) { dirX += Params.W_COH * cohX / m; dirY += Params.W_COH * cohY / m; }
        if ((m = len(aliX, aliY)) > 0) { dirX += Params.W_ALI * aliX / m; dirY += Params.W_ALI * aliY / m; }

        if (dirX == 0.0 && dirY == 0.0) return 0;   // no neighbours; hold heading

        // Evaluation order plus a strict > means STRAIGHT wins ties against both
        // turns and LEFT wins ties against RIGHT. That ordering is part of the
        // definition, not an implementation detail: changing it changes the corpus.
        int best = 0;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int delta : CANDIDATES) {
            int a = Math.floorMod(s.h[i] + delta, Params.TURNS);
            double score = Sim.COS[a] * dirX + Sim.SIN[a] * dirY
                    + (delta == 0 ? Params.STRAIGHT_BIAS : 0.0);
            if (score > bestScore) {
                bestScore = score;
                best = delta;
            }
        }
        return best;
    }

    private static double len(double a, double b) {
        return Math.sqrt(a * a + b * b);
    }
}
