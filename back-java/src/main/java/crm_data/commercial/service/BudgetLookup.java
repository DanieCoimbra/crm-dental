package crm_data.commercial.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Interface pública do módulo {@code commercial} para {@code finance}: lê orçamentos sem expor repositório nem entidade.
 * A F03 trouxe o resumo em lote; a F05 acrescenta {@code requireApproved}, {@code lockApproved} e {@code totalOf}; a F06,
 * as consultas de faturamento.
 */
public interface BudgetLookup {

	/** Id, número e paciente em lote (uma consulta). Id desconhecido ou de outra clínica não aparece no mapa. */
	Map<UUID, BudgetBrief> brief(Collection<UUID> budgetIds);

	/**
	 * Total do orçamento, em qualquer status.
	 *
	 * @throws crm_data.shared.error.BusinessException {@code not-found} (404) se não existe na clínica
	 */
	BigDecimal totalOf(UUID budgetId);

	/**
	 * Orçamento {@code APPROVED}, sem travar.
	 *
	 * @throws crm_data.shared.error.BusinessException {@code not-found} (404); {@code conflict} (409) se não está aprovado
	 */
	ApprovedBudget requireApproved(UUID budgetId);

	/**
	 * Como {@link #requireApproved}, mas <strong>trava a linha do orçamento</strong> até o fim da transação do chamador:
	 * serializa dois planos de parcelas simultâneos sobre o mesmo orçamento. Exige transação ativa.
	 */
	ApprovedBudget lockApproved(UUID budgetId);

	// ---- F06: faturamento (competência) e ficha do paciente ----

	/** Orçamentos aprovados com {@code decidedAt} em {@code [ini, fim)}, do mais antigo ao mais novo, até {@code limit}. */
	List<ApprovedBudget> approvedBetween(Instant ini, Instant fim, int limit);

	/** Quantidade de aprovados em {@code [ini, fim)} (para saber se a lista foi truncada). */
	long approvedCount(Instant ini, Instant fim);

	/** Soma do total dos aprovados em {@code [ini, fim)}. */
	BigDecimal approvedTotal(Instant ini, Instant fim);

	/** Aprovados do paciente, do mais recente ao mais antigo, até {@code limit}. */
	List<ApprovedBudget> approvedOfPatient(UUID patientId, int limit);

	/** Soma do total de todos os aprovados do paciente (agregação, nunca pela lista limitada). */
	BigDecimal approvedTotalOfPatient(UUID patientId);
}
