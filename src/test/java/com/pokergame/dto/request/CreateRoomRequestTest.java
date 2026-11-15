package com.pokergame.dto.request;

import com.pokergame.util.RoomPasswordPolicy;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

        assertTrue(validator.validate(request).isEmpty());
        assertTrue(request.hasPassword());
        assertEquals("  secret  ", request.getPassword());
    }

    @Test
    @DisplayName("Whitespace-only password normalizes to null and does not mark room password-protected")
    void blankPasswordNormalizesToNull() {
        CreateRoomRequest blank = validWithPassword("   \t ");
        assertNull(blank.getPassword());
        assertFalse(blank.hasPassword());
        assertTrue(blank.isPasswordWithinByteLimit());
    }

    @Test
    @DisplayName("Rejects multi-byte password exceeding 72 UTF-8 bytes even under 50 chars")
    void rejectsMultiBytePasswordExceedingBcryptByteLimit() {
        // 19 four-byte emoji characters = 19 chars (<= 50) but 76 UTF-8 bytes (> 72)
        String nineteenEmoji = "\uD83D\uDE00".repeat(19);
        CreateRoomRequest request = validWithPassword(nineteenEmoji);

        Set<ConstraintViolation<CreateRoomRequest>> violations = validator.validate(request);
        assertFalse(request.isPasswordWithinByteLimit());
        assertTrue(violations.stream()
                .anyMatch(v -> RoomPasswordPolicy.BYTE_LIMIT_MESSAGE.equals(v.getMessage())));
    }

    @Test
    @DisplayName("Accepts multi-byte password of exactly 72 UTF-8 bytes")
    void acceptsPasswordOfExact72Utf8Bytes() {
        // 18 four-byte emoji = 36 UTF-16 chars (<= 50) and exactly 72 UTF-8 bytes
        String eighteenEmoji = "\uD83D\uDE00".repeat(18);
        CreateRoomRequest request = validWithPassword(eighteenEmoji);

        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    @DisplayName("Rejects blank names, HTML tags, format characters, and out-of-range numeric settings")
    void rejectsInvalidFieldConstraints() {
        CreateRoomRequest invalid = new CreateRoomRequest(
                "<script>room</script>",
                "Alice\u200B",
                11,
                0,
                1,
                19,
                "valid");

        Set<ConstraintViolation<CreateRoomRequest>> violations = validator.validate(invalid);
        Set<String> messages = violations.stream()
                .map(ConstraintViolation::getMessage)
                .collect(java.util.stream.Collectors.toSet());

        assertTrue(messages.contains("Input contains illegal HTML tags."));
        assertTrue(messages.contains("Input contains illegal format characters."));
        assertTrue(messages.contains("Maximum 10 players allowed"));
        assertTrue(messages.contains("Small blind must be at least 1"));
        assertTrue(messages.contains("Big blind must be at least 2"));
        assertTrue(messages.contains("Buy-in must be at least 20"));
    }

    @Test
    @DisplayName("toString exposes settings and password flag without leaking raw password")
    void toStringMasksRawPassword() {
        String secret = "SuperSecretValue!";
        CreateRoomRequest request = validWithPassword(secret);

        String rendered = request.toString();
        assertTrue(rendered.contains("hasPassword=true"));
        assertFalse(rendered.contains(secret));
    }

    @Test
    @DisplayName("Rejects big blind smaller than twice the small blind")
    void rejectsBigBlindSmallerThanTwiceSmallBlind() {
        CreateRoomRequest request = new CreateRoomRequest("High Rollers", "Alice", 6, 10, 15, 1000, null);

        Set<ConstraintViolation<CreateRoomRequest>> violations = validator.validate(request);

        assertEquals(1, violations.size());
        assertEquals("Big blind must be at least twice the small blind.", violations.iterator().next().getMessage());
    }

    @Test
    @DisplayName("Rejects buy-in smaller than big blind")
    void rejectsBuyInSmallerThanBigBlind() {
        CreateRoomRequest request = new CreateRoomRequest("High Rollers", "Alice", 6, 10, 50, 30, null);

        Set<ConstraintViolation<CreateRoomRequest>> violations = validator.validate(request);

        assertEquals(1, violations.size());
        assertEquals("Buy-in must be at least the big blind amount.", violations.iterator().next().getMessage());
    }

    @Test
    @DisplayName("Null blinds or buy-in only trigger NotNull without cross-field AssertTrue noise")
    void nullBlindsOrBuyInDoNotTriggerCrossFieldAssertTrue() {
        CreateRoomRequest request = new CreateRoomRequest("High Rollers", "Alice", 6, null, null, null, null);

        Set<ConstraintViolation<CreateRoomRequest>> violations = validator.validate(request);

        assertEquals(3, violations.size());
        assertFalse(violations.stream().anyMatch(v -> v.getMessage().contains("twice the small blind")));
        assertFalse(violations.stream().anyMatch(v -> v.getMessage().contains("at least the big blind")));
    }
}
