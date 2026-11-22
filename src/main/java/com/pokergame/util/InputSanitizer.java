package com.pokergame.util;

import java.util.regex.Pattern;

/**
 * Utility for sanitizing and validating user-provided text inputs.
 */
public class InputSanitizer {

    private static final Pattern FORMAT_CHARS = Pattern.compile("\\p{Cf}");
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\p{Cntrl}]");
    private static final Pattern HTML_TAGS = Pattern.compile("<[^>]*>");

    /**
     * Sanitizes input by checking for illegal characters and trimming whitespace.
     *
     * @param input the raw input string
     * @return the trimmed string, or null/empty if the input was null/empty
     * @throws IllegalArgumentException if the input contains format characters, control characters, or HTML tags
     */
    public static String sanitize(String input) {
        if (input == null) {
            return null;
        }

        if (FORMAT_CHARS.matcher(input).find()) {
            throw new IllegalArgumentException("Input contains illegal format characters.");
        }

        if (CONTROL_CHARS.matcher(input).find()) {
            throw new IllegalArgumentException("Input contains illegal control characters.");
        }

        if (HTML_TAGS.matcher(input).find()) {
            throw new IllegalArgumentException("Input contains illegal HTML tags.");
        }

        String trimmed = input.trim();

        if (trimmed.isEmpty()) {
            return "";
        }

        return trimmed;
    }
}
