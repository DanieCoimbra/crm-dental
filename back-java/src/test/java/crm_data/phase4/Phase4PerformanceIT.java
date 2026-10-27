package crm_data.phase4;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import crm_data.shared.security.Role;
import crm_data.support.DatabaseContainer;
import crm_data.support.PerfLimits;
import crm_data.support.tenant.TenantFixtures.Clinic;

/**
 * Metas de desempenho da Fase 4 (p95) com massa grande: 100 mil agendamentos, 20 mil pacientes e 100 mil documentos
 * clínicos, com RLS ativa. Dashboard ≤ 150 ms (4 requisições concorrentes), emissão ≤ 100 ms, detalhe ≤ 50 ms, PDF ≤ 300 ms e
 * lista de 20 itens ≤ 50 ms. Os limites crescem na proporção de {@link PerfLimits} (dobram com {@code CI}). Os limites de
 * uso por minuto sobem para 600 só para medir sem esbarrar no 429.
 */
@TestPropertySource(properties = { "crm.clinical-documents.issues-per-minute=600", "crm.clinical-documents.pdfs-per-minute=600" })
class Phase4PerformanceIT extends Phase4ItSupport {

	private static final int WARMUP = 5;

	private static double scale() {
		return PerfLimits.p95Ms() / 100.0;
	}

	private static long limit(long baseMs) {
		return Math.round(baseMs * scale());
	}

	/** 20 mil pacientes, 100 mil agendamentos (slots de 5 min cobrindo ~347 dias ao redor de hoje) e 100 mil documentos. */
	private static void seed(Clinic clinic, UUID dentist) {
		String c = clinic.id().toString();
		String u = dentist.toString();
		seedSql("INSERT INTO core.patients (id, clinic_id, name, birth_date, phone, created_by) "
				+ "SELECT gen_random_uuid(), '" + c + "', 'Paciente ' || g, DATE '1990-01-01', '11987654321', '" + u
				+ "' FROM generate_series(1, 20000) g",
				"CREATE TEMP TABLE _p AS SELECT id, row_number() OVER (ORDER BY id) - 1 AS n FROM core.patients WHERE clinic_id = '" + c + "'",
				"INSERT INTO core.appointments (id, clinic_id, patient_id, starts_at, ends_at, status, created_by) "
						+ "SELECT gen_random_uuid(), '" + c + "', p.id, s.ts, s.ts + interval '5 minutes', "
						+ "(ARRAY['COMPLETED','CONFIRMED','CANCELED','NO_SHOW','SCHEDULED'])[1 + (s.g % 5)], '" + u + "' "
						+ "FROM (SELECT g, date_trunc('minute', now()) - interval '200 days' + (g * interval '5 minutes') AS ts "
						+ "FROM generate_series(0, 99999) g) s JOIN _p p ON p.n = s.g % 20000",
				"INSERT INTO core.clinical_documents (id, clinic_id, patient_id, document_type, number, issued_on, content, sha256, created_by) "
						+ "SELECT gen_random_uuid(), '" + c + "', p.id, 'PRESCRIPTION', g, current_date, repeat('A', 120), repeat('a', 64), '" + u
						+ "' FROM generate_series(1, 100000) g JOIN _p p ON p.n = g % 1000",
				"ANALYZE core.patients", "ANALYZE core.appointments", "ANALYZE core.clinical_documents");
	}

	private long measure(int samples, IntFunction<Integer> call) {
		List<Long> times = new ArrayList<>();
		for (int i = 0; i < WARMUP + samples; i++) {
			long start = System.nanoTime();
			int code = call.apply(i);
			long elapsed = (System.nanoTime() - start) / 1_000_000;
			assertThat(code).as("amostra %d", i).isBetween(200, 201);
			if (i >= WARMUP) {
				times.add(elapsed);
			}
		}
		return p95(times);
	}

	@Test
	void p95WithinTheTargetsOnLargeData() throws Exception {
		Setup setup = openDentistSetup();
		Clinic clinic = setup.clinic();
		UUID dentist = userOf(clinic, Role.DENTIST_ADMIN);
		seed(clinic, dentist);
		assertThat(DatabaseContainer.admin().queryForObject("SELECT count(*) FROM core.clinical_documents WHERE clinic_id = ?", Long.class, clinic.id()))
				.isEqualTo(100_000L);
		String patient = DatabaseContainer.admin().queryForObject("SELECT patient_id::text FROM core.clinical_documents WHERE clinic_id = ? LIMIT 1",
				String.class, clinic.id());
		String body = "{\"documentType\":\"PRESCRIPTION\",\"content\":\"" + "Tomar 1 comprimido a cada 8 horas. ".repeat(100) + "\"}";
		String created = read(mvc.perform(json(post("/api/v1/patients/" + patient + "/clinical-documents"), body).with(as(clinic, Role.DENTIST_ADMIN)))
				.andExpect(status().isCreated()).andReturn()).get("id").asString();

		long emission = measure(30, i -> call(post("/api/v1/patients/" + patient + "/clinical-documents"), body, clinic));
		long detail = measure(50, i -> call(get("/api/v1/clinical-documents/" + created), null, clinic));
		long pdf = measure(40, i -> call(get("/api/v1/clinical-documents/" + created + "/pdf"), null, clinic));
		long list = measure(50, i -> call(get("/api/v1/patients/" + patient + "/clinical-documents"), null, clinic));
		long dashboard = dashboardP95(clinic);

		System.out.printf("P95 fase4 (ms): dashboard=%d emissão=%d detalhe=%d pdf=%d lista=%d (escala %.1f)%n", dashboard, emission, detail, pdf, list,
				scale());
		assertThat(dashboard).as("dashboard p95 (4 concorrentes)").isLessThanOrEqualTo(limit(150));
		assertThat(emission).as("emissão p95").isLessThanOrEqualTo(limit(100));
		assertThat(detail).as("detalhe p95").isLessThanOrEqualTo(limit(50));
		assertThat(pdf).as("PDF p95").isLessThanOrEqualTo(limit(300));
		assertThat(list).as("lista p95").isLessThanOrEqualTo(limit(50));
	}

	private int call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, String body, Clinic clinic) {
		try {
			var builder = body == null ? request : json((org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder) request, body);
			return mvc.perform(builder.with(as(clinic, Role.DENTIST_ADMIN))).andReturn().getResponse().getStatus();
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private long dashboardP95(Clinic clinic) throws Exception {
		for (int i = 0; i < WARMUP; i++) {
			assertThat(call(get("/api/v1/dashboard"), null, clinic)).isEqualTo(200);
		}
		ExecutorService pool = Executors.newFixedThreadPool(4);
		List<Future<List<Long>>> futures = new ArrayList<>();
		for (int t = 0; t < 4; t++) {
			Callable<List<Long>> task = () -> {
				List<Long> times = new ArrayList<>();
				for (int i = 0; i < 15; i++) {
					long start = System.nanoTime();
					assertThat(call(get("/api/v1/dashboard"), null, clinic)).isEqualTo(200);
					times.add((System.nanoTime() - start) / 1_000_000);
				}
				return times;
			};
			futures.add(pool.submit(task));
		}
		List<Long> all = new ArrayList<>();
		for (Future<List<Long>> future : futures) {
			all.addAll(future.get());
		}
		pool.shutdown();
		Collections.sort(all);
		return p95(all);
	}
}
