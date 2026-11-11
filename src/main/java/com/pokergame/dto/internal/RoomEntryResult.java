package com.pokergame.dto.internal;

/**
 * Result of a room entry command (create or join), carrying both the target room
 * identity and the canonical (sanitized) player name as stored in the room.
 * <p>
 * Sanitization happens exactly once, inside the room service. Returning the canonical
 * name lets callers (for example the REST controller building a JWT) bind the token to
 * the same name that was stored, without re-running sanitization and risking divergence.
 * </p>
 *
 * @param roomId              identity of the created or joined room
 * @param canonicalPlayerName sanitized player name as stored in the room
 * @param membershipId membership admitted by this command, captured before releasing the room lease
 */
public record RoomEntryResult(
        String roomId,
        String canonicalPlayerName,
        String membershipId
) {
    /** Creates an entry without membership metadata for legacy callers. */
    public RoomEntryResult(String roomId, String canonicalPlayerName) {
        this(roomId, canonicalPlayerName, null);
    }
}
