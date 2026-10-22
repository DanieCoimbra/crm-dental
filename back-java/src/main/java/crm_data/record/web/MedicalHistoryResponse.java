package crm_data.record.web;

import java.time.Instant;
import java.util.UUID;

import crm_data.record.domain.MedicalHistory;

/** Histórico com o texto já decifrado. Sem {@code clinicId} nem {@code createdBy}. */
public record MedicalHistoryResponse(UUID id, UUID patientId, String content, Instant createdAt, Instant updatedAt,
		Long version) {

	public static MedicalHistoryResponse from(MedicalHistory h) {
		return new MedicalHistoryResponse(h.getId(), h.getPatientId(), h.getContent(), h.getCreatedAt(), h.getUpdatedAt(),
				h.getVersion());
	}

	/** Nunca imprime o conteúdo clínico. */
	@Override
	public String toString() {
		return "MedicalHistoryResponse[id=" + id + "]";
	}
}
