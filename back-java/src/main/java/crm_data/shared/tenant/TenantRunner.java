package crm_data.shared.tenant;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Executa um bloco sob um tenant explícito, para tarefas fora de requisição (assíncronas, agendadas, registro de
 * clínica). Sem isto, a tarefa não tem tenant e qualquer transação falha com 401. O contexto anterior é sempre
 * restaurado ao final.
 */
public final class TenantRunner {

	private TenantRunner() {
	}

	public static void run(UUID clinicId, Runnable block) {
		Objects.requireNonNull(block, "block");
		call(clinicId, () -> {
			block.run();
			return null;
		});
	}

	public static <T> T call(UUID clinicId, Supplier<T> block) {
		Objects.requireNonNull(clinicId, "clinicId");
		Objects.requireNonNull(block, "block");

		TenantContext.Snapshot previous = TenantContext.snapshot();
		if (previous != null && previous.clinicId().equals(clinicId)) {
			return block.get(); // já estamos neste tenant: preserva usuário e papel
		}
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			// o app.clinic_id da transação em curso já foi fixado no banco e não pode mudar no meio dela
			throw new IllegalStateException("Não é possível trocar de tenant dentro de uma transação em andamento.");
		}
		TenantContext.set(clinicId, null, null);
		try {
			return block.get();
		}
		finally {
			TenantContext.restore(previous);
		}
	}
}
