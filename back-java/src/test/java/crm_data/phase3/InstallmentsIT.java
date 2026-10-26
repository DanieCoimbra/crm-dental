package crm_data.phase3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import crm_data.shared.security.Role;
import crm_data.support.DatabaseContainer;
import crm_data.support.tenant.TenantFixtures.Clinic;
import tools.jackson.databind.JsonNode;

/**
 * F05 contra o PostgreSQL real (V001–V029): decisão do orçamento ({@code SENT → APPROVED/REJECTED} só pelo dentista),
 * plano de parcelas (AUTO com e sem entrada, MANUAL), refazer, {@code CR011} forçado no commit (409, nada gravado),
 * vencimento, pagamento líquido, paciente inativo e anonimizado, concorrência, papéis e isolamento entre clínicas.
 */
class InstallmentsIT extends Phase3ItSupport {

	@AfterEach
	void dropFaultTriggers() {
		var admin = DatabaseContainer.admin();
		admin.execute("DROP TRIGGER IF EXISTS itest_inflate ON core.installments");
		admin.execute("DROP FUNCTION IF EXISTS core.itest_inflate()");
	}

	// ---- auxiliares ----

	private MockHttpServletRequestBuilder decide(Kit kit, Role role, String action, String id, long version) {
		return json(post("/api/v1/budgets/" + id + "/" + action), "{\"version\":%d}".formatted(version)).with(as(kit.clinic(), role));
	}

	/** Orçamento de R$ 800,00 (2 × 250,00 + 1 × 900,00 − 600,00 de desconto) em {@code SENT}; devolve o id. */
	private String sentBudget(Kit kit) throws Exception {
		JsonNode draft = createBudget(kit, "600.00", item(kit.restoration(), 2, null), item(kit.root(), 1, null));
		assertThat(draft.get("totalAmount").asString()).isEqualTo("800.00");
		String id = draft.get("id").asString();
		forceStatus(kit.clinic(), id, "SENT");
		return id;
	}

