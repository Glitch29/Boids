package boids;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Read access to whichever parts of a simulation or state a probe names.
 * <p>
 * The source object is reached by reflection, gated on {@link Observable}. That costs
 * a lookup, cached here, and buys two things: the exposed surface is declared at each
 * field rather than in this file, and adding a new observable is an annotation plus a
 * constant instead of a new accessor threaded through an interface.
 * <p>
 * The reflection is confined to this class. Probes carry their own result type, so
 * observers get a typed value rather than an {@code Object} to cast.
 */
public final class Observation {

    /**
     * A named, typed view over one or more annotated fields of a source object.
     *
     * @param scope  which kind of source this probe reads
     * @param fields the annotated field names it needs, in the order {@code combine}
     *               expects them
     */
    public record Probe<T>(String name, SimObserver.Trigger.Scope scope,
                           String[] fields, Function<Object[], T> combine) {}

    /** Where the psyboid was, and which boid it is. */
    public record Psyboid(int index, double x, double y) {}

    // ---- Catalogue ---------------------------------------------------------

    /** The play area image the run is using. */
    public static final Probe<Path> BACKGROUND = new Probe<>(
            "background", SimObserver.Trigger.Scope.SIM,
            new String[]{"parameter"},
            values -> ((ScenarioParameter) values[0]).mapPath());

    /**
     * The psyboid's position, or null while no override identifies one.
     * <p>
     * Derived rather than stored: which boid is the psyboid is a property of the
     * overrides in force, not a field of the state.
     */
    public static final Probe<Psyboid> PSYBOID = new Probe<>(
            "psyboid", SimObserver.Trigger.Scope.STATE,
            new String[]{"x", "y", "psyboidOverrides"},
            values -> {
                PsyboidOverride[] overrides = (PsyboidOverride[]) values[2];
                if (overrides.length == 0) return null;
                int index = overrides[0].psyboid();
                return new Psyboid(index, ((double[]) values[0])[index], ((double[]) values[1])[index]);
            });

    // ------------------------------------------------------------------------

    private static final Map<String, Field> CACHE = new ConcurrentHashMap<>();

    private final SimObserver.Trigger trigger;
    private final Object source;

    Observation(SimObserver.Trigger trigger, Object source) {
        this.trigger = trigger;
        this.source = source;
    }

    public SimObserver.Trigger trigger() { return trigger; }

    /**
     * @throws IllegalArgumentException if the probe's scope does not match this trigger,
     *         or if a named field is missing or not annotated {@link Observable}
     */
    public <T> T get(Probe<T> probe) {
        if (probe.scope() != trigger.scope()) {
            throw new IllegalArgumentException(
                    "probe " + probe.name() + " reads a " + probe.scope()
                            + " but " + trigger + " carries a " + trigger.scope());
        }
        Object[] values = new Object[probe.fields().length];
        for (int i = 0; i < values.length; i++) {
            values[i] = read(source, probe.fields()[i]);
        }
        return probe.combine().apply(values);
    }

    private static Object read(Object source, String name) {
        Field field = CACHE.computeIfAbsent(
                source.getClass().getName() + "#" + name, key -> resolve(source.getClass(), name));
        try {
            return field.get(source);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("cannot read " + name, e);
        }
    }

    private static Field resolve(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField(name);
                if (!field.isAnnotationPresent(Observable.class)) {
                    throw new IllegalArgumentException(
                            c.getSimpleName() + "." + name + " is not @Observable");
                }
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // keep walking up
            }
        }
        throw new IllegalArgumentException("no field " + name + " on " + type.getSimpleName());
    }
}
