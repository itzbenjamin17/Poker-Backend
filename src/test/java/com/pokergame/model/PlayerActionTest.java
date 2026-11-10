package com.pokergame.model;

import com.pokergame.enums.PlayerAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for the PlayerAction enum.
 */
@Tag("unit")
@DisplayName("Player action enum")
class PlayerActionTest {

    /**
     * The action names are the serialized command vocabulary the client sends and the server
     * decodes, so the exact set is pinned as a wire contract.
     */
    @Test
    void serializedActionVocabularyIsStable() {
        Set<String> names = Stream.of(PlayerAction.values())
                .map(Enum::name)
                .collect(Collectors.toSet());
        assertEquals(Set.of("FOLD", "CHECK", "CALL", "BET", "RAISE", "ALL_IN"), names);
    }
}
