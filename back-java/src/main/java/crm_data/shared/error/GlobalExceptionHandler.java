package crm_data.shared.error;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

/**
 * Converte toda exceção em {@code ProblemDetail} (RFC 9457). Nada de stack trace, SQL, nome de classe
 * ou mensagem de exceção interna chega ao cliente: o detalhe vai para o log, correlacionado por X-Request-Id.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	private static final String INVALID_FIELDS = "Um ou mais campos são inválidos.";

	// ---- Regras de negócio e infraestrutura ----------------------------------------------------

	@ExceptionHandler(BusinessException.class)
	ResponseEntity<Object> handleBusiness(BusinessException ex, HttpServletRequest request) {
		ProblemDetail problem = ProblemDetails.of(ex.type(), ex.getMessage(), request);
		HttpHeaders headers = new HttpHeaders();
		if (ex instanceof RateLimitedException limited) {
			headers.set(HttpHeaders.RETRY_AFTER, Long.toString(limited.retryAfterSeconds()));
		}
		return problem(ex.type().status(), headers, problem);
	}

	@ExceptionHandler(OptimisticLockingFailureException.class)
	ResponseEntity<Object> handleOptimisticLock(OptimisticLockingFailureException ex, HttpServletRequest request) {
		return respond(ProblemTypes.CONFLICT, "O recurso foi alterado por outra operação. Recarregue e tente novamente.",
				request);
	}

	@ExceptionHandler({ CannotCreateTransactionException.class, DataAccessResourceFailureException.class,
			QueryTimeoutException.class })
	ResponseEntity<Object> handleDatabaseUnavailable(Exception ex, HttpServletRequest request) {
		log.error("Banco de dados indisponível ou lento em {} {}", request.getMethod(), request.getRequestURI(), ex);
		return respond(ProblemTypes.UNAVAILABLE, "Serviço temporariamente indisponível. Tente novamente em instantes.",
				request);
	}

	// Sem estes dois, o handler genérico abaixo transformaria 401/403 de @PreAuthorize em 500.
	@ExceptionHandler(AccessDeniedException.class)
	ResponseEntity<Object> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
		return respond(ProblemTypes.FORBIDDEN, "Você não tem permissão para esta operação.", request);
	}

	@ExceptionHandler(AuthenticationException.class)
	ResponseEntity<Object> handleAuthentication(AuthenticationException ex, HttpServletRequest request) {
		return respond(ProblemTypes.UNAUTHORIZED, "Autenticação necessária.", request);
	}

	// ---- Validação ------------------------------------------------------------------------------

	@ExceptionHandler(ConstraintViolationException.class)
	ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
		List<ProblemDetails.FieldError> errors = ex.getConstraintViolations().stream()
				.map(v -> new ProblemDetails.FieldError(lastNode(v.getPropertyPath().toString()), v.getMessage()))
				.toList();
		return validation(errors, request);
	}

	@Override
	protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex, HttpHeaders headers,
			HttpStatusCode status, WebRequest webRequest) {
		String field = ex instanceof MethodArgumentTypeMismatchException m ? m.getName() : ex.getPropertyName();
		return validation(List.of(new ProblemDetails.FieldError(field != null ? field : "request", "valor inválido")),
				request(webRequest));
	}

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex, HttpHeaders headers,
			HttpStatusCode status, WebRequest webRequest) {
		List<ProblemDetails.FieldError> errors = ex.getBindingResult().getFieldErrors().stream()
				.map(e -> new ProblemDetails.FieldError(e.getField(), message(e.getDefaultMessage())))
				.toList();
		return validation(errors, request(webRequest));
	}

	@Override
	protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest webRequest) {
		List<ProblemDetails.FieldError> errors = ex.getParameterValidationResults().stream()
				.flatMap(result -> result.getResolvableErrors().stream()
						.map(e -> new ProblemDetails.FieldError(result.getMethodParameter().getParameterName(),
								message(e.getDefaultMessage()))))
				.toList();
		return validation(errors, request(webRequest));
	}

	@Override
	protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex, HttpHeaders headers,
			HttpStatusCode status, WebRequest webRequest) {
		return respond(ProblemTypes.VALIDATION, "Corpo da requisição ausente ou malformado.", request(webRequest));
	}

	@Override
	protected ResponseEntity<Object> handleMissingServletRequestParameter(MissingServletRequestParameterException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest webRequest) {
		return validation(List.of(new ProblemDetails.FieldError(ex.getParameterName(), "parâmetro obrigatório")),
				request(webRequest));
	}

	// ---- Roteamento: 404 e demais erros do Spring MVC (405, 406, 415...) ------------------------

	@Override
	protected ResponseEntity<Object> handleNoResourceFoundException(NoResourceFoundException ex, HttpHeaders headers,
			HttpStatusCode status, WebRequest webRequest) {
		return respond(ProblemTypes.NOT_FOUND, "Recurso não encontrado.", request(webRequest));
	}

	/**
	 * Demais erros padrão do MVC: mantém status e cabeçalhos do Spring (por exemplo {@code Allow} no 405),
	 * mas troca o detalhe pela frase genérica do status para não expor texto interno.
	 */
	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
			HttpStatusCode status, WebRequest webRequest) {
		HttpStatus resolved = HttpStatus.resolve(status.value());
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status,
				resolved != null ? resolved.getReasonPhrase() : "Erro na requisição.");
		problem.setInstance(ProblemDetails.instanceOf(request(webRequest)));
		if (status.is5xxServerError()) {
			log.error("Erro do framework em {}", request(webRequest).getRequestURI(), ex);
		}
		return problem(status, headers, problem);
	}

	// ---- Qualquer outra coisa: 500 sem detalhes -------------------------------------------------

	@ExceptionHandler(Exception.class)
	ResponseEntity<Object> handleUnexpected(Exception ex, HttpServletRequest request) {
		log.error("Erro inesperado em {} {}", request.getMethod(), request.getRequestURI(), ex);
		return respond(ProblemTypes.INTERNAL, "Ocorreu um erro inesperado. Informe o X-Request-Id ao suporte.", request);
	}

	// ---- Utilitários ----------------------------------------------------------------------------

	private static ResponseEntity<Object> respond(ProblemTypes type, String detail, HttpServletRequest request) {
		return problem(type.status(), new HttpHeaders(), ProblemDetails.of(type, detail, request));
	}

	private static ResponseEntity<Object> validation(List<ProblemDetails.FieldError> errors, HttpServletRequest request) {
		return problem(ProblemTypes.VALIDATION.status(), new HttpHeaders(),
				ProblemDetails.validation(INVALID_FIELDS, errors, request));
	}

	/** Content-Type fixo: não depende do {@code Accept} do cliente. */
	private static ResponseEntity<Object> problem(HttpStatusCode status, HttpHeaders headers, ProblemDetail body) {
		return ResponseEntity.status(status).headers(headers).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
	}

	private static HttpServletRequest request(WebRequest webRequest) {
		return ((ServletWebRequest) webRequest).getRequest();
	}

	private static String message(String defaultMessage) {
		return defaultMessage != null ? defaultMessage : "valor inválido";
	}

	private static String lastNode(String path) {
		int dot = path.lastIndexOf('.');
		return dot >= 0 ? path.substring(dot + 1) : path;
	}
}
