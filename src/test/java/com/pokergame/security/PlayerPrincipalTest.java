package com.pokergame.security;

import com.pokergame.exception.UnauthorisedActionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tests the token-to-room binding assertion on the player principal. */
@Tag("unit")
@DisplayName("PlayerPrincipal binding assertion")
class PlayerPrincipalTest {

    private static final String ROOM_ID = "room-123";

    /** A principal bound to the requested room passes without throwing. */
    @Test
    @DisplayName("assertBoundTo - matching room does not throw")
    void assertBoundTo_matchingRoom_doesNotThrow() {
        PlayerPrincipal principal = new PlayerPrincipal("Player1", ROOM_ID);

        assertThatCode(() -> principal.assertBoundTo(ROOM_ID)).doesNotThrowAnyException();
    }

    /** A principal bound to a different room is rejected. */
    @Test
    @DisplayName("assertBoundTo - mismatched room throws")
    void assertBoundTo_mismatchedRoom_throws() {
        PlayerPrincipal principal = new PlayerPrincipal("Player1", "other-room");

        assertThatThrownBy(() -> principal.assertBoundTo(ROOM_ID))
                .isInstanceOf(UnauthorisedActionException.class)
                .hasMessage("Token is not valid for this room.");
    }

    /** A principal with a null bound room is rejected rather than throwing an NPE. */
    @Test
    @DisplayName("assertBoundTo - null bound room throws UnauthorisedActionException, not NPE")
    void assertBoundTo_nullBoundRoom_throwsUnauthorised() {
        PlayerPrincipal principal = new PlayerPrincipal("Player1", null);

        assertThatThrownBy(() -> principal.assertBoundTo(ROOM_ID))
                .isInstanceOf(UnauthorisedActionException.class)
                .hasMessage("Token is not valid for this room.");
    }

    /** The custom-message overload preserves transport-specific wording on mismatch. */
    @Test
    @DisplayName("assertBoundTo - custom message overload uses supplied wording")
    void assertBoundTo_customMessage_usesSuppliedWording() {
        PlayerPrincipal principal = new PlayerPrincipal("Player1", "other-room");

        assertThatThrownBy(() -> principal.assertBoundTo(ROOM_ID, "Token is not valid for this game."))
                .isInstanceOf(UnauthorisedActionException.class)
                .hasMessage("Token is not valid for this game.");
    }

    /** The custom-message overload is also null-safe for the bound room. */
    @Test
    @DisplayName("assertBoundTo - custom message overload is null-safe")
    void assertBoundTo_customMessage_nullBoundRoom_throwsUnauthorised() {
        PlayerPrincipal principal = new PlayerPrincipal("Player1", null);

        assertThatThrownBy(() -> principal.assertBoundTo(ROOM_ID, "Token is not valid for this game."))
                .isInstanceOf(UnauthorisedActionException.class)
                .hasMessage("Token is not valid for this game.");
    }

    /** The custom-message overload passes when the bound room matches. */
    @Test
    @DisplayName("assertBoundTo - custom message overload does not throw on match")
    void assertBoundTo_customMessage_matchingRoom_doesNotThrow() {
        PlayerPrincipal principal = new PlayerPrincipal("Player1", ROOM_ID);

        assertThatCode(() -> principal.assertBoundTo(ROOM_ID, "Token is not valid for this game."))
                .doesNotThrowAnyException();
    }
}
