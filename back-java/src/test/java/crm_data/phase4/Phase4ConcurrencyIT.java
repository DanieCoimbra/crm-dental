package crm_data.phase4;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

import crm_data.shared.security.Role;
import crm_data.support.DatabaseContainer;
import tools.jackson.databind.JsonNode;

/**
 * Concorrência dos documentos clínicos contra o PostgreSQL real: numeração por clínica e tipo sob emissões simultâneas, uma
 * única anulação vencedora e PDFs simultâneos, cada um com o seu {@code DOWNLOAD}. 20 repetições de cada cenário.
 */
class Phase4ConcurrencyIT extends Phase4ItSupport {

	private static final int REPETITIONS = 20;

	/** Dispara as tarefas ao mesmo tempo (barreira) e devolve os resultados. */
	private static <T> List<T> together(List<Callable<T>> tasks) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
		CountDownLatch ready = new CountDownLatch(tasks.size());
		CountDownLatch go = new CountDownLatch(1);
		List<Future<T>> futures = new ArrayList<>();
		for (Callable<T> task : tasks) {
			futures.add(pool.submit(() -> {
				ready.countDown();
				go.await();
				return task.call();
			}));
		}
		ready.await();
		go.countDown();
		List<T> results = new ArrayList<>();
		for (Future<T> future : futures) {
			results.add(future.get());
		}
		pool.shutdown();
		return results;
	}

	private String certificateBody() {
		return "{\"documentType\":\"CERTIFICATE\",\"restDays\":1}";
	}

	@Test
	void simultaneousIssuesGetDistinctSequentialNumbersWithoutAnyError() throws Exception {
		for (int round = 0; round < REPETITIONS; round++) {
			Setup setup = openDentistSetup();
			List<Callable<Integer>> issues = new ArrayList<>();
			for (int i = 0; i < 2; i++) {
				issues.add(() -> {
					var result = mvc.perform(json(post("/api/v1/patients/" + setup.patient() + "/clinical-documents"), certificateBody())
							.with(as(setup.clinic(), Role.DENTIST_ADMIN))).andReturn();
					assertThat(result.getResponse().getStatus()).isEqualTo(201);
					return read(result).get("number").asInt();
				});
			}

			List<Integer> numbers = together(issues);

			assertThat(numbers).as("rodada %d", round).containsExactlyInAnyOrder(1, 2);
			assertThat(DatabaseContainer.admin().queryForList(
					"SELECT number FROM core.clinical_documents WHERE clinic_id = ? ORDER BY number", Integer.class, setup.clinic().id()))
							.containsExactly(1, 2);
		}
	}

	@Test
	void differentTypesAndClinicsNeverWaitForEachOtherAndKeepTheirOwnSequences() throws Exception {
		Setup a = openDentistSetup();
		Setup b = openDentistSetup();
		List<Callable<String>> tasks = new ArrayList<>();
		for (Setup s : List.of(a, b)) {
			for (String body : List.of(certificateBody(), "{\"documentType\":\"PRESCRIPTION\",\"content\":\"x\"}")) {
				tasks.add(() -> {
					var result = mvc.perform(json(post("/api/v1/patients/" + s.patient() + "/clinical-documents"), body)
							.with(as(s.clinic(), Role.DENTIST_ADMIN))).andReturn();
					assertThat(result.getResponse().getStatus()).isEqualTo(201);
					return read(result).get("documentType").asString() + ":" + read(result).get("number").asInt();
				});
			}
		}

		assertThat(together(tasks)).containsExactlyInAnyOrder("CERTIFICATE:1", "PRESCRIPTION:1", "CERTIFICATE:1", "PRESCRIPTION:1");
	}

	@Test
	void simultaneousVoidsHaveExactlyOneWinnerAndOneAuditRow() throws Exception {
		for (int round = 0; round < REPETITIONS; round++) {
			Setup setup = openDentistSetup();
			JsonNode doc = read(mvc.perform(json(post("/api/v1/patients/" + setup.patient() + "/clinical-documents"), certificateBody())
					.with(as(setup.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isCreated()).andReturn());
			String id = doc.get("id").asString();
			List<Callable<Integer>> voids = new ArrayList<>();
			for (int i = 0; i < 2; i++) {
				voids.add(() -> mvc.perform(json(post("/api/v1/clinical-documents/" + id + "/void"), "{\"version\":%d}".formatted(doc.get("version").asLong()))
						.with(as(setup.clinic(), Role.DENTIST_ADMIN))).andReturn().getResponse().getStatus());
			}

			List<Integer> codes = together(voids);

			assertThat(codes).as("rodada %d", round).containsExactlyInAnyOrder(200, 409);
			assertThat(DatabaseContainer.admin().queryForObject(
					"SELECT count(*) FROM core.audit_log WHERE record_id = ?::uuid AND action = 'VOID'", Integer.class, id)).isEqualTo(1);
		}
	}

	@Test
	void simultaneousPdfsAllSucceedAndEachRecordsItsOwnDownload() throws Exception {
		for (int round = 0; round < REPETITIONS; round++) {
			Setup setup = openDentistSetup();
			String id = read(mvc.perform(json(post("/api/v1/patients/" + setup.patient() + "/clinical-documents"), certificateBody())
					.with(as(setup.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isCreated()).andReturn()).get("id").asString();
			List<Callable<Integer>> pdfs = new ArrayList<>();
			for (int i = 0; i < 3; i++) {
				pdfs.add(() -> mvc.perform(get("/api/v1/clinical-documents/" + id + "/pdf").with(as(setup.clinic(), Role.DENTIST_ADMIN)))
						.andReturn().getResponse().getStatus());
			}

			assertThat(together(pdfs)).as("rodada %d", round).containsExactly(200, 200, 200);
			assertThat(DatabaseContainer.admin().queryForObject(
					"SELECT count(*) FROM core.audit_log WHERE record_id = ?::uuid AND action = 'DOWNLOAD'", Integer.class, id)).isEqualTo(3);
		}
	}
}
