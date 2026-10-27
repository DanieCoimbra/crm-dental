package crm_data.commercial.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

import crm_data.catalog.service.ProcedureQuote;
import crm_data.catalog.service.ProcedureTypeLookup;
import crm_data.commercial.domain.Budget;
import crm_data.commercial.domain.BudgetItem;
import crm_data.commercial.domain.BudgetStatus;
import crm_data.commercial.repository.BudgetItemRepository;
import crm_data.commercial.repository.BudgetRepository;
import crm_data.commercial.service.BudgetDraftValidator.ItemInput;
import crm_data.commercial.service.BudgetService.BudgetView;
import crm_data.commercial.service.BudgetService.DraftInput;
import crm_data.patient.service.PatientBrief;
import crm_data.patient.service.PatientLookup;
import crm_data.scheduling.service.AppointmentLookup;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.FieldValidationException;
import crm_data.shared.error.ProblemTypes;
import crm_data.shared.security.Role;
import crm_data.shared.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

class BudgetServiceTest {

	private final BudgetRepository budgets = mock(BudgetRepository.class);
	private final BudgetItemRepository items = mock(BudgetItemRepository.class);
	private final PatientLookup patients = mock(PatientLookup.class);
	private final AppointmentLookup appointments = mock(AppointmentLookup.class);
	private final ProcedureTypeLookup procedures = mock(ProcedureTypeLookup.class);
	private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
	private final EntityManager entityManager = mock(EntityManager.class);
	private final crm_data.commercial.repository.BudgetDocumentRepository documents = mock(crm_data.commercial.repository.BudgetDocumentRepository.class);
	private final BudgetService service = new BudgetService(budgets, items, patients, appointments, procedures, events, entityManager, documents);

	private final UUID clinicId = UUID.randomUUID();
	private final UUID userId = UUID.randomUUID();
	private final UUID patientId = UUID.randomUUID();
	private final UUID restoration = UUID.randomUUID();
	private final UUID root = UUID.randomUUID();

	@BeforeEach
	void setUp() {
		TenantContext.set(clinicId, userId, Role.DENTIST_ADMIN);
		when(budgets.saveAndFlush(any(Budget.class))).thenAnswer(call -> call.getArgument(0));
		when(procedures.quotes(anyCollection())).thenReturn(Map.of(
				restoration, new ProcedureQuote(restoration, "Restauração", new BigDecimal("250.00"), true),
				root, new ProcedureQuote(root, "Canal", new BigDecimal("900.00"), true)));
		when(patients.briefs(anyCollection())).thenReturn(Map.of(patientId, new PatientBrief(patientId, "Maria Souza", "119")));
		when(items.findByBudgetIdOrderByPositionAsc(any())).thenReturn(List.of());
	}

	@AfterEach
	void clear() {
		TenantContext.clear();
	}

	private Budget stored(BudgetStatus status, long version) {
		Budget budget = new Budget(UUID.randomUUID(), patientId, null, null, null, userId);
		budget.setStatus(status);
		ReflectionTestUtils.setField(budget, "version", version);
		ReflectionTestUtils.setField(budget, "number", 7);
		ReflectionTestUtils.setField(budget, "totalAmount", BigDecimal.ZERO.setScale(2));
		return budget;
	}

	private static DraftInput draft(String discount, ItemInput... inputs) {
		return new DraftInput(null, null, null, new BigDecimal(discount), List.of(inputs));
	}

	private ItemInput item(UUID procedure, int quantity, String discount) {
		return new ItemInput(procedure, quantity, new BigDecimal(discount));
	}

	// ---- criar ----

