package com.pokergame.util;

import com.pokergame.exception.BadRequestException;

import java.nio.charset.StandardCharsets;

/** Length constraints shared by room request validation and direct service commands. */
public final class RoomPasswordPolicy {
    public static final String BYTE_LIMIT_MESSAGE = "Password must be at most 72 UTF-8 bytes.";
    private static final int MAX_CHARACTERS = 50;
    private static final int MAX_UTF8_BYTES = 72;

    /** Prevents instantiation of the password policy. */
    private RoomPasswordPolicy() { }

    /**
     * Checks BCrypt's input byte limit without altering a password's contents.
     *
     * @param password optional raw password, including intentional surrounding spaces
     * @return whether BCrypt can represent the complete password without truncation
     */
    public static boolean isWithinByteLimit(String password) {
        return password == null || password.getBytes(StandardCharsets.UTF_8).length <= MAX_UTF8_BYTES;
    }

    /**
     * Rejects unsupported password lengths before hashing or verifying a room credential.
     *
     * @param password optional raw password
     * @throws BadRequestException if the character or UTF-8 byte limit is exceeded
     */
    public static void validateLength(String password) {
        if (password != null && password.length() > MAX_CHARACTERS) {
            throw new BadRequestException("Password must be 50 characters or less");
        }
        if (!isWithinByteLimit(password)) {
            throw new BadRequestException(BYTE_LIMIT_MESSAGE);
        }
    }
}
