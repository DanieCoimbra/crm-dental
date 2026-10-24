package crm_data.patient.repository;

import java.util.Collection;
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

	/** Estado que importa para agendar; lista vazia se o paciente não existe na clínica do tenant. */
	@Query("select p.active as active, (p.anonymizedAt is not null) as anonymized from Patient p where p.id = :id")
	List<SchedulingState> schedulingState(@Param("id") UUID id);

	/** Id, nome e telefone em lote (só a agenda usa); ids de outra clínica não voltam (filtro + RLS). */
	@Query("select p.id as id, p.name as name, p.phone as phone from Patient p where p.id in :ids")
	List<BriefRow> briefRows(@Param("ids") Collection<UUID> ids);

	interface SchedulingState {

		boolean getActive();

		boolean getAnonymized();
	}

	interface BriefRow {

		UUID getId();

		String getName();

		String getPhone();
	}
}
