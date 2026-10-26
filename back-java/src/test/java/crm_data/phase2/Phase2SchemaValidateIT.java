package crm_data.phase2;

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
 * O contexto sobe com {@code ddl-auto=validate} contra o schema REAL (bootstrap + V001–V021): se qualquer coluna, tipo ou
 * tabela das entidades da agenda divergir do banco, a subida falha e TODOS os ITs quebram. Este teste torna a garantia
 * explícita (fecha o teste 84 do banco) e confere que os objetos da Fase 2 existem com a segurança esperada.
 */
class Phase2SchemaValidateIT extends Phase2ItSupport {

	@Autowired
	Environment environment;

	@Test
	void hibernateValidatesTheMappingAgainstV001ToV021() {
		assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
	}

	@Test
	void allTwentyOneMigrationsExistAndTheAgendaObjectsAreInPlace() throws IOException {
		Path migrations = Path.of("..", "database", "migrations");
		try (Stream<Path> files = Files.list(migrations)) {
			assertThat(files.filter(f -> f.getFileName().toString().matches("V0\\d\\d__.*\\.sql")).count()).isGreaterThanOrEqualTo(21); // o contêiner aplica todas (V001–V029 desde a Fase 3)
		}
		var admin = DatabaseContainer.admin();
		assertThat(admin.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema = 'core' AND table_type = 'BASE TABLE'", String.class))
				.contains("appointments", "business_hours", "clinic_holidays", "holidays");
		assertThat(admin.queryForList("SELECT extname FROM pg_extension", String.class)).contains("btree_gist");
		assertThat(admin.queryForList("SELECT conname FROM pg_constraint WHERE conrelid = 'core.appointments'::regclass", String.class))
				.contains("ex_appt_no_overlap");
		assertThat(admin.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema = 'core' AND table_name = 'clinics'", String.class))
				.contains("timezone");
		assertThat(admin.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema = 'core' AND table_name = 'clinical_notes'", String.class))
				.contains("appointment_id");
		assertThat(admin.queryForObject("SELECT count(*) FROM core.holidays", Long.class)).isEqualTo(50);
	}

	@Test
	void everyNewTableHasRowLevelSecurityEnabledAndForced() {
		List<String> withoutRls = DatabaseContainer.admin().queryForList("""
				SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
				WHERE n.nspname = 'core' AND c.relname IN ('appointments','business_hours','clinic_holidays','holidays')
				  AND NOT (c.relrowsecurity AND c.relforcerowsecurity)""", String.class);

		assertThat(withoutRls).as("tabelas da agenda sem RLS ativa e forçada").isEmpty();
	}

	@Test
	void theApplicationRoleCannotChangeTheAgendaSchemaNorTheSeed() {
		assertThatThrownBy(() -> jdbc.execute("ALTER TABLE core.appointments ADD COLUMN intruso int"))
				.satisfies(error -> assertThat(DatabaseErrors.sqlState(error)).contains("42501"));
		assertThatThrownBy(() -> jdbc.execute("DROP TABLE core.business_hours"))
				.satisfies(error -> assertThat(DatabaseErrors.sqlState(error)).contains("42501"));
		assertThatThrownBy(() -> jdbc.execute("TRUNCATE core.holidays"))
				.satisfies(error -> assertThat(DatabaseErrors.sqlState(error)).contains("42501"));
	}
}
