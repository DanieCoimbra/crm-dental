package crm_data.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Um rollback não pode deixar log de auditoria de algo que não aconteceu: os ouvintes rodam só após o commit. */
class AuditLoggersAfterCommitTest {

	@Test
	void everyAuditListenerWaitsForTheCommitButStillRunsOutsideTransactions() throws Exception {
		for (String className : List.of("crm_data.auth.service.AuthAuditLogger", "crm_data.clinic.service.ClinicAuditLogger")) {
			Class<?> logger = Class.forName(className);
			List<Method> listeners = Arrays.stream(logger.getDeclaredMethods()).filter(m -> m.getName().equals("on")).toList();

			assertThat(listeners).as(className).isNotEmpty();
			for (Method method : listeners) {
				TransactionalEventListener annotation = method.getAnnotation(TransactionalEventListener.class);
				assertThat(annotation).as("%s#%s", className, method).isNotNull();
				assertThat(annotation.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
				assertThat(annotation.fallbackExecution()).as("eventos publicados fora de transação não podem se perder").isTrue();
			}
		}
	}
}
