package crm_data.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import crm_data.catalog.domain.ProcedureType;
import crm_data.catalog.repository.ProcedureTypeRepository;
import crm_data.catalog.service.ProcedureTypeService.ActiveFilter;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ProblemTypes;
import crm_data.shared.security.Role;
import crm_data.shared.tenant.TenantContext;

class ProcedureTypeServiceTest {

	private final ProcedureTypeRepository procedureTypes = mock(ProcedureTypeRepository.class);
	private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
	private final ProcedureTypeService service = new ProcedureTypeService(procedureTypes, events);

	private final UUID clinicId = UUID.randomUUID();
	private final UUID userId = UUID.randomUUID();

	@BeforeEach
	void setUp() {
		TenantContext.set(clinicId, userId, Role.DENTIST_ADMIN);
		when(procedureTypes.saveAndFlush(any(ProcedureType.class))).thenAnswer(call -> call.getArgument(0));
	}

	@AfterEach
	void clear() {
		TenantContext.clear();
	}

	private ProcedureType existing(long version) {
		ProcedureType procedure = new ProcedureType(UUID.randomUUID(), "Restauração", new BigDecimal("250.00"), (short) 45, "#3B82F6", userId);
		ReflectionTestUtils.setField(procedure, "version", version);
		return procedure;
	}

	@Test
	void createStoresAnActiveProcedureWithTheTokenUserAndPublishesTheEvent() {
		ProcedureType created = service.create("Canal", new BigDecimal("900.00"), (short) 90, "#EF4444");

		ArgumentCaptor<ProcedureType> saved = ArgumentCaptor.forClass(ProcedureType.class);
		verify(procedureTypes).saveAndFlush(saved.capture());
		assertThat(saved.getValue()).isSameAs(created);
		assertThat(created.getName()).isEqualTo("Canal");
		assertThat(created.getBasePrice()).isEqualByComparingTo("900.00");
		assertThat(created.getDefaultDurationMinutes()).isEqualTo((short) 90);
		assertThat(created.getColor()).isEqualTo("#EF4444");
		assertThat(created.isActive()).isTrue();
		assertThat(created.getCreatedBy()).isEqualTo(userId);
		assertThat(created.getId()).isNotNull();
		verify(events).publishEvent(new CatalogEvents.Created(clinicId, created.getId(), userId));
	}

	@Test
	void aDatabaseUniqueViolationOnTheNamePropagatesSoTheGlobalHandlerTurnsItInto409() {
		DataIntegrityViolationException duplicate = new DataIntegrityViolationException("duplicado");
		when(procedureTypes.saveAndFlush(any(ProcedureType.class))).thenThrow(duplicate);

		assertThatThrownBy(() -> service.create("Canal", BigDecimal.TEN, (short) 30, "#EF4444")).isSameAs(duplicate);

		verify(events, never()).publishEvent(any(Object.class));
	}

	@Test
	void updateChangesTheFourFieldsAndKeepsActiveAndAuthor() {
		ProcedureType procedure = existing(3);
		when(procedureTypes.findOwnById(procedure.getId())).thenReturn(Optional.of(procedure));

		ProcedureType updated = service.update(procedure.getId(), "Restauração resina", new BigDecimal("300.00"), (short) 60, "#10B981", 3L);

		assertThat(updated.getName()).isEqualTo("Restauração resina");
		assertThat(updated.getBasePrice()).isEqualByComparingTo("300.00");
		assertThat(updated.getDefaultDurationMinutes()).isEqualTo((short) 60);
		assertThat(updated.getColor()).isEqualTo("#10B981");
		assertThat(updated.isActive()).isTrue();
		assertThat(updated.getCreatedBy()).isEqualTo(userId);
		verify(events).publishEvent(new CatalogEvents.Updated(clinicId, procedure.getId(), userId));
	}

	@Test
	void aStaleVersionOnUpdateOrActiveChangeIs409AndWritesNothing() {
		ProcedureType procedure = existing(3);
		when(procedureTypes.findOwnById(procedure.getId())).thenReturn(Optional.of(procedure));

		assertThatThrownBy(() -> service.update(procedure.getId(), "X1", BigDecimal.ONE, (short) 5, "#000000", 2L))
				.isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.type()).isEqualTo(ProblemTypes.CONFLICT));
		assertThatThrownBy(() -> service.setActive(procedure.getId(), false, 2L))
				.isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.type()).isEqualTo(ProblemTypes.CONFLICT));

		verify(procedureTypes, never()).saveAndFlush(any());
		assertThat(procedure.getName()).isEqualTo("Restauração");
		assertThat(procedure.isActive()).isTrue();
	}

	@Test
	void anUnknownProcedureIs404ForGetUpdateAndActiveChange() {
		UUID unknown = UUID.randomUUID();
		when(procedureTypes.findOwnById(unknown)).thenReturn(Optional.empty());

		for (Runnable call : List.<Runnable>of(() -> service.get(unknown),
				() -> service.update(unknown, "X1", BigDecimal.ONE, (short) 5, "#000000", 0L), () -> service.setActive(unknown, false, 0L))) {
			assertThatThrownBy(call::run)
					.isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.type()).isEqualTo(ProblemTypes.NOT_FOUND));
		}
	}

	@Test
	void setActiveTogglesAndPublishesTheNewState() {
		ProcedureType procedure = existing(0);
		when(procedureTypes.findOwnById(procedure.getId())).thenReturn(Optional.of(procedure));

		ProcedureType inactive = service.setActive(procedure.getId(), false, 0L);

		assertThat(inactive.isActive()).isFalse();
		verify(events).publishEvent(new CatalogEvents.ActiveChanged(clinicId, procedure.getId(), false, userId));
	}

	@Test
	void listUsesTheQueryOfEachFilter() {
		ProcedureType a = existing(0);
		when(procedureTypes.findByActiveOrdered(true)).thenReturn(List.of(a));
		when(procedureTypes.findByActiveOrdered(false)).thenReturn(List.of());
		when(procedureTypes.findAllOrdered()).thenReturn(List.of(a, a));

		assertThat(service.list(ActiveFilter.ACTIVE)).hasSize(1);
		assertThat(service.list(ActiveFilter.INACTIVE)).isEmpty();
		assertThat(service.list(ActiveFilter.ALL)).hasSize(2);
	}

	@Test
	void theEventsNeverCarryNameOrPrice() {
		assertThat(new CatalogEvents.Created(clinicId, UUID.randomUUID(), userId).toString()).doesNotContain("Canal");
		assertThat(new ProcedureQuote(UUID.randomUUID(), "Canal", new BigDecimal("900.00"), true).toString())
				.doesNotContain("Canal", "900");
	}
}