	private String approvedBudget(Kit kit) throws Exception {
		String id = sentBudget(kit);
		long version = getBudget(kit.clinic(), Role.DENTIST_ADMIN, id).get("version").asLong();
		mvc.perform(decide(kit, Role.DENTIST_ADMIN, "approve", id, version)).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("APPROVED")).andExpect(jsonPath("$.decidedAt").isNotEmpty());
		return id;
	}

	private static String auto(int count, String firstDue, String down) {
		return "{\"mode\":\"AUTO\",\"count\":%d,\"firstDueDate\":\"%s\"%s}".formatted(count, firstDue,
				down == null ? "" : ",\"downPayment\":\"" + down + "\"");
	}

	private static String manual(String... parts) {
		return "{\"mode\":\"MANUAL\",\"installments\":[" + String.join(",", parts) + "]}";
	}

	private static String part(String amount, String due) {
		return "{\"amount\":\"%s\",\"dueDate\":\"%s\"}".formatted(amount, due);
	}

	private MockHttpServletRequestBuilder createPlan(Kit kit, Role role, String id, String body) {
		return json(post("/api/v1/budgets/" + id + "/installments"), body).with(as(kit.clinic(), role));
	}

	private MockHttpServletRequestBuilder redoPlan(Kit kit, Role role, String id, String body) {
		return json(put("/api/v1/budgets/" + id + "/installments"), body).with(as(kit.clinic(), role));
	}

	private JsonNode plan(Kit kit, String id) throws Exception {
		return read(mvc.perform(get("/api/v1/budgets/" + id + "/installments").with(as(kit.clinic(), Role.DENTIST_ADMIN)))
				.andExpect(status().isOk()).andReturn());
	}

	private long installmentRows(String budgetId) {
		return DatabaseContainer.admin().queryForObject("SELECT count(*) FROM core.installments WHERE budget_id = ?::uuid", Long.class, budgetId);
	}

	private String statusOf(String installmentId) {
		return DatabaseContainer.admin().queryForObject("SELECT status FROM core.installments WHERE id = ?::uuid", String.class, installmentId);
	}

	private void pay(Clinic clinic, String installmentId, String amount, String kind, String reverses) {
		adminSql(clinic, ("INSERT INTO core.payments (clinic_id, installment_id, kind, amount, method, paid_on, reverses_payment_id, created_by) "
				+ "VALUES ('%s', '%s', '%s', %s, 'PIX', current_date, %s, '%s')").formatted(clinic.id(), installmentId, kind, amount,
				reverses == null ? "NULL" : "'" + reverses + "'", userOf(clinic, Role.DENTIST_ADMIN)));
	}

	private String paymentId(String installmentId, String kind) {
		return DatabaseContainer.admin().queryForObject("SELECT id::text FROM core.payments WHERE installment_id = ?::uuid AND kind = ?",
				String.class, installmentId, kind);
	}

	private static BigDecimal sumOf(JsonNode plan, boolean activeOnly) {
		BigDecimal sum = BigDecimal.ZERO;
		for (JsonNode installment : plan.get("installments")) {
			if (!activeOnly || !installment.get("status").asString().equals("CANCELED")) {
				sum = sum.add(new BigDecimal(installment.get("amount").asString()));
			}
		}
		return sum;
	}

	// ---- decisão do orçamento ----

	@Test
	void theDentistApprovesAndRejectsOnlyASentBudgetAndTheStatusIsTerminal() throws Exception {
		Kit kit = kit();
		String toApprove = sentBudget(kit);
		String toReject = sentBudget(kit);

		JsonNode approved = read(mvc.perform(decide(kit, Role.DENTIST_ADMIN, "approve", toApprove,
				getBudget(kit.clinic(), Role.DENTIST_ADMIN, toApprove).get("version").asLong())).andExpect(status().isOk()).andReturn());
		JsonNode rejected = read(mvc.perform(decide(kit, Role.DENTIST_ADMIN, "reject", toReject,
				getBudget(kit.clinic(), Role.DENTIST_ADMIN, toReject).get("version").asLong())).andExpect(status().isOk()).andReturn());

		assertThat(approved.get("status").asString()).isEqualTo("APPROVED");
		assertThat(approved.get("decidedAt").asString()).isNotBlank();
		assertThat(rejected.get("status").asString()).isEqualTo("REJECTED");
		// terminais: nada muda mais, nem pelo mesmo nem pelo outro caminho
		mvc.perform(decide(kit, Role.DENTIST_ADMIN, "reject", toApprove, approved.get("version").asLong())).andExpect(status().isConflict());
		mvc.perform(decide(kit, Role.DENTIST_ADMIN, "approve", toReject, rejected.get("version").asLong())).andExpect(status().isConflict());
		assertThat(getBudget(kit.clinic(), Role.DENTIST_ADMIN, toApprove).get("status").asString()).isEqualTo("APPROVED");
	}

	@Test
	void aDraftIsNotDecidedAndTheMessagePointsToDiscard() throws Exception {
		Kit kit = kit();
		JsonNode draft = createBudget(kit, null, item(kit.restoration(), 1, null));
		String id = draft.get("id").asString();
		long version = draft.get("version").asLong();

		mvc.perform(decide(kit, Role.DENTIST_ADMIN, "approve", id, version)).andExpect(status().isConflict());
		mvc.perform(decide(kit, Role.DENTIST_ADMIN, "reject", id, version)).andExpect(status().isConflict())
				.andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("descartar")));

		assertThat(getBudget(kit.clinic(), Role.DENTIST_ADMIN, id).get("status").asString()).isEqualTo("DRAFT");
	}

	@Test
	void aStaleVersionTheReceptionistAndAnotherClinicAreRefused() throws Exception {
		Kit kit = kit();
		String id = sentBudget(kit);
		long version = getBudget(kit.clinic(), Role.DENTIST_ADMIN, id).get("version").asLong();
		Kit other = kit();

		mvc.perform(decide(kit, Role.DENTIST_ADMIN, "approve", id, version + 5)).andExpect(status().isConflict());
		mvc.perform(decide(kit, Role.RECEPTIONIST, "approve", id, version)).andExpect(status().isForbidden());
		mvc.perform(decide(other, Role.DENTIST_ADMIN, "approve", id, version)).andExpect(status().isNotFound());

		assertThat(getBudget(kit.clinic(), Role.DENTIST_ADMIN, id).get("status").asString()).isEqualTo("SENT");
	}

	@Test
	void twoSimultaneousApprovalsOfTheSameVersionLeaveOneWinner() throws Exception {
		Kit kit = kit();
		String id = sentBudget(kit);
		long version = getBudget(kit.clinic(), Role.DENTIST_ADMIN, id).get("version").asLong();
		CountDownLatch go = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		List<Future<Integer>> results = new ArrayList<>();
		for (String action : List.of("approve", "reject")) {
			results.add(pool.submit(() -> {
				go.await();
				return mvc.perform(decide(kit, Role.DENTIST_ADMIN, action, id, version)).andReturn().getResponse().getStatus();
			}));
		}
		go.countDown();
		List<Integer> statuses = List.of(results.get(0).get(), results.get(1).get());
		pool.shutdown();

		assertThat(statuses).containsExactlyInAnyOrder(200, 409);
	}

	// ---- criar o plano ----

	@Test
	void autoWithADownPaymentCreatesFourOpenInstallmentsThatSumToTheTotal() throws Exception {
		Kit kit = kit();
		String id = approvedBudget(kit);

		JsonNode created = read(mvc.perform(createPlan(kit, Role.DENTIST_ADMIN, id, auto(4, "2027-01-31", "200.00")))
				.andExpect(status().isCreated()).andReturn());

		assertThat(created.get("totalAmount").asString()).isEqualTo("800.00");
		assertThat(created.get("activeSum").asString()).isEqualTo("800.00");
		JsonNode items = created.get("installments");
		assertThat(items).hasSize(4);
		for (int i = 0; i < 4; i++) {
			assertThat(items.get(i).get("number").asInt()).isEqualTo(i + 1);
			assertThat(items.get(i).get("status").asString()).isEqualTo("OPEN");
			assertThat(items.get(i).get("netPaid").asString()).isEqualTo("0.00");
			assertThat(items.get(i).get("balance").asString()).isEqualTo(items.get(i).get("amount").asString());
		}
		assertThat(items.get(0).get("amount").asString()).isEqualTo("200.00");
		LocalDate down = LocalDate.parse(items.get(0).get("dueDate").asString());
		assertThat(down).isBetween(LocalDate.now().minusDays(1), LocalDate.now().plusDays(1));
		assertThat(items.get(1).get("dueDate").asString()).isEqualTo("2027-01-31");
		assertThat(items.get(2).get("dueDate").asString()).isEqualTo("2027-02-28");
		assertThat(items.get(3).get("dueDate").asString()).isEqualTo("2027-03-31");
		assertThat(sumOf(created, true)).isEqualByComparingTo("800.00");
		assertThat(plan(kit, id).get("installments")).hasSize(4);
	}

	@Test
	void anOddTotalLeavesTheExtraCentsOnTheLastInstallment() throws Exception {
		Kit kit = kit();
		JsonNode draft = createBudget(kit, null, item(kit.restoration(), 1, "150.00")); // 250,00 − 150,00 = 100,00
		String id = draft.get("id").asString();
		forceStatus(kit.clinic(), id, "SENT");
		mvc.perform(decide(kit, Role.DENTIST_ADMIN, "approve", id, getBudget(kit.clinic(), Role.DENTIST_ADMIN, id).get("version").asLong()))
				.andExpect(status().isOk());

		JsonNode created = read(mvc.perform(createPlan(kit, Role.DENTIST_ADMIN, id, auto(3, "2027-01-10", null))).andExpect(status().isCreated())
				.andReturn());

		assertThat(created.get("installments").get(0).get("amount").asString()).isEqualTo("33.33");
		assertThat(created.get("installments").get(1).get("amount").asString()).isEqualTo("33.33");
		assertThat(created.get("installments").get(2).get("amount").asString()).isEqualTo("33.34");
		assertThat(sumOf(created, true)).isEqualByComparingTo("100.00");
	}

	@Test
	void manualSortsByDueDateAndNumbersTheInstallments() throws Exception {
		Kit kit = kit();
		String id = approvedBudget(kit);

		JsonNode created = read(mvc.perform(createPlan(kit, Role.DENTIST_ADMIN, id,
				manual(part("500.00", "2027-03-01"), part("300.00", "2027-02-01")))).andExpect(status().isCreated()).andReturn());

		assertThat(created.get("installments").get(0).get("amount").asString()).isEqualTo("300.00");
		assertThat(created.get("installments").get(0).get("number").asInt()).isEqualTo(1);
		assertThat(created.get("installments").get(1).get("amount").asString()).isEqualTo("500.00");
	}

	@Test
	void invalidPlansAre400AndPersistNothing() throws Exception {
		Kit kit = kit();
		String id = approvedBudget(kit);

		mvc.perform(createPlan(kit, Role.DENTIST_ADMIN, id, manual(part("700.00", "2027-03-01")))).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].message").value(org.hamcrest.Matchers.containsString("100.00")));
		mvc.perform(createPlan(kit, Role.DENTIST_ADMIN, id, manual(part("0.00", "2027-03-01"), part("800.00", "2027-04-01"))))
				.andExpect(status().isBadRequest());
		mvc.perform(createPlan(kit, Role.DENTIST_ADMIN, id, auto(61, "2027-03-01", null))).andExpect(status().isBadRequest());
		mvc.perform(createPlan(kit, Role.DENTIST_ADMIN, id, auto(3, "1999-12-31", null))).andExpect(status().isBadRequest());
		mvc.perform(createPlan(kit, Role.DENTIST_ADMIN, id, auto(3, "2027-03-01", "800.00"))).andExpect(status().isBadRequest());
		mvc.perform(createPlan(kit, Role.DENTIST_ADMIN, id, auto(1, "2027-03-01", "100.00"))).andExpect(status().isBadRequest());

		assertThat(installmentRows(id)).isZero();
	}

	@Test
	void aSecondPlanIs409AndABudgetThatIsNotApprovedHasNoPlan() throws Exception {
		Kit kit = kit();
		String id = approvedBudget(kit);
		mvc.perform(createPlan(kit, Role.DENTIST_ADMIN, id, auto(2, "2027-03-01", null))).andExpect(status().isCreated());
		String draft = createBudget(kit, null, item(kit.restoration(), 1, null)).get("id").asString();
		String sent = sentBudget(kit);

		mvc.perform(createPlan(kit, Role.DENTIST_ADMIN, id, auto(2, "2027-03-01", null))).andExpect(status().isConflict());
		mvc.perform(createPlan(kit, Role.DENTIST_ADMIN, draft, auto(2, "2027-03-01", null))).andExpect(status().isConflict());
		mvc.perform(createPlan(kit, Role.DENTIST_ADMIN, sent, auto(2, "2027-03-01", null))).andExpect(status().isConflict());

		assertThat(installmentRows(id)).isEqualTo(2);
		assertThat(installmentRows(draft)).isZero();
		assertThat(installmentRows(sent)).isZero();
	}

	@Test
	void aWrongSumDetectedByTheDatabaseOnlyAtCommitIs409AndNothingIsPersisted() throws Exception {
		Kit kit = kit();
		String id = approvedBudget(kit);
		DatabaseContainer.admin().execute("""
				CREATE FUNCTION core.itest_inflate() RETURNS trigger LANGUAGE plpgsql AS $$
				BEGIN NEW.amount := NEW.amount + 0.01; RETURN NEW; END $$""");
		DatabaseContainer.admin().execute("CREATE TRIGGER itest_inflate BEFORE INSERT ON core.installments FOR EACH ROW "
				+ "WHEN (NEW.number = 1) EXECUTE FUNCTION core.itest_inflate()");

		mvc.perform(createPlan(kit, Role.DENTIST_ADMIN, id, auto(3, "2027-03-01", null))).andExpect(status().isConflict())
				.andExpect(jsonPath("$.detail").value("As parcelas não somam o total do orçamento."));

		assertThat(installmentRows(id)).isZero();
	}

	// ---- consulta do plano ----

	@Test
	void theListShowsNetPaidBalanceAndTheStatusKeptByTheDatabase() throws Exception {
		Kit kit = kit();
		String id = approvedBudget(kit);
		JsonNode created = read(mvc.perform(createPlan(kit, Role.DENTIST_ADMIN, id, auto(2, "2027-03-01", null))).andExpect(status().isCreated())
				.andReturn());
		String first = created.get("installments").get(0).get("id").asString();
		String second = created.get("installments").get(1).get("id").asString();
		pay(kit.clinic(), first, "400.00", "PAYMENT", null);
		pay(kit.clinic(), second, "100.00", "PAYMENT", null);

		JsonNode listed = plan(kit, id);

		JsonNode paid = listed.get("installments").get(0);
		JsonNode partial = listed.get("installments").get(1);
		assertThat(paid.get("status").asString()).isEqualTo("PAID");
		assertThat(paid.get("netPaid").asString()).isEqualTo("400.00");
		assertThat(paid.get("balance").asString()).isEqualTo("0.00");
		assertThat(partial.get("status").asString()).isEqualTo("OPEN");
		assertThat(partial.get("netPaid").asString()).isEqualTo("100.00");
		assertThat(partial.get("balance").asString()).isEqualTo("300.00");
		assertThat(paid.get("version").asLong()).as("o trigger do pagamento incrementa a versão").isGreaterThan(0);
	}

	@Test
	void aBudgetWithoutAPlanListsNothingAndAnUnknownOrForeignBudgetIs404() throws Exception {
		Kit kit = kit();
		String id = approvedBudget(kit);
		Kit other = kit();

		JsonNode empty = plan(kit, id);
		assertThat(empty.get("installments")).isEmpty();
		assertThat(empty.get("activeSum").asString()).isEqualTo("0.00");
		mvc.perform(get("/api/v1/budgets/" + id + "/installments").with(as(other.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/budgets/" + java.util.UUID.randomUUID() + "/installments").with(as(kit.clinic(), Role.DENTIST_ADMIN)))
				.andExpect(status().isNotFound());
	}

	// ---- refazer o plano ----

	@Test
	void redoCancelsTheOpenInstallmentsReusesTheNumbersAndKeepsTheCanceledOnesInTheList() throws Exception {
		Kit kit = kit();
		String id = approvedBudget(kit);
		JsonNode created = read(mvc.perform(createPlan(kit, Role.DENTIST_ADMIN, id, auto(2, "2027-03-01", null))).andExpect(status().isCreated())
				.andReturn());
		String oldFirst = created.get("installments").get(0).get("id").asString();

		JsonNode redone = read(mvc.perform(redoPlan(kit, Role.DENTIST_ADMIN, id, auto(4, "2027-04-01", null))).andExpect(status().isOk())
				.andReturn());

		assertThat(redone.get("installments")).hasSize(6);
		assertThat(redone.get("activeSum").asString()).isEqualTo("800.00");
		assertThat(sumOf(redone, false)).isEqualByComparingTo("1600.00");
		assertThat(statusOf(oldFirst)).isEqualTo("CANCELED");
		long active = 0;
		for (JsonNode installment : redone.get("installments")) {
			if (installment.get("status").asString().equals("OPEN")) {
				active++;
			}
		}
		assertThat(active).isEqualTo(4);
		assertThat(sumOf(redone, true)).isEqualByComparingTo("800.00");
	}

	@Test
	void redoWithoutAPlanIs409() throws Exception {
		Kit kit = kit();
		String id = approvedBudget(kit);

		mvc.perform(redoPlan(kit, Role.DENTIST_ADMIN, id, auto(2, "2027-03-01", null))).andExpect(status().isConflict());
	}

	@Test
	void redoIsRefusedWhileThereIsANetPaymentAndAllowedAfterTheReversal() throws Exception {
		Kit kit = kit();
		String id = approvedBudget(kit);
		JsonNode created = read(mvc.perform(createPlan(kit, Role.DENTIST_ADMIN, id, auto(2, "2027-03-01", null))).andExpect(status().isCreated())
				.andReturn());
		String first = created.get("installments").get(0).get("id").asString();
		pay(kit.clinic(), first, "100.00", "PAYMENT", null);

		mvc.perform(redoPlan(kit, Role.DENTIST_ADMIN, id, auto(4, "2027-04-01", null))).andExpect(status().isConflict())
				.andExpect(jsonPath("$.detail").value("Estorne os pagamentos antes de refazer o plano."));
		assertThat(installmentRows(id)).as("nada foi cancelado nem inserido").isEqualTo(2);
		assertThat(statusOf(first)).isEqualTo("OPEN");

		pay(kit.clinic(), first, "100.00", "REVERSAL", paymentId(first, "PAYMENT"));
		mvc.perform(redoPlan(kit, Role.DENTIST_ADMIN, id, auto(4, "2027-04-01", null))).andExpect(status().isOk());
		assertThat(statusOf(first)).isEqualTo("CANCELED");
	}

	@Test
	void aCommitTimeSumFailureWhenRedoingKeepsTheOldPlan() throws Exception {
		Kit kit = kit();
		String id = approvedBudget(kit);
		mvc.perform(createPlan(kit, Role.DENTIST_ADMIN, id, auto(2, "2027-03-01", null))).andExpect(status().isCreated());
		DatabaseContainer.admin().execute("""
				CREATE FUNCTION core.itest_inflate() RETURNS trigger LANGUAGE plpgsql AS $$
				BEGIN NEW.amount := NEW.amount + 0.01; RETURN NEW; END $$""");
		DatabaseContainer.admin().execute("CREATE TRIGGER itest_inflate BEFORE INSERT ON core.installments FOR EACH ROW "
				+ "WHEN (NEW.number = 1) EXECUTE FUNCTION core.itest_inflate()");

		mvc.perform(redoPlan(kit, Role.DENTIST_ADMIN, id, auto(3, "2027-05-01", null))).andExpect(status().isConflict());

		assertThat(installmentRows(id)).isEqualTo(2);
		JsonNode listed = plan(kit, id);
		assertThat(sumOf(listed, true)).isEqualByComparingTo("800.00");
		assertThat(listed.get("installments").get(0).get("status").asString()).isEqualTo("OPEN");
	}

	// ---- vencimento ----

	@Test
	void theDueDateOfAnOpenInstallmentChangesAndTheVersionAdvances() throws Exception {
		Kit kit = kit();
		String id = approvedBudget(kit);
		JsonNode created = read(mvc.perform(createPlan(kit, Role.DENTIST_ADMIN, id, auto(2, "2027-03-01", null))).andExpect(status().isCreated())
				.andReturn());
		JsonNode first = created.get("installments").get(0);

		JsonNode changed = read(mvc.perform(json(patch("/api/v1/installments/" + first.get("id").asString() + "/due-date"),
				"{\"dueDate\":\"2027-05-20\",\"version\":%d}".formatted(first.get("version").asLong())).with(as(kit.clinic(), Role.DENTIST_ADMIN)))
				.andExpect(status().isOk()).andReturn());

		assertThat(changed.get("dueDate").asString()).isEqualTo("2027-05-20");
		assertThat(changed.get("version").asLong()).isGreaterThan(first.get("version").asLong());
		assertThat(changed.get("amount").asString()).isEqualTo(first.get("amount").asString());
		assertThat(plan(kit, id).get("installments").get(0).get("dueDate").asString()).isEqualTo("2027-05-20");
	}

	@Test
	void aPaidOrCanceledInstallmentAStaleVersionAndAnotherClinicAreRefused() throws Exception {
		Kit kit = kit();
		String id = approvedBudget(kit);
		JsonNode created = read(mvc.perform(createPlan(kit, Role.DENTIST_ADMIN, id, auto(2, "2027-03-01", null))).andExpect(status().isCreated())
				.andReturn());
		String first = created.get("installments").get(0).get("id").asString();
		JsonNode second = created.get("installments").get(1);
		pay(kit.clinic(), first, "400.00", "PAYMENT", null);
		long paidVersion = plan(kit, id).get("installments").get(0).get("version").asLong();
		Kit other = kit();
		String body = "{\"dueDate\":\"2027-05-20\",\"version\":%d}";

		mvc.perform(json(patch("/api/v1/installments/" + first + "/due-date"), body.formatted(paidVersion)).with(as(kit.clinic(), Role.DENTIST_ADMIN)))
				.andExpect(status().isConflict());
		mvc.perform(json(patch("/api/v1/installments/" + second.get("id").asString() + "/due-date"), body.formatted(second.get("version").asLong() + 7))
				.with(as(kit.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isConflict());
		mvc.perform(json(patch("/api/v1/installments/" + second.get("id").asString() + "/due-date"), body.formatted(second.get("version").asLong()))
				.with(as(other.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isNotFound());
		mvc.perform(json(patch("/api/v1/installments/" + second.get("id").asString() + "/due-date"), body.formatted(second.get("version").asLong()))
				.with(as(kit.clinic(), Role.RECEPTIONIST))).andExpect(status().isForbidden());

		// cancelada: refaz o plano com a segunda ainda aberta e tenta mudar a antiga
		pay(kit.clinic(), first, "400.00", "REVERSAL", paymentId(first, "PAYMENT"));
		mvc.perform(redoPlan(kit, Role.DENTIST_ADMIN, id, auto(2, "2027-06-01", null))).andExpect(status().isOk());
		long canceledVersion = plan(kit, id).get("installments").get(0).get("version").asLong();
		mvc.perform(json(patch("/api/v1/installments/" + first + "/due-date"), body.formatted(canceledVersion)).with(as(kit.clinic(), Role.DENTIST_ADMIN)))
				.andExpect(status().isConflict());
	}

	// ---- paciente inativo e anonimizado ----

	@Test
	void anInactivePatientKeepsThePlanAndTheDueDateButAnAnonymizedOneDoesNot() throws Exception {
		Kit kit = kit();
		String id = approvedBudget(kit);
		JsonNode created = read(mvc.perform(createPlan(kit, Role.DENTIST_ADMIN, id, auto(2, "2027-03-01", null))).andExpect(status().isCreated())
				.andReturn());
		JsonNode first = created.get("installments").get(0);
		adminSql(kit.clinic(), "UPDATE core.patients SET active = false, deactivated_at = now() WHERE id = '%s'".formatted(kit.patient()));

		mvc.perform(json(patch("/api/v1/installments/" + first.get("id").asString() + "/due-date"),
				"{\"dueDate\":\"2027-05-20\",\"version\":%d}".formatted(first.get("version").asLong())).with(as(kit.clinic(), Role.DENTIST_ADMIN)))
				.andExpect(status().isOk());
		mvc.perform(redoPlan(kit, Role.DENTIST_ADMIN, id, auto(3, "2027-06-01", null))).andExpect(status().isOk());

		// a função de anonimização recusaria (CR006: há parcela em aberto); o trigger é suspenso só nesta transação
		adminSql(kit.clinic(), "BEGIN; SET LOCAL session_replication_role = replica; UPDATE core.patients SET anonymized_at = now() WHERE id = '%s'; COMMIT".formatted(kit.patient()));
		JsonNode current = plan(kit, id).get("installments");
		JsonNode open = null;
		for (JsonNode installment : current) {
			if (installment.get("status").asString().equals("OPEN")) {
				open = installment;
			}
		}
		mvc.perform(json(patch("/api/v1/installments/" + open.get("id").asString() + "/due-date"),
				"{\"dueDate\":\"2027-09-20\",\"version\":%d}".formatted(open.get("version").asLong())).with(as(kit.clinic(), Role.DENTIST_ADMIN)))
				.andExpect(status().isConflict());
		mvc.perform(redoPlan(kit, Role.DENTIST_ADMIN, id, auto(2, "2027-06-01", null))).andExpect(status().isConflict());
	}

	// ---- papéis e concorrência ----

	@Test
	void theReceptionistIs403OnEveryInstallmentRoute() throws Exception {
		Kit kit = kit();
		String id = approvedBudget(kit);

		mvc.perform(get("/api/v1/budgets/" + id + "/installments").with(as(kit.clinic(), Role.RECEPTIONIST))).andExpect(status().isForbidden());
		mvc.perform(createPlan(kit, Role.RECEPTIONIST, id, auto(2, "2027-03-01", null))).andExpect(status().isForbidden());
		mvc.perform(redoPlan(kit, Role.RECEPTIONIST, id, auto(2, "2027-03-01", null))).andExpect(status().isForbidden());

		assertThat(installmentRows(id)).isZero();
	}

	@Test
	void twoSimultaneousPlansLeaveExactlyOnePlan() throws Exception {
		for (int round = 0; round < 5; round++) {
			Kit kit = kit();
			String id = approvedBudget(kit);
			CountDownLatch go = new CountDownLatch(1);
			ExecutorService pool = Executors.newFixedThreadPool(2);
			List<Future<Integer>> results = new ArrayList<>();
			for (int count : new int[] { 2, 3 }) {
				results.add(pool.submit(() -> {
					go.await();
					return mvc.perform(createPlan(kit, Role.DENTIST_ADMIN, id, auto(count, "2027-03-01", null))).andReturn().getResponse().getStatus();
				}));
			}
			go.countDown();
			List<Integer> statuses = List.of(results.get(0).get(), results.get(1).get());
			pool.shutdown();

			assertThat(statuses).as("rodada %d", round).containsExactlyInAnyOrder(201, 409);
			JsonNode listed = plan(kit, id);
			assertThat(sumOf(listed, true)).isEqualByComparingTo("800.00");
			assertThat(listed.get("installments").size()).isIn(2, 3);
		}
	}

	// ---- isolamento com o filtro Hibernate desligado (a RLS é a barreira) ----

	@org.springframework.beans.factory.annotation.Autowired
	jakarta.persistence.EntityManager entityManager;

	private <T> T asDentistWithoutHibernateFilter(Clinic clinic, java.util.function.Supplier<T> block) {
		crm_data.shared.tenant.TenantContext.set(clinic.id(), userOf(clinic, Role.DENTIST_ADMIN), Role.DENTIST_ADMIN);
		try {
			return new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(status -> {
				entityManager.unwrap(org.hibernate.Session.class).disableFilter(crm_data.shared.tenant.TenantAwareEntity.FILTER);
				return block.get();
			});
		}
		finally {
			crm_data.shared.tenant.TenantContext.clear();
		}
	}

	@Test
	void withTheFilterOffAClinicSeesOnlyItsOwnInstallmentsAndPayments() throws Exception {
		Kit a = kit();
		Kit b = kit();
		for (Kit kit : List.of(a, b)) {
			String id = approvedBudget(kit);
			JsonNode created = read(mvc.perform(createPlan(kit, Role.DENTIST_ADMIN, id, auto(2, "2027-03-01", null))).andExpect(status().isCreated())
					.andReturn());
			pay(kit.clinic(), created.get("installments").get(0).get("id").asString(), "100.00", "PAYMENT", null);
		}

		for (String entity : List.of("Installment", "Payment")) {
			List<? extends crm_data.shared.tenant.TenantAwareEntity> seenByB = asDentistWithoutHibernateFilter(b.clinic(),
					() -> entityManager.createQuery("select e from " + entity + " e", crm_data.shared.tenant.TenantAwareEntity.class).getResultList());
			assertThat(seenByB).as(entity).isNotEmpty().allSatisfy(row -> assertThat(row.getClinicId()).isEqualTo(b.clinic().id()));
		}
		for (String table : List.of("installments", "payments")) {
			long[] seen = asDentistWithoutHibernateFilter(b.clinic(), () -> new long[] {
					jdbc.queryForObject("SELECT count(*) FROM core." + table + " WHERE clinic_id = ?::uuid", Long.class, a.clinic().id().toString()),
					jdbc.queryForObject("SELECT count(*) FROM core." + table, Long.class) });
			long own = DatabaseContainer.admin().queryForObject("SELECT count(*) FROM core." + table + " WHERE clinic_id = ?::uuid", Long.class,
					b.clinic().id().toString());
			assertThat(seen[0]).as(table).isZero();
			assertThat(seen[1]).as(table).isEqualTo(own).isGreaterThan(0);
		}
	}

	// ---- higiene de log ----

	@Test
	void theAuditLogsCarryOnlyIdsAndCountsNeverAmountsDatesOrNames() throws Exception {
		ch.qos.logback.classic.Logger finance = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("crm_data.finance.audit");
		ch.qos.logback.classic.Logger commercial = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("crm_data.commercial.audit");
		ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = new ch.qos.logback.core.read.ListAppender<>();
		appender.start();
		finance.addAppender(appender);
		commercial.addAppender(appender);
		Kit kit = kit();
		String id;
		JsonNode created;
		try {
			id = approvedBudget(kit);
			created = read(mvc.perform(createPlan(kit, Role.DENTIST_ADMIN, id, auto(2, "2027-03-17", "123.45"))).andExpect(status().isCreated()).andReturn());
			JsonNode first = created.get("installments").get(1);
			mvc.perform(json(patch("/api/v1/installments/" + first.get("id").asString() + "/due-date"),
					"{\"dueDate\":\"2027-08-29\",\"version\":%d}".formatted(first.get("version").asLong())).with(as(kit.clinic(), Role.DENTIST_ADMIN)))
					.andExpect(status().isOk());
			mvc.perform(redoPlan(kit, Role.DENTIST_ADMIN, id, auto(3, "2027-09-13", null))).andExpect(status().isOk());
		}
		finally {
			finance.detachAppender(appender);
			commercial.detachAppender(appender);
		}

		List<String> messages = appender.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList();
		assertThat(messages).anySatisfy(m -> assertThat(m).contains("Plano de parcelas criado", id, "count=2"));
		assertThat(messages).anySatisfy(m -> assertThat(m).contains("Vencimento alterado"));
		assertThat(messages).anySatisfy(m -> assertThat(m).contains("Plano de parcelas refeito", id, "count=3"));
		assertThat(messages).anySatisfy(m -> assertThat(m).contains("Orçamento decidido (APPROVED)", id));
		assertThat(messages).allSatisfy(m -> assertThat(m).doesNotContain("123.45", "800.00", "2027-03-17", "2027-08-29", "2027-09-13", "Maria", "Souza"));
	}

	// ---- desempenho (metas do PRD: plano de 60 parcelas p95 ≤ 250 ms; listagem p95 ≤ 100 ms) ----

	@Test
	void aSixtyInstallmentPlanMeetsTheLatencyTargets() throws Exception {
		Kit kit = kit();
		List<Long> create = new ArrayList<>();
		List<Long> list = new ArrayList<>();
		for (int round = 0; round < 25; round++) {
			String id = approvedBudget(kit);
			long start = System.nanoTime();
			mvc.perform(createPlan(kit, Role.DENTIST_ADMIN, id, auto(60, "2027-03-01", null))).andExpect(status().isCreated());
			create.add((System.nanoTime() - start) / 1_000_000);
			start = System.nanoTime();
			mvc.perform(get("/api/v1/budgets/" + id + "/installments").with(as(kit.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isOk());
			list.add((System.nanoTime() - start) / 1_000_000);
		}
		// as 5 primeiras rodadas aquecem a JVM e o pool; o p95 vale para as 20 restantes
		long createP95 = p95(create.subList(5, create.size()));
		long listP95 = p95(list.subList(5, list.size()));

		assertThat(createP95).as("p95 do plano de 60 parcelas (ms)").isLessThanOrEqualTo(250);
		assertThat(listP95).as("p95 da listagem do plano (ms)").isLessThanOrEqualTo(100);
	}

	private static long p95(List<Long> samples) {
		List<Long> sorted = samples.stream().sorted().toList();
		return sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1);
	}
}
