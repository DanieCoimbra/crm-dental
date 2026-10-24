package crm_data.scheduling.service;

import java.util.UUID;

/** Eventos internos, em memória, da agenda. Só identificadores e contagens: nunca nome, telefone ou observação. */
public final class SchedulingEvents {

	private SchedulingEvents() {
	}

	/** {@code BusinessHours.Changed}: o expediente foi gravado (não é publicado em {@code dryRun}). */
	public record BusinessHoursChanged(UUID clinicId, UUID userId, int affectedCount) {
	}

	/** {@code Appointment.Created}: agendamento criado (publicado depois do commit). Só ids. */
	public record AppointmentCreated(UUID clinicId, UUID appointmentId, UUID patientId, UUID userId) {
	}

	/** {@code Appointment.Rescheduled}: horário alterado. */
	public record AppointmentRescheduled(UUID clinicId, UUID appointmentId, UUID userId) {
	}

	/** {@code Appointment.OutsideHoursBooked}: o dentista encaixou onde a regra bloquearia a recepção (rastro do encaixe). */
	public record OutsideHoursBooked(UUID clinicId, UUID appointmentId, UUID userId) {
	}

	/** {@code Appointment.StatusChanged}: transição de status concluída (publicado depois do commit). Só ids e status. */
	public record StatusChanged(UUID clinicId, UUID appointmentId, String from, String to, UUID userId) {
	}

	/** {@code Appointment.AutoCanceled}: agendamentos futuros cancelados porque o paciente foi inativado (só contagem). */
	public record AutoCanceled(UUID clinicId, UUID patientId, int count) {
	}

	/** {@code ClinicHoliday.Created|Updated|Deleted}: feriado da clínica alterado. */
	public record ClinicHolidayChanged(UUID clinicId, UUID userId, UUID holidayId, String action, int affectedCount) {
	}
}
