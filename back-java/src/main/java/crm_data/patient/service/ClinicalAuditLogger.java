package crm_data.patient.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Registro dos eventos de pacientes: somente identificadores e contagens, nunca nomes, CPF, chaves de objeto ou nomes
 * de arquivo. Roda depois do commit (ou na hora, se o evento for publicado fora de transação, como na anonimização,
 * que publica depois do commit do banco).
 */
@Component
class ClinicalAuditLogger {

	private static final Logger log = LoggerFactory.getLogger("crm_data.patient.audit");

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	void on(PatientEvents.Anonymized event) {
		log.info("Paciente anonimizado: clinicId={} patientId={} objects={} storageCleanup={}", event.clinicId(),
				event.patientId(), event.objectCount(), event.status());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	void on(PatientEvents.StorageCleanupPending event) {
		log.warn("Limpeza do Storage pendente após anonimização: patientId={} objects={}", event.patientId(),
				event.objectCount());
	}
}
