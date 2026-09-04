package boids;

/**
 * The flocking constants a search runs under, which need not be the ones the simulation flies.
 * <p>
 * {@link Params} holds what the boids actually do and is not to be touched: changing it would
 * change the simulation, and every corpus and map ingest taken under the old values would
 * quietly stop meaning what it meant. But an analysis that asks "where could a leader be" is
 * not obliged to ask it at the true constants. Widening a window by weakening the straight
 * bias, or by supposing a stronger separation term, gives a superset of the true window — and
 * a superset is exactly what a cover wants, since a cover that misses nothing is worth more
 * than one that is tight.
 * <p>
 * So the constants become an argument. The default is the simulation's, and anything wanting a
 * different question asks it explicitly and locally, without moving anything the simulation
 * reads.
 *
 * @param straightBias hysteresis on holding the current heading. The knob of choice for
 *                     widening a window: it scales how decisive an influence must be before it
 *                     changes the outcome, without altering what any influence is
 * @param wSep         separation weight, active only within {@code rSep}
 */
public record Flocking(double wSep, double wCoh, double wAli, double straightBias,
                       double rSep, double rFlock, boolean sepFalloff) {

    /**
     * Exactly what the simulation uses at this turning radius.
     * <p>
     * {@code sepFalloff} is false here because that is what physics 2 does — see the field's own
     * note. A survey of alternatives sets it explicitly.
     */
    public static Flocking of(double turningRadius) {
        return new Flocking(Params.W_SEP, Params.W_COH, Params.W_ALI, Params.STRAIGHT_BIAS,
                Params.separation(turningRadius), Params.flock(turningRadius), false);
    }

    /**
     * Whether a lone close neighbour's separation term is scaled by the distance falloff.
     * <p>
     * <b>Under physics 2 it is not, and that is an accident rather than a decision.</b>
     * {@link MovementLogic} does compute {@code (rSep - d) / rSep} — but it then normalises the
     * separation sum to a fixed magnitude, and normalising a single vector discards its length.
     * So with one neighbour close the falloff cancels exactly, and a neighbour a pixel inside
     * {@code rSep} pushes precisely as hard as one on top of the boid. The falloff only ever
     * shapes a direction, and only when two or more are close.
     * <p>
     * This flag is what the single-neighbour closed form in {@link EdgeInfluence#steer} has to
     * know, because that form <em>is</em> the aggregation restricted to one neighbour and the two
     * must not be able to disagree. Set it from
     * {@link Aggregation#separationFalloffAtOne()} and never by hand.
     */
    public Flocking sepFalloff(boolean falloff) {
        return new Flocking(wSep, wCoh, wAli, straightBias, rSep, rFlock, falloff);
    }

    public Flocking straightBias(double bias) {
        return new Flocking(wSep, wCoh, wAli, bias, rSep, rFlock, sepFalloff);
    }

    /**
     * The same, supposing separation counts for more than it does.
     * <p>
     * Stands in for influences the single-neighbour model cannot see. With one boid inside the
     * separation radius and {@code n} others outside it, the close one contributes one boid's
     * worth of push while the far ones contribute {@code n/2} boids' worth of pull, so a lone
     * close neighbour is regularly outvoted in the real flock while looking decisive on its
     * own — and the reverse, a close neighbour that is not decisive alone becoming decisive
     * once the others are added. Doubling its weight is the cheap way to admit both without
     * modelling the crowd.
     * <p>
     * Only bites within {@code rSep}, so applying it everywhere still only changes the
     * separation cases.
     */
    public Flocking separation(double weight) {
        return new Flocking(weight, wCoh, wAli, straightBias, rSep, rFlock, sepFalloff);
    }

    /**
     * What a close neighbour looks like once a crowd has cancelled the other two rules.
     * <p>
     * Alignment and cohesion at zero, separation doubled. This is the second model a boid is
     * allowed to choose in the critical-envelope analysis, and its shape is chosen from which
     * way each term moves as boids are added rather than from a wish to widen anything.
     * <p>
     * <b>Alignment and cohesion dilute; separation does not.</b> Every term is summed over
     * neighbours and then normalised, so a spread of neighbours in the annulus partially cancels
     * and the surviving unit vector is the mean of directions that disagree. A neighbour inside
     * {@code rSep} keeps contributing a full-strength push. So the more crowded a boid is, the
     * more its decision is separation and the less it is anything else.
     * <p>
     * <b>And for a separation-dominated leader the other two are obstacles</b>, not help:
     * cohesion pulls along the very line separation is pushing back down. Zeroing them is
     * therefore the faithful model of a crowd, where halving the straight bias would be the
     * opposite — that amplifies alignment and cohesion just where a real crowd suppresses them.
     * <p>
     * It also keeps the fallback narrow. With those two at zero this model cannot manufacture an
     * alignment-dominated admission that the true constants missed; it can only add
     * separation-driven ones.
     */
    public Flocking diluted() {
        return new Flocking(2 * wSep, 0, 0, straightBias, rSep, rFlock, sepFalloff);
    }

    /** Whether a neighbour this far away is inside the separation term. */
    public boolean separating(double distance) {
        return distance < rSep;
    }
}
