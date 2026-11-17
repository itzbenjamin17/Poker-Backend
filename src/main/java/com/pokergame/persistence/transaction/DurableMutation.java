package com.pokergame.persistence.transaction;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a room mutation that attempts persistence before publishing its effects.
 * Valid mutations continue in memory when saving is unavailable; recovery protection
 * resumes after a full checkpoint catches up with the accepted state.
 * <p>
 * The room ID must be explicitly provided by marking exactly one method parameter with
 * {@link RoomId} or {@link GameId}. If we tried to automatically extract the room ID by
 * inspecting the room's data, it could lead to bugs and race conditions when locking the room
 * or recovering it after a crash.
 * </p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DurableMutation {
}
