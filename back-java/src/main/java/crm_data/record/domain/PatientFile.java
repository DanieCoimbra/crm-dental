package crm_data.record.domain;

import java.time.Instant;
import java.util.UUID;

import crm_data.shared.tenant.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Metadados de um exame guardado no Storage. Todos os campos são imutáveis ({@code updatable = false}), exceto
 * {@code deletedAt} (remoção lógica). {@code createdAt}/{@code updatedAt} são somente leitura e sem {@code @Generated}:
 * o {@code RETURNING} que isso geraria seria negado à recepção, que só tem {@code INSERT} nesta tabela. Sem
 * {@code @ToString}: chave e nome do arquivo não vão para log.
 */
@Entity
@Table(name = "patient_files", schema = "core")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PatientFile extends TenantAwareEntity {

	@Id
	private UUID id;

	@Column(name = "patient_id", nullable = false, updatable = false)
	private UUID patientId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, updatable = false, length = 20)
	private FileKind kind;

	@Column(name = "storage_key", nullable = false, updatable = false, length = 120)
	private String storageKey;

	@Column(name = "original_filename", nullable = false, updatable = false, length = 255)
	private String originalFilename;

	@Column(name = "content_type", nullable = false, updatable = false, length = 50)
	private String contentType;

	@Column(name = "size_bytes", nullable = false, updatable = false)
	private long sizeBytes;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	@Column(name = "created_by", nullable = false, updatable = false)
	private UUID createdBy;

	@Column(name = "created_at", insertable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", insertable = false, updatable = false)
	private Instant updatedAt;

	@Version
	private Long version;

	public PatientFile(UUID id, UUID patientId, FileKind kind, String storageKey, String originalFilename,
			String contentType, long sizeBytes, UUID createdBy) {
		this.id = id;
		this.patientId = patientId;
		this.kind = kind;
		this.storageKey = storageKey;
		this.originalFilename = originalFilename;
		this.contentType = contentType;
		this.sizeBytes = sizeBytes;
		this.createdBy = createdBy;
	}

	public boolean isDeleted() {
		return deletedAt != null;
	}

	/** Remoção lógica: o registro e o objeto permanecem (retenção legal) até a anonimização. */
	public void markDeleted(Instant when) {
		this.deletedAt = when;
	}
}
