package crm_data.catalog.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

/**
 * Duração em minutos múltipla de 5 (igual à {@code CHECK} do banco). Ausente é tratado por {@code @NotNull}; a faixa de
 * 5 a 720 é de {@code @Min}/{@code @Max}.
 */
@Documented
@Target({ ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT })
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = DurationStep.Validator.class)
public @interface DurationStep {

	String message() default "deve ser múltiplo de 5";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};

	class Validator implements ConstraintValidator<DurationStep, Integer> {

		@Override
		public boolean isValid(Integer value, ConstraintValidatorContext context) {
			return value == null || value % 5 == 0;
		}
	}
}
