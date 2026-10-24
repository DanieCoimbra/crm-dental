package crm_data.clinic.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.ZoneId;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

/**
 * Fuso IANA conhecido do Java (por exemplo {@code America/Sao_Paulo} ou {@code UTC}). Ausente é válido (mantém o fuso
 * atual). Rejeita deslocamentos como {@code +03:00}, que o Java aceita em {@code ZoneId.of} mas o banco não reconhece.
 * O trigger do banco ({@code 23514}) é a segunda barreira, pois os catálogos do Java e do Postgres podem divergir.
 */
@Documented
@Target({ ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT })
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ValidTimeZone.Validator.class)
public @interface ValidTimeZone {

	String message() default "fuso horário inválido";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};

	class Validator implements ConstraintValidator<ValidTimeZone, String> {

		@Override
		public boolean isValid(String value, ConstraintValidatorContext context) {
			return value == null || ZoneId.getAvailableZoneIds().contains(value);
		}
	}
}
