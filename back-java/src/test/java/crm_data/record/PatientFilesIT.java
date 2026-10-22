package crm_data.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import crm_data.patient.service.PatientLookup;
import crm_data.record.service.PatientFileService;
import crm_data.shared.security.Role;
import crm_data.shared.storage.FileStorage;
import crm_data.shared.storage.StorageException;
import crm_data.shared.tenant.TenantContext;
import crm_data.support.DatabaseContainer;
import crm_data.support.SqlRecordingDataSourceConfig;
import crm_data.support.tenant.PatientFixtures;
import crm_data.support.tenant.TenantFixtures;
import crm_data.support.tenant.TenantFixtures.Clinic;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * F04 ponta a ponta com PostgreSQL real e {@code LocalFileStorage} em disco: upload com compensação, papéis (a recepção
 * só anexa e, para ela, não há {@code SELECT}/{@code RETURNING}), URL assinada, remoção lógica e interface da
 * anonimização.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(SqlRecordingDataSourceConfig.class)
class PatientFilesIT {

	private static final Path STORAGE_DIR = createStorageDir();
	private static final int TEN_MB = 10_485_760;
	private static final byte[] PNG_HEAD = { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A };

	private static Path createStorageDir() {
		try {
			return Files.createTempDirectory("crm-it-files-");
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		DatabaseContainer.register(registry);
		registry.add("crm.storage.local-dir", STORAGE_DIR::toString);
	}

	@Autowired
	MockMvc mvc;
	@Autowired
	JsonMapper mapper;
	@Autowired
	PatientFileService fileService;
	@MockitoSpyBean
	FileStorage storage;
	@MockitoSpyBean
	PatientLookup lookup;

	@AfterEach
	void cleanUp() {
		TenantContext.clear();
		SecurityContextHolder.clearContext();
	}

	// ---- apoio ----

	private static RequestPostProcessor as(Clinic clinic, Role role) {
		int index = role == Role.DENTIST_ADMIN ? 0 : 1; // dois usuários por clínica: um por papel
		return jwt().jwt(j -> j.subject(clinic.userIds().get(index).toString()).claim("clinic_id", clinic.id().toString())
				.claim("role", role.name())).authorities(new SimpleGrantedAuthority("ROLE_" + role.name()));
	}

	private static Clinic newClinic() {
		return TenantFixtures.clinicWithUsers(2);
	}

	private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
		return request.contentType(MediaType.APPLICATION_JSON).content(body);
	}

	private String createPatient(Clinic clinic) throws Exception {
		MvcResult result = mvc.perform(json(post("/api/v1/patients"),
				"{\"name\":\"Maria Souza\",\"birthDate\":\"1988-04-12\",\"phone\":\"11987654321\"}").with(as(clinic, Role.RECEPTIONIST)))
				.andExpect(status().isCreated()).andReturn();
		return mapper.readTree(result.getResponse().getContentAsString()).get("id").asString();
	}

	private static byte[] png(int size) {
		return Arrays.copyOf(PNG_HEAD, size);
	}

	private static MockMultipartFile file(String name, String type, byte[] bytes) {
		return new MockMultipartFile("file", name, type, bytes);
	}

	private MvcResult upload(Clinic clinic, Role role, String patient, MockMultipartFile file, String kind, int expectedStatus) throws Exception {
		return mvc.perform(multipart("/api/v1/patients/" + patient + "/files").file(file).param("kind", kind).with(as(clinic, role)))
				.andExpect(status().is(expectedStatus)).andReturn();
	}

	private String uploadPng(Clinic clinic, Role role, String patient) throws Exception {
		MvcResult result = upload(clinic, role, patient, file("raio-x.png", "image/png", png(64)), "RADIOGRAPH", 201);
		return mapper.readTree(result.getResponse().getContentAsString()).get("id").asString();
	}

	private static long objectsOf(Clinic clinic) throws IOException {
		Path dir = STORAGE_DIR.resolve(clinic.id().toString());
		if (!Files.exists(dir)) {
			return 0;
		}
		try (Stream<Path> walk = Files.walk(dir)) {
			return walk.filter(Files::isRegularFile).count();
		}
	}

	private static String keyOf(Clinic clinic, String patient, String fileId) {
		return clinic.id() + "/" + patient + "/" + fileId;
	}

	private static boolean onDisk(String key) {
		return Files.exists(STORAGE_DIR.resolve(key));
	}

