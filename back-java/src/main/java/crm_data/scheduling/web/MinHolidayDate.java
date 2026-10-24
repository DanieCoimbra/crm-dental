package crm_data.scheduling.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.LocalDate;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

/** Data de feriado a partir de 2000-01-01 ({@code ck_clinic_holidays_date} do banco); ausente é tratado por {@code @NotNull}. */
@Documented
@Target({ ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT })
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = MinHolidayDate.Validator.class)
public @interface MinHolidayDate {

	LocalDate EARLIEST = LocalDate.of(2000, 1, 1);

	String message() default "deve ser 2000-01-01 ou posterior";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};

	class Validator implements ConstraintValidator<MinHolidayDate, LocalDate> {

		@Override
		public boolean isValid(LocalDate value, ConstraintValidatorContext context) {
			return value == null || !value.isBefore(EARLIEST);
		}
	}
}
