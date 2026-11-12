package com.pokergame.service;

import com.pokergame.model.Game;
import com.pokergame.model.Room;

/** Decides when active-game inactivity applies using the current room and authoritative time. */
final class GameInactivityPolicy {
    static final long ALLOWANCE_MS = 15 * 60_000L;

    private GameInactivityPolicy() { }

    /** Reads the deadline from the room that owns it. */
    static Long deadline(Room room) {
        return room == null ? null : room.getInactivityDeadlineEpochMs();
    }

    /** Checks an ordinary activity deadline, which must be configured and positive. */
    static boolean isExpired(Long deadline, long now) {
        return deadline != null && deadline > 0 && hasElapsed(deadline, now);
    }

    /** Checks a callback's captured deadline against the current room. */
    static boolean matchesDeadline(Room room, long expectedDeadline) {
        Long currentDeadline = deadline(room);
        return currentDeadline != null && currentDeadline == expectedDeadline;
    }

    /** Checks the elapsed time after the caller has identified the applicable deadline. */
    static boolean hasElapsed(long deadline, long now) {
        return now >= deadline;
    }

    /** A completed game no longer accepts inactivity resets. */
    static boolean canReset(Game game) {
        return game != null && !game.isGameOver();
    }

    /** Only an accepted change in readiness counts as deliberate activity. */
    static boolean isReadinessActivity(boolean previouslyReady, boolean ready) {
        return previouslyReady != ready;
    }

    /** Computes the new deadline for game creation or accepted deliberate activity. */
    static long nextDeadline(long now) {
        return now + ALLOWANCE_MS;
    }
}