	@Test
	void createWritesTheHeaderThenTheItemsThenTheDiscountFlushingBetweenSteps() {
		Budget stored = stored(BudgetStatus.DRAFT, 0);
		when(budgets.findOwnById(any())).thenReturn(Optional.of(stored));

		BudgetView view = service.create(patientId, draft("50.00", item(restoration, 2, "0.00"), item(root, 1, "100.00")));

		InOrder order = inOrder(patients, budgets, items, entityManager);
		order.verify(patients).requireSchedulable(patientId);
		ArgumentCaptor<Budget> header = ArgumentCaptor.forClass(Budget.class);
		order.verify(budgets).saveAndFlush(header.capture());
		order.verify(items).saveAll(any());
		order.verify(entityManager).flush();
		order.verify(budgets).saveAndFlush(header.getValue()); // o desconto entra por UPDATE, depois dos itens (S7)
		assertThat(header.getValue().getStatus()).isEqualTo(BudgetStatus.DRAFT);
		assertThat(header.getValue().getDiscountAmount()).isEqualByComparingTo("50.00");
		assertThat(header.getValue().getPatientId()).isEqualTo(patientId);
		assertThat(header.getValue().getCreatedBy()).isEqualTo(userId);
		assertThat(view.patient().name()).isEqualTo("Maria Souza");
		assertThat(view.documents()).isEmpty();
	}

	@Test
	void theHeaderIsInsertedWithoutDiscountAndWithoutASecondUpdateWhenThereIsNoDiscount() {
		when(budgets.findOwnById(any())).thenReturn(Optional.of(stored(BudgetStatus.DRAFT, 0)));

		service.create(patientId, draft("0.00", item(restoration, 1, "0.00")));

		ArgumentCaptor<Budget> header = ArgumentCaptor.forClass(Budget.class);
		verify(budgets).saveAndFlush(header.capture()); // uma única gravação do cabeçalho
		assertThat(header.getValue().getDiscountAmount()).isEqualByComparingTo("0.00");
	}

	@Test
	void theItemsGetPositionsOneToNAndTheDatabaseOwnedFieldsAreNeverSet() {
		when(budgets.findOwnById(any())).thenReturn(Optional.of(stored(BudgetStatus.DRAFT, 0)));

		service.create(patientId, draft("0.00", item(restoration, 2, "10.00"), item(root, 3, "0.00"), item(restoration, 1, "0.00")));

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<BudgetItem>> saved = ArgumentCaptor.forClass(List.class);
		verify(items).saveAll(saved.capture());
		assertThat(saved.getValue()).extracting(i -> (int) i.getPosition()).containsExactly(1, 2, 3);
		assertThat(saved.getValue()).extracting(i -> (int) i.getQuantity()).containsExactly(2, 3, 1);
		assertThat(saved.getValue()).allSatisfy(i -> {
			assertThat(i.getUnitPrice()).isNull();
			assertThat(i.getDescription()).isNull();
			assertThat(i.getLineTotal()).isNull();
			assertThat(i.isNew()).isTrue();
		});
	}

	@Test
	void createRelooksTheBudgetAfterClearingThePersistenceContext() {
		when(budgets.findOwnById(any())).thenReturn(Optional.of(stored(BudgetStatus.DRAFT, 0)));

		service.create(patientId, draft("0.00", item(restoration, 1, "0.00")));

		InOrder order = inOrder(entityManager, budgets);
		order.verify(entityManager).clear();
		order.verify(budgets).findOwnById(any());
	}

