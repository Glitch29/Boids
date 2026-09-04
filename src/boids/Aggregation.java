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
 * {@link #CURRENT} is what {@link MovementLogic} does, and it is the default everywhere. The
 * others exist to be measured against it.
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

    /** {@code w * v / n}: keeps the length as the measure of agreement it is. */
    private static void mean(double[] out, int at, double vx, double vy, double w, int n) {
        if (n > 0) { out[at] = w * vx / n; out[at + 1] = w * vy / n; }
    }

    // ---- the variants --------------------------------------------------------

    /**
     * What the simulation does. Each rule's raw sum is renormalised to its full weight.
     * <p>
     * Cohesion sums <b>raw offsets</b> rather than unit vectors, so before normalisation a
     * neighbour 140 px away counts fourteen times one at 10 px; after normalisation only the
     * direction survives, which is the direction of the centroid. Separation's distance falloff
     * is likewise erased whenever there is exactly one close neighbour, since normalising a
     * single vector discards its length — <b>the falloff only ever shapes a direction, never a
     * magnitude</b>, which is not what a reader of the formula would expect.
     */
    Aggregation CURRENT = new Aggregation() {
        public String id() { return "CURRENT"; }

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
     * Each rule averages over its own contributors, and the average keeps its length.
     * <p>
     * The magnitude then means what it looks like it means: alignment at full weight is unanimity,
     * alignment at half weight is neighbours pulling apart. Convex, so the pseudo-triangle rule
     * holds exactly at {@code k = 1}. The cost is that a single neighbour no longer produces a
     * full-weight separation term, because the falloff survives.
     */
    Aggregation RULE_MEAN = new Aggregation() {
        public String id() { return "RULE_MEAN"; }

        public String describes() { return "per-rule mean over that rule's contributors"; }

        public boolean separationFalloffAtOne() { return true; }

        public void combine(Neighbours nb, Flocking f, double[] out) {
            double sx = 0, sy = 0, cx = 0, cy = 0, ax = 0, ay = 0;
            int close = 0;
            for (int j = 0; j < nb.n(); j++) {
                cx += nb.ux()[j];
                cy += nb.uy()[j];
                ax += nb.ax()[j];
                ay += nb.ay()[j];
                if (nb.d()[j] < f.rSep()) {
                    close++;
                    double falloff = (f.rSep() - nb.d()[j]) / f.rSep();
                    sx -= nb.ux()[j] * falloff;
                    sy -= nb.uy()[j] * falloff;
                }
            }
            mean(out, 0, sx, sy, f.wSep(), close);
            mean(out, 2, cx, cy, f.wCoh(), nb.n());
            mean(out, 4, ax, ay, f.wAli(), nb.n());
        }
    };

    /**
     * Each rule sums unit contributions and the sum is capped rather than rescaled.
     * <p>
     * <b>Identical to {@link #CURRENT} whenever the neighbours agree</b> — two neighbours pulling
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

    /**
     * The same, with separation as a step rather than a falloff.
     * <p>
     * Restores exact agreement with {@link #CURRENT} for a single neighbour: normalising one
     * vector erases the falloff anyway, so dropping it changes nothing at {@code n = 1} and makes
     * this the only bounded variant that is a strict extension of today's physics rather than a
     * replacement for it.
     */
    Aggregation RULE_SUM_CLAMP_STEP = new Aggregation() {
        public String id() { return "RULE_CLAMP_STEP"; }

        public String describes() { return "the same, separation a step so n=1 is unchanged"; }

        public void combine(Neighbours nb, Flocking f, double[] out) {
            double sx = 0, sy = 0, cx = 0, cy = 0, ax = 0, ay = 0;
            for (int j = 0; j < nb.n(); j++) {
                cx += nb.ux()[j];
                cy += nb.uy()[j];
                ax += nb.ax()[j];
                ay += nb.ay()[j];
                if (nb.d()[j] < f.rSep()) { sx -= nb.ux()[j]; sy -= nb.uy()[j]; }
            }
            clamp(out, 0, sx, sy, f.wSep());
            clamp(out, 2, cx, cy, f.wCoh());
            clamp(out, 4, ax, ay, f.wAli());
        }
    };

    /**
     * Every neighbour casts one complete vote and the votes are averaged.
     * <p>
     * A vote is the single-neighbour closed form {@link EdgeInfluence#steer} already uses:
     * {@code (wCoh - [close] wSep) u + wAli a}. Averaging bounded votes puts the result inside
     * their convex hull, so the pseudo-triangle rule holds exactly at {@code k = 1} in two
     * dimensions and not merely on the across-component — the strongest guarantee available. It
     * agrees with {@link #CURRENT} exactly at {@code n = 1}.
     * <p>
     * It is also the one variant that reorders rather than rescales: rules are combined per
     * neighbour and then across neighbours, where today it is the other way round.
     */
    Aggregation VOTE_MEAN = new Aggregation() {
        public String id() { return "VOTE_MEAN"; }

        public String describes() { return "mean of per-neighbour votes (rules combined first)"; }

        public void combine(Neighbours nb, Flocking f, double[] out) {
            for (int j = 0; j < nb.n(); j++) {
                if (nb.d()[j] < f.rSep()) {
                    out[0] -= f.wSep() * nb.ux()[j] / nb.n();
                    out[1] -= f.wSep() * nb.uy()[j] / nb.n();
                }
                out[2] += f.wCoh() * nb.ux()[j] / nb.n();
                out[3] += f.wCoh() * nb.uy()[j] / nb.n();
                out[4] += f.wAli() * nb.ax()[j] / nb.n();
                out[5] += f.wAli() * nb.ay()[j] / nb.n();
            }
        }
    };

    /**
     * The same votes summed, with the total capped at what one vote could have been.
     * <p>
     * Between {@link #VOTE_MEAN} and {@link #CURRENT}: agreeing neighbours reinforce up to the
     * cap instead of being averaged down, and cancelling ones stay cancelled instead of being
     * blown back up.
     */
    Aggregation VOTE_SUM_CLAMP = new Aggregation() {
        public String id() { return "VOTE_CLAMP"; }

        public String describes() { return "sum of votes, total capped at one vote's reach"; }

        public void combine(Neighbours nb, Flocking f, double[] out) {
            double sx = 0, sy = 0, cx = 0, cy = 0, ax = 0, ay = 0;
            for (int j = 0; j < nb.n(); j++) {
                if (nb.d()[j] < f.rSep()) { sx -= nb.ux()[j]; sy -= nb.uy()[j]; }
                cx += nb.ux()[j];
                cy += nb.uy()[j];
                ax += nb.ax()[j];
                ay += nb.ay()[j];
            }
            double vx = f.wSep() * sx + f.wCoh() * cx + f.wAli() * ax;
            double vy = f.wSep() * sy + f.wCoh() * cy + f.wAli() * ay;
            // One vote's reach is the largest a single neighbour could ask for, which is the
            // separation regime: the three terms are collinear at worst.
            double cap = Math.max(f.wCoh(), f.wSep() - f.wCoh()) + f.wAli();
            double m = len(vx, vy);
            double k = m > cap ? cap / m : 1;
            out[0] = f.wSep() * sx * k;
            out[1] = f.wSep() * sy * k;
            out[2] = f.wCoh() * cx * k;
            out[3] = f.wCoh() * cy * k;
            out[4] = f.wAli() * ax * k;
            out[5] = f.wAli() * ay * k;
        }
    };

    /**
     * The control: reorder without rescaling, and the defect survives.
     * <p>
     * Votes are summed and the total is renormalised to one vote's reach. Moving the
     * normalisation from the rules to the total makes no difference to the amplification,
     * because the amplification is caused by normalising at all — which is the point worth
     * establishing before anything is concluded about ordering.
     */
    Aggregation VOTE_NORM = new Aggregation() {
        public String id() { return "VOTE_NORM"; }

        public String describes() { return "sum of votes, renormalised (control: still amplifies)"; }

        public void combine(Neighbours nb, Flocking f, double[] out) {
            double sx = 0, sy = 0, cx = 0, cy = 0, ax = 0, ay = 0;
            for (int j = 0; j < nb.n(); j++) {
                if (nb.d()[j] < f.rSep()) { sx -= nb.ux()[j]; sy -= nb.uy()[j]; }
                cx += nb.ux()[j];
                cy += nb.uy()[j];
                ax += nb.ax()[j];
                ay += nb.ay()[j];
            }
            double vx = f.wSep() * sx + f.wCoh() * cx + f.wAli() * ax;
            double vy = f.wSep() * sy + f.wCoh() * cy + f.wAli() * ay;
            double cap = Math.max(f.wCoh(), f.wSep() - f.wCoh()) + f.wAli();
            double m = len(vx, vy);
            double k = m > 0 ? cap / m : 0;
            out[0] = f.wSep() * sx * k;
            out[1] = f.wSep() * sy * k;
            out[2] = f.wCoh() * cx * k;
            out[3] = f.wCoh() * cy * k;
            out[4] = f.wAli() * ax * k;
            out[5] = f.wAli() * ay * k;
        }
    };

    /** Everything surveyed, {@link #CURRENT} first. */
    Aggregation[] ALL = {CURRENT, RULE_MEAN, RULE_SUM_CLAMP, RULE_SUM_CLAMP_STEP, VOTE_MEAN,
            VOTE_SUM_CLAMP, VOTE_NORM};
}
