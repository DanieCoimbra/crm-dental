package crm_data.patient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import crm_data.patient.service.PatientEvents;
import crm_data.patient.service.PatientAnonymizationService.StorageCleanup;
import crm_data.shared.security.Role;
import crm_data.shared.storage.FileStorage;
import crm_data.shared.storage.StorageException;
import crm_data.support.DatabaseContainer;
import crm_data.support.SqlRecordingDataSourceConfig;
import crm_data.support.tenant.PatientFixtures;
import crm_data.support.tenant.TenantFixtures;
import crm_data.support.tenant.TenantFixtures.Clinic;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * F05 ponta a ponta com PostgreSQL real (função {@code core.anonymize_patient}, RLS, triggers de auditoria) e Storage
 * em disco: erros da função, anonimização completa, limpeza do Storage (e sua falha), concorrência e trilha.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@RecordApplicationEvents
@Import(SqlRecordingDataSourceConfig.class)
class AnonymizationAndAuditIT {

	private static final Path STORAGE_DIR = createStorageDir();
	private static final byte[] PNG_HEAD = { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A };
	private static final String CPF = "52998224725";

	private static Path createStorageDir() {
		try {
			return Files.createTempDirectory("crm-it-anon-");
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
	ApplicationEvents applicationEvents;
	@MockitoSpyBean
	FileStorage storage;

	// ---- apoio ----

	private static RequestPostProcessor as(Clinic clinic, Role role) {
		int index = role == Role.DENTIST_ADMIN ? 0 : 1;
		return jwt().jwt(j -> j.subject(clinic.userIds().get(index).toString()).claim("clinic_id", clinic.id().toString())
				.claim("role", role.name())).authorities(new SimpleGrantedAuthority("ROLE_" + role.name()));
	}

	private static Clinic newClinic() {
		return TenantFixtures.clinicWithUsers(2);
	}

	private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
		return request.contentType(MediaType.APPLICATION_JSON).content(body);
	}

	private JsonNode read(MvcResult result) throws Exception {
		return mapper.readTree(result.getResponse().getContentAsString());
	}

	private String createPatient(Clinic clinic, String name, String cpf) throws Exception {
		String body = """
				{"name":"%s","cpf":%s,"birthDate":"1941-04-12","sex":"FEMALE","profession":"Professora","phone":"11987654321",
				 "email":"maria@exemplo.com","address":"Rua das Flores, 10","insuranceName":"Convênio X","insuranceNumber":"123456",
				 "acquaintanceName":"João Souza","acquaintancePhone":"11912345678"}"""
				.formatted(name, cpf == null ? "null" : "\"" + cpf + "\"");
		MvcResult result = mvc.perform(json(post("/api/v1/patients"), body).with(as(clinic, Role.RECEPTIONIST)))
				.andExpect(status().isCreated()).andReturn();
		return read(result).get("id").asString();
	}

	private void deactivate(Clinic clinic, String patient) throws Exception {
		mvc.perform(json(patch("/api/v1/patients/" + patient + "/active"), "{\"active\":false}").with(as(clinic, Role.DENTIST_ADMIN)))
				.andExpect(status().isOk());
	}

	private String addNote(Clinic clinic, String patient) throws Exception {
		MvcResult result = mvc.perform(json(post("/api/v1/patients/" + patient + "/notes"), "{\"content\":\"Restauração do dente 36\"}")
				.with(as(clinic, Role.DENTIST_ADMIN))).andExpect(status().isCreated()).andReturn();
		return read(result).get("id").asString();
	}

	private void addHistory(Clinic clinic, String patient) throws Exception {
		mvc.perform(json(put("/api/v1/patients/" + patient + "/medical-history"), "{\"content\":\"Alergia grave a penicilina\"}")
				.with(as(clinic, Role.DENTIST_ADMIN))).andExpect(status().isCreated());
	}

	private String addFile(Clinic clinic, String patient, String filename) throws Exception {
		MockMultipartFile file = new MockMultipartFile("file", filename, "image/png", Arrays.copyOf(PNG_HEAD, 64));
		MvcResult result = mvc.perform(multipart("/api/v1/patients/" + patient + "/files").file(file).param("kind", "RADIOGRAPH")
				.with(as(clinic, Role.RECEPTIONIST))).andExpect(status().isCreated()).andReturn();
		return read(result).get("id").asString();
	}

	private MvcResult anonymize(Clinic clinic, String patient, int expectedStatus) throws Exception {
		return mvc.perform(post("/api/v1/patients/" + patient + "/anonymize").with(as(clinic, Role.DENTIST_ADMIN)))
				.andExpect(status().is(expectedStatus)).andReturn();
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

	private static Map<String, Object> patientRow(String patient) {
		return DatabaseContainer.admin().queryForMap("SELECT * FROM core.patients WHERE id = ?::uuid", patient);
	}

	// ---- papéis ----

	@Test
	void receptionistGets403OnBothRoutesAndTheDatabaseIsNotTouched() throws Exception {
		Clinic clinic = newClinic();
		String patient = createPatient(clinic, "Maria Souza", null);
		SqlRecordingDataSourceConfig.reset();

		mvc.perform(post("/api/v1/patients/" + patient + "/anonymize").with(as(clinic, Role.RECEPTIONIST)))
				.andExpect(status().isForbidden());
		mvc.perform(get("/api/v1/audit-log").with(as(clinic, Role.RECEPTIONIST))).andExpect(status().isForbidden());

		assertThat(SqlRecordingDataSourceConfig.prepared()).isEmpty();
		assertThat(patientRow(patient).get("anonymized_at")).isNull();
	}

	// ---- erros da função ----

	@Test
	void activePatientIs409AskingToInactivateFirst() throws Exception {
		Clinic clinic = newClinic();
		String patient = createPatient(clinic, "Maria Souza", null);

		mvc.perform(post("/api/v1/patients/" + patient + "/anonymize").with(as(clinic, Role.DENTIST_ADMIN)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.type").value("https://crm.local/problems/conflict"))
				.andExpect(jsonPath("$.detail").value("Inative o paciente antes de anonimizar."));
		assertThat(patientRow(patient).get("anonymized_at")).isNull();
	}

	@Test
	void unknownAndForeignPatientsAre404() throws Exception {
		Clinic a = newClinic();
		Clinic b = newClinic();
		String patientOfB = createPatient(b, "Maria Souza", null);
		deactivate(b, patientOfB);

		mvc.perform(post("/api/v1/patients/" + java.util.UUID.randomUUID() + "/anonymize").with(as(a, Role.DENTIST_ADMIN)))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.detail").value("Paciente não encontrado."));
		mvc.perform(post("/api/v1/patients/" + patientOfB + "/anonymize").with(as(a, Role.DENTIST_ADMIN)))
				.andExpect(status().isNotFound());
		assertThat(patientRow(patientOfB).get("anonymized_at")).as("a outra clínica não foi tocada").isNull();
	}

	@Test
	void retentionInForceIs409WithTheReleaseDate() throws Exception {
		Clinic clinic = newClinic();
		String patient = createPatient(clinic, "Maria Souza", null);
		addNote(clinic, patient);
		deactivate(clinic, patient);

		MvcResult result = anonymize(clinic, patient, 409);

		assertThat(read(result).get("detail").asString()).matches("Retenção legal do prontuário vigente\\. Liberado em \\d{4}-\\d{2}-\\d{2}\\.");
		assertThat(patientRow(patient).get("anonymized_at")).isNull();
	}

	// ---- anonimização ----

	@Test
	void inactivePatientWithoutClinicalRecordsIsAnonymizedAtOnceAndASecondCallIs409() throws Exception {
		Clinic clinic = newClinic();
		String patient = createPatient(clinic, "Maria Souza", CPF);
		deactivate(clinic, patient);

		MvcResult result = anonymize(clinic, patient, 200);

		assertThat(read(result).get("storageCleanup").asString()).isEqualTo("COMPLETED");
		assertThat(read(result).get("anonymizedAt").isNull()).isFalse();
		mvc.perform(post("/api/v1/patients/" + patient + "/anonymize").with(as(clinic, Role.DENTIST_ADMIN)))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("Paciente anonimizado."));
	}

	@Test
	void afterTheRetentionExpiresNoPersonalColumnKeepsTheOriginalAndEverythingIsLocked() throws Exception {
		Clinic clinic = newClinic();
		String patient = createPatient(clinic, "Maria Anonimizavel", CPF);
		addHistory(clinic, patient);
		addNote(clinic, patient);
		addNote(clinic, patient);
		String kept = addFile(clinic, patient, "exame-secreto-1.png");
		String removed = addFile(clinic, patient, "exame-secreto-2.png");
		mvc.perform(delete("/api/v1/patients/" + patient + "/files/" + removed).with(as(clinic, Role.DENTIST_ADMIN)))
				.andExpect(status().isNoContent());
		deactivate(clinic, patient);
		PatientFixtures.backdateClinicalRecords(patient, 21);
		assertThat(objectsOf(clinic)).isEqualTo(2);

		MvcResult result = anonymize(clinic, patient, 200);

		assertThat(read(result).get("storageCleanup").asString()).isEqualTo("COMPLETED");
		Map<String, Object> row = patientRow(patient);
		assertThat(row.get("name")).isEqualTo("Paciente anonimizado");
		assertThat(row.get("cpf")).isNull();
		assertThat(row.get("birth_date").toString()).isEqualTo("1941-01-01");
		assertThat(row.get("sex")).isEqualTo("NOT_INFORMED");
		assertThat(row.get("profession")).isNull();
		assertThat(row.get("phone")).isEqualTo("0000000000");
		assertThat(row.get("email")).isNull();
		assertThat(row.get("address")).isNull();
		assertThat(row.get("insurance_name")).isNull();
		assertThat(row.get("insurance_number")).isNull();
		assertThat(row.get("acquaintance_name")).isNull();
		assertThat(row.get("acquaintance_phone")).isNull();
		assertThat(row.get("anonymized_at")).isNotNull();
		assertThat(row.get("active")).isEqualTo(false);
		var admin = DatabaseContainer.admin();
		assertThat(admin.queryForObject("SELECT count(*) FROM core.medical_histories WHERE patient_id = ?::uuid", Long.class, patient)).isZero();
		assertThat(admin.queryForObject("SELECT count(*) FROM core.clinical_notes WHERE patient_id = ?::uuid", Long.class, patient)).isZero();
		assertThat(admin.queryForList("SELECT original_filename, deleted_at IS NOT NULL AS removed FROM core.patient_files WHERE patient_id = ?::uuid", patient))
				.hasSize(2).allSatisfy(file -> {
					assertThat(file.get("original_filename")).isEqualTo("anonimizado");
					assertThat(file.get("removed")).isEqualTo(true);
				});
		assertThat(objectsOf(clinic)).as("os objetos de todos os exames, inclusive o removido, foram apagados").isZero();

		mvc.perform(get("/api/v1/patients/" + patient).with(as(clinic, Role.DENTIST_ADMIN)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Paciente anonimizado"))
				.andExpect(jsonPath("$.anonymizedAt").isNotEmpty()).andExpect(jsonPath("$.cpf").doesNotExist());
		mvc.perform(json(put("/api/v1/patients/" + patient), "{\"name\":\"Outro\",\"birthDate\":\"1941-01-01\",\"phone\":\"11987654321\",\"version\":99}")
				.with(as(clinic, Role.DENTIST_ADMIN))).andExpect(status().isConflict());
		mvc.perform(json(patch("/api/v1/patients/" + patient + "/active"), "{\"active\":true}").with(as(clinic, Role.DENTIST_ADMIN)))
				.andExpect(status().isConflict());
		mvc.perform(json(post("/api/v1/patients/" + patient + "/notes"), "{\"content\":\"x\"}").with(as(clinic, Role.DENTIST_ADMIN)))
				.andExpect(status().isConflict());
		mvc.perform(multipart("/api/v1/patients/" + patient + "/files")
				.file(new MockMultipartFile("file", "novo.png", "image/png", Arrays.copyOf(PNG_HEAD, 64))).param("kind", "OTHER")
				.with(as(clinic, Role.RECEPTIONIST))).andExpect(status().isConflict());
		assertThat(kept).isNotBlank();
	}

	@Test
	void storageFailureKeepsTheAnonymizationAndReportsPendingWithoutKeysInTheEvents() throws Exception {
		Clinic clinic = newClinic();
		String patient = createPatient(clinic, "Maria Souza", CPF);
		addFile(clinic, patient, "exame-secreto.png");
		addFile(clinic, patient, "exame-secreto-2.png");
		deactivate(clinic, patient);
		PatientFixtures.backdateClinicalRecords(patient, 21);
		doThrow(new StorageException("Falha ao apagar objetos no Storage (HTTP 500).")).when(storage).deleteAll(any());

		MvcResult result = anonymize(clinic, patient, 200);

		assertThat(read(result).get("storageCleanup").asString()).isEqualTo("PENDING");
		assertThat(patientRow(patient).get("anonymized_at")).as("a anonimização não é desfeita").isNotNull();
		assertThat(patientRow(patient).get("name")).isEqualTo("Paciente anonimizado");
		assertThat(objectsOf(clinic)).as("os objetos ficam para conciliação").isEqualTo(2);
		assertThat(applicationEvents.stream(PatientEvents.StorageCleanupPending.class))
				.singleElement().satisfies(event -> {
					assertThat(event.objectCount()).isEqualTo(2);
					assertThat(event.patientId().toString()).isEqualTo(patient);
				});
		assertThat(applicationEvents.stream(PatientEvents.Anonymized.class))
				.anySatisfy(event -> assertThat(event.status()).isEqualTo(StorageCleanup.PENDING));
	}

	@Test
	void twoConcurrentCallsYieldOne200AndOne409() throws Exception {
		Clinic clinic = newClinic();
		String patient = createPatient(clinic, "Maria Souza", null);
		addNote(clinic, patient);
		deactivate(clinic, patient);
		PatientFixtures.backdateClinicalRecords(patient, 21);
		CyclicBarrier barrier = new CyclicBarrier(2);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		List<Future<Integer>> futures = new ArrayList<>();
		for (int i = 0; i < 2; i++) {
			futures.add(pool.submit(() -> {
				barrier.await();
				return mvc.perform(post("/api/v1/patients/" + patient + "/anonymize").with(as(clinic, Role.DENTIST_ADMIN)))
						.andReturn().getResponse().getStatus();
			}));
		}
		List<Integer> statuses = new ArrayList<>();
		for (Future<Integer> future : futures) {
			statuses.add(future.get());
		}
		pool.shutdown();

		assertThat(statuses).containsExactlyInAnyOrder(200, 409);
		assertThat(patientRow(patient).get("anonymized_at")).isNotNull();
	}

	// ---- corrida entre upload e anonimização ----

	/**
	 * Upload e anonimização do mesmo paciente ao mesmo tempo, várias vezes. A linha do paciente é travada (trigger do
	 * upload com FOR KEY SHARE contra a função com FOR UPDATE), então só há dois desfechos coerentes: o upload perde
	 * (409, objeto compensado e apagado) e a anonimização vence, ou o upload vence e a anonimização é recusada pela
	 * retenção (409). Nunca os dois com sucesso, e nunca sobra objeto de paciente anonimizado.
	 */
	@Test
	void concurrentUploadAndAnonymizationNeverLeaveAnOrphanObject() throws Exception {
		Clinic clinic = newClinic();
		for (int round = 0; round < 8; round++) {
			String patient = createPatient(clinic, "Maria Souza", null);
			deactivate(clinic, patient);
			CyclicBarrier barrier = new CyclicBarrier(2);
			ExecutorService pool = Executors.newFixedThreadPool(2);
			Future<Integer> upload = pool.submit(() -> {
				barrier.await();
				return mvc.perform(multipart("/api/v1/patients/" + patient + "/files")
						.file(new MockMultipartFile("file", "exame.png", "image/png", Arrays.copyOf(PNG_HEAD, 64))).param("kind", "OTHER")
						.with(as(clinic, Role.RECEPTIONIST))).andReturn().getResponse().getStatus();
			});
			Future<Integer> anonymization = pool.submit(() -> {
				barrier.await();
				return mvc.perform(post("/api/v1/patients/" + patient + "/anonymize").with(as(clinic, Role.DENTIST_ADMIN)))
						.andReturn().getResponse().getStatus();
			});
			int uploadStatus = upload.get();
			int anonymizeStatus = anonymization.get();
			pool.shutdown();

			boolean anonymized = patientRow(patient).get("anonymized_at") != null;
			Path patientDir = STORAGE_DIR.resolve(clinic.id().toString()).resolve(patient);
			long objects;
			if (Files.exists(patientDir)) {
				try (Stream<Path> children = Files.list(patientDir)) {
					objects = children.count();
				}
			}
			else {
				objects = 0;
			}
			long rows = DatabaseContainer.admin().queryForObject("SELECT count(*) FROM core.patient_files WHERE patient_id = ?::uuid",
					Long.class, patient);
			String label = "rodada " + round + " (upload=" + uploadStatus + ", anonimização=" + anonymizeStatus + ")";

			assertThat(List.of(uploadStatus, anonymizeStatus)).as(label).isIn(List.of(201, 409), List.of(409, 200));
			if (anonymized) {
				assertThat(anonymizeStatus).as(label).isEqualTo(200);
				assertThat(objects).as("paciente anonimizado sem objeto no Storage: " + label).isZero();
				assertThat(rows).as("sem exame gravado: " + label).isZero();
			}
			else {
				assertThat(uploadStatus).as(label).isEqualTo(201);
				assertThat(objects).as("objeto e linha andam juntos: " + label).isEqualTo(1);
				assertThat(rows).as(label).isEqualTo(1);
			}
		}
	}

	// ---- logs ----

	@Test
	void logsAndEventsCarryNoNameCpfObjectKeyOrFilename() throws Exception {
		Clinic clinic = newClinic();
		String patient = createPatient(clinic, "Maria Anonimizavel", CPF);
		addFile(clinic, patient, "exame-secreto.png");
		deactivate(clinic, patient);
		PatientFixtures.backdateClinicalRecords(patient, 21);
		Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		root.addAppender(appender);
		try {
			anonymize(clinic, patient, 200);
			doThrow(new StorageException("Falha ao apagar objetos no Storage (HTTP 500).")).when(storage).deleteAll(any());
			String second = createPatient(clinic, "Maria Anonimizavel", null);
			addFile(clinic, second, "exame-secreto.png");
			deactivate(clinic, second);
			PatientFixtures.backdateClinicalRecords(second, 21);
			anonymize(clinic, second, 200);
		}
		finally {
			root.detachAppender(appender);
		}

		assertThat(appender.list).anySatisfy(e -> assertThat(e.getFormattedMessage()).contains("Paciente anonimizado: clinicId=" + clinic.id()));
		assertThat(appender.list).allSatisfy(event -> {
			assertThat(event.getFormattedMessage()).doesNotContain("Maria Anonimizavel", CPF, "exame-secreto", clinic.id() + "/" + patient);
			for (IThrowableProxy t = event.getThrowableProxy(); t != null; t = t.getCause()) {
				assertThat(t.getMessage()).doesNotContain("Maria Anonimizavel", CPF, "exame-secreto");
			}
		});
		assertThat(applicationEvents.stream()).allSatisfy(event -> assertThat(event.toString()).doesNotContain("Maria Anonimizavel", CPF, "exame-secreto"));
	}

	// ---- trilha de auditoria ----

	@Test
	void auditTrailPagesFiltersByRecordShowsUserNamesAndNeverValues() throws Exception {
		Clinic clinic = newClinic();
		String patient = createPatient(clinic, "Maria Souza", CPF);
		addHistory(clinic, patient);
		String note = addNote(clinic, patient);
		deactivate(clinic, patient);
		PatientFixtures.backdateClinicalRecords(patient, 21);
		anonymize(clinic, patient, 200);

		MvcResult all = mvc.perform(get("/api/v1/audit-log").param("size", "50").with(as(clinic, Role.DENTIST_ADMIN)))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andReturn();
		JsonNode content = read(all).get("content");
		assertThat(content.size()).isGreaterThanOrEqualTo(6);
		assertThat(content.get(0).get("action").asString()).as("mais recente primeiro: ANONYMIZE é a última linha da transação").isEqualTo("ANONYMIZE");
		for (JsonNode entry : content) {
			assertThat(entry.size()).as("só identificadores: %s", entry).isEqualTo(7);
			assertThat(entry.get("userName").asString()).startsWith("Usuário ");
		}
		assertThat(all.getResponse().getContentAsString()).doesNotContain("Maria Souza", CPF, "penicilina", "dente 36", "clinicId");

		MvcResult patientTrail = mvc.perform(get("/api/v1/audit-log").param("tableName", "patients").param("recordId", patient)
				.with(as(clinic, Role.DENTIST_ADMIN))).andExpect(status().isOk()).andReturn();
		List<String> actions = new ArrayList<>();
		read(patientTrail).get("content").forEach(entry -> actions.add(entry.get("action").asString()));
		assertThat(actions).startsWith("ANONYMIZE").contains("UPDATE", "INSERT");
		assertThat(actions.indexOf("ANONYMIZE")).isLessThan(actions.indexOf("INSERT"));
		read(patientTrail).get("content").forEach(entry -> assertThat(entry.get("recordId").asString()).isEqualTo(patient));

		mvc.perform(get("/api/v1/audit-log").param("tableName", "clinical_notes").param("recordId", note).with(as(clinic, Role.DENTIST_ADMIN)))
				.andExpect(jsonPath("$.content[0].action").value("DELETE"))
				.andExpect(jsonPath("$.content[1].action").value("INSERT"))
				.andExpect(jsonPath("$.totalElements").value(2));

		mvc.perform(get("/api/v1/audit-log").param("size", "2").param("page", "1").with(as(clinic, Role.DENTIST_ADMIN)))
				.andExpect(jsonPath("$.content.length()").value(2)).andExpect(jsonPath("$.page").value(1))
				.andExpect(jsonPath("$.totalPages").value(org.hamcrest.Matchers.greaterThan(1)));
	}

	@Test
	void auditTrailNeverCrossesClinicsAndRejectsBadFilters() throws Exception {
		Clinic a = newClinic();
		Clinic b = newClinic();
		String patientOfA = createPatient(a, "Maria Souza", null);
		createPatient(b, "Pedro Reis", null);

		mvc.perform(get("/api/v1/audit-log").param("tableName", "patients").param("recordId", patientOfA).with(as(b, Role.DENTIST_ADMIN)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
		MvcResult own = mvc.perform(get("/api/v1/audit-log").param("size", "50").with(as(b, Role.DENTIST_ADMIN))).andReturn();
		read(own).get("content").forEach(entry -> assertThat(entry.get("recordId").asString()).isNotEqualTo(patientOfA));

		mvc.perform(get("/api/v1/audit-log").param("recordId", patientOfA).with(as(a, Role.DENTIST_ADMIN))).andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/audit-log").param("tableName", "users").with(as(a, Role.DENTIST_ADMIN))).andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/audit-log").param("size", "51").with(as(a, Role.DENTIST_ADMIN))).andExpect(status().isBadRequest());
	}
}
