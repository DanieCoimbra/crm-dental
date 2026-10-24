package crm_data.scheduling.web;

import java.time.LocalTime;
import java.util.List;

import crm_data.scheduling.service.BusinessHoursService.DayView;
import crm_data.scheduling.service.BusinessHoursService.UpdateResult;

/** Respostas de {@code /business-hours}: horários em {@code HH:mm} (locais da clínica). */
public final class BusinessHoursResponses {

	private BusinessHoursResponses() {
	}

	/** Um dia da semana; dia sem linha vem {@code configured:false, isOpen:false} e sem {@code version}. */
	public record BusinessDayResponse(int weekday, boolean configured, boolean isOpen, String opensAt, String closesAt,
			String lunchStartsAt, String lunchEndsAt, Long version) {

		static BusinessDayResponse from(DayView view) {
			return new BusinessDayResponse(view.day().getValue(), view.configured(), view.schedule().open(),
					hhmm(view.schedule().opensAt()), hhmm(view.schedule().closesAt()), hhmm(view.schedule().lunchStartsAt()),
					hhmm(view.schedule().lunchEndsAt()), view.version());
		}

		static List<BusinessDayResponse> from(List<DayView> views) {
			return views.stream().map(BusinessDayResponse::from).toList();
		}
	}

	/** Resultado do {@code PUT}: {@code saved=false} em {@code dryRun}. */
	public record BusinessHoursUpdateResponse(boolean saved, List<BusinessDayResponse> days,
			List<AffectedAppointmentResponse> affectedAppointments, int affectedCount, boolean truncated) {

		static BusinessHoursUpdateResponse from(UpdateResult result) {
			return new BusinessHoursUpdateResponse(result.saved(), BusinessDayResponse.from(result.days()),
					AffectedAppointmentResponse.from(result.affected()), result.affected().count(),
					result.affected().truncated());
		}
	}

	private static String hhmm(LocalTime time) {
		return time == null ? null : String.format("%02d:%02d", time.getHour(), time.getMinute());
	}
}
