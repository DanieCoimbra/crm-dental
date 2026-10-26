package crm_data.phase3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
 * Corridas da Fase 3 com requisições simultâneas de verdade (threads soltas por um {@code CountDownLatch}, sem
 * transação de teste), 20 repetições cada: numeração de orçamento, plano de parcelas e edição × envio. Nenhuma pode
 * responder 5xx; o estado final nunca é misto. As outras corridas estão nos ITs de F03–F07, com estas rodadas:
 * pagamento no último saldo, 20 ({@code PaymentsIT}); numeração e {@code PUT} com a mesma versão, 20 ({@code BudgetsIT});
 * dois envios da mesma versão, 10, e revisões simultâneas, 2 ({@code BudgetSendIT}); envio × anonimização, 10
 * ({@code CommercialAnonymizationIT}); planos simultâneos, 5 ({@code InstallmentsIT}); aprovação × recusa e estorno
 * repetido, 1 cada ({@code InstallmentsIT}, {@code PaymentsIT}). Ver {@code traceability.md}.
 */
class Phase3ConcurrencyIT extends Phase3ItSupport {

	private static final int ROUNDS = 20;

	private List<Integer> race(List<Callable<Integer>> calls) throws Exception {
		CountDownLatch go = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(calls.size());
		List<Future<Integer>> futures = new ArrayList<>();
		for (Callable<Integer> call : calls) {
			futures.add(pool.submit(() -> {
				go.await();
				return call.call();
			}));
		}
		go.countDown();
		List<Integer> statuses = new ArrayList<>();
		for (Future<Integer> future : futures) {
			statuses.add(future.get());
		}
		pool.shutdown();
		return statuses;
	}

	@Test
	void twoSimultaneousBudgetsAlwaysGetDistinctSequentialNumbers() throws Exception {
		Kit kit = kit();
		Set<Integer> numbers = new HashSet<>();
		for (int round = 0; round < ROUNDS; round++) {
			List<Callable<Integer>> calls = new ArrayList<>();
			List<String> bodies = new ArrayList<>();
			for (int i = 0; i < 2; i++) {
				calls.add(() -> {
					var response = mvc.perform(json(post("/api/v1/budgets"), budgetBody(kit.patient(), null, item(kit.restoration(), 1, null)))
							.with(as(kit.clinic(), Role.DENTIST_ADMIN))).andReturn().getResponse();
					synchronized (bodies) {
						bodies.add(response.getContentAsString());
					}
					return response.getStatus();
				});
			}
			assertThat(race(calls)).as("rodada %d", round).containsExactly(201, 201);
			for (String body : bodies) {
				assertThat(numbers.add(mapper.readTree(body).get("number").asInt())).as("rodada %d: número repetido", round).isTrue();
			}
		}
		List<Integer> sorted = numbers.stream().sorted().toList();
		assertThat(sorted).as("sem buracos: 1..40").isEqualTo(java.util.stream.IntStream.rangeClosed(1, 2 * ROUNDS).boxed().toList());
	}

	@Test
	void twoSimultaneousPlansLeaveExactlyOneInTwentyRounds() throws Exception {
		Kit kit = kit();
		for (int round = 0; round < ROUNDS; round++) {
			String budget = approvedBudgetOf(kit);
			List<Callable<Integer>> calls = new ArrayList<>();
			for (int count : new int[] { 2, 3 }) {
				calls.add(() -> mvc.perform(json(post("/api/v1/budgets/" + budget + "/installments"),
						"{\"mode\":\"AUTO\",\"count\":%d,\"firstDueDate\":\"2027-03-01\"}".formatted(count)).with(as(kit.clinic(), Role.DENTIST_ADMIN)))
						.andReturn().getResponse().getStatus());
			}

			assertThat(race(calls)).as("rodada %d", round).containsExactlyInAnyOrder(201, 409);
			assertThat(DatabaseContainer.admin().queryForObject("SELECT count(*) FROM core.installments WHERE budget_id = ?::uuid AND status <> 'CANCELED'", Long.class, budget))
					.as("rodada %d", round).isIn(2L, 3L);
			assertThat(DatabaseContainer.admin().queryForObject("SELECT sum(amount) FROM core.installments WHERE budget_id = ?::uuid", java.math.BigDecimal.class, budget))
					.as("rodada %d: Σ parcelas = total", round).isEqualByComparingTo("800.00");
		}
	}

	@Test
	void anItemEditRacingTheSendNeverLeavesAMixedState() throws Exception {
		for (int round = 0; round < ROUNDS; round++) {
			Kit kit = kit(); // clínica e usuário novos: o limite de 6 envios por minuto é por usuário
			JsonNode draft = createBudget(kit, null, item(kit.restoration(), 1, null));
			String id = draft.get("id").asString();
			long version = draft.get("version").asLong();
			String totalBefore = draft.get("totalAmount").asString();
			List<Callable<Integer>> calls = List.of(
					() -> mvc.perform(json(put("/api/v1/budgets/" + id), "{\"version\":%d,\"items\":[%s,%s]}".formatted(version,
							item(kit.restoration(), 1, null), item(kit.root(), 1, null))).with(as(kit.clinic(), Role.DENTIST_ADMIN))).andReturn().getResponse().getStatus(),
					() -> mvc.perform(json(post("/api/v1/budgets/" + id + "/send"), "{\"version\":%d}".formatted(version)).with(as(kit.clinic(), Role.DENTIST_ADMIN)))
							.andReturn().getResponse().getStatus());

			List<Integer> statuses = race(calls);

			assertThat(statuses).as("rodada %d: exatamente um vence", round).containsExactlyInAnyOrder(200, 409);
			JsonNode after = getBudget(kit.clinic(), Role.DENTIST_ADMIN, id);
			long documents = DatabaseContainer.admin().queryForObject("SELECT count(*) FROM core.budget_documents WHERE budget_id = ?::uuid", Long.class, id);
			if (statuses.get(1) == 200) { // o envio venceu: orçamento enviado com os itens de antes e um PDF
				assertThat(after.get("status").asString()).as("rodada %d", round).isEqualTo("SENT");
				assertThat(after.get("items")).as("rodada %d: itens do envio", round).hasSize(1);
				assertThat(after.get("totalAmount").asString()).isEqualTo(totalBefore);
				assertThat(documents).isEqualTo(1);
			}
			else { // a edição venceu: continua rascunho, com os dois itens, sem PDF
				assertThat(after.get("status").asString()).as("rodada %d", round).isEqualTo("DRAFT");
				assertThat(after.get("items")).hasSize(2);
				assertThat(documents).isZero();
			}
		}
	}
}
