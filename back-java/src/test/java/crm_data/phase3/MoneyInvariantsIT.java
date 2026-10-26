package crm_data.phase3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

import crm_data.shared.security.Role;
import crm_data.support.DatabaseContainer;
import tools.jackson.databind.JsonNode;

/**
 * Propriedade das invariantes do dinheiro em 200 sequências aleatórias (semente fixa, impressa no início): criar,
 * editar, aprovar, planejar (AUTO com e sem entrada, MANUAL), pagar parcial e total, estornar e refazer, com operações
 * inválidas misturadas. Depois de cada sequência, consultas diretas ao banco conferem:
 * <ol>
 * <li>(a) o total do orçamento é Σ das linhas menos o desconto, e cada linha é preço × quantidade menos o desconto;</li>
 * <li>(b) a soma das parcelas ativas é igual ao total;</li>
 * <li>(c) o líquido pago nunca passa do valor da parcela nem fica negativo;</li>
 * <li>(d) a parcela é {@code PAID} exatamente quando o líquido alcança o valor ({@code CANCELED} só com líquido zero).</li>
 * </ol>
 * Uma operação inválida deve responder 4xx e <strong>não alterar</strong> nada (contagens, status e líquido).
 */
class MoneyInvariantsIT extends Phase3ItSupport {

	private static final long SEED = 20261026L;
	private static final int SEQUENCES = 200;
	private static final int SEQUENCES_PER_CLINIC = 25;

	private final Random random = new Random(SEED);

	private static String money(long cents) {
		return "%d.%02d".formatted(cents / 100, cents % 100);
	}

