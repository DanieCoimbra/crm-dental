package crm_data.scheduling.domain;

import java.time.LocalDate;

import org.hibernate.annotations.Immutable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Feriado nacional: dado global (sem {@code clinic_id}), somente leitura para a aplicação. Por isso não estende
 * {@code TenantAwareEntity}; a escrita é feita só por migration do dono do schema.
 */
@Entity
@Immutable
@Table(name = "holidays", schema = "core")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NationalHoliday {

	@Id
	@Column(name = "holiday_date")
	private LocalDate date;

	@Column(nullable = false, length = 80)
	private String name;
}
