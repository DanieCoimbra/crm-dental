package crm_data.auth.service;

/** Mascaramento de dados pessoais para logs de eventos de segurança. */
final class Masking {

	private Masking() {
	}

	/** {@code joao@clinica.com} → {@code j***@clinica.com}; nunca o e-mail completo. */
	static String email(String email) {
		if (email == null || email.isBlank()) {
			return "?";
		}
		int at = email.indexOf('@');
		if (at <= 0) {
			return "***";
		}
		return email.charAt(0) + "***" + email.substring(at);
	}
}
