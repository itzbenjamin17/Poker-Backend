package com.pokergame.testutil;

import static com.pokergame.testutil.TestRoomLocks.runWithRoomLock;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import com.pokergame.persistence.RoomAggregates;
import com.pokergame.service.RoomService;
import com.pokergame.util.RoomCoordinator;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.awaitility.Awaitility;
import org.jspecify.annotations.NonNull;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.converter.AbstractMessageConverter;
import org.springframework.messaging.converter.CompositeMessageConverter;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.messaging.simp.user.UserDestinationResolver;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * Shared STOMP client and broker-subscription test harness with best-effort teardown cleanup.
 */
public final class TestStompClientSupport {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);
    private static final String ALLOWED_ORIGIN = "http://localhost:5173";

    private final int port;
    private final SimpUserRegistry userRegistry;
    private final UserDestinationResolver userDestinationResolver;
    private final SimpleBrokerMessageHandler broker;

    private final List<StompSession> trackedSessions = new CopyOnWriteArrayList<>();
    private final List<WebSocketStompClient> trackedClients = new CopyOnWriteArrayList<>();

    /**
     * Creates a STOMP client support helper bound to the running server port and broker registries.
     *
     * @param port                    local server port
     * @param userRegistry            Spring STOMP user registry
     * @param userDestinationResolver user destination resolver
     * @param broker                  in-memory simple broker handler
     */
    public TestStompClientSupport(
            int port,
            SimpUserRegistry userRegistry,
            UserDestinationResolver userDestinationResolver,
            SimpleBrokerMessageHandler broker) {
        this.port = port;
        this.userRegistry = requireNonNull(userRegistry, "userRegistry must not be null");
        this.userDestinationResolver = requireNonNull(userDestinationResolver, "userDestinationResolver must not be null");
        this.broker = requireNonNull(broker, "broker must not be null");
    }

    /**
     * Creates a tracked {@link WebSocketStompClient} configured with string and Jackson JSON converters.
     *
     * @return tracked STOMP client
     */
    public WebSocketStompClient createJsonClient() {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new CompositeMessageConverter(
                List.of(new StringMessageConverter(), new JacksonJsonMessageConverter())));
        trackedClients.add(client);
        return client;
    }

    /**
     * Creates a tracked {@link WebSocketStompClient} that sends raw byte/string frames and decodes JSON replies.
     *
     * @return tracked raw-bytes STOMP client
     */
    public WebSocketStompClient createRawBytesClient() {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new RawBytesMessageConverter());
        trackedClients.add(client);
        return client;
    }

    /**
     * Connects a JSON STOMP session using the default allowed origin.
     *
     * @param token JWT bearer token
     * @return connected STOMP session
     * @throws Exception if connection fails or times out
     */
    public StompSession connectJsonSession(String token) throws Exception {
        return connectSession(createJsonClient(), token);
    }

    /**
     * Connects a raw-bytes STOMP session using the default allowed origin.
     *
     * @param token JWT bearer token
     * @return connected STOMP session
     * @throws Exception if connection fails or times out
     */
    public StompSession connectRawBytesSession(String token) throws Exception {
        return connectSession(createRawBytesClient(), token);
    }

    /**
     * Connects a STOMP session on {@code client} using the default allowed origin.
     *
     * @param client STOMP client to use
     * @param token  JWT bearer token
     * @return connected STOMP session
     * @throws Exception if connection fails or times out
     */
    public StompSession connectSession(WebSocketStompClient client, String token) throws Exception {
        return connectSessionWithOrigin(client, token, ALLOWED_ORIGIN)
                .get(DEFAULT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    }

    /**
     * Initiates an asynchronous STOMP connection with a custom {@code Origin} header.
     *
     * @param client STOMP client to use
     * @param token  JWT bearer token
     * @param origin HTTP {@code Origin} header value
     * @return future completing with the connected STOMP session
     */
    public CompletableFuture<StompSession> connectSessionWithOrigin(
            WebSocketStompClient client, String token, String origin) {
        WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();
        handshakeHeaders.add("Origin", origin);
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + token);

        CompletableFuture<StompSession> future = client.connectAsync(
                "ws://localhost:" + port + "/ws",
                handshakeHeaders,
                connectHeaders,
                new StompSessionHandlerAdapter() {});
        future.thenAccept(trackedSessions::add);
        return future;
    }

    /**
     * Connects a STOMP session that records protocol or transport errors into {@code errorReceived}.
     *
     * @param client        STOMP client to use
     * @param token         JWT bearer token
     * @param errorReceived flag set when an exception or transport error arrives
     * @return connected STOMP session
     * @throws Exception if connection fails or times out
     */
    public StompSession connectSessionWithErrorHandler(
            WebSocketStompClient client, String token, AtomicBoolean errorReceived) throws Exception {
        WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();
        handshakeHeaders.add("Origin", ALLOWED_ORIGIN);
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + token);

        StompSession session = client.connectAsync(
                "ws://localhost:" + port + "/ws",
                handshakeHeaders,
                connectHeaders,
                new StompSessionHandlerAdapter() {
                    @Override
                    public void handleException(
                            @NonNull StompSession session,
                            StompCommand command,
                            @NonNull StompHeaders headers,
                            byte @NonNull [] payload,
                            @NonNull Throwable exception) {
                        errorReceived.set(true);
                    }

                    @Override
                    public void handleTransportError(@NonNull StompSession session, @NonNull Throwable exception) {
                        errorReceived.set(true);
                    }
                }).get(DEFAULT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        trackedSessions.add(session);
        return session;
    }

    /**
     * Subscribes {@code session} to {@code destination} and hands every decoded frame to {@code onFrame}.
     * Frames whose payload cannot be decoded as {@code payloadType} are ignored.
     *
     * @param session     connected STOMP session
     * @param destination destination to subscribe to (for example {@code /user/queue/private})
     * @param payloadType type each frame payload is decoded into
     * @param onFrame     callback invoked on the client's receive thread for each decoded frame
     * @param <T>         payload type
     */
    public <T> void subscribe(StompSession session, String destination, Class<T> payloadType, Consumer<T> onFrame) {
        session.subscribe(destination, new StompFrameHandler() {
            @Override
            public @NonNull Type getPayloadType(@NonNull StompHeaders headers) {
                return payloadType;
            }

            @Override
            public void handleFrame(@NonNull StompHeaders headers, Object payload) {
                if (payloadType.isInstance(payload)) {
                    onFrame.accept(payloadType.cast(payload));
                }
            }
        });
    }

    /**
     * Waits until the simple broker has registered at least one subscription for {@code destination}.
     *
     * @param destination resolved broker destination (for example {@code /game/{roomId}})
     */
    public void awaitBrokerSubscription(String destination) {
        Awaitility.await().atMost(DEFAULT_TIMEOUT).until(() -> hasBrokerSubscription(destination));
    }

    /**
     * Reports whether the simple broker currently has at least one subscription for {@code destination}.
     *
     * @param destination resolved broker destination (for example {@code /game/{roomId}})
     * @return {@code true} when a client subscription is registered for the destination
     */
    public boolean hasBrokerSubscription(String destination) {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setDestination(destination);
        var probe = MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
        return !broker.getSubscriptionRegistry().findSubscriptions(probe).isEmpty();
    }

    /**
     * Waits until the authenticated user {@code principalName} has an active broker subscription for
     * {@code userDestination} (such as {@code /user/queue/private}) and returns the broker session ID.
     *
     * @param principalName   authenticated principal name ({@code PlayerPrincipal.getName()})
     * @param userDestination user-prefixed destination subscribed by the client
     * @return resolved broker session identifier
     */
    public String awaitUserBrokerSubscription(String principalName, String userDestination) {
        SimpUser user = Awaitility.await().atMost(DEFAULT_TIMEOUT).until(
                () -> userRegistry.getUser(principalName),
                registered -> registered != null && registered.hasSessions());
        assertThat(user.getSessions()).hasSize(1);
        String sessionId = user.getSessions().iterator().next().getId();

        var headers = SimpMessageHeaderAccessor.create(SimpMessageType.SUBSCRIBE);
        headers.setSessionId(sessionId);
        headers.setDestination(userDestination);
        var subscription = MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
        var resolved = userDestinationResolver.resolveDestination(subscription);
        assertThat(resolved).isNotNull();
        assertThat(resolved.getTargetDestinations()).hasSize(1);
        awaitBrokerSubscription(resolved.getTargetDestinations().iterator().next());
        return sessionId;
    }

    /**
     * Disconnects all tracked STOMP sessions, stops all tracked clients, and destroys all tracked
     * rooms with per-room fault isolation.
     *
     * @param roomCoordinator room coordinator for lock-protected room teardown
     * @param roomService     room service to destroy rooms
     * @param roomAggregates  aggregate store to remove room records
     * @param trackedRoomIds  collection of room IDs to clean up and clear
     */
    public void closeAllAndDestroyRooms(
            RoomCoordinator roomCoordinator,
            RoomService roomService,
            RoomAggregates roomAggregates,
            Collection<String> trackedRoomIds) {
        for (StompSession session : trackedSessions) {
            if (session != null && session.isConnected()) {
                try {
                    session.disconnect();
                } catch (RuntimeException ignored) {
                    // Best-effort disconnect on teardown.
                }
            }
        }
        trackedSessions.clear();
        for (WebSocketStompClient client : trackedClients) {
            if (client != null) {
                try {
                    client.stop();
                } catch (RuntimeException ignored) {
                    // Best-effort stop on teardown.
                }
            }
        }
        trackedClients.clear();
        for (String roomId : List.copyOf(trackedRoomIds)) {
            try {
                runWithRoomLock(roomCoordinator, roomId, () -> {
                    roomService.destroyRoom(roomId);
                    roomAggregates.remove(roomId);
                });
            } catch (RuntimeException ignored) {
                // Best-effort per-room cleanup so one failure does not leak subsequent rooms.
            }
        }
        trackedRoomIds.clear();
    }

    private static final class RawBytesMessageConverter extends AbstractMessageConverter {

        private final JacksonJsonMessageConverter json = new JacksonJsonMessageConverter();

        private RawBytesMessageConverter() {
            super(MimeTypeUtils.APPLICATION_JSON);
        }

        @Override
        protected boolean supports(@NonNull Class<?> clazz) {
            return true;
        }

        @Override
        protected Object convertFromInternal(
                @NonNull Message<?> message, @NonNull Class<?> targetClass, Object conversionHint) {
            return json.fromMessage(message, targetClass);
        }

        @Override
        protected Object convertToInternal(
                @NonNull Object payload, MessageHeaders headers, Object conversionHint) {
            return payload instanceof byte[] raw ? raw : String.valueOf(payload).getBytes(StandardCharsets.UTF_8);
        }
    }
}
