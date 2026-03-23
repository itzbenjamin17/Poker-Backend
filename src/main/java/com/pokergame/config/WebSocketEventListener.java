package com.pokergame.config;

import com.pokergame.model.Room;
import com.pokergame.service.GameLifecycleService;
import com.pokergame.service.RoomService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;

@Component
public class WebSocketEventListener {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketEventListener.class);

    private final RoomService roomService;
    private final GameLifecycleService gameLifecycleService;

    public WebSocketEventListener(RoomService roomService, GameLifecycleService gameLifecycleService) {
        this.roomService = roomService;
        this.gameLifecycleService = gameLifecycleService;
    }

    @EventListener
    public void handleWebSocketDisconnectListener(SessionDisconnectEvent event) {
        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        Principal user = headerAccessor.getUser();

        if (user != null) {
            String username = user.getName();
            logger.info("WebSocket connection dropped. User disconnected: {}", username);

            // Find which room the player is in and remove them
            for (Room room : roomService.getRooms()) {
                if (room.hasPlayer(username)) {
                    try {
                        logger.info("Automatically removing disconnected user '{}' from room '{}'", username,
                                room.getRoomName());
                        String roomId = room.getRoomId();
                        roomService.leaveRoom(roomId, username);

                        if (gameLifecycleService.gameExists(roomId)) {
                            gameLifecycleService.leaveGame(roomId, username);
                        }
                    } catch (Exception e) {
                        logger.error("Failed to remove disconnected user '{}' from room '{}'", username,
                                room.getRoomName(), e);
                    }

                    // Can break because player should only be in one room at a time
                    break;
                }
            }
        }
    }
}