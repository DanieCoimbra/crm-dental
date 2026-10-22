package crm_data.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import crm_data.shared.error.DatabaseErrors;
import crm_data.shared.security.Role;
import crm_data.shared.tenant.TenantContext;
import crm_data.support.DatabaseContainer;
import crm_data.support.PerfLimits;
import crm_data.support.SqlRecordingDataSourceConfig;
import crm_data.support.tenant.PatientFixtures;
import crm_data.support.tenant.TenantFixtures;
import crm_data.support.tenant.TenantFixtures.Clinic;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** F03 ponta a ponta com PostgreSQL real: cifra em repouso, RLS por papel, linha do tempo, isolamento e desempenho. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(SqlRecordingDataSourceConfig.class)
class ClinicalRecordIT {

	private static final String SECRET_HISTORY = "Alergia grave a penicilina e látex";
	private static final String SECRET_NOTE = "Restauração do dente 36 com resina composta";
	private static final String BASE64 = "^[A-Za-z0-9+/]+={0,2}$";
	/** Meta do contrato: p95 ≤ 100 ms; folga maior em CI e sobrescrevível por -Dperf.limit-ms (ver PerfLimits). */
	private static final long LIMIT_MS = PerfLimits.p95Ms();

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		DatabaseContainer.register(registry);
	}

	@Autowired
	MockMvc mvc;
	@Autowired
	JsonMapper mapper;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	PlatformTransactionManager transactionManager;

	@AfterEach
	void cleanUp() {
		TenantContext.clear();
	}

	private static RequestPostProcessor as(Clinic clinic, Role role) {
		return jwt().jwt(j -> j.subject(clinic.userIds().get(0).toString()).claim("clinic_id", clinic.id().toString())
				.claim("role", role.name())).authorities(new SimpleGrantedAuthority("ROLE_" + role.name()));
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

	private JsonNode putHistory(Clinic clinic, String patientId, String text, Long version, int expectedStatus) throws Exception {
		String body = "{\"content\":\"%s\"%s}".formatted(text, version == null ? "" : ",\"version\":" + version);
		MvcResult result = mvc.perform(json(put("/api/v1/patients/" + patientId + "/medical-history"), body).with(as(clinic, Role.DENTIST_ADMIN)))
				.andExpect(status().is(expectedStatus)).andReturn();
		return mapper.readTree(result.getResponse().getContentAsString());
	}

	private JsonNode postNote(Clinic clinic, String patientId, String text, Instant notedAt) throws Exception {
		String body = "{\"content\":\"%s\"%s}".formatted(text, notedAt == null ? "" : ",\"notedAt\":\"" + notedAt + "\"");
		MvcResult result = mvc.perform(json(post("/api/v1/patients/" + patientId + "/notes"), body).with(as(clinic, Role.DENTIST_ADMIN)))
				.andExpect(status().isCreated()).andReturn();
		return mapper.readTree(result.getResponse().getContentAsString());
	}

	private <T> T asRole(Clinic clinic, Role role, Supplier<T> block) {
		TenantContext.set(clinic.id(), clinic.userIds().get(0), role);
		try {
			return new TransactionTemplate(transactionManager).execute(status -> block.get());
		}
		finally {
			TenantContext.clear();
		}
	}

	// ---- histórico ----

	@Test
	void historyIsCreatedThenUpdatedWithTheVersionAndNeverTrimmed() throws Exception {
		Clinic clinic = TenantFixtures.clinicWithUsers(1);
		String patient = createPatient(clinic);
		String path = "/api/v1/patients/" + patient + "/medical-history";

		mvc.perform(get(path).with(as(clinic, Role.DENTIST_ADMIN))).andExpect(status().isNotFound());
		JsonNode created = putHistory(clinic, patient, "  " + SECRET_HISTORY + "  ", null, 201);
		assertThat(created.get("content").asString()).isEqualTo("  " + SECRET_HISTORY + "  ");
		assertThat(created.get("version").asLong()).isZero();
		assertThat(created.get("createdAt").isNull()).isFalse();
		assertThat(created.get("updatedAt").isNull()).isFalse();
		assertThat(created.has("createdBy")).isFalse();
		assertThat(created.has("clinicId")).isFalse();

		putHistory(clinic, patient, "sem versão", null, 409);
		putHistory(clinic, patient, "versão errada", 7L, 409);
		JsonNode updated = putHistory(clinic, patient, "Atualizado", 0L, 200);
		assertThat(updated.get("version").asLong()).isEqualTo(1);
		putHistory(clinic, patient, "versão antiga", 0L, 409);

		mvc.perform(get(path).with(as(clinic, Role.DENTIST_ADMIN)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.content").value("Atualizado"));
	}

	@Test
	void clinicalTextIsEncryptedAtRestAsOpaqueBase64() throws Exception {
		Clinic clinic = TenantFixtures.clinicWithUsers(1);
		String patient = createPatient(clinic);
		putHistory(clinic, patient, SECRET_HISTORY, null, 201);
		postNote(clinic, patient, SECRET_NOTE, null);

		JdbcTemplate admin = DatabaseContainer.admin();
		String historyRaw = admin.queryForObject("SELECT content FROM core.medical_histories WHERE patient_id = ?::uuid", String.class, patient);
		String noteRaw = admin.queryForObject("SELECT content FROM core.clinical_notes WHERE patient_id = ?::uuid", String.class, patient);

		for (String raw : List.of(historyRaw, noteRaw)) {
			assertThat(raw).matches(BASE64).hasSizeGreaterThanOrEqualTo(40);
			assertThat(raw).doesNotContain("penicilina", "Restauração", "dente");
		}
	}

	@Test
	void tamperedCipherTextIs500WithoutDetailsOrClinicalText() throws Exception {
		Clinic clinic = TenantFixtures.clinicWithUsers(1);
		String patient = createPatient(clinic);
		putHistory(clinic, patient, SECRET_HISTORY, null, 201);
		PatientFixtures.corruptHistory(clinic, patient);

		mvc.perform(get("/api/v1/patients/" + patient + "/medical-history").with(as(clinic, Role.DENTIST_ADMIN)))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.type").value("https://crm.local/problems/internal"))
				.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("penicilina"))));
	}

	@Test
	void clinicalTextNeverReachesAnyLogLine() throws Exception {
		Clinic clinic = TenantFixtures.clinicWithUsers(1);
		String patient = createPatient(clinic);
		Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		root.addAppender(appender);
		try {
			putHistory(clinic, patient, SECRET_HISTORY, null, 201);
			putHistory(clinic, patient, SECRET_HISTORY + " 2", 99L, 409);
			JsonNode note = postNote(clinic, patient, SECRET_NOTE, null);
			mvc.perform(get("/api/v1/patients/" + patient + "/notes").with(as(clinic, Role.DENTIST_ADMIN))).andExpect(status().isOk());
			mvc.perform(json(put("/api/v1/patients/" + patient + "/notes/" + note.get("id").asString()),
					"{\"content\":\"%s\",\"notedAt\":\"%s\",\"version\":5}".formatted(SECRET_NOTE, Instant.now())).with(as(clinic, Role.DENTIST_ADMIN)))
					.andExpect(status().isConflict());
			PatientFixtures.corruptHistory(clinic, patient);
			mvc.perform(get("/api/v1/patients/" + patient + "/medical-history").with(as(clinic, Role.DENTIST_ADMIN)))
					.andExpect(status().isInternalServerError());
		}
		finally {
			root.detachAppender(appender);
		}

		assertThat(appender.list).allSatisfy(event -> {
			assertThat(event.getFormattedMessage()).doesNotContain("penicilina", "Restauração");
			for (IThrowableProxy t = event.getThrowableProxy(); t != null; t = t.getCause()) {
				assertThat(t.getMessage()).doesNotContain("penicilina", "Restauração");
			}
		});
	}

	// ---- recepção ----

	@Test
	void receptionistGets403OnEveryRouteAndTheDatabaseIsNotTouched() throws Exception {
		Clinic clinic = TenantFixtures.clinicWithUsers(1);
		String patient = createPatient(clinic);
		String base = "/api/v1/patients/" + patient;
		RequestPostProcessor r = as(clinic, Role.RECEPTIONIST);
		SqlRecordingDataSourceConfig.reset();

		mvc.perform(get(base + "/medical-history").with(r)).andExpect(status().isForbidden());
		mvc.perform(json(put(base + "/medical-history"), "{\"content\":\"x\"}").with(r)).andExpect(status().isForbidden());
		mvc.perform(json(post(base + "/notes"), "{\"content\":\"x\"}").with(r)).andExpect(status().isForbidden());
		mvc.perform(get(base + "/notes").with(r)).andExpect(status().isForbidden());
		mvc.perform(json(put(base + "/notes/" + UUID.randomUUID()),
				"{\"content\":\"x\",\"notedAt\":\"2026-01-01T00:00:00Z\",\"version\":0}").with(r)).andExpect(status().isForbidden());

		assertThat(SqlRecordingDataSourceConfig.prepared()).isEmpty();
	}

	@Test
	void forgedReceptionistRoleSeesNothingAndCannotWriteEvenWithoutTheHibernateFilter() throws Exception {
		Clinic clinic = TenantFixtures.clinicWithUsers(1);
		String patient = createPatient(clinic);
		putHistory(clinic, patient, SECRET_HISTORY, null, 201);
		postNote(clinic, patient, SECRET_NOTE, null);
		UUID user = clinic.userIds().get(0);

		for (String table : List.of("medical_histories", "clinical_notes")) {
			assertThat(asRole(clinic, Role.RECEPTIONIST, () -> jdbc.queryForObject("SELECT count(*) FROM core." + table, Long.class))).isZero();
			assertThat(asRole(clinic, Role.DENTIST_ADMIN, () -> jdbc.queryForObject("SELECT count(*) FROM core." + table, Long.class))).isEqualTo(1L);
			assertThat(asRole(clinic, null, () -> jdbc.queryForObject("SELECT count(*) FROM core." + table, Long.class))).isZero();
		}
		assertThat(asRole(clinic, Role.RECEPTIONIST,
				() -> jdbc.update("UPDATE core.medical_histories SET content = repeat('B', 48)"))).isZero();
		assertThat(asRole(clinic, Role.RECEPTIONIST,
				() -> jdbc.update("UPDATE core.clinical_notes SET content = repeat('B', 48)"))).isZero();
		assertThatThrownBy(() -> asRole(clinic, Role.RECEPTIONIST, () -> jdbc.update(
				"INSERT INTO core.clinical_notes (clinic_id, patient_id, content, created_by) VALUES (core.current_clinic_id(), ?::uuid, repeat('B', 48), ?)",
				patient, user)))
				.isInstanceOf(DataAccessException.class)
				.satisfies(error -> assertThat(DatabaseErrors.sqlState(error)).contains("42501"));
		assertThatThrownBy(() -> asRole(clinic, Role.RECEPTIONIST, () -> jdbc.update(
				"INSERT INTO core.medical_histories (clinic_id, patient_id, content, created_by) VALUES (core.current_clinic_id(), ?::uuid, repeat('B', 48), ?)",
				patient, user)))
				.isInstanceOf(DataAccessException.class);
	}

	// ---- notas ----

	@Test
	void notesAreListedNewestFirstAndEditedWithTheVersion() throws Exception {
		Clinic clinic = TenantFixtures.clinicWithUsers(1);
		String patient = createPatient(clinic);
		Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		postNote(clinic, patient, "antiga", base.minus(3, ChronoUnit.DAYS));
		JsonNode middle = postNote(clinic, patient, "meio", base.minus(1, ChronoUnit.DAYS));
		postNote(clinic, patient, "recente", base.plus(4, ChronoUnit.MINUTES)); // dentro da tolerância de 5 min
		String notes = "/api/v1/patients/" + patient + "/notes";

		mvc.perform(get(notes).with(as(clinic, Role.DENTIST_ADMIN)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(3))
				.andExpect(jsonPath("$.content[0].content").value("recente"))
				.andExpect(jsonPath("$.content[1].content").value("meio"))
				.andExpect(jsonPath("$.content[2].content").value("antiga"));
		mvc.perform(get(notes).param("size", "2").param("page", "1").with(as(clinic, Role.DENTIST_ADMIN)))
				.andExpect(jsonPath("$.content.length()").value(1)).andExpect(jsonPath("$.totalPages").value(2));
		mvc.perform(get(notes).param("size", "51").with(as(clinic, Role.DENTIST_ADMIN))).andExpect(status().isBadRequest());

		String edit = "{\"content\":\"meio corrigido\",\"notedAt\":\"%s\",\"version\":%d}";
		mvc.perform(json(put(notes + "/" + middle.get("id").asString()), edit.formatted(base, 0)).with(as(clinic, Role.DENTIST_ADMIN)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.content").value("meio corrigido")).andExpect(jsonPath("$.version").value(1));
		mvc.perform(json(put(notes + "/" + middle.get("id").asString()), edit.formatted(base, 0)).with(as(clinic, Role.DENTIST_ADMIN)))
				.andExpect(status().isConflict());
	}

	@Test
	void futureNotedAtBeyondFiveMinutesIs400() throws Exception {
		Clinic clinic = TenantFixtures.clinicWithUsers(1);
		String patient = createPatient(clinic);

		mvc.perform(json(post("/api/v1/patients/" + patient + "/notes"),
				"{\"content\":\"x\",\"notedAt\":\"%s\"}".formatted(Instant.now().plus(10, ChronoUnit.MINUTES))).with(as(clinic, Role.DENTIST_ADMIN)))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.type").value("https://crm.local/problems/validation"));
	}

	@Test
	void noteOfAnotherPatientOrClinicIs404() throws Exception {
		Clinic a = TenantFixtures.clinicWithUsers(1);
		Clinic b = TenantFixtures.clinicWithUsers(1);
		String patientA = createPatient(a);
		String otherPatientA = createPatient(a);
		String patientB = createPatient(b);
		String noteId = postNote(a, patientA, SECRET_NOTE, null).get("id").asString();
		String edit = "{\"content\":\"x\",\"notedAt\":\"2026-01-01T00:00:00Z\",\"version\":0}";

		mvc.perform(json(put("/api/v1/patients/" + otherPatientA + "/notes/" + noteId), edit).with(as(a, Role.DENTIST_ADMIN)))
				.andExpect(status().isNotFound());
		mvc.perform(json(put("/api/v1/patients/" + patientB + "/notes/" + noteId), edit).with(as(b, Role.DENTIST_ADMIN)))
				.andExpect(status().isNotFound());
		for (String path : List.of("/medical-history", "/notes")) {
			mvc.perform(get("/api/v1/patients/" + patientA + path).with(as(b, Role.DENTIST_ADMIN))).andExpect(status().isNotFound());
		}
		mvc.perform(json(post("/api/v1/patients/" + patientA + "/notes"), "{\"content\":\"x\"}").with(as(b, Role.DENTIST_ADMIN)))
				.andExpect(status().isNotFound());
		mvc.perform(json(put("/api/v1/patients/" + patientA + "/medical-history"), "{\"content\":\"x\"}").with(as(b, Role.DENTIST_ADMIN)))
				.andExpect(status().isNotFound());
	}

	@Test
	void anonymizedPatientBlocksHistoryAndNoteWrites() throws Exception {
		Clinic clinic = TenantFixtures.clinicWithUsers(1);
		String patient = createPatient(clinic);
		String noteId = postNote(clinic, patient, SECRET_NOTE, null).get("id").asString();
		PatientFixtures.anonymize(clinic, patient);

		mvc.perform(json(put("/api/v1/patients/" + patient + "/medical-history"), "{\"content\":\"x\"}").with(as(clinic, Role.DENTIST_ADMIN)))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("Paciente anonimizado."));
		mvc.perform(json(post("/api/v1/patients/" + patient + "/notes"), "{\"content\":\"x\"}").with(as(clinic, Role.DENTIST_ADMIN)))
				.andExpect(status().isConflict());
		mvc.perform(json(put("/api/v1/patients/" + patient + "/notes/" + noteId),
				"{\"content\":\"x\",\"notedAt\":\"2026-01-01T00:00:00Z\",\"version\":0}").with(as(clinic, Role.DENTIST_ADMIN)))
				.andExpect(status().isConflict());
	}

	@Test
	void inactivePatientStillAcceptsRecords() throws Exception {
		Clinic clinic = TenantFixtures.clinicWithUsers(1);
		String patient = createPatient(clinic);
		mvc.perform(json(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/patients/" + patient + "/active"),
				"{\"active\":false}").with(as(clinic, Role.DENTIST_ADMIN))).andExpect(status().isOk());

		putHistory(clinic, patient, "histórico de paciente inativo", null, 201);
		postNote(clinic, patient, "nota de paciente inativo", null);
	}

	@Test
	void unknownPatientIs404OnEveryRoute() throws Exception {
		Clinic clinic = TenantFixtures.clinicWithUsers(1);
		String base = "/api/v1/patients/" + UUID.randomUUID();
		RequestPostProcessor d = as(clinic, Role.DENTIST_ADMIN);

		mvc.perform(get(base + "/medical-history").with(d)).andExpect(status().isNotFound());
		mvc.perform(json(put(base + "/medical-history"), "{\"content\":\"x\"}").with(d)).andExpect(status().isNotFound());
		mvc.perform(get(base + "/notes").with(d)).andExpect(status().isNotFound());
		mvc.perform(json(post(base + "/notes"), "{\"content\":\"x\"}").with(d)).andExpect(status().isNotFound());
	}

	// ---- desempenho ----

	@Test
	void timelineP95With500NotesAndFourConcurrentRequests() throws Exception {
		Clinic clinic = TenantFixtures.clinicWithUsers(1);
		String patient = createPatient(clinic);
		String seed = postNote(clinic, patient, SECRET_NOTE, null).get("id").asString();
		PatientFixtures.cloneNote(clinic, seed, 499);
		RequestPostProcessor token = as(clinic, Role.DENTIST_ADMIN);
		String path = "/api/v1/patients/" + patient + "/notes";
		for (int i = 0; i < 10; i++) {
			mvc.perform(get(path).param("size", "50").with(token)).andExpect(status().isOk());
		}

		int concurrent = 4;
		int perThread = 25;
		ExecutorService pool = Executors.newFixedThreadPool(concurrent);
		CountDownLatch go = new CountDownLatch(1);
		List<Future<List<Long>>> futures = new ArrayList<>();
		for (int t = 0; t < concurrent; t++) {
			int thread = t;
			futures.add(pool.submit(() -> {
				go.await();
				List<Long> times = new ArrayList<>();
				for (int i = 0; i < perThread; i++) {
					long start = System.nanoTime();
					mvc.perform(get(path).param("size", "50").param("page", String.valueOf((thread + i) % 10)).with(token))
							.andExpect(status().isOk());
					times.add((System.nanoTime() - start) / 1_000_000);
				}
				return times;
			}));
		}
		go.countDown();
		List<Long> all = new ArrayList<>();
		for (Future<List<Long>> future : futures) {
			all.addAll(future.get());
		}
		pool.shutdown();
		Collections.sort(all);
		long p95 = all.get((int) Math.ceil(all.size() * 0.95) - 1);
		System.out.printf("F03 timeline p95 = %d ms (limite %d ms, 500 notas, %d concorrentes)%n", p95, LIMIT_MS, concurrent);

		assertThat(p95).as("p95 da linha do tempo em ms").isLessThanOrEqualTo(LIMIT_MS);
	}
}
