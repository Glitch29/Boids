package boids;

/**
 * A movement override installed on a timeline: one boid, one turn, held for a stretch.
 * <p>
 * One of the three {@link MovementControl}s a tick runs through — the flocking rules, this,
 * and the collision veto — so a steering override is not a special case in the engine but
 * another voice choosing from the same three turns. It runs after flocking and before the
 * veto, which is what makes an override a request rather than a guarantee: it can ask for a
 * turn the map will not allow, and {@link NavMap#constrainTurn} still has the last word.
 * <p>
 * Anything else wanting to steer implements {@link MovementControl} the same way, and
 * anything wanting to watch registers with {@link Sim#register} as a {@link SimObserver}.
 * Neither needs a change here.
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
    public void calculate(Movement movement, int i) {
        if (i != psyboid) return;
        if (movement.boids.tick() >= onset && movement.boids.tick() < onset + duration) {
            movement.movement[i] = direction;
        }
    }
}
