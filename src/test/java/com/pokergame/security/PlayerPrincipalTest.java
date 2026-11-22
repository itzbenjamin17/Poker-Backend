package com.pokergame.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pokergame.exception.UnauthorisedActionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link PlayerPrincipal}. */
@Tag("unit")
@DisplayName("PlayerPrincipal")
class PlayerPrincipalTest {

    private static final String ROOM_ID = "room-123";

    @Test
    @DisplayName("two-arg constructor sets membershipId to null and formats literal getName()")
    void constructorsAndGetNameFormat() {
        PlayerPrincipal twoArg = new PlayerPrincipal("Alice", "room-1");
        assertThat(twoArg.membershipId()).isNull();
        assertThat(twoArg.getName()).isEqualTo("Alice:room-1:null");

        PlayerPrincipal threeArg = new PlayerPrincipal("Alice", "room-1", "mem-1");
        assertThat(threeArg.membershipId()).isEqualTo("mem-1");
        assertThat(threeArg.getName()).isEqualTo("Alice:room-1:mem-1");
    }

    @Test
    @DisplayName("assertBoundTo - matching room does not throw")
    void assertBoundTo_matchingRoom_doesNotThrow() {
        PlayerPrincipal principal = new PlayerPrincipal("Player1", ROOM_ID);

        assertThatCode(() -> principal.assertBoundTo(ROOM_ID)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("assertBoundTo - mismatched room throws")
    void assertBoundTo_mismatchedRoom_throws() {
        PlayerPrincipal principal = new PlayerPrincipal("Player1", "other-room");

        assertThatThrownBy(() -> principal.assertBoundTo(ROOM_ID))
                .isInstanceOf(UnauthorisedActionException.class)
                .hasMessage("Token is not valid for this room.");
    }

    @Test
    @DisplayName("assertBoundTo - null bound room or null target room throws UnauthorisedActionException")
    void assertBoundTo_nullBoundOrTargetRoom_throwsUnauthorised() {
        PlayerPrincipal unbound = new PlayerPrincipal("Player1", null);
        PlayerPrincipal bound = new PlayerPrincipal("Player1", ROOM_ID);

        assertThatThrownBy(() -> unbound.assertBoundTo(ROOM_ID))
                .isInstanceOf(UnauthorisedActionException.class)
                .hasMessage("Token is not valid for this room.");

        assertThatThrownBy(() -> unbound.assertBoundTo(null))
                .isInstanceOf(UnauthorisedActionException.class)
                .hasMessage("Token is not valid for this room.");

        assertThatThrownBy(() -> bound.assertBoundTo(null))
                .isInstanceOf(UnauthorisedActionException.class)
                .hasMessage("Token is not valid for this room.");
    }

    @Test
    @DisplayName("assertBoundTo - custom message overload uses supplied wording on mismatch or null")
    void assertBoundTo_customMessage_usesSuppliedWording() {
        PlayerPrincipal mismatched = new PlayerPrincipal("Player1", "other-room");
        PlayerPrincipal unbound = new PlayerPrincipal("Player1", null);

        assertThatThrownBy(() -> mismatched.assertBoundTo(ROOM_ID, "Token is not valid for this game."))
                .isInstanceOf(UnauthorisedActionException.class)
                .hasMessage("Token is not valid for this game.");

        assertThatThrownBy(() -> mismatched.assertBoundTo(null, "Token is not valid for this game."))
                .isInstanceOf(UnauthorisedActionException.class)
                .hasMessage("Token is not valid for this game.");

        assertThatThrownBy(() -> unbound.assertBoundTo(ROOM_ID, "Token is not valid for this game."))
                .isInstanceOf(UnauthorisedActionException.class)
                .hasMessage("Token is not valid for this game.");

        assertThatThrownBy(() -> unbound.assertBoundTo(null, "Token is not valid for this game."))
                .isInstanceOf(UnauthorisedActionException.class)
                .hasMessage("Token is not valid for this game.");
    }

    @Test
    @DisplayName("assertBoundTo - custom message overload does not throw on match")
    void assertBoundTo_customMessage_matchingRoom_doesNotThrow() {
        PlayerPrincipal principal = new PlayerPrincipal("Player1", ROOM_ID);

        assertThatCode(() -> principal.assertBoundTo(ROOM_ID, "Token is not valid for this game."))
                .doesNotThrowAnyException();
    }
}
