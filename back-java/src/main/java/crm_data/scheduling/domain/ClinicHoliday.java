package crm_data.scheduling.domain;

import java.time.Instant;
import java.time.LocalDate;
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

/** Feriado ou recesso da clínica (único por clínica e data). Remoção física: única exceção a "sem DELETE". */
@Entity
@Table(name = "clinic_holidays", schema = "core")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ClinicHoliday extends TenantAwareEntity {

	@Id
	private UUID id;

	@Setter
	@Column(name = "holiday_date", nullable = false)
	private LocalDate date;

	@Setter
	@Column(nullable = false, length = 80)
	private String name;

	@Column(name = "created_by", nullable = false, updatable = false)
	private UUID createdBy;

	@Column(name = "created_at", insertable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", insertable = false, updatable = false)
	private Instant updatedAt;

	@Version
	private Long version;

	public ClinicHoliday(UUID id, LocalDate date, String name, UUID createdBy) {
		this.id = id;
		this.date = date;
		this.name = name;
		this.createdBy = createdBy;
	}
}
