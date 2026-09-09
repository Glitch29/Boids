package boids;

/**
 * Anything that steers the psyboid: one of the three {@link MovementControl}s a tick runs through,
 * between the flocking rules and the collision veto.
 * <p>
 * Being in that chain rather than beside it is what makes a steered boid ordinary. An override
 * chooses from the same three turns as everything else and runs before the veto, so it is a
 * <b>request</b> — {@link NavMap#constrainTurn} still has the last word, and a boid cannot turn
 * faster by being overridden.
 *
 * <h2>Why this is an interface</h2>
 * It was a class, and the class was one thing: a fixed turn held over a fixed span of ticks. That
 * is enough to express any plan, but only as a list of absolute-tick instructions worked out in
 * advance — which means the plan cannot react, and a search producing it has to know at planning
 * time exactly when the boid will arrive somewhere. On a map whose edges run 750 ticks, it will
 * not. <b>That class is gone</b>, deleted 2026-09-08 with the search that emitted it.
 * {@link EdgePilot} is the kind that remains: it carries a route rather than a schedule and steers
 * only on the ticks where coasting would leave it. Whatever a decision point installs will be of
 * that shape too — a rule evaluated against where the boid actually is, never a tick worked out
 * in advance.
 *
 * <h2>The window, generically</h2>
 * Everything that reads an override reads one of three things — when it can act, whether it asks
 * for anything, and whether it acts at a given tick. Those are on the interface so that nothing
 * downstream has to know which kind it holds. <b>{@link #to()} is exclusive.</b>
 *
 * <h2>Labels</h2>
 * A plan's label is its artifact, so every implementation round-trips through one. The first
 * character says which kind, because a corpus row has to be readable back without being told what
 * wrote it. <b>A pilot's label names a route, and a route means nothing without the map it runs
 * on</b>, so reading one back takes the two-argument {@link #parse(String, NavMap, SolverFacts)}.
 */
public interface PsyboidOverride extends MovementControl {

    /** Which boid this steers. */
    int psyboid();

    /** Compact identifier, holding no comma or pipe: both separate fields elsewhere. */
    String label();

    /** First tick this may act on. */
    long from();

    /** One past the last tick this may act on. */
    long to();

    /**
     * Whether this asks the boid for anything at all.
     * <p>
     * A decision recorded as <em>not</em> taken is still recorded — that is what makes a plan a
     * complete account of what was chosen rather than only of what was done — so an override that
     * asks for nothing is normal and is not the same as an absent one.
     */
    boolean asks();

    /** Whether this is in force at {@code tick}. */
    default boolean actsAt(long tick) { return tick >= from() && tick < to(); }

    /**
     * A fixed turn held over a span of ticks, for an <em>analysis</em> that wants a boid steered
     * bluntly and does not care where it ends up.
     * <p>
     * <b>This is not a plan kind and must not become one again.</b> It is what
     * {@link ThreeBoidPhase} and {@link ThreeBoidSamples} use to say "turn right the whole time"
     * while mapping phase, and what a synthetic grading override is. The class it used to live in
     * carried a label format as well, which made it the unit a search emitted and a corpus stored;
     * that was the mistake, because an absolute tick cannot survive a change of warm-up and the
     * boid it steers does not arrive when the plan assumed. Deleted 2026-09-08. There is
     * deliberately no {@code label()} worth parsing here — see {@link #parse}.
     */
    static PsyboidOverride held(int onset, int duration, int direction, int psyboid) {
        return new PsyboidOverride() {
            public int psyboid() { return psyboid; }

            public String label() { return "held" + psyboid + "@" + onset + "+" + duration; }

            public long from() { return onset; }

            public long to() { return (long) onset + duration; }

            public boolean asks() { return duration > 0; }

            public void calculate(Movement movement, int i) {
                if (i == psyboid && actsAt((long) movement.boids.tick())) {
                    movement.movement[i] = direction;
                }
            }
        };
    }

    /**
     * Reads a label back. Handles every kind that does not need the map.
     *
     * @throws IllegalArgumentException for a kind that does, naming the overload that can
     */
    static PsyboidOverride parse(String label) {
        throw new IllegalArgumentException("'" + label + "' names an override that is a function "
                + "of the map, so reading it back needs parse(label, map, facts)");
    }

    /** The same, for a context where the map is available. Handles every kind. */
    static PsyboidOverride parse(String label, NavMap map, SolverFacts f) {
        if (label.startsWith("q")) return EdgePilot.parsePilot(label, map, f);
        throw new IllegalArgumentException("no override kind is written '" + label.charAt(0)
                + "': " + label);
    }
}
