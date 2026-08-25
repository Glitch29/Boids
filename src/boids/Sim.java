package boids;

/**
 * One tick of a run, and nothing else.
 * <p>
 * This class was once the whole orchestration layer — a receding-horizon override search, a
 * replay path that rebuilt a timeline from a label, an observer registry that fed a set of
 * loggers, and a renderer. All of it served the question of whether a psyboid could be picked
 * out of a flock by watching what it scored, and all of it was answered and set aside when the
 * work moved to edge decomposition and the clock. What survived is the state itself, because
 * every later thing still needs somewhere to put a flock.
 * <p>
 * The name is kept because {@code Sim.State} reads correctly at the several dozen places that
 * use it, and renaming it buys nothing the compiler would catch.
 */
public final class Sim {
    private Sim() {}

    /**
     * A flock at one instant, with the score it has accumulated and where it came from.
     * <p>
     * Treat instances as immutable. The arrays are exposed directly rather than copied,
     * because the decision layer reads them in its inner loop and a copy per access would
     * dominate the cost. Nothing writes to a {@code State} once it has been constructed —
     * {@link Boids2DEngine#tick} allocates fresh arrays and returns a new instance.
     * <p>
     * Because an old state can never be clobbered, the decide and act phases do not need
     * separating: reading the previous tick is guaranteed safe by construction, which is what
     * lets the same state be re-advanced any number of times.
     */
    public static final class State {

        public final int n;
        public final int[] x;
        public final int[] y;
        public final int[] h;
        public final long tick;
        public final long score;

        /**
         * Score accumulated by each boid individually; sums to {@link #score}.
         * <p>
         * Kept alongside the total because a psyboid that simply parks itself in the
         * scoring zone is trivially visible, whereas one whose gain comes from moving
         * the rest of the flock is not. Telling those apart needs the breakdown.
         */
        public final long[] boidScore;

        /**
         * Identifies which scenario this timeline came from. Carried through every tick
         * untouched, so a result at the end of a batch can be traced back to what produced
         * it rather than being an anonymous number in a list.
         */
        public final String label;

        /** Steering in force on this timeline; empty for an ordinary flock. */
        public final PsyboidOverride[] psyboidOverrides;

        State(int n, int[] x, int[] y, int[] h, long tick, long score, long[] boidScore,
              String label, PsyboidOverride... psyboidOverrides) {
            this.n = n;
            this.x = x;
            this.y = y;
            this.h = h;
            this.tick = tick;
            this.score = score;
            this.boidScore = boidScore;
            this.label = label;
            this.psyboidOverrides = psyboidOverrides;
        }
    }
}
