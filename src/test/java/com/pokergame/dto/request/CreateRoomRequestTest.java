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
 * Unit tests for {@link CreateRoomRequest}.
 */
@Tag("unit")
@DisplayName("Create room request validation")
class CreateRoomRequestTest {

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

    private static CreateRoomRequest validWithPassword(String password) {
        return new CreateRoomRequest("High Rollers", "Alice", 6, 10, 20, 1000, password);
    }

    @Test
    @DisplayName("Valid request passes validation without violations")
    void validRequestPassesValidation() {
        CreateRoomRequest request = validWithPassword("  secret  ");

        assertThat(validator.validate(request)).isEmpty();
        assertThat(request.hasPassword()).isTrue();
        assertThat(request.password()).isEqualTo("  secret  ");
    }

    @Test
    @DisplayName("Whitespace-only password normalizes to null and does not mark room password-protected")
    void blankPasswordNormalizesToNull() {
        CreateRoomRequest blank = validWithPassword("   \t ");
        assertThat(blank.password()).isNull();
        assertThat(blank.hasPassword()).isFalse();
        assertThat(blank.isPasswordWithinByteLimit()).isTrue();
    }

    @Test
    @DisplayName("Rejects multi-byte password exceeding 72 UTF-8 bytes even under 50 chars")
    void rejectsMultiBytePasswordExceedingBcryptByteLimit() {
        String nineteenEmoji = "\uD83D\uDE00".repeat(19);
        CreateRoomRequest request = validWithPassword(nineteenEmoji);

        Set<ConstraintViolation<CreateRoomRequest>> violations = validator.validate(request);
        assertThat(request.isPasswordWithinByteLimit()).isFalse();
        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString(), ConstraintViolation::getMessage)
                .containsExactly(tuple("passwordWithinByteLimit", RoomPasswordPolicy.BYTE_LIMIT_MESSAGE));
    }

    @Test
    @DisplayName("Accepts multi-byte password of exactly 72 UTF-8 bytes")
    void acceptsPasswordOfExact72Utf8Bytes() {
        String eighteenEmoji = "\uD83D\uDE00".repeat(18);
        CreateRoomRequest request = validWithPassword(eighteenEmoji);

        assertThat(validator.validate(request)).isEmpty();
    }

    static Stream<Arguments> invalidFieldCases() {
        return Stream.of(
                Arguments.of(new CreateRoomRequest("   ", "Alice", 6, 10, 20, 1000, null),
                        "roomName", "Room name is required"),
                Arguments.of(new CreateRoomRequest("R".repeat(51), "Alice", 6, 10, 20, 1000, null),
                        "roomName", "Room name must be 50 characters or less"),
                Arguments.of(new CreateRoomRequest("Room\u0007", "Alice", 6, 10, 20, 1000, null),
                        "roomName", "Room name cannot contain control characters"),
                Arguments.of(new CreateRoomRequest("Room\u200B", "Alice", 6, 10, 20, 1000, null),
                        "roomName", "Input contains illegal format characters."),
                Arguments.of(new CreateRoomRequest("<script>room</script>", "Alice", 6, 10, 20, 1000, null),
                        "roomName", "Input contains illegal HTML tags."),
                Arguments.of(new CreateRoomRequest("High Rollers", "   ", 6, 10, 20, 1000, null),
                        "playerName", "Player name is required"),
                Arguments.of(new CreateRoomRequest("High Rollers", "P".repeat(31), 6, 10, 20, 1000, null),
                        "playerName", "Player name must be 30 characters or less"),
                Arguments.of(new CreateRoomRequest("High Rollers", "Alice\n", 6, 10, 20, 1000, null),
                        "playerName", "Player name cannot contain control characters"),
                Arguments.of(new CreateRoomRequest("High Rollers", "Alice\u200B", 6, 10, 20, 1000, null),
                        "playerName", "Input contains illegal format characters."),
                Arguments.of(new CreateRoomRequest("High Rollers", "<b>Alice</b>", 6, 10, 20, 1000, null),
                        "playerName", "Input contains illegal HTML tags."),
                Arguments.of(new CreateRoomRequest("High Rollers", "Alice", 1, 10, 20, 1000, null),
                        "maxPlayers", "Minimum 2 players required"),
                Arguments.of(new CreateRoomRequest("High Rollers", "Alice", 11, 10, 20, 1000, null),
                        "maxPlayers", "Maximum 10 players allowed"),
                Arguments.of(new CreateRoomRequest("High Rollers", "Alice", 6, 0, 20, 1000, null),
                        "smallBlind", "Small blind must be at least 1"),
                Arguments.of(new CreateRoomRequest("High Rollers", "Alice", 6, 10_001, 20_000, 20_000, null),
                        "smallBlind", "Small blind cannot exceed 10,000"),
                Arguments.of(new CreateRoomRequest("High Rollers", "Alice", 6, 1, 1, 1000, null),
                        "bigBlind", "Big blind must be at least 2"),
                Arguments.of(new CreateRoomRequest("High Rollers", "Alice", 6, 10, 20_001, 20_001, null),
                        "bigBlind", "Big blind cannot exceed 20,000"),
                Arguments.of(new CreateRoomRequest("High Rollers", "Alice", 6, 1, 2, 19, null),
                        "buyIn", "Buy-in must be at least 20"),
                Arguments.of(new CreateRoomRequest("High Rollers", "Alice", 6, 10, 20, 1_000_001, null),
                        "buyIn", "Buy-in cannot exceed 1,000,000"),
                Arguments.of(new CreateRoomRequest("High Rollers", "Alice", 6, 10, 20, 1000, "p".repeat(51)),
                        "password", "Password must be 50 characters or less"),
                Arguments.of(new CreateRoomRequest("High Rollers", "Alice", 6, 10, 20, 1000, "pass\u0000"),
                        "password", "Password cannot contain control characters"));
    }

    @ParameterizedTest
    @MethodSource("invalidFieldCases")
    @DisplayName("Rejects invalid individual field constraints with exact property path and message")
    void rejectsInvalidFieldConstraints(CreateRoomRequest request, String expectedPath, String expectedMessage) {
        Set<ConstraintViolation<CreateRoomRequest>> violations = validator.validate(request);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString(), ConstraintViolation::getMessage)
                .contains(tuple(expectedPath, expectedMessage));
    }

    @Test
    @DisplayName("toString exposes settings and password flag without leaking raw password")
    void toStringMasksRawPassword() {
        String secret = "SuperSecretValue!";
        CreateRoomRequest request = validWithPassword(secret);

        String rendered = request.toString();
        assertThat(rendered)
                .contains("hasPassword=true")
                .doesNotContain(secret);
    }

    @Test
    @DisplayName("Rejects big blind smaller than twice the small blind")
    void rejectsBigBlindSmallerThanTwiceSmallBlind() {
        CreateRoomRequest request = new CreateRoomRequest("High Rollers", "Alice", 6, 10, 15, 1000, null);

        Set<ConstraintViolation<CreateRoomRequest>> violations = validator.validate(request);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString(), ConstraintViolation::getMessage)
                .containsExactly(tuple("bigBlindAtLeastTwiceSmallBlind",
                        "Big blind must be at least twice the small blind."));
    }

    @Test
    @DisplayName("Rejects buy-in smaller than big blind")
    void rejectsBuyInSmallerThanBigBlind() {
        CreateRoomRequest request = new CreateRoomRequest("High Rollers", "Alice", 6, 10, 50, 30, null);

        Set<ConstraintViolation<CreateRoomRequest>> violations = validator.validate(request);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString(), ConstraintViolation::getMessage)
                .containsExactly(tuple("buyInAtLeastBigBlind", "Buy-in must be at least the big blind amount."));
    }

    static Stream<Arguments> nullBlindAndBuyInCombinations() {
        return Stream.of(
                Arguments.of(null, 20, 1000, Set.of("smallBlind")),
                Arguments.of(10, null, 1000, Set.of("bigBlind")),
                Arguments.of(10, 20, null, Set.of("buyIn")),
                Arguments.of(null, null, null, Set.of("smallBlind", "bigBlind", "buyIn")));
    }

    @ParameterizedTest
    @MethodSource("nullBlindAndBuyInCombinations")
    @DisplayName("Null blinds or buy-in only trigger NotNull without cross-field AssertTrue noise")
    void nullBlindsOrBuyInDoNotTriggerCrossFieldAssertTrue(
            Integer smallBlind, Integer bigBlind, Integer buyIn, Set<String> expectedNullPaths) {
        CreateRoomRequest request = new CreateRoomRequest("High Rollers", "Alice", 6, smallBlind, bigBlind, buyIn, null);

        Set<ConstraintViolation<CreateRoomRequest>> violations = validator.validate(request);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .containsExactlyInAnyOrderElementsOf(expectedNullPaths)
                .doesNotContain("bigBlindAtLeastTwiceSmallBlind", "buyInAtLeastBigBlind");
    }
}
