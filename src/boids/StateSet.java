package boids;

import java.util.function.IntConsumer;

/**
 * A set of {@code (x, y, d)} states, and the operations we build <b>stable+</b> out of.
 * <p>
 * An interface rather than a class because stable+ is not yet defined and is not expected to be
 * got right first time. What a run of this analysis produces is a <em>chain</em> —
 * {@code pureStable(1).partialTick(STRAIGHT).closed(STRAIGHT).expandByAgreement(...)} — and the
 * useful thing to be able to change is one link of it, without the previous definition being
 * overwritten by the next one.
 *
 * <h2>What stable+ is for</h2>
 * A boid on a stable edge owes no explanation, and that is the test the whole solver rests on.
 * But <b>stable</b> is what a boid alone can hold, and no boid in a real scene is alone: the
 * flock jostles everyone slightly off it constantly, without any of that requiring a psyboid or
 * anything unusual. <b>Stable+ is meant to name the states a boid reaches in ordinary multi-boid
 * traffic</b> — visited without psyboid activity and without abnormal circumstances — so that a
 * history which merely starts slightly off the stable set is not thereby unexplained.
 *
 * <h2>Every operation returns a new set and unions with this one</h2>
 * {@code partialTick} and {@code closed} both <em>add</em> to the receiver rather than replacing
 * it, because the definitions they implement are lists of clauses — "these states, and their
 * partial-tick successors, and the straight closure of all that" — not a pipeline of
 * replacements. Nothing here mutates.
 *
 * <h2>Ints, not objects</h2>
 * A state is its navmap index, as everywhere else in this project, and a set is a bitset over
 * those. The alternative reading of {@code Set<State>} — boxed objects — would be 136,276
 * {@code Integer}s per set on dabeone and the sets are built by the thousand.
 */
public interface StateSet {

    /** Which turn an operation steers with. */
    enum Steering {
        LEFT(-1), STRAIGHT(0), RIGHT(+1);

        private final int turn;

        Steering(int turn) { this.turn = turn; }

        /** The turn as the engine takes it: {@code -1}, {@code 0} or {@code +1}. */
        public int turn() { return turn; }
    }

    int size();

    boolean contains(int state);

    /** The members, ascending. */
    int[] toArray();

    void forEach(IntConsumer each);

    StateSet union(StateSet other);

    StateSet minus(StateSet other);

    /**
     * Every state a single partial tick of {@code how} reaches, added to this set.
     * <p>
     * <b>Why a partial tick is in the definition at all.</b> Boids do not stay evenly spread in
     * position modulo the step length — the step is about four pixels and a straight orbit lands
     * on one phase of that lattice, so the states between two consecutive stable states are not
     * stable however long anything coasts. They are still perfectly ordinary places to be, and a
     * boid knocked into one by traffic stays there. Turning as the rules say but advancing only
     * partway, onto the samples the collision test itself walks, is what recovers them.
     * <p>
     * <b>Applied once, and that is load-bearing.</b> Chaining partial ticks would let a boid
     * strafe sideways a pixel at a time and the set would grow to the whole map.
     */
    StateSet partialTick(Steering how);

    /** The forward closure under {@code how}, added to this set. */
    StateSet closed(Steering how);

    /**
     * Everywhere a quorum of {@code influencers} could push a boid in this set, and where it
     * would end up.
     * <p>
     * The question stable+ has to answer is which departures from stable are ordinary. This
     * answers it by consensus rather than by possibility: a turn counts as ordinary if
     * {@code |influencers| / agreementRatio} of them would induce it, so a placement that only
     * one contrived position produces is not admitted while one that a broad swathe of the
     * ordinary traffic produces is.
     * <p>
     * <b>The turn's duration is not known, so every plausible end is taken.</b> The coalition is
     * carried forward alongside the boid — the influencers coast, the boid turns — and the boid's
     * state is collected at every tick as a mid-turn state, and additionally as an end-turn state
     * from the moment an {@code agreementRatio} share of the coalition has stopped asking for the
     * turn. The turn stops when all but that share have.
     * <p>
     * <b>End-turn states are closed under straight travel and mid-turn states are not.</b> A boid
     * that has finished turning coasts onward and everything downstream of it is reached; a boid
     * still mid-turn has not finished, and closing from there would credit it with a straight
     * future it does not have.
     *
     * @param influencers where the other boid may be. Must be closed under straight travel, since
     *                    the coalition is advanced by coasting
     */
    StateSet expandByAgreement(StateSet influencers, int agreementRatio);
}
