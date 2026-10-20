package crm_data.shared.tenant;

import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Abre uma transação JPA sob um tenant explícito: {@link TenantRunner} define o contexto e o gerenciador de
 * transações do tenant (F02) fixa {@code app.clinic_id}. Para fluxos sem requisição autenticada (registro,
 * refresh, logout), onde o tenant vem do UUID gerado ou do refresh token.
 */
@Component
public class TenantTransactions {

	private final TransactionTemplate template;

	public TenantTransactions(PlatformTransactionManager transactionManager) {
		this.template = new TransactionTemplate(transactionManager);
	}

	public <T> T execute(UUID clinicId, Supplier<T> block) {
		return TenantRunner.call(clinicId, () -> template.execute(status -> block.get()));
	}

	public void run(UUID clinicId, Runnable block) {
		execute(clinicId, () -> {
			block.run();
			return null;
		});
	}
}
