package crm_data.phase2;

import static crm_data.phase2.AgendaDates.at;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import crm_data.shared.security.Role;
import crm_data.support.DatabaseContainer;
import crm_data.support.PerfLimits;

/**
 * Desempenho da agenda com 20 mil agendamentos numa clínica (~8 por dia durante ~7 anos, sem sobreposição): a agenda da
 * semana e a criação com p95 dentro do limite (meta do PRD: 100 ms; 200 ms com {@code CI}; {@code -Dperf.limit-ms}
 * sobrescreve) e o plano das consultas sem {@code Seq Scan}. As 4 requisições concorrentes são do PRD global.
 */
class SchedulingPerformanceIT extends Phase2ItSupport {

	private static final long LIMIT_MS = PerfLimits.p95Ms();

	private static final int DAYS = 2_500;
	private static final int PER_DAY = 8;

	/** Clínica aberta, com um paciente que concentra os 20 mil (o histórico dele também é consultado). */
	private Setup seeded() throws Exception {
		Setup setup = openClinic();
		var connectionSource = DatabaseContainer.admin().getDataSource();
		try (var connection = connectionSource.getConnection(); var statement = connection.createStatement()) {
			statement.execute("SELECT set_config('app.clinic_id', '%s', false), set_config('app.user_id', '%s', false), set_config('app.role', 'DENTIST_ADMIN', false)"
					.formatted(setup.clinic().id(), userOf(setup.clinic(), Role.DENTIST_ADMIN)));
			statement.execute("""
					INSERT INTO core.appointments (id, clinic_id, patient_id, starts_at, ends_at, status, created_by)
					SELECT gen_random_uuid(), '%s', '%s',
					       timestamptz '2027-01-04 11:00:00+00' + make_interval(days => d, hours => k),
					       timestamptz '2027-01-04 11:00:00+00' + make_interval(days => d, hours => k, mins => 50),
					       'COMPLETED', '%s'
					FROM generate_series(0, %d) d, generate_series(0, %d) k"""
					.formatted(setup.clinic().id(), setup.patient(), userOf(setup.clinic(), Role.DENTIST_ADMIN), DAYS - 1, PER_DAY - 1));
			statement.execute("ANALYZE core.appointments");
		}
		assertThat(DatabaseContainer.admin().queryForObject("SELECT count(*) FROM core.appointments WHERE clinic_id = ?", Long.class,
				setup.clinic().id())).isEqualTo((long) DAYS * PER_DAY);
		return setup;
	}

	private String plan(Setup setup, String sql) {
		return String.join("\n", asSession(setup.clinic(), userOf(setup.clinic(), Role.RECEPTIONIST), Role.RECEPTIONIST,
				() -> jdbc.queryForList("EXPLAIN " + sql, String.class)));
	}

	/** p95 (ms) de {@code threads × perThread} requisições concorrentes, depois de um aquecimento. */
	private long p95(int threads, int perThread, IntFunction<MockHttpServletRequestBuilder> request, int expectedStatus) throws Exception {
		for (int i = 0; i < 5; i++) {
			mvc.perform(request.apply(10_000 + i)).andExpect(status().is(expectedStatus));
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
					int code = mvc.perform(request.apply(thread * perThread + i)).andReturn().getResponse().getStatus();
					times.add((System.nanoTime() - start) / 1_000_000);
					assertThat(code).isEqualTo(expectedStatus);
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

	@Test
	void theWeekAgendaOf20ThousandAppointmentsIsFastAndUsesTheIndex() throws Exception {
		Setup setup = seeded();
		LocalDate from = LocalDate.of(2029, 6, 4); // uma segunda-feira no meio do período semeado
		LocalDate to = from.plusDays(6);

		long p95 = p95(4, 25, i -> get("/api/v1/appointments").param("from", from.plusDays(i % 20).toString())
				.param("to", to.plusDays(i % 20).toString()).with(as(setup.clinic(), Role.RECEPTIONIST)), 200);

		System.out.println("PERF agenda da semana: p95=" + p95 + " ms (limite " + LIMIT_MS + " ms)");
		assertThat(p95).as("p95 da agenda da semana (ms), limite %d", LIMIT_MS).isLessThanOrEqualTo(LIMIT_MS);
		String week = plan(setup, """
				SELECT * FROM core.appointments WHERE clinic_id = '%s' AND starts_at >= timestamptz '2029-06-03 15:00+00' - interval '12 hours'
				AND starts_at < timestamptz '2029-06-11 03:00+00' AND ends_at > timestamptz '2029-06-03 15:00+00' ORDER BY starts_at, id"""
				.formatted(setup.clinic().id()));
		assertThat(week).as("plano da agenda da semana:\n%s", week).doesNotContain("Seq Scan on appointments").contains("ix_appt_clinic_start");
	}

	@Test
	void thePatientHistoryAndTheOpenFutureLookupUseTheirIndex() throws Exception {
		Setup setup = seeded();

		String history = plan(setup, """
				SELECT * FROM core.appointments WHERE clinic_id = '%s' AND patient_id = '%s' ORDER BY starts_at DESC, id DESC LIMIT 20"""
				.formatted(setup.clinic().id(), setup.patient()));
		String openFuture = plan(setup, """
				SELECT * FROM core.appointments WHERE clinic_id = '%s' AND patient_id = '%s' AND ends_at > now()
				AND status IN ('SCHEDULED','CONFIRMED') ORDER BY starts_at, id""".formatted(setup.clinic().id(), setup.patient()));

		assertThat(history).as("plano do histórico:\n%s", history).doesNotContain("Seq Scan on appointments").contains("ix_appt_patient");
		assertThat(openFuture).as("plano dos futuros em aberto:\n%s", openFuture).doesNotContain("Seq Scan on appointments");
	}

	@Test
	void bookingWithTheRulesAppliedIsFastWith20ThousandAppointmentsAndFourConcurrentClients() throws Exception {
		Setup setup = seeded();
		LocalDate base = LocalDate.of(2040, 1, 1).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));

		long p95 = p95(4, 12, n -> {
			LocalDate day = base.plusWeeks(n / 20).plusDays((n / 4) % 5);
			int hour = 8 + n % 4;
			return post("/api/v1/appointments").contentType("application/json").content(
					"{\"patientId\":\"%s\",\"startsAt\":\"%s\",\"endsAt\":\"%s\"}".formatted(setup.patient(), at(day, hour, 0), at(day, hour, 45)))
					.with(as(setup.clinic(), Role.RECEPTIONIST));
		}, 201);

		System.out.println("PERF criação: p95=" + p95 + " ms (limite " + LIMIT_MS + " ms)");
		assertThat(p95).as("p95 da criação (ms), limite %d", LIMIT_MS).isLessThanOrEqualTo(LIMIT_MS);
		assertThat(UUID.fromString(setup.patient())).isNotNull();
	}
}
