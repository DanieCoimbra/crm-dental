package crm_data.phase3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Import;

import crm_data.shared.security.Role;
import crm_data.shared.storage.FileStorage;
import crm_data.support.FaultyDocumentStorageConfig;
import crm_data.support.FaultyFileStorage;
import crm_data.support.PerfLimits;
import tools.jackson.databind.JsonNode;

/**
 * Pool de conexões sob envios lentos: o envio do PDF segura uma conexão enquanto o Storage responde (H1). Com 3 envios
 * simultâneos, cada um com o upload demorando 1,5 s (o semáforo de 3 do envio é o teto), as leituras de orçamento
 * continuam rápidas (p95 ≤ 200 ms) e nenhuma delas falha por falta de conexão. Limite proporcional a {@link PerfLimits}.
 */
@Import(FaultyDocumentStorageConfig.class)
class Phase3PoolIT extends Phase3ItSupport {

	@Autowired
	@Qualifier("documentStorage")
	FileStorage documentStorage;

	@AfterEach
	void reset() {
		((FaultyFileStorage) documentStorage).reset();
	}

	@Test
	void readsStayFastWhileThreeSlowSendsHoldTheirConnections() throws Exception {
		FaultyFileStorage storage = (FaultyFileStorage) documentStorage;
		Kit reader = kit();
		String readable = createBudget(reader, null, item(reader.restoration(), 1, null)).get("id").asString();
		List<Kit> senders = new ArrayList<>();
		List<JsonNode> drafts = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			Kit kit = kit();
			senders.add(kit);
			drafts.add(createBudget(kit, null, item(kit.restoration(), 1, null)));
		}
		for (int i = 0; i < 5; i++) { // aquece a leitura
			mvc.perform(get("/api/v1/budgets/" + readable).with(as(reader.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isOk());
		}
		storage.saveLatencyMillis = 1500;
		ExecutorService pool = Executors.newFixedThreadPool(3);
		AtomicBoolean sending = new AtomicBoolean(true);
		List<Future<Integer>> sends = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			Kit kit = senders.get(i);
			JsonNode draft = drafts.get(i);
			sends.add(pool.submit(() -> mvc.perform(json(post("/api/v1/budgets/" + draft.get("id").asString() + "/send"), "{\"version\":%d}".formatted(draft.get("version").asLong()))
					.with(as(kit.clinic(), Role.DENTIST_ADMIN))).andReturn().getResponse().getStatus()));
		}
		Thread.sleep(300); // os três envios já estão dentro do upload lento
		List<Long> samples = new ArrayList<>();
		int failed = 0;
		long deadline = System.currentTimeMillis() + 1000;
		while (System.currentTimeMillis() < deadline || samples.size() < 20) {
			long start = System.nanoTime();
			int code = mvc.perform(get("/api/v1/budgets/" + readable).with(as(reader.clinic(), Role.DENTIST_ADMIN))).andReturn().getResponse().getStatus();
			samples.add((System.nanoTime() - start) / 1_000_000);
			if (code != 200) {
				failed++;
			}
			if (samples.size() >= 200) {
				break;
			}
		}
		sending.set(false);
		for (Future<Integer> send : sends) {
			assertThat(send.get()).as("os envios lentos terminam bem").isEqualTo(200);
		}
		pool.shutdown();

		List<Long> sorted = samples.stream().sorted().toList();
		long p95 = sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1);
		System.out.println("P95 leitura-com-3-envios-lentos=" + p95 + " ms (" + samples.size() + " amostras)");
		assertThat(failed).as("nenhuma leitura falha por falta de conexão").isZero();
		assertThat(samples.size()).isGreaterThanOrEqualTo(20);
		assertThat(p95).as("p95 da leitura com 3 envios lentos (ms)").isLessThanOrEqualTo(2 * PerfLimits.p95Ms());
	}
}