	/**
	 * Faz a checagem "paciente gravável" passar sem consultar o banco, simulando a anonimização que ocorre entre ela e
	 * o INSERT. O spy fica dentro do proxy transacional, então configurar o stub abre uma transação e precisa de tenant.
	 */
	private void skipWritableCheck(Clinic clinic) {
		TenantContext.set(clinic.id(), clinic.userIds().get(0), Role.DENTIST_ADMIN);
		try {
			doNothing().when(lookup).requireWritable(any(UUID.class));
		}
		finally {
			TenantContext.clear();
		}
	}

	// ---- upload ----

	@Test
	void dentistAndReceptionistUploadAndGetOnlyTheIdAndTheMetadataIsRecorded() throws Exception {
		Clinic clinic = newClinic();
		String patient = createPatient(clinic);

		MvcResult byDentist = upload(clinic, Role.DENTIST_ADMIN, patient, file("..\\C:\\raio-x.png", "image/png", png(64)), "radiograph", 201);
		MvcResult byReceptionist = upload(clinic, Role.RECEPTIONIST, patient, file("foto.png", "image/png", png(32)), "INTRAORAL_PHOTO", 201);

		for (MvcResult result : List.of(byDentist, byReceptionist)) {
			JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
			assertThat(body.size()).isEqualTo(1);
			assertThat(body.get("id").asString()).isNotBlank();
			assertThat(result.getResponse().getContentAsString()).doesNotContain("storageKey", "clinicId");
		}
		String dentistFileId = mapper.readTree(byDentist.getResponse().getContentAsString()).get("id").asString();
		var row = DatabaseContainer.admin().queryForMap(
				"SELECT kind, storage_key, original_filename, content_type, size_bytes, created_by FROM core.patient_files WHERE id = ?::uuid",
				dentistFileId);
		assertThat(row.get("kind")).isEqualTo("RADIOGRAPH");
		assertThat(row.get("storage_key")).isEqualTo(keyOf(clinic, patient, dentistFileId));
		assertThat(row.get("original_filename")).isEqualTo("raio-x.png");
		assertThat(row.get("content_type")).isEqualTo("image/png");
		assertThat(((Number) row.get("size_bytes")).longValue()).isEqualTo(64);
		assertThat(row.get("created_by").toString()).isEqualTo(clinic.userIds().get(0).toString());
		assertThat(onDisk(keyOf(clinic, patient, dentistFileId))).isTrue();
		assertThat(objectsOf(clinic)).isEqualTo(2);
	}

	@Test
	void receptionistUploadRunsNoSelectOrReturningOnPatientFiles() throws Exception {
		Clinic clinic = newClinic();
		String patient = createPatient(clinic);
		SqlRecordingDataSourceConfig.reset();

		uploadPng(clinic, Role.RECEPTIONIST, patient);

		assertThat(SqlRecordingDataSourceConfig.prepared()).filteredOn(sql -> sql.toLowerCase().contains("patient_files"))
				.isNotEmpty()
				.allSatisfy(sql -> assertThat(sql.toLowerCase()).startsWith("insert into core.patient_files")
						.doesNotContain("returning"));
	}

	@Test
	void forgedTypeEmptyUnknownAndInvalidKindAreRejectedAndNothingIsStored() throws Exception {
		Clinic clinic = newClinic();
		String patient = createPatient(clinic);

		upload(clinic, Role.DENTIST_ADMIN, patient, file("a.jpg", "image/jpeg", png(64)), "OTHER", 400); // JPEG declarado, bytes de PNG
		upload(clinic, Role.DENTIST_ADMIN, patient, file("a.png", "image/png", new byte[0]), "OTHER", 400);
		upload(clinic, Role.DENTIST_ADMIN, patient, file("a.png", "image/png", "texto puro, nao e imagem".getBytes()), "OTHER", 400);
		upload(clinic, Role.DENTIST_ADMIN, patient, file("a.png", "image/png", png(64)), "XRAY", 400);
		upload(clinic, Role.DENTIST_ADMIN, patient, file("a.gif", "image/gif", "GIF89a......".getBytes()), "OTHER", 400);

		assertThat(objectsOf(clinic)).isZero();
		assertThat(DatabaseContainer.admin().queryForObject("SELECT count(*) FROM core.patient_files WHERE clinic_id = ?::uuid",
				Long.class, clinic.id().toString())).isZero();
	}

