package crm_data.scheduling.web;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

import crm_data.scheduling.service.BusinessHoursService.DayInput;
import crm_data.scheduling.service.DaySchedule;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Corpo de {@code PUT /business-hours}. Não aceita {@code id} nem {@code clinicId}: extras do JSON são ignorados. */
public final class BusinessHoursRequests {

	private BusinessHoursRequests() {
	}

	@ValidBusinessHours
	public record UpdateBusinessHoursRequest(
			@NotNull(message = "obrigatório") @Size(min = 1, max = 7, message = "deve ter de 1 a 7 dias") List<@Valid @NotNull(message = "obrigatório") DayRequest> days) {

		public List<DayInput> toInputs() {
			return days.stream().map(DayRequest::toInput).toList();
		}
	}

	/**
	 * @param weekday 1 = segunda … 7 = domingo
	 * @param version versão lida pelo cliente; ausente só ao criar o dia
	 */
	public record DayRequest(
			@NotNull(message = "obrigatório") @Min(value = 1, message = "deve estar entre 1 e 7") @Max(value = 7, message = "deve estar entre 1 e 7") Integer weekday,
			@NotNull(message = "obrigatório") Boolean isOpen, LocalTime opensAt, LocalTime closesAt, LocalTime lunchStartsAt,
			LocalTime lunchEndsAt, @Min(value = 0, message = "deve ser maior ou igual a 0") Long version) {

		DayInput toInput() {
			DaySchedule schedule = isOpen
					? new DaySchedule(true, opensAt, closesAt, lunchStartsAt, lunchEndsAt)
					: DaySchedule.CLOSED;
			return new DayInput(DayOfWeek.of(weekday), schedule, version);
		}
	}
}
