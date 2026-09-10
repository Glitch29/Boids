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
     * Everything that cannot avoid this set, added to it: repeatedly take any state all of whose
     * successors are already members, until nothing more qualifies.
     * <p>
     * <b>What it fixes.</b> A set built forwards from one seed is reached by some states and
     * missed by others that are, as far as anything downstream can tell, in the same position —
     * a state whose every future runs through the set is not distinguishable from a member by
     * where it can get to, but a decomposition will still split it out for not being one. Adding
     * it removes a distinction that was never real. Without this a set with a single source is
     * reliably <em>backwards-imperfect</em>, and that shows up as an edge shattering.
     * <p>
     * <b>Not the same as a backward closure.</b> Closing backwards would add every predecessor;
     * this adds only predecessors with nowhere else to go, so a state that can still dodge the
     * set stays out. That is what keeps the result meaningful rather than growing to the whole
     * map.
     * <p>
     * Successors are the map's post-veto landings over all three turns, which is what a boid can
     * actually do rather than what it may ask for.
     */
    StateSet backwardsPerfect();

    /**
     * Everything that can only be reached from this set, added to it: repeatedly take any state
     * all of whose predecessors are already members, until nothing more qualifies.
     * <p>
     * The mirror of {@link #backwardsPerfect}, and needed for the mirror reason. That operation
     * removes a distinction nothing downstream can see; this one removes a distinction nothing
     * <em>upstream</em> can see. A state reachable only through the set is, as far as any history
     * can tell, already part of it — but a decomposition keyed on where a state can have come
     * from will still cut it out for not being a member.
     * <p>
     * <b>The two cannot feed each other, so one pass of each is a joint fixed point.</b> Anything
     * backwards perfection adds has all its successors in the set already, so it can never be the
     * last missing predecessor of a non-member; anything forwards perfection adds has all its
     * predecessors in the set already, so it can never be the last missing successor of one.
     * Order does not matter and iterating does nothing.
     */
    StateSet forwardsPerfect();

    /**
     * The same states travelled the other way: this set's mirror image.
     * <p>
     * <b>The inverse of {@code (x, y, d)} is {@code (x - stepX(d), y - stepY(d), d + TURNS/2)}</b>
     * — one step back down the heading, then flipped. Not the same pixel: a state is a position
     * <em>and</em> the step it is about to take, and reversing the step moves the position by one.
     * Pairing at the same pixel is off by exactly that step, which is the note
     * {@code SimTest.renderEdges} carries where it pairs edges with their inverses.
     * <p>
     * It is an involution because the step table is antipodally exact — {@code step(d + TURNS/2)}
     * is precisely {@code -step(d)} — so the position offset cancels on the second application
     * rather than drifting.
     * <p>
     * <b>A member whose inverse is not live is dropped</b>, so the result can be smaller than the
     * receiver. That is not a defect: a one-way stretch of corridor has states whose reverse no
     * boid could occupy.
     */
    StateSet inverted();

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
     * <b>One quorum, and the turn may stop at any tick.</b> The same
     * {@code |influencers| / agreementRatio} gates starting the turn and continuing it, and
     * nothing gates ending it — a boid is free to stop turning whenever, so every state along the
     * turn is a state a boid can be left in and every one of them is added, together with
     * everything downstream of it. The coalition is carried forward alongside the boid: the
     * influencers coast, the boid turns, and the turn ends when the quorum stops asking for it.
     * <p>
     * An earlier version tied three numbers to the one ratio — the quorum to start, the share
     * that had to drop out before the turn could end, and the share that had to remain for it to
     * continue — and separated mid-turn states, which were not closed, from end-turn states,
     * which were. That left the result not closed under straight travel, which is both harder to
     * reason about and harder to predict: with one quorum and every state closed, an exit can
     * only enter the set if an exit window sits on the stable loop with a quorum of influencers
     * on it.
     *
     * @param influencers where the other boid may be. Must be closed under straight travel, since
     *                    the coalition is advanced by coasting
     */
    StateSet expandByAgreement(StateSet influencers, int agreementRatio);

    /**
     * The same, naming the quorum outright instead of deriving it from the influencer count.
     * <p>
     * The primary form. A ratio was the wrong handle: what the quorum means is <b>how many ticks
     * of influencer positions have to agree</b>, and turning that into a divisor makes it depend
     * on how densely the influencer set happens to sample its loop — which varies with how the
     * straight-travel cycles came out on the map, not with anything about flocking.
     */
    StateSet expandByQuorum(StateSet influencers, int quorum);
}
