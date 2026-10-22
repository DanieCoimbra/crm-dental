package crm_data.record.web;

import java.time.Instant;
import java.util.UUID;

import crm_data.record.domain.PatientFile;

/** Item da lista de exames. Nunca traz {@code storageKey}, {@code clinicId} nem {@code createdBy}. */
public record FileResponse(UUID id, String kind, String originalFilename, String contentType, long sizeBytes,
		Instant createdAt) {

	public static FileResponse from(PatientFile f) {
		return new FileResponse(f.getId(), f.getKind().name(), f.getOriginalFilename(), f.getContentType(),
				f.getSizeBytes(), f.getCreatedAt());
	}

	/** Nunca imprime o nome do arquivo. */
	@Override
	public String toString() {
		return "FileResponse[id=" + id + "]";
	}
}
