package crm_data.scheduling.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import crm_data.scheduling.domain.BusinessHour;

public interface BusinessHourRepository extends JpaRepository<BusinessHour, UUID> {
}
