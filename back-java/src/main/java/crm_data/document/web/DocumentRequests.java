package crm_data.document.web;

import java.time.LocalDate;
import java.util.UUID;

import crm_data.document.domain.DocumentType;
import crm_data.shared.web.NotBefore2000;
import crm_data.shared.web.RequestText;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Corpos das rotas de documento clínico. Nenhum aceita {@code clinicId}, {@code number}, {@code sha256}, {@code voided*},
 * {@code anonymizedAt}, {@code createdBy} ou {@code version} na emissão: campos extras do JSON são ignorados. A combinação
 * de campos por tipo é validada pelo service ({@code DocumentRules}), com a lista de campos no 400.
 */
public final class DocumentRequests {

	private DocumentRequests() {
	}

	/** Emissão. {@code content} e {@code targetSpecialty} chegam aparados; vazio vira nulo. */
	public record CreateDocumentRequest(
			@NotNull(message = "obrigatório") DocumentType documentType,
			@NotBefore2000 LocalDate issuedOn,
			Integer restDays,
			String targetSpecialty,
			String content,
			UUID appointmentId) {

		public CreateDocumentRequest {
			targetSpecialty = RequestText.blankToNull(targetSpecialty);
			content = RequestText.blankToNull(content);
		}

		/** Nunca imprime o texto clínico. */
		@Override
		public String toString() {
			return "CreateDocumentRequest[documentType=" + documentType + "]";
		}
	}

	/** Anulação. {@code reason} aparado; vazio vira nulo. */
	public record VoidDocumentRequest(
			@NotNull(message = "obrigatório") Long version,
			@Size(max = 200, message = "deve ter no máximo 200 caracteres") String reason) {

		public VoidDocumentRequest {
			reason = RequestText.blankToNull(reason);
		}

		/** Nunca imprime o motivo. */
		@Override
		public String toString() {
			return "VoidDocumentRequest[version=" + version + "]";
		}
	}
}
