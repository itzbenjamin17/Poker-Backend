package com.pokergame.dto.request;

import com.pokergame.enums.PlayerAction;
import com.pokergame.exception.BadRequestException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link PlayerActionRequest}.
 */
@Tag("unit")
@DisplayName("Player action request validation")
class PlayerActionRequestTest {

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

    @ParameterizedTest
    @EnumSource(value = PlayerAction.class, names = {"BET", "RAISE"})
    @DisplayName("BET and RAISE require a strictly positive amount")
    void wageringActionsRequirePositiveAmount(PlayerAction action) {
        BadRequestException missing = assertThrows(
                BadRequestException.class,
                () -> new PlayerActionRequest(action, null));
        assertEquals("AMOUNT_REQUIRED", missing.actionErrorCode());

        BadRequestException zero = assertThrows(
                BadRequestException.class,
                () -> new PlayerActionRequest(action, 0));
        assertEquals("AMOUNT_REQUIRED", zero.actionErrorCode());

        BadRequestException negative = assertThrows(
                BadRequestException.class,
                () -> new PlayerActionRequest(action, -10));
        assertEquals("NEGATIVE_AMOUNT", negative.actionErrorCode());
    }

    @ParameterizedTest
    @EnumSource(value = PlayerAction.class, names = {"FOLD", "CHECK", "CALL", "ALL_IN"})
    @DisplayName("Non-wagering actions allow null or zero amount and reject non-zero amount")
    void nonWageringActionsRejectNonZeroAmount(PlayerAction action) {
        assertTrue(validator.validate(new PlayerActionRequest(action, null)).isEmpty());
        assertTrue(validator.validate(new PlayerActionRequest(action, 0)).isEmpty());

        BadRequestException unexpected = assertThrows(
                BadRequestException.class,
                () -> new PlayerActionRequest(action, 50));
        assertEquals("UNEXPECTED_AMOUNT", unexpected.actionErrorCode());
        assertTrue(unexpected.getMessage().contains(action.name()));
    }

    @Test
    @DisplayName("Jakarta Bean Validation enforces non-null action and maximum amount")
    void beanValidationEnforcesActionAndMaxAmount() {
        PlayerActionRequest missingAction = new PlayerActionRequest(null, 0);
        Set<ConstraintViolation<PlayerActionRequest>> missingViolations = validator.validate(missingAction);
        assertFalse(missingViolations.isEmpty());
        assertTrue(missingViolations.stream().anyMatch(v -> "Action is required".equals(v.getMessage())));

        PlayerActionRequest overMax = new PlayerActionRequest(PlayerAction.BET, 10_000_001);
        Set<ConstraintViolation<PlayerActionRequest>> maxViolations = validator.validate(overMax);
        assertFalse(maxViolations.isEmpty());
        assertTrue(maxViolations.stream().anyMatch(v -> "Amount cannot exceed 10,000,000".equals(v.getMessage())));

        PlayerActionRequest atMax = new PlayerActionRequest(PlayerAction.RAISE, 10_000_000);
        assertTrue(validator.validate(atMax).isEmpty());
    }
}
