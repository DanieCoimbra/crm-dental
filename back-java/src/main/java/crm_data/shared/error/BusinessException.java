package crm_data.shared.error;

import java.util.Objects;

/**
 * Única forma de uma feature sinalizar erro de regra. O status vem do {@link ProblemTypes};
 * a mensagem é mostrada ao cliente, então não pode conter dado interno nem dado clínico.
 */
public class BusinessException extends RuntimeException {

	private final ProblemTypes type;

	public BusinessException(ProblemTypes type, String message) {
		super(message);
		this.type = Objects.requireNonNull(type, "type");
	}

	public ProblemTypes type() {
		return type;
	}
}
