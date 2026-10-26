package crm_data.phase3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import org.hibernate.Session;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import crm_data.shared.security.Role;
import crm_data.shared.tenant.TenantAwareEntity;
import crm_data.shared.tenant.TenantContext;
import crm_data.support.DatabaseContainer;
import crm_data.support.tenant.TenantFixtures.Clinic;
import jakarta.persistence.EntityManager;
import tools.jackson.databind.JsonNode;

/**
 * Isolamento entre clínicas nas seis tabelas da Fase 3 com o filtro Hibernate DESLIGADO (quem protege é a RLS), em JPQL,
 * em SQL nativo e pela API (relatórios e rotas por id). As duas clínicas têm dados em todas as tabelas, para que "0 linhas
 * da outra" não seja resultado de tabela vazia.
 */
class Phase3TenantIsolationIT extends Phase3ItSupport {

	private static final List<String> TABLES = List.of("procedure_types", "budgets", "budget_items", "budget_documents", "installments", "payments");
	private static final List<String> ENTITIES = List.of("ProcedureType", "Budget", "BudgetItem", "BudgetDocument", "Installment", "Payment");

	@Autowired
	EntityManager entityManager;

	/** Clínica com procedimento, orçamento enviado (PDF), aprovado, com 2 parcelas e 1 pagamento. */
	private record World(Kit kit, String budget, String installment, String payment) {

		Clinic clinic() {
			return kit.clinic();
		}
	}

