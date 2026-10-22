package crm_data.shared.error;

/** Identifica qual constraint do banco causou uma exceção de integridade (pelo nome, sem expor o valor). */
public final class ConstraintViolations {

	/** Índice único global de e-mail em {@code core.users}. */
	public static final String USER_EMAIL = "ux_users_email";

	/** Índice único de CPF por clínica em {@code core.patients}. */
	public static final String PATIENT_CPF = "ux_patients_clinic_cpf";

	/** Índice único de um histórico médico por paciente em {@code core.medical_histories}. */
	public static final String HISTORY_PATIENT = "ux_histories_patient";

	private ConstraintViolations() {
	}

	/** Percorre a cadeia de causas procurando o nome da constraint na mensagem do driver. */
	public static boolean violates(Throwable error, String constraintName) {
		for (Throwable t = error; t != null; t = t.getCause()) {
			if (t.getMessage() != null && t.getMessage().contains(constraintName)) {
				return true;
			}
		}
		return false;
	}
}
