package crm_data.record.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import crm_data.record.domain.MedicalHistory;

public interface MedicalHistoryRepository extends JpaRepository<MedicalHistory, UUID> {

	/** Histórico do paciente na clínica do tenant (filtro + RLS); no máximo um ({@code ux_histories_patient}). */
	Optional<MedicalHistory> findByPatientId(UUID patientId);
}
