package crm_data.phase1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import crm_data.shared.error.DatabaseErrors;
import crm_data.shared.security.Role;
import crm_data.support.SqlRecordingDataSourceConfig;
import crm_data.support.tenant.TenantFixtures.Clinic;

/**
 * A RLS por papel provada com SQL direto (nenhum filtro Hibernate no caminho), nas 5 tabelas, para papel real,
 * forjado, ausente e inválido; e as gravações que cada um pode ou não fazer. O papel do banco só vale se o backend o
 * informar; estes testes mostram o que o banco faz com cada valor.
 */
class Phase1ForgedRoleIT extends Phase1ItSupport {

	private static final String[] TABLES = { "patients", "medical_histories", "clinical_notes", "patient_files", "audit_log" };

	private record World(Clinic clinic, String patient, UUID dentist, UUID receptionist) {
	}

	private World world() throws Exception {
		Clinic clinic = newClinic();
		String patient = createPatient(clinic, "Maria Souza", null);
		saveHistory(clinic, patient, "Alergia grave a penicilina");
		addNote(clinic, patient, "Restauração do dente 36");
		addFile(clinic, patient, "raio-x.png");
		return new World(clinic, patient, clinic.userIds().get(0), clinic.userIds().get(1));
	}

	/** Linhas visíveis em cada tabela; {@code overrideRole} sobrescreve {@code app.role} DEPOIS de o dialeto defini-lo. */
	private Map<String, Long> visible(World w, UUID user, Role role, String overrideRole) {
		return asRole(w.clinic(), user, role, () -> {
			if (overrideRole != null) {
				jdbc.queryForObject("SELECT set_config('app.role', ?, true)", String.class, overrideRole);
			}
			Map<String, Long> counts = new LinkedHashMap<>();
			for (String table : TABLES) {
				counts.put(table, jdbc.queryForObject("SELECT count(*) FROM core." + table, Long.class));
			}
			return counts;
		});
	}

	private static String sqlState(Throwable error) {
		return DatabaseErrors.sqlState(error).orElse("?");
	}

	// ---- leitura ----

	@Test
	void theDentistSeesEveryTable() throws Exception {
		World w = world();

		assertThat(visible(w, w.dentist(), Role.DENTIST_ADMIN, null)).allSatisfy((table, rows) -> assertThat(rows).as(table).isGreaterThan(0));
	}

	@Test
	void aForgedReceptionistSeesOnlyPatients() throws Exception {
		World w = world();

		Map<String, Long> seen = visible(w, w.receptionist(), Role.RECEPTIONIST, null);

		assertThat(seen.get("patients")).isEqualTo(1L);
		assertThat(seen.get("medical_histories")).isZero();
		assertThat(seen.get("clinical_notes")).isZero();
		assertThat(seen.get("patient_files")).isZero();
		assertThat(seen.get("audit_log")).isZero();
	}

	@Test
	void anAbsentOrInvalidRoleDeniesEvenPatients() throws Exception {
		World w = world();

		assertThat(visible(w, w.dentist(), null, null)).as("papel ausente").allSatisfy((table, rows) -> assertThat(rows).as(table).isZero());
		for (String invalid : new String[] { "ADMIN", "dentist_admin", "DENTIST_ADMIN ", "SUPERUSER", "" }) {
			assertThat(visible(w, w.dentist(), Role.DENTIST_ADMIN, invalid)).as("papel inválido '" + invalid + "'")
					.allSatisfy((table, rows) -> assertThat(rows).as(table).isZero());
		}
	}

	// ---- gravação como recepção forjada ----

	@Test
	void aForgedReceptionistCanCrudPatientsAndInsertFilesButNothingElse() throws Exception {
		World w = world();
		UUID patient = UUID.fromString(w.patient());
		UUID fileId = UUID.randomUUID();
		String key = w.clinic().id() + "/" + patient + "/" + fileId;

		// o que ela PODE
		asRole(w.clinic(), w.receptionist(), Role.RECEPTIONIST, () -> {
			assertThat(jdbc.update("""
					INSERT INTO core.patients (id, clinic_id, name, birth_date, phone, created_by)
					VALUES (gen_random_uuid(), core.current_clinic_id(), 'Pedro Reis', DATE '1990-01-01', '11987654321', ?)""",
					w.receptionist())).isEqualTo(1);
			assertThat(jdbc.update("UPDATE core.patients SET profession = 'Professor' WHERE id = ?", patient)).isEqualTo(1);
			assertThat(jdbc.update("""
					INSERT INTO core.patient_files (id, clinic_id, patient_id, kind, storage_key, original_filename, content_type, size_bytes, created_by)
					VALUES (?, core.current_clinic_id(), ?, 'OTHER', ?, 'a.png', 'image/png', 10, ?)""",
					fileId, patient, key, w.receptionist())).isEqualTo(1);
			assertThat(jdbc.queryForObject("SELECT count(*) FROM core.patient_files", Long.class)).as("insere, mas não lê").isZero();
			return null;
		});

		// o que ela NÃO pode: gravar prontuário (RLS) e tocar a auditoria (sem GRANT)
		assertThatThrownBy(() -> asRole(w.clinic(), w.receptionist(), Role.RECEPTIONIST, () -> jdbc.update("""
				INSERT INTO core.medical_histories (clinic_id, patient_id, content, created_by)
				VALUES (core.current_clinic_id(), ?, repeat('B', 48), ?)""", patient, w.receptionist())))
				.satisfies(e -> assertThat(sqlState(e)).isEqualTo("42501"));
		assertThatThrownBy(() -> asRole(w.clinic(), w.receptionist(), Role.RECEPTIONIST, () -> jdbc.update("""
				INSERT INTO core.clinical_notes (clinic_id, patient_id, content, created_by)
				VALUES (core.current_clinic_id(), ?, repeat('B', 48), ?)""", patient, w.receptionist())))
				.satisfies(e -> assertThat(sqlState(e)).isEqualTo("42501"));
		assertThat(asRole(w.clinic(), w.receptionist(), Role.RECEPTIONIST, () -> jdbc.update("UPDATE core.medical_histories SET content = repeat('B', 48)")))
				.isZero();
		assertThat(asRole(w.clinic(), w.receptionist(), Role.RECEPTIONIST, () -> jdbc.update("UPDATE core.clinical_notes SET content = repeat('B', 48)")))
				.isZero();
		assertThat(asRole(w.clinic(), w.receptionist(), Role.RECEPTIONIST, () -> jdbc.update("UPDATE core.patient_files SET deleted_at = now()")))
				.as("a recepção não altera nem remove exame").isZero();
		assertThatThrownBy(() -> asRole(w.clinic(), w.receptionist(), Role.RECEPTIONIST, () -> jdbc.update("UPDATE core.audit_log SET action = 'DELETE'")))
				.satisfies(e -> assertThat(sqlState(e)).isEqualTo("42501"));
	}

