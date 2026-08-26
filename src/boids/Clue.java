package boids;

/**
 * One reason to believe a boid is or is not the psyboid, read off a single arrangement.
 * <p>
 * A clue does not name the psyboid. It says how much more likely each boid became once this
 * particular thing was noticed, as a ratio against the boid's odds before it was: 1 for "this
 * tells you nothing about that boid", 0 for "this rules that boid out", anything else for a
 * lean. The solver multiplies the ratios, which is only correct when clues are independent
 * given the scene — nothing here enforces that, and a clue that overlaps another has to say
 * so rather than being multiplied in twice.
 * <p>
 * Ratios rather than probabilities because a clue has no business knowing the prior. How many
 * boids there are, whether every scenario even has a psyboid, whether the photographer chose
 * the moment — all of that belongs to whoever assembles the answer, and a clue that returned
 * probabilities would have to guess at it.
 * <p>
 * The clues that exist now are exact and return only 1 and 0. That is not the general case
 * and the interface should not be narrowed to it: the reason drift is not yet modelled is
 * that modelling it turns "this boid could not have been led" into "this boid is unlikely to
 * have been led", which is a ratio and not a veto.
 */
public interface Clue {

    /** Short name, for saying which clue produced which numbers. */
    String name();

    /**
     * The odds each boid is the psyboid, relative to what they were before this clue.
     *
     * @return one ratio per boid, in the state's own ordering; never null, never shorter than
     *         {@code state.n}
     */
    double[] odds(SolverFacts facts, Sim.State state);
}
