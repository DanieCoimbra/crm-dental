package crm_data.document.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.hibernate.annotations.DynamicUpdate;

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
 * Documento clínico emitido (atestado, receita ou encaminhamento): registro IMUTÁVEL. O texto fica cifrado no campo
 * {@code content} (o service cifra uma vez; o {@code sha256} cobre o texto cifrado). A única mutação é a anulação, que
 * atualiza só {@code voided_at}, {@code voided_by}, {@code void_reason} e {@code version} ({@link DynamicUpdate}); o
 * restante é {@code updatable=false}. Número, anulação e anonimização vêm do banco (triggers), por isso são
 * {@code insertable=false}. Sem {@code @ToString}/{@code @Data}: o conteúdo e o motivo nunca vão para log.
 */
@Entity
@Table(name = "clinical_documents", schema = "core")
@DynamicUpdate
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ClinicalDocument extends TenantAwareEntity {

	@Id
	private UUID id;

	@Column(name = "patient_id", nullable = false, updatable = false)
	private UUID patientId;

	@Column(name = "appointment_id", updatable = false)
	private UUID appointmentId;

	@Enumerated(EnumType.STRING)
	@Column(name = "document_type", nullable = false, length = 15, updatable = false)
	private DocumentType type;

	/** Atribuído pelo banco (por clínica e tipo); lido por {@code refresh} depois do {@code INSERT}. */
	@Column(name = "number", insertable = false, updatable = false)
	private Integer number;

	@Column(name = "issued_on", nullable = false, updatable = false)
	private LocalDate issuedOn;

	@Column(name = "rest_days", updatable = false)
	private Short restDays;

	@Column(name = "target_specialty", length = 60, updatable = false)
	private String targetSpecialty;

	/** Texto CIFRADO (Base64 do envelope AES-GCM); nulo só depois de anonimizado. */
	@Column(name = "content", length = 22000, updatable = false)
	private String content;

	@Column(name = "sha256", nullable = false, length = 64, updatable = false)
	private String sha256;

	@Column(name = "voided_at", insertable = false)
	private Instant voidedAt;

	@Column(name = "voided_by", insertable = false)
	private UUID voidedBy;

	@Column(name = "void_reason", length = 200, insertable = false)
	private String voidReason;

	@Column(name = "anonymized_at", insertable = false, updatable = false)
	private Instant anonymizedAt;

	@Column(name = "created_by", nullable = false, updatable = false)
	private UUID createdBy;

	@Column(name = "created_at", insertable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", insertable = false, updatable = false)
	private Instant updatedAt;

	@Version
	private Long version;

	public ClinicalDocument(UUID id, UUID patientId, UUID appointmentId, DocumentType type, LocalDate issuedOn, Short restDays,
			String targetSpecialty, String cipherText, String sha256, UUID createdBy) {
		this.id = id;
		this.patientId = patientId;
		this.appointmentId = appointmentId;
		this.type = type;
		this.issuedOn = issuedOn;
		this.restDays = restDays;
		this.targetSpecialty = targetSpecialty;
		this.content = cipherText;
		this.sha256 = sha256;
		this.createdBy = createdBy;
	}

	public DocumentState state() {
		if (anonymizedAt != null) {
			return DocumentState.ANONYMIZED;
		}
		return voidedAt != null ? DocumentState.VOIDED : DocumentState.ACTIVE;
	}

	/** Única mutação permitida: anular (uma vez). O service confere o estado antes. */
	public void markVoided(Instant now, UUID by, String reason) {
		this.voidedAt = now;
		this.voidedBy = by;
		this.voidReason = reason;
	}
}
