package crm_data.catalog.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Registro dos eventos do catálogo: somente identificadores, nunca nome nem preço de procedimento. Roda depois do
 * commit: um rollback não deixa log de algo que não aconteceu.
 */
@Component
class CatalogAuditLogger {

	private static final Logger log = LoggerFactory.getLogger("crm_data.catalog.audit");

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	void on(CatalogEvents.Created event) {
		log.info("Procedimento criado: clinicId={} procedureTypeId={} userId={}", event.clinicId(), event.procedureTypeId(),
				event.userId());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	void on(CatalogEvents.Updated event) {
		log.info("Procedimento alterado: clinicId={} procedureTypeId={} userId={}", event.clinicId(), event.procedureTypeId(),
				event.userId());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	void on(CatalogEvents.ActiveChanged event) {
		log.info("Procedimento {}: clinicId={} procedureTypeId={} userId={}", event.active() ? "ativado" : "inativado",
				event.clinicId(), event.procedureTypeId(), event.userId());
	}
}
