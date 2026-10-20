package crm_data.shared.error;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

import org.springframework.http.ProblemDetail;

import jakarta.servlet.http.HttpServletRequest;

/** Fábrica do {@link ProblemDetail} no formato do contrato (RFC 9457). */
public final class ProblemDetails {

	/** Item de {@code errors[]} em respostas de validação. */
	public record FieldError(String field, String message) {
	}

	private ProblemDetails() {
	}

	public static ProblemDetail of(ProblemTypes type, String detail, HttpServletRequest request) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(type.status(), detail);
		problem.setType(type.typeUri());
		problem.setTitle(type.title());
		problem.setInstance(instanceOf(request));
		return problem;
	}

	public static ProblemDetail validation(String detail, List<FieldError> errors, HttpServletRequest request) {
		ProblemDetail problem = of(ProblemTypes.VALIDATION, detail, request);
		problem.setProperty("errors", errors);
		return problem;
	}

	/** Caminho da requisição, com escape de caracteres inválidos para não falhar dentro do handler de erro. */
	public static URI instanceOf(HttpServletRequest request) {
		try {
			return new URI(null, null, request.getRequestURI(), null);
		}
		catch (URISyntaxException | RuntimeException ex) {
			return null;
		}
	}
}
