package boids;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * An override that steers by gates alone: inside a {@link DecisionZone} whose decision has been
 * made, it refuses any turn that would cross the prohibited gate, and otherwise leaves the boid
 * to the flock.
 * <p>
 * The shape the user specified: cross the gate that opens a zone, be handed gates you will not
 * cross, cross the gate that closes it. This carries the decisions as data — one chosen option
 * per zone, or none — and holds no state of its own between ticks. <b>It cannot</b>: an override
 * object is shared by every timeline that descends from the state it was installed on, and a
 * search re-advances the same state any number of times. Whether the boid is between a zone's
 * opening and closing gates is therefore read off the state each tick, through
 * {@link DecisionZone#inside}, which is why a zone is stored as a region.
 *
 * <h2>The rule</h2>
 * The flock's own request stands whenever it crosses no prohibited gate of any zone the boid is
 * inside; otherwise the first of straight, left, right that crosses none. The request is
 * constrained by the veto before it is looked up, because a gate holds effective turns and a
 * request the veto alters is not the transition the boid takes. For an exit decision this is
 * {@link EdgePilot}'s rule exactly — <em>not prohibited</em> is <em>stays on the edge or reaches
 * the chosen successor</em> — so the two must fly identically, and {@code SimTest.zones} checks
 * that they do. A subpath decision is another zone with other prohibitions and the same rule,
 * which is the whole reason for the representation.
 *
 * <h2>Labels</h2>
 * {@code g<psyboid>:<exit choice per edge, '.'-separated, -1 for none>}, then for each subpath
 * zone {@code ;s<edge>=<choice>:<path states, '.'-separated>}, then {@code [@from-to]}. Mirrors
 * {@link EdgePilot}'s {@code q}. Reading one back rebuilds every zone from the map and the facts.
 */
public final class DecisionOverride implements PsyboidOverride {

    /** One zone and the option chosen at it. */
    private record Taken(DecisionZone zone, int choice, int[] path, Gate forbids) {}

    private final int psyboid;
    private final NavMap map;
    private final SolverFacts facts;
    /** Per edge, the exit choice, -1 for none. */
    private final int[] exitChoice;
    /** Per edge, the decided zones on it, exits and subpaths alike. */
    private final Taken[][] byEdge;
    private final List<Taken> subpaths;
    private final long from;
    private final long to;

    private DecisionOverride(int psyboid, NavMap map, SolverFacts facts, DecisionZone[] exits,
                             int[] exitChoice, List<Taken> subpaths, long from, long to) {
        this.psyboid = psyboid;
        this.map = map;
        this.facts = facts;
        this.exitChoice = exitChoice.clone();
        this.subpaths = List.copyOf(subpaths);
        this.from = from;
        this.to = to;
        List<List<Taken>> lists = new ArrayList<>();
        for (int e = 0; e < facts.edges(); e++) lists.add(new ArrayList<>());
        for (int e = 0; e < exitChoice.length; e++) {
            if (exitChoice[e] >= 0 && exits[e] != null) {
                lists.get(e).add(new Taken(exits[e], exitChoice[e], null, exits[e].prohibited(exitChoice[e])));
            }
        }
        for (Taken t : subpaths) lists.get(t.zone().edge()).add(t);
        this.byEdge = new Taken[facts.edges()][];
        for (int e = 0; e < facts.edges(); e++) byEdge[e] = lists.get(e).toArray(new Taken[0]);
    }

    /**
     * Exit decisions only, over all time.
     *
     * @param exitChoice per edge, the successor chosen at that edge's exit, or -1 to leave it to
     *                   the flock
     */
    public static DecisionOverride of(int psyboid, int[] exitChoice, NavMap map, SolverFacts f,
                                      DecisionZone[] exits) {
        return new DecisionOverride(psyboid, map, f, exits, exitChoice, List.of(),
                Long.MIN_VALUE, Long.MAX_VALUE);
    }

    /** Exit decisions plus one subpath, decided {@code choice} ({@link DecisionZone#TAKE} or {@code SKIP}). */
    public static DecisionOverride of(int psyboid, int[] exitChoice, NavMap map, SolverFacts f,
                                      DecisionZone[] exits, DecisionZone subpath, int[] path,
                                      int choice) {
        List<Taken> taken = List.of(new Taken(subpath, choice, path.clone(), subpath.prohibited(choice)));
        return new DecisionOverride(psyboid, map, f, exits, exitChoice, taken,
                Long.MIN_VALUE, Long.MAX_VALUE);
    }

    @Override
    public int psyboid() { return psyboid; }

    @Override
    public long from() { return from; }

    @Override
    public long to() { return to; }

    @Override
    public boolean asks() {
        for (int e = 0; e < exitChoice.length; e++) {
            if (exitChoice[e] >= 0 && exitChoice[e] != facts.straightTo()[e]) return true;
        }
        for (Taken t : subpaths) if (!t.forbids().isEmpty()) return true;
        return false;
    }

    public int[] exitChoice() { return exitChoice.clone(); }

    @Override
    public String label() {
        StringBuilder s = new StringBuilder("g").append(psyboid).append(':');
        for (int e = 0; e < exitChoice.length; e++) s.append(e == 0 ? "" : ".").append(exitChoice[e]);
        for (Taken t : subpaths) {
            s.append(";s").append(t.zone().edge()).append('=').append(t.choice()).append(':');
            for (int i = 0; i < t.path().length; i++) s.append(i == 0 ? "" : ".").append(t.path()[i]);
        }
        if (from != Long.MIN_VALUE || to != Long.MAX_VALUE) {
            s.append('@').append(from).append('-').append(to);
        }
        return s.toString();
    }

    /** Inverse of {@link #label()}; rebuilds the zones, which are a function of the facts. */
    static DecisionOverride parseZones(String label, NavMap map, SolverFacts f) {
        int at = label.indexOf('@');
        String body = at < 0 ? label : label.substring(0, at);
        String[] parts = body.split(";");
        int colon = parts[0].indexOf(':');
        int psyboid = Integer.parseInt(parts[0], 1, colon, 10);
        int[] exitChoice = ints(parts[0].substring(colon + 1));
        DecisionZone[] exits = DecisionZone.exits(map, f);
        List<Taken> taken = new ArrayList<>();
        for (int i = 1; i < parts.length; i++) {
            String p = parts[i];
            int eq = p.indexOf('='), c = p.indexOf(':');
            int edge = Integer.parseInt(p, 1, eq, 10);
            int choice = Integer.parseInt(p, eq + 1, c, 10);
            int[] path = ints(p.substring(c + 1));
            DecisionZone zone = DecisionZone.subpath(map, f, edge, path);
            taken.add(new Taken(zone, choice, path, zone.prohibited(choice)));
        }
        long from = Long.MIN_VALUE, to = Long.MAX_VALUE;
        if (at >= 0) {
            int dash = label.indexOf('-', at + 2);
            from = Long.parseLong(label, at + 1, dash, 10);
            to = Long.parseLong(label, dash + 1, label.length(), 10);
        }
        return new DecisionOverride(psyboid, map, f, exits, exitChoice, taken, from, to);
    }

    private static int[] ints(String dotted) {
        String[] parts = dotted.split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = Integer.parseInt(parts[i]);
        return out;
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
        if (edge < 0 || edge >= byEdge.length || byEdge[edge].length == 0) return;

        int asked = Math.max(-1, Math.min(1, movement.movement[i]));
        if (allowed(state, x, y, h, asked, edge)) return;
        for (int turn : ORDER) {
            if (allowed(state, x, y, h, turn, edge)) {
                movement.movement[i] = turn;
                return;
            }
        }
        throw new IllegalStateException(String.format(
                "every turn from (%d,%d,%d) on edge %d crosses a prohibited gate of %s; on a "
                        + "valid decomposition an exit zone cannot do this, and a subpath zone "
                        + "that does is not sound", x, y, h, edge, Arrays.toString(byEdge[edge])));
    }

    /** Whether asking for {@code turn} here crosses no prohibited gate of any zone the boid is in. */
    private boolean allowed(int state, int x, int y, int h, int turn, int edge) {
        int effective = map.constrainTurn(x, y, h, turn);
        for (Taken t : byEdge[edge]) {
            if (t.zone().inside(state) && t.forbids().crosses(state, effective)) return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return "DecisionOverride" + Arrays.toString(exitChoice)
                + (subpaths.isEmpty() ? "" : " + " + subpaths.size() + " subpath(s)");
    }
}
