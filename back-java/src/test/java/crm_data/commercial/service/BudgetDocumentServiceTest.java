package crm_data.commercial.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import crm_data.clinic.service.ClinicIdentity;
import crm_data.clinic.service.ClinicIdentity.ClinicInfo;
import crm_data.clinic.service.ClinicZones;
import crm_data.commercial.domain.Budget;
import crm_data.commercial.domain.BudgetDocument;
import crm_data.commercial.domain.BudgetItem;
import crm_data.commercial.domain.BudgetStatus;
import crm_data.commercial.repository.BudgetDocumentRepository;
import crm_data.commercial.repository.BudgetItemRepository;
import crm_data.commercial.repository.BudgetRepository;
import crm_data.commercial.service.BudgetDocumentService.DownloadUrl;
import crm_data.commercial.service.BudgetService.BudgetView;
import crm_data.commercial.service.BudgetService.DocumentSummary;
import crm_data.patient.service.PatientBrief;
import crm_data.patient.service.PatientLookup;
import crm_data.shared.config.CrmProperties;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.BusyException;
import crm_data.shared.error.ProblemTypes;
import crm_data.shared.security.Role;
import crm_data.shared.storage.FileStorage;
import crm_data.shared.storage.StorageException;
import crm_data.shared.storage.StorageKeys;
import crm_data.shared.tenant.TenantContext;
import jakarta.persistence.EntityManager;

/**
 * O fluxo atômico do envio (H1) com cada ponto de falha injetado: Storage, gravação do metadado, commit (resultado
 * incerto) e a própria compensação. Em todos os casos a vaga é devolvida e o erro original chega ao cliente.
 */
class BudgetDocumentServiceTest {

	private static final Instant NOW = Instant.parse("2026-10-27T15:00:00Z");

	private final BudgetRepository budgets = mock(BudgetRepository.class);
	private final BudgetItemRepository items = mock(BudgetItemRepository.class);
	private final BudgetDocumentRepository documents = mock(BudgetDocumentRepository.class);
	private final PatientLookup patients = mock(PatientLookup.class);
	private final ClinicIdentity clinic = mock(ClinicIdentity.class);
	private final ClinicZones zones = mock(ClinicZones.class);
	private final FileStorage storage = mock(FileStorage.class);
	private final DocumentSlots slots = new DocumentSlots(3);
	private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
	private final EntityManager entityManager = mock(EntityManager.class);
	private final BudgetService budgetService = mock(BudgetService.class);
	private final PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
	private BudgetDocumentService service;

	private final UUID clinicId = UUID.randomUUID();
	private final UUID userId = UUID.randomUUID();
	private final UUID patientId = UUID.randomUUID();
	private final UUID procedureId = UUID.randomUUID();
	private final AtomicReference<byte[]> uploaded = new AtomicReference<>();

