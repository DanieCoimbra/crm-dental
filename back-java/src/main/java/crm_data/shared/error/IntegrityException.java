package crm_data.shared.error;

/**
 * O {@code sha256} gravado não confere com o conteúdo lido: a linha foi alterada fora da aplicação. Vira 500
 * {@code integrity}. A mensagem carrega só identificadores (nunca texto do documento).
 */
public class IntegrityException extends RuntimeException {

	public IntegrityException(String message) {
		super(message);
	}
}
