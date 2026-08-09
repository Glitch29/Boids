package boids;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field as readable by an outside observer through {@link Observation}.
 * <p>
 * The gate exists so that the exposed surface of a class is declared at the field
 * itself, where anyone reading or reviewing the class will see it, rather than being
 * discoverable only by reading whatever catalogue happens to reach in. Reading an
 * unannotated field fails loudly instead of quietly succeeding.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Observable {
}
