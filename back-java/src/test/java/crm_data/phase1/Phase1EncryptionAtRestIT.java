package crm_data.phase1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;

import crm_data.shared.security.Role;
import crm_data.support.DatabaseContainer;
import crm_data.support.tenant.TenantFixtures.Clinic;

/**
 * Nenhum texto clínico em claro no banco: o histórico e as notas guardam só {@code Base64(versão | IV | cifra + tag)}.
 * Além de conferir o formato de toda linha, procura os textos enviados em TODAS as tabelas do schema (o texto de uma
 * nota que escapasse para uma coluna de auditoria, por exemplo, seria achado).
 */
class Phase1EncryptionAtRestIT extends Phase1ItSupport {

	private static final String BASE64 = "^[A-Za-z0-9+/]+={0,2}$";
	/** Versão da chave ativa nos testes (ver {@code DatabaseContainer.register}). */
	private static final int ACTIVE_KEY_VERSION = 2;

	private String patientWithRecords(Clinic clinic, String tag) throws Exception {
		String patient = createPatient(clinic, "Paciente " + tag, null);
		saveHistory(clinic, patient, "SENTINELA-HISTORICO-" + tag + " alergia a penicilina");
		addNote(clinic, patient, "SENTINELA-NOTA-" + tag + " restauração do dente 36");
		addNote(clinic, patient, "SENTINELA-NOTA2-" + tag + " profilaxia");
		return patient;
	}

	@Test
	void everyStoredContentIsOpaqueBase64WithTheMinimumLength() throws Exception {
		Clinic a = newClinic();
		Clinic b = newClinic();
		patientWithRecords(a, "A");
		patientWithRecords(b, "B");
		var admin = DatabaseContainer.admin();

		for (String table : List.of("medical_histories", "clinical_notes")) {
			assertThat(admin.queryForObject("SELECT count(*) FROM core." + table, Long.class)).as(table).isGreaterThan(0);
			assertThat(admin.queryForObject("SELECT count(*) FROM core." + table + " WHERE content !~ '" + BASE64 + "' OR char_length(content) < 40",
					Long.class)).as(table + ": linhas fora do formato cifrado").isZero();
		}
	}

	@Test
	void noClinicalTextAppearsInAnyTableOfTheDatabase() throws Exception {
		Clinic clinic = newClinic();
		patientWithRecords(clinic, "SEM-VAZAMENTO");
		var admin = DatabaseContainer.admin();
		List<String> tables = admin.queryForList(
				"SELECT table_name FROM information_schema.tables WHERE table_schema = 'core' AND table_type = 'BASE TABLE'", String.class);

		assertThat(tables).contains("patients", "medical_histories", "clinical_notes", "patient_files", "audit_log");
		for (String table : tables) {
			assertThat(admin.queryForObject("SELECT count(*) FROM core.\"" + table + "\" t WHERE t::text LIKE '%SENTINELA%'", Long.class))
					.as("texto clínico em claro na tabela " + table).isZero();
			assertThat(admin.queryForObject("SELECT count(*) FROM core.\"" + table + "\" t WHERE t::text ILIKE '%penicilina%' OR t::text ILIKE '%dente 36%'",
					Long.class)).as("texto clínico em claro na tabela " + table).isZero();
		}
	}

	@Test
	void theStoredValueFollowsTheDocumentedLayoutVersionIvCipherAndTag() throws Exception {
		Clinic clinic = newClinic();
		String patient = patientWithRecords(clinic, "LAYOUT");
		var admin = DatabaseContainer.admin();

		for (String sql : List.of("SELECT content FROM core.medical_histories WHERE patient_id = ?::uuid",
				"SELECT content FROM core.clinical_notes WHERE patient_id = ?::uuid LIMIT 1")) {
			byte[] raw = Base64.getDecoder().decode(admin.queryForObject(sql, String.class, patient));

			assertThat(raw.length).as("versão(1) + IV(12) + tag(16) + texto").isGreaterThan(1 + 12 + 16);
			assertThat(raw[0]).as("versão da chave").isEqualTo((byte) ACTIVE_KEY_VERSION);
		}
	}

	@Test
	void theSamePlaintextGivesDifferentCipherTextsBecauseOfTheRandomIv() throws Exception {
		Clinic clinic = newClinic();
		String patient = createPatient(clinic, "Maria Souza", null);
		addNote(clinic, patient, "texto idêntico nas duas notas");
		addNote(clinic, patient, "texto idêntico nas duas notas");

		var stored = DatabaseContainer.admin().queryForList("SELECT content FROM core.clinical_notes WHERE patient_id = ?::uuid", String.class, patient);

		assertThat(stored).hasSize(2).doesNotHaveDuplicates();
	}

	@Test
	void theApiReturnsExactlyWhatWasSentIncludingSpacesAndUnicode() throws Exception {
		Clinic clinic = newClinic();
		String patient = createPatient(clinic, "Maria Souza", null);
		String text = "  Paciente relata dor — 36 \\u00e7 \\ud83d\\ude00  ";

		saveHistory(clinic, patient, text);

		mvc.perform(get("/api/v1/patients/" + patient + "/medical-history").with(as(clinic, Role.DENTIST_ADMIN)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content").value("  Paciente relata dor — 36 ç 😀  "));
	}
}
