package crm_data.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import crm_data.support.DatabaseContainer;

/**
 * Meta da F04: login p95 ≤ 300 ms com até 4 requisições concorrentes (decisão de 2026-10-19: com 50 o BCrypt custo 12, limitado por CPU,
 * leva ~1,5 s; ver contract.md). Roda com o custo REAL do BCrypt (12, o padrão),
 * sem sobrescrever {@code crm.auth.bcrypt-strength}. Imprime as medidas para registro da decisão de custo.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LoginPerformanceIT {

	private static final String PASSWORD = "SenhaForte2026";
	/** 4 por padrão (meta do contrato); -Dperf.concurrent=50 reproduz a medição que embasou a decisão. */
	private static final int CONCURRENT = Integer.getInteger("perf.concurrent", 4);

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		DatabaseContainer.register(registry);
	}

	@Autowired
	MockMvc mvc;
	@org.springframework.beans.factory.annotation.Value("${crm.auth.bcrypt-strength}")
	int bcryptStrength;

	@Test
	void loginP95WithFourConcurrentRequests() throws Exception {
		String email = "perf-" + UUID.randomUUID() + "@teste.local";
		String register = "{\"clinicName\":\"Perf %s\",\"name\":\"Dra. Perf\",\"email\":\"%s\",\"password\":\"%s\"}"
				.formatted(UUID.randomUUID(), email, PASSWORD);
		assertThat(mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(register)
				.with(request -> {
					request.setRemoteAddr("10.9.0.1");
					return request;
				})).andReturn().getResponse().getStatus()).isEqualTo(201);

		for (int i = 0; i < 5; i++) { // aquecimento da JIT e do pool
			login(email, "10.9.1." + i);
		}

		ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT);
		CountDownLatch ready = new CountDownLatch(CONCURRENT);
		CountDownLatch go = new CountDownLatch(1);
		List<Future<Long>> futures = new ArrayList<>();
		for (int i = 0; i < CONCURRENT; i++) {
			String ip = "10.9.2." + (i + 1); // um IP por requisição: mede o login, não o limitador
			futures.add(pool.submit(() -> {
				ready.countDown();
				go.await();
				long start = System.nanoTime();
				int status = login(email, ip);
				assertThat(status).isEqualTo(200);
				return (System.nanoTime() - start) / 1_000_000;
			}));
		}
		ready.await();
		go.countDown();
		List<Long> latencies = new ArrayList<>();
		for (Future<Long> future : futures) {
			latencies.add(future.get());
		}
		pool.shutdownNow();
		Collections.sort(latencies);
		long p50 = latencies.get(latencies.size() / 2);
		long p95 = latencies.get((int) Math.ceil(latencies.size() * 0.95) - 1);
		System.out.printf("F04 login (BCrypt custo %d, %d concorrentes, %d CPUs): p50=%d ms p95=%d ms max=%d ms%n",
				bcryptStrength, CONCURRENT, Runtime.getRuntime().availableProcessors(), p50, p95, latencies.get(latencies.size() - 1));

		assertThat(p95).as("p95 do login em ms (limite: %d)", limitMs()).isLessThanOrEqualTo(limitMs());
	}

	/**
	 * A meta de 300 ms vale para o ambiente de referência (12 CPUs). O runner do GitHub Actions tem 4 CPUs e já gasta
	 * ~380 ms num único BCrypt custo 12, então no CI (variável CI) o limite sobe para 600 ms; sobrescrevível por -Dperf.limit-ms.
	 */
	private static long limitMs() {
		long fallback = System.getenv("CI") != null ? 600 : 300;
		return Long.getLong("perf.limit-ms", fallback);
	}

	private int login(String email, String ip) throws Exception {
		return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD))
				.with(request -> {
					request.setRemoteAddr(ip);
					return request;
				})).andReturn().getResponse().getStatus();
	}
}
