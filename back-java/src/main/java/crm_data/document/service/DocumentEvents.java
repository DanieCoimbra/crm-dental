package crm_data.document.service;

import java.util.UUID;

/** Eventos internos, em memória, do módulo de documentos. Só identificadores: nunca texto, motivo ou nome. */
public final class DocumentEvents {

	private DocumentEvents() {
	}

	/** {@code Document.Issued}: documento emitido (publicado depois do commit). */
	public record Issued(UUID clinicId, UUID documentId, UUID patientId, String type, UUID userId) {
	}

	/** {@code Document.Voided}: documento anulado. */
	public record Voided(UUID clinicId, UUID documentId, UUID patientId, UUID userId) {
	}

	/** {@code Document.Downloaded}: PDF entregue (e {@code DOWNLOAD} gravado). */
	public record Downloaded(UUID clinicId, UUID documentId, UUID userId) {
	}
}
