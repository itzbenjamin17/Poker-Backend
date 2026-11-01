package com.pokergame.security;

import com.pokergame.config.WebSocketAuthInterceptor;
import com.pokergame.model.Room;
import com.pokergame.service.GameStateService;
import com.pokergame.service.RoomService;
import com.pokergame.service.HandEvaluatorService;
import com.pokergame.model.Game;
import com.pokergame.model.Player;
import com.pokergame.dto.response.PrivatePlayerState;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.user.DefaultUserDestinationResolver;
import org.springframework.messaging.simp.user.SimpSession;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Guards membership isolation at the application-to-Spring routing boundary. */
class MembershipRoutingRegressionTest {
    @Test
    void privateProjectionWhileHoldingGameDoesNotWaitForRoomMonitor() throws Exception {
        Room room = new Room("room", "Room", "Alice", 6, 5, 10, 100, null);
        room.addPlayer("Alice");
        RoomService rooms = mock(RoomService.class);
        when(rooms.getRoom("room")).thenReturn(room);
        var projections = new GameStateService(rooms, mock(SimpMessagingTemplate.class));
        Game game = new Game("room", java.util.List.of(new Player("Alice", "alice-id", 100),
                new Player("Host", "host-id", 100)), 5, 10, mock(HandEvaluatorService.class));
        var roomHeld = new java.util.concurrent.CountDownLatch(1);
        var releaseRoom = new java.util.concurrent.CountDownLatch(1);
        Thread holder = new Thread(() -> {
            synchronized (room) {
                roomHeld.countDown();
                try { releaseRoom.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        });
        holder.setDaemon(true);
        holder.start();
        assertTrue(roomHeld.await(2, java.util.concurrent.TimeUnit.SECONDS));
        var publish = new java.util.concurrent.FutureTask<Void>(() -> {
            synchronized (game) { projections.broadcastGameState("room", game); }
            return null;
        });
        Thread publisher = new Thread(publish);
        publisher.setDaemon(true);
        try {
            publisher.start();
            publish.get(2, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            releaseRoom.countDown();
            holder.join(2000);
            publisher.join(2000);
        }
    }

    @Test
    void retiredSocketCannotReceivePrivateMessagesForReplacementMembership() {
        PlayerPrincipal retired = new PlayerPrincipal("Alice", "room", "retired");
        Room room = new Room("room", "Room", "Host", 6, 5, 10, 100, null);
        room.addPlayer("Alice");
        PlayerPrincipal replacement = new PlayerPrincipal("Alice", "room", room.getMembershipId("Alice"));
        RoomService rooms = mock(RoomService.class);
        when(rooms.getRoom("room")).thenReturn(room);
        SimpMessagingTemplate publisher = mock(SimpMessagingTemplate.class);
        GameStateService projections = new GameStateService(rooms, publisher);

        // The retired socket remains in Spring's user registry after REST departure.
        SimpUserRegistry registry = mock(SimpUserRegistry.class);
        SimpUser retiredUser = user("retired-socket");
        SimpUser replacementUser = user("replacement-socket", "replacement-second-tab");
        // Spring registers all sessions for a user name; duplicate names would
        // therefore group the retired and replacement sockets together.
        when(registry.getUser(anyString())).thenAnswer(invocation -> {
            String name = invocation.getArgument(0);
            if (name.equals(retired.getName()) && name.equals(replacement.getName())) {
                Set<SimpSession> combined = new java.util.HashSet<>(retiredUser.getSessions());
                combined.addAll(replacementUser.getSessions());
                SimpUser both = mock(SimpUser.class);
                when(both.getSessions()).thenReturn(combined);
                return both;
            }
            return name.equals(replacement.getName()) ? replacementUser : retiredUser;
        });
        Game game = new Game("room", java.util.List.of(new Player("Alice", "alice-id", 100),
                new Player("Host", "host-id", 100)), 5, 10, mock(HandEvaluatorService.class));
        projections.broadcastGameState("room", game);
        var recipient = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(publisher).convertAndSendToUser(recipient.capture(), eq("/queue/private"), any(PrivatePlayerState.class));

        var headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setDestination("/user/" + recipient.getValue() + "/queue/private");
        var resolved = new DefaultUserDestinationResolver(registry).resolveDestination(
                MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders()));
        assertNotNull(resolved);
        assertEquals(Set.of("/queue/private-userreplacement-socket", "/queue/private-userreplacement-second-tab"),
                resolved.getTargetDestinations());
        assertNotEquals(retired.getName(), replacement.getName(), "A replacement must have a distinct broker identity");
    }

    @Test
    void signedIdentityWithoutMembershipCannotSubscribeToPrivateQueue() {
        var interceptor = new WebSocketAuthInterceptor(mock(JwtService.class), mock(RoomService.class));
        var headers = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        headers.setUser(new PlayerPrincipal("Alice", "room"));
        headers.setDestination("/user/queue/private");
        headers.setLeaveMutable(true);
        assertThrows(AccessDeniedException.class, () -> interceptor.preSend(
                MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders()), null));
    }

    private static SimpUser user(String... sessionIds) {
        SimpUser user = mock(SimpUser.class);
        Set<SimpSession> sessions = new java.util.HashSet<>();
        for (String id : sessionIds) {
            SimpSession session = mock(SimpSession.class);
            when(session.getId()).thenReturn(id);
            sessions.add(session);
        }
        when(user.getSessions()).thenReturn(sessions);
        return user;
    }
}
