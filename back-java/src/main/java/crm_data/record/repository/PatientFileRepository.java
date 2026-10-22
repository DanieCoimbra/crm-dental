package crm_data.record.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import crm_data.record.domain.PatientFile;

public interface PatientFileRepository extends JpaRepository<PatientFile, UUID> {

	/** Exames ativos do paciente; a ordem ({@code createdAt DESC, id DESC}) vem do {@link Pageable}. */
	Page<PatientFile> findByPatientIdAndDeletedAtIsNull(UUID patientId, Pageable pageable);

	/** Exame ativo só se pertencer ao paciente informado (removido, de outro paciente ou de outra clínica não casa). */
	Optional<PatientFile> findByIdAndPatientIdAndDeletedAtIsNull(UUID id, UUID patientId);

	/** Todas as chaves do paciente, inclusive de exames removidos logicamente (a anonimização apaga todos). */
	@Query("select f.storageKey from PatientFile f where f.patientId = :patientId")
	List<String> findStorageKeysByPatientId(@Param("patientId") UUID patientId);
}
