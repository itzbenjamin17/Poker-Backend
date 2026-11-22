package com.pokergame.persistence;

/**
 * The live rooms and games as persistence sees them. It lets the durable-mutation aspect capture,
 * roll back and evict a room without depending on the services that own the rooms.
 * <p>
 * Every method must be called while the calling thread holds the room's lock.
 * </p>
 */
public interface RoomAggregates {

    /**
     * Returns the live room and game for {@code roomId}.
     *
     * @param roomId the room ID
     * @return the live aggregate, or {@code null} if no such room exists
     */
    RoomAggregate find(String roomId);

    /**
     * Puts {@code aggregate} in place of whatever is live for its room, as a rollback does after a failed
     * change. Game timers and the game inactivity timer are scheduled again from the restored state,
     * without queueing any side effects.
     *
     * @param aggregate the room and game to make live
     */
    void replace(RoomAggregate aggregate);

    /**
     * Removes the room and its game from memory, cancelling their timers. Nothing is saved and no
     * client is told.
     *
     * @param roomId the room ID
     */
    void remove(String roomId);
}
