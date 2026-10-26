package crm_data.commercial.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Registro dos eventos do orçamento: somente identificadores, nunca valor, nome do paciente nem observação. Roda depois
 * do commit: um rollback não deixa log de algo que não aconteceu.
 */
@Component
class CommercialAuditLogger {

	private static final Logger log = LoggerFactory.getLogger("crm_data.commercial.audit");

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	void on(CommercialEvents.BudgetCreated event) {
		log.info("Orçamento criado: clinicId={} budgetId={} patientId={} userId={}", event.clinicId(), event.budgetId(),
				event.patientId(), event.userId());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	void on(CommercialEvents.BudgetUpdated event) {
		log.info("Orçamento alterado: clinicId={} budgetId={} userId={}", event.clinicId(), event.budgetId(), event.userId());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	void on(CommercialEvents.BudgetDecided event) {
		log.info("Orçamento decidido ({}): clinicId={} budgetId={} userId={}", event.status(), event.clinicId(), event.budgetId(),
				event.userId());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	void on(CommercialEvents.BudgetSent event) {
		log.info("Orçamento enviado: clinicId={} budgetId={} patientId={} revision={} userId={}", event.clinicId(), event.budgetId(),
				event.patientId(), event.revision(), event.userId());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	void on(CommercialEvents.DocumentGenerated event) {
		log.info("PDF do orçamento reemitido: clinicId={} budgetId={} revision={} userId={}", event.clinicId(), event.budgetId(),
				event.revision(), event.userId());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	void on(CommercialEvents.StorageCompensationFailed event) {
		log.error("File.CompensationFailed: objeto órfão no Storage clinicId={} budgetId={}", event.clinicId(), event.budgetId());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	void on(CommercialEvents.BudgetDiscarded event) {
		log.info("Orçamento descartado: clinicId={} budgetId={} userId={}", event.clinicId(), event.budgetId(), event.userId());
	}
}
