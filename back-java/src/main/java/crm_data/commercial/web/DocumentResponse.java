package crm_data.commercial.web;

import java.time.Instant;
import java.util.UUID;

import crm_data.commercial.service.BudgetService.DocumentSummary;

/** Revisão do PDF na API. Nunca traz a {@code storageKey}, o {@code sha256} nem o {@code clinicId}. */
public record DocumentResponse(UUID id, int revision, Instant generatedAt, long sizeBytes) {

	public static DocumentResponse from(DocumentSummary document) {
		return new DocumentResponse(document.id(), document.revision(), document.generatedAt(), document.sizeBytes());
	}
}