	@Test
	void fileOverTenMegabytesIs413AndTenMegabytesExactlyIsAcceptedWithinFiveSeconds() throws Exception {
		Clinic clinic = newClinic();
		String patient = createPatient(clinic);

		upload(clinic, Role.DENTIST_ADMIN, patient, file("grande.png", "image/png", png(TEN_MB + 1)), "OTHER", 413);
		assertThat(objectsOf(clinic)).isZero();

		long start = System.nanoTime();
		upload(clinic, Role.DENTIST_ADMIN, patient, file("limite.png", "image/png", png(TEN_MB)), "OTHER", 201);
		Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

		assertThat(elapsed).as("upload de 10 MB com LocalFileStorage").isLessThanOrEqualTo(Duration.ofSeconds(5));
		assertThat(objectsOf(clinic)).isEqualTo(1);
	}

	@Test
	void unknownPatientForeignPatientAndAnonymizedPatientNeverStoreAnObject() throws Exception {
		Clinic a = newClinic();
		Clinic b = newClinic();
		String patientOfB = createPatient(b);
		String anonymized = createPatient(a);
		PatientFixtures.anonymize(a, anonymized);

		upload(a, Role.RECEPTIONIST, UUID.randomUUID().toString(), file("a.png", "image/png", png(64)), "OTHER", 404);
		upload(a, Role.RECEPTIONIST, patientOfB, file("a.png", "image/png", png(64)), "OTHER", 404);
		upload(a, Role.DENTIST_ADMIN, anonymized, file("a.png", "image/png", png(64)), "OTHER", 409);

		assertThat(objectsOf(a)).isZero();
		assertThat(objectsOf(b)).isZero();
	}

	@Test
	void databaseFailureAfterTheUploadDeletesTheObjectLeavingNoOrphan() throws Exception {
		Clinic clinic = newClinic();
		String patient = createPatient(clinic);
		PatientFixtures.anonymize(clinic, patient);
		// simula o paciente sendo anonimizado entre a checagem e o INSERT: a checagem passa, o trigger (CR003) recusa
		skipWritableCheck(clinic);

		upload(clinic, Role.DENTIST_ADMIN, patient, file("a.png", "image/png", png(64)), "OTHER", 409);

		assertThat(objectsOf(clinic)).as("0 órfãos no Storage").isZero();
		assertThat(DatabaseContainer.admin().queryForObject("SELECT count(*) FROM core.patient_files WHERE clinic_id = ?::uuid",
				Long.class, clinic.id().toString())).isZero();
	}

	@Test
	void storageOutageOnUploadIs503AndNothingIsRecorded() throws Exception {
		Clinic clinic = newClinic();
		String patient = createPatient(clinic);
		doThrow(new StorageException("fora do ar")).when(storage).save(anyString(), any(), anyString(), anyLong());

		upload(clinic, Role.DENTIST_ADMIN, patient, file("a.png", "image/png", png(64)), "OTHER", 503);

		assertThat(DatabaseContainer.admin().queryForObject("SELECT count(*) FROM core.patient_files WHERE clinic_id = ?::uuid",
				Long.class, clinic.id().toString())).isZero();
	}

	@Test
	void theEleventhUploadInAMinuteIs429WithRetryAfter() throws Exception {
		Clinic clinic = newClinic();
		String patient = createPatient(clinic);

		for (int i = 0; i < 10; i++) {
			uploadPng(clinic, Role.RECEPTIONIST, patient);
		}
		mvc.perform(multipart("/api/v1/patients/" + patient + "/files").file(file("a.png", "image/png", png(64))).param("kind", "OTHER")
				.with(as(clinic, Role.RECEPTIONIST)))
				.andExpect(status().isTooManyRequests())
				.andExpect(header().exists("Retry-After"));

		assertThat(objectsOf(clinic)).isEqualTo(10);
		// o limite é por usuário: o dentista da mesma clínica ainda pode enviar
		uploadPng(clinic, Role.DENTIST_ADMIN, patient);
	}

	// ---- recepção ----

	@Test
	void receptionistGets403OnListDownloadAndDeleteAndTheDatabaseIsNotTouched() throws Exception {
		Clinic clinic = newClinic();
		String patient = createPatient(clinic);
		String fileId = uploadPng(clinic, Role.RECEPTIONIST, patient);
		SqlRecordingDataSourceConfig.reset();
		RequestPostProcessor r = as(clinic, Role.RECEPTIONIST);

		mvc.perform(get("/api/v1/patients/" + patient + "/files").with(r)).andExpect(status().isForbidden());
		mvc.perform(get("/api/v1/patients/" + patient + "/files/" + fileId + "/download-url").with(r)).andExpect(status().isForbidden());
		mvc.perform(delete("/api/v1/patients/" + patient + "/files/" + fileId).with(r)).andExpect(status().isForbidden());

		assertThat(SqlRecordingDataSourceConfig.prepared()).isEmpty();
		assertThat(onDisk(keyOf(clinic, patient, fileId))).isTrue();
	}

