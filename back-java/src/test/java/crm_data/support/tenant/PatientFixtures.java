package crm_data.support.tenant;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import crm_data.support.DatabaseContainer;
import crm_data.support.tenant.TenantFixtures.Clinic;

/**
 * Monta estados de paciente direto no banco como administrador (ignora a RLS de {@code patients}). A auditoria exige
 * clínica e usuário na sessão mesmo para o administrador, então cada operação os define na conexão que usa.
 */
public final class PatientFixtures {

	private PatientFixtures() {
	}

	/** Insere {@code count} pacientes ativos, "Paciente N Silva", na clínica. */
	public static void seed(Clinic clinic, int count) throws SQLException {
		try (Connection connection = DatabaseContainer.admin().getDataSource().getConnection();
				Statement statement = connection.createStatement()) {
			setSession(statement, clinic);
			statement.execute("""
					INSERT INTO core.patients (id, clinic_id, name, birth_date, phone, created_by)
					SELECT gen_random_uuid(), '%s', 'Paciente ' || g || ' Silva', DATE '1990-01-01', '11987654321', '%s'
					FROM generate_series(1, %d) g""".formatted(clinic.id(), clinic.userIds().get(0), count));
		}
	}

	/** Como {@link #seed}, mas cada paciente tem um CPF único de 11 dígitos (só o formato; os dígitos verificadores não valem). */
	public static void seedWithCpf(Clinic clinic, int count) throws SQLException {
		try (Connection connection = DatabaseContainer.admin().getDataSource().getConnection();
				Statement statement = connection.createStatement()) {
			setSession(statement, clinic);
			statement.execute("""
					INSERT INTO core.patients (id, clinic_id, name, cpf, birth_date, phone, created_by)
					SELECT gen_random_uuid(), '%s', 'Paciente ' || g || ' Silva', lpad(g::text, 11, '0'), DATE '1990-01-01', '11987654321', '%s'
					FROM generate_series(1, %d) g""".formatted(clinic.id(), clinic.userIds().get(0), count));
		}
	}

	/** Deixa o paciente anonimizado (a função real é da F05; aqui só o estado final, com a flag que só ela liga). */
	public static void anonymize(Clinic clinic, String patientId) throws SQLException {
		try (Connection connection = DatabaseContainer.admin().getDataSource().getConnection();
				Statement statement = connection.createStatement()) {
			setSession(statement, clinic);
			statement.execute("SELECT set_config('core.anonymizing', 'on', false)");
			statement.execute("UPDATE core.patients SET active = false, deactivated_at = now(), anonymized_at = now() "
					+ "WHERE id = '" + patientId + "'");
		}
	}

	/** Troca o texto cifrado do histórico por um Base64 válido que não decifra (tag inválida): simula adulteração. */
	public static void corruptHistory(Clinic clinic, String patientId) throws SQLException {
		try (Connection connection = DatabaseContainer.admin().getDataSource().getConnection();
				Statement statement = connection.createStatement()) {
			setSession(statement, clinic);
			statement.execute("UPDATE core.medical_histories SET content = repeat('A', 48) WHERE patient_id = '" + patientId + "'");
		}
	}

	/** Copia a nota informada {@code count} vezes (mesmo texto cifrado, datas distintas): carga para a linha do tempo. */
	public static void cloneNote(Clinic clinic, String noteId, int count) throws SQLException {
		try (Connection connection = DatabaseContainer.admin().getDataSource().getConnection();
				Statement statement = connection.createStatement()) {
			setSession(statement, clinic);
			statement.execute("""
					INSERT INTO core.clinical_notes (id, clinic_id, patient_id, noted_at, content, created_by)
					SELECT gen_random_uuid(), n.clinic_id, n.patient_id, n.noted_at - (g || ' minutes')::interval, n.content, n.created_by
					FROM core.clinical_notes n CROSS JOIN generate_series(1, %d) g
					WHERE n.id = '%s'""".formatted(count, noteId));
		}
	}

	/**
	 * Recua a data dos registros clínicos do paciente em {@code years} anos, para vencer a retenção legal. Usa a conexão
	 * do administrador com {@code session_replication_role = replica}, que desliga os triggers (o de {@code updated_at}
	 * desfaria o recuo).
	 */
	public static void backdateClinicalRecords(String patientId, int years) throws SQLException {
		try (Connection connection = DatabaseContainer.admin().getDataSource().getConnection();
				Statement statement = connection.createStatement()) {
			String ago = "now() - interval '" + years + " years'";
			statement.execute("SET session_replication_role = replica");
			statement.execute("UPDATE core.medical_histories SET updated_at = " + ago + " WHERE patient_id = '" + patientId + "'");
			statement.execute("UPDATE core.clinical_notes SET updated_at = " + ago + ", noted_at = " + ago + " WHERE patient_id = '" + patientId + "'");
			statement.execute("UPDATE core.patient_files SET updated_at = " + ago + " WHERE patient_id = '" + patientId + "'");
			statement.execute("RESET session_replication_role");
		}
	}

	private static void setSession(Statement statement, Clinic clinic) throws SQLException {
		statement.execute("SELECT set_config('app.clinic_id', '" + clinic.id() + "', false), "
				+ "set_config('app.user_id', '" + clinic.userIds().get(0) + "', false)");
	}
}
