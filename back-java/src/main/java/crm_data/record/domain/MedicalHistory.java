package crm_data.record.domain;

import java.time.Instant;
import java.util.UUID;

import crm_data.shared.crypto.EncryptedStringConverter;
import crm_data.shared.tenant.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Histórico médico (um por paciente). O {@code content} é cifrado com AES-256-GCM pelo conversor: o banco guarda só o
 * texto opaco em Base64. Sem {@code @ToString}: o conteúdo clínico nunca vai para log. {@code patientId} é um UUID
 * simples (sem {@code @ManyToOne}) e imutável, assim como {@code createdBy}; {@code createdAt}/{@code updatedAt} são
 * somente leitura (o {@code updated_at} é do trigger) e não usam {@code @Generated}.
 */
@Entity
@Table(name = "medical_histories", schema = "core")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MedicalHistory extends TenantAwareEntity {

	@Id
	private UUID id;

	@Column(name = "patient_id", nullable = false, updatable = false)
	private UUID patientId;

	@Setter
	@Convert(converter = EncryptedStringConverter.class)
	@Column(nullable = false, length = 54000)
	private String content;

	@Column(name = "created_by", nullable = false, updatable = false)
	private UUID createdBy;

	@Column(name = "created_at", insertable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", insertable = false, updatable = false)
	private Instant updatedAt;

	@Version
	private Long version;

	public MedicalHistory(UUID id, UUID patientId, String content, UUID createdBy) {
		this.id = id;
		this.patientId = patientId;
		this.content = content;
		this.createdBy = createdBy;
	}
}
