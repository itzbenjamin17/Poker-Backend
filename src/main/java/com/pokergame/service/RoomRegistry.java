package com.pokergame.service;

import static java.util.Objects.requireNonNull;

import com.pokergame.model.Room;
import com.pokergame.util.RoomCoordinator;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Stores live rooms keyed by room ID and verifies room lock ownership on every access by ID.
 * Looking through all rooms (by name) takes no lock, so callers must not read mutable room fields
 * from those results without acquiring the room's lock.
 */
@Component
class RoomRegistry {
    private final RoomCoordinator roomCoordinator;

    private final Map<String, Room> rooms = new ConcurrentHashMap<>();

    RoomRegistry(RoomCoordinator roomCoordinator) {
        this.roomCoordinator = roomCoordinator;
    }

    /**
     * Returns the live room for {@code roomId}, or {@code null} if none exists.
     *
     * @param roomId the room ID to look up
     * @return the live room, or {@code null} if not found
     */
    Room get(String roomId) {
        roomCoordinator.assertHeldByCurrentThread(roomId);
        return rooms.get(roomId);
    }

    /**
     * Stores or replaces the live room for its ID.
     *
     * @param room the room to store
     */
    void put(Room room) {
        requireNonNull(room, "room must not be null");
        roomCoordinator.assertHeldByCurrentThread(room.getRoomId());
        rooms.put(room.getRoomId(), room);
    }

    /**
     * Removes the live room for {@code roomId}.
     *
     * @param roomId the room ID to remove
     * @return the removed room, or {@code null} if there was none
     */
    Room remove(String roomId) {
        roomCoordinator.assertHeldByCurrentThread(roomId);
        return rooms.remove(roomId);
    }

    /**
     * Returns every live room without taking any room lock.
     *
     * @return a live view of all rooms
     */
    Collection<Room> all() {
        return rooms.values();
    }
}
