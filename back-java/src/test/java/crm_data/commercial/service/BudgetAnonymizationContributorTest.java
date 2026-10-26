package crm_data.commercial.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import crm_data.commercial.repository.BudgetDocumentRepository;
import crm_data.commercial.repository.BudgetRepository;

class BudgetAnonymizationContributorTest {

	private final BudgetDocumentRepository documents = mock(BudgetDocumentRepository.class);
	private final BudgetRepository budgets = mock(BudgetRepository.class);
	private final BudgetAnonymizationContributor contributor = new BudgetAnonymizationContributor(documents, budgets);

	@Test
	void objectKeysAreAllTheDocumentKeysOfThePatientIncludingRemovedOnes() {
		UUID patientId = UUID.randomUUID();
		when(documents.findStorageKeysByPatientId(patientId)).thenReturn(List.of("a/b/budgets/1", "a/b/budgets/2"));

		assertThat(contributor.objectKeys(patientId)).containsExactly("a/b/budgets/1", "a/b/budgets/2");
	}

	@Test
	void openItemsCountsDraftAndSentBudgets() {
		UUID patientId = UUID.randomUUID();
		when(budgets.countOpenByPatientId(patientId)).thenReturn(4L);

		assertThat(contributor.openItems(patientId)).isEqualTo(4);
	}
}
