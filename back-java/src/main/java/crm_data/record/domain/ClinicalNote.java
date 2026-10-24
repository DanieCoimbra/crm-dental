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
 * Nota de atendimento, cifrada como o histórico. Editáveis: {@code content} e {@code notedAt}. Não há exclusão.
 * Sem {@code @ToString}: o conteúdo clínico nunca vai para log.
 */
@Entity
@Table(name = "clinical_notes", schema = "core")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ClinicalNote extends TenantAwareEntity {

	@Id
	private UUID id;

	@Column(name = "patient_id", nullable = false, updatable = false)
	private UUID patientId;

	@Setter
	@Column(name = "noted_at", nullable = false)
	private Instant notedAt;

	@Setter
	@Convert(converter = EncryptedStringConverter.class)
	@Column(nullable = false, length = 27000)
	private String content;

	/** Atendimento em que a nota ocorreu (opcional; mesmo paciente e clínica garantidos pela FK composta). */
	@Setter
	@Column(name = "appointment_id")
	private UUID appointmentId;

	@Column(name = "created_by", nullable = false, updatable = false)
	private UUID createdBy;

	@Column(name = "created_at", insertable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", insertable = false, updatable = false)
	private Instant updatedAt;

	@Version
	private Long version;

	public ClinicalNote(UUID id, UUID patientId, Instant notedAt, String content, UUID createdBy) {
		this.id = id;
		this.patientId = patientId;
		this.notedAt = notedAt;
		this.content = content;
		this.createdBy = createdBy;
	}
}
