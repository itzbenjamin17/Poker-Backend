package com.pokergame.exception;

/**
 * A join attempt supplied the wrong password for a private room. Still a client error (HTTP 400),
 * but distinguishable so the join endpoint can count wrong guesses against that room.
 */
public class InvalidRoomPasswordException extends BadRequestException {
    /** Creates the standard wrong-password failure. */
    public InvalidRoomPasswordException() {
        super("Invalid password");
    }
}
