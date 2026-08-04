package boids;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Random;

/**
 * The simulation engine: it owns the play area's navigation and knows how to advance a
 * {@link State}. It holds nothing that changes as a run proceeds, so one instance can
 * drive any number of independent timelines concurrently.
 * <p>
 * Leaving the play area carries no special handling — a boid out of bounds is steered
 * by the navigation map like any other, and simply flies on if it is beyond the map's
 * reach. Leaving the <em>image</em> is fatal and throws.
 */
public final class Sim {

    private static final long DEFAULT_SEED = 1234L;
    private static final int MAX_SEED_ATTEMPTS = 10_000;

    private final NavMap area;

    /**
     * @param playArea PNG where {@code #000000} is out of bounds
     * @param navRadius the turning radius the navigation map is built at. Normally
     *                  {@link Params#R_TURN}, but building at a larger radius is the
     *                  knob for making boids commit to their turns earlier than they
     *                  strictly must.
     */
    public Sim(Path playArea, double navRadius) throws IOException {
        this.area = NavMapBuilder.buildFromPng(playArea, (int) Math.round(navRadius));
    }

    public NavMap area() { return area; }

    public State init(int n) {
        return init(n, DEFAULT_SEED);
    }

    /**
     * A fresh timeline with {@code n} boids placed uniformly over the play area by
     * rejection, and no override.
     * <p>
     * Position and heading are drawn together and redrawn as a pair, because a boid
     * must start somewhere it is both in play and unconstrained — starting inside a
     * restricted interval would mean beginning the run already committed to a turn.
     */
    public State init(int n, long seed) {
        double[] x = new double[n];
        double[] y = new double[n];
        int[] h = new int[n];

        // java.util.Random's algorithm is specified exactly in its javadoc, so a
        // given seed produces the same sequence on any JVM.
        Random rng = new Random(seed);
        for (int i = 0; i < n; i++) {
            boolean placed = false;
            for (int attempt = 0; attempt < MAX_SEED_ATTEMPTS && !placed; attempt++) {
                x[i] = rng.nextDouble() * area.width();
                y[i] = rng.nextDouble() * area.height();
                h[i] = rng.nextInt(Params.TURNS);
                placed = area.traversable(x[i], y[i])
                        && area.forcedTurn(x[i], y[i], Params.headingDeg(h[i])) == 0;
            }
            if (!placed) {
                throw new IllegalStateException("no unconstrained start for boid " + i
                        + " in " + MAX_SEED_ATTEMPTS + " attempts");
            }
        }
        return new State(n, x, y, h, 0L, null);
    }

    /**
     * Advance one tick, returning a new state and leaving the argument untouched.
     * <p>
     * The play area's compulsory turn is applied here rather than folded into the
     * decision layer, so a boid facing a wall has its choice removed rather than
     * discouraged.
     *
     * @throws IllegalStateException if a boid leaves the image
     */
    public State step(State s) {
        int n = s.n;
        double[] x = new double[n];
        double[] y = new double[n];
        int[] h = new int[n];

        for (int i = 0; i < n; i++) {
            // decide() reads only s, which no longer changes, so deciding and acting
            // can share one pass without any risk of reading a half-updated tick.
            int turn = Rules.decide(s, i);

            int forced = area.forcedTurn(s.x[i], s.y[i], Params.headingDeg(s.h[i]));
            if (forced != 0) turn = forced;

            int a = Math.floorMod(s.h[i] + turn, Params.TURNS);
            h[i] = a;

            // Turn is applied before translation, so a boid moves along its new heading.
            double px = s.x[i] + Params.SPEED * Params.COS[a];
            double py = s.y[i] + Params.SPEED * Params.SIN[a];

            if (px < 0 || py < 0 || px >= area.width() || py >= area.height()) {
                throw new IllegalStateException(String.format(
                        "boid %d left the image at tick %d: (%.1f, %.1f) heading %d",
                        i, s.tick, px, py, a));
            }

            x[i] = px;
            y[i] = py;
        }

        return new State(n, x, y, h, s.tick + 1, s.override);
    }

    /**
     * Advance until the state reaches {@code targetTick}. Returns the argument
     * unchanged if it is already there.
     */
    public State stepTo(State s, long targetTick) {
        if (targetTick < s.tick) {
            throw new IllegalArgumentException(
                    "cannot step back from tick " + s.tick + " to " + targetTick);
        }
        while (s.tick < targetTick) s = step(s);
        return s;
    }
}
