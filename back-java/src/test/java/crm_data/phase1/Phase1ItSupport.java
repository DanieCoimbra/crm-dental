package crm_data.phase1;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import crm_data.shared.security.Role;
import crm_data.shared.storage.FileStorage;
import crm_data.shared.tenant.TenantContext;
import crm_data.support.DatabaseContainer;
import crm_data.support.PerfLimits;
import crm_data.support.SqlRecordingDataSourceConfig;
import crm_data.support.tenant.TenantFixtures;
import crm_data.support.tenant.TenantFixtures.Clinic;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Base dos ITs da verificação da Fase 1. Todos compartilham a mesma configuração, então o Spring reaproveita um único
 * contexto (e um único contêiner PostgreSQL) entre as classes. Cada teste cria suas próprias clínicas, sem depender
 * de dados de outro. O {@link FileStorage} é o {@code LocalFileStorage} em disco, espionado para injetar falhas.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(SqlRecordingDataSourceConfig.class)
abstract class Phase1ItSupport {

	static final Path STORAGE_DIR = createStorageDir();
	static final byte[] PNG_HEAD = { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A };
	/** Meta do contrato: p95 ≤ 100 ms; folga maior em CI e sobrescrevível por {@code -Dperf.limit-ms} (ver {@link PerfLimits}). */
	static final long LIMIT_MS = PerfLimits.p95Ms();

	private static Path createStorageDir() {
		try {
			return Files.createTempDirectory("crm-it-phase1-");
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
	JdbcTemplate jdbc;
	@Autowired
	PlatformTransactionManager transactionManager;
	@MockitoSpyBean
	FileStorage storage;

	@AfterEach
	void clearContexts() {
		TenantContext.clear();
		SecurityContextHolder.clearContext();
	}

	// ---- clínicas e identidades ----

	/** Clínica com dois usuários: o índice 0 faz o papel de dentista, o 1 o de recepcionista. */
	static Clinic newClinic() {
		return TenantFixtures.clinicWithUsers(2);
	}

	static RequestPostProcessor as(Clinic clinic, Role role) {
		return Phase1Routes.token(clinic.id(), clinic.userIds().get(role == Role.DENTIST_ADMIN ? 0 : 1), role);
	}

	static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
		return request.contentType(MediaType.APPLICATION_JSON).content(body);
	}

	JsonNode read(MvcResult result) throws Exception {
		return mapper.readTree(result.getResponse().getContentAsString());
	}

	// ---- dados pela API (como o front faria) ----

	String createPatient(Clinic clinic, String name, String cpf) throws Exception {
		String body = "{\"name\":\"%s\",\"cpf\":%s,\"birthDate\":\"1988-04-12\",\"phone\":\"11987654321\"}"
				.formatted(name, cpf == null ? "null" : "\"" + cpf + "\"");
		MvcResult result = mvc.perform(json(post("/api/v1/patients"), body).with(as(clinic, Role.RECEPTIONIST)))
				.andExpect(status().isCreated()).andReturn();
		return read(result).get("id").asString();
	}

	void deactivate(Clinic clinic, String patient) throws Exception {
		mvc.perform(json(patch("/api/v1/patients/" + patient + "/active"), "{\"active\":false}").with(as(clinic, Role.DENTIST_ADMIN)))
				.andExpect(status().isOk());
	}

	void saveHistory(Clinic clinic, String patient, String text) throws Exception {
		mvc.perform(json(put("/api/v1/patients/" + patient + "/medical-history"), "{\"content\":\"" + text + "\"}")
				.with(as(clinic, Role.DENTIST_ADMIN))).andExpect(status().isCreated());
	}

	String addNote(Clinic clinic, String patient, String text) throws Exception {
		MvcResult result = mvc.perform(json(post("/api/v1/patients/" + patient + "/notes"), "{\"content\":\"" + text + "\"}")
				.with(as(clinic, Role.DENTIST_ADMIN))).andExpect(status().isCreated()).andReturn();
		return read(result).get("id").asString();
	}

	String addFile(Clinic clinic, String patient, String filename) throws Exception {
		MockMultipartFile file = new MockMultipartFile("file", filename, "image/png", Arrays.copyOf(PNG_HEAD, 64));
		MvcResult result = mvc.perform(multipart("/api/v1/patients/" + patient + "/files").file(file).param("kind", "RADIOGRAPH")
				.with(as(clinic, Role.RECEPTIONIST))).andExpect(status().isCreated()).andReturn();
		return read(result).get("id").asString();
	}

	void removeFile(Clinic clinic, String patient, String fileId) throws Exception {
		mvc.perform(delete("/api/v1/patients/" + patient + "/files/" + fileId).with(as(clinic, Role.DENTIST_ADMIN)))
				.andExpect(status().isNoContent());
	}

	// ---- banco e Storage ----

	/** Executa o bloco numa transação com o papel informado (e {@code null} para papel ausente), sem filtro Hibernate. */
	<T> T asRole(Clinic clinic, UUID userId, Role role, Supplier<T> block) {
		TenantContext.set(clinic.id(), userId, role);
		try {
			return new TransactionTemplate(transactionManager).execute(status -> block.get());
		}
		finally {
			TenantContext.clear();
		}
	}

	static long objectsOf(Clinic clinic) throws IOException {
		Path dir = STORAGE_DIR.resolve(clinic.id().toString());
		if (!Files.exists(dir)) {
			return 0;
		}
		try (Stream<Path> walk = Files.walk(dir)) {
			return walk.filter(Files::isRegularFile).count();
		}
	}

	// ---- desempenho ----

	/** p95 (ms) de {@code threads × perThread} requisições concorrentes, depois de um aquecimento. */
	long p95(int threads, int perThread, java.util.function.IntFunction<RequestBuilder> request) throws Exception {
		for (int i = 0; i < 10; i++) {
			mvc.perform(request.apply(i)).andExpect(status().isOk());
		}
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch go = new CountDownLatch(1);
		List<Future<List<Long>>> futures = new ArrayList<>();
		for (int t = 0; t < threads; t++) {
			int thread = t;
			futures.add(pool.submit(() -> {
				go.await();
				List<Long> times = new ArrayList<>();
				for (int i = 0; i < perThread; i++) {
					long start = System.nanoTime();
					mvc.perform(request.apply(thread * perThread + i)).andExpect(status().isOk());
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
		return all.get((int) Math.ceil(all.size() * 0.95) - 1);
	}

	/** Atalho para requisições GET autenticadas. */
	static RequestBuilder authenticatedGet(String path, RequestPostProcessor token, String... queryNameValue) {
		MockHttpServletRequestBuilder builder = get(path);
		for (int i = 0; i + 1 < queryNameValue.length; i += 2) {
			builder = builder.param(queryNameValue[i], queryNameValue[i + 1]);
		}
		return builder.with(token);
	}
}
