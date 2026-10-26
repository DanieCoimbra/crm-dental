package crm_data.finance.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Registro dos eventos do financeiro: só identificadores e contagens, depois do commit. */
@Component
class FinanceAuditLogger {

	private static final Logger log = LoggerFactory.getLogger("crm_data.finance.audit");

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	void on(FinanceEvents.PlanCreated event) {
		log.info("Plano de parcelas criado: clinicId={} budgetId={} count={} userId={}", event.clinicId(), event.budgetId(),
				event.count(), event.userId());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	void on(FinanceEvents.PlanRedone event) {
		log.info("Plano de parcelas refeito: clinicId={} budgetId={} count={} userId={}", event.clinicId(), event.budgetId(),
				event.count(), event.userId());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	void on(FinanceEvents.PaymentRegistered event) {
		log.info("Pagamento registrado: clinicId={} paymentId={} installmentId={} userId={}", event.clinicId(), event.paymentId(),
				event.installmentId(), event.userId());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	void on(FinanceEvents.PaymentReversed event) {
		log.info("Pagamento estornado: clinicId={} paymentId={} reversedPaymentId={} installmentId={} userId={}", event.clinicId(),
				event.paymentId(), event.reversedPaymentId(), event.installmentId(), event.userId());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	void on(FinanceEvents.DueDateChanged event) {
		log.info("Vencimento alterado: clinicId={} installmentId={} userId={}", event.clinicId(), event.installmentId(),
				event.userId());
	}
}
