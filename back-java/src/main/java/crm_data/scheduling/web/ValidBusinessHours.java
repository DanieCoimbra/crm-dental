package crm_data.scheduling.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.Set;

import crm_data.scheduling.web.BusinessHoursRequests.DayRequest;
import crm_data.scheduling.web.BusinessHoursRequests.UpdateBusinessHoursRequest;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

/**
 * Coerência do expediente enviado ({@code ck_bh_open} e {@code ck_bh_lunch} do banco), validada no servidor para
 * devolver todos os campos inválidos de uma vez, no caminho {@code days[i].campo}. O banco é a segunda barreira
 * ({@code 23514}).
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ValidBusinessHours.Validator.class)
public @interface ValidBusinessHours {

	String message() default "horário comercial inválido";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};

	class Validator implements ConstraintValidator<ValidBusinessHours, UpdateBusinessHoursRequest> {

		/** O {@code LocalTime} não representa 24:00; o expediente fecha no máximo às 23:59. */
		private static final LocalTime LAST_MINUTE = LocalTime.of(23, 59);

		@Override
		public boolean isValid(UpdateBusinessHoursRequest request, ConstraintValidatorContext context) {
			if (request == null || request.days() == null) {
				return true;
			}
			context.disableDefaultConstraintViolation();
			boolean valid = true;
			Set<Integer> seen = new HashSet<>();
			for (int i = 0; i < request.days().size(); i++) {
				DayRequest day = request.days().get(i);
				if (day == null) {
					continue;
				}
				Integer weekday = day.weekday();
				if (weekday != null && weekday >= 1 && weekday <= 7 && !seen.add(weekday)) {
					valid &= fail(context, i, "weekday", "dia repetido na requisição");
				}
				if (day.isOpen() == null) {
					continue;
				}
				valid &= day.isOpen() ? open(context, i, day) : closed(context, i, day);
			}
			return valid;
		}

		private static boolean closed(ConstraintValidatorContext context, int i, DayRequest day) {
			boolean valid = true;
			String message = "não aceita horário com o dia fechado";
			if (day.opensAt() != null) {
				valid = fail(context, i, "opensAt", message);
			}
			if (day.closesAt() != null) {
				valid &= fail(context, i, "closesAt", message);
			}
			if (day.lunchStartsAt() != null) {
				valid &= fail(context, i, "lunchStartsAt", message);
			}
			if (day.lunchEndsAt() != null) {
				valid &= fail(context, i, "lunchEndsAt", message);
			}
			return valid;
		}

		private static boolean open(ConstraintValidatorContext context, int i, DayRequest day) {
			boolean valid = true;
			valid &= required(context, i, "opensAt", day.opensAt());
			valid &= required(context, i, "closesAt", day.closesAt());
			valid &= minutes(context, i, "opensAt", day.opensAt());
			valid &= minutes(context, i, "closesAt", day.closesAt());
			valid &= minutes(context, i, "lunchStartsAt", day.lunchStartsAt());
			valid &= minutes(context, i, "lunchEndsAt", day.lunchEndsAt());
			if (day.closesAt() != null && day.closesAt().isAfter(LAST_MINUTE)) {
				valid &= fail(context, i, "closesAt", "o fechamento deve ser até 23:59");
			}
			if (day.opensAt() != null && day.closesAt() != null && !day.opensAt().isBefore(day.closesAt())) {
				valid &= fail(context, i, "closesAt", "deve ser posterior à abertura");
			}
			boolean hasStart = day.lunchStartsAt() != null;
			boolean hasEnd = day.lunchEndsAt() != null;
			if (hasStart != hasEnd) {
				valid &= fail(context, i, hasStart ? "lunchEndsAt" : "lunchStartsAt", "informe o início e o fim do almoço");
			}
			else if (hasStart) {
				if (!day.lunchStartsAt().isBefore(day.lunchEndsAt())) {
					valid &= fail(context, i, "lunchEndsAt", "deve ser posterior ao início do almoço");
				}
				if (day.opensAt() != null && !day.opensAt().isBefore(day.lunchStartsAt())) {
					valid &= fail(context, i, "lunchStartsAt", "o almoço deve começar depois da abertura");
				}
				if (day.closesAt() != null && !day.lunchEndsAt().isBefore(day.closesAt())) {
					valid &= fail(context, i, "lunchEndsAt", "o almoço deve terminar antes do fechamento");
				}
			}
			return valid;
		}

		private static boolean required(ConstraintValidatorContext context, int i, String field, LocalTime value) {
			return value != null || fail(context, i, field, "obrigatório com o dia aberto");
		}

		/** Só {@code HH:mm}: segundos ou frações não fazem parte do contrato. */
		private static boolean minutes(ConstraintValidatorContext context, int i, String field, LocalTime value) {
			return value == null || (value.getSecond() == 0 && value.getNano() == 0)
					|| fail(context, i, field, "use o formato HH:mm");
		}

		private static boolean fail(ConstraintValidatorContext context, int index, String field, String message) {
			context.buildConstraintViolationWithTemplate(message).addPropertyNode("days").addPropertyNode(field)
					.inIterable().atIndex(index).addConstraintViolation();
			return false;
		}
	}
}
