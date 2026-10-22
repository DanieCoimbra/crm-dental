package crm_data.patient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import crm_data.patient.repository.PatientRepository;
import crm_data.patient.service.PatientLookup;
import crm_data.patient.service.PatientService;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ProblemTypes;
import crm_data.shared.security.Role;
import crm_data.shared.tenant.TenantContext;
import crm_data.support.DatabaseContainer;
import crm_data.support.PerfLimits;
import crm_data.support.tenant.PatientFixtures;
import crm_data.support.tenant.TenantFixtures;
import crm_data.support.tenant.TenantFixtures.Clinic;
import jakarta.persistence.EntityManager;

/** F02 ponta a ponta com PostgreSQL real (RLS, grants por coluna, índices): fluxo, busca, isolamento e desempenho. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PatientsIT {

	private static final String CPF = "52998224725";
	private static final String CPF_2 = "11144477735";
	/** Meta do contrato: busca p95 ≤ 100 ms; folga maior em CI e sobrescrevível por -Dperf.limit-ms (ver PerfLimits). */
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
	PlatformTransactionManager transactionManager;
	@Autowired
	PatientRepository repository;
	@Autowired
	EntityManager entityManager;
	@Autowired
	PatientService patientService;
	@Autowired
	PatientLookup lookup;

	@AfterEach
	void cleanUp() {
		TenantContext.clear();
		org.springframework.security.core.context.SecurityContextHolder.clearContext();
	}

	private static RequestPostProcessor as(Clinic clinic, Role role) {
		return jwt().jwt(j -> j.subject(clinic.userIds().get(0).toString()).claim("clinic_id", clinic.id().toString())
				.claim("role", role.name())).authorities(new SimpleGrantedAuthority("ROLE_" + role.name()));
	}

	private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
		return request.contentType(MediaType.APPLICATION_JSON).content(body);
	}

	private static String body(String name, String cpf, String phone) {
		return """
				{"name":"%s","cpf":%s,"birthDate":"1988-04-12","phone":"%s","email":"Maria@Exemplo.com"}"""
				.formatted(name, cpf == null ? "null" : "\"" + cpf + "\"", phone);
	}

	private JsonNode create(Clinic clinic, Role role, String name, String cpf) throws Exception {
		MvcResult result = mvc.perform(json(post("/api/v1/patients"), body(name, cpf, "(11) 98765-4321")).with(as(clinic, role)))
				.andExpect(status().isCreated()).andReturn();
		return mapper.readTree(result.getResponse().getContentAsString());
	}

	// ---- cadastro ----

	@Test
	void dentistAndReceptionistCreatePatientsWithServerSideFieldsAndNormalizedData() throws Exception {
		Clinic clinic = TenantFixtures.clinicWithUsers(1);

		JsonNode byDentist = create(clinic, Role.DENTIST_ADMIN, "  Maria Souza ", "529.982.247-25");
		JsonNode byReceptionist = create(clinic, Role.RECEPTIONIST, "João Lima", null);

		assertThat(byDentist.get("name").asString()).isEqualTo("Maria Souza");
		assertThat(byDentist.get("cpf").asString()).isEqualTo(CPF);
		assertThat(byDentist.get("phone").asString()).isEqualTo("11987654321");
		assertThat(byDentist.get("email").asString()).isEqualTo("maria@exemplo.com");
		assertThat(byDentist.get("sex").asString()).isEqualTo("NOT_INFORMED");
		assertThat(byDentist.get("active").asBoolean()).isTrue();
		assertThat(byDentist.get("createdAt").isNull()).isFalse();
		assertThat(byDentist.get("updatedAt").isNull()).isFalse();
		assertThat(byDentist.get("version").asLong()).isZero();
		assertThat(byDentist.has("clinicId")).isFalse();
		assertThat(byDentist.has("createdBy")).isFalse();
		assertThat(byReceptionist.get("cpf").isNull()).isTrue();
		Object createdBy = DatabaseContainer.admin().queryForObject("SELECT created_by FROM core.patients WHERE id = ?::uuid",
				Object.class, byDentist.get("id").asString());
		assertThat(createdBy.toString()).isEqualTo(clinic.userIds().get(0).toString());
	}

	@Test
	void duplicateCpfIsConflictWithoutTheCpfInTheMessageNorInAnyLog() throws Exception {
		Clinic clinic = TenantFixtures.clinicWithUsers(1);
		create(clinic, Role.DENTIST_ADMIN, "Maria Souza", CPF);

		Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		root.addAppender(appender);
		String response;
		try {
			response = mvc.perform(json(post("/api/v1/patients"), body("Outra Pessoa", CPF, "11987654321")).with(as(clinic, Role.RECEPTIONIST)))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.detail").value("CPF já cadastrado."))
					.andReturn().getResponse().getContentAsString();
		}
		finally {
			root.detachAppender(appender);
		}

		assertThat(response).doesNotContain(CPF);
		assertThat(appender.list).allSatisfy(event -> {
			assertThat(event.getFormattedMessage()).doesNotContain(CPF);
			for (IThrowableProxy t = event.getThrowableProxy(); t != null; t = t.getCause()) {
				assertThat(t.getMessage()).doesNotContain(CPF);
			}
		});
	}

	@Test
	void sameCpfIsAcceptedInAnotherClinic() throws Exception {
		Clinic a = TenantFixtures.clinicWithUsers(1);
		Clinic b = TenantFixtures.clinicWithUsers(1);

		create(a, Role.DENTIST_ADMIN, "Maria Souza", CPF);
		create(b, Role.DENTIST_ADMIN, "Maria Souza", CPF);
	}

	@Test
	void invalidCpfIs400OnTheCpfField() throws Exception {
		Clinic clinic = TenantFixtures.clinicWithUsers(1);

		mvc.perform(json(post("/api/v1/patients"), body("Maria", "52998224724", "11987654321")).with(as(clinic, Role.DENTIST_ADMIN)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("cpf"));
	}

	// ---- busca ----

	@Test
	void searchByNameFragmentIsCaseInsensitiveButNotAccentInsensitive() throws Exception {
		Clinic clinic = TenantFixtures.clinicWithUsers(1);
		create(clinic, Role.DENTIST_ADMIN, "José Alves", null);
		create(clinic, Role.DENTIST_ADMIN, "Joao Pereira", null);

		mvc.perform(get("/api/v1/patients").param("q", "JOSÉ").with(as(clinic, Role.RECEPTIONIST)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.content[0].name").value("José Alves"));
		mvc.perform(get("/api/v1/patients").param("q", "jose").with(as(clinic, Role.RECEPTIONIST)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
		mvc.perform(get("/api/v1/patients").param("q", "ao pe").with(as(clinic, Role.RECEPTIONIST)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
	}

	@Test
	void searchTreatsWildcardsAsText() throws Exception {
		Clinic clinic = TenantFixtures.clinicWithUsers(1);
		create(clinic, Role.DENTIST_ADMIN, "Ana 100% Silva", null);
		create(clinic, Role.DENTIST_ADMIN, "Ana_Maria", null);
		create(clinic, Role.DENTIST_ADMIN, "Ana Bia", null);

		mvc.perform(get("/api/v1/patients").param("q", "100%").with(as(clinic, Role.DENTIST_ADMIN)))
				.andExpect(jsonPath("$.totalElements").value(1));
		mvc.perform(get("/api/v1/patients").param("q", "a_m").with(as(clinic, Role.DENTIST_ADMIN)))
				.andExpect(jsonPath("$.totalElements").value(1));
		mvc.perform(get("/api/v1/patients").param("q", "%%").with(as(clinic, Role.DENTIST_ADMIN)))
				.andExpect(jsonPath("$.totalElements").value(0));
		mvc.perform(get("/api/v1/patients").param("q", "\\\\").with(as(clinic, Role.DENTIST_ADMIN)))
				.andExpect(jsonPath("$.totalElements").value(0));
	}

	@Test
	void queryWithElevenDigitsSearchesByCpfWithOrWithoutMask() throws Exception {
		Clinic clinic = TenantFixtures.clinicWithUsers(1);
		create(clinic, Role.DENTIST_ADMIN, "Maria Souza", CPF);
		create(clinic, Role.DENTIST_ADMIN, "Pedro Reis", CPF_2);

		for (String q : List.of(CPF, "529.982.247-25")) {
			mvc.perform(get("/api/v1/patients").param("q", q).with(as(clinic, Role.RECEPTIONIST)))
					.andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
					.andExpect(jsonPath("$.content[0].name").value("Maria Souza"));
		}
	}

	@Test
	void queryLengthRulesAndPagingLimits() throws Exception {
		Clinic clinic = TenantFixtures.clinicWithUsers(1);
		create(clinic, Role.DENTIST_ADMIN, "Abel Costa", null);

		mvc.perform(get("/api/v1/patients").param("q", "ab").with(as(clinic, Role.DENTIST_ADMIN)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
		mvc.perform(get("/api/v1/patients").param("q", "a").with(as(clinic, Role.DENTIST_ADMIN)))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.type").value("https://crm.local/problems/validation"));
		mvc.perform(get("/api/v1/patients").param("size", "51").with(as(clinic, Role.DENTIST_ADMIN)))
				.andExpect(status().isBadRequest());
	}

	@Test
	void statusFilterAndOrderingByNameThenId() throws Exception {
		Clinic clinic = TenantFixtures.clinicWithUsers(1);
		JsonNode zeca = create(clinic, Role.DENTIST_ADMIN, "Zeca", null);
		create(clinic, Role.DENTIST_ADMIN, "Bruno", null);
		create(clinic, Role.DENTIST_ADMIN, "Ana", null);
		mvc.perform(json(patch("/api/v1/patients/" + zeca.get("id").asString() + "/active"), "{\"active\":false}")
				.with(as(clinic, Role.DENTIST_ADMIN))).andExpect(status().isOk());

		mvc.perform(get("/api/v1/patients").with(as(clinic, Role.RECEPTIONIST)))
				.andExpect(jsonPath("$.totalElements").value(2))
				.andExpect(jsonPath("$.content[0].name").value("Ana"))
				.andExpect(jsonPath("$.content[1].name").value("Bruno"));
		mvc.perform(get("/api/v1/patients").param("status", "inactive").with(as(clinic, Role.RECEPTIONIST)))
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.content[0].name").value("Zeca"))
				.andExpect(jsonPath("$.content[0].active").value(false));
		mvc.perform(get("/api/v1/patients").param("status", "all").param("size", "2").with(as(clinic, Role.RECEPTIONIST)))
				.andExpect(jsonPath("$.totalElements").value(3))
				.andExpect(jsonPath("$.totalPages").value(2))
				.andExpect(jsonPath("$.content.length()").value(2));
	}

	// ---- edição e inativação ----

	@Test
	void updateBumpsVersionAndAStaleVersionIsConflict() throws Exception {
		Clinic clinic = TenantFixtures.clinicWithUsers(1);
		JsonNode created = create(clinic, Role.DENTIST_ADMIN, "Maria Souza", CPF);
		String path = "/api/v1/patients/" + created.get("id").asString();
		String update = """
				{"name":"Maria S. Lima","cpf":"%s","birthDate":"1988-04-12","phone":"11987654321","version":%d}""";

		mvc.perform(json(put(path), update.formatted(CPF, 0)).with(as(clinic, Role.RECEPTIONIST)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Maria S. Lima"))
				.andExpect(jsonPath("$.version").value(1));
		mvc.perform(json(put(path), update.formatted(CPF, 0)).with(as(clinic, Role.DENTIST_ADMIN)))
				.andExpect(status().isConflict());
		mvc.perform(get(path).with(as(clinic, Role.DENTIST_ADMIN))).andExpect(jsonPath("$.name").value("Maria S. Lima"));
	}

	@Test
	void updateToAnotherPatientsCpfIsConflict() throws Exception {
		Clinic clinic = TenantFixtures.clinicWithUsers(1);
		create(clinic, Role.DENTIST_ADMIN, "Maria Souza", CPF);
		JsonNode other = create(clinic, Role.DENTIST_ADMIN, "Pedro Reis", CPF_2);
		String update = """
				{"name":"Pedro Reis","cpf":"%s","birthDate":"1988-04-12","phone":"11987654321","version":0}""".formatted(CPF);

		mvc.perform(json(put("/api/v1/patients/" + other.get("id").asString()), update).with(as(clinic, Role.DENTIST_ADMIN)))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("CPF já cadastrado."));
	}

	@Test
	void deactivateReactivateAndIdempotency() throws Exception {
		Clinic clinic = TenantFixtures.clinicWithUsers(1);
		String path = "/api/v1/patients/" + create(clinic, Role.DENTIST_ADMIN, "Maria Souza", null).get("id").asString() + "/active";

		mvc.perform(json(patch(path), "{\"active\":false}").with(as(clinic, Role.DENTIST_ADMIN)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false))
				.andExpect(jsonPath("$.deactivatedAt").isNotEmpty());
		mvc.perform(json(patch(path), "{\"active\":false}").with(as(clinic, Role.DENTIST_ADMIN)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
		mvc.perform(json(patch(path), "{\"active\":true}").with(as(clinic, Role.DENTIST_ADMIN)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.active").value(true))
				.andExpect(jsonPath("$.deactivatedAt").doesNotExist());
		mvc.perform(json(patch(path), "{\"active\":false}").with(as(clinic, Role.RECEPTIONIST)))
				.andExpect(status().isForbidden());
	}

	@Test
	void anonymizedPatientCannotBeEditedNorReactivated() throws Exception {
		Clinic clinic = TenantFixtures.clinicWithUsers(1);
		JsonNode created = create(clinic, Role.DENTIST_ADMIN, "Maria Souza", null);
		String id = created.get("id").asString();
		// a anonimização é da F05; aqui só o estado final, montado pelo dono do schema
		anonymizeAsAdmin(clinic, id);

		mvc.perform(get("/api/v1/patients/" + id).with(as(clinic, Role.DENTIST_ADMIN)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.anonymizedAt").isNotEmpty());
		mvc.perform(json(put("/api/v1/patients/" + id), """
				{"name":"Maria Souza","birthDate":"1988-04-12","phone":"11987654321","version":%d}"""
				.formatted(created.get("version").asLong() + 1)).with(as(clinic, Role.DENTIST_ADMIN)))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("Paciente anonimizado."));
		mvc.perform(json(patch("/api/v1/patients/" + id + "/active"), "{\"active\":true}").with(as(clinic, Role.DENTIST_ADMIN)))
				.andExpect(status().isConflict());
	}

	// ---- PatientLookup (interface interna usada por F03, F04 e F05) ----

	@Test
	void lookupDistinguishesMissingActiveAnonymizedAndOtherClinicPatients() throws Exception {
		Clinic a = TenantFixtures.clinicWithUsers(1);
		Clinic b = TenantFixtures.clinicWithUsers(1);
		UUID active = UUID.fromString(create(a, Role.DENTIST_ADMIN, "Maria Souza", null).get("id").asString());
		JsonNode anonymizedJson = create(a, Role.DENTIST_ADMIN, "Pedro Reis", null);
		anonymizeAsAdmin(a, anonymizedJson.get("id").asString());
		UUID anonymized = UUID.fromString(anonymizedJson.get("id").asString());

		TenantContext.set(a.id(), a.userIds().get(0), Role.DENTIST_ADMIN);
		lookup.requireExists(active);
		lookup.requireWritable(active);
		lookup.requireExists(anonymized);
		assertThat(statusOf(() -> lookup.requireWritable(anonymized))).isEqualTo(ProblemTypes.CONFLICT);
		assertThat(statusOf(() -> lookup.requireExists(UUID.randomUUID()))).isEqualTo(ProblemTypes.NOT_FOUND);

		TenantContext.set(b.id(), b.userIds().get(0), Role.DENTIST_ADMIN);
		assertThat(statusOf(() -> lookup.requireExists(active))).isEqualTo(ProblemTypes.NOT_FOUND);
		assertThat(statusOf(() -> lookup.requireWritable(active))).isEqualTo(ProblemTypes.NOT_FOUND);
	}

	private static ProblemTypes statusOf(Runnable call) {
		try {
			call.run();
		}
		catch (BusinessException ex) {
			return ex.type();
		}
		throw new AssertionError("era esperada uma BusinessException");
	}

	// ---- segunda barreira de papel (@PreAuthorize no service) ----

	@Test
	void serviceDeniesTheReceptionistOnSetActiveEvenWithoutTheRouteRule() {
		Clinic clinic = TenantFixtures.clinicWithUsers(1);
		TenantContext.set(clinic.id(), clinic.userIds().get(0), Role.RECEPTIONIST);
		org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
				new org.springframework.security.authentication.TestingAuthenticationToken("recepcao", "n/a", "ROLE_RECEPTIONIST"));

		org.assertj.core.api.Assertions.assertThatThrownBy(() -> patientService.setActive(UUID.randomUUID(), false))
				.isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
	}

	// ---- isolamento ----

	@Test
	void idsOfAnotherClinicAre404OnGetPutAndPatch() throws Exception {
		Clinic a = TenantFixtures.clinicWithUsers(1);
		Clinic b = TenantFixtures.clinicWithUsers(1);
		String id = create(a, Role.DENTIST_ADMIN, "Maria Souza", CPF).get("id").asString();
		String path = "/api/v1/patients/" + id;

		mvc.perform(get(path).with(as(b, Role.DENTIST_ADMIN))).andExpect(status().isNotFound());
		mvc.perform(json(put(path), """
				{"name":"Invasor","birthDate":"1988-04-12","phone":"11987654321","version":0}""").with(as(b, Role.DENTIST_ADMIN)))
				.andExpect(status().isNotFound());
		mvc.perform(json(patch(path + "/active"), "{\"active\":false}").with(as(b, Role.DENTIST_ADMIN)))
				.andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/patients").param("status", "all").with(as(b, Role.DENTIST_ADMIN)))
				.andExpect(jsonPath("$.totalElements").value(0));
		mvc.perform(get("/api/v1/patients").param("q", CPF).with(as(b, Role.DENTIST_ADMIN)))
				.andExpect(jsonPath("$.totalElements").value(0));
	}

	@Test
	void rowLevelSecurityHidesOtherClinicsEvenWithTheHibernateFilterOff() throws Exception {
		Clinic a = TenantFixtures.clinicWithUsers(1);
		Clinic b = TenantFixtures.clinicWithUsers(1);
		UUID id = UUID.fromString(create(a, Role.DENTIST_ADMIN, "Maria Souza", CPF).get("id").asString());

		TenantContext.set(b.id(), b.userIds().get(0), Role.DENTIST_ADMIN);
		TransactionTemplate tx = new TransactionTemplate(transactionManager);
		long visible = tx.execute(s -> {
			entityManager.unwrap(Session.class).disableFilter("tenantFilter");
			return repository.findAll().stream().filter(p -> p.getId().equals(id)).count()
					+ (repository.findById(id).isPresent() ? 1 : 0)
					+ repository.anonymizedFlag(id).size();
		});

		assertThat(visible).isZero();
	}

	// ---- desempenho ----

	@Test
	void searchP95WithTenThousandPatientsAndFourConcurrentRequests() throws Exception {
		Clinic clinic = TenantFixtures.clinicWithUsers(1);
		seedPatients(clinic, 10_000);
		RequestPostProcessor token = as(clinic, Role.RECEPTIONIST);
		for (int i = 0; i < 10; i++) { // aquecimento
			search(token, "silva " + i);
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
					search(token, i % 2 == 0 ? "paciente " + (thread * 1000 + i) : "silva");
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
		System.out.printf("F02 busca p95 = %d ms (limite %d ms, %d pacientes, %d concorrentes)%n", p95, LIMIT_MS, 10_000, concurrent);

		assertThat(p95).as("p95 da busca em ms").isLessThanOrEqualTo(LIMIT_MS);
	}

	private void search(RequestPostProcessor token, String q) throws Exception {
		mvc.perform(get("/api/v1/patients").param("q", q).with(token)).andExpect(status().isOk());
	}

	private static void seedPatients(Clinic clinic, int count) throws Exception {
		PatientFixtures.seed(clinic, count);
	}

	private static void anonymizeAsAdmin(Clinic clinic, String id) throws Exception {
		PatientFixtures.anonymize(clinic, id);
	}
}
