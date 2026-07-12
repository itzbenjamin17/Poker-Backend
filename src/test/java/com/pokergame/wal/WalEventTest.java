package com.pokergame.wal;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
@DisplayName("WalEvent")
class WalEventTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    @DisplayName("record fields are accessible")
    void recordFieldsAccessible() {
        JsonNode arg1 = jsonMapper.valueToTree("hello");
        JsonNode arg2 = jsonMapper.valueToTree(42);

        WalEvent event = new WalEvent(
                "RoomService",
                "createRoom",
                List.of("java.lang.String", "int"),
                List.of(arg1, arg2),
                null);

        assertThat(event.serviceName()).isEqualTo("RoomService");
        assertThat(event.methodName()).isEqualTo("createRoom");
        assertThat(event.parameterTypes()).containsExactly("java.lang.String", "int");
        assertThat(event.arguments()).hasSize(2);
    }

    @Test
    @DisplayName("serializes to and deserializes from JSON")
    void roundTripSerialization() throws Exception {
        JsonNode arg = jsonMapper.valueToTree("test-room-id");
        WalEvent original = new WalEvent(
                "GameLifecycleService",
                "createGameFromRoom",
                List.of("java.lang.String"),
                List.of(arg),
                "test-id");

        String json = jsonMapper.writeValueAsString(original);
        WalEvent deserialized = jsonMapper.readValue(json, WalEvent.class);

        assertThat(deserialized.serviceName()).isEqualTo(original.serviceName());
        assertThat(deserialized.methodName()).isEqualTo(original.methodName());
        assertThat(deserialized.parameterTypes()).isEqualTo(original.parameterTypes());
        assertThat(deserialized.arguments()).hasSize(1);
        assertThat(deserialized.arguments().getFirst().asString()).isEqualTo("test-room-id");
    }

    @Test
    @DisplayName("handles complex argument types")
    void handlesComplexArguments() {
        record SampleArg(String name, int value) {
        }
        JsonNode complexArg = jsonMapper.valueToTree(new SampleArg("player1", 100));

        WalEvent event = new WalEvent(
                "TestService",
                "testMethod",
                List.of("com.example.SampleArg"),
                List.of(complexArg),
                null);

        assertThat(event.arguments().getFirst().get("name").asString()).isEqualTo("player1");
        assertThat(event.arguments().getFirst().get("value").asInt()).isEqualTo(100);
    }

    @Test
    @DisplayName("handles empty argument list")
    void handlesEmptyArguments() {
        WalEvent event = new WalEvent(
                "TestService",
                "noArgMethod",
                List.of(),
                List.of(),
                null);

        assertThat(event.parameterTypes()).isEmpty();
        assertThat(event.arguments()).isEmpty();
    }
}
