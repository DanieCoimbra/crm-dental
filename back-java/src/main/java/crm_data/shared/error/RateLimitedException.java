package crm_data.shared.error;

/** Limite de requisições excedido: 429 com {@code Retry-After}. */
public class RateLimitedException extends BusinessException {

	private final long retryAfterSeconds;

	public RateLimitedException(String message, long retryAfterSeconds) {
		super(ProblemTypes.RATE_LIMITED, message);
		this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
	}

	public long retryAfterSeconds() {
		return retryAfterSeconds;
	}
}
