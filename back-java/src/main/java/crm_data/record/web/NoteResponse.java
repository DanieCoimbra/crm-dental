package crm_data.record.web;

import java.time.Instant;
import java.util.UUID;

import crm_data.record.domain.ClinicalNote;

/** Nota com o texto já decifrado. Sem {@code clinicId}. */
public record NoteResponse(UUID id, UUID patientId, Instant notedAt, String content, UUID createdBy, Instant createdAt,
		Instant updatedAt, Long version) {

	public static NoteResponse from(ClinicalNote n) {
		return new NoteResponse(n.getId(), n.getPatientId(), n.getNotedAt(), n.getContent(), n.getCreatedBy(),
				n.getCreatedAt(), n.getUpdatedAt(), n.getVersion());
	}

	/** Nunca imprime o conteúdo clínico. */
	@Override
	public String toString() {
		return "NoteResponse[id=" + id + "]";
	}
}
