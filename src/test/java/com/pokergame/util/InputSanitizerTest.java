package com.pokergame.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Unit tests for {@link InputSanitizer}. */
@Tag("unit")
class InputSanitizerTest {

    @Test
    @DisplayName("should trim whitespace from input")
    void sanitize_TrimsWhitespace() {
        assertThat(InputSanitizer.sanitize("  room name  ")).isEqualTo("room name");
        assertThat(InputSanitizer.sanitize("  player  ")).isEqualTo("player");
    }

    @Test
    @DisplayName("should return null for null input")
    void sanitize_ReturnsNullForNull() {
        assertThat(InputSanitizer.sanitize(null)).isNull();
    }

    @ParameterizedTest(name = "should return empty string for empty or blank input: ''{0}''")
    @ValueSource(strings = {"", "  ", "   "})
    void sanitize_ReturnsEmptyForEmptyOrBlank(String input) {
        assertThat(InputSanitizer.sanitize(input)).isEmpty();
    }

    @Test
    @DisplayName("should preserve casing, special characters, and benign lone angle brackets")
    void sanitize_PreservesCasingSpecialCharsAndLoneAngleBrackets() {
        assertThat(InputSanitizer.sanitize("Poker Table #1!")).isEqualTo("Poker Table #1!");
        assertThat(InputSanitizer.sanitize("Player_One")).isEqualTo("Player_One");
        assertThat(InputSanitizer.sanitize("2 < 3")).isEqualTo("2 < 3");
        assertThat(InputSanitizer.sanitize("A > B")).isEqualTo("A > B");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Player\u0000Name",
            "Player\u0007Name",
            "Player\rName",
            "Player\u007FName",
            "\tplayer\n",
            "\n",
            "  \u0007  "
    })
    @DisplayName("should reject ASCII control characters including NUL, CR, DEL, and leading/trailing controls")
    void sanitize_RejectsControlCharacters(String input) {
        assertThatThrownBy(() -> InputSanitizer.sanitize(input))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("control characters");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "<script>alert(1)</script>",
            "<b>Player</b>",
            "  <script>  ",
            "<>"
    })
    @DisplayName("should reject HTML and empty angle-bracket tags")
    void sanitize_RejectsTags(String input) {
        assertThatThrownBy(() -> InputSanitizer.sanitize(input))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTML tags");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Player\u200BOne",
            "Player\u200DOne",
            "\uFEFFPlayer",
            "Player\u202EOne",
            "  \u200B  ",
            "\u200B  Player  ",
            "  Player  \u200B",
            "\u200D"
    })
    @DisplayName("should reject Unicode Cf format characters anywhere in raw input")
    void sanitize_RejectsFormatCharacters(String input) {
        assertThatThrownBy(() -> InputSanitizer.sanitize(input))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("format characters");
    }

    @Test
    @DisplayName("should preserve ordinary Unicode names without prohibited characters")
    void sanitize_PreservesOrdinaryUnicodeNames() {
        assertThat(InputSanitizer.sanitize("René")).isEqualTo("René");
        assertThat(InputSanitizer.sanitize("Иван")).isEqualTo("Иван");
        assertThat(InputSanitizer.sanitize("田中")).isEqualTo("田中");
        assertThat(InputSanitizer.sanitize("  José María  ")).isEqualTo("José María");
    }
}
