package crm_data.commercial.service;

import java.util.UUID;

/** Eventos internos, em memória, do módulo comercial. Só identificadores e o status: nunca valor, nome ou observação. */
public final class CommercialEvents {

	private CommercialEvents() {
	}

	/** {@code Budget.Created}: rascunho criado (publicado depois do commit). */
	public record BudgetCreated(UUID clinicId, UUID budgetId, UUID patientId, UUID userId) {
	}

	/** {@code Budget.Updated}: rascunho editado. */
	public record BudgetUpdated(UUID clinicId, UUID budgetId, UUID userId) {
	}

	/** {@code Budget.Discarded}: rascunho descartado ({@code DRAFT → REJECTED}). */
	public record BudgetDiscarded(UUID clinicId, UUID budgetId, UUID userId) {
	}

	/** {@code Budget.Approved} / {@code Budget.Rejected}: decisão do paciente sobre o orçamento enviado. {@code status}: o destino. */
	public record BudgetDecided(UUID clinicId, UUID budgetId, String status, UUID userId) {
	}

	/** {@code Budget.Sent}: orçamento enviado ({@code DRAFT → SENT}) com o PDF da revisão 1 registrado. */
	public record BudgetSent(UUID clinicId, UUID budgetId, UUID patientId, int revision, UUID userId) {
	}

	/** {@code Budget.DocumentGenerated}: nova revisão do PDF, sem mudar o status. */
	public record DocumentGenerated(UUID clinicId, UUID budgetId, int revision, UUID userId) {
	}

	/**
	 * {@code Budget.StorageCompensationFailed}: a transação do envio falhou e não foi possível apagar o objeto gravado no
	 * Storage; sobra um objeto órfão para limpeza manual. Só o orçamento: a chave nunca vai para log nem evento.
	 */
	public record StorageCompensationFailed(UUID clinicId, UUID budgetId) {
	}
}
