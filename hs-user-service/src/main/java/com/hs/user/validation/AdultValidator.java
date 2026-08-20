package com.hs.user.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.time.LocalDate;

public class AdultValidator implements ConstraintValidator<Adult, LocalDate> {

    private int minimumAge;

    @Override
    public void initialize(Adult constraintAnnotation) {
        minimumAge = constraintAnnotation.minimumAge();
    }

    @Override
    public boolean isValid(LocalDate dateOfBirth, ConstraintValidatorContext context) {
        if (dateOfBirth == null) {
            return true;
        }

        return !dateOfBirth.isAfter(LocalDate.now().minusYears(minimumAge));
    }
}
