package crm_data.shared.crypto;

/**
 * Falha de criptografia de campo. Nunca carrega texto claro, chave nem valor cifrado: a mensagem é fixa por
 * {@link Reason} (a versão da chave pode constar, pois não é segredo). Quando sobe por um endpoint, vira 500
 * {@code internal} pelo handler global (F01).
 */
public class CryptoException extends RuntimeException {

	public enum Reason {
		/** Chaveiro ausente ou inválido (tamanho, base64, versão ativa). */
		KEY_INVALID,
		/** O valor cifrado usa uma versão de chave que o chaveiro não tem. */
		VERSION_UNKNOWN,
		/** A tag de autenticação não confere: valor adulterado ou chave errada. */
		TAMPERED,
		/** O valor armazenado não tem o formato esperado (base64 inválido ou curto demais). */
		MALFORMED
	}

	private final Reason reason;

	public CryptoException(Reason reason, String message) {
		super(message);
		this.reason = reason;
	}

	public Reason reason() {
		return reason;
	}
}
