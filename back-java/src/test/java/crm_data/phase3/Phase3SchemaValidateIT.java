package crm_data.phase3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

import crm_data.shared.error.DatabaseErrors;
import crm_data.support.DatabaseContainer;

/**
 * O contexto sobe com {@code ddl-auto=validate} contra o schema REAL (bootstrap + V001–V029): se qualquer coluna, tipo ou
 * tabela das entidades divergir do banco, a subida falha e TODOS os ITs quebram. Este teste torna a garantia explícita
 * (fecha o teste 120 do banco) e confere que os objetos da Fase 3 existem com a segurança esperada. As entidades de cada
 * feature (F02 a F06) entram no contexto à medida que são entregues.
 */
class Phase3SchemaValidateIT extends Phase3ItSupport {

	private static final List<String> PHASE3_TABLES = List.of("procedure_types", "budgets", "budget_items", "budget_documents",
			"installments", "payments");

	@Autowired
	Environment environment;

	@Test
	void hibernateValidatesTheMappingAgainstV001ToV030() {
		assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
	}

	@Test
	void allThirtyMigrationsExistAndTheCommercialObjectsAreInPlace() throws IOException {
		Path migrations = Path.of("..", "database", "migrations");
		try (Stream<Path> files = Files.list(migrations)) {
			assertThat(files.filter(f -> f.getFileName().toString().matches("V0\\d\\d__.*\\.sql")).count()).isGreaterThanOrEqualTo(30);
		}
		var admin = DatabaseContainer.admin();
		assertThat(admin.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema = 'core' AND table_type = 'BASE TABLE'", String.class))
				.containsAll(PHASE3_TABLES);
		assertThat(admin.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema = 'core' AND table_name = 'appointments'", String.class))
				.contains("procedure_type_id");
		assertThat(admin.queryForList("SELECT conname FROM pg_constraint WHERE conrelid = 'core.appointments'::regclass", String.class))
				.contains("fk_appt_procedure");
		assertThat(admin.queryForList("SELECT indexname FROM pg_indexes WHERE schemaname = 'core'", String.class))
				.contains("ux_ptype_clinic_name", "ux_budgets_clinic_number", "ux_items_budget_position", "ux_inst_budget_number",
						"ux_pay_reverses", "ix_inst_open_due", "ix_pay_clinic_paid", "ix_budgets_decided");
	}

	@Test
	void everyNewTableHasRowLevelSecurityEnabledAndForced() {
		List<String> withoutRls = DatabaseContainer.admin().queryForList("""
				SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
				WHERE n.nspname = 'core' AND c.relname IN ('procedure_types','budgets','budget_items','budget_documents','installments','payments')
				  AND NOT (c.relrowsecurity AND c.relforcerowsecurity)""", String.class);

		assertThat(withoutRls).as("tabelas da Fase 3 sem RLS ativa e forçada").isEmpty();
	}

	@Test
	void theApplicationRoleHasNoDeleteAndNoUpdateOnTheFinancialHistory() {
		var admin = DatabaseContainer.admin();
		for (String table : List.of("procedure_types", "budgets", "budget_documents", "installments", "payments")) {
			assertThat(admin.queryForObject("SELECT has_table_privilege('crm_app', 'core." + table + "', 'DELETE')", Boolean.class))
					.as("crm_app sem DELETE em %s", table).isFalse();
		}
		assertThat(admin.queryForObject("SELECT has_table_privilege('crm_app', 'core.payments', 'UPDATE')", Boolean.class))
				.as("pagamento é só de inserção").isFalse();
		assertThat(admin.queryForObject("SELECT has_table_privilege('crm_app', 'core.budget_items', 'DELETE')", Boolean.class))
				.as("único DELETE permitido da fase: item de orçamento em rascunho").isTrue();
	}

	@Test
	void theApplicationRoleCannotChangeTheCommercialSchema() {
		assertThatThrownBy(() -> jdbc.execute("ALTER TABLE core.budgets ADD COLUMN intruso int"))
				.satisfies(error -> assertThat(DatabaseErrors.sqlState(error)).contains("42501"));
		assertThatThrownBy(() -> jdbc.execute("DROP TABLE core.payments"))
				.satisfies(error -> assertThat(DatabaseErrors.sqlState(error)).contains("42501"));
		assertThatThrownBy(() -> jdbc.execute("TRUNCATE core.installments"))
				.satisfies(error -> assertThat(DatabaseErrors.sqlState(error)).contains("42501"));
	}
}
