package crm_data.phase4;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

import crm_data.support.DatabaseContainer;

/**
 * O contexto sobe com {@code ddl-auto=validate} contra o schema REAL (V001-V035): se uma coluna, tipo ou tabela das
 * entidades da Fase 4 divergir do banco, a subida falha e todos os ITs quebram. Fecha o teste 143 do banco.
 */
class Phase4SchemaValidateIT extends Phase4ItSupport {

	@Autowired
	Environment environment;

	@Test
	void hibernateValidatesTheMappingAgainstV001ToV035() {
		assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
	}

	@Test
	void croColumnsExistOnUsers() {
		assertThat(DatabaseContainer.admin().queryForList(
				"SELECT column_name FROM information_schema.columns WHERE table_schema = 'core' AND table_name = 'users'", String.class))
						.contains("cro_number", "cro_state");
	}

	@Test
	void auditConstraintsAcceptTheNewActionsAndTable() {
		List<String> definitions = DatabaseContainer.admin()
				.queryForList("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname IN ('ck_audit_action','ck_audit_table')", String.class);
		assertThat(String.join(" ", definitions)).contains("VOID", "DOWNLOAD", "clinical_documents");
	}
}
