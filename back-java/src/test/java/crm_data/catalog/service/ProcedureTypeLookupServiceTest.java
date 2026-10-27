package crm_data.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import crm_data.catalog.domain.ProcedureType;
import crm_data.catalog.repository.ProcedureTypeRepository;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ProblemTypes;

class ProcedureTypeLookupServiceTest {

	private final ProcedureTypeRepository procedureTypes = mock(ProcedureTypeRepository.class);
	private final ProcedureTypeLookupService lookup = new ProcedureTypeLookupService(procedureTypes);

	private ProcedureType procedure(String name, String price, boolean active) {
		ProcedureType procedure = new ProcedureType(UUID.randomUUID(), name, new BigDecimal(price), (short) 30, "#3B82F6", UUID.randomUUID());
		procedure.setActive(active);
		ReflectionTestUtils.setField(procedure, "version", 0L);
		return procedure;
	}

	@Test
	void requireActivePassesForAnActiveProcedure() {
		ProcedureType active = procedure("Limpeza", "150.00", true);
		when(procedureTypes.findOwnById(active.getId())).thenReturn(Optional.of(active));

		lookup.requireActive(active.getId());
	}

	@Test
	void requireActiveIs404ForAnUnknownOrForeignProcedure() {
		UUID unknown = UUID.randomUUID();
		when(procedureTypes.findOwnById(unknown)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> lookup.requireActive(unknown)).isInstanceOfSatisfying(BusinessException.class, ex -> {
			assertThat(ex.type()).isEqualTo(ProblemTypes.NOT_FOUND);
			assertThat(ex.reason()).isNull();
		});
	}

	@Test
	void requireActiveIs422WithTheStableReasonForAnInactiveProcedure() {
		ProcedureType inactive = procedure("Limpeza", "150.00", false);
		when(procedureTypes.findOwnById(inactive.getId())).thenReturn(Optional.of(inactive));

		assertThatThrownBy(() -> lookup.requireActive(inactive.getId())).isInstanceOfSatisfying(BusinessException.class, ex -> {
			assertThat(ex.type()).isEqualTo(ProblemTypes.BUSINESS_RULE);
			assertThat(ex.reason()).isEqualTo("PROCEDURE_INACTIVE");
		});
	}

	@Test
	void briefsAndQuotesUseOneQueryEachAndSkipUnknownIds() {
		ProcedureType a = procedure("Limpeza", "150.00", true);
		ProcedureType b = procedure("Canal", "900.00", false);
		UUID unknown = UUID.randomUUID();
		when(procedureTypes.findAllByIds(Set.of(a.getId(), b.getId(), unknown))).thenReturn(List.of(a, b));

		var briefs = lookup.briefs(Set.of(a.getId(), b.getId(), unknown));
		var quotes = lookup.quotes(Set.of(a.getId(), b.getId(), unknown));

		assertThat(briefs).containsOnlyKeys(a.getId(), b.getId());
		assertThat(briefs.get(a.getId())).isEqualTo(new ProcedureBrief(a.getId(), "Limpeza", "#3B82F6"));
		assertThat(quotes).containsOnlyKeys(a.getId(), b.getId());
		assertThat(quotes.get(b.getId()).basePrice()).isEqualByComparingTo("900.00");
		assertThat(quotes.get(b.getId()).active()).isFalse();
		org.mockito.Mockito.verify(procedureTypes, org.mockito.Mockito.times(2)).findAllByIds(Set.of(a.getId(), b.getId(), unknown));
	}

	@Test
	void emptyInputNeverHitsTheRepositoryAndReturnsEmptyMaps() {
		assertThat(lookup.briefs(List.of())).isEmpty();
		assertThat(lookup.quotes(List.of())).isEmpty();

		verifyNoInteractions(procedureTypes);
	}

	@Test
	void theQuoteToStringNeverPrintsNameOrPrice() {
		ProcedureQuote quote = new ProcedureQuote(UUID.randomUUID(), "Canal", new BigDecimal("900.00"), true);

		assertThat(quote.toString()).doesNotContain("Canal", "900").contains(quote.id().toString());
		verify(procedureTypes, org.mockito.Mockito.never()).findAllByIds(Set.of());
	}
}
