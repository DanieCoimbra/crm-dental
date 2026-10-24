package crm_data.scheduling.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import crm_data.scheduling.domain.ClinicHoliday;

public interface ClinicHolidayRepository extends JpaRepository<ClinicHoliday, UUID> {

	List<ClinicHoliday> findByDateBetweenOrderByDate(LocalDate from, LocalDate to);
}
