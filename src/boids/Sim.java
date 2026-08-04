package boids;

import java.util.Random;

/**
 * The entire mutable state of a run: three parallel arrays plus a tick counter.
 * <p>
 * Nothing else in the simulation may hold state that affects future ticks. That
 * invariant is the reason {@link #snapshot()} is a handful of array copies, and it
 * is what will make speculative timeline branching affordable once the psyboid
 * exists. Any derived cache added later (a spatial grid, say) must be rebuilt from
 * these arrays every tick and never restored, or it will silently desynchronise
 * from a restored snapshot.
 * <p>
 * Position is continuous; heading is an index into a fixed table of {@link
 * Params#TURNS} directions, so headings stay exact and never accumulate drift.
 */
public final class Sim {

    /** Unit vectors for each heading index, built once. */
    public static final double[] COS = new double[Params.TURNS];
    public static final double[] SIN = new double[Params.TURNS];

    static {
        // StrictMath, not Math: bit-identical on every platform, so a corpus
        // generated on one machine is reproducible on another.
        for (int a = 0; a < Params.TURNS; a++) {
            double t = 2.0 * StrictMath.PI * a / Params.TURNS;
            COS[a] = StrictMath.cos(t);
            SIN[a] = StrictMath.sin(t);
        }
    }

    public final int n;
    public final double[] x;
    public final double[] y;
    public final int[] h;
    public long tick;

    /** Scratch, fully overwritten each tick before it is read. Not part of the state. */
    private final int[] intent;

    public Sim(int n, long seed) {
        this.n = n;
        this.x = new double[n];
        this.y = new double[n];
        this.h = new int[n];
        this.intent = new int[n];

        // java.util.Random's algorithm is specified exactly in its javadoc, so a
        // given seed produces the same sequence on any JVM.
        Random rng = new Random(seed);
        for (int i = 0; i < n; i++) {
            x[i] = rng.nextDouble() * Params.W;
            y[i] = rng.nextDouble() * Params.H;
            h[i] = rng.nextInt(Params.TURNS);
        }
    }

    /**
     * Advance one tick.
     * <p>
     * DECIDE reads only pre-tick state and mutates nothing; ACT owns all mutation.
     * The two passes must stay separate: reading a partially updated heading array
     * during DECIDE is a correctness bug, not an approximation.
     */
    public void step() {
        for (int i = 0; i < n; i++) {
            intent[i] = Rules.decide(this, i);
        }
        for (int i = 0; i < n; i++) {
            int a = Math.floorMod(h[i] + intent[i], Params.TURNS);
            h[i] = a;
            // Turn is applied before translation, so a boid moves along its new heading.
            x[i] = wrap(x[i] + Params.SPEED * COS[a], Params.W);
            y[i] = wrap(y[i] + Params.SPEED * SIN[a], Params.H);
        }
        tick++;
    }

    public void advance(int ticks) {
        for (int k = 0; k < ticks; k++) step();
    }

    static double wrap(double v, double span) {
        v %= span;
        return v < 0.0 ? v + span : v;
    }

    // ---- Snapshot ----------------------------------------------------------

    public record Snapshot(double[] x, double[] y, int[] h, long tick) {}

    public Snapshot snapshot() {
        return new Snapshot(x.clone(), y.clone(), h.clone(), tick);
    }

    public void restore(Snapshot s) {
        System.arraycopy(s.x(), 0, x, 0, n);
        System.arraycopy(s.y(), 0, y, 0, n);
        System.arraycopy(s.h(), 0, h, 0, n);
        tick = s.tick();
    }
}
