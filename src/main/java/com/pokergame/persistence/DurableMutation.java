package com.pokergame.persistence;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method that changes a room's state. It ensures that any changes made by the method 
 * are safely saved to disk before they become visible to the rest of the application.
 * <p>
 * The room ID must be explicitly provided in the annotation. If we tried to automatically 
 * extract the room ID by inspecting the room's data, it could lead to bugs and race conditions 
 * when locking the room or recovering it after a crash.
 * </p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DurableMutation {
    /**
     * A Spring Expression (SpEL) string that tells the application how to find the room ID 
     * from the method's parameters. 
     * <p>
     * For example, if your method takes a parameter named {@code roomId}, you would use:
     * {@code @DurableMutation(roomId = "#roomId")}
     *
     * @return a SpEL expression that evaluates to the room ID
     */
    String roomId();
}
