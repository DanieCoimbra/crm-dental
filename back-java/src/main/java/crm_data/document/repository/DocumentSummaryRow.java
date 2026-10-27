package crm_data.document.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import crm_data.document.domain.DocumentType;

/** Linha da lista de documentos do paciente: tudo menos o texto cifrado, o motivo e o hash. */
public record DocumentSummaryRow(UUID id, UUID patientId, UUID appointmentId, DocumentType type, Integer number,
		LocalDate issuedOn, Short restDays, String targetSpecialty, Instant voidedAt, UUID voidedBy, Instant anonymizedAt,
		Instant createdAt, UUID createdBy) {
}
