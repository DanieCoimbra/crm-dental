package crm_data.shared.error;

/** Mensagens de conflito (409) compartilhadas, para o cliente receber o mesmo texto em qualquer recurso. */
public final class ConflictMessages {

	/** {@code version} defasada em edição com controle otimista. */
	public static final String STALE_VERSION = "O recurso foi alterado por outra operação. Recarregue e tente novamente.";

	/** Conflito de horário na agenda ({@code ex_appt_no_overlap}); nunca revela o outro agendamento. */
	public static final String SLOT_TAKEN = "Horário já ocupado.";

	/** Agendamento alterado entre a leitura e a gravação (versão defasada ou 0 linhas pela RLS). */
	public static final String APPOINTMENT_CHANGED = "O agendamento foi alterado. Recarregue e tente novamente.";

	// ---- Fase 3: comercial e financeiro (códigos CR006–CR011 do banco) ----

	/** {@code CR006}: anonimizar com parcela em aberto. */
	public static final String OPEN_INSTALLMENTS = "Quite ou cancele as parcelas em aberto antes de anonimizar.";

	/** {@code CR007}: orçamento fora de {@code DRAFT} não aceita mais alteração de conteúdo. */
	public static final String BUDGET_FROZEN = "Orçamento já enviado: não pode mais ser alterado.";

	/** {@code CR008}: status do orçamento inválido para a operação. */
	public static final String BUDGET_STATUS = "Operação inválida para o status do orçamento.";

	/** {@code CR009}: regra da parcela. */
	public static final String INSTALLMENT_RULE = "Operação inválida para o estado da parcela.";

	/** {@code CR010}: regra do pagamento. */
	public static final String PAYMENT_RULE = "Pagamento inválido: valor acima do saldo ou parcela cancelada.";

	/** {@code CR011}: soma das parcelas diferente do total (só aparece no commit). */
	public static final String INSTALLMENTS_SUM = "As parcelas não somam o total do orçamento.";

	/** {@code ux_ptype_clinic_name}. */
	public static final String PROCEDURE_NAME_TAKEN = "Já existe um procedimento ativo com este nome.";

	/** {@code ux_pay_reverses}. */
	public static final String PAYMENT_REVERSED = "Este pagamento já foi estornado.";

	/** {@code ux_inst_budget_number}: dois planos de parcelas simultâneos. */
	public static final String PLAN_CHANGED = "O plano de parcelas foi alterado. Recarregue.";

	/** Estorno de estorno. */
	public static final String REVERSAL_OF_REVERSAL = "Um estorno não pode ser estornado.";

	/** Plano de parcelas já existente para o orçamento. */
	public static final String PLAN_EXISTS = "O orçamento já tem plano de parcelas. Refaça o plano.";

	/** Refazer sem plano ativo. */
	public static final String NO_PLAN = "O orçamento não tem plano de parcelas para refazer.";

	/** Refazer com pagamento líquido positivo. */
	public static final String REVERSE_FIRST = "Estorne os pagamentos antes de refazer o plano.";

	/** Vencimento de parcela paga ou cancelada. */
	public static final String INSTALLMENT_NOT_OPEN = "Só parcelas em aberto podem mudar de vencimento.";

	/** {@code ck_items_line} / {@code ck_budgets_total} que escaparam da validação prévia. */
	public static final String AMOUNT_OUT_OF_RANGE = "Valores do orçamento fora do limite.";

	// ---- Fase 4: documentos clínicos ----

	/** {@code CR012}: emissor sem CRO cadastrado. */
	public static final String PROFESSIONAL_REGISTRY_MISSING = "Cadastre seu CRO e a UF no perfil antes de emitir documentos.";

	/** Anular documento já anulado ou anonimizado. */
	public static final String DOCUMENT_STATE = "Documento já anulado ou anonimizado.";

	/** PDF de documento anonimizado. */
	public static final String DOCUMENT_ANONYMIZED = "Documento anonimizado: o conteúdo não está mais disponível.";

	private ConflictMessages() {
	}
}
