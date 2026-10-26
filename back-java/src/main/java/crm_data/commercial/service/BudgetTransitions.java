package crm_data.commercial.service;

import crm_data.commercial.domain.BudgetStatus;

/**
 * Tabela pura da máquina de estados do orçamento (sem Spring): cada ação tem origens permitidas e destino. O banco só
 * impõe terminalidade e congelamento; esta tabela é mais restritiva por decisão do produto (H3: aprovar e recusar só a
 * partir de {@code SENT}; descartar rascunho é {@code DRAFT → REJECTED} pela rota própria). Todas as ações são do
 * dentista (a recepção só lê).
 *
 * <pre>
 * EDIT                 DRAFT                      → DRAFT
 * DISCARD              DRAFT                      → REJECTED
 * SEND                 DRAFT                      → SENT
 * APPROVE              SENT                       → APPROVED
 * REJECT               SENT                       → REJECTED
 * REGENERATE_DOCUMENT  SENT, APPROVED, REJECTED   → (mesmo status)
 * PLAN                 APPROVED                   → (mesmo status)
 * </pre>
 */
public final class BudgetTransitions {

	public enum Action {
		EDIT, DISCARD, SEND, APPROVE, REJECT, REGENERATE_DOCUMENT, PLAN
	}

	private BudgetTransitions() {
	}

	/** A ação pode ser feita a partir deste status? */
	public static boolean allowedFrom(Action action, BudgetStatus from) {
		return switch (action) {
			case EDIT, DISCARD, SEND -> from == BudgetStatus.DRAFT;
			case APPROVE, REJECT -> from == BudgetStatus.SENT;
			case REGENERATE_DOCUMENT -> from == BudgetStatus.SENT || from == BudgetStatus.APPROVED || from == BudgetStatus.REJECTED;
			case PLAN -> from == BudgetStatus.APPROVED;
		};
	}

	/** Status de destino; {@code null} quando a ação não muda o status (nova revisão do PDF e plano de parcelas). */
	public static BudgetStatus target(Action action) {
		return switch (action) {
			case EDIT -> BudgetStatus.DRAFT;
			case DISCARD, REJECT -> BudgetStatus.REJECTED;
			case SEND -> BudgetStatus.SENT;
			case APPROVE -> BudgetStatus.APPROVED;
			case REGENERATE_DOCUMENT, PLAN -> null;
		};
	}

	/** Mensagem (409) de uma ação recusada, com o status atual (valor do enum, sem dado pessoal). */
	public static String refusal(Action action, BudgetStatus from) {
		if (action == Action.APPROVE && from == BudgetStatus.DRAFT) {
			return "Envie o orçamento antes de aprová-lo.";
		}
		if (action == Action.REJECT && from == BudgetStatus.DRAFT) {
			return "Use descartar para rascunhos.";
		}
		if (action == Action.REGENERATE_DOCUMENT && from == BudgetStatus.DRAFT) {
			return "Envie o orçamento antes de gerar o PDF.";
		}
		if (action == Action.PLAN && from != BudgetStatus.APPROVED) {
			return "Aprove o orçamento antes de gerar as parcelas.";
		}
		return "O orçamento está " + from + " e não aceita esta ação.";
	}
}
