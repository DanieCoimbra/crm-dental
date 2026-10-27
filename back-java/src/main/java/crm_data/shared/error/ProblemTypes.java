package crm_data.shared.error;

import java.net.URI;

import org.springframework.http.HttpStatus;

/** Tipos de erro do contrato (slug, status e título); o handler global é o único que os traduz em resposta. */
public enum ProblemTypes {

	VALIDATION("validation", HttpStatus.BAD_REQUEST, "Dados inválidos"),
	UNAUTHORIZED("unauthorized", HttpStatus.UNAUTHORIZED, "Não autenticado"),
	FORBIDDEN("forbidden", HttpStatus.FORBIDDEN, "Acesso negado"),
	NOT_FOUND("not-found", HttpStatus.NOT_FOUND, "Recurso não encontrado"),
	CONFLICT("conflict", HttpStatus.CONFLICT, "Conflito"),
	BUSINESS_RULE("business-rule", HttpStatus.UNPROCESSABLE_CONTENT, "Regra de negócio violada"),
	RATE_LIMITED("rate-limited", HttpStatus.TOO_MANY_REQUESTS, "Muitas requisições"),
	INTERNAL("internal", HttpStatus.INTERNAL_SERVER_ERROR, "Erro interno"),
	/** Documento clínico adulterado no banco (hash não confere); 500 com detalhe genérico. */
	INTEGRITY("integrity", HttpStatus.INTERNAL_SERVER_ERROR, "Falha de integridade"),
	UNAVAILABLE("unavailable", HttpStatus.SERVICE_UNAVAILABLE, "Serviço indisponível");

	private static final String BASE_URI = "https://crm.local/problems/";

	private final String slug;
	private final HttpStatus status;
	private final String title;

	ProblemTypes(String slug, HttpStatus status, String title) {
		this.slug = slug;
		this.status = status;
		this.title = title;
	}

	public String slug() {
		return slug;
	}

	public HttpStatus status() {
		return status;
	}

	public String title() {
		return title;
	}

	public URI typeUri() {
		return URI.create(BASE_URI + slug);
	}
}
