package crm_data.commercial.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.Immutable;
import org.springframework.data.domain.Persistable;

import crm_data.shared.tenant.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Metadados do PDF de um orçamento guardado no Storage (o binário nunca vai para o banco). Linha imutável: só o banco
 * altera {@code deleted_at} (na anonimização do paciente, que também manda apagar o objeto). {@code revision} e
 * {@code generatedAt} são preenchidas pelo banco e lidas por {@code refresh} depois do {@code INSERT}. {@code storageKey}
 * é opaca e <strong>nunca</strong> sai pela API nem vai para log. Sem {@code @Version}: implementa {@link Persistable}
 * para o {@code save} fazer {@code persist} sem {@code SELECT} prévio.
 */
@Entity
@Immutable
@Table(name = "budget_documents", schema = "core")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BudgetDocument extends TenantAwareEntity implements Persistable<UUID> {

	public static final String CONTENT_TYPE = "application/pdf";

	@Id
	private UUID id;

	@Column(name = "budget_id", nullable = false, updatable = false)
	private UUID budgetId;

	@Column(name = "patient_id", nullable = false, updatable = false)
	private UUID patientId;

	/** Atribuída pelo banco ({@code max + 1} sob a trava do orçamento); 1, 2, 3… por orçamento. */
	@Column(insertable = false, updatable = false)
	private Short revision;

	@Column(name = "storage_key", nullable = false, updatable = false, length = 120)
	private String storageKey;

	@Column(name = "content_type", nullable = false, updatable = false, length = 20)
	private String contentType;

	@Column(name = "size_bytes", nullable = false, updatable = false)
	private Long sizeBytes;

	/** Hex minúsculo; {@code varchar(64)}, nunca {@code char}. */
	@Column(nullable = false, updatable = false, length = 64)
	private String sha256;

	@Column(name = "generated_at", insertable = false, updatable = false)
	private Instant generatedAt;

	@Column(name = "deleted_at", insertable = false, updatable = false)
	private Instant deletedAt;

	@Column(name = "created_by", nullable = false, updatable = false)
	private UUID createdBy;

	@Column(name = "created_at", insertable = false, updatable = false)
	private Instant createdAt;

	@Transient
	private boolean isNew;

	public BudgetDocument(UUID id, UUID budgetId, UUID patientId, String storageKey, long sizeBytes, String sha256, UUID createdBy) {
		this.id = id;
		this.budgetId = budgetId;
		this.patientId = patientId;
		this.storageKey = storageKey;
		this.contentType = CONTENT_TYPE;
		this.sizeBytes = sizeBytes;
		this.sha256 = sha256;
		this.createdBy = createdBy;
		this.isNew = true;
	}

	@Override
	public boolean isNew() {
		return isNew;
	}

	@PostLoad
	@PostPersist
	void markNotNew() {
		this.isNew = false;
	}
}
