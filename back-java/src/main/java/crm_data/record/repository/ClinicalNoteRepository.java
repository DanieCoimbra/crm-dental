package crm_data.record.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import crm_data.record.domain.ClinicalNote;

public interface ClinicalNoteRepository extends JpaRepository<ClinicalNote, UUID> {

	/** Linha do tempo do paciente; a ordem ({@code notedAt DESC, id DESC}) vem do {@link Pageable}. */
	Page<ClinicalNote> findByPatientId(UUID patientId, Pageable pageable);

	/** Nota só se pertencer ao paciente informado (outro paciente ou outra clínica não casa). */
	Optional<ClinicalNote> findByIdAndPatientId(UUID id, UUID patientId);
}
