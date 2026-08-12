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
     * Boids are advanced in index order rather than all at once, each deciding against
     * the positions its predecessors have already reached this tick. A simultaneous
     * update is symmetric, and under a symmetric update two boids that ever arrive at the
     * same position and heading are thereafter permanently identical: same neighbours,
     * same decision, forever. Sequencing breaks that symmetry, because the earlier boid
     * has already moved by the time the later one looks.
     * <p>
     * The play area's compulsory turn is applied here rather than folded into the
     * decision layer, so a boid facing a wall has its choice removed rather than
     * discouraged.
     *
     * @throws IllegalStateException if a boid leaves the image
     */
    @Override
    public Sim.State tick(Sim.State state) {
        int n = state.n;

        // Copies, mutated in place as each boid takes its turn. The argument's arrays are
        // never written to: a state that has been handed out is immutable, and the search
        // relies on being able to re-advance the same state any number of times.
        int[] x = state.x.clone();
        int[] y = state.y.clone();
        int[] h = state.h.clone();

        MovementControl.Movement movement = new MovementControl.Movement(x, y, h, state.tick);

        int score = 0;
        long[] boidScore = new long[n];
        for (int i = 0; i < n; i++) {
            flocking.calculate(movement, i);
            for (PsyboidOverride override : state.psyboidOverrides) {
                override.calculate(movement, i);
            }

            // One step of turn, whoever proposed it. Every control already respects this,
            // but constrainTurn indexes a three-row table by the proposal, so a stray value
            // would fail deep inside the navigation map rather than here. Clamping makes
            // the limit an invariant rather than a convention, and makes it structural
            // that a steered boid gets exactly the choice an unsteered one does.
            movement.movement[i] = Math.max(-1, Math.min(1, movement.movement[i]));

            collision.calculate(movement, i);

            int heading = Math.floorMod(h[i] + movement.movement[i], Params.TURNS);

            // Turn is applied before translation, so a boid moves along its new heading.
            int px = x[i] + map.stepX(heading);
            int py = y[i] + map.stepY(heading);

            // The collision layer only ever hands back a turn whose whole segment stays
            // in play, so this cannot fire unless a boid was started somewhere dead.
            if (!map.traversable(px, py)) {
                throw new IllegalStateException(String.format(
                        "boid %d left the play area at tick %d: (%d, %d) heading %d",
                        i, state.tick, px, py, heading));
            }
            int scored = map.score(px, py);
            score += scored;
            boidScore[i] = state.boidScore[i] + scored;

            h[i] = heading;
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
        public void calculate(Movement movement, int i) {
            BoidArray boids = movement.boids;
            movement.movement[i] = map.constrainTurn(
                    boids.x()[i], boids.y()[i], boids.h()[i], movement.movement[i]);
        }
    }
}
