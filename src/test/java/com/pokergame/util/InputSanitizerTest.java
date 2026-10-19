package com.pokergame.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tests input sanitizer behavior. */
class InputSanitizerTest {

    /**
     * Protects the contract that the system should trim whitespace from input.
     */
    @Test
    @DisplayName("should trim whitespace from input")
    void sanitize_TrimsWhitespace() {
        assertThat(InputSanitizer.sanitize("  room name  ")).isEqualTo("room name");
        assertThat(InputSanitizer.sanitize("  player  ")).isEqualTo("player");
    }

    /**
     * Protects the contract that sanitize handles null and empty.
     * @param input input supplied to the fixture
     */
    @ParameterizedTest(name = "should return same value for null/empty/blank: {0}")
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "   "})
    void sanitize_HandlesNullAndEmpty(String input) {
        if (input == null) {
            assertThat(InputSanitizer.sanitize(input)).isNull();
        } else {
            assertThat(InputSanitizer.sanitize(input)).isEmpty();
        }
    }

    /**
     * Protects the contract that the system should preserve casing and special characters.
     */
    @Test
    @DisplayName("should preserve casing and special characters")
    void sanitize_PreservesCasingAndSpecialChars() {
        assertThat(InputSanitizer.sanitize("Poker Table #1!")).isEqualTo("Poker Table #1!");
        assertThat(InputSanitizer.sanitize("Player_One")).isEqualTo("Player_One");
    }

    /**
     * Protects the contract that the system should reject control characters.
     */
    @Test
    @DisplayName("should reject control characters")
    void sanitize_RejectsControlCharacters() {
        String inputWithControl = "Player" + (char) 7 + "Name"; // Bell character
        assertThatThrownBy(() -> InputSanitizer.sanitize(inputWithControl))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("control characters");
    }

    /**
     * Protects the contract that the system should reject control characters on raw input including leading/trailing.
     */
    @Test
    @DisplayName("should reject control characters on raw input including leading/trailing")
    void sanitize_RejectsControlCharactersOnRawInput() {
        assertThatThrownBy(() -> InputSanitizer.sanitize("\tplayer\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("control characters");

        assertThatThrownBy(() -> InputSanitizer.sanitize("\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("control characters");

        assertThatThrownBy(() -> InputSanitizer.sanitize("  " + (char) 7 + "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("control characters");
    }

    /**
     * Protects the contract that the system should reject scripts or other malicious-looking tags (basic check).
     */
    @Test
    @DisplayName("should reject scripts or other malicious-looking tags (basic check)")
    void sanitize_RejectsTags() {
        assertThatThrownBy(() -> InputSanitizer.sanitize("<script>alert(1)</script>"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTML tags");

        assertThatThrownBy(() -> InputSanitizer.sanitize("<b>Player</b>"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTML tags");

        assertThatThrownBy(() -> InputSanitizer.sanitize("  <script>  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTML tags");
    }

    /**
     * Protects the contract that the system should reject Unicode Cf format characters.
     */
    @Test
    @DisplayName("should reject Unicode Cf format characters")
    void sanitize_RejectsFormatCharacters() {
        // Zero-width space (U+200B)
        assertThatThrownBy(() -> InputSanitizer.sanitize("Player\u200BOne"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("format characters");

        // Zero-width joiner (U+200D)
        assertThatThrownBy(() -> InputSanitizer.sanitize("Player\u200DOne"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("format characters");

        // Byte Order Mark / BOM (U+FEFF)
        assertThatThrownBy(() -> InputSanitizer.sanitize("\uFEFFPlayer"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("format characters");

        // Bidi override (U+202E)
        assertThatThrownBy(() -> InputSanitizer.sanitize("Player\u202EOne"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("format characters");
    }

    /**
     * Protects the contract that raw input with leading/trailing Cf format characters is rejected before trimming.
     */
    @Test
    @DisplayName("should reject raw input with leading or trailing format characters before trimming")
    void sanitize_RejectsRawInputWithLeadingOrTrailingFormatCharacters() {
        assertThatThrownBy(() -> InputSanitizer.sanitize("  \u200B  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("format characters");

        assertThatThrownBy(() -> InputSanitizer.sanitize("\u200B  Player  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("format characters");

        assertThatThrownBy(() -> InputSanitizer.sanitize("  Player  \u200B"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("format characters");

        assertThatThrownBy(() -> InputSanitizer.sanitize("\u200D"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("format characters");
    }

    /**
     * Protects the contract that ordinary Unicode names without prohibited characters remain accepted.
     */
    @Test
    @DisplayName("should preserve ordinary Unicode names without prohibited characters")
    void sanitize_PreservesOrdinaryUnicodeNames() {
        assertThat(InputSanitizer.sanitize("René")).isEqualTo("René");
        assertThat(InputSanitizer.sanitize("Иван")).isEqualTo("Иван");
        assertThat(InputSanitizer.sanitize("田中")).isEqualTo("田中");
        assertThat(InputSanitizer.sanitize("  José María  ")).isEqualTo("José María");
    }
}
