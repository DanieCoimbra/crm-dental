package crm_data.shared.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import crm_data.shared.error.DatabaseErrors;
import crm_data.shared.security.Role;
import crm_data.support.DatabaseContainer;
import crm_data.support.SqlRecordingDataSourceConfig;
import crm_data.support.tenant.TenantFixtures;
import crm_data.support.tenant.TenantFixtures.Clinic;

/**
 * F01: o dialeto fixa {@code app.clinic_id}, {@code app.role} e {@code app.user_id} em cada transação, sem vazar entre
 * transações (pool de uma conexão), e o banco nega o que o papel não pode, mesmo com SQL direto (sem filtro Hibernate).
 */
@SpringBootTest(properties = { "spring.datasource.hikari.maximum-pool-size=1", "spring.datasource.hikari.minimum-idle=1" })
@ActiveProfiles("test")
@Import(SqlRecordingDataSourceConfig.class)
class TenantSessionIT {

	private static final String SETTINGS = """
			SELECT coalesce(current_setting('app.clinic_id', true), '') || '|' || coalesce(current_setting('app.role', true), '')
			    || '|' || coalesce(current_setting('app.user_id', true), '')""";
	private static final String VALID_CIPHERTEXT = "A".repeat(48);

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		DatabaseContainer.register(registry);
	}

	@Autowired
	PlatformTransactionManager transactionManager;
	@Autowired
	JdbcTemplate jdbc;

	@AfterEach
	void cleanUp() {
		TenantContext.clear();
	}

	private <T> T as(UUID clinicId, UUID userId, Role role, Supplier<T> block) {
		TenantContext.set(clinicId, userId, role);
		try {
			return new TransactionTemplate(transactionManager).execute(status -> block.get());
		}
		finally {
			TenantContext.clear();
		}
	}

	@Test
	void everyTransactionCarriesTheThreeSessionVariablesAndNothingLeaksToTheNext() {
		Clinic a = TenantFixtures.clinicWithUsers(1);
		Clinic b = TenantFixtures.clinicWithUsers(1);
		UUID userA = a.userIds().get(0);

		String inA = as(a.id(), userA, Role.DENTIST_ADMIN, () -> jdbc.queryForObject(SETTINGS, String.class));
		assertThat(inA).isEqualTo(a.id() + "|DENTIST_ADMIN|" + userA);

		String outside = jdbc.queryForObject(SETTINGS, String.class);
		assertThat(outside).isEqualTo("||");

		UUID userB = b.userIds().get(0);
		String inB = as(b.id(), userB, Role.RECEPTIONIST, () -> jdbc.queryForObject(SETTINGS, String.class));
		assertThat(inB).isEqualTo(b.id() + "|RECEPTIONIST|" + userB);
	}

	@Test
	void sessionVariablesAreSetByExactlyOneStatementPerTransaction() {
		Clinic a = TenantFixtures.clinicWithUsers(1);
		SqlRecordingDataSourceConfig.reset();

		as(a.id(), a.userIds().get(0), Role.DENTIST_ADMIN, () -> jdbc.queryForObject("SELECT 1", Integer.class));

		assertThat(SqlRecordingDataSourceConfig.prepared()).filteredOn(sql -> sql.contains("set_config"))
				.singleElement().asString().contains("app.clinic_id", "app.role", "app.user_id");
	}

	@Test
	void flowsWithoutUserLeaveRoleAndUserEmpty() {
		Clinic a = TenantFixtures.clinicWithUsers(1);

		String settings = TenantRunner.call(a.id(),
				() -> new TransactionTemplate(transactionManager).execute(s -> jdbc.queryForObject(SETTINGS, String.class)));

		assertThat(settings).isEqualTo(a.id() + "||");
	}

	@Test
	void receptionistReadsPatientsButNotTheClinicalRecord() {
		Clinic clinic = TenantFixtures.clinicWithUsers(1);
		UUID user = clinic.userIds().get(0);
		UUID patient = insertPatientWithHistory(clinic.id(), user);

		assertThat(as(clinic.id(), user, Role.RECEPTIONIST,
				() -> jdbc.queryForObject("SELECT count(*) FROM core.patients", Long.class))).isEqualTo(1L);
		assertThat(as(clinic.id(), user, Role.RECEPTIONIST,
				() -> jdbc.queryForObject("SELECT count(*) FROM core.medical_histories WHERE patient_id = ?", Long.class,
						patient))).isZero();
		assertThat(as(clinic.id(), user, Role.DENTIST_ADMIN,
				() -> jdbc.queryForObject("SELECT count(*) FROM core.medical_histories WHERE patient_id = ?", Long.class,
						patient))).isEqualTo(1L);
	}

	@Test
	void withoutAValidRoleEvenPatientsAreDenied() {
		Clinic clinic = TenantFixtures.clinicWithUsers(1);
		UUID user = clinic.userIds().get(0);
		insertPatientWithHistory(clinic.id(), user);

		assertThat(as(clinic.id(), user, null,
				() -> jdbc.queryForObject("SELECT count(*) FROM core.patients", Long.class))).isZero();
		assertThatThrownBy(() -> as(clinic.id(), user, null, () -> insertPatient(user)))
				.isInstanceOf(DataAccessException.class);
	}

	@Test
	void clinicalUpdateWithoutUserFailsWith28000() {
		Clinic clinic = TenantFixtures.clinicWithUsers(1);
		UUID user = clinic.userIds().get(0);
		UUID patient = as(clinic.id(), user, Role.DENTIST_ADMIN, () -> insertPatient(user));

		// o UPDATE passa na RLS (que não exige usuário); a auditoria recusa a gravação anônima
		assertThatThrownBy(() -> as(clinic.id(), null, Role.DENTIST_ADMIN,
				() -> jdbc.update("UPDATE core.patients SET name = 'Outro Nome' WHERE id = ?", patient)))
				.satisfies(error -> assertThat(DatabaseErrors.sqlState(error)).contains("28000"));
	}

	@Test
	void clinicalInsertWithoutUserIsDeniedByRowLevelSecurity() {
		Clinic clinic = TenantFixtures.clinicWithUsers(1);
		UUID user = clinic.userIds().get(0);

		// created_by = current_user_id() é exigido pela política de INSERT: nega (42501) antes da auditoria
		assertThatThrownBy(() -> as(clinic.id(), null, Role.DENTIST_ADMIN, () -> insertPatient(user)))
				.satisfies(error -> assertThat(DatabaseErrors.sqlState(error)).contains("42501"));
	}

	private UUID insertPatientWithHistory(UUID clinicId, UUID user) {
		return as(clinicId, user, Role.DENTIST_ADMIN, () -> {
			UUID patient = insertPatient(user);
			jdbc.update("""
					INSERT INTO core.medical_histories (clinic_id, patient_id, content, created_by)
					VALUES (core.current_clinic_id(), ?, ?, ?)""", patient, VALID_CIPHERTEXT, user);
			return patient;
		});
	}

	private UUID insertPatient(UUID user) {
		UUID id = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO core.patients (id, clinic_id, name, birth_date, phone, created_by)
				VALUES (?, core.current_clinic_id(), 'Paciente Teste', DATE '1990-01-01', '11987654321', ?)""", id, user);
		return id;
	}
}