	// ---- lista, URL assinada e remoção ----

	@Test
	void listShowsActiveFilesNewestFirstWithoutTheStorageKey() throws Exception {
		Clinic clinic = newClinic();
		String patient = createPatient(clinic);
		String first = uploadPng(clinic, Role.DENTIST_ADMIN, patient);
		String second = uploadPng(clinic, Role.RECEPTIONIST, patient);
		String removed = uploadPng(clinic, Role.DENTIST_ADMIN, patient);
		mvc.perform(delete("/api/v1/patients/" + patient + "/files/" + removed).with(as(clinic, Role.DENTIST_ADMIN)))
				.andExpect(status().isNoContent());

		MvcResult result = mvc.perform(get("/api/v1/patients/" + patient + "/files").with(as(clinic, Role.DENTIST_ADMIN)))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.totalElements").value(2))
				.andExpect(jsonPath("$.content[0].originalFilename").value("raio-x.png"))
				.andExpect(jsonPath("$.content[0].contentType").value("image/png"))
				.andExpect(jsonPath("$.content[0].kind").value("RADIOGRAPH"))
				.andReturn();

		JsonNode content = mapper.readTree(result.getResponse().getContentAsString()).get("content");
		assertThat(content.get(0).get("id").asString()).isEqualTo(second);
		assertThat(content.get(1).get("id").asString()).isEqualTo(first);
		assertThat(result.getResponse().getContentAsString()).doesNotContain("storageKey", "clinicId", "createdBy");
		mvc.perform(get("/api/v1/patients/" + patient + "/files").param("size", "51").with(as(clinic, Role.DENTIST_ADMIN)))
				.andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/patients/" + UUID.randomUUID() + "/files").with(as(clinic, Role.DENTIST_ADMIN)))
				.andExpect(status().isNotFound());
	}

	@Test
	void downloadUrlHasSixtySecondsNoStoreAndNeverReachesTheLog() throws Exception {
		Clinic clinic = newClinic();
		String patient = createPatient(clinic);
		String fileId = uploadPng(clinic, Role.DENTIST_ADMIN, patient);
		Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		root.addAppender(appender);
		String url;
		try {
			MvcResult result = mvc.perform(get("/api/v1/patients/" + patient + "/files/" + fileId + "/download-url").with(as(clinic, Role.DENTIST_ADMIN)))
					.andExpect(status().isOk())
					.andExpect(header().string("Cache-Control", "no-store"))
					.andExpect(jsonPath("$.expiresIn").value(60))
					.andReturn();
			url = mapper.readTree(result.getResponse().getContentAsString()).get("url").asString();
		}
		finally {
			root.detachAppender(appender);
		}

		assertThat(url).contains(keyOf(clinic, patient, fileId)).endsWith("expiresIn=60");
		assertThat(appender.list).allSatisfy(event -> assertThat(event.getFormattedMessage())
				.doesNotContain(url, keyOf(clinic, patient, fileId), fileId));
	}

	@Test
	void downloadUrlOfRemovedForeignOrOtherPatientsFilesIs404() throws Exception {
		Clinic a = newClinic();
		Clinic b = newClinic();
		String patientA = createPatient(a);
		String otherPatientA = createPatient(a);
		String patientB = createPatient(b);
		String fileA = uploadPng(a, Role.DENTIST_ADMIN, patientA);
		String removed = uploadPng(a, Role.DENTIST_ADMIN, patientA);
		mvc.perform(delete("/api/v1/patients/" + patientA + "/files/" + removed).with(as(a, Role.DENTIST_ADMIN))).andExpect(status().isNoContent());

		mvc.perform(get("/api/v1/patients/" + patientA + "/files/" + removed + "/download-url").with(as(a, Role.DENTIST_ADMIN)))
				.andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/patients/" + patientB + "/files/" + fileA + "/download-url").with(as(b, Role.DENTIST_ADMIN)))
				.andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/patients/" + patientA + "/files/" + fileA + "/download-url").with(as(b, Role.DENTIST_ADMIN)))
				.andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/patients/" + otherPatientA + "/files/" + fileA + "/download-url").with(as(a, Role.DENTIST_ADMIN)))
				.andExpect(status().isNotFound());
		mvc.perform(delete("/api/v1/patients/" + patientA + "/files/" + fileA).with(as(b, Role.DENTIST_ADMIN)))
				.andExpect(status().isNotFound());
	}

