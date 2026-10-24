package crm_data.scheduling.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import crm_data.patient.service.PatientBrief;
import crm_data.scheduling.domain.AppointmentStatus;
import crm_data.scheduling.service.AppointmentSlotRules.Reason;

/**
 * Aviso de impacto de uma mudança de expediente ou de feriado: os agendamentos futuros em aberto que passam a violar a
 * regra. É informativo (nada é cancelado nem remarcado). {@code items} traz no máximo
 * {@link SchedulingRules#MAX_AFFECTED_ITEMS}; {@code count} é o total.
 */
public record AffectedAppointments(List<Item> items, int count, boolean truncated) {

	public static final AffectedAppointments NONE = new AffectedAppointments(List.of(), 0, false);

	/** Um agendamento afetado, com o paciente resumido. O {@code toString} traz só ids. */
	public record Item(UUID id, PatientBrief patient, Instant startsAt, Instant endsAt, AppointmentStatus status,
			Reason reason) {

		@Override
		public String toString() {
			return "Item[id=" + id + ", reason=" + reason + "]";
		}
	}
}
