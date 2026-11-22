package com.pokergame.dto.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.pokergame.util.RoomPasswordPolicy;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Unit tests for {@link JoinRoomRequest}.
 */
@Tag("unit")
@DisplayName("Join room request validation")
class JoinRoomRequestTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void initValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    @Test
    @DisplayName("Whitespace-only password normalizes to null while non-blank password is preserved")
    void blankPasswordNormalizesToNull() {
        JoinRoomRequest blank = new JoinRoomRequest("High Rollers", "Alice", "   \t ");
        assertThat(blank.password()).isNull();
        assertThat(blank.isPasswordWithinByteLimit()).isTrue();
        assertThat(validator.validate(blank)).isEmpty();

        JoinRoomRequest withPassword = new JoinRoomRequest("High Rollers", "Alice", "  secret  ");
        assertThat(withPassword.password()).isEqualTo("  secret  ");
        assertThat(validator.validate(withPassword)).isEmpty();
    }

    @Test
    @DisplayName("Accepts 72-byte UTF-8 password and rejects 76-byte multi-byte password exceeding BCrypt limit")
    void enforcesBcryptByteLimitOnPassword() {
        JoinRoomRequest exactLimit = new JoinRoomRequest("High Rollers", "Alice", "\uD83D\uDE00".repeat(18));
        assertThat(exactLimit.isPasswordWithinByteLimit()).isTrue();
        assertThat(validator.validate(exactLimit)).isEmpty();

        JoinRoomRequest overLimit = new JoinRoomRequest("High Rollers", "Alice", "\uD83D\uDE00".repeat(19));
        assertThat(overLimit.isPasswordWithinByteLimit()).isFalse();
        assertThat(validator.validate(overLimit))
                .extracting(v -> v.getPropertyPath().toString(), ConstraintViolation::getMessage)
                .containsExactly(tuple("passwordWithinByteLimit", RoomPasswordPolicy.BYTE_LIMIT_MESSAGE));
    }

    @Test
    @DisplayName("toString exposes room, player, and password flag without leaking raw password")
    void toStringMasksRawPassword() {
        String secret = "JoinSecret123!";
        JoinRoomRequest request = new JoinRoomRequest("High Rollers", "Alice", secret);

        assertThat(request.toString())
                .contains("roomName='High Rollers'", "playerName='Alice'", "hasPassword=true")
                .doesNotContain(secret);
    }

    static Stream<Arguments> invalidFieldCases() {
        return Stream.of(
                Arguments.of(new JoinRoomRequest("   ", "Alice", null),
                        "roomName", "Room name is required"),
                Arguments.of(new JoinRoomRequest("R".repeat(51), "Alice", null),
                        "roomName", "Room name must be 50 characters or less"),
                Arguments.of(new JoinRoomRequest("Room\u0007", "Alice", null),
                        "roomName", "Room name cannot contain control characters"),
                Arguments.of(new JoinRoomRequest("Room\u200B", "Alice", null),
                        "roomName", "Input contains illegal format characters."),
                Arguments.of(new JoinRoomRequest("<script>room</script>", "Alice", null),
                        "roomName", "Input contains illegal HTML tags."),
                Arguments.of(new JoinRoomRequest("High Rollers", "   ", null),
                        "playerName", "Player name is required"),
                Arguments.of(new JoinRoomRequest("High Rollers", "P".repeat(31), null),
                        "playerName", "Player name must be 30 characters or less"),
                Arguments.of(new JoinRoomRequest("High Rollers", "Alice\r", null),
                        "playerName", "Player name cannot contain control characters"),
                Arguments.of(new JoinRoomRequest("High Rollers", "Alice\u200B", null),
                        "playerName", "Input contains illegal format characters."),
                Arguments.of(new JoinRoomRequest("High Rollers", "<b>Alice</b>", null),
                        "playerName", "Input contains illegal HTML tags."),
                Arguments.of(new JoinRoomRequest("High Rollers", "Alice", "p".repeat(51)),
                        "password", "Password must be 50 characters or less"),
                Arguments.of(new JoinRoomRequest("High Rollers", "Alice", "pass\u0000"),
                        "password", "Password cannot contain control characters"));
    }

    @ParameterizedTest
    @MethodSource("invalidFieldCases")
    @DisplayName("Rejects invalid name and password constraints with exact property path and message")
    void rejectsInvalidFieldConstraints(JoinRoomRequest request, String expectedPath, String expectedMessage) {
        Set<ConstraintViolation<JoinRoomRequest>> violations = validator.validate(request);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString(), ConstraintViolation::getMessage)
                .contains(tuple(expectedPath, expectedMessage));
    }
}
