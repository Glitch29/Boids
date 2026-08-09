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

    public int onset() { return onset; }
    public int duration() { return duration; }
    public int direction() { return direction; }
    public int psyboid() { return psyboid; }

    /**
     * Compact identifier, so a score can be traced back to the override that produced
     * it. Contains no commas or pipes, both of which separate fields elsewhere.
     */
    public String label() {
        char turn = direction < 0 ? 'L' : direction > 0 ? 'R' : 'S';
        return "p" + psyboid + turn + "d" + duration + "t" + onset;
    }

    /** Inverse of {@link #label()}. */
    public static PsyboidOverride parse(String label) {
        int turnAt = 1;
        while ("LSR".indexOf(label.charAt(turnAt)) < 0) turnAt++;
        int dAt = label.indexOf('d', turnAt);
        int tAt = label.indexOf('t', dAt);

        int psyboid = Integer.parseInt(label, 1, turnAt, 10);
        char turn = label.charAt(turnAt);
        int duration = Integer.parseInt(label, dAt + 1, tAt, 10);
        int onset = Integer.parseInt(label, tAt + 1, label.length(), 10);

        return new PsyboidOverride(onset, duration, turn == 'L' ? -1 : turn == 'R' ? 1 : 0, psyboid);
    }

    @java.lang.Override
    public void calculate(Movement movement) {
        if (movement.boids.tick() >= onset && movement.boids.tick() < onset + duration) {
            movement.movement[psyboid] = direction;
        }
    }
}
