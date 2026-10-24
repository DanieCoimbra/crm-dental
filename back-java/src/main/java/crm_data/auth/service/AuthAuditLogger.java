package crm_data.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import crm_data.auth.service.AuthEvents.ClinicRegistered;
import crm_data.auth.service.AuthEvents.RefreshReuseDetected;
import crm_data.auth.service.AuthEvents.UserSessionsRevoked;
import crm_data.shared.security.AccessTokenRevocations;
import lombok.RequiredArgsConstructor;

/**
 * Registro de auditoria dos eventos de autenticação: somente identificadores, nunca tokens, senhas ou e-mails. Roda
 * depois do commit (ou na hora, se o evento for publicado fora de transação): um rollback não deixa log de algo que
 * não aconteceu.
 */
@Component
@RequiredArgsConstructor
class AuthAuditLogger {

	private static final Logger log = LoggerFactory.getLogger("crm_data.auth.audit");

	private final AccessTokenRevocations revocations;

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	void on(ClinicRegistered event) {
		log.info("Clínica registrada: clinicId={} userId={}", event.clinicId(), event.userId());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	void on(RefreshReuseDetected event) {
		log.warn("Reuso de refresh token detectado; família revogada: userId={} familyId={}", event.userId(), event.familyId());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	void on(UserSessionsRevoked event) {
		revocations.revoke(event.userId()); // derruba também os access tokens ainda vivos (até 15 min)
		log.info("Sessões do usuário revogadas: userId={}", event.userId());
	}
}
