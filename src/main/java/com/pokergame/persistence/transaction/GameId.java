package com.pokergame.persistence.transaction;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the parameter of a {@link DurableMutation} method that holds the game ID.
 * <p>
 * A game shares its room's ID, so this value is the room that gets locked and saved.
 * The method must have exactly one parameter marked with either {@link RoomId} or
 * {@link GameId}, and it must hold a non-blank {@code String}.
 * </p>
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface GameId {
}
