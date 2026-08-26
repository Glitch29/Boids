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
                       double rSep, double rFlock) {

    /** Exactly what the simulation uses at this turning radius. */
    public static Flocking of(double turningRadius) {
        return new Flocking(Params.W_SEP, Params.W_COH, Params.W_ALI, Params.STRAIGHT_BIAS,
                Params.separation(turningRadius), Params.flock(turningRadius));
    }

    public Flocking straightBias(double bias) {
        return new Flocking(wSep, wCoh, wAli, bias, rSep, rFlock);
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
        return new Flocking(weight, wCoh, wAli, straightBias, rSep, rFlock);
    }

    /** Whether a neighbour this far away is inside the separation term. */
    public boolean separating(double distance) {
        return distance < rSep;
    }
}
