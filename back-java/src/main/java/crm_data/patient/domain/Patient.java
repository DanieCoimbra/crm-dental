package crm_data.patient.domain;

import java.time.Instant;
import java.time.LocalDate;
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
import lombok.Setter;

/**
 * Paciente da clínica. O {@code clinic_id} vem do tenant ({@link TenantAwareEntity}). Só os campos editáveis têm
 * setter; colunas imutáveis levam {@code updatable = false}. Sem {@code @ToString}: nome e CPF não vão para log.
 * {@code createdAt}/{@code updatedAt} (trigger) e {@code anonymizedAt} (só a função de anonimização grava) são
 * somente leitura, sem {@code @Generated}.
 */
@Entity
@Table(name = "patients", schema = "core")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Patient extends TenantAwareEntity {

	@Id
	private UUID id;

	@Setter
	@Column(nullable = false, length = 120)
	private String name;

	@Setter
	@Column(length = 11)
	private String cpf;

	@Setter
	@Column(name = "birth_date", nullable = false)
	private LocalDate birthDate;

	@Setter
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 15)
	private Sex sex;

	@Setter
	@Column(length = 100)
	private String profession;

	@Setter
	@Column(nullable = false, length = 20)
	private String phone;

	@Setter
	@Column(length = 254)
	private String email;

	@Setter
	@Column(length = 255)
	private String address;

	@Setter
	@Column(name = "insurance_name", length = 100)
	private String insuranceName;

	@Setter
	@Column(name = "insurance_number", length = 40)
	private String insuranceNumber;

	@Setter
	@Column(name = "acquaintance_name", length = 120)
	private String acquaintanceName;

	@Setter
	@Column(name = "acquaintance_phone", length = 20)
	private String acquaintancePhone;

	@Column(nullable = false)
	private boolean active = true;

	@Column(name = "deactivated_at")
	private Instant deactivatedAt;

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

	public Patient(UUID id, UUID createdBy) {
		this.id = id;
		this.createdBy = createdBy;
	}

	public boolean isAnonymized() {
		return anonymizedAt != null;
	}

	/** Inativa e registra o instante ({@code ck_patients_deactivated} exige essa coerência). */
	public void deactivate(Instant when) {
		this.active = false;
		this.deactivatedAt = when;
	}

	public void activate() {
		this.active = true;
		this.deactivatedAt = null;
	}
}