	@BeforeEach
	void setUp() throws Exception {
		TenantContext.set(clinicId, userId, Role.DENTIST_ADMIN);
		lenient().when(tm.getTransaction(any(TransactionDefinition.class))).thenAnswer(i -> new SimpleTransactionStatus());
		CrmProperties properties = new CrmProperties(List.of(), new CrmProperties.Storage("", "", "", Duration.ofSeconds(60), ""), false, null);
		service = new BudgetDocumentService(budgets, items, documents, patients, clinic, zones, storage, slots, events, entityManager,
				Clock.fixed(NOW, ZoneOffset.UTC), budgetService, tm, properties);

		lenient().when(zones.zone()).thenReturn(ZoneId.of("America/Sao_Paulo"));
		lenient().when(clinic.current()).thenReturn(new ClinicInfo("Clínica Sorriso", "Rua A, 10", "1133334444"));
		lenient().when(patients.briefs(anyCollection())).thenReturn(Map.of(patientId, new PatientBrief(patientId, "Maria Souza", "119")));
		lenient().when(budgets.saveAndFlush(any(Budget.class))).thenAnswer(call -> call.getArgument(0));
		lenient().when(documents.saveAndFlush(any(BudgetDocument.class))).thenAnswer(call -> call.getArgument(0));
		lenient().when(documents.maxRevision(any())).thenReturn(0);
		lenient().doAnswer(call -> {
			Object entity = call.getArgument(0);
			if (entity instanceof Budget budget && budget.getStatus() == BudgetStatus.SENT) {
				ReflectionTestUtils.setField(budget, "sentAt", NOW);
			}
			return null;
		}).when(entityManager).refresh(any());
		// o banco atribui a revisão e o instante; a leitura é por projeção (refresh em @Immutable lança UnsupportedLockAttemptException)
		var firstRevision = issued(1);
		lenient().when(documents.findIssuedById(any())).thenReturn(Optional.of(firstRevision));
		lenient().doAnswer(call -> {
			try (InputStream in = call.getArgument(1)) {
				ByteArrayOutputStream out = new ByteArrayOutputStream();
				in.transferTo(out);
				uploaded.set(out.toByteArray());
			}
			return null;
		}).when(storage).save(anyString(), any(InputStream.class), anyString(), anyLong());
	}

	@AfterEach
	void clear() {
		TenantContext.clear();
	}

	private Budget budget(BudgetStatus status, long version, String total) {
		Budget budget = new Budget(UUID.randomUUID(), patientId, null, null, "Em 6x", userId);
		budget.setStatus(status);
		ReflectionTestUtils.setField(budget, "version", version);
		ReflectionTestUtils.setField(budget, "number", 12);
		ReflectionTestUtils.setField(budget, "totalAmount", new BigDecimal(total));
		budget.setDiscountAmount(new BigDecimal("50.00"));
		when(budgets.findForUpdateById(budget.getId())).thenReturn(Optional.of(budget));
		BudgetItem item = new BudgetItem(UUID.randomUUID(), budget.getId(), procedureId, (short) 2, BigDecimal.ZERO.setScale(2), (short) 1);
		ReflectionTestUtils.setField(item, "description", "Restauração");
		ReflectionTestUtils.setField(item, "unitPrice", new BigDecimal("650.00"));
		ReflectionTestUtils.setField(item, "lineTotal", new BigDecimal("1300.00"));
		lenient().when(items.findByBudgetIdOrderByPositionAsc(budget.getId())).thenReturn(List.of(item));
		return budget;
	}

	private static BudgetDocumentRepository.IssuedRow issued(int revision) {
		BudgetDocumentRepository.IssuedRow row = mock(BudgetDocumentRepository.IssuedRow.class);
		lenient().when(row.getRevision()).thenReturn((short) revision);
		lenient().when(row.getGeneratedAt()).thenReturn(NOW);
		return row;
	}

	private void assertNothingLeaked() {
		assertThat(slots.available()).as("a vaga é devolvida").isEqualTo(3);
	}

	// ---- caminho feliz ----