	@Test
	void theAppointmentMustBelongToThePatient() {
		UUID appointmentId = UUID.randomUUID();
		doThrow(new BusinessException(ProblemTypes.CONFLICT, "Atendimento de outro paciente.")).when(appointments)
				.requireOfPatient(appointmentId, patientId);

		assertThatThrownBy(() -> service.create(patientId,
				new DraftInput(appointmentId, null, null, BigDecimal.ZERO.setScale(2), List.of(item(restoration, 1, "0.00")))))
						.isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.type()).isEqualTo(ProblemTypes.CONFLICT));

		verify(budgets, never()).saveAndFlush(any());
	}

	@Test
	void noAppointmentMeansNoLookup() {
		when(budgets.findOwnById(any())).thenReturn(Optional.of(stored(BudgetStatus.DRAFT, 0)));

		service.create(patientId, draft("0.00", item(restoration, 1, "0.00")));

		verifyNoInteractions(appointments);
	}

	@Test
	void anIneligiblePatientStopsBeforeAnyWrite() {
		doThrow(new BusinessException(ProblemTypes.CONFLICT, "Paciente inativo.")).when(patients).requireSchedulable(patientId);

		assertThatThrownBy(() -> service.create(patientId, draft("0.00", item(restoration, 1, "0.00")))).isInstanceOf(BusinessException.class);

		verifyNoInteractions(budgets, items, procedures);
	}

	@Test
	void anUnknownProcedureIs404AndAnInactiveOneIs422WithTheReason() {
		UUID unknown = UUID.randomUUID();
		UUID inactive = UUID.randomUUID();
		when(procedures.quotes(anyCollection())).thenReturn(Map.of(restoration, new ProcedureQuote(restoration, "R", BigDecimal.TEN, true),
				inactive, new ProcedureQuote(inactive, "I", BigDecimal.TEN, false)));

		assertThatThrownBy(() -> service.create(patientId, draft("0.00", item(unknown, 1, "0.00"))))
				.isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.type()).isEqualTo(ProblemTypes.NOT_FOUND));
		assertThatThrownBy(() -> service.create(patientId, draft("0.00", item(restoration, 1, "0.00"), item(inactive, 1, "0.00"))))
				.isInstanceOfSatisfying(BusinessException.class, ex -> {
					assertThat(ex.type()).isEqualTo(ProblemTypes.BUSINESS_RULE);
					assertThat(ex.reason()).isEqualTo("PROCEDURE_INACTIVE");
				});

		verify(budgets, never()).saveAndFlush(any());
	}

	@Test
	void valueErrorsComeAsAFieldListAndNothingIsWritten() {
		assertThatThrownBy(() -> service.create(patientId, draft("9999.00", item(restoration, 1, "999.00"))))
				.isInstanceOfSatisfying(FieldValidationException.class, ex -> assertThat(ex.errors()).extracting(e -> e.field())
						.containsExactly("items[0].discountAmount", "discountAmount"));

		verify(budgets, never()).saveAndFlush(any());
		verifyNoInteractions(items);
	}

	@Test
	void createPublishesTheEventWithOnlyIds() {
		Budget stored = stored(BudgetStatus.DRAFT, 0);
		when(budgets.findOwnById(any())).thenReturn(Optional.of(stored));

		service.create(patientId, draft("0.00", item(restoration, 1, "0.00")));

		ArgumentCaptor<CommercialEvents.BudgetCreated> event = ArgumentCaptor.forClass(CommercialEvents.BudgetCreated.class);
		verify(events).publishEvent(event.capture());
		assertThat(event.getValue().clinicId()).isEqualTo(clinicId);
		assertThat(event.getValue().patientId()).isEqualTo(patientId);
		assertThat(event.getValue().userId()).isEqualTo(userId);
	}

	// ---- editar ----

	@Test
	void updateReplacesTheItemsZeroingTheOldDiscountFirstAndWritingTheFinalOneLast() {
		Budget budget = stored(BudgetStatus.DRAFT, 3);
		budget.setDiscountAmount(new BigDecimal("300.00"));
		BudgetItem old = new BudgetItem(UUID.randomUUID(), budget.getId(), restoration, (short) 1, BigDecimal.ZERO, (short) 1);
		when(budgets.findForUpdateById(budget.getId())).thenReturn(Optional.of(budget));
		when(items.findByBudgetIdOrderByPositionAsc(budget.getId())).thenReturn(List.of(old));
		when(budgets.findOwnById(budget.getId())).thenReturn(Optional.of(budget));
		List<BigDecimal> discountsAtFlush = new java.util.ArrayList<>();
		org.mockito.Mockito.doAnswer(call -> {
			discountsAtFlush.add(budget.getDiscountAmount());
			return null;
		}).when(entityManager).flush();

		service.update(budget.getId(), draft("20.00", item(root, 1, "0.00")), 3L);

		InOrder order = inOrder(entityManager, items);
		order.verify(entityManager).lock(budget, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
		order.verify(entityManager).flush(); // desconto antigo zerado
		order.verify(items).deleteAll(List.of(old));
		order.verify(entityManager).flush(); // itens antigos fora
		order.verify(items).saveAll(any());
		assertThat(discountsAtFlush.get(0)).as("primeiro flush: desconto zerado").isEqualByComparingTo("0.00");
		assertThat(discountsAtFlush.get(discountsAtFlush.size() - 1)).as("último flush: desconto final").isEqualByComparingTo("20.00");
	}

	@Test
	void updateWithoutAnOldDiscountSkipsTheZeroingFlush() {
		Budget budget = stored(BudgetStatus.DRAFT, 0);
		when(budgets.findForUpdateById(budget.getId())).thenReturn(Optional.of(budget));
		when(budgets.findOwnById(budget.getId())).thenReturn(Optional.of(budget));

		service.update(budget.getId(), draft("0.00", item(root, 1, "0.00")), 0L);

		// exclui os itens, grava os novos, grava o cabeçalho e o flush do reload; sem o flush extra que zera o desconto antigo
		verify(entityManager, org.mockito.Mockito.times(4)).flush();
	}

	@Test
	void updateCopiesTheEditableHeaderFieldsAndNeverTheOwner() {
		Budget budget = stored(BudgetStatus.DRAFT, 0);
		UUID appointmentId = UUID.randomUUID();
		when(budgets.findForUpdateById(budget.getId())).thenReturn(Optional.of(budget));
		when(budgets.findOwnById(budget.getId())).thenReturn(Optional.of(budget));

		service.update(budget.getId(), new DraftInput(appointmentId, LocalDate.of(2026, 12, 15), "Em 6x", new BigDecimal("10.00"),
				List.of(item(restoration, 1, "0.00"))), 0L);

		assertThat(budget.getAppointmentId()).isEqualTo(appointmentId);
		assertThat(budget.getValidUntil()).isEqualTo(LocalDate.of(2026, 12, 15));
		assertThat(budget.getNotes()).isEqualTo("Em 6x");
		assertThat(budget.getPatientId()).isEqualTo(patientId);
		verify(appointments).requireOfPatient(appointmentId, patientId);
	}

	@Test
	void updateOfAStaleVersionOrNonDraftStatusIs409WithoutTouchingTheItems() {
		Budget stale = stored(BudgetStatus.DRAFT, 5);
		Budget sent = stored(BudgetStatus.SENT, 0);
		when(budgets.findForUpdateById(stale.getId())).thenReturn(Optional.of(stale));
		when(budgets.findForUpdateById(sent.getId())).thenReturn(Optional.of(sent));

		assertThatThrownBy(() -> service.update(stale.getId(), draft("0.00", item(root, 1, "0.00")), 4L))
				.isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.type()).isEqualTo(ProblemTypes.CONFLICT));
		assertThatThrownBy(() -> service.update(sent.getId(), draft("0.00", item(root, 1, "0.00")), 0L))
				.isInstanceOfSatisfying(BusinessException.class, ex -> {
					assertThat(ex.type()).isEqualTo(ProblemTypes.CONFLICT);
					assertThat(ex.getMessage()).contains("SENT");
				});

		verify(items, never()).deleteAll(any());
		verify(items, never()).saveAll(any());
	}

	@Test
	void updateOfAnUnknownBudgetIs404() {
		UUID unknown = UUID.randomUUID();
		when(budgets.findForUpdateById(unknown)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.update(unknown, draft("0.00", item(root, 1, "0.00")), 0L))
				.isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.type()).isEqualTo(ProblemTypes.NOT_FOUND));
	}

	// ---- descartar ----

	@Test
	void discardTurnsADraftIntoRejectedAndPublishesTheEvent() {
		Budget budget = stored(BudgetStatus.DRAFT, 2);
		when(budgets.findForUpdateById(budget.getId())).thenReturn(Optional.of(budget));
		when(budgets.findOwnById(budget.getId())).thenReturn(Optional.of(budget));

		service.discard(budget.getId(), 2L);

		assertThat(budget.getStatus()).isEqualTo(BudgetStatus.REJECTED);
		verify(budgets).saveAndFlush(budget);
		verify(events).publishEvent(new CommercialEvents.BudgetDiscarded(clinicId, budget.getId(), userId));
	}

	@Test
	void discardOfANonDraftOrStaleBudgetIs409AndChangesNothing() {
		Budget sent = stored(BudgetStatus.SENT, 1);
		Budget stale = stored(BudgetStatus.DRAFT, 1);
		when(budgets.findForUpdateById(sent.getId())).thenReturn(Optional.of(sent));
		when(budgets.findForUpdateById(stale.getId())).thenReturn(Optional.of(stale));

		assertThatThrownBy(() -> service.discard(sent.getId(), 1L)).isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> service.discard(stale.getId(), 0L)).isInstanceOf(BusinessException.class);

		assertThat(sent.getStatus()).isEqualTo(BudgetStatus.SENT);
		assertThat(stale.getStatus()).isEqualTo(BudgetStatus.DRAFT);
		verify(budgets, never()).saveAndFlush(any());
	}

	// ---- leitura ----

	@Test
	void getReturnsTheBudgetItemsAndThePatient() {
		Budget budget = stored(BudgetStatus.SENT, 1);
		BudgetItem item = new BudgetItem(UUID.randomUUID(), budget.getId(), restoration, (short) 1, BigDecimal.ZERO, (short) 1);
		when(budgets.findOwnById(budget.getId())).thenReturn(Optional.of(budget));
		when(items.findByBudgetIdOrderByPositionAsc(budget.getId())).thenReturn(List.of(item));

		BudgetView view = service.get(budget.getId());

		assertThat(view.items()).containsExactly(item);
		assertThat(view.patient().name()).isEqualTo("Maria Souza");
	}

	@Test
	void getOfAnUnknownBudgetIs404() {
		UUID unknown = UUID.randomUUID();
		when(budgets.findOwnById(unknown)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.get(unknown))
				.isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.type()).isEqualTo(ProblemTypes.NOT_FOUND));
	}

	@Test
	@SuppressWarnings("unchecked")
	void listPagesNewestFirstAndResolvesThePatientsInOneCall() {
		Budget a = stored(BudgetStatus.DRAFT, 0);
		Budget b = stored(BudgetStatus.SENT, 0);
		when(budgets.findAll(any(Specification.class), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(a, b)));

		var page = service.list(Set.of(BudgetStatus.DRAFT), patientId, 7, 1, 20);

		assertThat(page.getContent()).hasSize(2);
		assertThat(page.getContent()).allSatisfy(s -> assertThat(s.patient().name()).isEqualTo("Maria Souza"));
		ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
		verify(budgets).findAll(any(Specification.class), pageable.capture());
		assertThat(pageable.getValue().getPageNumber()).isEqualTo(1);
		assertThat(pageable.getValue().getPageSize()).isEqualTo(20);
		assertThat(pageable.getValue().getSort().toString()).isEqualTo("createdAt: DESC,id: DESC");
		verify(patients).briefs(List.of(patientId));
	}

	@Test
	@SuppressWarnings("unchecked")
	void historyRequiresAnExistingPatientAndStopsBeforeTheQuery() {
		doThrow(new BusinessException(ProblemTypes.NOT_FOUND, "Paciente não encontrado.")).when(patients).requireExists(patientId);

		assertThatThrownBy(() -> service.historyOf(patientId, 0, 20)).isInstanceOf(BusinessException.class);

		verify(budgets, never()).findAll(any(Specification.class), any(Pageable.class));
		verify(patients).requireExists(eq(patientId));
	}
}
