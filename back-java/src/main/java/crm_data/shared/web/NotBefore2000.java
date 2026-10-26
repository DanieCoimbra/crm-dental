package crm_data.shared.web;

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

/**
 * Data civil a partir de 2000-01-01, o mesmo piso das {@code CHECK} de data do banco (validade do orçamento, vencimento
 * de parcela, data de pagamento). Ausente é válido; obrigatoriedade é de {@code @NotNull}.
 */
@Documented
@Target({ ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT })
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = NotBefore2000.Validator.class)
public @interface NotBefore2000 {

	LocalDate EARLIEST = LocalDate.of(2000, 1, 1);

	String message() default "deve ser 2000-01-01 ou posterior";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};

	class Validator implements ConstraintValidator<NotBefore2000, LocalDate> {

		@Override
		public boolean isValid(LocalDate value, ConstraintValidatorContext context) {
			return value == null || !value.isBefore(EARLIEST);
		}
	}
}
