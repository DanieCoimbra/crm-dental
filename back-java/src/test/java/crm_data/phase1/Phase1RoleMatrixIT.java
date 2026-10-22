package crm_data.phase1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import crm_data.phase1.Phase1Routes.Ctx;
import crm_data.phase1.Phase1Routes.Route;
import crm_data.shared.security.Role;
import crm_data.support.DatabaseContainer;
import crm_data.support.SqlRecordingDataSourceConfig;
import crm_data.support.tenant.TenantFixtures.Clinic;

/**
 * A matriz de papéis contra o contexto completo e o PostgreSQL de verdade: aqui o 403 da recepção é provado por
 * "nenhuma instrução JDBC executada", e os 2xx são de dados reais (versão otimista, RLS, triggers de auditoria).
 */
class Phase1RoleMatrixIT extends Phase1ItSupport {

	/** Dados de partida de uma clínica: um paciente com nota e exame, e outro inativo e sem prontuário (anonimizável). */
	private record Fixture(Clinic clinic, UUID patient, UUID note, UUID file, UUID anonymizable) {

		Ctx ctx() {
			var admin = DatabaseContainer.admin();
			long patientVersion = admin.queryForObject("SELECT version FROM core.patients WHERE id = ?", Long.class, patient);
			long noteVersion = admin.queryForObject("SELECT version FROM core.clinical_notes WHERE id = ?", Long.class, note);
			return new Ctx(patient, note, file, anonymizable, patientVersion, noteVersion);
		}
	}

	private Fixture fixture() throws Exception {
		Clinic clinic = newClinic();
		String patient = createPatient(clinic, "Maria Souza", null);
		String note = addNote(clinic, patient, "Restauração do dente 36");
		String file = addFile(clinic, patient, "raio-x.png");
		String anonymizable = createPatient(clinic, "Pedro Reis", null);
		deactivate(clinic, anonymizable);
		return new Fixture(clinic, UUID.fromString(patient), UUID.fromString(note), UUID.fromString(file), UUID.fromString(anonymizable));
	}

	@Test
	void withoutTokenEverythingIs401AndTheDatabaseIsNeverReached() throws Exception {
		Fixture f = fixture();
		SqlRecordingDataSourceConfig.reset();

		for (Route route : Route.values()) {
			mvc.perform(route.request(f.ctx(), Phase1Routes.NO_TOKEN))
					.andExpect(status().isUnauthorized())
					.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
					.andExpect(jsonPath("$.type").value("https://crm.local/problems/unauthorized"));
		}

		assertThat(SqlRecordingDataSourceConfig.prepared()).as("nenhuma instrução SQL nas rotas sem token").isEmpty();
	}

	@Test
	void theReceptionistIs2xxOnItsFiveRoutesAnd403WithZeroStatementsOnTheOtherEleven() throws Exception {
		Fixture f = fixture();
		var token = as(f.clinic(), Role.RECEPTIONIST);

		for (Route route : Route.values()) {
			SqlRecordingDataSourceConfig.reset();
			var result = mvc.perform(route.request(f.ctx(), token));
			if (route.receptionistAllowed) {
				result.andExpect(status().is2xxSuccessful());
			}
			else {
				result.andExpect(status().isForbidden())
						.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
						.andExpect(jsonPath("$.type").value("https://crm.local/problems/forbidden"));
				assertThat(SqlRecordingDataSourceConfig.prepared()).as("403 sem executar nenhuma instrução: " + route).isEmpty();
			}
		}
	}

	@Test
	void theDentistWalksAllSixteenRoutesWith2xx() throws Exception {
		Fixture f = fixture();
		var token = as(f.clinic(), Role.DENTIST_ADMIN);

		for (Route route : Route.values()) {
			mvc.perform(route.request(f.ctx(), token)).andExpect(status().is2xxSuccessful());
		}

		var admin = DatabaseContainer.admin();
		assertThat(admin.queryForObject("SELECT anonymized_at IS NOT NULL FROM core.patients WHERE id = ?", Boolean.class, f.anonymizable()))
				.as("a última rota anonimizou o paciente inativo e sem prontuário").isTrue();
		assertThat(admin.queryForObject("SELECT deleted_at IS NOT NULL FROM core.patient_files WHERE id = ?", Boolean.class, f.file()))
				.as("DELETE de exame é lógico").isTrue();
		assertThat(admin.queryForObject("SELECT active FROM core.patients WHERE id = ?", Boolean.class, f.patient())).isFalse();
	}
}
