package com.pokergame.util;

import com.pokergame.exception.BadRequestException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for {@link RoomPasswordPolicy}. */
@Tag("unit")
class RoomPasswordPolicyTest {

    @Test
    void isWithinByteLimitAcceptsNullAndPasswordsUpTo72Utf8Bytes() {
        assertThat(RoomPasswordPolicy.isWithinByteLimit(null)).isTrue();
        assertThat(RoomPasswordPolicy.isWithinByteLimit("")).isTrue();
        assertThat(RoomPasswordPolicy.isWithinByteLimit("a".repeat(72))).isTrue();
        assertThat(RoomPasswordPolicy.isWithinByteLimit("\u00e9".repeat(36))).isTrue();
    }

    @Test
    void isWithinByteLimitRejectsPasswordsOver72Utf8Bytes() {
        assertThat(RoomPasswordPolicy.isWithinByteLimit("a".repeat(73))).isFalse();
        // 19 4-byte emoji characters = 76 UTF-8 bytes despite being under 50 chars
        assertThat(RoomPasswordPolicy.isWithinByteLimit("\uD83C\uDCA1".repeat(19))).isFalse();
    }

    @Test
    void validateLengthAcceptsNullEmptyAndBoundaryInputs() {
        assertThatCode(() -> RoomPasswordPolicy.validateLength(null)).doesNotThrowAnyException();
        assertThatCode(() -> RoomPasswordPolicy.validateLength("")).doesNotThrowAnyException();
        assertThatCode(() -> RoomPasswordPolicy.validateLength("a".repeat(50))).doesNotThrowAnyException();
        assertThatCode(() -> RoomPasswordPolicy.validateLength("  padded  ")).doesNotThrowAnyException();
        // 18 4-byte emoji characters = 36 UTF-16 chars and 72 UTF-8 bytes
        assertThatCode(() -> RoomPasswordPolicy.validateLength("\uD83C\uDCA1".repeat(18)))
                .doesNotThrowAnyException();
    }

    @Test
    void validateLengthRejectsMoreThan50Characters() {
        assertThatThrownBy(() -> RoomPasswordPolicy.validateLength("a".repeat(51)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Password must be 50 characters or less");
    }

    @Test
    void validateLengthRejectsMultibytePasswordsExceeding72Utf8BytesWithinCharLimit() {
        // 19 4-byte emoji characters = 38 UTF-16 chars (<= 50), 76 UTF-8 bytes (> 72)
        String tooManyUtf8Bytes = "\uD83C\uDCA1".repeat(19);
        assertThatThrownBy(() -> RoomPasswordPolicy.validateLength(tooManyUtf8Bytes))
                .isInstanceOf(BadRequestException.class)
                .hasMessage(RoomPasswordPolicy.BYTE_LIMIT_MESSAGE);
    }
}
