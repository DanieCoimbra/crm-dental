package crm_data.record.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
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
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import crm_data.patient.service.PatientLookup;
import crm_data.record.domain.FileKind;
import crm_data.record.domain.PatientFile;
import crm_data.record.repository.PatientFileRepository;
import crm_data.record.service.PatientFileService.SignedUrl;
import crm_data.record.service.PatientFileService.Upload;
import crm_data.shared.config.CrmProperties;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ProblemTypes;
import crm_data.shared.security.Role;
import crm_data.shared.storage.FileStorage;
import crm_data.shared.storage.StorageException;
import crm_data.shared.tenant.TenantContext;

@ExtendWith(MockitoExtension.class)
class PatientFileServiceTest {

	private static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");
	private static final byte[] PNG_HEAD = { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A };

	@Mock
	PatientFileRepository files;
	@Mock
	PatientLookup patients;
	@Mock
	FileStorage storage;

	/** Marca se há "transação" aberta, para provar que o Storage nunca é chamado dentro dela. */
	final AtomicBoolean inTransaction = new AtomicBoolean();
	PatientFileService service;
	UUID clinicId = UUID.randomUUID();
	UUID userId = UUID.randomUUID();
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
		CrmProperties properties = new CrmProperties(List.of(),
				new CrmProperties.Storage("", "", "", Duration.ofSeconds(60), ""), false);
		service = new PatientFileService(files, patients, storage, tm, Clock.fixed(NOW, ZoneOffset.UTC), properties);
		TenantContext.set(clinicId, userId, Role.RECEPTIONIST);
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
	}

	private static byte[] png(int totalBytes) {
		return Arrays.copyOf(PNG_HEAD, Math.max(totalBytes, PNG_HEAD.length));
	}

	private static Upload png(String kind, String declared, String filename) {
		byte[] bytes = png(100);
		return new Upload(kind, declared, filename, bytes.length, () -> new ByteArrayInputStream(bytes));
	}

	private static void assertValidation(Throwable error) {
		assertThat(error).isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.type()).isEqualTo(ProblemTypes.VALIDATION));
	}

	// ---- upload ----

	@Test
	void uploadChecksThePatientThenStoresThenRecordsAndReturnsTheId() throws IOException {
		when(files.saveAndFlush(any(PatientFile.class))).thenAnswer(i -> {
			assertThat(inTransaction.get()).as("gravar metadados exige transação").isTrue();
			return i.getArgument(0);
		});
		doAnswer(i -> {
			assertThat(inTransaction.get()).as("o Storage nunca é chamado dentro de transação").isFalse();
			return null;
		}).when(storage).save(anyString(), any(), anyString(), anyLong());

		UUID id = service.upload(patientId, png("radiograph", "image/png", "..\\C:\\raio-x.png"));

		String expectedKey = clinicId + "/" + patientId + "/" + id;
		InOrder order = inOrder(patients, storage, files);
		order.verify(patients).requireWritable(patientId);
		order.verify(storage).save(eq(expectedKey), any(), eq("image/png"), eq(100L));
		ArgumentCaptor<PatientFile> saved = ArgumentCaptor.forClass(PatientFile.class);
		order.verify(files).saveAndFlush(saved.capture());
		PatientFile file = saved.getValue();
		assertThat(file.getId()).isEqualTo(id);
		assertThat(file.getPatientId()).isEqualTo(patientId);
		assertThat(file.getKind()).isEqualTo(FileKind.RADIOGRAPH);
		assertThat(file.getStorageKey()).isEqualTo(expectedKey);
		assertThat(file.getOriginalFilename()).isEqualTo("raio-x.png");
		assertThat(file.getContentType()).isEqualTo("image/png");
		assertThat(file.getSizeBytes()).isEqualTo(100);
		assertThat(file.getCreatedBy()).isEqualTo(userId);
		verify(storage, never()).delete(anyString());
	}

	@Test
	void uploadStoresTheStreamedBytes() throws IOException {
		when(files.saveAndFlush(any(PatientFile.class))).thenAnswer(i -> i.getArgument(0));
		ArgumentCaptor<java.io.InputStream> stream = ArgumentCaptor.forClass(java.io.InputStream.class);
		doAnswer(i -> {
			assertThat(((java.io.InputStream) i.getArgument(1)).readAllBytes()).isEqualTo(png(100));
			return null;
		}).when(storage).save(anyString(), stream.capture(), anyString(), anyLong());

		service.upload(patientId, png("OTHER", "image/png", "a.png"));

		verify(storage).save(anyString(), any(), eq("image/png"), eq(100L));
	}

	@Test
	void declaredTypeIsNormalizedButMustMatchTheDetectedOne() throws IOException {
		when(files.saveAndFlush(any(PatientFile.class))).thenAnswer(i -> i.getArgument(0));

		assertThatCode(() -> service.upload(patientId, png("OTHER", " IMAGE/PNG; charset=binary ", "a.png"))).doesNotThrowAnyException();

		for (String wrong : new String[] { "image/jpeg", "application/pdf", "application/octet-stream", "", null }) {
			assertThatThrownBy(() -> service.upload(patientId, png("OTHER", wrong, "a.png"))).satisfies(PatientFileServiceTest::assertValidation);
		}
	}

	@Test
	void unknownContentIsRejectedWhateverIsDeclared() {
		byte[] text = "isto nao e uma imagem".getBytes();
		Upload upload = new Upload("OTHER", "image/png", "a.png", text.length, () -> new ByteArrayInputStream(text));

		assertThatThrownBy(() -> service.upload(patientId, upload)).satisfies(PatientFileServiceTest::assertValidation);
		verifyNoInteractions(storage);
		verify(files, never()).saveAndFlush(any());
	}

	@Test
	void invalidKindEmptyAndOversizeAreRejectedBeforeAnyWork() {
		assertThatThrownBy(() -> service.upload(patientId, png("XRAY", "image/png", "a.png"))).satisfies(PatientFileServiceTest::assertValidation);
		assertThatThrownBy(() -> service.upload(patientId, png(null, "image/png", "a.png"))).satisfies(PatientFileServiceTest::assertValidation);
		assertThatThrownBy(() -> service.upload(patientId, new Upload("OTHER", "image/png", "a.png", 0, () -> new ByteArrayInputStream(new byte[0]))))
				.satisfies(PatientFileServiceTest::assertValidation);
		assertThatThrownBy(() -> service.upload(patientId,
				new Upload("OTHER", "image/png", "a.png", PatientFileService.MAX_SIZE_BYTES + 1, () -> new ByteArrayInputStream(png(100)))))
				.isInstanceOf(MaxUploadSizeExceededException.class);

		verifyNoInteractions(patients, storage);
	}

	@Test
	void exactlyTenMegabytesIsAccepted() throws IOException {
		when(files.saveAndFlush(any(PatientFile.class))).thenAnswer(i -> i.getArgument(0));
		Upload upload = new Upload("OTHER", "image/png", "a.png", PatientFileService.MAX_SIZE_BYTES, () -> new ByteArrayInputStream(png(100)));

		assertThatCode(() -> service.upload(patientId, upload)).doesNotThrowAnyException();
		verify(storage).save(anyString(), any(), eq("image/png"), eq(PatientFileService.MAX_SIZE_BYTES));
	}

	@Test
	void unwritablePatientStopsBeforeTheStorage() {
		BusinessException anonymized = new BusinessException(ProblemTypes.CONFLICT, "Paciente anonimizado.");
		doThrow(anonymized).when(patients).requireWritable(patientId);

		assertThatThrownBy(() -> service.upload(patientId, png("OTHER", "image/png", "a.png"))).isSameAs(anonymized);
		verifyNoInteractions(storage);
		verify(files, never()).saveAndFlush(any());
	}

	@Test
	void storageFailureIsPropagatedAndNothingIsRecorded() throws IOException {
		StorageException down = new StorageException("fora do ar");
		doThrow(down).when(storage).save(anyString(), any(), anyString(), anyLong());

		assertThatThrownBy(() -> service.upload(patientId, png("OTHER", "image/png", "a.png"))).isSameAs(down);
		verify(files, never()).saveAndFlush(any());
		verify(storage, never()).delete(anyString());
	}

	@Test
	void ioFailureWhileSendingBecomesAStorageException() throws IOException {
		doThrow(new IOException("disco")).when(storage).save(anyString(), any(), anyString(), anyLong());

		assertThatThrownBy(() -> service.upload(patientId, png("OTHER", "image/png", "a.png"))).isInstanceOf(StorageException.class);
		verify(files, never()).saveAndFlush(any());
	}

	@Test
	void databaseFailureDeletesTheUploadedObjectAndRethrowsTheOriginalError() throws IOException {
		DataIntegrityViolationException dbError = new DataIntegrityViolationException("CR003");
		when(files.saveAndFlush(any(PatientFile.class))).thenThrow(dbError);
		ArgumentCaptor<String> savedKey = ArgumentCaptor.forClass(String.class);
		doAnswer(i -> null).when(storage).save(savedKey.capture(), any(), anyString(), anyLong());

		assertThatThrownBy(() -> service.upload(patientId, png("OTHER", "image/png", "a.png"))).isSameAs(dbError);

		verify(storage).delete(savedKey.getValue());
		assertThat(inTransaction.get()).isFalse();
	}

	@Test
	void failedCompensationIsLoggedWithIdsOnlyAndTheOriginalErrorSurvives() throws IOException {
		DataIntegrityViolationException dbError = new DataIntegrityViolationException("falha");
		when(files.saveAndFlush(any(PatientFile.class))).thenThrow(dbError);
		doThrow(new IOException("sem acesso")).when(storage).delete(anyString());
		Logger logger = (Logger) LoggerFactory.getLogger(PatientFileService.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			assertThatThrownBy(() -> service.upload(patientId, png("OTHER", "image/png", "segredo-do-exame.png"))).isSameAs(dbError);
		}
		finally {
			logger.detachAppender(appender);
		}

		assertThat(appender.list).singleElement().satisfies(event -> {
			assertThat(event.getFormattedMessage()).contains("File.CompensationFailed", patientId.toString());
			assertThat(event.getFormattedMessage()).doesNotContain(clinicId.toString(), "segredo-do-exame");
			assertThat(event.getThrowableProxy()).isNull();
		});
	}

	// ---- lista, URL e remoção ----

	@Test
	void listChecksThePatientAndOrdersNewestFirst() {
		Page<PatientFile> page = new PageImpl<>(List.of());
		when(files.findByPatientIdAndDeletedAtIsNull(any(UUID.class), any(Pageable.class))).thenReturn(page);

		assertThat(service.list(patientId, 1, 10)).isSameAs(page);

		ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
		verify(patients).requireExists(patientId);
		verify(files).findByPatientIdAndDeletedAtIsNull(eq(patientId), pageable.capture());
		assertThat(pageable.getValue().getSort()).isEqualTo(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
		assertThat(pageable.getValue().getPageNumber()).isEqualTo(1);
		assertThat(pageable.getValue().getPageSize()).isEqualTo(10);
	}

	private PatientFile stored(UUID fileId, UUID ownerClinic) {
		PatientFile file = new PatientFile(fileId, patientId, FileKind.OTHER, ownerClinic + "/" + patientId + "/" + fileId,
				"a.png", "image/png", 10, userId);
		ReflectionTestUtils.setField(file, "version", 0L);
		return file;
	}

	@Test
	void signedUrlIsCreatedOutsideATransactionWithTheConfiguredTtl() throws IOException {
		UUID fileId = UUID.randomUUID();
		PatientFile file = stored(fileId, clinicId);
		when(files.findByIdAndPatientIdAndDeletedAtIsNull(fileId, patientId)).thenAnswer(i -> {
			assertThat(inTransaction.get()).as("a busca roda numa transação curta").isTrue();
			return Optional.of(file);
		});
		when(storage.createSignedUrl(eq(file.getStorageKey()), any(Duration.class))).thenAnswer(i -> {
			assertThat(inTransaction.get()).as("o Storage nunca é chamado dentro de transação").isFalse();
			return URI.create("https://exemplo.test/assinada?token=abc");
		});

		SignedUrl signed = service.signedUrl(patientId, fileId);

		assertThat(signed.url()).isEqualTo(URI.create("https://exemplo.test/assinada?token=abc"));
		assertThat(signed.expiresInSeconds()).isEqualTo(60);
		verify(storage).createSignedUrl(file.getStorageKey(), Duration.ofSeconds(60));
		assertThat(signed.toString()).doesNotContain("token");
	}

	@Test
	void signedUrlOfAMissingRemovedOrForeignFileIs404AndNeverSigns() throws IOException {
		UUID fileId = UUID.randomUUID();
		when(files.findByIdAndPatientIdAndDeletedAtIsNull(fileId, patientId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.signedUrl(patientId, fileId)).isInstanceOfSatisfying(BusinessException.class,
				ex -> assertThat(ex.type()).isEqualTo(ProblemTypes.NOT_FOUND));
		verifyNoInteractions(storage);
	}

	@Test
	void signedUrlRefusesAKeyOutsideTheTokenClinicPrefix() throws IOException {
		UUID fileId = UUID.randomUUID();
		when(files.findByIdAndPatientIdAndDeletedAtIsNull(fileId, patientId)).thenReturn(Optional.of(stored(fileId, UUID.randomUUID())));

		assertThatThrownBy(() -> service.signedUrl(patientId, fileId)).isInstanceOfSatisfying(BusinessException.class,
				ex -> assertThat(ex.type()).isEqualTo(ProblemTypes.NOT_FOUND));
		verifyNoInteractions(storage);
	}

	@Test
	void signedUrlStorageFailureBecomesAStorageException() throws IOException {
		UUID fileId = UUID.randomUUID();
		when(files.findByIdAndPatientIdAndDeletedAtIsNull(fileId, patientId)).thenReturn(Optional.of(stored(fileId, clinicId)));
		when(storage.createSignedUrl(anyString(), any(Duration.class))).thenThrow(new IOException("rede"));

		assertThatThrownBy(() -> service.signedUrl(patientId, fileId)).isInstanceOf(StorageException.class);
	}

	@Test
	void removeMarksTheFileAsDeletedAndKeepsTheObject() throws IOException {
		UUID fileId = UUID.randomUUID();
		PatientFile file = stored(fileId, clinicId);
		when(files.findByIdAndPatientIdAndDeletedAtIsNull(fileId, patientId)).thenReturn(Optional.of(file));

		service.remove(patientId, fileId);

		assertThat(file.getDeletedAt()).isEqualTo(NOW);
		verify(files).saveAndFlush(file);
		verifyNoInteractions(storage);
	}

	@Test
	void removeOfAMissingOrAlreadyRemovedFileIs404() {
		UUID fileId = UUID.randomUUID();
		when(files.findByIdAndPatientIdAndDeletedAtIsNull(fileId, patientId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.remove(patientId, fileId)).isInstanceOfSatisfying(BusinessException.class,
				ex -> assertThat(ex.type()).isEqualTo(ProblemTypes.NOT_FOUND));
		verify(files, never()).saveAndFlush(any());
	}

	// ---- interface para a anonimização ----

	@Test
	void storageKeysOfReturnsEveryKeyOfThePatient() {
		when(files.findStorageKeysByPatientId(patientId)).thenReturn(List.of("k1", "k2"));

		assertThat(service.storageKeysOf(patientId)).containsExactly("k1", "k2");
	}

	@Test
	void purgeObjectsDeletesInBatchAndMapsIoFailures() throws IOException {
		service.purgeObjects(List.of("k1", "k2"));
		verify(storage).deleteAll(List.of("k1", "k2"));

		doThrow(new IOException("rede")).when(storage).deleteAll(any());
		assertThatThrownBy(() -> service.purgeObjects(List.of("k1"))).isInstanceOf(StorageException.class);
		StorageException down = new StorageException("fora");
		doThrow(down).when(storage).deleteAll(any());
		assertThatThrownBy(() -> service.purgeObjects(List.of("k1"))).isSameAs(down);
	}
}
