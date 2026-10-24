package crm_data.scheduling.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.Duration;
import java.time.Instant;

import crm_data.scheduling.service.SchedulingRules;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

/**
 * Janela do agendamento coerente com as constraints do banco ({@code ck_appt_range}, {@code ck_appt_start_minute},
 * {@code ck_appt_duration}, {@code ck_appt_notes}), validada no servidor para devolver todos os campos de uma vez em
 * vez de depender do {@code 23514}: início sem segundos, fim posterior ao início, duração múltipla de 5 minutos entre
 * 5 minutos e 12 horas e observação de até 500 caracteres que não seja só espaços.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ValidAppointmentWindow.Validator.class)
public @interface ValidAppointmentWindow {

	String message() default "horário inválido";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};

	/** O que o validador lê do corpo; implementado pelos corpos de criação e de remarcação. */
	interface Window {

		Instant startsAt();

		Instant endsAt();

		String notes();
	}

	class Validator implements ConstraintValidator<ValidAppointmentWindow, Window> {

		@Override
		public boolean isValid(Window window, ConstraintValidatorContext context) {
			if (window == null) {
				return true;
			}
			context.disableDefaultConstraintViolation();
			boolean valid = true;
			Instant start = window.startsAt();
			Instant end = window.endsAt();
			if (start != null && (start.getEpochSecond() % 60 != 0 || start.getNano() != 0)) {
				valid = fail(context, "startsAt", "não deve ter segundos");
			}
			if (start != null && end != null) {
				if (!end.isAfter(start)) {
					valid &= fail(context, "endsAt", "deve ser posterior ao início");
				}
				else {
					Duration duration = Duration.between(start, end);
					boolean inRange = duration.compareTo(SchedulingRules.MIN_DURATION) >= 0
							&& duration.compareTo(SchedulingRules.MAX_DURATION) <= 0;
					boolean onStep = duration.toNanos() % SchedulingRules.DURATION_STEP.toNanos() == 0;
					if (!inRange || !onStep) {
						valid &= fail(context, "endsAt", "a duração deve ser múltipla de 5 minutos, de 5 minutos a 12 horas");
					}
				}
			}
			String notes = window.notes();
			if (notes != null) {
				if (notes.isBlank()) {
					valid &= fail(context, "notes", "não pode ser só espaços");
				}
				else if (notes.strip().length() > SchedulingRules.NOTES_MAX) {
					valid &= fail(context, "notes", "deve ter no máximo " + SchedulingRules.NOTES_MAX + " caracteres");
				}
			}
			return valid;
		}

		private static boolean fail(ConstraintValidatorContext context, String field, String message) {
			context.buildConstraintViolationWithTemplate(message).addPropertyNode(field).addConstraintViolation();
			return false;
		}
	}
}
