package crm_data.document.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import crm_data.document.domain.DocumentType;
import crm_data.shared.error.ProblemDetails.FieldError;

/**
 * Limites e validação por tipo do documento clínico. Regra pura (sem Spring nem banco). As mensagens nunca repetem o valor
 * enviado.
 */
public final class DocumentRules {

	public static final int MAX_CONTENT_CHARS = 4000;
	public static final int MAX_CONTENT_BYTES = 16000;
	public static final int MIN_REST_DAYS = 1;
	public static final int MAX_REST_DAYS = 90;
	public static final int MIN_SPECIALTY = 2;
	public static final int MAX_SPECIALTY = 60;
	public static final int MAX_VOID_REASON = 200;
	/** Teto do PDF (4 MB). */
	public static final int MAX_PDF_BYTES = 4 * 1024 * 1024;

	private DocumentRules() {
	}

	/**
	 * Combinação de campos por tipo: atestado exige {@code restDays} (texto opcional, sem especialidade); receita exige texto
	 * (sem dias nem especialidade); encaminhamento exige especialidade e texto (sem dias). Texto ≤ 4000 caracteres e 16000
	 * bytes UTF-8. {@code specialty} e {@code content} já chegam aparados (vazio = nulo).
	 */
	public static List<FieldError> validate(DocumentType type, Integer restDays, String specialty, String content) {
		List<FieldError> errors = new ArrayList<>();
		switch (type) {
			case CERTIFICATE -> {
				if (restDays == null) {
					errors.add(new FieldError("restDays", "obrigatório para atestado"));
				}
				else if (restDays < MIN_REST_DAYS || restDays > MAX_REST_DAYS) {
					errors.add(new FieldError("restDays", "deve estar entre 1 e 90"));
				}
				if (specialty != null) {
					errors.add(new FieldError("targetSpecialty", "não se aplica a atestado"));
				}
			}
			case PRESCRIPTION -> {
				if (content == null) {
					errors.add(new FieldError("content", "obrigatório para receita"));
				}
				if (restDays != null) {
					errors.add(new FieldError("restDays", "não se aplica a receita"));
				}
				if (specialty != null) {
					errors.add(new FieldError("targetSpecialty", "não se aplica a receita"));
				}
			}
			case REFERRAL -> {
				if (specialty == null) {
					errors.add(new FieldError("targetSpecialty", "obrigatório para encaminhamento"));
				}
				else if (specialty.length() < MIN_SPECIALTY || specialty.length() > MAX_SPECIALTY) {
					errors.add(new FieldError("targetSpecialty", "deve ter entre 2 e 60 caracteres"));
				}
				if (content == null) {
					errors.add(new FieldError("content", "obrigatório para encaminhamento"));
				}
				if (restDays != null) {
					errors.add(new FieldError("restDays", "não se aplica a encaminhamento"));
				}
			}
		}
		if (content != null && (content.length() > MAX_CONTENT_CHARS
				|| content.getBytes(StandardCharsets.UTF_8).length > MAX_CONTENT_BYTES)) {
			errors.add(new FieldError("content", "deve ter no máximo 4000 caracteres"));
		}
		return errors;
	}
}
