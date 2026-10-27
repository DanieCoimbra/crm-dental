package crm_data.document.web;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import crm_data.document.domain.DocumentState;
import crm_data.document.domain.DocumentType;
import crm_data.document.service.ClinicalDocumentService.SummaryView;

/** Item da lista do paciente: os mesmos campos do detalhe, sem {@code content}, {@code voidReason} e {@code version}. */
public record DocumentSummaryResponse(UUID id, UUID patientId, UUID appointmentId, DocumentType documentType, Integer number,
		LocalDate issuedOn, Short restDays, String targetSpecialty, DocumentState state, boolean anonymized, Instant voidedAt,
		String voidedByName, Instant createdAt, String createdByName) {

	public static DocumentSummaryResponse from(SummaryView v) {
		return new DocumentSummaryResponse(v.id(), v.patientId(), v.appointmentId(), v.type(), v.number(), v.issuedOn(), v.restDays(),
				v.targetSpecialty(), v.state(), v.state() == DocumentState.ANONYMIZED, v.voidedAt(), v.voidedByName(), v.createdAt(),
				v.createdByName());
	}
}
