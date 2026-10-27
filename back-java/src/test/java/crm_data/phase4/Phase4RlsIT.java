package crm_data.phase4;

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

/**
 * A RLS dos documentos clínicos e do CRO provada com SQL direto (nenhum filtro Hibernate no caminho), com papel real,
 * forjado, ausente e de outra clínica: o banco é a barreira final. Documento é só do dentista; a recepção não lê nem grava.
 */
class Phase4RlsIT extends Phase4ItSupport {

	private record World(Clinic clinic, String patient, UUID dentist, UUID receptionist, String document) {
	}

	private World world() throws Exception {
		Setup setup = openDentistSetup();
		String document = read(mvc.perform(json(post("/api/v1/patients/" + setup.patient() + "/clinical-documents"),
				"{\"documentType\":\"PRESCRIPTION\",\"content\":\"Dipirona 500 mg\"}").with(as(setup.clinic(), Role.DENTIST_ADMIN)))
				.andExpect(status().isCreated()).andReturn()).get("id").asString();
		return new World(setup.clinic(), setup.patient(), userOf(setup.clinic(), Role.DENTIST_ADMIN),
				userOf(setup.clinic(), Role.RECEPTIONIST), document);
	}

	private long count(World w, UUID user, Role role, String sql, Object... args) {
		return asSession(w.clinic(), user, role, () -> jdbc.queryForObject(sql, Long.class, args));
	}

	private static String sqlState(Throwable error) {
		return DatabaseErrors.sqlState(error).orElse("?");
	}

	@Test
	void dentistSeesOnlyTheOwnClinicDocumentsWithoutAnyHibernateFilter() throws Exception {
		World mine = world();
		World other = world();

		assertThat(count(mine, mine.dentist(), Role.DENTIST_ADMIN, "SELECT count(*) FROM core.clinical_documents")).isEqualTo(1);
		assertThat(count(mine, mine.dentist(), Role.DENTIST_ADMIN, "SELECT count(*) FROM core.clinical_documents WHERE id = ?::uuid",
				other.document())).isZero();
		assertThat(count(mine, mine.dentist(), Role.DENTIST_ADMIN, "SELECT count(*) FROM core.clinical_documents WHERE id = ?::uuid",
				mine.document())).isEqualTo(1);
	}

	@Test
	void receptionAbsentAndInvalidRolesSeeNoDocuments() throws Exception {
		World w = world();

		assertThat(count(w, w.receptionist(), Role.RECEPTIONIST, "SELECT count(*) FROM core.clinical_documents")).isZero();
		assertThat(count(w, w.dentist(), null, "SELECT count(*) FROM core.clinical_documents")).as("papel ausente").isZero();
		assertThat(count(w, null, null, "SELECT count(*) FROM core.clinical_documents")).as("sem usuário nem papel").isZero();
	}

	@Test
	void receptionCannotInsertOrCallTheDownloadFunction() throws Exception {
		World w = world();

		assertThatThrownBy(() -> asSession(w.clinic(), w.receptionist(), Role.RECEPTIONIST, () -> jdbc.update("""
				INSERT INTO core.clinical_documents (id, clinic_id, patient_id, document_type, number, issued_on, rest_days, content, sha256, created_by)
				VALUES (gen_random_uuid(), ?::uuid, ?::uuid, 'CERTIFICATE', 1, current_date, 1, repeat('A', 40), repeat('a', 64), ?::uuid)""",
				w.clinic().id().toString(), w.patient(), w.receptionist().toString()))).satisfies(e -> assertThat(sqlState(e)).isEqualTo("42501"));
		assertThatThrownBy(() -> asSession(w.clinic(), w.receptionist(), Role.RECEPTIONIST,
				() -> jdbc.queryForObject("SELECT cast(core.record_document_download(?::uuid) AS text)", String.class, w.document())))
						.satisfies(e -> assertThat(sqlState(e)).isEqualTo("42501"));
	}

