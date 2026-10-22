package crm_data.patient.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import crm_data.patient.service.PatientAnonymizationService.Result;
import crm_data.patient.service.PatientAnonymizationService.StorageCleanup;
import crm_data.record.service.PatientFileService;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ProblemTypes;
import crm_data.shared.security.Role;
import crm_data.shared.storage.StorageException;
import crm_data.shared.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.Query;

@ExtendWith(MockitoExtension.class)
class PatientAnonymizationServiceTest {

	private static final Instant NOW = Instant.parse("2046-10-22T13:00:00Z");

	@Mock
	EntityManager entityManager;
	@Mock
	Query query;
	@Mock
	PatientFileService files;
	@Mock
	ApplicationEventPublisher events;

	final AtomicBoolean inTransaction = new AtomicBoolean();
	PatientAnonymizationService service;
	UUID clinicId = UUID.randomUUID();
	UUID patientId = UUID.randomUUID();

	@BeforeEach
	void setUp() {
		PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
		lenient().when(tm.getTransaction(any(TransactionDefinition.class))).thenAnswer(i -> {
			inTransaction.set(true);
			return new SimpleTransactionStatus();
		});
		lenient().doAnswer(i -> {
			inTransaction.set(false);
			return null;
		}).when(tm).commit(any());
		lenient().doAnswer(i -> {
			inTransaction.set(false);
			return null;
		}).when(tm).rollback(any());
		lenient().when(entityManager.createNativeQuery(anyString())).thenReturn(query);
		lenient().when(query.setParameter(anyString(), any())).thenReturn(query);
		lenient().when(query.getSingleResult()).thenReturn(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
		service = new PatientAnonymizationService(entityManager, files, tm, events);
		TenantContext.set(clinicId, UUID.randomUUID(), Role.DENTIST_ADMIN);
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
	}

	private static PersistenceException databaseError(String sqlState, String message) {
		return new PersistenceException("erro", new SQLException(message, sqlState));
	}

	private static void assertBusiness(Throwable error, ProblemTypes type, String message) {
		assertThat(error).isInstanceOfSatisfying(BusinessException.class, ex -> {
			assertThat(ex.type()).isEqualTo(type);
			assertThat(ex.getMessage()).isEqualTo(message);
		});
	}

	// ---- caminho feliz ----

	@Test
	void callsTheFunctionThenListsTheKeysInTheSameTransactionAndPurgesOnlyAfterTheCommit() {
		doAnswer(i -> {
			assertThat(inTransaction.get()).as("as chaves são listadas na mesma transação da função").isTrue();
			return List.of("k1", "k2");
		}).when(files).storageKeysOf(patientId);
		doAnswer(i -> {
			assertThat(inTransaction.get()).as("o Storage só é chamado depois do commit").isFalse();
			return null;
		}).when(files).purgeObjects(any());

		Result result = service.anonymize(patientId);

		assertThat(result.anonymizedAt()).isEqualTo(NOW);
		assertThat(result.storageCleanup()).isEqualTo(StorageCleanup.COMPLETED);
		InOrder order = inOrder(entityManager, query, files);
		order.verify(entityManager).createNativeQuery("SELECT core.anonymize_patient(:id)");
		order.verify(query).setParameter("id", patientId);
		order.verify(query).getSingleResult();
		order.verify(files).storageKeysOf(patientId);
		order.verify(files).purgeObjects(List.of("k1", "k2"));
		ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
		verify(events).publishEvent(event.capture());
		assertThat(event.getValue()).isEqualTo(new PatientEvents.Anonymized(clinicId, patientId, 2, StorageCleanup.COMPLETED));
	}

	@Test
	void patientWithoutObjectsSkipsTheStorageCall() {
		when(files.storageKeysOf(patientId)).thenReturn(List.of());

		Result result = service.anonymize(patientId);

		assertThat(result.storageCleanup()).isEqualTo(StorageCleanup.COMPLETED);
		verify(files, never()).purgeObjects(any());
		verify(events).publishEvent(new PatientEvents.Anonymized(clinicId, patientId, 0, StorageCleanup.COMPLETED));
	}

	@Test
	void storageFailureKeepsTheAnonymizationAndReportsPendingWithoutKeysInTheEvents() {
		when(files.storageKeysOf(patientId)).thenReturn(List.of("chave-secreta-1", "chave-secreta-2", "chave-secreta-3"));
		doThrow(new StorageException("fora do ar")).when(files).purgeObjects(any());

		Result result = service.anonymize(patientId);

		assertThat(result.anonymizedAt()).isEqualTo(NOW);
		assertThat(result.storageCleanup()).isEqualTo(StorageCleanup.PENDING);
		ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
		verify(this.events, org.mockito.Mockito.times(2)).publishEvent(events.capture());
		assertThat(events.getAllValues()).containsExactly(new PatientEvents.StorageCleanupPending(patientId, 3),
				new PatientEvents.Anonymized(clinicId, patientId, 3, StorageCleanup.PENDING));
		assertThat(events.getAllValues()).allSatisfy(e -> assertThat(e.toString()).doesNotContain("chave-secreta"));
	}

	@Test
	void anyUnexpectedFailureWhilePurgingIsAlsoPendingAndNeverTurnsIntoAnError() {
		when(files.storageKeysOf(patientId)).thenReturn(List.of("chave-secreta-1", "chave-secreta-2"));
		doThrow(new IllegalStateException("explodiu ao apagar chave-secreta-1")).when(files).purgeObjects(any());
		Logger logger = (Logger) LoggerFactory.getLogger(PatientAnonymizationService.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		Result result;
		try {
			result = service.anonymize(patientId);
		}
		finally {
			logger.detachAppender(appender);
		}

		assertThat(result.anonymizedAt()).isEqualTo(NOW);
		assertThat(result.storageCleanup()).isEqualTo(StorageCleanup.PENDING);
		ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
		verify(events, org.mockito.Mockito.times(2)).publishEvent(published.capture());
		assertThat(published.getAllValues()).containsExactly(new PatientEvents.StorageCleanupPending(patientId, 2),
				new PatientEvents.Anonymized(clinicId, patientId, 2, StorageCleanup.PENDING));
		assertThat(appender.list).singleElement().satisfies(event -> {
			assertThat(event.getFormattedMessage()).contains(patientId.toString(), "java.lang.IllegalStateException");
			assertThat(event.getFormattedMessage()).doesNotContain("chave-secreta");
			assertThat(event.getThrowableProxy()).isNull();
		});
	}

	@Test
	void theDriverMayReturnTimestamptzInSeveralShapes() {
		when(files.storageKeysOf(patientId)).thenReturn(List.of());

		for (Object value : new Object[] { NOW, OffsetDateTime.ofInstant(NOW, ZoneOffset.ofHours(-3)),
				ZonedDateTime.ofInstant(NOW, ZoneOffset.UTC), Timestamp.from(NOW) }) {
			when(query.getSingleResult()).thenReturn(value);
			assertThat(service.anonymize(patientId).anonymizedAt()).isEqualTo(NOW);
		}

		when(query.getSingleResult()).thenReturn("não é data");
		assertThatThrownBy(() -> service.anonymize(patientId)).isInstanceOf(IllegalStateException.class);
	}

	// ---- erros da função ----

	@Test
	void databaseErrorsAreTranslatedAndNothingElseRuns() {
		String[][] cases = { { "CR001", "ativo" }, { "CR003", "ja anonimizado" }, { "P0002", "inexistente" }, { "42501", "papel" } };
		ProblemTypes[] types = { ProblemTypes.CONFLICT, ProblemTypes.CONFLICT, ProblemTypes.NOT_FOUND, ProblemTypes.FORBIDDEN };
		String[] messages = { "Inative o paciente antes de anonimizar.", "Paciente anonimizado.", "Paciente não encontrado.",
				"Você não tem permissão para esta operação." };

		for (int i = 0; i < cases.length; i++) {
			doThrow(databaseError(cases[i][0], cases[i][1])).when(query).getSingleResult();
			int index = i;
			assertThatThrownBy(() -> service.anonymize(patientId)).satisfies(e -> assertBusiness(e, types[index], messages[index]));
		}
		verify(files, never()).storageKeysOf(any());
		verify(files, never()).purgeObjects(any());
		verify(events, never()).publishEvent(any(Object.class));
	}

	@Test
	void retentionConflictCarriesTheReleaseDate() {
		when(query.getSingleResult()).thenThrow(databaseError("CR002", "ERROR: retencao\n  Detail: liberado em 2046-11-30"));

		assertThatThrownBy(() -> service.anonymize(patientId)).satisfies(
				e -> assertBusiness(e, ProblemTypes.CONFLICT, "Retenção legal do prontuário vigente. Liberado em 2046-11-30."));
	}

	@Test
	void retentionConflictWithoutAParsableDateOmitsIt() {
		when(query.getSingleResult()).thenThrow(databaseError("CR002", "ERROR: retencao\n  Detail: formato inesperado"));

		assertThatThrownBy(() -> service.anonymize(patientId))
				.satisfies(e -> assertBusiness(e, ProblemTypes.CONFLICT, "Retenção legal do prontuário vigente."));
	}

	@Test
	void unknownDatabaseErrorsAreRethrownUntouched() {
		PersistenceException other = databaseError("28000", "sessao sem clinica ou usuario");
		when(query.getSingleResult()).thenThrow(other);

		assertThatThrownBy(() -> service.anonymize(patientId)).isSameAs(other);
		verify(files, never()).purgeObjects(any());
	}

	@Test
	void failureListingTheKeysRollsBackAndNeverPurges() {
		IllegalStateException failure = new IllegalStateException("falha ao listar");
		when(files.storageKeysOf(patientId)).thenThrow(failure);

		assertThatThrownBy(() -> service.anonymize(patientId)).isSameAs(failure);
		verify(files, never()).purgeObjects(any());
		verify(events, never()).publishEvent(any(Object.class));
		assertThat(inTransaction.get()).isFalse();
	}

	@Test
	void theTenantComesOnlyFromTheTokenContext() {
		TenantContext.clear();

		assertThatThrownBy(() -> service.anonymize(patientId)).isInstanceOfSatisfying(BusinessException.class,
				ex -> assertThat(ex.type()).isEqualTo(ProblemTypes.UNAUTHORIZED));
		verify(entityManager, never()).createNativeQuery(eq("SELECT core.anonymize_patient(:id)"));
	}
}
