package crm_data.shared.error;

/** Mensagens de conflito (409) compartilhadas, para o cliente receber o mesmo texto em qualquer recurso. */
public final class ConflictMessages {

	/** {@code version} defasada em edição com controle otimista. */
	public static final String STALE_VERSION = "O recurso foi alterado por outra operação. Recarregue e tente novamente.";

	private ConflictMessages() {
	}
}
