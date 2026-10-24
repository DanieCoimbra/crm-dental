package crm_data.scheduling.web;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import crm_data.scheduling.service.HolidayService.HolidayChange;
import crm_data.scheduling.service.HolidayService.HolidayView;

/** Respostas de {@code /holidays} e {@code /clinic-holidays}. */
public final class HolidayResponses {

	private HolidayResponses() {
	}

	/** Feriado; {@code id} e {@code version} só existem nos da clínica ({@code source = CLINIC}). */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record HolidayResponse(LocalDate date, String name, String source, UUID id, Long version) {

		static HolidayResponse from(HolidayView view) {
			return new HolidayResponse(view.date(), view.name(), view.source().name(), view.id(), view.version());
		}

		static List<HolidayResponse> from(List<HolidayView> views) {
			return views.stream().map(HolidayResponse::from).toList();
		}
	}

	/** Feriado da clínica gravado, com o aviso de agendamentos afetados (informativo). */
	public record ClinicHolidayChangeResponse(HolidayResponse holiday, List<AffectedAppointmentResponse> affectedAppointments,
			int affectedCount, boolean truncated) {

		static ClinicHolidayChangeResponse from(HolidayChange change) {
			return new ClinicHolidayChangeResponse(HolidayResponse.from(change.holiday()),
					AffectedAppointmentResponse.from(change.affected()), change.affected().count(),
					change.affected().truncated());
		}
	}
}
