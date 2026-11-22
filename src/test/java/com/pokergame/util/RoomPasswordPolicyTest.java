package com.pokergame.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pokergame.exception.BadRequestException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

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
        assertThat(RoomPasswordPolicy.isWithinByteLimit("\uD83C\uDCA1".repeat(18) + "a")).isFalse();
        assertThat(RoomPasswordPolicy.isWithinByteLimit("\uD83C\uDCA1".repeat(19))).isFalse();
    }

    @Test
    void validateLengthAcceptsNullEmptyAndBoundaryInputs() {
        assertThatCode(() -> RoomPasswordPolicy.validateLength(null)).doesNotThrowAnyException();
        assertThatCode(() -> RoomPasswordPolicy.validateLength("")).doesNotThrowAnyException();
        assertThatCode(() -> RoomPasswordPolicy.validateLength("a".repeat(50))).doesNotThrowAnyException();
        assertThatCode(() -> RoomPasswordPolicy.validateLength("  padded  ")).doesNotThrowAnyException();
        assertThatCode(() -> RoomPasswordPolicy.validateLength("\uD83C\uDCA1".repeat(18)))
                .doesNotThrowAnyException();
    }

    @Test
    void validateLengthRejectsMoreThan50CharactersIncludingLeadingWhitespace() {
        assertThatThrownBy(() -> RoomPasswordPolicy.validateLength("a".repeat(51)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Password must be 50 characters or less");

        assertThatThrownBy(() -> RoomPasswordPolicy.validateLength(" " + "a".repeat(50)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Password must be 50 characters or less");
    }

    @Test
    void validateLengthRejectsMultibytePasswordsExceeding72Utf8BytesWithinCharLimit() {
        String exact73Utf8Bytes = "\uD83C\uDCA1".repeat(18) + "a";
        assertThatThrownBy(() -> RoomPasswordPolicy.validateLength(exact73Utf8Bytes))
                .isInstanceOf(BadRequestException.class)
                .hasMessage(RoomPasswordPolicy.BYTE_LIMIT_MESSAGE);

        String seventySixUtf8Bytes = "\uD83C\uDCA1".repeat(19);
        assertThatThrownBy(() -> RoomPasswordPolicy.validateLength(seventySixUtf8Bytes))
                .isInstanceOf(BadRequestException.class)
                .hasMessage(RoomPasswordPolicy.BYTE_LIMIT_MESSAGE);
    }
}
