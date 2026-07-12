package com.pokergame.persistence;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an authoritative room mutation that must not become observable before
 * its resulting aggregate state is durably committed.
 * <p>
 * The room identity is explicit because it is also the serialization and WAL
 * boundary; allowing advice to infer it from mutable state would make locking and
 * recovery ambiguous.
 * </p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DurableMutation {
    /**
     * Supplies the Spring Expression Language expression that resolves the stable
     * room identifier from method arguments.
     *
     * @return expression resolving to a nonblank room ID
     */
    String roomId();
}
