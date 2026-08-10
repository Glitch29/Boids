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
        int[] x = new int[n];
        int[] y = new int[n];
        int[] h = new int[n];

        flocking.calculate(movement);
        for (PsyboidOverride override : state.psyboidOverrides) {
            override.calculate(movement);
        }
        collision.calculate(movement);

        int score = 0;
        long[] boidScore = new long[n];
        for (int i = 0; i < n; i++) {
            h[i] = Math.floorMod(state.h[i] + movement.movement[i], Params.TURNS);

            // Turn is applied before translation, so a boid moves along its new heading.
            int px = state.x[i] + map.stepX(h[i]);
            int py = state.y[i] + map.stepY(h[i]);

            // The collision layer only ever hands back a turn whose whole segment stays
            // in play, so this cannot fire unless a boid was started somewhere dead.
            if (!map.traversable(px, py)) {
                throw new IllegalStateException(String.format(
                        "boid %d left the play area at tick %d: (%d, %d) heading %d",
                        i, state.tick, px, py, h[i]));
            }
            int scored = map.score(px, py);
            score += scored;
            boidScore[i] = state.boidScore[i] + scored;
            x[i] = px;
            y[i] = py;
        }
        return new Sim.State(n, x, y, h, state.tick + 1, state.score + score, boidScore,
                state.label, state.psyboidOverrides.clone());
    }


    /**
     * A fresh timeline with {@code n} boids placed uniformly over the play area by
     * rejection, and no override.
     * <p>
     * Position and heading are drawn together and redrawn as a pair, because a boid must
     * start in a state it can survive from. That is the whole precondition of the
     * no-escape guarantee: from a live state the collision layer can always find a live
     * turn, so a run that starts live stays in play forever.
     */
    @Override
    public Sim.State init(long seed) {
        int n = defaultFlockSize;
        int[] x = new int[n];
        int[] y = new int[n];
        int[] h = new int[n];

        // java.util.Random's algorithm is specified exactly in its javadoc, so a
        // given seed produces the same sequence on any JVM.
        Random rng = new Random(seed);
        for (int i = 0; i < n; i++) {
            boolean placed = false;
            for (int attempt = 0; attempt < MAX_SEED_ATTEMPTS && !placed; attempt++) {
                x[i] = rng.nextInt(map.width());
                y[i] = rng.nextInt(map.height());
                h[i] = rng.nextInt(Params.TURNS);
                placed = map.alive(x[i], y[i], h[i]);
            }
            if (!placed) {
                throw new IllegalStateException("no survivable start for boid " + i
                        + " in " + MAX_SEED_ATTEMPTS + " attempts");
            }
        }
        return new Sim.State(n, x, y, h, 0L, 0L, new long[n], "seed" + seed);
    }

    @Override
    public BufferedImage print(Sim.State state) {
        return null;
    }

    /**
     * Runs last, after the flocking rules and any override, so nothing upstream can
     * steer a boid out of play. It vetoes rather than dictates: the proposed turn stands
     * whenever it survives.
     */
    class Collision implements MovementControl {

        @Override
        public void calculate(Movement movement) {
            BoidArray boids = movement.boids;
            for (int i = 0; i < boids.n(); i++) {
                movement.movement[i] = map.constrainTurn(
                        boids.x()[i], boids.y()[i], boids.h()[i], movement.movement[i]);
            }
        }
    }
}
