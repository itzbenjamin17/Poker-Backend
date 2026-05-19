package com.pokergame.util;

import java.util.regex.Pattern;

/**
 * Utility for sanitizing and validating user-provided text inputs.
 */
public class InputSanitizer {

    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\p{Cntrl}]");
    private static final Pattern HTML_TAGS = Pattern.compile("<[^>]*>");

    /**
     * Sanitizes input by trimming whitespace and checking for illegal characters.
     *
     * @param input the raw input string
     * @return the trimmed string, or null/empty if the input was null/empty
     * @throws IllegalArgumentException if the input contains control characters or HTML tags
     */
    public static String sanitize(String input) {
        if (input == null) {
            return null;
        }

        String trimmed = input.trim();

        if (trimmed.isEmpty()) {
            return "";
        }

        if (CONTROL_CHARS.matcher(trimmed).find()) {
            throw new IllegalArgumentException("Input contains illegal control characters.");
        }

        if (HTML_TAGS.matcher(trimmed).find()) {
            throw new IllegalArgumentException("Input contains illegal HTML tags.");
        }

        return trimmed;
    }
}
