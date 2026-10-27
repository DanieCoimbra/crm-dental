package crm_data.document.web;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import crm_data.document.domain.ClinicalDocument;
import crm_data.document.domain.DocumentState;
import crm_data.document.domain.DocumentType;
import crm_data.document.service.DocumentView;

/**
 * Documento na API, com o texto já decifrado. Nunca traz {@code clinicId}, {@code sha256}, o id do emissor nem o texto
 * cifrado. Documento anonimizado: {@code content}, {@code restDays}, {@code targetSpecialty} e {@code voidReason} nulos.
 */
public record DocumentResponse(UUID id, UUID patientId, UUID appointmentId, DocumentType documentType, Integer number,
		LocalDate issuedOn, Short restDays, String targetSpecialty, String content, DocumentState state, boolean anonymized,
		Instant voidedAt, String voidedByName, String voidReason, Instant createdAt, String createdByName, Long version) {

	public static DocumentResponse from(DocumentView view) {
		ClinicalDocument d = view.document();
		return new DocumentResponse(d.getId(), d.getPatientId(), d.getAppointmentId(), d.getType(), d.getNumber(), d.getIssuedOn(),
				d.getRestDays(), d.getTargetSpecialty(), view.content(), d.state(), d.getAnonymizedAt() != null, d.getVoidedAt(),
				view.voidedByName(), d.getVoidReason(), d.getCreatedAt(), view.createdByName(), d.getVersion());
	}

	/** Nunca imprime o texto clínico nem o motivo. */
	@Override
	public String toString() {
		return "DocumentResponse[id=" + id + "]";
	}
}
