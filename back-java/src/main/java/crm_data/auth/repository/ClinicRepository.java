package crm_data.auth.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import crm_data.auth.domain.Clinic;

public interface ClinicRepository extends JpaRepository<Clinic, UUID> {
}