	private int call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
		return mvc.perform(request).andReturn().getResponse().getStatus();
	}

	private JsonNode okJson(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, int expected) throws Exception {
		var response = mvc.perform(request).andReturn().getResponse();
		assertThat(response.getStatus()).as("seed %d: %s", SEED, request.toString()).isEqualTo(expected);
		return mapper.readTree(response.getContentAsString());
	}

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder as(Kit kit, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder r) {
		return r.with(as(kit.clinic(), Role.DENTIST_ADMIN));
	}

	/** Estado observável que uma operação inválida não pode mexer. */
	private String snapshot(String budgetId) {
		var admin = DatabaseContainer.admin();
		return admin.queryForObject("""
				SELECT b.status || '|' || b.version || '|' || b.total_amount || '|' ||
				  (SELECT count(*) FROM core.installments i WHERE i.budget_id = b.id) || '|' ||
				  (SELECT coalesce(string_agg(i.status || ':' || i.version, ',' ORDER BY i.number, i.id), '') FROM core.installments i WHERE i.budget_id = b.id) || '|' ||
				  (SELECT count(*) FROM core.payments p JOIN core.installments i ON i.id = p.installment_id WHERE i.budget_id = b.id)
				FROM core.budgets b WHERE b.id = ?::uuid""", String.class, budgetId);
	}

	private void assertInvalid(Kit kit, String budgetId, String label, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
		String before = snapshot(budgetId);
		int status = call(as(kit, request));
		assertThat(status).as("seed %d: %s deve ser 4xx", SEED, label).isBetween(400, 499);
		assertThat(snapshot(budgetId)).as("seed %d: %s não pode alterar nada", SEED, label).isEqualTo(before);
	}

	// ---- geração ----

	private String createBudgetRandomly(Kit kit) throws Exception {
		int itemCount = 1 + random.nextInt(3);
		List<String> items = new ArrayList<>();
		long subtotal = 0;
		for (int i = 0; i < itemCount; i++) {
			boolean root = random.nextBoolean();
			int quantity = 1 + random.nextInt(5);
			long line = (root ? 90000L : 25000L) * quantity;
			long discount = random.nextInt(3) == 0 ? random.nextInt((int) (line / 2)) : 0;
			items.add(item(root ? kit.root() : kit.restoration(), quantity, discount == 0 ? null : money(discount)));
			subtotal += line - discount;
		}
		long budgetDiscount = random.nextInt(3) == 0 ? random.nextInt((int) (subtotal / 2)) : 0;
		return okJson(as(kit, post("/api/v1/budgets").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
				.content(budgetBody(kit.patient(), budgetDiscount == 0 ? null : money(budgetDiscount), items.toArray(String[]::new)))), 201).get("id").asString();
	}

	private String autoPlan(long total) {
		int count = 1 + random.nextInt(12);
		String down = "";
		if (count >= 2 && random.nextBoolean() && total > 200) {
			down = ",\"downPayment\":\"" + money(1 + random.nextInt((int) Math.min(total - 1, 1_000_000))) + "\"";
		}
		return "{\"mode\":\"AUTO\",\"count\":%d,\"firstDueDate\":\"2027-0%d-%02d\"%s}".formatted(count, 1 + random.nextInt(9), 1 + random.nextInt(28), down);
	}

	private String manualPlan(long total) {
		int parts = 1 + random.nextInt(6);
		java.util.TreeSet<Long> cuts = new java.util.TreeSet<>();
		while (cuts.size() < parts - 1 && total > parts) {
			cuts.add(1 + (long) random.nextInt((int) Math.min(total - 1, Integer.MAX_VALUE)));
		}
		List<String> items = new ArrayList<>();
		long previous = 0;
		for (long cut : cuts) {
			items.add("{\"amount\":\"%s\",\"dueDate\":\"2027-%02d-%02d\"}".formatted(money(cut - previous), 1 + random.nextInt(12), 1 + random.nextInt(28)));
			previous = cut;
		}
		items.add("{\"amount\":\"%s\",\"dueDate\":\"2027-%02d-%02d\"}".formatted(money(total - previous), 1 + random.nextInt(12), 1 + random.nextInt(28)));
		return "{\"mode\":\"MANUAL\",\"installments\":[" + String.join(",", items) + "]}";
	}

	private long totalCents(String budgetId) {
		return DatabaseContainer.admin().queryForObject("SELECT (total_amount * 100)::bigint FROM core.budgets WHERE id = ?::uuid", Long.class, budgetId);
	}

	private List<Map<String, Object>> installments(String budgetId) {
		return DatabaseContainer.admin().queryForList("""
				SELECT i.id::text AS id, i.status, (i.amount * 100)::bigint AS amount,
				       coalesce((SELECT sum(CASE WHEN p.kind = 'PAYMENT' THEN p.amount ELSE -p.amount END) * 100 FROM core.payments p WHERE p.installment_id = i.id), 0)::bigint AS net
				FROM core.installments i WHERE i.budget_id = ?::uuid ORDER BY i.number, i.id""", budgetId);
	}

	// ---- a propriedade ----

	@Test
	void theMoneyInvariantsHoldInTwoHundredRandomSequences() throws Exception {
		System.out.println("MoneyInvariantsIT seed=" + SEED + " sequences=" + SEQUENCES);
		Kit kit = null;
		int withPlan = 0;
		int withPayments = 0;
		int redone = 0;
		for (int sequence = 0; sequence < SEQUENCES; sequence++) {
			if (sequence % SEQUENCES_PER_CLINIC == 0) {
				kit = kit();
			}
			String budget = createBudgetRandomly(kit);
			long total = totalCents(budget);

			// operações inválidas em rascunho: nada muda
			assertInvalid(kit, budget, "aprovar rascunho", post("/api/v1/budgets/" + budget + "/approve").contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"version\":0}"));
			assertInvalid(kit, budget, "plano em rascunho", post("/api/v1/budgets/" + budget + "/installments").contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(autoPlan(total)));
			assertInvalid(kit, budget, "item com quantidade zero", put("/api/v1/budgets/" + budget).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
					.content("{\"version\":0,\"items\":[{\"procedureTypeId\":\"%s\",\"quantity\":0}]}".formatted(kit.restoration())));

			forceStatus(kit.clinic(), budget, "SENT");
			long version = getBudget(kit.clinic(), Role.DENTIST_ADMIN, budget).get("version").asLong();
			okJson(as(kit, post("/api/v1/budgets/" + budget + "/approve").contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"version\":%d}".formatted(version))), 200);

			if (random.nextInt(10) == 0) {
				// só aprovado, sem plano: as invariantes valem sem parcelas
				checkInvariants(budget, sequence);
				continue;
			}
			String plan = random.nextBoolean() ? autoPlan(total) : manualPlan(total);
			okJson(as(kit, post("/api/v1/budgets/" + budget + "/installments").contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(plan)), 201);
			withPlan++;
			assertInvalid(kit, budget, "segundo plano", post("/api/v1/budgets/" + budget + "/installments").contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(autoPlan(total)));
			assertInvalid(kit, budget, "plano manual com soma errada", put("/api/v1/budgets/" + budget + "/installments").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
					.content("{\"mode\":\"MANUAL\",\"installments\":[{\"amount\":\"%s\",\"dueDate\":\"2027-05-01\"}]}".formatted(money(total + 1))));

			int operations = random.nextInt(7);
			for (int op = 0; op < operations; op++) {
				List<Map<String, Object>> rows = installments(budget);
				List<Map<String, Object>> active = rows.stream().filter(r -> !"CANCELED".equals(r.get("status"))).toList();
				Map<String, Object> target = active.get(random.nextInt(active.size()));
				String id = (String) target.get("id");
				long amount = (Long) target.get("amount");
				long net = (Long) target.get("net");
				switch (random.nextInt(5)) {
					case 0 -> { // pagamento parcial válido
						if (net < amount) {
							long part = 1 + random.nextInt((int) Math.min(amount - net, Integer.MAX_VALUE));
							okJson(as(kit, post("/api/v1/installments/" + id + "/payments").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
									.content("{\"amount\":\"%s\",\"method\":\"PIX\"}".formatted(money(part)))), 201);
							withPayments++;
						}
					}
					case 1 -> { // quita o saldo
						if (net < amount) {
							okJson(as(kit, post("/api/v1/installments/" + id + "/payments").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
									.content("{\"amount\":\"%s\",\"method\":\"CASH\"}".formatted(money(amount - net)))), 201);
							withPayments++;
						}
					}
					case 2 -> // pagamento acima do saldo: 409, nada muda
						assertInvalid(kit, budget, "pagamento acima do saldo", post("/api/v1/installments/" + id + "/payments").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
								.content("{\"amount\":\"%s\",\"method\":\"PIX\"}".formatted(money(amount - net + 1))));
					case 3 -> { // estorna um pagamento não estornado; o segundo estorno do mesmo é inválido
						List<String> reversible = DatabaseContainer.admin().queryForList("""
								SELECT p.id::text FROM core.payments p WHERE p.installment_id = ?::uuid AND p.kind = 'PAYMENT'
								  AND NOT EXISTS (SELECT 1 FROM core.payments r WHERE r.reverses_payment_id = p.id)""", String.class, id);
						if (!reversible.isEmpty()) {
							String payment = reversible.get(random.nextInt(reversible.size()));
							okJson(as(kit, post("/api/v1/payments/" + payment + "/reversal")), 201);
							assertInvalid(kit, budget, "estorno repetido", post("/api/v1/payments/" + payment + "/reversal"));
						}
					}
					default -> { // refazer o plano: só passa sem líquido pago
						boolean anyNet = active.stream().anyMatch(r -> (Long) r.get("net") > 0);
						String again = random.nextBoolean() ? autoPlan(total) : manualPlan(total);
						if (anyNet) {
							assertInvalid(kit, budget, "refazer com líquido pago", put("/api/v1/budgets/" + budget + "/installments").contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(again));
						}
						else {
							okJson(as(kit, put("/api/v1/budgets/" + budget + "/installments").contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(again)), 200);
							redone++;
						}
					}
				}
				checkInvariants(budget, sequence);
			}
			checkInvariants(budget, sequence);
		}
		System.out.println("MoneyInvariantsIT: sequences=%d withPlan=%d payments=%d redone=%d".formatted(SEQUENCES, withPlan, withPayments, redone));
		assertThat(withPlan).as("a propriedade exercitou planos").isGreaterThan(150);
		assertThat(withPayments).as("a propriedade exercitou pagamentos").isGreaterThan(100);
		assertThat(redone).as("a propriedade exercitou o refazer").isGreaterThan(5);
	}

	private void checkInvariants(String budgetId, int sequence) {
		var admin = DatabaseContainer.admin();
		String where = "seed %d, sequência %d, orçamento %s".formatted(SEED, sequence, budgetId);

		// (a) total = Σ linhas − desconto; linha = preço × quantidade − desconto do item
		Map<String, Object> totals = admin.queryForMap("""
				SELECT (b.total_amount * 100)::bigint AS total, (b.discount_amount * 100)::bigint AS discount,
				       coalesce((SELECT sum(i.line_total) * 100 FROM core.budget_items i WHERE i.budget_id = b.id), 0)::bigint AS lines,
				       (SELECT count(*) FROM core.budget_items i WHERE i.budget_id = b.id AND i.line_total <> i.unit_price * i.quantity - i.discount_amount) AS badLines
				FROM core.budgets b WHERE b.id = ?::uuid""", budgetId);
		assertThat((Long) totals.get("total")).as("(a) total = Σ linhas − desconto — " + where).isEqualTo((Long) totals.get("lines") - (Long) totals.get("discount"));
		assertThat((Long) totals.get("badLines")).as("(a) linhas coerentes — " + where).isZero();

		List<Map<String, Object>> rows = installments(budgetId);
		long activeSum = 0;
		for (Map<String, Object> row : rows) {
			long amount = (Long) row.get("amount");
			long net = (Long) row.get("net");
			String status = (String) row.get("status");
			if (!"CANCELED".equals(status)) {
				activeSum += amount;
			}
			// (c) 0 ≤ líquido ≤ valor
			assertThat(net).as("(c) líquido não negativo — " + where).isGreaterThanOrEqualTo(0);
			assertThat(net).as("(c) líquido ≤ valor da parcela — " + where).isLessThanOrEqualTo(amount);
			// (d) PAID ⇔ líquido ≥ valor; CANCELED só com líquido zero
			switch (status) {
				case "PAID" -> assertThat(net).as("(d) PAID exige líquido = valor — " + where).isEqualTo(amount);
				case "OPEN" -> assertThat(net).as("(d) OPEN exige líquido < valor — " + where).isLessThan(amount);
				case "CANCELED" -> assertThat(net).as("(d) CANCELED exige líquido zero — " + where).isZero();
				default -> throw new AssertionError("status inesperado " + status);
			}
		}
		// (b) Σ parcelas ativas = total (quando existe plano)
		if (rows.stream().anyMatch(r -> !"CANCELED".equals(r.get("status")))) {
			assertThat(activeSum).as("(b) Σ das parcelas ativas = total — " + where).isEqualTo((Long) totals.get("total"));
		}
	}
}
