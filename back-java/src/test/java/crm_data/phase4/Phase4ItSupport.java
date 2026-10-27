package crm_data.phase4;

import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import crm_data.phase3.Phase3ItSupport;
import crm_data.shared.security.Role;
import crm_data.support.DatabaseContainer;
import crm_data.support.tenant.TenantFixtures.Clinic;
import tools.jackson.databind.JsonNode;

/**
 * Base dos ITs da Fase 4 (CRO, documentos clínicos e dashboard). Mesma configuração das fases anteriores: o Spring
 * reaproveita o contexto e o contêiner PostgreSQL (V001-V035). Cada teste cria suas clínicas.
 */
public abstract class Phase4ItSupport extends Phase3ItSupport {

	/**
	 * Clínica de teste cujo primeiro usuário é de fato {@code DENTIST_ADMIN} no banco: a fixture cria todos como recepção, e a
	 * constraint {@code ck_users_cro_role} só aceita CRO em linha de dentista.
	 */
	protected static Clinic newDentistClinic() {
		var admin = DatabaseContainer.admin();
		UUID clinicId = UUID.randomUUID();
		admin.update("INSERT INTO core.clinics (id, name) VALUES (?, ?)", clinicId, "Clínica " + clinicId);
		UUID dentist = UUID.randomUUID();
		UUID reception = UUID.randomUUID();
		admin.update("INSERT INTO core.users (id, clinic_id, name, email, password_hash, role) VALUES (?, ?, 'Dra. Teste', ?, 'hash-de-teste', 'DENTIST_ADMIN')",
				dentist, clinicId, dentist + "@teste.local");
		admin.update("INSERT INTO core.users (id, clinic_id, name, email, password_hash, role) VALUES (?, ?, 'Recepção Teste', ?, 'hash-de-teste', 'RECEPTIONIST')",
				reception, clinicId, reception + "@teste.local");
		return new Clinic(clinicId, List.of(dentist, reception));
	}

	/** {@code version} atual do usuário do token (vem de {@code GET /me}). */
	protected long versionOf(Clinic clinic, Role role) throws Exception {
		return read(mvc.perform(get("/api/v1/me").with(as(clinic, role))).andExpect(status().isOk()).andReturn()).get("version")
				.asLong();
	}

	/**
	 * Executa instruções SQL como administrador numa ÚNICA conexão com {@code session_replication_role = replica} (sem
	 * triggers nem FKs): só para semear massa de desempenho. Tabelas temporárias valem entre as instruções.
	 */
	protected static void seedSql(String... statements) {
		try (java.sql.Connection connection = DatabaseContainer.admin().getDataSource().getConnection();
				java.sql.Statement statement = connection.createStatement()) {
			statement.execute("SET session_replication_role = replica");
			for (String sql : statements) {
				statement.execute(sql);
			}
		}
		catch (java.sql.SQLException ex) {
			throw new IllegalStateException(ex.getMessage(), ex);
		}
	}

	/** p95 (nearest-rank) em milissegundos. */
	protected static long p95(java.util.List<Long> samplesMs) {
		java.util.List<Long> sorted = samplesMs.stream().sorted().toList();
		return sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1);
	}

	/** Clínica com dentista (CRO 12345/SP cadastrado pela API) e um paciente ativo. */
	protected Setup openDentistSetup() throws Exception {
		Clinic clinic = newDentistClinic();
		registerCro(clinic, "12345", "SP");
		return setupOf(clinic, createPatient(clinic, "Maria Souza"));
	}

	/** Cadastra o CRO do dentista pela API e devolve o perfil. */
	protected JsonNode registerCro(Clinic clinic, String cro, String state) throws Exception {
		String body = "{\"cro\":\"%s\",\"croState\":\"%s\",\"version\":%d}".formatted(cro, state,
				versionOf(clinic, Role.DENTIST_ADMIN));
		return read(mvc.perform(json(put("/api/v1/me/professional-registry"), body).with(as(clinic, Role.DENTIST_ADMIN)))
				.andExpect(status().isOk()).andReturn());
	}
}
