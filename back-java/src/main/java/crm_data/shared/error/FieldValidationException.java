package crm_data.shared.error;

import java.util.List;

import crm_data.shared.error.ProblemDetails.FieldError;

/**
 * Validação de regra que só o service consegue fazer (por exemplo, limites que dependem do preço do procedimento no
 * banco) com <strong>todos</strong> os campos inválidos de uma vez, no mesmo formato do Bean Validation: 400
 * {@code validation} com {@code errors[]}. O {@code field} é o caminho no corpo ({@code items[2].discountAmount}).
 * As mensagens nunca repetem o valor enviado nem dado pessoal.
 */
public class FieldValidationException extends RuntimeException {

	private final transient List<FieldError> errors;

	public FieldValidationException(List<FieldError> errors) {
		super("Um ou mais campos são inválidos.");
		this.errors = List.copyOf(errors);
	}

	public List<FieldError> errors() {
		return errors;
	}
}
