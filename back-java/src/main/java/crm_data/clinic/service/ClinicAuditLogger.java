package crm_data.clinic.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Registro de auditoria dos eventos da clínica: somente identificadores, nunca nomes, e-mails ou senhas. Roda depois
 * do commit (ou na hora, se o evento for publicado fora de transação): um rollback não deixa log de algo que não
 * aconteceu.
 */
@Component
class ClinicAuditLogger {

	private static final Logger log = LoggerFactory.getLogger("crm_data.clinic.audit");

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	void on(ClinicEvents.Updated event) {
		log.info("Clínica atualizada: clinicId={}", event.clinicId());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	void on(ClinicEvents.UserCreated event) {
		log.info("Usuário criado: userId={} clinicId={}", event.userId(), event.clinicId());
	}
}
