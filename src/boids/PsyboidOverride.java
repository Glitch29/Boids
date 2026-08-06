package boids;

/**
 * A movement override installed on a timeline.
 * <p>
 * Deliberately empty. The branching search does not exist yet, so nothing implements
 * this and nothing calls it; {@link Sim.State} carries it forward untouched. It exists now
 * so the timeline shape is right before there is anything to put in it.
 */
public class PsyboidOverride implements MovementControl {
    private final int onset;
    private final int duration;
    private final int direction;
    private final int psyboid;

    public PsyboidOverride(int onset, int duration, int direction, int psyboid) {
        this.onset = onset;
        this.duration = duration;
        this.direction = direction;
        this.psyboid = psyboid;
    }

    @java.lang.Override
    public void calculate(Movement movement) {
        if (movement.boids.tick() >= onset && movement.boids.tick() < onset + duration) {
            movement.movement[psyboid] = direction;
        }
    }
}
