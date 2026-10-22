package crm_data.phase1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

import crm_data.shared.error.DatabaseErrors;
import crm_data.support.DatabaseContainer;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.EntityType;

/**
 * O contexto sobe com {@code ddl-auto=validate} contra o schema REAL (bootstrap + V001–V013): se qualquer coluna,
 * tipo ou tabela das entidades divergir do banco, a subida do Spring falha e TODOS os ITs quebram. Este teste torna a
 * garantia explícita (fecha o teste 57 do banco) e confere que os objetos esperados das migrations existem.
 */
class Phase1SchemaValidateIT extends Phase1ItSupport {

	@Autowired
	Environment environment;
	@Autowired
	EntityManagerFactory entityManagerFactory;

	@Test
	void hibernateValidatesTheMappingAgainstTheRealSchema() {
		assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
	}

	@Test
	void theFiveEntitiesOfThePhaseAreMapped() {
		Set<String> mapped = entityManagerFactory.getMetamodel().getEntities().stream().map(EntityType::getName).collect(Collectors.toSet());

		assertThat(mapped).contains("Patient", "MedicalHistory", "ClinicalNote", "PatientFile", "AuditLog", "User", "Clinic", "RefreshToken");
	}

	@Test
	void theMigrationsV001ToV013LeftTheExpectedObjects() {
		var admin = DatabaseContainer.admin();

		assertThat(admin.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema = 'core' AND table_type = 'BASE TABLE'", String.class))
				.contains("clinics", "users", "refresh_tokens", "patients", "medical_histories", "clinical_notes", "patient_files", "audit_log");
		assertThat(admin.queryForList("""
				SELECT p.proname FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'core'""", String.class))
				.contains("current_clinic_id", "current_app_role", "current_user_id", "anonymize_patient", "write_audit", "set_updated_at");
		assertThat(admin.queryForList("SELECT extname FROM pg_extension", String.class)).contains("pg_trgm");
		assertThat(admin.queryForList("SELECT rolname FROM pg_roles", String.class)).contains("crm_app", "crm_owner", "crm_definer", "crm_backup");
		List<String> rlsOff = admin.queryForList("""
				SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
				WHERE n.nspname = 'core' AND c.relname IN ('patients','medical_histories','clinical_notes','patient_files','audit_log')
				  AND NOT (c.relrowsecurity AND c.relforcerowsecurity)""", String.class);
		assertThat(rlsOff).as("tabelas da fase sem RLS ativa e forçada").isEmpty();
	}

	@Test
	void theApplicationRoleCannotChangeTheSchema() {
		assertThatThrownBy(() -> jdbc.execute("CREATE TABLE core.intruso (id int)"))
				.satisfies(error -> assertThat(DatabaseErrors.sqlState(error)).contains("42501"));
		assertThatThrownBy(() -> jdbc.execute("ALTER TABLE core.patients ADD COLUMN intruso int"))
				.satisfies(error -> assertThat(DatabaseErrors.sqlState(error)).contains("42501"));
	}
}
