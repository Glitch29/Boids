package boids;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Something outside the simulation that wants to watch it run.
 * <p>
 * {@link Sim} knows only this interface, so an observer can live anywhere and see
 * whatever it registers for without the simulation carrying a reference to it or
 * knowing what it does.
 */
public interface SimObserver {

    /** Points in a run at which observers are called. */
    enum Trigger {
        /** Before anything runs. Carries the simulation. */
        SIM_START(Scope.SIM),
        /** After everything has run. Carries the simulation. */
        SIM_END(Scope.SIM),
        /** A timeline's first state. Carries the state. */
        STATE_INIT(Scope.STATE),
        /**
         * A timeline branching into variants. Carries the new state.
         * <p>
         * Part of the vocabulary but not yet fired anywhere — nothing needs it.
         */
        STATE_SPLIT(Scope.STATE),
        /** A timeline advanced by one tick. Carries the new state. */
        STATE_ADVANCE(Scope.STATE);

        enum Scope { SIM, STATE }

        private final Scope scope;

        Trigger(Scope scope) { this.scope = scope; }

        public Scope scope() { return scope; }
    }

    /**
     * Called for each trigger the observer registered for. The observation is valid
     * only for the duration of this call; it reads through to a live object.
     */
    void observe(Trigger trigger, Observation observation);

    /**
     * Holds the registrations and does the dispatch, so the simulation itself carries
     * only a field, a register method, and the hook calls.
     */
    final class Registry {

        private final Map<Trigger, List<SimObserver>> byTrigger = new EnumMap<>(Trigger.class);

        public void register(SimObserver observer, Trigger... triggers) {
            for (Trigger trigger : triggers) {
                byTrigger.computeIfAbsent(trigger, t -> new ArrayList<>()).add(observer);
            }
        }

        public boolean isEmpty() { return byTrigger.isEmpty(); }

        /** @param source the simulation or the state, according to the trigger's scope */
        public void fire(Trigger trigger, Object source) {
            List<SimObserver> observers = byTrigger.get(trigger);
            if (observers == null) return;
            Observation observation = new Observation(trigger, source);
            for (SimObserver observer : observers) observer.observe(trigger, observation);
        }
    }
}
