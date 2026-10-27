package crm_data.scheduling.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import crm_data.scheduling.domain.ClinicHoliday;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ClinicHolidayRepository extends JpaRepository<ClinicHoliday, UUID> {

	List<ClinicHoliday> findByDateBetweenOrderByDate(LocalDate from, LocalDate to);
	/**
	 * Como {@code findById}, mas por JPQL: o filtro Hibernate do tenant se aplica ({@code find} por id não o aplica) e a RLS segue
	 * como barreira final. Id de outra clínica não volta.
	 */
	@Query("select h from ClinicHoliday h where h.id = :id")
	Optional<ClinicHoliday> findOwnById(@Param("id") UUID id);
}
