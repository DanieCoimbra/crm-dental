package crm_data.scheduling.domain;

/**
 * Estados do agendamento (coluna {@code status}, {@code ck_appt_status}). A ordem das transições é regra do backend
 * ({@code AppointmentTransitions}); o banco só valida a lista e o que a recepção pode gravar.
 */
public enum AppointmentStatus {

	SCHEDULED,
	CONFIRMED,
	IN_PROGRESS,
	COMPLETED,
	NO_SHOW,
	CANCELED;

	/** Ocupa horário? É o mesmo predicado da exclusão {@code ex_appt_no_overlap}: só cancelado e falta liberam. */
	public boolean occupiesSlot() {
		return this != CANCELED && this != NO_SHOW;
	}
}
