package crm_data.scheduling.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import crm_data.patient.service.PatientBrief;
import crm_data.scheduling.domain.Appointment;
import crm_data.scheduling.domain.AppointmentStatus;
import crm_data.scheduling.service.AppointmentService.AppointmentView;

/**
 * Agendamento na API, com o paciente resumido. Nunca traz {@code clinicId} nem {@code procedureTypeId}. A
 * {@code notes} é texto claro e não leva dado clínico.
 */
public record AppointmentResponse(UUID id, PatientBrief patient, Instant startsAt, Instant endsAt, AppointmentStatus status,
		String notes, UUID createdBy, Instant createdAt, Instant updatedAt, Long version) {

	public static AppointmentResponse from(AppointmentView view) {
		Appointment a = view.appointment();
		return new AppointmentResponse(a.getId(), view.patient(), a.getStartsAt(), a.getEndsAt(), a.getStatus(), a.getNotes(),
				a.getCreatedBy(), a.getCreatedAt(), a.getUpdatedAt(), a.getVersion());
	}

	public static List<AppointmentResponse> from(List<AppointmentView> views) {
		return views.stream().map(AppointmentResponse::from).toList();
	}
}