	private World world() throws Exception {
		Kit kit = kit();
		JsonNode draft = createBudget(kit, "600.00", item(kit.restoration(), 2, null), item(kit.root(), 1, null));
		String id = draft.get("id").asString();
		JsonNode sent = read(mvc.perform(json(post("/api/v1/budgets/" + id + "/send"), "{\"version\":%d}".formatted(draft.get("version").asLong()))
				.with(as(kit.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isOk()).andReturn());
		mvc.perform(json(post("/api/v1/budgets/" + id + "/approve"), "{\"version\":%d}".formatted(sent.get("version").asLong()))
				.with(as(kit.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isOk());
		JsonNode plan = planOf(kit, id, 2);
		String installment = plan.get("installments").get(0).get("id").asString();
		String payment = read(mvc.perform(json(post("/api/v1/installments/" + installment + "/payments"), "{\"amount\":\"100.00\",\"method\":\"PIX\"}")
				.with(as(kit.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isCreated()).andReturn()).get("payment").get("id").asString();
		return new World(kit, id, installment, payment);
	}

	private <T> T withoutHibernateFilter(Clinic clinic, Supplier<T> block) {
		TenantContext.set(clinic.id(), userOf(clinic, Role.DENTIST_ADMIN), Role.DENTIST_ADMIN);
		try {
			return new TransactionTemplate(transactionManager).execute(status -> {
				entityManager.unwrap(Session.class).disableFilter(TenantAwareEntity.FILTER);
				return block.get();
			});
		}
		finally {
			TenantContext.clear();
		}
	}

	@Test
	void bothClinicsHaveRowsInEverySixTableSoTheProofIsNotVacuous() throws Exception {
		World a = world();
		World b = world();

		for (String table : TABLES) {
			for (World w : List.of(a, b)) {
				assertThat(DatabaseContainer.admin().queryForObject("SELECT count(*) FROM core." + table + " WHERE clinic_id = ?::uuid", Long.class,
						w.clinic().id().toString())).as(table).isGreaterThan(0);
			}
		}
	}

	@Test
	void jpqlWithTheFilterOffSeesOnlyOwnRowsInAllSixTables() throws Exception {
		World a = world();
		World b = world();

		for (String entity : ENTITIES) {
			List<? extends TenantAwareEntity> seenByB = withoutHibernateFilter(b.clinic(),
					() -> entityManager.createQuery("select e from " + entity + " e", TenantAwareEntity.class).getResultList());
			assertThat(seenByB).as(entity).isNotEmpty().allSatisfy(row -> assertThat(row.getClinicId()).isEqualTo(b.clinic().id()))
					.noneSatisfy(row -> assertThat(row.getClinicId()).isEqualTo(a.clinic().id()));
		}
	}

	@Test
	void nativeSqlCrossReadsAreEmptyAndTheTotalIsOnlyTheOwnRows() throws Exception {
		World a = world();
		World b = world();

		for (String table : TABLES) {
			long own = DatabaseContainer.admin().queryForObject("SELECT count(*) FROM core." + table + " WHERE clinic_id = ?::uuid", Long.class,
					b.clinic().id().toString());
			long[] seen = withoutHibernateFilter(b.clinic(), () -> new long[] {
					jdbc.queryForObject("SELECT count(*) FROM core." + table + " WHERE clinic_id = ?::uuid", Long.class, a.clinic().id().toString()),
					jdbc.queryForObject("SELECT count(*) FROM core." + table, Long.class) });
			assertThat(seen[0]).as(table + ": linhas da clínica A vistas pela B").isZero();
			assertThat(seen[1]).as(table + ": total visto pela B = só as suas").isEqualTo(own);
		}
	}

	@Test
	void nativeSqlCannotWriteIntoAnotherClinic() throws Exception {
		World a = world();
		World b = world();

		long updated = withoutHibernateFilter(b.clinic(), () -> (long) jdbc.update("UPDATE core.budgets SET notes = 'invasor' WHERE id = ?::uuid", a.budget()))
				+ withoutHibernateFilter(b.clinic(), () -> (long) jdbc.update("UPDATE core.installments SET due_date = DATE '2030-01-01' WHERE id = ?::uuid", a.installment()))
				+ withoutHibernateFilter(b.clinic(), () -> (long) jdbc.update("UPDATE core.procedure_types SET name = 'invasor' WHERE clinic_id = ?::uuid", a.clinic().id().toString()));

		assertThat(updated).isZero();
		assertThat(DatabaseContainer.admin().queryForObject("SELECT notes FROM core.budgets WHERE id = ?::uuid", String.class, a.budget())).isNull();
		assertThat(DatabaseContainer.admin().queryForObject("SELECT due_date::text FROM core.installments WHERE id = ?::uuid", String.class, a.installment()))
				.isNotEqualTo("2030-01-01");
	}

	@Test
	void everyRouteThatTakesAnIdAnswers404ForAnotherClinicsIds() throws Exception {
		World a = world();
		World b = world();
		var dentistOfB = as(b.clinic(), Role.DENTIST_ADMIN);
		String version = "{\"version\":0}";

		mvc.perform(get("/api/v1/budgets/" + a.budget()).with(dentistOfB)).andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/budgets/" + a.budget() + "/documents").with(dentistOfB)).andExpect(status().isNotFound());
		mvc.perform(json(put("/api/v1/budgets/" + a.budget()), "{\"version\":0,\"items\":[{\"procedureTypeId\":\"" + UUID.randomUUID() + "\",\"quantity\":1}]}").with(dentistOfB))
				.andExpect(status().isNotFound());
		mvc.perform(json(post("/api/v1/budgets/" + a.budget() + "/send"), version).with(dentistOfB)).andExpect(status().isNotFound());
		mvc.perform(json(post("/api/v1/budgets/" + a.budget() + "/discard"), version).with(dentistOfB)).andExpect(status().isNotFound());
		mvc.perform(json(post("/api/v1/budgets/" + a.budget() + "/approve"), version).with(dentistOfB)).andExpect(status().isNotFound());
		mvc.perform(json(post("/api/v1/budgets/" + a.budget() + "/reject"), version).with(dentistOfB)).andExpect(status().isNotFound());
		mvc.perform(post("/api/v1/budgets/" + a.budget() + "/documents").with(dentistOfB)).andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/budgets/" + a.budget() + "/installments").with(dentistOfB)).andExpect(status().isNotFound());
		mvc.perform(json(post("/api/v1/budgets/" + a.budget() + "/installments"), "{\"mode\":\"AUTO\",\"count\":2,\"firstDueDate\":\"2027-03-01\"}").with(dentistOfB))
				.andExpect(status().isNotFound());
		mvc.perform(json(patch("/api/v1/installments/" + a.installment() + "/due-date"), "{\"dueDate\":\"2027-05-01\",\"version\":1}").with(dentistOfB))
				.andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/installments/" + a.installment() + "/payments").with(dentistOfB)).andExpect(status().isNotFound());
		mvc.perform(json(post("/api/v1/installments/" + a.installment() + "/payments"), "{\"amount\":\"1.00\",\"method\":\"PIX\"}").with(dentistOfB))
				.andExpect(status().isNotFound());
		mvc.perform(post("/api/v1/payments/" + a.payment() + "/reversal").with(dentistOfB)).andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/patients/" + a.kit().patient() + "/financial").with(dentistOfB)).andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/patients/" + a.kit().patient() + "/budgets").with(dentistOfB)).andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/procedure-types/" + a.kit().restoration()).with(dentistOfB)).andExpect(status().isNotFound());
		mvc.perform(json(put("/api/v1/procedure-types/" + a.kit().restoration()), procedureBody("Invasor", "1.00", 30, "#3B82F6").replace("}", ",\"version\":0}")).with(dentistOfB))
				.andExpect(status().isNotFound());
	}

	@Test
	void theReportsAndListsOfOneClinicNeverIncludeTheOthersData() throws Exception {
		World a = world();
		World b = world();
		var dentistOfB = as(b.clinic(), Role.DENTIST_ADMIN);

		// as parcelas das duas vencem em 2027-03-01 e 2027-04-01; a janela cobre as duas, e só as da própria clínica voltam
		JsonNode receivables = read(mvc.perform(get("/api/v1/finance/receivables?from=2027-01-01&to=2027-12-31&size=100").with(dentistOfB))
				.andExpect(status().isOk()).andReturn());
		assertThat(receivables.get("content")).hasSize(2);
		receivables.get("content").forEach(row -> assertThat(row.get("installmentId").asString()).isNotEqualTo(a.installment()));
		assertThat(receivables.get("summary").get("count").asInt()).isEqualTo(2);

		JsonNode overdue = read(mvc.perform(get("/api/v1/finance/overdue?size=100").with(dentistOfB)).andExpect(status().isOk()).andReturn());
		assertThat(overdue.toString()).doesNotContain(a.installment());

		java.time.LocalDate today = java.time.LocalDate.now();
		JsonNode cash = read(mvc.perform(get("/api/v1/finance/cash-flow?from=" + today.minusDays(3) + "&to=" + today.plusDays(3)).with(dentistOfB))
				.andExpect(status().isOk()).andReturn());
		assertThat(cash.get("netTotal").asString()).as("só o pagamento de R$ 100,00 da própria clínica").isEqualTo("100.00");

		JsonNode revenue = read(mvc.perform(get("/api/v1/finance/revenue?from=" + today.minusDays(3) + "&to=" + today.plusDays(3)).with(dentistOfB))
				.andExpect(status().isOk()).andReturn());
		assertThat(revenue.get("count").asInt()).isEqualTo(1);
		assertThat(revenue.get("budgets").get(0).get("id").asString()).isEqualTo(b.budget());

		JsonNode budgets = read(mvc.perform(get("/api/v1/budgets?size=50").with(dentistOfB)).andExpect(status().isOk()).andReturn());
		budgets.get("content").forEach(row -> assertThat(row.get("id").asString()).isNotEqualTo(a.budget()));
		JsonNode procedures = read(mvc.perform(get("/api/v1/procedure-types?size=50").with(dentistOfB)).andExpect(status().isOk()).andReturn());
		assertThat(procedures.toString()).doesNotContain(a.kit().restoration()).doesNotContain(a.kit().root());
	}
}
