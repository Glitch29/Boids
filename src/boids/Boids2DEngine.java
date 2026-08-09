package boids;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.Override;
import java.nio.file.Path;
import java.util.Random;

public class Boids2DEngine implements Engine {
    private static final int MAX_SEED_ATTEMPTS = 10_000;
    private final NavMap map;
    private final double turningRadius;
    private final double speed;
    private final int defaultFlockSize;
    private final MovementControl flocking;
    private final MovementControl collision;

    Boids2DEngine(ScenarioParameter parameters) throws IOException {
        turningRadius = parameters.turningRadius();

        // Speed and the perception radii must come from the same radius the map is
        // built at. Deriving any of them from a constant instead lets the map assume
        // a boid more agile than the one actually flying, which reads as boids
        // sailing straight through walls the map says they can avoid.
        speed = Params.speed(turningRadius);
        map = NavMapBuilder.buildFromPng(parameters.mapPath(), (int) Math.round(turningRadius));
        flocking = new MovementLogic(turningRadius);

        defaultFlockSize = parameters.flockSize();
        NavMapRender.write(map,1,Path.of("render","recent","navmap.png"));
        collision = new Collision();
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
    @Override
    public Sim.State tick(Sim.State state) {
        MovementControl.Movement movement = new MovementControl.Movement(state.x, state.y, state.h, state.tick);
        int n = state.n;
        double[] x = new double[n];
        double[] y = new double[n];
        int[] h = new int[n];

        flocking.calculate(movement);
        for (PsyboidOverride override : state.psyboidOverrides) {
            override.calculate(movement);
        }
        collision.calculate(movement);

        int score = 0;
        for (int i = 0; i < n; i++) {
            h[i] = Math.floorMod(state.h[i] + movement.movement[i], Params.TURNS);

            // Turn is applied before translation, so a boid moves along its new heading.
            double px = state.x[i] + speed * Params.COS[h[i]];
            double py = state.y[i] + speed * Params.SIN[h[i]];

            if (px < 0 || py < 0 || px >= map.width() || py >= map.height()) {
                throw new IllegalStateException(String.format(
                        "boid %d left the image at tick %d: (%.1f, %.1f) heading %d",
                        i, state.tick, px, py, h[i]));
            }
            score += map.score((int) px, (int) py);
            x[i] = px;
            y[i] = py;
        }
        return new Sim.State(n, x, y, h, state.tick + 1, state.score + score,
                state.label, state.psyboidOverrides.clone());
    }


    /**
     * A fresh timeline with {@code n} boids placed uniformly over the play area by
     * rejection, and no override.
     * <p>
     * Position and heading are drawn together and redrawn as a pair, because a boid
     * must start somewhere it is both in play and unconstrained — starting inside a
     * restricted interval would mean beginning the run already committed to a turn.
     */
    @Override
    public Sim.State init(long seed) {
        int n = defaultFlockSize;
        double[] x = new double[n];
        double[] y = new double[n];
        int[] h = new int[n];

        // java.util.Random's algorithm is specified exactly in its javadoc, so a
        // given seed produces the same sequence on any JVM.
        Random rng = new Random(seed);
        for (int i = 0; i < n; i++) {
            boolean placed = false;
            for (int attempt = 0; attempt < MAX_SEED_ATTEMPTS && !placed; attempt++) {
                x[i] = rng.nextDouble() * map.width();
                y[i] = rng.nextDouble() * map.height();
                h[i] = rng.nextInt(Params.TURNS);
                placed = map.traversable(x[i], y[i])
                        && map.forcedTurn(x[i], y[i], Params.headingDeg(h[i])) == 0;
            }
            if (!placed) {
                throw new IllegalStateException("no unconstrained start for boid " + i
                        + " in " + MAX_SEED_ATTEMPTS + " attempts");
            }
        }
        return new Sim.State(n, x, y, h, 0L, 0L, "seed" + seed);
    }

    @Override
    public BufferedImage print(Sim.State state) {
        return null;
    }
    class Collision implements MovementControl {

        @Override
        public void calculate(Movement movement) {
            for (int i = 0; i < movement.boids.n(); i++) {
                int forced = map.forcedTurn(movement.boids.x()[i], movement.boids.y()[i], Params.headingDeg(movement.boids.h()[i]));
                if (forced != 0) movement.movement[i] = forced;
            }
        }
    }
}
