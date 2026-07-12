package com.pokergame.wal;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Indicates that a method's invocation should be written to the Write-Ahead Log (WAL).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface WalLogged {

    /**
     * SpEL expression to extract the room/game ID.
     * Uses method arguments (e.g., "#roomId") or the return value (e.g., "#result").
     */
    String roomId();

    /**
     * If true, the log will be written AFTER the method executes successfully.
     * Use this for creation methods where the ID is generated inside the method (Write-Behind).
     * If false (default), the log is written BEFORE the method executes (Write-Ahead).
     */
    boolean writeAfter() default false;
}
