package crm_data.scheduling.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import crm_data.patient.service.PatientBrief;
import crm_data.scheduling.domain.AppointmentStatus;
import crm_data.scheduling.service.AffectedAppointments;

/** Agendamento afetado por uma mudança de expediente ou de feriado, com o motivo (aviso informativo). */
public record AffectedAppointmentResponse(UUID id, PatientBrief patient, Instant startsAt, Instant endsAt,
		AppointmentStatus status, String reason) {

	static AffectedAppointmentResponse from(AffectedAppointments.Item item) {
		return new AffectedAppointmentResponse(item.id(), item.patient(), item.startsAt(), item.endsAt(), item.status(),
				item.reason().name());
	}

	static List<AffectedAppointmentResponse> from(AffectedAppointments affected) {
		return affected.items().stream().map(AffectedAppointmentResponse::from).toList();
	}
}
