package crm_data.shared.error;

/** Identifica qual constraint do banco causou uma exceção de integridade (pelo nome, sem expor o valor). */
public final class ConstraintViolations {

	/** Índice único global de e-mail em {@code core.users}. */
	public static final String USER_EMAIL = "ux_users_email";

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
