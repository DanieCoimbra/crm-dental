package crm_data.shared.error;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DataAccessException;
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
import org.springframework.transaction.TransactionSystemException;
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

import crm_data.shared.storage.StorageException;
import jakarta.persistence.PersistenceException;
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
		if (ex.reason() != null) {
			problem.setProperty("reason", ex.reason());
		}
		HttpHeaders headers = new HttpHeaders();
		if (ex instanceof RateLimitedException limited) {
			headers.set(HttpHeaders.RETRY_AFTER, Long.toString(limited.retryAfterSeconds()));
		}
		else if (ex instanceof BusyException busy) {
			headers.set(HttpHeaders.RETRY_AFTER, Long.toString(busy.retryAfterSeconds()));
		}
		return problem(ex.type().status(), headers, problem);
	}

	/** Validação feita pelo service, com a lista de campos (mesmo formato do Bean Validation). */
	@ExceptionHandler(FieldValidationException.class)
	ResponseEntity<Object> handleFieldValidation(FieldValidationException ex, HttpServletRequest request) {
		return validation(ex.errors(), request);
	}

	/** Hash do documento diverge do conteúdo: 500 {@code integrity}. Só o id do documento (na mensagem) vai ao log. */
	@ExceptionHandler(IntegrityException.class)
	ResponseEntity<Object> handleIntegrity(IntegrityException ex, HttpServletRequest request) {
		log.error("Falha de integridade em {} {}: {}", request.getMethod(), request.getRequestURI(), ex.getMessage());
		return respond(ProblemTypes.INTEGRITY, "Ocorreu um erro inesperado. Informe o X-Request-Id ao suporte.", request);
	}

	@ExceptionHandler(OptimisticLockingFailureException.class)
	ResponseEntity<Object> handleOptimisticLock(OptimisticLockingFailureException ex, HttpServletRequest request) {
		return respond(ProblemTypes.CONFLICT, ConflictMessages.STALE_VERSION,
				request);
	}

	@ExceptionHandler({ CannotCreateTransactionException.class, DataAccessResourceFailureException.class,
			QueryTimeoutException.class })
	ResponseEntity<Object> handleDatabaseUnavailable(Exception ex, HttpServletRequest request) {
		log.error("Banco de dados indisponível ou lento em {} {}", request.getMethod(), request.getRequestURI(), ex);
		return respond(ProblemTypes.UNAVAILABLE, "Serviço temporariamente indisponível. Tente novamente em instantes.",
				request);
	}

	/**
	 * Erros do banco traduzidos pelo {@code SQLState} (contrato F01). Nunca loga a mensagem nem a causa: o driver
	 * repete valores (CPF, por exemplo) nela. Só classe, {@code SQLState} e nome da constraint vão para o log.
	 * {@code 23505} não é tratado aqui: cada service converte pela constraint e, sem conversão, vira 500.
	 */
	@ExceptionHandler({ DataAccessException.class, PersistenceException.class })
	ResponseEntity<Object> handleDatabase(Exception ex, HttpServletRequest request) {
		String state = DatabaseErrors.sqlState(ex).orElse("");
		return switch (state) {
			case "CR001" -> respond(ProblemTypes.CONFLICT, "Inative o paciente antes de anonimizar.", request);
			case "CR002" -> respond(ProblemTypes.CONFLICT, DatabaseErrors.retentionReleaseDate(ex)
					.map(date -> "Retenção legal do prontuário vigente. Liberado em " + date + ".")
					.orElse("Retenção legal do prontuário vigente."), request);
			case "CR003" -> respond(ProblemTypes.CONFLICT, "Paciente anonimizado.", request);
			case "CR004" -> respond(ProblemTypes.CONFLICT, "Cancele os agendamentos futuros antes de anonimizar.", request);
			case "CR005" -> respond(ProblemTypes.CONFLICT, "Paciente inativo.", request);
			// Fase 3: dinheiro. CR011 só estoura no COMMIT (chega por TransactionSystemException, ver abaixo)
			case "CR006" -> respond(ProblemTypes.CONFLICT, ConflictMessages.OPEN_INSTALLMENTS, request);
			case "CR007" -> respond(ProblemTypes.CONFLICT, ConflictMessages.BUDGET_FROZEN, request);
			case "CR008" -> respond(ProblemTypes.CONFLICT, ConflictMessages.BUDGET_STATUS, request);
			case "CR009" -> respond(ProblemTypes.CONFLICT, ConflictMessages.INSTALLMENT_RULE, request);
			case "CR010" -> respond(ProblemTypes.CONFLICT, ConflictMessages.PAYMENT_RULE, request);
			case "CR011" -> respond(ProblemTypes.CONFLICT, ConflictMessages.INSTALLMENTS_SUM, request);
			// Fase 4: emissor sem CRO (a checagem prévia do service devolve a mesma resposta)
			case "CR012" -> respond(ProblemTypes.CONFLICT, ConflictMessages.PROFESSIONAL_REGISTRY_MISSING, request);
			case "23514" -> checkViolation(ex, request);
			case "23P01" -> respond(ProblemTypes.CONFLICT, ConflictMessages.SLOT_TAKEN, request);
			// deadlock ou falha de serialização: a operação perdeu a disputa; é retentável, não um defeito (409, não 500)
			case "40P01", "40001" -> respond(ProblemTypes.CONFLICT, ConflictMessages.STALE_VERSION, request);
			case "23505" -> uniqueViolation(ex, request);
			case "P0002" -> respond(ProblemTypes.NOT_FOUND, "Paciente não encontrado.", request);
			case "42501" -> respond(ProblemTypes.FORBIDDEN, "Você não tem permissão para esta operação.", request);
			default -> {
				log.error("Erro de banco em {} {}: {} sqlState={} constraint={}", request.getMethod(),
						request.getRequestURI(), ex.getClass().getName(), state.isEmpty() ? "-" : state,
						DatabaseErrors.constraintName(ex).orElse("-"));
				yield respond(ProblemTypes.INTERNAL, "Ocorreu um erro inesperado. Informe o X-Request-Id ao suporte.",
						request);
			}
		};
	}

	/**
	 * O commit (por exemplo o {@code CR011}, constraint trigger adiado) falha fora do {@code flush}: o Spring entrega a
	 * {@link TransactionSystemException}, que não é {@link DataAccessException}. Sem este tratamento a soma errada das
	 * parcelas viraria 500. O {@code SQLState} é procurado na cadeia de causas e segue o mesmo caminho dos demais erros do
	 * banco; sem {@code SQLState} conhecido, o resultado é o 500 genérico (só a classe e o estado vão ao log).
	 */
	@ExceptionHandler(TransactionSystemException.class)
	ResponseEntity<Object> handleTransactionSystem(TransactionSystemException ex, HttpServletRequest request) {
		if (DatabaseErrors.sqlState(ex).isEmpty()) {
			// falha de commit sem erro de banco na causa: mantém o log completo da stack, como o handler genérico
			return handleUnexpected(ex, request);
		}
		return handleDatabase(ex, request);
	}

	/**
	 * {@code 23514}: só duas constraints viram resposta de negócio (valor acima do teto que escapou da validação
	 * prévia); qualquer outra é defeito do backend e vira 500, com o nome da constraint no log.
	 */
	private ResponseEntity<Object> checkViolation(Exception ex, HttpServletRequest request) {
		String constraint = DatabaseErrors.constraintName(ex).orElse("");
		if (constraint.equals("ck_items_line") || constraint.equals("ck_budgets_total")) {
			ProblemDetail problem = ProblemDetails.of(ProblemTypes.BUSINESS_RULE, ConflictMessages.AMOUNT_OUT_OF_RANGE,
					request);
			problem.setProperty("reason", "AMOUNT_OUT_OF_RANGE");
			return problem(ProblemTypes.BUSINESS_RULE.status(), new HttpHeaders(), problem);
		}
		log.error("Erro de banco em {} {}: {} sqlState=23514 constraint={}", request.getMethod(), request.getRequestURI(),
				ex.getClass().getName(), constraint.isEmpty() ? "-" : constraint);
		return respond(ProblemTypes.INTERNAL, "Ocorreu um erro inesperado. Informe o X-Request-Id ao suporte.", request);
	}

	/**
	 * Unicidade da agenda traduzida pela constraint. Qualquer outra {@code 23505} segue como 500 (cada service converte
	 * a sua, por exemplo o CPF repetido): a mensagem do driver repete o valor e nunca é devolvida.
	 */
	private ResponseEntity<Object> uniqueViolation(Exception ex, HttpServletRequest request) {
		String constraint = DatabaseErrors.constraintName(ex).orElse("");
		return switch (constraint) {
			case "ux_bh_clinic_weekday" -> respond(ProblemTypes.CONFLICT, "Dia já configurado.", request);
			case "ux_clinic_holidays_date" -> respond(ProblemTypes.CONFLICT, "Já existe feriado nesta data.", request);
			case "ux_ptype_clinic_name" -> respond(ProblemTypes.CONFLICT, ConflictMessages.PROCEDURE_NAME_TAKEN, request);
			case "ux_pay_reverses" -> respond(ProblemTypes.CONFLICT, ConflictMessages.PAYMENT_REVERSED, request);
			case "ux_inst_budget_number" -> respond(ProblemTypes.CONFLICT, ConflictMessages.PLAN_CHANGED, request);
			default -> {
				log.error("Erro de banco em {} {}: {} sqlState=23505 constraint={}", request.getMethod(),
						request.getRequestURI(), ex.getClass().getName(), constraint.isEmpty() ? "-" : constraint);
				yield respond(ProblemTypes.INTERNAL, "Ocorreu um erro inesperado. Informe o X-Request-Id ao suporte.",
						request);
			}
		};
	}

	/**
	 * Storage fora do ar, lento ou recusando: 503. Vai ao log a mensagem da exceção, que é segura por construção (só a
	 * operação, o status HTTP ou a classe do erro de rede, nunca URL, chave ou resposta do Storage); a causa não.
	 */
	@ExceptionHandler(StorageException.class)
	ResponseEntity<Object> handleStorage(StorageException ex, HttpServletRequest request) {
		log.error("Falha no armazenamento de arquivos em {} {}: {}", request.getMethod(), request.getRequestURI(),
				ex.getMessage());
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
		if (DatabaseErrors.hasSqlCause(ex)) {
			// Erro de banco embrulhado fora de DataAccessException: a stack e as causas carregariam o DETAIL do driver
			// (CPF, nome). Só a classe e o SQLState vão ao log.
			log.error("Erro inesperado em {} {}: {} sqlState={}", request.getMethod(), request.getRequestURI(),
					ex.getClass().getName(), DatabaseErrors.sqlState(ex).orElse("-"));
		}
		else {
			log.error("Erro inesperado em {} {}", request.getMethod(), request.getRequestURI(), ex);
		}
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
