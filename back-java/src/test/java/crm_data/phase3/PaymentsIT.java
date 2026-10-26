package crm_data.phase3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import crm_data.shared.security.Role;
import crm_data.support.DatabaseContainer;
import crm_data.support.tenant.TenantFixtures.Clinic;
import tools.jackson.databind.JsonNode;

/**
 * F06 contra o PostgreSQL real (V001–V029): pagamento parcial e quitação, estorno (e seus 409), saldo decidido pelo banco
 * sob trava (concorrência), data do recebimento no fuso da clínica, paciente inativo/anonimizado, caixa líquido de
 * estornos, faturamento na virada do mês no fuso da clínica, atraso derivado, ficha do paciente, papéis e carga de 5 mil
 * parcelas e 5 mil pagamentos (p95 e planos sem {@code Seq Scan}).
 */
class PaymentsIT extends Phase3ItSupport {

	// ---- auxiliares ----

	private ZoneId zoneOf(Clinic clinic) {
		return ZoneId.of(DatabaseContainer.admin().queryForObject("SELECT timezone FROM core.clinics WHERE id = ?::uuid", String.class,
				clinic.id().toString()));
	}

	private LocalDate todayIn(Clinic clinic) {
		return LocalDate.now(zoneOf(clinic));
	}

	private MockHttpServletRequestBuilder pay(Kit kit, Role role, String installmentId, String body) {
		return json(post("/api/v1/installments/" + installmentId + "/payments"), body).with(as(kit.clinic(), role));
	}

	private static String payment(String amount, String method, String paidOn) {
		return "{\"amount\":\"%s\",\"method\":\"%s\"%s}".formatted(amount, method, paidOn == null ? "" : ",\"paidOn\":\"" + paidOn + "\"");
	}

	private MockHttpServletRequestBuilder reverse(Kit kit, Role role, String paymentId) {
		return post("/api/v1/payments/" + paymentId + "/reversal").with(as(kit.clinic(), role));
	}

	private JsonNode paid(Kit kit, String installmentId, String amount, String method) throws Exception {
		return read(mvc.perform(pay(kit, Role.DENTIST_ADMIN, installmentId, payment(amount, method, null))).andExpect(status().isCreated()).andReturn());
	}

	/** Orçamento aprovado de R$ 800,00 com 2 parcelas de R$ 400,00; devolve os ids das parcelas. */
	private List<String> twoInstallments(Kit kit) throws Exception {
		String budget = approvedBudgetOf(kit);
		JsonNode plan = planOf(kit, budget, 2);
		return List.of(plan.get("installments").get(0).get("id").asString(), plan.get("installments").get(1).get("id").asString());
	}