	@Test
	void nobodyCanDeleteRowsOfTheClinicalTablesOrTheTrail() throws Exception {
		World w = world();

		for (String table : TABLES) {
			assertThatThrownBy(() -> asRole(w.clinic(), w.dentist(), Role.DENTIST_ADMIN, () -> jdbc.update("DELETE FROM core." + table)))
					.as("DELETE em " + table).satisfies(e -> assertThat(sqlState(e)).isEqualTo("42501"));
		}
	}

	@Test
	void anAbsentRoleCannotWriteAnything() throws Exception {
		World w = world();

		assertThatThrownBy(() -> asRole(w.clinic(), w.dentist(), null, () -> jdbc.update("""
				INSERT INTO core.patients (id, clinic_id, name, birth_date, phone, created_by)
				VALUES (gen_random_uuid(), core.current_clinic_id(), 'Pedro Reis', DATE '1990-01-01', '11987654321', ?)""", w.dentist())))
				.satisfies(e -> assertThat(sqlState(e)).isEqualTo("42501"));
		assertThat(asRole(w.clinic(), w.dentist(), null, () -> jdbc.update("UPDATE core.patients SET profession = 'x'"))).isZero();
	}

	// ---- usuário ausente ----

	@Test
	void anAbsentUserFailsEveryClinicalWrite() throws Exception {
		World w = world();
		UUID patient = UUID.fromString(w.patient());

		// INSERT: a RLS exige created_by = app.user_id, então a recusa vem antes da auditoria
		assertThatThrownBy(() -> asRole(w.clinic(), null, Role.DENTIST_ADMIN, () -> jdbc.update("""
				INSERT INTO core.patients (id, clinic_id, name, birth_date, phone, created_by)
				VALUES (gen_random_uuid(), core.current_clinic_id(), 'Pedro Reis', DATE '1990-01-01', '11987654321', ?)""", w.dentist())))
				.satisfies(e -> assertThat(sqlState(e)).isEqualTo("42501"));
		// UPDATE: passa na RLS e a auditoria (que não pode ser anônima) recusa com 28000
		assertThatThrownBy(() -> asRole(w.clinic(), null, Role.DENTIST_ADMIN,
				() -> jdbc.update("UPDATE core.patients SET profession = 'x' WHERE id = ?", patient)))
				.satisfies(e -> assertThat(sqlState(e)).isEqualTo("28000"));
		// exame da recepção sem usuário
		assertThatThrownBy(() -> asRole(w.clinic(), null, Role.RECEPTIONIST, () -> jdbc.update("""
				INSERT INTO core.patient_files (id, clinic_id, patient_id, kind, storage_key, original_filename, content_type, size_bytes, created_by)
				VALUES (?, core.current_clinic_id(), ?, 'OTHER', ?, 'a.png', 'image/png', 10, ?)""",
				UUID.randomUUID(), patient, w.clinic().id() + "/" + patient + "/" + UUID.randomUUID(), w.receptionist())))
				.satisfies(e -> assertThat(sqlState(e)).isEqualTo("42501"));
	}

	// ---- pelo caminho da API ----

	@Test
	void theReceptionistUploadThroughTheApiRunsNeitherSelectNorReturningOnPatientFiles() throws Exception {
		World w = world();
		SqlRecordingDataSourceConfig.reset();

		addFile(w.clinic(), w.patient(), "outro.png");

		assertThat(SqlRecordingDataSourceConfig.prepared()).filteredOn(sql -> sql.toLowerCase().contains("patient_files"))
				.isNotEmpty()
				.allSatisfy(sql -> assertThat(sql.toLowerCase()).startsWith("insert into core.patient_files").doesNotContain("returning"));
	}
}
