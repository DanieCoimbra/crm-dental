package crm_data.scheduling.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

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
 * Agendamento. O conflito de horário é garantido pelo banco ({@code ex_appt_no_overlap}); a máquina de status é do
 * backend. {@code patientId} e {@code createdBy} são imutáveis. {@code procedure_type_id} existe na tabela mas não é
 * mapeada (Fase 3). {@code notes} é texto claro e não leva dado clínico; ainda assim não há {@code @ToString}.
 */
@Entity
@Table(name = "appointments", schema = "core")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Appointment extends TenantAwareEntity {

	@Id
	private UUID id;

	@Column(name = "patient_id", nullable = false, updatable = false)
	private UUID patientId;

	@Setter
	@Column(name = "starts_at", nullable = false)
	private Instant startsAt;

	@Setter
	@Column(name = "ends_at", nullable = false)
	private Instant endsAt;

	@Setter
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 15)
	private AppointmentStatus status;

	@Setter
	@Column(length = 500)
	private String notes;

	@Column(name = "created_by", nullable = false, updatable = false)
	private UUID createdBy;

	/**
	 * Preenchidos pelo banco (default e trigger) e devolvidos ao Hibernate depois do {@code INSERT}/{@code UPDATE}, para
	 * a resposta da criação e da remarcação já trazê-los. A leitura da própria linha é permitida aos dois papéis pela
	 * RLS de {@code appointments}; por isso, ao contrário de {@code patient_files}, aqui o {@code @Generated} é seguro.
	 */
	@Generated(event = { EventType.INSERT, EventType.UPDATE })
	@Column(name = "created_at", insertable = false, updatable = false)
	private Instant createdAt;

	@Generated(event = { EventType.INSERT, EventType.UPDATE })
	@Column(name = "updated_at", insertable = false, updatable = false)
	private Instant updatedAt;

	@Version
	private Long version;

	/** Agendamento novo: sempre {@code SCHEDULED}. */
	public Appointment(UUID id, UUID patientId, Instant startsAt, Instant endsAt, String notes, UUID createdBy) {
		this.id = id;
		this.patientId = patientId;
		this.startsAt = startsAt;
		this.endsAt = endsAt;
		this.status = AppointmentStatus.SCHEDULED;
		this.notes = notes;
		this.createdBy = createdBy;
	}
}
