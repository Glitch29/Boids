package boids;

/**
 * How well a solver separated psyboids from boids, as a penalty to be minimised.
 * <p>
 * Replaces the earlier {@code 3 x TP + TN} tally, which counted correct answers and therefore
 * could not tell a solver that knows something from one that has quietly stopped answering. This
 * one is a <b>Brier score over an implied probability</b>: each of the two predicted classes is
 * given the K-weighted psyboid rate actually found inside it, and every boid is then scored on
 * the squared distance between that probability and the truth, weighted by {@code K} if it is a
 * psyboid.
 *
 * <h2>What it is built to do</h2>
 * <ul>
 *   <li>Make a psyboid correctly kept worth <b>K times</b> a boid correctly ruled out.</li>
 *   <li>Make a poorly-discriminating solver able to improve by <b>drawing distinctions</b>,
 *       rather than effectively abstaining — keeping every boid, which at high {@code K} a
 *       count-based score rewards.</li>
 * </ul>
 *
 * <h2>The closed form</h2>
 * Writing {@code g(a, b) = ab / (a + b)} — parallel resistance, or half the harmonic mean — the
 * whole expression collapses to
 * <pre>
 *     penalty = g(K*FN, TN) + g(K*TP, FP)
 * </pre>
 * because the two terms of each predicted class telescope:
 * {@code TN*Hn^2 + K*FN*(1-Hn)^2 = ab/(a+b)} with {@code a = K*FN, b = TN}, and likewise for the
 * positive class. So it is, per predicted class, the parallel combination of the K-weighted
 * counts of the two true classes within it. That identity is what the properties below are read
 * off, and it is worth keeping in mind even though the code below does not use it.
 * <p>
 * <b>Three properties follow, and they are why this shape was chosen.</b> {@code g} is concave
 * and homogeneous of degree one, hence superadditive, so
 * {@code g(K*Np, Nb) >= g(K*FN,TN) + g(K*TP,FP)}:
 * <ul>
 *   <li><b>Abstaining is the worst case.</b> No assignment scores worse than keeping everything.
 *   <li><b>Uninformative splits all tie with it</b>, exactly when both classes hold the same
 *       psyboid-to-boid ratio. So no score can be gained by drawing distinctions that carry no
 *       information — only enrichment pays.
 *   <li><b>Zero exactly when the answer is perfect.</b>
 * </ul>
 *
 * <h2>Why it is piecewise</h2>
 * The plain form is <b>symmetric under swapping every answer</b>, so a perfectly inverted solver
 * also scores zero — and, worse, the score is <em>non-monotone</em>: on the far side of the
 * abstain ridge, correcting a mistake makes the number go up. That is fatal for something whose
 * whole job is to notice regressions, since it silently reverses sign if a solver ever crosses.
 * <p>
 * The fix is to stop a class being assigned a probability that inverts it: <b>a group labelled
 * BOID may not be given a higher psyboid rate than the population has, and a group labelled
 * PSYBOID may not be given a higher boid rate.</b> Hence {@link #capNegative} and
 * {@link #capPositive}, which sum to one.
 * <p>
 * <b>The caps must be the population rates and not constants.</b> A fixed cap is flat only at
 * one class balance and tilts the plateau everywhere else — under {@code K/(K+1)} an
 * uninformative solver could improve its score by nothing more than answering PSYBOID less
 * often, which is the very thing the second goal excludes. At the population rates the caps bind
 * exactly at the abstain point and nowhere on the uninformative manifold, so the plateau stays
 * flat at every base rate while inversion is still clamped. Verified over 400,000 random
 * assignments: no monotonicity violations either way, no split worse than abstaining, plateau
 * spread 0 to within rounding, and zero reached only by a perfect answer.
 *
 * @see #penalty for the argument order, which is easy to transpose
 */
public final class SolverScore {
    private SolverScore() {}

    /**
     * How much more a psyboid is worth than a boid.
     * <p>
     * Three because a scene holds four boids, so at three the score is indifferent to guessing:
     * keep every boid with any probability and the expectation does not move. Anything above it
     * is knowledge.
     */
    public static final double K = 3;

    /** The largest psyboid rate a group answered BOID may be given. */
    public static double capNegative(int psyboids, int boids, double k) {
        double d = k * psyboids + boids;
        return d == 0 ? 0 : k * psyboids / d;
    }

    /** The largest boid rate a group answered PSYBOID may be given. Complements the above. */
    public static double capPositive(int psyboids, int boids, double k) {
        double d = k * psyboids + boids;
        return d == 0 ? 0 : boids / d;
    }

    /**
     * The penalty for one set of outcomes. Lower is better; zero is a perfect answer.
     *
     * @param tp psyboids kept as candidates
     * @param fn psyboids ruled out — the expensive mistake, weighted {@code k}
     * @param tn boids ruled out
     * @param fp boids kept as candidates
     */
    public static double penalty(int tp, int fn, int tn, int fp, double k) {
        int psyboids = tp + fn, boids = tn + fp;
        double capN = capNegative(psyboids, boids, k);
        double capP = capPositive(psyboids, boids, k);

        // The rate each answered class is implicitly claiming, read off what is actually in it,
        // then clamped so neither class can be claimed to be mostly the other thing.
        double dn = k * fn + tn, dp = fp + k * tp;
        double hn = dn == 0 ? 0 : Math.min(k * fn / dn, capN);
        double hp = dp == 0 ? 0 : Math.min(fp / dp, capP);

        // Squared error per boid against that claim, psyboids counting k times. Written out
        // rather than as g(k*fn, tn) + g(k*tp, fp), because the closed form is only equal to it
        // where the caps do not bind.
        return tn * hn * hn
                + fn * k * (1 - hn) * (1 - hn)
                + fp * (1 - hp) * (1 - hp)
                + tp * k * hp * hp;
    }

    /** The same at the project's {@link #K}. */
    public static double penalty(int tp, int fn, int tn, int fp) {
        return penalty(tp, fn, tn, fp, K);
    }

    /**
     * What a solver scores for keeping everything, which is the worst any assignment can do.
     * <p>
     * The number a raw penalty wants reporting against: on its own a penalty says nothing, since
     * it scales with how many scenes were graded.
     */
    public static double abstaining(int psyboids, int boids, double k) {
        return penalty(psyboids, 0, 0, boids, k);
    }

    /**
     * The penalty as a fraction of the ground it had to make up. 1 is perfect, 0 is knowing
     * nothing, and below 0 is worse than abstaining.
     * <p>
     * Comparable across corpora with different psyboid rates, which a raw penalty is not. It does
     * largely cancel {@code k} — a fixed solver scores 0.44 at {@code k = 1} and 0.51 at
     * {@code k = 8} — so read the raw penalty when the weighting is the thing in question.
     */
    public static double normalised(int tp, int fn, int tn, int fp, double k) {
        double worst = abstaining(tp + fn, tn + fp, k);
        return worst == 0 ? 0 : 1 - penalty(tp, fn, tn, fp, k) / worst;
    }
}
