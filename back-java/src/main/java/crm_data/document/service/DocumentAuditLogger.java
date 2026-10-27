package crm_data.document.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Registro dos eventos do documento: somente identificadores, nunca texto, motivo, nome de paciente ou CRO. Roda depois do
 * commit: um rollback não deixa log de algo que não aconteceu.
 */
@Component
class DocumentAuditLogger {

	private static final Logger log = LoggerFactory.getLogger("crm_data.document.audit");

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	void on(DocumentEvents.Issued event) {
		log.info("Documento emitido ({}): clinicId={} documentId={} patientId={} userId={}", event.type(), event.clinicId(),
				event.documentId(), event.patientId(), event.userId());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	void on(DocumentEvents.Voided event) {
		log.info("Documento anulado: clinicId={} documentId={} patientId={} userId={}", event.clinicId(), event.documentId(),
				event.patientId(), event.userId());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	void on(DocumentEvents.Downloaded event) {
		log.info("PDF de documento entregue: clinicId={} documentId={} userId={}", event.clinicId(), event.documentId(),
				event.userId());
	}
}
