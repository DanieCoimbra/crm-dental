package crm_data.commercial.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import crm_data.commercial.domain.BudgetStatus;
import crm_data.commercial.service.BudgetTransitions.Action;

class BudgetTransitionsTest {

	/** A tabela inteira da seção 5 do PRD global: ação × status de origem. */
	@ParameterizedTest
	@CsvSource({ "EDIT,DRAFT,true", "EDIT,SENT,false", "EDIT,APPROVED,false", "EDIT,REJECTED,false",
			"DISCARD,DRAFT,true", "DISCARD,SENT,false", "DISCARD,APPROVED,false", "DISCARD,REJECTED,false",
			"SEND,DRAFT,true", "SEND,SENT,false", "SEND,APPROVED,false", "SEND,REJECTED,false",
			"APPROVE,DRAFT,false", "APPROVE,SENT,true", "APPROVE,APPROVED,false", "APPROVE,REJECTED,false",
			"REJECT,DRAFT,false", "REJECT,SENT,true", "REJECT,APPROVED,false", "REJECT,REJECTED,false",
			"REGENERATE_DOCUMENT,DRAFT,false", "REGENERATE_DOCUMENT,SENT,true", "REGENERATE_DOCUMENT,APPROVED,true",
			"REGENERATE_DOCUMENT,REJECTED,true",
			"PLAN,DRAFT,false", "PLAN,SENT,false", "PLAN,APPROVED,true", "PLAN,REJECTED,false" })
	void theTransitionTableMatchesThePrd(Action action, BudgetStatus from, boolean allowed) {
		assertThat(BudgetTransitions.allowedFrom(action, from)).isEqualTo(allowed);
	}

	@Test
	void everyActionAndStatusPairIsCovered() {
		int pairs = Action.values().length * BudgetStatus.values().length;

		assertThat(pairs).isEqualTo(28);
	}

	@ParameterizedTest
	@CsvSource({ "EDIT,DRAFT", "DISCARD,REJECTED", "REJECT,REJECTED", "SEND,SENT", "APPROVE,APPROVED" })
	void actionsThatChangeTheStatusHaveATarget(Action action, BudgetStatus target) {
		assertThat(BudgetTransitions.target(action)).isEqualTo(target);
	}

	@Test
	void theDocumentAndThePlanDoNotChangeTheStatus() {
		assertThat(BudgetTransitions.target(Action.REGENERATE_DOCUMENT)).isNull();
		assertThat(BudgetTransitions.target(Action.PLAN)).isNull();
	}

	@Test
	void terminalStatusesAllowNothingThatChangesTheBudget() {
		for (Action action : new Action[] { Action.EDIT, Action.DISCARD, Action.SEND, Action.APPROVE, Action.REJECT }) {
			assertThat(BudgetTransitions.allowedFrom(action, BudgetStatus.APPROVED)).as("%s a partir de APPROVED", action).isFalse();
			assertThat(BudgetTransitions.allowedFrom(action, BudgetStatus.REJECTED)).as("%s a partir de REJECTED", action).isFalse();
		}
	}

	@Test
	void onlyTheFlowsFromDraftOrSentChangeTheStatus() {
		for (Action action : Action.values()) {
			if (BudgetTransitions.target(action) != null && BudgetTransitions.target(action) != BudgetStatus.DRAFT) {
				assertThat(BudgetTransitions.allowedFrom(action, BudgetStatus.DRAFT) || BudgetTransitions.allowedFrom(action, BudgetStatus.SENT))
						.as("%s", action).isTrue();
			}
		}
	}

	@Test
	void theRefusalMessagesAreSpecificForTheCommonMistakes() {
		assertThat(BudgetTransitions.refusal(Action.APPROVE, BudgetStatus.DRAFT)).isEqualTo("Envie o orçamento antes de aprová-lo.");
		assertThat(BudgetTransitions.refusal(Action.REJECT, BudgetStatus.DRAFT)).isEqualTo("Use descartar para rascunhos.");
		assertThat(BudgetTransitions.refusal(Action.REGENERATE_DOCUMENT, BudgetStatus.DRAFT)).isEqualTo("Envie o orçamento antes de gerar o PDF.");
		assertThat(BudgetTransitions.refusal(Action.PLAN, BudgetStatus.SENT)).isEqualTo("Aprove o orçamento antes de gerar as parcelas.");
		assertThat(BudgetTransitions.refusal(Action.EDIT, BudgetStatus.SENT)).isEqualTo("O orçamento está SENT e não aceita esta ação.");
	}
}