	@Test
	void anotherClinicCannotChangeOrDownloadADocument() throws Exception {
		World mine = world();
		World other = world();

		int updated = asSession(other.clinic(), other.dentist(), Role.DENTIST_ADMIN, () -> jdbc.update(
				"UPDATE core.clinical_documents SET voided_at = now(), voided_by = ?::uuid, version = version + 1 WHERE id = ?::uuid",
				other.dentist().toString(), mine.document()));
		assertThat(updated).as("a linha de outra clínica nem aparece").isZero();
		assertThatThrownBy(() -> asSession(other.clinic(), other.dentist(), Role.DENTIST_ADMIN,
				() -> jdbc.queryForObject("SELECT cast(core.record_document_download(?::uuid) AS text)", String.class, mine.document())))
						.satisfies(e -> assertThat(sqlState(e)).isEqualTo("P0002"));
		assertThat(count(mine, mine.dentist(), Role.DENTIST_ADMIN, "SELECT count(*) FROM core.clinical_documents WHERE voided_at IS NOT NULL")).isZero();
	}

	@Test
	void insertForAnotherClinicOrWithAForgedAuthorIsRefused() throws Exception {
		World mine = world();
		World other = world();

		assertThatThrownBy(() -> asSession(mine.clinic(), mine.dentist(), Role.DENTIST_ADMIN, () -> jdbc.update("""
				INSERT INTO core.clinical_documents (id, clinic_id, patient_id, document_type, number, issued_on, rest_days, content, sha256, created_by)
				VALUES (gen_random_uuid(), ?::uuid, ?::uuid, 'CERTIFICATE', 1, current_date, 1, repeat('A', 40), repeat('a', 64), ?::uuid)""",
				other.clinic().id().toString(), other.patient(), other.dentist().toString()))).isNotNull();
		assertThatThrownBy(() -> asSession(mine.clinic(), mine.dentist(), Role.DENTIST_ADMIN, () -> jdbc.update("""
				INSERT INTO core.clinical_documents (id, clinic_id, patient_id, document_type, number, issued_on, rest_days, content, sha256, created_by)
				VALUES (gen_random_uuid(), ?::uuid, ?::uuid, 'CERTIFICATE', 1, current_date, 1, repeat('A', 40), repeat('a', 64), ?::uuid)""",
				mine.clinic().id().toString(), mine.patient(), mine.receptionist().toString()))) // autor sem CRO: o trigger recusa antes da RLS
						.satisfies(e -> assertThat(sqlState(e)).isIn("42501", "CR012"));
		assertThat(count(mine, mine.dentist(), Role.DENTIST_ADMIN, "SELECT count(*) FROM core.clinical_documents")).isEqualTo(1);
	}

	@Test
	void theAppRoleCanNeitherDeleteNorTruncateNorEditTheContent() throws Exception {
		World w = world();

		for (String sql : List.of("DELETE FROM core.clinical_documents", "TRUNCATE core.clinical_documents",
				"UPDATE core.clinical_documents SET content = repeat('B', 40)", "UPDATE core.clinical_documents SET sha256 = repeat('b', 64)")) {
			assertThatThrownBy(() -> asSession(w.clinic(), w.dentist(), Role.DENTIST_ADMIN, () -> jdbc.update(sql)))
					.as(sql).satisfies(e -> assertThat(sqlState(e)).isEqualTo("42501"));
		}
	}

	@Test
	void onlyTheOwnerOfTheProfileCanChangeTheCro() throws Exception {
		World w = world();

		assertThatThrownBy(() -> asSession(w.clinic(), w.receptionist(), Role.DENTIST_ADMIN,
				() -> jdbc.update("UPDATE core.users SET cro_number = '999', cro_state = 'RJ' WHERE id = ?::uuid", w.dentist().toString())))
						.satisfies(e -> assertThat(sqlState(e)).isEqualTo("42501"));
	}
}
