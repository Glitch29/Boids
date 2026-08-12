package boids;

public interface MovementControl {

    /**
     * The turn boid {@code i} wants, written into {@code movement.movement[i]}.
     * <p>
     * <b>Contract: the value written must be -1, 0 or +1</b> — one step left, straight, or
     * one step right. That is the entire action space, and it is the same for every
     * implementation: the flocking rules, a steering override, and the collision veto all
     * choose from the same three. A boid cannot turn faster by wanting to more.
     * <p>
     * Per boid rather than per flock because boids are advanced in sequence. When boid
     * {@code i} decides, boids {@code 0..i-1} have already moved this tick and the arrays
     * behind {@code movement.boids} hold their new positions, while {@code i+1..n-1} still
     * hold their old ones. Deciding the whole flock against one frozen snapshot instead
     * makes the update symmetric, and symmetry is what lets two boids that arrive at the
     * same position and heading stay identical for the rest of the run.
     */
    void calculate(Movement movement, int i);

    class Movement {
        BoidArray boids;
        int[] movement;

        Movement(int[] x, int[] y, int[] h, long tick) {
            boids = new BoidArray(x.length, x, y, h, tick);
            movement = new int[x.length];
        }
    }
}
