package boids;

/**
 * How one boid's neighbours are condensed into a single desired direction.
 * <p>
 * The three rules are not the interesting part — every boids implementation has separation,
 * cohesion and alignment, and they agree about what each one wants from a single neighbour. What
 * they disagree about is <b>what happens when several neighbours want different things</b>, and
 * that is entirely a question of where the aggregation normalises.
 *
 * <h2>The defect this exists to survey</h2>
 * {@link Params} normalises each rule's <em>sum</em> to a fixed magnitude, so the length of the
 * sum — which is precisely the measure of how much the neighbours agree — is thrown away and
 * replaced by a constant. Two alignment vectors that nearly cancel leave a short residual, and
 * renormalising blows it back up to the full weight. In the limit, signals of {@code X} and
 * {@code -X + eY} combine into a full-strength signal along {@code Y}: a direction neither
 * neighbour asked for, at a magnitude neither could have produced.
 * <p>
 * That is not a rounding problem. It is the reason a combination can turn a boid that no member
 * of it would turn alone, which is the whole of the unaccounted residue on the three-boid phase
 * map, and it makes the decision arbitrarily sensitive to a pixel.
 *
 * <h2>What a well-behaved aggregation would satisfy</h2>
 * <blockquote>
 * {@code min(signal(A), signal(B))  <=  k * signal(A and B)  <=  max(signal(A), signal(B))}
 * </blockquote>
 * for a fixed {@code k}, where {@code signal} is the across-heading component of the desired
 * direction — the scalar that actually decides left from right. Call it the <b>pseudo-triangle
 * rule</b>. An aggregation that averages satisfies it exactly with {@code k = 1} because an
 * average is a convex combination and the across-component is linear. An aggregation that
 * normalises satisfies no bound at all.
 *
 * <h2>Nothing here changes the simulation</h2>
 * {@link #SIMULATION} is what {@link MovementLogic} does, and {@link #RULE_NORMALISE} is kept as
 * the record of what physics 2 did, so the two can be run against each other end to end
 * ({@code SimTest.proposedPhysics}). <b>Five more were surveyed and are gone</b> —
 * {@code RULE_MEAN}, {@code RULE_SUM_CLAMP_STEP}, {@code VOTE_MEAN}, {@code VOTE_SUM_CLAMP} and
 * {@code VOTE_NORM}, removed 2026-09-13 once {@code RULE_SUM_CLAMP} had shipped as physics 3.
 * The survey and its numbers are in {@code ROADMAP.md} §0a; the variants are in git at
 * {@code 0116abc}.
 */
public interface Aggregation {

    /** Everything a decision depends on, with the geometry already resolved. */
    record Neighbours(int n, double[] ux, double[] uy, double[] d, double[] ax, double[] ay) {

        /** Unit vector from the deciding boid toward neighbour {@code j}. */
        public double ux(int j) { return ux[j]; }

        public static Neighbours of(int capacity) {
            return new Neighbours(0, new double[capacity], new double[capacity],
                    new double[capacity], new double[capacity], new double[capacity]);
        }

        public Neighbours count(int k) { return new Neighbours(k, ux, uy, d, ax, ay); }
    }

    String id();

    /** One line on what it does differently, for a report to print. */
    String describes();

    /**
     * Whether a single close neighbour's separation term keeps its distance falloff.
     * <p>
     * The one thing the single-neighbour closed form in {@link EdgeInfluence#steer} needs to know
     * about an aggregation, because that form <em>is</em> this aggregation restricted to one
     * neighbour — everything else about the {@code n = 1} case is the same under all of these.
     * Copy it into {@link Flocking#sepFalloff(boolean)} rather than setting that flag by hand;
     * {@code AggregationSurvey.checkClosedForm} asserts the two agree.
     */
    default boolean separationFalloffAtOne() { return false; }

    /**
     * The three weighted rule vectors, in the order
     * {@code sepX, sepY, cohX, cohY, aliX, aliY}. Their sum is the desired direction.
     */
    void combine(Neighbours nb, Flocking f, double[] out);

    // ---- helpers -------------------------------------------------------------

    private static double len(double a, double b) { return Math.sqrt(a * a + b * b); }

    /** {@code w * v / |v|}: throws the length away and replaces it with the weight. */
    private static void normalise(double[] out, int at, double vx, double vy, double w) {
        double m = len(vx, vy);
        if (m > 0) { out[at] = w * vx / m; out[at + 1] = w * vy / m; }
    }

