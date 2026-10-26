package crm_data.phase3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import crm_data.shared.security.Role;
import crm_data.support.PerfLimits;
import tools.jackson.databind.JsonNode;

/**
 * Metas de latência da Fase 3 que as features não mediam: pagamento p95 ≤ 100 ms, orçamento de 20 itens p95 ≤ 250 ms
 * (criar e editar) e envio com PDF p95 ≤ 1,5 s. (Relatórios, ficha, plano de 60 parcelas e listagem do plano, com 5 mil
 * parcelas e 100 mil linhas no banco, e os planos sem {@code Seq Scan}, são medidos no {@code PaymentsIT} e no
 * {@code InstallmentsIT}.) Os limites escalam com {@link PerfLimits} (dobro com {@code CI}, ou {@code -Dperf.limit-ms}).
 */
class Phase3PerformanceIT extends Phase3ItSupport {

	private static final int WARM_UP = 3;
	private static final int SAMPLES = 20;

	/** Limite proporcional ao padrão de 100 ms de {@link PerfLimits} (250 ms → 250 × p95Ms / 100). */
	private static long scaled(long baseMs) {
		return baseMs * PerfLimits.p95Ms() / 100;
	}

	private static long p95(List<Long> samples) {
		List<Long> sorted = samples.stream().sorted().toList();
		return sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1);
	}

	@Test
	void aPaymentRegistersWithinTheLimitAtP95() throws Exception {
		Kit kit = kit();
		String budget = approvedBudgetOf(kit);
		JsonNode plan = read(mvc.perform(json(post("/api/v1/budgets/" + budget + "/installments"),
				"{\"mode\":\"MANUAL\",\"installments\":[{\"amount\":\"800.00\",\"dueDate\":\"2027-03-01\"}]}").with(as(kit.clinic(), Role.DENTIST_ADMIN)))
				.andExpect(status().isCreated()).andReturn());
		String installment = plan.get("installments").get(0).get("id").asString();
		List<Long> samples = new ArrayList<>();
		for (int i = 0; i < WARM_UP + SAMPLES; i++) { // 23 pagamentos de R$ 0,50 + estorno não cabem: 23 × 0,50 < 800
			long start = System.nanoTime();
			mvc.perform(json(post("/api/v1/installments/" + installment + "/payments"), "{\"amount\":\"0.50\",\"method\":\"PIX\"}")
					.with(as(kit.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isCreated());
			if (i >= WARM_UP) {
				samples.add((System.nanoTime() - start) / 1_000_000);
			}
		}

		System.out.println("P95 pagamento=" + p95(samples) + " ms");
		assertThat(p95(samples)).as("p95 do pagamento (ms)").isLessThanOrEqualTo(PerfLimits.p95Ms());
	}

	@Test
	void aTwentyItemBudgetIsCreatedAndEditedWithinTheLimitAtP95() throws Exception {
		Kit kit = kit();
		List<String> items = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			items.add(item(i % 2 == 0 ? kit.restoration() : kit.root(), 1 + i % 3, i % 4 == 0 ? "5.00" : null));
		}
		List<Long> create = new ArrayList<>();
		List<Long> edit = new ArrayList<>();
		for (int i = 0; i < WARM_UP + SAMPLES; i++) {
			long start = System.nanoTime();
			JsonNode created = read(mvc.perform(json(post("/api/v1/budgets"), budgetBody(kit.patient(), "10.00", items.toArray(String[]::new)))
					.with(as(kit.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isCreated()).andReturn());
			long afterCreate = System.nanoTime();
			mvc.perform(json(put("/api/v1/budgets/" + created.get("id").asString()),
					"{\"version\":%d,\"discountAmount\":\"20.00\",\"items\":[%s]}".formatted(created.get("version").asLong(), String.join(",", items)))
					.with(as(kit.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isOk());
			long end = System.nanoTime();
			if (i >= WARM_UP) {
				create.add((afterCreate - start) / 1_000_000);
				edit.add((end - afterCreate) / 1_000_000);
			}
		}

		System.out.println("P95 orcamento-20-itens criar=" + p95(create) + " ms editar=" + p95(edit) + " ms");
		assertThat(p95(create)).as("p95 da criação de orçamento de 20 itens (ms)").isLessThanOrEqualTo(scaled(250));
		assertThat(p95(edit)).as("p95 da edição de orçamento de 20 itens (ms)").isLessThanOrEqualTo(scaled(250));
	}

	@Test
	void sendingWithAPdfStaysUnderOneAndAHalfSecondsAtP95() throws Exception {
		List<Long> samples = new ArrayList<>();
		for (int i = 0; i < WARM_UP + 10; i++) {
			Kit kit = kit(); // usuário novo a cada envio: o limite de 6 por minuto é por usuário
			JsonNode draft = createBudget(kit, null, item(kit.restoration(), 2, null), item(kit.root(), 1, null));
			long start = System.nanoTime();
			mvc.perform(json(post("/api/v1/budgets/" + draft.get("id").asString() + "/send"), "{\"version\":%d}".formatted(draft.get("version").asLong()))
					.with(as(kit.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isOk());
			if (i >= WARM_UP) {
				samples.add((System.nanoTime() - start) / 1_000_000);
			}
		}

		System.out.println("P95 envio-com-pdf=" + p95(samples) + " ms");
		assertThat(p95(samples)).as("p95 do envio com PDF (ms)").isLessThanOrEqualTo(scaled(1500));
	}

	@Test
	void readingABudgetStaysFastAtP95() throws Exception {
		Kit kit = kit();
		String id = approvedBudgetOf(kit);
		List<Long> samples = new ArrayList<>();
		for (int i = 0; i < WARM_UP + SAMPLES; i++) {
			long start = System.nanoTime();
			mvc.perform(get("/api/v1/budgets/" + id).with(as(kit.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isOk());
			if (i >= WARM_UP) {
				samples.add((System.nanoTime() - start) / 1_000_000);
			}
		}

		System.out.println("P95 leitura-orcamento=" + p95(samples) + " ms");
		assertThat(p95(samples)).as("p95 da leitura de orçamento (ms)").isLessThanOrEqualTo(PerfLimits.p95Ms());
	}
}
