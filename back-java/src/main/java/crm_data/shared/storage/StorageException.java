package crm_data.shared.storage;

/**
 * Falha de infraestrutura do Storage (rede, timeout, 4xx/5xx, configuração). Não verificada: o handler global a traduz
 * em 503. A mensagem é fixa e curta, sem URL, chave ou nome de arquivo.
 */
public class StorageException extends RuntimeException {

	public StorageException(String message) {
		super(message);
	}

	public StorageException(String message, Throwable cause) {
		super(message, cause);
	}
}
