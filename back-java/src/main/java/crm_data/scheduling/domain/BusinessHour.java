package crm_data.scheduling.domain;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

import crm_data.shared.tenant.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Expediente de um dia da semana da clínica (uma linha por clínica e dia). Horários são locais da clínica
 * ({@code clinics.timezone}). Dia fechado não tem horários; dia sem linha é "não configurado" e conta como fechado.
 */
@Entity
@Table(name = "business_hours", schema = "core")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BusinessHour extends TenantAwareEntity {

	@Id
	private UUID id;

	/** 1 = segunda … 7 = domingo (igual ao {@link DayOfWeek#getValue()}). */
	@Column(nullable = false, updatable = false)
	private Short weekday;

	@Setter
	@Column(name = "is_open", nullable = false)
	private boolean open;

	@Setter
	@Column(name = "opens_at")
	private LocalTime opensAt;

	@Setter
	@Column(name = "closes_at")
	private LocalTime closesAt;

	@Setter
	@Column(name = "lunch_starts_at")
	private LocalTime lunchStartsAt;

	@Setter
	@Column(name = "lunch_ends_at")
	private LocalTime lunchEndsAt;

	@Column(name = "created_by", nullable = false, updatable = false)
	private UUID createdBy;

	@Column(name = "created_at", insertable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", insertable = false, updatable = false)
	private Instant updatedAt;

	@Version
	private Long version;

	public BusinessHour(UUID id, DayOfWeek day, UUID createdBy) {
		this.id = id;
		this.weekday = (short) day.getValue();
		this.createdBy = createdBy;
	}

	public DayOfWeek dayOfWeek() {
		return DayOfWeek.of(weekday);
	}
}
