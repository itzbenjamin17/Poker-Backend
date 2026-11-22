package com.pokergame.dto.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

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
    @DisplayName("BET and RAISE accept minimum positive amount of 1 and reject null, zero, or negative amounts")
    void wageringActionsRequirePositiveAmount(PlayerAction action) {
        assertThat(validator.validate(new PlayerActionRequest(action, 1))).isEmpty();

        assertThatThrownBy(() -> new PlayerActionRequest(action, null))
                .isInstanceOf(BadRequestException.class)
                .extracting(
                        e -> ((BadRequestException) e).actionErrorCode(),
                        e -> ((BadRequestException) e).actionErrorMessage())
                .containsExactly("AMOUNT_REQUIRED", "Enter a positive amount");

        assertThatThrownBy(() -> new PlayerActionRequest(action, 0))
                .isInstanceOf(BadRequestException.class)
                .extracting(
                        e -> ((BadRequestException) e).actionErrorCode(),
                        e -> ((BadRequestException) e).actionErrorMessage())
                .containsExactly("AMOUNT_REQUIRED", "Amount must be positive; yours: 0");

        assertThatThrownBy(() -> new PlayerActionRequest(action, -10))
                .isInstanceOf(BadRequestException.class)
                .extracting(
                        e -> ((BadRequestException) e).actionErrorCode(),
                        e -> ((BadRequestException) e).actionErrorMessage())
                .containsExactly("NEGATIVE_AMOUNT", "Amount must be positive; yours: -10");
    }

    @ParameterizedTest
    @EnumSource(value = PlayerAction.class, names = {"FOLD", "CHECK", "CALL", "ALL_IN"})
    @DisplayName("Non-wagering actions require zero amount and reject null, positive, or negative amounts")
    void nonWageringActionsRejectNonZeroAmount(PlayerAction action) {
        assertThat(validator.validate(new PlayerActionRequest(action, 0))).isEmpty();

        assertThatThrownBy(() -> new PlayerActionRequest(action, null))
                .isInstanceOf(BadRequestException.class)
                .extracting(
                        e -> ((BadRequestException) e).actionErrorCode(),
                        e -> ((BadRequestException) e).actionErrorMessage())
                .containsExactly("UNEXPECTED_AMOUNT", "No amount needed; yours: null");

        assertThatThrownBy(() -> new PlayerActionRequest(action, 50))
                .isInstanceOf(BadRequestException.class)
                .extracting(
                        e -> ((BadRequestException) e).actionErrorCode(),
                        e -> ((BadRequestException) e).actionErrorMessage())
                .containsExactly("UNEXPECTED_AMOUNT", "No amount needed; yours: 50");

        assertThatThrownBy(() -> new PlayerActionRequest(action, -10))
                .isInstanceOf(BadRequestException.class)
                .extracting(
                        e -> ((BadRequestException) e).actionErrorCode(),
                        e -> ((BadRequestException) e).actionErrorMessage())
                .containsExactly("UNEXPECTED_AMOUNT", "No amount needed; yours: -10");
    }

    @Test
    @DisplayName("Jakarta Bean Validation rejects null action even when amount is null or zero")
    void beanValidationRejectsNullAction() {
        assertThat(validator.validate(new PlayerActionRequest(null, 0)))
                .extracting(v -> v.getPropertyPath().toString(), ConstraintViolation::getMessage)
                .containsExactly(tuple("action", "Action is required"));

        assertThat(validator.validate(new PlayerActionRequest(null, null)))
                .extracting(v -> v.getPropertyPath().toString(), ConstraintViolation::getMessage)
                .containsExactly(tuple("action", "Action is required"));
    }

    @Test
    @DisplayName("Jakarta Bean Validation enforces 10,000,000 maximum amount boundary")
    void beanValidationEnforcesMaxAmount() {
        assertThat(validator.validate(new PlayerActionRequest(PlayerAction.RAISE, 10_000_000))).isEmpty();

        assertThat(validator.validate(new PlayerActionRequest(PlayerAction.BET, 10_000_001)))
                .extracting(v -> v.getPropertyPath().toString(), ConstraintViolation::getMessage)
                .containsExactly(tuple("amount", "Amount cannot exceed 10,000,000"));
    }
}