	private JsonNode getJson(Kit kit, String path) throws Exception {
		return read(mvc.perform(get(path).with(as(kit.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store")).andReturn());
	}

	private void sql(Clinic clinic, String statement) {
		adminSql(clinic, "BEGIN; SET LOCAL session_replication_role = replica; " + statement + "; COMMIT");
	}

	private String statusOf(String installmentId) {
		return DatabaseContainer.admin().queryForObject("SELECT status FROM core.installments WHERE id = ?::uuid", String.class, installmentId);
	}

	// ---- registrar ----

	@Test
	void aPartialPaymentThenTheRestSettlesTheInstallmentByTrigger() throws Exception {
		Kit kit = kit();
		String first = twoInstallments(kit).get(0);

		JsonNode partial = paid(kit, first, "150.00", "PIX");
		assertThat(partial.get("payment").get("kind").asString()).isEqualTo("PAYMENT");
		assertThat(partial.get("payment").get("amount").asString()).isEqualTo("150.00");
		assertThat(partial.get("payment").get("paidOn").asString()).isEqualTo(todayIn(kit.clinic()).toString());
		assertThat(partial.get("payment").get("createdAt").asString()).isNotBlank();
		assertThat(partial.get("installment").get("status").asString()).isEqualTo("OPEN");
		assertThat(partial.get("installment").get("netPaid").asString()).isEqualTo("150.00");
		assertThat(partial.get("installment").get("balance").asString()).isEqualTo("250.00");

		JsonNode rest = paid(kit, first, "250.00", "CASH");
		assertThat(rest.get("installment").get("status").asString()).isEqualTo("PAID");
		assertThat(rest.get("installment").get("balance").asString()).isEqualTo("0.00");
		assertThat(rest.get("installment").get("version").asLong()).isGreaterThan(partial.get("installment").get("version").asLong());
		assertThat(statusOf(first)).isEqualTo("PAID");
	}

	@Test
	void aPaymentAboveTheBalanceOrOnACanceledInstallmentIs409() throws Exception {
		Kit kit = kit();
		String budget = approvedBudgetOf(kit);
		JsonNode plan = planOf(kit, budget, 2);
		String first = plan.get("installments").get(0).get("id").asString();

		mvc.perform(pay(kit, Role.DENTIST_ADMIN, first, payment("400.01", "PIX", null))).andExpect(status().isConflict());
		paid(kit, first, "300.00", "PIX");
		mvc.perform(pay(kit, Role.DENTIST_ADMIN, first, payment("100.01", "PIX", null))).andExpect(status().isConflict());

		// parcela cancelada: refazer um plano sem pagamento cancela as antigas
		mvc.perform(json(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/budgets/" + budget + "/installments"),
				"{\"mode\":\"AUTO\",\"count\":1,\"firstDueDate\":\"2027-05-01\"}").with(as(kit.clinic(), Role.DENTIST_ADMIN)))
				.andExpect(status().isConflict()); // há pagamento líquido na primeira: não refaz
		String otherBudget = approvedBudgetOf(kit);
		JsonNode otherPlan = planOf(kit, otherBudget, 2);
		String doomed = otherPlan.get("installments").get(1).get("id").asString();
		mvc.perform(json(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/budgets/" + otherBudget + "/installments"),
				"{\"mode\":\"AUTO\",\"count\":1,\"firstDueDate\":\"2027-05-01\"}").with(as(kit.clinic(), Role.DENTIST_ADMIN)))
				.andExpect(status().isOk());
		assertThat(statusOf(doomed)).isEqualTo("CANCELED");
		mvc.perform(pay(kit, Role.DENTIST_ADMIN, doomed, payment("10.00", "PIX", null))).andExpect(status().isConflict());
	}

	@Test
	void invalidBodiesAre400AndAFutureDateIs422() throws Exception {
		Kit kit = kit();
		String first = twoInstallments(kit).get(0);

		for (String body : List.of("{}", payment("0.00", "PIX", null), payment("-5.00", "PIX", null), payment("10.001", "PIX", null),
				payment("1e3", "PIX", null), payment("10.00", "BITCOIN", null), "{\"amount\":10,\"method\":\"PIX\"}",
				payment("10.00", "PIX", "1999-12-31"), payment("1000000.00", "PIX", null), "{\"method\":\"PIX\"}", "{\"amount\":\"10.00\"}")) {
			mvc.perform(pay(kit, Role.DENTIST_ADMIN, first, body)).andExpect(status().isBadRequest());
		}
		mvc.perform(pay(kit, Role.DENTIST_ADMIN, first, payment("10.00", "PIX", todayIn(kit.clinic()).plusDays(1).toString())))
				.andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.reason").value("FUTURE_DATE"));

		assertThat(DatabaseContainer.admin().queryForObject("SELECT count(*) FROM core.payments WHERE installment_id = ?::uuid", Long.class, first)).isZero();
	}

	@Test
	void thePaymentDateDefaultsToTodayInTheClinicZoneAndAPastDateIsAccepted() throws Exception {
		Kit kit = kit();
		String first = twoInstallments(kit).get(0);
		String past = todayIn(kit.clinic()).minusDays(30).toString();

		JsonNode today = paid(kit, first, "10.00", "DEBIT_CARD");
		JsonNode old = read(mvc.perform(pay(kit, Role.DENTIST_ADMIN, first, payment("20.00", "BANK_TRANSFER", past))).andExpect(status().isCreated()).andReturn());

		assertThat(today.get("payment").get("paidOn").asString()).isEqualTo(todayIn(kit.clinic()).toString());
		assertThat(old.get("payment").get("paidOn").asString()).isEqualTo(past);
	}

	@Test
	void serverOwnedFieldsInTheBodyAreIgnored() throws Exception {
		Kit kit = kit();
		String first = twoInstallments(kit).get(0);

		JsonNode created = read(mvc.perform(pay(kit, Role.DENTIST_ADMIN, first,
				"{\"amount\":\"10.00\",\"method\":\"PIX\",\"kind\":\"REVERSAL\",\"clinicId\":\"%s\",\"installmentId\":\"%s\",\"reversesPaymentId\":\"%s\",\"createdBy\":\"%s\",\"status\":\"PAID\"}"
						.formatted(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), java.util.UUID.randomUUID())))
				.andExpect(status().isCreated()).andReturn());

		assertThat(created.get("payment").get("kind").asString()).isEqualTo("PAYMENT");
		assertThat(created.get("payment").get("reversesPaymentId").isNull()).isTrue();
		assertThat(created.get("payment").get("createdBy").asString()).isEqualTo(userOf(kit.clinic(), Role.DENTIST_ADMIN).toString());
		assertThat(created.get("installment").get("id").asString()).isEqualTo(first);
	}

	// ---- estornar ----

	@Test
	void reversingASettledPaymentReopensTheInstallmentAndKeepsTheHistory() throws Exception {
		Kit kit = kit();
		String first = twoInstallments(kit).get(0);
		JsonNode settled = paid(kit, first, "400.00", "PIX");
		String paymentId = settled.get("payment").get("id").asString();
		assertThat(settled.get("installment").get("status").asString()).isEqualTo("PAID");

		JsonNode reversed = read(mvc.perform(reverse(kit, Role.DENTIST_ADMIN, paymentId)).andExpect(status().isCreated())
				.andExpect(header().exists("Location")).andReturn());

		assertThat(reversed.get("payment").get("kind").asString()).isEqualTo("REVERSAL");
		assertThat(reversed.get("payment").get("reversesPaymentId").asString()).isEqualTo(paymentId);
		assertThat(reversed.get("payment").get("amount").asString()).isEqualTo("400.00");
		assertThat(reversed.get("payment").get("method").asString()).isEqualTo("PIX");
		assertThat(reversed.get("installment").get("status").asString()).isEqualTo("OPEN");
		assertThat(reversed.get("installment").get("netPaid").asString()).isEqualTo("0.00");
		JsonNode history = getJson(kit, "/api/v1/installments/" + first + "/payments");
		assertThat(history).hasSize(2);
		assertThat(history.get(0).get("kind").asString()).isEqualTo("PAYMENT");
		assertThat(history.get(1).get("kind").asString()).isEqualTo("REVERSAL");
		paid(kit, first, "400.00", "CASH"); // depois do estorno o saldo voltou
	}

	@Test
	void reversingAReversalOrTheSamePaymentTwiceIs409AndAnotherClinicsPaymentIs404() throws Exception {
		Kit kit = kit();
		String first = twoInstallments(kit).get(0);
		String paymentId = paid(kit, first, "100.00", "PIX").get("payment").get("id").asString();
		String reversalId = read(mvc.perform(reverse(kit, Role.DENTIST_ADMIN, paymentId)).andExpect(status().isCreated()).andReturn())
				.get("payment").get("id").asString();
		Kit other = kit();

		mvc.perform(reverse(kit, Role.DENTIST_ADMIN, paymentId)).andExpect(status().isConflict())
				.andExpect(jsonPath("$.detail").value("Este pagamento já foi estornado."));
		mvc.perform(reverse(kit, Role.DENTIST_ADMIN, reversalId)).andExpect(status().isConflict());
		mvc.perform(reverse(other, Role.DENTIST_ADMIN, paymentId)).andExpect(status().isNotFound());
		mvc.perform(reverse(kit, Role.DENTIST_ADMIN, java.util.UUID.randomUUID().toString())).andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/installments/" + first + "/payments").with(as(other.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isNotFound());

		assertThat(DatabaseContainer.admin().queryForObject("SELECT count(*) FROM core.payments WHERE installment_id = ?::uuid", Long.class, first)).isEqualTo(2);
	}

	@Test
	void anInactivePatientStillPaysButAnAnonymizedOneIs409ForPaymentAndReversal() throws Exception {
		Kit kit = kit();
		String first = twoInstallments(kit).get(0);
		String paymentId = paid(kit, first, "100.00", "PIX").get("payment").get("id").asString();
		adminSql(kit.clinic(), "UPDATE core.patients SET active = false, deactivated_at = now() WHERE id = '%s'".formatted(kit.patient()));

		paid(kit, first, "50.00", "PIX");
		sql(kit.clinic(), "UPDATE core.patients SET anonymized_at = now() WHERE id = '%s'".formatted(kit.patient()));

		mvc.perform(pay(kit, Role.DENTIST_ADMIN, first, payment("10.00", "PIX", null))).andExpect(status().isConflict());
		mvc.perform(reverse(kit, Role.DENTIST_ADMIN, paymentId)).andExpect(status().isConflict());
	}

	// ---- concorrência ----

	@Test
	void twoSimultaneousPaymentsOnTheLastBalanceLeaveExactlyOneWinnerInTwentyRounds() throws Exception {
		Kit kit = kit();
		String budget = approvedBudgetOf(kit);
		JsonNode plan = planOf(kit, budget, 1); // 1 parcela de R$ 800,00 por orçamento
		String installment = plan.get("installments").get(0).get("id").asString();
		for (int round = 0; round < 20; round++) {
			String target = round == 0 ? installment : planOf(kit, approvedBudgetOf(kit), 1).get("installments").get(0).get("id").asString();
			CountDownLatch go = new CountDownLatch(1);
			ExecutorService pool = Executors.newFixedThreadPool(2);
			List<Future<Integer>> results = new ArrayList<>();
			for (int i = 0; i < 2; i++) {
				results.add(pool.submit(() -> {
					go.await();
					return mvc.perform(pay(kit, Role.DENTIST_ADMIN, target, payment("800.00", "PIX", null))).andReturn().getResponse().getStatus();
				}));
			}
			go.countDown();
			List<Integer> statuses = List.of(results.get(0).get(), results.get(1).get());
			pool.shutdown();

			assertThat(statuses).as("rodada %d", round).containsExactlyInAnyOrder(201, 409);
			assertThat(statusOf(target)).isEqualTo("PAID");
		}
	}

	@Test
	void simultaneousPaymentsOnDifferentInstallmentsBothSucceed() throws Exception {
		Kit kit = kit();
		List<String> installments = twoInstallments(kit);
		CountDownLatch go = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		List<Future<Integer>> results = new ArrayList<>();
		for (String id : installments) {
			results.add(pool.submit(() -> {
				go.await();
				return mvc.perform(pay(kit, Role.DENTIST_ADMIN, id, payment("400.00", "PIX", null))).andReturn().getResponse().getStatus();
			}));
		}
		go.countDown();
		List<Integer> statuses = List.of(results.get(0).get(), results.get(1).get());
		pool.shutdown();

		assertThat(statuses).containsExactly(201, 201);
	}

	@Test
	void twoSimultaneousReversalsOfTheSamePaymentLeaveOne() throws Exception {
		Kit kit = kit();
		String first = twoInstallments(kit).get(0);
		String paymentId = paid(kit, first, "100.00", "PIX").get("payment").get("id").asString();
		CountDownLatch go = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		List<Future<Integer>> results = new ArrayList<>();
		for (int i = 0; i < 2; i++) {
			results.add(pool.submit(() -> {
				go.await();
				return mvc.perform(reverse(kit, Role.DENTIST_ADMIN, paymentId)).andReturn().getResponse().getStatus();
			}));
		}
		go.countDown();
		List<Integer> statuses = List.of(results.get(0).get(), results.get(1).get());
		pool.shutdown();

		assertThat(statuses).containsExactlyInAnyOrder(201, 409);
	}

	// ---- papéis ----

	@Test
	void theReceptionistIs403OnEveryPaymentAndFinanceRouteAndWithoutATokenIs401() throws Exception {
		Kit kit = kit();
		String first = twoInstallments(kit).get(0);
		String paymentId = paid(kit, first, "100.00", "PIX").get("payment").get("id").asString();
		String window = "?from=2026-01-01&to=2026-12-31";

		mvc.perform(get("/api/v1/installments/" + first + "/payments").with(as(kit.clinic(), Role.RECEPTIONIST))).andExpect(status().isForbidden());
		mvc.perform(pay(kit, Role.RECEPTIONIST, first, payment("10.00", "PIX", null))).andExpect(status().isForbidden());
		mvc.perform(reverse(kit, Role.RECEPTIONIST, paymentId)).andExpect(status().isForbidden());
		for (String path : List.of("/finance/receivables" + window, "/finance/overdue", "/finance/cash-flow" + window,
				"/finance/revenue" + window, "/patients/" + kit.patient() + "/financial")) {
			mvc.perform(get("/api/v1" + path).with(as(kit.clinic(), Role.RECEPTIONIST))).andExpect(status().isForbidden());
			mvc.perform(get("/api/v1" + path)).andExpect(status().isUnauthorized());
		}
		assertThat(DatabaseContainer.admin().queryForObject("SELECT count(*) FROM core.payments WHERE installment_id = ?::uuid", Long.class, first)).isEqualTo(1);
	}

	// ---- relatórios ----

	@Test
	void cashFlowIsNetOfReversalsByDayAndMethodOnTheCashDate() throws Exception {
		Kit kit = kit();
		List<String> installments = twoInstallments(kit);
		LocalDate today = todayIn(kit.clinic());
		String pix = paid(kit, installments.get(0), "300.00", "PIX").get("payment").get("id").asString();
		paid(kit, installments.get(1), "100.00", "CASH");
		read(mvc.perform(pay(kit, Role.DENTIST_ADMIN, installments.get(1), payment("50.00", "PIX", today.minusDays(2).toString()))).andExpect(status().isCreated()).andReturn());
		mvc.perform(reverse(kit, Role.DENTIST_ADMIN, pix)).andExpect(status().isCreated());

		JsonNode flow = getJson(kit, "/api/v1/finance/cash-flow?from=%s&to=%s".formatted(today.minusDays(5), today));

		assertThat(flow.get("netTotal").asString()).isEqualTo("150.00"); // 300 − 300 + 100 + 50
		assertThat(flow.get("byDay")).hasSize(2);
		assertThat(flow.get("byDay").get(0).get("date").asString()).isEqualTo(today.minusDays(2).toString());
		assertThat(flow.get("byDay").get(0).get("net").asString()).isEqualTo("50.00");
		assertThat(flow.get("byDay").get(1).get("net").asString()).isEqualTo("100.00");
		JsonNode byMethod = flow.get("byMethod");
		assertThat(byMethod).hasSize(2);
		assertThat(byMethod.toString()).contains("\"method\":\"CASH\",\"net\":\"100.00\"").contains("\"method\":\"PIX\",\"net\":\"50.00\"");
		assertThat(getJson(kit, "/api/v1/finance/cash-flow?from=%s&to=%s".formatted(today.plusDays(1), today.plusDays(2))).get("netTotal").asString()).isEqualTo("0.00");
	}

	@Test
	void theWindowAndPagingAreValidated() throws Exception {
		Kit kit = kit();
		for (String path : List.of("/finance/cash-flow", "/finance/cash-flow?from=2026-01-01", "/finance/cash-flow?from=2026-05-01&to=2026-04-30",
				"/finance/cash-flow?from=2026-01-01&to=2027-01-02", "/finance/revenue?from=2026-13-01&to=2026-12-31",
				"/finance/receivables?from=2026-01-01&to=2026-12-31&size=101", "/finance/receivables?from=2026-01-01&to=2026-12-31&size=0",
				"/finance/receivables?from=2026-01-01&to=2026-12-31&page=-1", "/finance/overdue?size=101", "/finance/revenue?from=1999-12-01&to=1999-12-31")) {
			mvc.perform(get("/api/v1" + path).with(as(kit.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isBadRequest());
		}
		mvc.perform(get("/api/v1/finance/cash-flow?from=2026-01-01&to=2026-12-31").with(as(kit.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isOk());
		mvc.perform(get("/api/v1/finance/cash-flow?from=2028-01-01&to=2028-12-31").with(as(kit.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isOk()); // 366 dias (bissexto)
	}

	@Test
	void receivablesAreOpenInstallmentsInTheWindowWithNetPaidBalanceAndASummary() throws Exception {
		Kit kit = kit();
		List<String> installments = twoInstallments(kit); // vencem em 2027-03-01 e 2027-04-01
		paid(kit, installments.get(0), "100.00", "PIX");

		JsonNode page = getJson(kit, "/api/v1/finance/receivables?from=2027-03-01&to=2027-04-30");

		assertThat(page.get("content")).hasSize(2);
		JsonNode first = page.get("content").get(0);
		assertThat(first.get("installmentId").asString()).isEqualTo(installments.get(0));
		assertThat(first.get("dueDate").asString()).isEqualTo("2027-03-01");
		assertThat(first.get("netPaid").asString()).isEqualTo("100.00");
		assertThat(first.get("balance").asString()).isEqualTo("300.00");
		assertThat(first.get("patient").get("name").asString()).isEqualTo("Maria Souza");
		assertThat(first.get("budget").get("number").asInt()).isGreaterThan(0);
		assertThat(first.has("daysOverdue") && !first.get("daysOverdue").isNull()).isFalse();
		assertThat(page.get("summary").get("count").asInt()).isEqualTo(2);
		assertThat(page.get("summary").get("balanceTotal").asString()).isEqualTo("700.00");
		assertThat(page.get("totalElements").asInt()).isEqualTo(2);
		// só a janela pedida e só abertas
		assertThat(getJson(kit, "/api/v1/finance/receivables?from=2027-03-02&to=2027-03-31").get("content")).isEmpty();
		paid(kit, installments.get(0), "300.00", "PIX");
		assertThat(getJson(kit, "/api/v1/finance/receivables?from=2027-03-01&to=2027-04-30").get("content")).hasSize(1);
		assertThat(getJson(kit, "/api/v1/finance/receivables?from=2027-03-01&to=2027-04-30&size=1&page=1").get("content")).isEmpty();
	}

	@Test
	void overdueIsDerivedFromTodayInTheClinicZoneAndOrderedOldestFirst() throws Exception {
		Kit kit = kit();
		List<String> installments = twoInstallments(kit);
		LocalDate today = todayIn(kit.clinic());
		adminSql(kit.clinic(), "UPDATE core.installments SET due_date = DATE '%s' WHERE id = '%s'".formatted(today.minusDays(10), installments.get(1)));
		adminSql(kit.clinic(), "UPDATE core.installments SET due_date = DATE '%s' WHERE id = '%s'".formatted(today, installments.get(0)));

		JsonNode page = getJson(kit, "/api/v1/finance/overdue");

		assertThat(page.get("content")).hasSize(1); // vencendo hoje ainda não está em atraso
		assertThat(page.get("content").get(0).get("installmentId").asString()).isEqualTo(installments.get(1));
		assertThat(page.get("content").get(0).get("daysOverdue").asLong()).isEqualTo(10);
		assertThat(page.get("summary").get("count").asInt()).isEqualTo(1);
		assertThat(page.get("summary").get("balanceTotal").asString()).isEqualTo("400.00");
		paid(kit, installments.get(1), "400.00", "PIX");
		assertThat(getJson(kit, "/api/v1/finance/overdue").get("content")).isEmpty();
	}

	@Test
	void revenueUsesTheClinicZoneAtTheMonthBoundary() throws Exception {
		Kit kit = kit();
		String budget = approvedBudgetOf(kit);
		ZoneId zone = zoneOf(kit.clinic());
		// 30 min antes da virada de 2026-10 → 2026-11 na clínica: é outubro lá, mesmo que seja novembro em UTC
		Instant decided = LocalDate.of(2026, 11, 1).atStartOfDay(zone).toInstant().minusSeconds(1800);
		sql(kit.clinic(), "UPDATE core.budgets SET decided_at = TIMESTAMPTZ '%s' WHERE id = '%s'".formatted(decided, budget));

		JsonNode october = getJson(kit, "/api/v1/finance/revenue?from=2026-10-01&to=2026-10-31");
		JsonNode november = getJson(kit, "/api/v1/finance/revenue?from=2026-11-01&to=2026-11-30");

		assertThat(october.get("count").asInt()).isEqualTo(1);
		assertThat(october.get("totalAmount").asString()).isEqualTo("800.00");
		assertThat(october.get("truncated").asBoolean()).isFalse();
		assertThat(october.get("budgets").get(0).get("id").asString()).isEqualTo(budget);
		assertThat(october.get("budgets").get(0).get("patient").get("name").asString()).isEqualTo("Maria Souza");
		assertThat(november.get("count").asInt()).isZero();
		assertThat(november.get("totalAmount").asString()).isEqualTo("0.00");
	}

	@Test
	void thePatientFinancialSheetTotalsComeFromAggregatesAndInactiveOrAnonymizedStayReadable() throws Exception {
		Kit kit = kit();
		List<String> installments = twoInstallments(kit);
		paid(kit, installments.get(0), "400.00", "PIX");
		paid(kit, installments.get(1), "100.00", "PIX");
		LocalDate today = todayIn(kit.clinic());
		adminSql(kit.clinic(), "UPDATE core.installments SET due_date = DATE '%s' WHERE id = '%s'".formatted(today.minusDays(3), installments.get(1)));

		JsonNode sheet = getJson(kit, "/api/v1/patients/" + kit.patient() + "/financial");

		assertThat(sheet.get("approvedTotal").asString()).isEqualTo("800.00");
		assertThat(sheet.get("activeInstallmentsTotal").asString()).isEqualTo("800.00");
		assertThat(sheet.get("paid").asString()).isEqualTo("500.00");
		assertThat(sheet.get("balance").asString()).isEqualTo("300.00");
		assertThat(sheet.get("overdue").asString()).isEqualTo("300.00");
		assertThat(sheet.get("budgets")).hasSize(1);
		assertThat(sheet.get("installments")).hasSize(2);
		assertThat(sheet.get("truncated").asBoolean()).isFalse();
		adminSql(kit.clinic(), "UPDATE core.patients SET active = false, deactivated_at = now() WHERE id = '%s'".formatted(kit.patient()));
		sql(kit.clinic(), "UPDATE core.patients SET anonymized_at = now() WHERE id = '%s'".formatted(kit.patient()));
		assertThat(getJson(kit, "/api/v1/patients/" + kit.patient() + "/financial").get("paid").asString()).isEqualTo("500.00");
		mvc.perform(get("/api/v1/patients/" + java.util.UUID.randomUUID() + "/financial").with(as(kit.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/patients/" + kit.patient() + "/financial").with(as(kit().clinic(), Role.DENTIST_ADMIN))).andExpect(status().isNotFound());
	}

	// ---- carga: 5 mil parcelas e 5 mil pagamentos na clínica ----

	@Test
	void withFiveThousandInstallmentsAndPaymentsTheQueriesMeetP95AndNeverSeqScan() throws Exception {
		Kit kit = kit();
		Clinic clinic = kit.clinic();
		String user = userOf(clinic, Role.DENTIST_ADMIN).toString();
		String typical = createPatient(clinic, "Pedro Reis"); // paciente típico: 12 parcelas; o paciente do kit concentra 1100 (ficha truncada)
		// linhas diretas (triggers suspensos só nesta transação): 5000 orçamentos aprovados, 5000 parcelas abertas, 5000 pagamentos
		sql(clinic, """
				INSERT INTO core.budgets (id, clinic_id, number, patient_id, status, total_amount, sent_at, decided_at, created_by)
				SELECT md5('b' || g)::uuid, '%1$s', 100000 + g, CASE WHEN g <= 1100 THEN '%2$s'::uuid WHEN g <= 1112 THEN '%4$s'::uuid ELSE md5('pt' || (g %% 500))::uuid END, 'APPROVED', 100.00, now() - interval '400 days', now() - (g %% 300) * interval '1 day', '%3$s'
				FROM generate_series(1, 5000) g;
				INSERT INTO core.installments (id, clinic_id, budget_id, patient_id, number, amount, due_date, status, created_by)
				SELECT md5('i' || g)::uuid, '%1$s', md5('b' || g)::uuid, CASE WHEN g <= 1100 THEN '%2$s'::uuid WHEN g <= 1112 THEN '%4$s'::uuid ELSE md5('pt' || (g %% 500))::uuid END, 1, 100.00, current_date + (g %% 400 - 200), 'OPEN', '%3$s'
				FROM generate_series(1, 5000) g;
				INSERT INTO core.payments (id, clinic_id, installment_id, kind, amount, method, paid_on, created_by)
				SELECT md5('p' || g)::uuid, '%1$s', md5('i' || g)::uuid, 'PAYMENT', 30.00, 'PIX', current_date - (g %% 300), '%3$s'
				FROM generate_series(1, 5000) g""".formatted(clinic.id(), kit.patient(), user, typical));
		// o banco do PRD tem 100 mil parcelas e 100 mil pagamentos: as outras 95 mil de cada vêm de clínicas sintéticas (FKs suspensas)
		DatabaseContainer.admin().execute("""
				BEGIN;
				SET LOCAL session_replication_role = replica;
				INSERT INTO core.budgets (id, clinic_id, number, patient_id, status, total_amount, sent_at, decided_at, created_by)
				SELECT md5('xb' || g)::uuid, md5('xc' || (g %% 50))::uuid, g, md5('xp' || (g %% 2000))::uuid, 'APPROVED', 100.00, now() - interval '400 days', now() - (g %% 300) * interval '1 day', md5('xu')::uuid
				FROM generate_series(1, 95000) g;
				INSERT INTO core.installments (id, clinic_id, budget_id, patient_id, number, amount, due_date, status, created_by)
				SELECT md5('xi' || g)::uuid, md5('xc' || (g %% 50))::uuid, md5('xb' || g)::uuid, md5('xp' || (g %% 2000))::uuid, 1, 100.00, current_date + (g %% 400 - 200), 'OPEN', md5('xu')::uuid
				FROM generate_series(1, 95000) g;
				INSERT INTO core.payments (id, clinic_id, installment_id, kind, amount, method, paid_on, created_by)
				SELECT md5('xq' || g)::uuid, md5('xc' || (g %% 50))::uuid, md5('xi' || g)::uuid, 'PAYMENT', 30.00, 'PIX', current_date - (g %% 300), md5('xu')::uuid
				FROM generate_series(1, 95000) g;
				COMMIT""".replace("%%", "%"));
		DatabaseContainer.admin().execute("ANALYZE core.budgets");
		DatabaseContainer.admin().execute("ANALYZE core.installments");
		DatabaseContainer.admin().execute("ANALYZE core.payments");
		LocalDate today = todayIn(clinic);
		String window = "from=%s&to=%s".formatted(today.minusDays(150), today.plusDays(150));
		List<String> paths = List.of("/finance/receivables?" + window + "&size=100", "/finance/overdue?size=100",
				"/finance/cash-flow?from=%s&to=%s".formatted(today.minusDays(300), today), "/finance/revenue?from=%s&to=%s".formatted(today.minusDays(300), today),
				"/patients/" + typical + "/financial");

		for (String path : paths) {
			for (int warm = 0; warm < 3; warm++) {
				mvc.perform(get("/api/v1" + path).with(as(clinic, Role.DENTIST_ADMIN))).andExpect(status().isOk());
			}
			List<Long> samples = new ArrayList<>();
			for (int i = 0; i < 20; i++) {
				long start = System.nanoTime();
				mvc.perform(get("/api/v1" + path).with(as(clinic, Role.DENTIST_ADMIN))).andExpect(status().isOk());
				samples.add((System.nanoTime() - start) / 1_000_000);
			}
			long p95 = samples.stream().sorted().toList().get(18);
			System.out.println("P95 " + path.replaceAll("[0-9a-f]{8}-[0-9a-f-]{27}", "{id}") + "=" + p95 + " ms");
			assertThat(p95).as("p95 de %s (ms)", path).isLessThanOrEqualTo(100);
		}
		JsonNode sheet = getJson(kit, "/api/v1/patients/" + kit.patient() + "/financial");
		assertThat(sheet.get("truncated").asBoolean()).isTrue();
		assertThat(sheet.get("installments")).hasSize(1000);
		assertThat(sheet.get("activeInstallmentsTotal").asString()).isEqualTo("110000.00");
		assertThat(sheet.get("paid").asString()).isEqualTo("33000.00");

		// os planos das consultas do financeiro não usam Seq Scan (com a clínica filtrada, como o filtro de tenant faz)
		for (String explain : List.of(
				"SELECT i.id FROM core.installments i WHERE i.clinic_id = '%s' AND i.status = 'OPEN' AND i.due_date >= current_date - 150 AND i.due_date <= current_date + 150 ORDER BY i.due_date, i.id LIMIT 100",
				"SELECT i.id FROM core.installments i WHERE i.clinic_id = '%s' AND i.status = 'OPEN' AND i.due_date < current_date ORDER BY i.due_date, i.id LIMIT 100",
				"SELECT p.paid_on, sum(p.amount) FROM core.payments p WHERE p.clinic_id = '%s' AND p.paid_on >= current_date - 30 AND p.paid_on <= current_date GROUP BY p.paid_on",
				"SELECT i.id FROM core.installments i WHERE i.clinic_id = '%s' AND i.patient_id = '" + typical + "' ORDER BY i.due_date, i.id LIMIT 1001",
				// (os predicados abaixo são seletivos de propósito: com 75–100% das linhas na janela o planejador escolhe Seq Scan, e acerta)
		// somas de líquido pago do resumo (semi-junção partindo das parcelas filtradas)
				"SELECT coalesce(sum(CASE WHEN p.kind = 'PAYMENT' THEN p.amount ELSE -p.amount END), 0) FROM core.payments p WHERE p.clinic_id = '%s' AND EXISTS (SELECT 1 FROM core.installments i WHERE i.id = p.installment_id AND i.clinic_id = '%<s' AND i.status = 'OPEN' AND i.due_date >= current_date - 10 AND i.due_date <= current_date + 10)",
				"SELECT coalesce(sum(CASE WHEN p.kind = 'PAYMENT' THEN p.amount ELSE -p.amount END), 0) FROM core.payments p WHERE p.clinic_id = '%s' AND EXISTS (SELECT 1 FROM core.installments i WHERE i.id = p.installment_id AND i.clinic_id = '%<s' AND i.status = 'OPEN' AND i.due_date < current_date - 190)",
				"SELECT coalesce(sum(CASE WHEN p.kind = 'PAYMENT' THEN p.amount ELSE -p.amount END), 0) FROM core.payments p WHERE p.clinic_id = '%s' AND EXISTS (SELECT 1 FROM core.installments i WHERE i.id = p.installment_id AND i.clinic_id = '%<s' AND i.patient_id = '" + java.util.UUID.randomUUID() + "')")) {
			List<String> plan = DatabaseContainer.admin().queryForList("EXPLAIN " + explain.formatted(clinic.id()), String.class);
			assertThat(String.join("\n", plan)).as(explain).doesNotContain("Seq Scan");
		}
	}

	// ---- higiene de log ----

	@Test
	void thePaymentAuditLogCarriesOnlyIdsNeverAmountsMethodsDatesOrNames() throws Exception {
		ch.qos.logback.classic.Logger finance = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("crm_data.finance.audit");
		ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = new ch.qos.logback.core.read.ListAppender<>();
		appender.start();
		finance.addAppender(appender);
		Kit kit = kit();
		String first;
		String paymentId;
		try {
			first = twoInstallments(kit).get(0);
			String past = todayIn(kit.clinic()).minusDays(7).toString();
			paymentId = read(mvc.perform(pay(kit, Role.DENTIST_ADMIN, first, payment("123.45", "BANK_TRANSFER", past))).andExpect(status().isCreated())
					.andReturn()).get("payment").get("id").asString();
			mvc.perform(reverse(kit, Role.DENTIST_ADMIN, paymentId)).andExpect(status().isCreated());
		}
		finally {
			finance.detachAppender(appender);
		}

		List<String> messages = appender.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList();
		assertThat(messages).anySatisfy(m -> assertThat(m).contains("Pagamento registrado", paymentId, first));
		assertThat(messages).anySatisfy(m -> assertThat(m).contains("Pagamento estornado", paymentId, first));
		assertThat(messages).allSatisfy(m -> assertThat(m).doesNotContain("123.45", "BANK_TRANSFER", "Maria", "Souza", todayIn(kit.clinic()).minusDays(7).toString()));
	}
}
