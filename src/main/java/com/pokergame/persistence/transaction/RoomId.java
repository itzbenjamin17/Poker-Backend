package com.pokergame.persistence.transaction;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the parameter of a {@link DurableMutation} method that holds the room ID.
 * <p>
 * The save and the room lock both use this value. Use this for methods whose parameter is
 * called a room ID; use {@link GameId} when it is called a game ID. A game shares its room's ID,
 * so both annotations mean the same thing. The method must have exactly one parameter marked
 * with either {@link RoomId} or {@link GameId}, and it must hold a non-blank {@code String}.
 * </p>
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface RoomId {
}
