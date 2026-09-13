package boids;

import java.util.Arrays;

/**
 * An override that steers by gates alone: inside a {@link DecisionZone} whose decision has been
 * made, it refuses any turn that would cross the prohibited gate, and otherwise leaves the boid
 * to the flock.
 * <p>
 * The shape the user specified: cross the gate that opens a zone, be handed gates you will not
 * cross, cross the gate that closes it. This carries the decisions as data — one chosen
 * successor per edge, or none — and holds no state of its own between ticks. <b>It cannot</b>:
 * an override object is shared by every timeline that descends from the state it was installed
 * on, and a search re-advances the same state any number of times. Whether the boid is between
 * the opening and closing gates is therefore read off the state each tick, through
 * {@link DecisionZone#inside}, which is why the zone is stored as a region.
 *
 * <h2>The rule</h2>
 * The flock's own request stands whenever it does not cross the prohibited gate; otherwise the
 * first of straight, left, right that does not. The request is constrained by the veto before it
 * is looked up, because a gate holds effective turns and a request the veto alters is not the
 * transition the boid takes. For an exit decision this is {@link EdgePilot}'s rule exactly —
 * <em>not prohibited</em> is <em>stays on the edge or reaches the chosen successor</em> — so the
 * two must fly identically, and {@code SimTest.zones} checks that they do. The difference is that
 * this one is data: a shortcut is another zone with other prohibitions, and needs no new rule.
 *
 * <h2>Labels</h2>
 * {@code g<psyboid>:<choice per edge, '.'-separated, -1 for none>[@from-to]}, mirroring
 * {@link EdgePilot}'s {@code q}. Reading one back rebuilds the zones from the map and the facts.
 */
public final class DecisionOverride implements PsyboidOverride {

    private final int psyboid;
    private final NavMap map;
    private final SolverFacts facts;
    private final DecisionZone[] zones;
    private final int[] choice;
    /** Per edge, the gate the choice forbids, or null where no choice was made. */
    private final Gate[] forbid;
    private final long from;
    private final long to;

    private DecisionOverride(int psyboid, NavMap map, SolverFacts facts, DecisionZone[] zones,
                             int[] choice, long from, long to) {
        this.psyboid = psyboid;
        this.map = map;
        this.facts = facts;
        this.zones = zones;
        this.choice = choice.clone();
        this.from = from;
        this.to = to;
        this.forbid = new Gate[zones.length];
        for (int e = 0; e < zones.length; e++) {
            if (choice[e] >= 0 && zones[e] != null) forbid[e] = zones[e].prohibited(choice[e]);
        }
    }

    /**
     * Over all time.
     *
     * @param choice per edge, the successor chosen at that edge's exit, or -1 to leave it to
     *               the flock
     */
    public static DecisionOverride of(int psyboid, int[] choice, NavMap map, SolverFacts f,
                                      DecisionZone[] zones) {
        return new DecisionOverride(psyboid, map, f, zones, choice, Long.MIN_VALUE, Long.MAX_VALUE);
    }

    public static DecisionOverride of(int psyboid, int[] choice, NavMap map, SolverFacts f,
                                      DecisionZone[] zones, long from, long to) {
        return new DecisionOverride(psyboid, map, f, zones, choice, from, to);
    }

    @Override
    public int psyboid() { return psyboid; }

    @Override
    public long from() { return from; }

    @Override
    public long to() { return to; }

    @Override
    public boolean asks() {
        for (int e = 0; e < choice.length; e++) {
            if (choice[e] >= 0 && choice[e] != facts.straightTo()[e]) return true;
        }
        return false;
    }

    public int[] choice() { return choice.clone(); }

    @Override
    public String label() {
        StringBuilder s = new StringBuilder("g").append(psyboid).append(':');
        for (int e = 0; e < choice.length; e++) s.append(e == 0 ? "" : ".").append(choice[e]);
        if (from != Long.MIN_VALUE || to != Long.MAX_VALUE) {
            s.append('@').append(from).append('-').append(to);
        }
        return s.toString();
    }

    /** Inverse of {@link #label()}; rebuilds the zones, which are a function of the facts. */
    static DecisionOverride parseZones(String label, NavMap map, SolverFacts f) {
        int colon = label.indexOf(':');
        int at = label.indexOf('@');
        int psyboid = Integer.parseInt(label, 1, colon, 10);
        String body = at < 0 ? label.substring(colon + 1) : label.substring(colon + 1, at);
        String[] parts = body.split("\\.");
        int[] choice = new int[parts.length];
        for (int i = 0; i < parts.length; i++) choice[i] = Integer.parseInt(parts[i]);
        long from = Long.MIN_VALUE, to = Long.MAX_VALUE;
        if (at >= 0) {
            int dash = label.indexOf('-', at + 2);
            from = Long.parseLong(label, at + 1, dash, 10);
            to = Long.parseLong(label, dash + 1, label.length(), 10);
        }
        return new DecisionOverride(psyboid, map, f, DecisionZone.exits(map, f), choice, from, to);
    }

    /** Straight first, then left, then right; the same order as {@link EdgePilot}, for the same reasons. */
    private static final int[] ORDER = {0, -1, 1};

    @Override
    public void calculate(Movement movement, int i) {
        if (i != psyboid || !actsAt((long) movement.boids.tick())) return;
        BoidArray boids = movement.boids;
        int x = boids.x()[i], y = boids.y()[i], h = boids.h()[i];
        int state = facts.state(x, y, h);
        if (state < 0 || state >= facts.edgeOf().length) return;
        int edge = facts.edgeOf()[state];
        if (edge < 0 || edge >= zones.length || forbid[edge] == null) return;
        if (!zones[edge].inside(state)) return;

        Gate gate = forbid[edge];
        int asked = Math.max(-1, Math.min(1, movement.movement[i]));
        if (!gate.crosses(state, map.constrainTurn(x, y, h, asked))) return;
        for (int turn : ORDER) {
            if (!gate.crosses(state, map.constrainTurn(x, y, h, turn))) {
                movement.movement[i] = turn;
                return;
            }
        }
        throw new IllegalStateException(String.format(
                "every turn from (%d,%d,%d) on edge %d crosses the gate prohibited by choosing "
                        + "edge %d, which one-step navigability says cannot happen on a valid "
                        + "decomposition", x, y, h, edge, choice[edge]));
    }

    @Override
    public String toString() { return "DecisionOverride" + Arrays.toString(choice); }
}
