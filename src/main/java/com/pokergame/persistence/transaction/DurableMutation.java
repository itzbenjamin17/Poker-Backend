package com.pokergame.persistence.transaction;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Locks a room, rolls back in-memory state if the method fails, and saves the updated
 * {@code RoomSnapshot} to the WAL before running queued broadcasts and timers.
 * <p>
 * If disk saving fails, the change still succeeds in memory and a background retry saves
 * a full checkpoint once storage recovers. When disk persistence is disabled, this annotation
 * still acquires the room lock.
 * </p>
 * <p>
 * Exactly one {@code String} parameter on the method must be marked with {@link RoomId} or
 * {@link GameId} so the room lock can be acquired before touching any room state.
 * </p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DurableMutation {
}
