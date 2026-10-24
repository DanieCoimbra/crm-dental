package crm_data.patient.service;

import java.util.UUID;

import crm_data.patient.service.PatientAnonymizationService.StorageCleanup;

/**
 * Eventos internos, em memória, do módulo de pacientes. Só identificadores e contagens: nunca nome, CPF, chave de
 * objeto nem nome de arquivo.
 */
public final class PatientEvents {

	private PatientEvents() {
	}

	/**
	 * {@code Patient.Deactivating}: o paciente vai ser inativado. Publicado <strong>antes de gravar</strong>, de forma
	 * síncrona e dentro da transação, para a agenda cancelar os futuros (ou recusar, se há atendimento em andamento);
	 * qualquer exceção do ouvinte reverte a inativação. Só ids.
	 */
	public record Deactivating(UUID clinicId, UUID patientId) {
	}

	/** {@code Patient.Anonymized}: anonimização concluída (publicado depois do commit). */
	public record Anonymized(UUID clinicId, UUID patientId, int objectCount, StorageCleanup status) {
	}

	/** {@code Patient.StorageCleanupPending}: a limpeza do Storage falhou; os objetos ficam para conciliação. */
	public record StorageCleanupPending(UUID patientId, int objectCount) {
	}
}
