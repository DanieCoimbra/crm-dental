package crm_data.patient.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.LocalDate;

import crm_data.patient.service.PatientRules;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

/** Restrições de Bean Validation específicas de paciente (CPF, nascimento e coerência entre campos). */
public final class PatientConstraints {

	private PatientConstraints() {
	}

	/** Campos que as restrições de coerência precisam ler; implementada pelos DTOs de cadastro e edição. */
	public interface PatientFields {

		String insuranceName();

		String insuranceNumber();

		String acquaintanceName();

		String acquaintancePhone();
	}

	/** CPF com 11 dígitos, não todos iguais e dígitos verificadores corretos; {@code null} é válido (opcional). */
	@Documented
	@Constraint(validatedBy = ValidCpf.Validator.class)
	@Target({ ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT })
	@Retention(RetentionPolicy.RUNTIME)
	public @interface ValidCpf {

		String message() default "CPF inválido";

		Class<?>[] groups() default {};

		Class<? extends Payload>[] payload() default {};

		class Validator implements ConstraintValidator<ValidCpf, String> {

			@Override
			public boolean isValid(String value, ConstraintValidatorContext context) {
				return value == null || PatientRules.isValidCpf(value);
			}
		}
	}

	/** Data entre 1900-01-01 e hoje (fuso {@code America/Sao_Paulo}); {@code null} é tratado por {@code @NotNull}. */
	@Documented
	@Constraint(validatedBy = ValidBirthDate.Validator.class)
	@Target({ ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT })
	@Retention(RetentionPolicy.RUNTIME)
	public @interface ValidBirthDate {

		String message() default "deve estar entre 1900-01-01 e hoje";

		Class<?>[] groups() default {};

		Class<? extends Payload>[] payload() default {};

		class Validator implements ConstraintValidator<ValidBirthDate, LocalDate> {

			@Override
			public boolean isValid(LocalDate value, ConstraintValidatorContext context) {
				return value == null || PatientRules.isValidBirthDate(value, PatientRules.today());
			}
		}
	}

	/**
	 * Coerência entre campos: carteirinha exige o nome do convênio; nome e telefone do conhecido, ambos ou nenhum.
	 * Cada violação é reportada no campo que o cliente deve corrigir.
	 */
	@Documented
	@Constraint(validatedBy = ConsistentPatient.Validator.class)
	@Target(ElementType.TYPE)
	@Retention(RetentionPolicy.RUNTIME)
	public @interface ConsistentPatient {

		String message() default "campos inconsistentes";

		Class<?>[] groups() default {};

		Class<? extends Payload>[] payload() default {};

		class Validator implements ConstraintValidator<ConsistentPatient, PatientFields> {

			@Override
			public boolean isValid(PatientFields value, ConstraintValidatorContext context) {
				if (value == null) {
					return true;
				}
				boolean valid = true;
				context.disableDefaultConstraintViolation();
				if (value.insuranceNumber() != null && value.insuranceName() == null) {
					report(context, "insuranceNumber", "exige o nome do convênio");
					valid = false;
				}
				if ((value.acquaintanceName() == null) != (value.acquaintancePhone() == null)) {
					String missing = value.acquaintanceName() == null ? "acquaintanceName" : "acquaintancePhone";
					report(context, missing, "nome e telefone do conhecido devem ser informados juntos");
					valid = false;
				}
				return valid;
			}

			private static void report(ConstraintValidatorContext context, String field, String message) {
				context.buildConstraintViolationWithTemplate(message).addPropertyNode(field).addConstraintViolation();
			}
		}
	}
}
