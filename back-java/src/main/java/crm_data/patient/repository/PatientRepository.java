package crm_data.patient.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import crm_data.patient.domain.Patient;

public interface PatientRepository extends JpaRepository<Patient, UUID>, JpaSpecificationExecutor<Patient> {

	/**
	 * Uma linha com {@code true} se o paciente está anonimizado, {@code false} se não; lista vazia se ele não existe
	 * na clínica do tenant (filtro + RLS). Evita carregar a entidade só para checar existência e estado.
	 */
	@Query("select (p.anonymizedAt is not null) from Patient p where p.id = :id")
	List<Boolean> anonymizedFlag(@Param("id") UUID id);
}
