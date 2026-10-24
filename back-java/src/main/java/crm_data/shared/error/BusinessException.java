package crm_data.shared.error;

import java.util.Objects;

/**
 * Única forma de uma feature sinalizar erro de regra. O status vem do {@link ProblemTypes};
 * a mensagem é mostrada ao cliente, então não pode conter dado interno nem dado clínico.
 */
public class BusinessException extends RuntimeException {

	private final ProblemTypes type;
	private final String reason;

	public BusinessException(ProblemTypes type, String message) {
		this(type, message, null);
	}

	/**
	 * @param reason código estável da regra violada (por exemplo {@code LUNCH}), devolvido ao cliente como propriedade
	 *        {@code reason} do {@code ProblemDetail}; {@code null} se a regra não tem código
	 */
	public BusinessException(ProblemTypes type, String message, String reason) {
		super(message);
		this.type = Objects.requireNonNull(type, "type");
		this.reason = reason;
	}

	/** Código estável da regra violada, ou {@code null}. */
	public String reason() {
		return reason;
	}

	public ProblemTypes type() {
		return type;
	}
}
