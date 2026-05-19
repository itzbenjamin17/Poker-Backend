package com.pokergame.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InputSanitizerTest {

    @Test
    @DisplayName("should trim whitespace from input")
    void sanitize_TrimsWhitespace() {
        assertThat(InputSanitizer.sanitize("  room name  ")).isEqualTo("room name");
        assertThat(InputSanitizer.sanitize("\tplayer\n")).isEqualTo("player");
    }

    @ParameterizedTest(name = "should return same value for null/empty/blank: {0}")
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "\t", "\n"})
    void sanitize_HandlesNullAndEmpty(String input) {
        if (input == null) {
            assertThat(InputSanitizer.sanitize(input)).isNull();
        } else {
            assertThat(InputSanitizer.sanitize(input)).isEmpty();
        }
    }

    @Test
    @DisplayName("should preserve casing and special characters")
    void sanitize_PreservesCasingAndSpecialChars() {
        assertThat(InputSanitizer.sanitize("Poker Table #1!")).isEqualTo("Poker Table #1!");
        assertThat(InputSanitizer.sanitize("Player_One")).isEqualTo("Player_One");
    }

    @Test
    @DisplayName("should reject control characters")
    void sanitize_RejectsControlCharacters() {
        String inputWithControl = "Player" + (char) 7 + "Name"; // Bell character
        assertThatThrownBy(() -> InputSanitizer.sanitize(inputWithControl))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("control characters");
    }

    @Test
    @DisplayName("should reject scripts or other malicious-looking tags (basic check)")
    void sanitize_RejectsTags() {
        assertThatThrownBy(() -> InputSanitizer.sanitize("<script>alert(1)</script>"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTML tags");
    }
}
