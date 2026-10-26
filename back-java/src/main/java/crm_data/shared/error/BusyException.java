package crm_data.shared.error;

/**
 * Serviço momentaneamente sem capacidade para a operação (por exemplo, muitos envios de PDF em andamento): 503
 * {@code unavailable} com {@code Retry-After}. Diferente do {@link RateLimitedException} (429), não é culpa de quem
 * chama: a carga é da instância.
 */
public class BusyException extends BusinessException {

	private final long retryAfterSeconds;

	public BusyException(String message, long retryAfterSeconds) {
		super(ProblemTypes.UNAVAILABLE, message);
		this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
	}

	public long retryAfterSeconds() {
		return retryAfterSeconds;
	}
}