	@Test
	void sendChangesTheStatusThenUploadsThenRegistersTheMetadataAndPublishesTheEvent() throws Exception {
		Budget budget = budget(BudgetStatus.DRAFT, 4, "1250.00");
		BudgetView view = mock(BudgetView.class);
		when(budgetService.get(budget.getId())).thenReturn(view);

		BudgetView result = service.send(budget.getId(), 4L);

		assertThat(result).isSameAs(view);
		assertThat(budget.getStatus()).isEqualTo(BudgetStatus.SENT);
		InOrder order = inOrder(budgets, entityManager, storage, documents, events);
		order.verify(budgets).saveAndFlush(budget);
		order.verify(entityManager).refresh(budget);
		ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
		order.verify(storage).save(key.capture(), any(InputStream.class), eq("application/pdf"), anyLong());
		ArgumentCaptor<BudgetDocument> saved = ArgumentCaptor.forClass(BudgetDocument.class);
		order.verify(documents).saveAndFlush(saved.capture());
		order.verify(documents).findIssuedById(saved.getValue().getId());
		order.verify(events).publishEvent(new CommercialEvents.BudgetSent(clinicId, budget.getId(), patientId, 1, userId));
		byte[] bytes = uploaded.get();
		assertThat(new String(bytes, 0, 5)).isEqualTo("%PDF-");
		assertThat(saved.getValue().getStorageKey()).isEqualTo(key.getValue());
		assertThat(saved.getValue().getSizeBytes()).isEqualTo(bytes.length);
		assertThat(saved.getValue().getSha256()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
		assertThat(saved.getValue().getContentType()).isEqualTo("application/pdf");
		assertThat(saved.getValue().getCreatedBy()).isEqualTo(userId);
		assertThat(saved.getValue().getBudgetId()).isEqualTo(budget.getId());
		assertThat(saved.getValue().getPatientId()).isEqualTo(patientId);
		assertThat(key.getValue()).startsWith(clinicId + "/" + patientId + "/budgets/").hasSize(118);
		assertThatCodeIsAValidKey(key.getValue());
		verify(storage, never()).delete(anyString());
		assertNothingLeaked();
	}

	private static void assertThatCodeIsAValidKey(String key) {
		assertThat(StorageKeys.require(key)).isEqualTo(key);
	}

	@Test
	void theSendTransactionHasTheTwentySecondCeilingAndTheReadChecksAreReadOnly() throws Exception {
		Budget budget = budget(BudgetStatus.DRAFT, 0, "1250.00");
		when(budgetService.get(budget.getId())).thenReturn(mock(BudgetView.class));

		service.send(budget.getId(), 0L);

		ArgumentCaptor<TransactionDefinition> definition = ArgumentCaptor.forClass(TransactionDefinition.class);
		verify(tm).getTransaction(definition.capture());
		assertThat(definition.getValue().getTimeout()).isEqualTo(20);
		assertThat(definition.getValue().isReadOnly()).isFalse();
	}

	@Test
	void theTransactionCeilingComesFromTheConfiguredProperty() {
		CrmProperties custom = new CrmProperties(List.of(), null, false,
				new CrmProperties.Documents(null, null, null, Duration.ofSeconds(7)));
		BudgetDocumentService configured = new BudgetDocumentService(budgets, items, documents, patients, clinic, zones, storage, slots,
				events, entityManager, Clock.fixed(NOW, ZoneOffset.UTC), budgetService, tm, custom);
		Budget budget = budget(BudgetStatus.DRAFT, 0, "1250.00");
		when(budgetService.get(budget.getId())).thenReturn(mock(BudgetView.class));

		configured.send(budget.getId(), 0L);

		ArgumentCaptor<TransactionDefinition> definition = ArgumentCaptor.forClass(TransactionDefinition.class);
		verify(tm).getTransaction(definition.capture());
		assertThat(definition.getValue().getTimeout()).isEqualTo(7);
	}

	// ---- recusas antes de qualquer gravação ----

	@Test
	void aBudgetWithoutItemsOrWithZeroTotalIs409AndNothingIsWritten() {
		Budget empty = budget(BudgetStatus.DRAFT, 0, "0.00");
		when(items.findByBudgetIdOrderByPositionAsc(empty.getId())).thenReturn(List.of());
		Budget zero = budget(BudgetStatus.DRAFT, 0, "0.00");

		for (Budget budget : List.of(empty, zero)) {
			assertThatThrownBy(() -> service.send(budget.getId(), 0L)).isInstanceOfSatisfying(BusinessException.class, ex -> {
				assertThat(ex.type()).isEqualTo(ProblemTypes.CONFLICT);
				assertThat(ex.getMessage()).isEqualTo("Inclua ao menos um item com valor maior que zero.");
			});
			assertThat(budget.getStatus()).isEqualTo(BudgetStatus.DRAFT);
		}
		verify(budgets, never()).saveAndFlush(any());
		verifyNoInteractions(storage);
		assertNothingLeaked();
	}

	@Test
	void aStaleVersionOrANonDraftStatusIs409WithoutTouchingTheStorage() {
		Budget stale = budget(BudgetStatus.DRAFT, 5, "1250.00");
		Budget sent = budget(BudgetStatus.SENT, 0, "1250.00");

		assertThatThrownBy(() -> service.send(stale.getId(), 4L)).isInstanceOfSatisfying(BusinessException.class,
				ex -> assertThat(ex.type()).isEqualTo(ProblemTypes.CONFLICT));
		assertThatThrownBy(() -> service.send(sent.getId(), 0L)).isInstanceOfSatisfying(BusinessException.class, ex -> {
			assertThat(ex.type()).isEqualTo(ProblemTypes.CONFLICT);
			assertThat(ex.getMessage()).contains("SENT");
		});

		verifyNoInteractions(storage);
		verify(budgets, never()).saveAndFlush(any());
		assertNothingLeaked();
	}

	@Test
	void anUnknownBudgetIs404() {
		UUID unknown = UUID.randomUUID();
		when(budgets.findForUpdateById(unknown)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.send(unknown, 0L)).isInstanceOfSatisfying(BusinessException.class,
				ex -> assertThat(ex.type()).isEqualTo(ProblemTypes.NOT_FOUND));
		assertNothingLeaked();
	}

	@Test
	void withoutAFreeSlotTheSendIs503WithRetryAfterAndNothingIsTouched() {
		Budget budget = budget(BudgetStatus.DRAFT, 0, "1250.00");
		DocumentSlots busy = new DocumentSlots(0);
		BudgetDocumentService saturated = new BudgetDocumentService(budgets, items, documents, patients, clinic, zones, storage, busy,
				events, entityManager, Clock.fixed(NOW, ZoneOffset.UTC), budgetService, tm,
				new CrmProperties(List.of(), null, false, null));

		assertThatThrownBy(() -> saturated.send(budget.getId(), 0L)).isInstanceOfSatisfying(BusyException.class, ex -> {
			assertThat(ex.type()).isEqualTo(ProblemTypes.UNAVAILABLE);
			assertThat(ex.retryAfterSeconds()).isEqualTo(5);
		});

		verifyNoInteractions(budgets, storage, documents);
		assertThat(busy.available()).isZero();
	}

	@Test
	void aPdfAboveFiveMegabytesIs422AndNothingIsStored() {
		Budget budget = budget(BudgetStatus.DRAFT, 0, "1250.00");
		ReflectionTestUtils.setField(service, "renderer", new BudgetPdfRenderer() {
			@Override
			public byte[] render(BudgetPdfModel model) {
				return new byte[BudgetDocumentService.MAX_PDF_BYTES + 1];
			}
		});

		assertThatThrownBy(() -> service.send(budget.getId(), 0L)).isInstanceOfSatisfying(BusinessException.class, ex -> {
			assertThat(ex.type()).isEqualTo(ProblemTypes.BUSINESS_RULE);
			assertThat(ex.reason()).isEqualTo("PDF_TOO_LARGE");
		});

		verifyNoInteractions(storage);
		verify(documents, never()).saveAndFlush(any());
		verify(tm).rollback(any());
		assertNothingLeaked();
	}

	// ---- compensação ----

	@Test
	void aStorageFailureRollsBackAndCleansTheKeyThatWasAboutToBeWritten() throws Exception {
		Budget budget = budget(BudgetStatus.DRAFT, 0, "1250.00");
		doThrow(new IOException("rede")).when(storage).save(anyString(), any(InputStream.class), anyString(), anyLong());

		assertThatThrownBy(() -> service.send(budget.getId(), 0L)).isInstanceOf(StorageException.class);

		ArgumentCaptor<String> saveKey = ArgumentCaptor.forClass(String.class);
		verify(storage).save(saveKey.capture(), any(InputStream.class), anyString(), anyLong());
		verify(storage).delete(saveKey.getValue()); // a chave foi registrada ANTES de gravar: um upload parcial também é limpo
		verify(documents, never()).saveAndFlush(any());
		verify(tm).rollback(any());
		verify(events, never()).publishEvent(any(CommercialEvents.BudgetSent.class));
		assertNothingLeaked();
	}

	@Test
	void aFailureToRegisterTheMetadataRollsBackAndDeletesTheObject() throws Exception {
		Budget budget = budget(BudgetStatus.DRAFT, 0, "1250.00");
		IllegalStateException failure = new IllegalStateException("insert falhou");
		when(documents.saveAndFlush(any(BudgetDocument.class))).thenThrow(failure);

		assertThatThrownBy(() -> service.send(budget.getId(), 0L)).isSameAs(failure);

		ArgumentCaptor<String> saveKey = ArgumentCaptor.forClass(String.class);
		verify(storage).save(saveKey.capture(), any(InputStream.class), anyString(), anyLong());
		verify(storage).delete(saveKey.getValue());
		verify(tm).rollback(any());
		assertNothingLeaked();
	}

	@Test
	void whenTheCommitFailsAndTheRowDoesNotExistTheObjectIsDeleted() throws Exception {
		Budget budget = budget(BudgetStatus.DRAFT, 0, "1250.00");
		TransactionSystemException commitFailure = new TransactionSystemException("commit falhou");
		doThrow(commitFailure).doNothing().when(tm).commit(any());
		when(documents.existsByStorageKey(anyString())).thenReturn(false);

		assertThatThrownBy(() -> service.send(budget.getId(), 0L)).isSameAs(commitFailure);

		ArgumentCaptor<String> saveKey = ArgumentCaptor.forClass(String.class);
		verify(storage).save(saveKey.capture(), any(InputStream.class), anyString(), anyLong());
		verify(documents).existsByStorageKey(saveKey.getValue());
		verify(storage).delete(saveKey.getValue());
		verify(events, never()).publishEvent(any(CommercialEvents.BudgetSent.class));
		assertNothingLeaked();
	}

	@Test
	void whenTheCommitResultIsUncertainButTheRowExistsTheObjectIsKept() throws Exception {
		Budget budget = budget(BudgetStatus.DRAFT, 0, "1250.00");
		doThrow(new TransactionSystemException("conexão caiu depois do ack")).doNothing().when(tm).commit(any());
		when(documents.existsByStorageKey(anyString())).thenReturn(true);

		assertThatThrownBy(() -> service.send(budget.getId(), 0L)).isInstanceOf(TransactionSystemException.class);

		verify(storage, never()).delete(anyString()); // apagar o objeto de uma linha que existe deixaria o PDF sem arquivo
		assertNothingLeaked();
	}

	@Test
	void aFailureOfTheBodyNeverAsksIfTheRowExistsBecauseTheRollbackIsCertain() throws Exception {
		Budget budget = budget(BudgetStatus.DRAFT, 0, "1250.00");
		when(documents.saveAndFlush(any(BudgetDocument.class))).thenThrow(new IllegalStateException("insert falhou"));

		assertThatThrownBy(() -> service.send(budget.getId(), 0L)).isInstanceOf(IllegalStateException.class);

		verify(documents, never()).existsByStorageKey(anyString());
		verify(storage).delete(anyString());
	}

	@Test
	void aFailedCompensationPublishesTheEventKeepsTheOriginalErrorAndNeverLogsTheKey() throws Exception {
		Budget budget = budget(BudgetStatus.DRAFT, 0, "1250.00");
		doThrow(new IOException("rede")).when(storage).save(anyString(), any(InputStream.class), anyString(), anyLong());
		doThrow(new IOException("rede de novo")).when(storage).delete(anyString());
		Logger logger = (Logger) LoggerFactory.getLogger(BudgetDocumentService.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			assertThatThrownBy(() -> service.send(budget.getId(), 0L)).isInstanceOf(StorageException.class)
					.hasMessage("Falha ao enviar o PDF ao Storage.");
		}
		finally {
			logger.detachAppender(appender);
		}

		verify(events).publishEvent(new CommercialEvents.StorageCompensationFailed(clinicId, budget.getId()));
		ArgumentCaptor<String> saveKey = ArgumentCaptor.forClass(String.class);
		verify(storage).save(saveKey.capture(), any(InputStream.class), anyString(), anyLong());
		assertThat(appender.list).isNotEmpty().allSatisfy(event -> {
			assertThat(event.getFormattedMessage()).doesNotContain(saveKey.getValue()).contains(budget.getId().toString());
			assertThat(event.getThrowableProxy()).as("só a classe do erro, nunca a stack com a causa").isNull();
		});
		assertNothingLeaked();
	}

	@Test
	void aRuntimeFailureOfTheCompensationItselfAlsoBecomesTheEvent() throws Exception {
		Budget budget = budget(BudgetStatus.DRAFT, 0, "1250.00");
		when(documents.saveAndFlush(any(BudgetDocument.class))).thenThrow(new IllegalStateException("insert falhou"));
		doThrow(new IllegalStateException("storage fora")).when(storage).delete(anyString());

		assertThatThrownBy(() -> service.send(budget.getId(), 0L)).hasMessage("insert falhou");

		verify(events).publishEvent(new CommercialEvents.StorageCompensationFailed(clinicId, budget.getId()));
	}

	// ---- nova revisão ----

	@Test
	void regenerateKeepsTheStatusUsesTheNextRevisionAndPublishesTheEvent() throws Exception {
		Budget budget = budget(BudgetStatus.APPROVED, 7, "1250.00");
		ReflectionTestUtils.setField(budget, "sentAt", NOW.minusSeconds(86_400));
		when(documents.maxRevision(budget.getId())).thenReturn(2);
		var thirdRevision = issued(3);
		when(documents.findIssuedById(any())).thenReturn(Optional.of(thirdRevision));

		DocumentSummary summary = service.regenerate(budget.getId());

		assertThat(summary.revision()).isEqualTo(3);
		assertThat(budget.getStatus()).isEqualTo(BudgetStatus.APPROVED);
		verify(budgets, never()).saveAndFlush(any());
		verify(events).publishEvent(new CommercialEvents.DocumentGenerated(clinicId, budget.getId(), 3, userId));
		assertThat(uploaded.get()).isNotEmpty();
		assertNothingLeaked();
	}

	@Test
	void regenerateIsAllowedFromSentApprovedAndRejectedButNeverFromDraft() {
		for (BudgetStatus allowed : List.of(BudgetStatus.SENT, BudgetStatus.APPROVED, BudgetStatus.REJECTED)) {
			Budget budget = budget(allowed, 0, "1250.00");
			assertThat(service.regenerate(budget.getId()).revision()).as(allowed.name()).isEqualTo(1);
		}
		Budget draft = budget(BudgetStatus.DRAFT, 0, "1250.00");

		assertThatThrownBy(() -> service.regenerate(draft.getId())).isInstanceOfSatisfying(BusinessException.class, ex -> {
			assertThat(ex.type()).isEqualTo(ProblemTypes.CONFLICT);
			assertThat(ex.getMessage()).isEqualTo("Envie o orçamento antes de gerar o PDF.");
		});
	}

	// ---- leitura ----

	@Test
	void theDownloadUrlIsSignedForSixtySecondsOutsideTheTransactionAndNeverPrinted() throws Exception {
		Budget budget = budget(BudgetStatus.SENT, 0, "1250.00");
		UUID documentId = UUID.randomUUID();
		String key = StorageKeys.ofBudget(clinicId, patientId, UUID.randomUUID());
		BudgetDocument document = new BudgetDocument(documentId, budget.getId(), patientId, key, 100, "a".repeat(64), userId);
		when(documents.findByIdAndBudgetIdAndDeletedAtIsNull(documentId, budget.getId())).thenReturn(Optional.of(document));
		when(storage.createSignedUrl(key, Duration.ofSeconds(60))).thenReturn(URI.create("https://storage.test/sign?token=segredo"));

		DownloadUrl url = service.downloadUrl(budget.getId(), documentId);

		assertThat(url.url()).hasToString("https://storage.test/sign?token=segredo");
		assertThat(url.expiresInSeconds()).isEqualTo(60);
		assertThat(url.toString()).doesNotContain("segredo", "storage.test");
	}

	@Test
	void aMissingRemovedOrForeignDocumentIs404AndTheStorageIsNotCalled() throws Exception {
		Budget budget = budget(BudgetStatus.SENT, 0, "1250.00");
		UUID missing = UUID.randomUUID();
		UUID foreign = UUID.randomUUID();
		when(documents.findByIdAndBudgetIdAndDeletedAtIsNull(missing, budget.getId())).thenReturn(Optional.empty());
		String foreignKey = StorageKeys.ofBudget(UUID.randomUUID(), patientId, UUID.randomUUID()); // prefixo de outra clínica
		BudgetDocument foreignDocument = new BudgetDocument(foreign, budget.getId(), patientId, foreignKey, 100, "a".repeat(64), userId);
		when(documents.findByIdAndBudgetIdAndDeletedAtIsNull(foreign, budget.getId())).thenReturn(Optional.of(foreignDocument));

		for (UUID id : List.of(missing, foreign)) {
			assertThatThrownBy(() -> service.downloadUrl(budget.getId(), id)).isInstanceOfSatisfying(BusinessException.class,
					ex -> assertThat(ex.type()).isEqualTo(ProblemTypes.NOT_FOUND));
		}
		verifyNoInteractions(storage);
	}

	@Test
	void aStorageFailureWhileSigningIs503WithoutTheKey() throws Exception {
		Budget budget = budget(BudgetStatus.SENT, 0, "1250.00");
		UUID documentId = UUID.randomUUID();
		String key = StorageKeys.ofBudget(clinicId, patientId, UUID.randomUUID());
		when(documents.findByIdAndBudgetIdAndDeletedAtIsNull(documentId, budget.getId()))
				.thenReturn(Optional.of(new BudgetDocument(documentId, budget.getId(), patientId, key, 100, "a".repeat(64), userId)));
		when(storage.createSignedUrl(anyString(), any())).thenThrow(new IOException("rede"));

		assertThatThrownBy(() -> service.downloadUrl(budget.getId(), documentId)).isInstanceOfSatisfying(StorageException.class,
				ex -> assertThat(ex.getMessage()).doesNotContain(key));
	}

	@Test
	void listReturnsTheActiveRevisionsAndAnUnknownBudgetIs404() {
		Budget budget = budget(BudgetStatus.SENT, 0, "1250.00");
		when(budgets.findById(budget.getId())).thenReturn(Optional.of(budget));
		BudgetDocument document = new BudgetDocument(UUID.randomUUID(), budget.getId(), patientId, "k", 10, "a".repeat(64), userId);
		ReflectionTestUtils.setField(document, "revision", (short) 2);
		ReflectionTestUtils.setField(document, "generatedAt", NOW);
		when(documents.findActiveByBudgetId(budget.getId())).thenReturn(List.of(document));
		UUID unknown = UUID.randomUUID();
		when(budgets.findById(unknown)).thenReturn(Optional.empty());

		assertThat(service.list(budget.getId())).extracting(DocumentSummary::revision, DocumentSummary::sizeBytes).containsExactly(org.assertj.core.groups.Tuple.tuple(2, 10L));
		assertThatThrownBy(() -> service.list(unknown)).isInstanceOfSatisfying(BusinessException.class,
				ex -> assertThat(ex.type()).isEqualTo(ProblemTypes.NOT_FOUND));
	}
}