    /** {@code w * v / max(1, |v|)}: keeps the length when short, caps it when long. */
    private static void clamp(double[] out, int at, double vx, double vy, double w) {
        double m = Math.max(1, len(vx, vy));
        out[at] = w * vx / m;
        out[at + 1] = w * vy / m;
    }


    // ---- the variants --------------------------------------------------------

    /**
     * <b>Physics 2.</b> Each rule's raw sum is renormalised to its full weight. Kept as the
     * record of what the simulation did before 2026-09-04, and as the baseline every survey
     * number is quoted against.
     * <p>
     * Cohesion sums <b>raw offsets</b> rather than unit vectors, so before normalisation a
     * neighbour 140 px away counts fourteen times one at 10 px; after normalisation only the
     * direction survives, which is the direction of the centroid. Separation's distance falloff
     * is likewise erased whenever there is exactly one close neighbour, since normalising a
     * single vector discards its length — <b>the falloff only ever shapes a direction, never a
     * magnitude</b>, which is not what a reader of the formula would expect.
     */
    Aggregation RULE_NORMALISE = new Aggregation() {
        public String id() { return "RULE_NORMALISE"; }

        public String describes() { return "per-rule sum, renormalised to full weight"; }

        public void combine(Neighbours nb, Flocking f, double[] out) {
            double sx = 0, sy = 0, cx = 0, cy = 0, ax = 0, ay = 0;
            for (int j = 0; j < nb.n(); j++) {
                cx += nb.d()[j] * nb.ux()[j];
                cy += nb.d()[j] * nb.uy()[j];
                ax += nb.ax()[j];
                ay += nb.ay()[j];
                if (nb.d()[j] < f.rSep()) {
                    double falloff = (f.rSep() - nb.d()[j]) / f.rSep();
                    sx -= nb.ux()[j] * falloff;
                    sy -= nb.uy()[j] * falloff;
                }
            }
            normalise(out, 0, sx, sy, f.wSep());
            normalise(out, 2, cx, cy, f.wCoh());
            normalise(out, 4, ax, ay, f.wAli());
        }
    };

    /**
     * Each rule sums unit contributions and the sum is capped rather than rescaled.
     * <p>
     * <b>Identical to {@link #RULE_NORMALISE} whenever the neighbours agree</b> — two neighbours pulling
     * the same way sum to length 2, which caps to 1, which is what normalising would have given.
     * It differs only where the sum is short, which is exactly the cancelling case the
     * normalisation amplifies. That makes it the smallest change that removes the defect.
     */
    Aggregation RULE_SUM_CLAMP = new Aggregation() {
        public String id() { return "RULE_SUM_CLAMP"; }

        public String describes() { return "per-rule sum of unit terms, magnitude capped at 1"; }

        public boolean separationFalloffAtOne() { return true; }

        public void combine(Neighbours nb, Flocking f, double[] out) {
            double sx = 0, sy = 0, cx = 0, cy = 0, ax = 0, ay = 0;
            for (int j = 0; j < nb.n(); j++) {
                cx += nb.ux()[j];
                cy += nb.uy()[j];
                ax += nb.ax()[j];
                ay += nb.ay()[j];
                if (nb.d()[j] < f.rSep()) {
                    double falloff = (f.rSep() - nb.d()[j]) / f.rSep();
                    sx -= nb.ux()[j] * falloff;
                    sy -= nb.uy()[j] * falloff;
                }
            }
            clamp(out, 0, sx, sy, f.wSep());
            clamp(out, 2, cx, cy, f.wCoh());
            clamp(out, 4, ax, ay, f.wAli());
        }
    };

    /** Physics 2 first, then what flies now. */
    Aggregation[] ALL = {RULE_NORMALISE, RULE_SUM_CLAMP};

    /**
     * What the simulation flies, and the single place that says so.
     * <p>
     * {@link MovementLogic} defaults to it and {@link Flocking#of} takes its separation profile
     * from it, so the decision rules and the single-neighbour closed form cannot be set to
     * disagree by editing one of them and forgetting the other.
     * <p>
     * <b>Physics 3</b>, shipped 2026-09-04. Physics 2 was {@link #RULE_NORMALISE}.
     */
    Aggregation SIMULATION = RULE_SUM_CLAMP;
}
