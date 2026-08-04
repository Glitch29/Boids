package boids;

/**
 * Every tunable constant.
 * <p>
 * Lengths are given as multiples of {@link #R_TURN}, so the whole simulation
 * rescales by changing that one number. The ratios matter more than the absolute
 * values: because a boid moves at fixed speed and can only turn by one step per
 * tick, it has a hard minimum turning radius, and separation is only physically
 * achievable if {@link #R_SEP} comfortably exceeds it. A boid that detects a
 * neighbour it cannot turn away from in time produces jitter that no amount of
 * weight tuning will fix.
 * <p>
 * The arena is no longer defined here. Its extent comes from the play area image.
 */
public final class Params {
    private Params() {}

    // ---- Geometry ----------------------------------------------------------

    /** Distinct headings. A boid turns by exactly one of these per tick. */
    public static final int TURNS = 64;

    /** Turning radius, in arena units. The scale everything else derives from. */
    public static final double R_TURN = 40.0;

    /** Distance covered per tick: the chord of the TURNS-gon of radius R_TURN. */
    public static final double SPEED = 2.0 * R_TURN * StrictMath.sin(StrictMath.PI / TURNS);

    public static double headingDeg(int heading) {
        return heading * 360.0 / TURNS;
    }

    /** Unit vectors for each heading index, built once. */
    public static final double[] COS = new double[TURNS];
    public static final double[] SIN = new double[TURNS];

    static {
        // StrictMath, not Math: bit-identical on every platform, so a corpus
        // generated on one machine is reproducible on another.
        for (int a = 0; a < TURNS; a++) {
            double t = 2.0 * StrictMath.PI * a / TURNS;
            COS[a] = StrictMath.cos(t);
            SIN[a] = StrictMath.sin(t);
        }
    }

    // ---- Perception --------------------------------------------------------

    /** Separation acts within this radius, with linear falloff. */
    public static final double R_SEP = 1.25 * R_TURN;

    /** Cohesion and alignment act within this radius. */
    public static final double R_FLOCK = 3.75 * R_TURN;

    /**
     * cos(120 degrees). A 240-degree forward field of view leaving a 120-degree
     * blind arc behind each boid. The blind arc is what gives a flock a leading
     * and a trailing edge instead of collapsing into a symmetric blob, and is
     * most of the reason the result reads as flocking rather than clustering.
     */
    public static final double COS_FOV = -0.5;

    // ---- Behaviour ---------------------------------------------------------

    public static final double W_SEP = 120.0;
    public static final double W_COH = 30.0;
    public static final double W_ALI = 70.0;

    /**
     * Hysteresis added to the STRAIGHT candidate. Without it a boid whose desired
     * direction sits almost exactly between two headings alternates left-right
     * every tick, which renders as a visible tremor. Scaled by the total weight so
     * the dead zone is invariant to the overall weight magnitude.
     */
    public static final double STRAIGHT_BIAS = (W_SEP + W_COH + W_ALI) / 64.0;

    public static final int N_BOIDS = 60;
}
