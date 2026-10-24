package crm_data.shared.error;

/** Mensagens de conflito (409) compartilhadas, para o cliente receber o mesmo texto em qualquer recurso. */
public final class ConflictMessages {

	/** {@code version} defasada em edição com controle otimista. */
	public static final String STALE_VERSION = "O recurso foi alterado por outra operação. Recarregue e tente novamente.";

	/** Conflito de horário na agenda ({@code ex_appt_no_overlap}); nunca revela o outro agendamento. */
	public static final String SLOT_TAKEN = "Horário já ocupado.";

	/** Agendamento alterado entre a leitura e a gravação (versão defasada ou 0 linhas pela RLS). */
	public static final String APPOINTMENT_CHANGED = "O agendamento foi alterado. Recarregue e tente novamente.";

	private ConflictMessages() {
	}
}