	@Test
	void deleteIsLogicalFillsDeletedAtAndKeepsTheObject() throws Exception {
		Clinic clinic = newClinic();
		String patient = createPatient(clinic);
		String fileId = uploadPng(clinic, Role.DENTIST_ADMIN, patient);
		String path = "/api/v1/patients/" + patient + "/files/" + fileId;

		mvc.perform(delete(path).with(as(clinic, Role.DENTIST_ADMIN))).andExpect(status().isNoContent());

		assertThat(DatabaseContainer.admin().queryForObject("SELECT deleted_at IS NOT NULL FROM core.patient_files WHERE id = ?::uuid",
				Boolean.class, fileId)).isTrue();
		assertThat(onDisk(keyOf(clinic, patient, fileId))).as("o objeto permanece até a anonimização").isTrue();
		mvc.perform(delete(path).with(as(clinic, Role.DENTIST_ADMIN))).andExpect(status().isNotFound());
		mvc.perform(get(path + "/download-url").with(as(clinic, Role.DENTIST_ADMIN))).andExpect(status().isNotFound());
	}

	@Test
	void storageOutageOnSigningIs503() throws Exception {
		Clinic clinic = newClinic();
		String patient = createPatient(clinic);
		String fileId = uploadPng(clinic, Role.DENTIST_ADMIN, patient);
		doThrow(new StorageException("fora do ar")).when(storage).createSignedUrl(anyString(), any());

		mvc.perform(get("/api/v1/patients/" + patient + "/files/" + fileId + "/download-url").with(as(clinic, Role.DENTIST_ADMIN)))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.type").value("https://crm.local/problems/unavailable"));
	}

	// ---- interface para a anonimização (F05) ----

	@Test
	void storageKeysOfIncludesRemovedFilesAndPurgeObjectsErasesEverything() throws Exception {
		Clinic clinic = newClinic();
		String patient = createPatient(clinic);
		String active = uploadPng(clinic, Role.DENTIST_ADMIN, patient);
		String removed = uploadPng(clinic, Role.DENTIST_ADMIN, patient);
		mvc.perform(delete("/api/v1/patients/" + patient + "/files/" + removed).with(as(clinic, Role.DENTIST_ADMIN))).andExpect(status().isNoContent());

		SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("dentista", "n/a", "ROLE_DENTIST_ADMIN"));
		TenantContext.set(clinic.id(), clinic.userIds().get(0), Role.DENTIST_ADMIN);
		List<String> keys = fileService.storageKeysOf(UUID.fromString(patient));

		assertThat(keys).containsExactlyInAnyOrder(keyOf(clinic, patient, active), keyOf(clinic, patient, removed));
		fileService.purgeObjects(keys);
		assertThat(objectsOf(clinic)).isZero();
	}

	// ---- log ----

	@Test
	void neitherKeysNorFilenamesReachTheLogEvenWhenTheUploadFails() throws Exception {
		Clinic clinic = newClinic();
		String patient = createPatient(clinic);
		PatientFixtures.anonymize(clinic, patient);
		skipWritableCheck(clinic);
		Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		root.addAppender(appender);
		try {
			upload(clinic, Role.DENTIST_ADMIN, patient, file("segredo-do-exame.png", "image/png", png(64)), "OTHER", 409);
			doThrow(new StorageException("Falha ao gravar no Storage (HTTP 503).", new IllegalStateException("causa com https://projeto.supabase.co/chave"))).when(storage).save(anyString(), any(), anyString(), anyLong());
			upload(clinic, Role.DENTIST_ADMIN, patient, file("segredo-do-exame.png", "image/png", png(64)), "OTHER", 503);
		}
		finally {
			root.detachAppender(appender);
		}

		assertThat(appender.list).allSatisfy(event -> {
			assertThat(event.getFormattedMessage()).doesNotContain("segredo-do-exame", clinic.id().toString(), "supabase.co");
			// a causa (que pode carregar URL do Storage) nunca vai ao log; só a mensagem segura da StorageException
			for (var t = event.getThrowableProxy(); t != null; t = t.getCause()) {
				assertThat(t.getMessage()).doesNotContain("supabase.co");
			}
		});
		assertThat(appender.list).anySatisfy(event -> assertThat(event.getFormattedMessage()).contains("Falha ao gravar no Storage (HTTP 503)."));
	}
}
