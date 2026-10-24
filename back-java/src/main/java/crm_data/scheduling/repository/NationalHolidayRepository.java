package crm_data.scheduling.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.repository.Repository;

import crm_data.scheduling.domain.NationalHoliday;

/** Somente leitura: o {@link Repository} cru não expõe nenhum método de escrita. */
public interface NationalHolidayRepository extends Repository<NationalHoliday, LocalDate> {

	List<NationalHoliday> findByDateBetweenOrderByDate(LocalDate from, LocalDate to);
}
