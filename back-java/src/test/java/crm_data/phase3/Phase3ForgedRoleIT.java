package crm_data.phase3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import crm_data.shared.error.DatabaseErrors;
import crm_data.shared.security.Role;
import crm_data.support.tenant.TenantFixtures.Clinic;
import tools.jackson.databind.JsonNode;

/**
 * A RLS do comercial e do financeiro provada com SQL direto (nenhum filtro Hibernate no caminho), com papel real,
 * forjado, ausente e inválido. O papel do banco só vale se o backend o informar; estes testes mostram o que o banco faz
 * com cada valor: parcela e pagamento são só do dentista; a recepção lê o catálogo, os orçamentos e os PDFs mas não grava
 * nada; papel ausente ou inválido não enxerga nada; escrita sem usuário é recusada.
 */
class Phase3ForgedRoleIT extends Phase3ItSupport {

	private record World(Clinic clinic, String patient, UUID dentist, UUID receptionist, String budget, String installment, String procedure) {
	}

	private static final List<String> CLINIC_TABLES = List.of("procedure_types", "budgets", "budget_items", "budget_documents", "installments", "payments");

	private World world() throws Exception {
		Kit kit = kit();
		JsonNode draft = createBudget(kit, null, item(kit.restoration(), 2, null));
		String id = draft.get("id").asString();
		JsonNode sent = read(mvc.perform(json(post("/api/v1/budgets/" + id + "/send"), "{\"version\":%d}".formatted(draft.get("version").asLong()))
				.with(as(kit.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isOk()).andReturn());
		mvc.perform(json(post("/api/v1/budgets/" + id + "/approve"), "{\"version\":%d}".formatted(sent.get("version").asLong()))
				.with(as(kit.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isOk());
		String installment = planOf(kit, id, 1).get("installments").get(0).get("id").asString();
		mvc.perform(json(post("/api/v1/installments/" + installment + "/payments"), "{\"amount\":\"100.00\",\"method\":\"PIX\"}")
				.with(as(kit.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isCreated());
		return new World(kit.clinic(), kit.patient(), userOf(kit.clinic(), Role.DENTIST_ADMIN), userOf(kit.clinic(), Role.RECEPTIONIST), id, installment,
				kit.restoration());
	}

	private static String sqlState(Throwable error) {
		return DatabaseErrors.sqlState(error).orElse("?");
	}

	private long count(World w, UUID user, Role role, String table) {
		return asSession(w.clinic(), user, role, () -> jdbc.queryForObject("SELECT count(*) FROM core." + table, Long.class));
	}

	private int update(World w, UUID user, Role role, String sql, Object... args) {
		return asSession(w.clinic(), user, role, () -> jdbc.update(sql, args));
	}

	// ---- leitura ----

	@Test
	void theDentistReadsEverythingAndTheReceptionistReadsOnlyCatalogBudgetsAndPdfs() throws Exception {
		World w = world();

		for (String table : CLINIC_TABLES) {
			assertThat(count(w, w.dentist(), Role.DENTIST_ADMIN, table)).as("dentista lê %s", table).isGreaterThan(0);
		}
		for (String table : List.of("procedure_types", "budgets", "budget_items", "budget_documents")) {
			assertThat(count(w, w.receptionist(), Role.RECEPTIONIST, table)).as("recepção lê %s", table).isGreaterThan(0);
		}
		for (String table : List.of("installments", "payments")) {
			assertThat(count(w, w.receptionist(), Role.RECEPTIONIST, table)).as("recepção NÃO lê %s", table).isZero();
		}
	}

	@Test
	void anAbsentOrInvalidRoleSeesNothingOfTheClinic() throws Exception {
		World w = world();

		for (String table : CLINIC_TABLES) {
			assertThat(count(w, w.dentist(), null, table)).as("papel ausente em %s", table).isZero();
			long invalid = asSession(w.clinic(), w.dentist(), Role.DENTIST_ADMIN, () -> {
				jdbc.queryForObject("SELECT set_config('app.role', 'ADMIN', true)", String.class);
				return jdbc.queryForObject("SELECT count(*) FROM core." + table, Long.class);
			});
			assertThat(invalid).as("papel inválido em %s", table).isZero();
		}
	}

	// ---- recepção forjada: escrita ----

	@Test
	void aForgedReceptionistCannotWriteAnythingCommercialOrFinancial() throws Exception {
		World w = world();

		assertThatThrownBy(() -> update(w, w.receptionist(), Role.RECEPTIONIST,
				"INSERT INTO core.procedure_types (id, clinic_id, name, base_price, default_duration_minutes, color, created_by) VALUES (?, ?, 'Forjado', 10.00, 30, '#3B82F6', ?)",
				UUID.randomUUID(), w.clinic().id(), w.receptionist())).satisfies(e -> assertThat(sqlState(e)).isEqualTo("42501"));
		assertThatThrownBy(() -> update(w, w.receptionist(), Role.RECEPTIONIST,
				"INSERT INTO core.budgets (id, clinic_id, number, patient_id, status, created_by) VALUES (?, ?, 999, ?, 'DRAFT', ?)",
				UUID.randomUUID(), w.clinic().id(), UUID.fromString(w.patient()), w.receptionist())).satisfies(e -> assertThat(sqlState(e)).isEqualTo("42501"));
		assertThatThrownBy(() -> update(w, w.receptionist(), Role.RECEPTIONIST,
				"INSERT INTO core.payments (id, clinic_id, installment_id, kind, amount, method, paid_on, created_by) VALUES (?, ?, ?::uuid, 'PAYMENT', 1.00, 'PIX', current_date, ?)",
				UUID.randomUUID(), w.clinic().id(), w.installment(), w.receptionist())).satisfies(e -> assertThat(sqlState(e)).isEqualTo("42501"));
		assertThatThrownBy(() -> update(w, w.receptionist(), Role.RECEPTIONIST,
				"INSERT INTO core.installments (id, clinic_id, budget_id, patient_id, number, amount, due_date, status, created_by) VALUES (?, ?, ?::uuid, ?, 9, 1.00, current_date, 'OPEN', ?)",
				UUID.randomUUID(), w.clinic().id(), w.budget(), UUID.fromString(w.patient()), w.receptionist())).satisfies(e -> assertThat(sqlState(e)).isEqualTo("42501"));

		assertThat(update(w, w.receptionist(), Role.RECEPTIONIST, "UPDATE core.budgets SET notes = 'forjado' WHERE id = ?::uuid", w.budget())).isZero();
		assertThat(update(w, w.receptionist(), Role.RECEPTIONIST, "UPDATE core.procedure_types SET name = 'Forjado' WHERE clinic_id = ?", w.clinic().id())).isZero();
		assertThat(update(w, w.receptionist(), Role.RECEPTIONIST, "UPDATE core.installments SET due_date = DATE '2030-01-01' WHERE id = ?::uuid", w.installment())).isZero();
		assertThat(update(w, w.receptionist(), Role.RECEPTIONIST, "UPDATE core.budget_documents SET deleted_at = now() WHERE budget_id = ?::uuid", w.budget())).isZero();

		assertThat(count(w, w.dentist(), Role.DENTIST_ADMIN, "payments")).isEqualTo(1);
		assertThat(count(w, w.dentist(), Role.DENTIST_ADMIN, "installments")).isEqualTo(1);
	}

	@Test
	void nobodyUpdatesOrDeletesPaymentsAndNobodyDeletesTheCommercialRows() throws Exception {
		World w = world();

		for (Role role : Role.values()) {
			UUID user = role == Role.DENTIST_ADMIN ? w.dentist() : w.receptionist();
			assertThatThrownBy(() -> update(w, user, role, "UPDATE core.payments SET amount = 1.00 WHERE clinic_id = ?", w.clinic().id()))
					.as("%s altera pagamento", role).satisfies(e -> assertThat(sqlState(e)).isEqualTo("42501"));
			assertThatThrownBy(() -> update(w, user, role, "DELETE FROM core.payments WHERE clinic_id = ?", w.clinic().id()))
					.as("%s apaga pagamento", role).satisfies(e -> assertThat(sqlState(e)).isEqualTo("42501"));
			for (String table : List.of("budgets", "budget_items", "installments", "budget_documents")) {
				assertDeleteDenied(w, user, role, table);
			}
		}
		assertThat(count(w, w.dentist(), Role.DENTIST_ADMIN, "payments")).isEqualTo(1);
		for (String table : List.of("budgets", "budget_items", "installments", "budget_documents")) {
			assertThat(count(w, w.dentist(), Role.DENTIST_ADMIN, table)).as("%s intacta", table).isGreaterThan(0);
		}
	}

	// ---- sem papel ou sem usuário ----

	@Test
	void withoutARoleTheWritesAreRefused() throws Exception {
		World w = world();

		assertThatThrownBy(() -> update(w, w.dentist(), null,
				"INSERT INTO core.procedure_types (id, clinic_id, name, base_price, default_duration_minutes, color, created_by) VALUES (?, ?, 'Sem papel', 10.00, 30, '#3B82F6', ?)",
				UUID.randomUUID(), w.clinic().id(), w.dentist())).satisfies(e -> assertThat(sqlState(e)).isEqualTo("42501"));
		assertThat(update(w, w.dentist(), null, "UPDATE core.budgets SET notes = 'sem papel' WHERE id = ?::uuid", w.budget())).isZero();
		assertThat(count(w, w.dentist(), Role.DENTIST_ADMIN, "procedure_types")).isEqualTo(2);
	}

	@Test
	void withoutAUserTheWriteIsRefusedAndNothingChanges() throws Exception {
		World w = world();

		assertThatThrownBy(() -> update(w, null, Role.DENTIST_ADMIN, "UPDATE core.installments SET due_date = DATE '2030-01-01' WHERE id = ?::uuid", w.installment()))
				.satisfies(e -> assertThat(sqlState(e)).isIn("28000", "42501"));
		assertThatThrownBy(() -> update(w, null, Role.DENTIST_ADMIN,
				"INSERT INTO core.payments (id, clinic_id, installment_id, kind, amount, method, paid_on, created_by) VALUES (?, ?, ?::uuid, 'PAYMENT', 1.00, 'PIX', current_date, ?)",
				UUID.randomUUID(), w.clinic().id(), w.installment(), w.dentist())).satisfies(e -> assertThat(sqlState(e)).isIn("28000", "42501"));

		assertThat(count(w, w.dentist(), Role.DENTIST_ADMIN, "payments")).isEqualTo(1);
	}

	/**
	 * DELETE negado: ou o banco recusa (sem GRANT: 42501; trigger de congelamento do orçamento enviado: CR007), ou a RLS
	 * esconde a linha e nada é apagado (0 linhas). O que nunca pode acontecer é apagar.
	 */
	private void assertDeleteDenied(World w, UUID user, Role role, String table) {
		try {
			int deleted = update(w, user, role, "DELETE FROM core." + table + " WHERE clinic_id = ?", w.clinic().id());
			assertThat(deleted).as("%s apaga %s: a RLS esconde a linha", role, table).isZero();
		}
		catch (RuntimeException ex) {
			assertThat(sqlState(ex)).as("%s apaga %s", role, table).isIn("42501", "CR007");
		}
	}
}
