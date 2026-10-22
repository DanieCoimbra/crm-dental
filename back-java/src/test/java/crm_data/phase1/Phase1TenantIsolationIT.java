package crm_data.phase1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.hibernate.Session;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import crm_data.phase1.Phase1Routes.Ctx;
import crm_data.phase1.Phase1Routes.Route;
import crm_data.record.domain.AuditLog;
import crm_data.record.domain.ClinicalNote;
import crm_data.record.domain.MedicalHistory;
import crm_data.record.domain.PatientFile;
import crm_data.patient.domain.Patient;
import crm_data.shared.security.Role;
import crm_data.shared.tenant.TenantAwareEntity;
import crm_data.shared.tenant.TenantContext;
import crm_data.support.DatabaseContainer;
import crm_data.support.tenant.TenantFixtures.Clinic;
import jakarta.persistence.EntityManager;

/**
 * Isolamento entre clínicas nas 5 tabelas da Fase 1 com o filtro Hibernate DESLIGADO: quem protege é a RLS do banco.
 * As duas clínicas têm dados em todas as tabelas (a auditoria vem dos triggers), para que "0 linhas da outra" não seja
 * resultado de tabela vazia.
 */
class Phase1TenantIsolationIT extends Phase1ItSupport {

	private static final List<String> TABLES = List.of("patients", "medical_histories", "clinical_notes", "patient_files", "audit_log");
	private static final List<Class<? extends TenantAwareEntity>> ENTITIES = List.of(Patient.class, MedicalHistory.class,
			ClinicalNote.class, PatientFile.class, AuditLog.class);

	@org.springframework.beans.factory.annotation.Autowired
	EntityManager entityManager;

	private record World(Clinic clinic, String patient, String note, String file) {
	}

	private World populate(String patientName, String cpf) throws Exception {
		Clinic clinic = newClinic();
		String patient = createPatient(clinic, patientName, cpf);
		saveHistory(clinic, patient, "Alergia grave a penicilina");
		String note = addNote(clinic, patient, "Restauração do dente 36");
		String file = addFile(clinic, patient, "raio-x.png");
		return new World(clinic, patient, note, file);
	}

	private <T> T asDentistWithoutHibernateFilter(Clinic clinic, java.util.function.Supplier<T> block) {
		TenantContext.set(clinic.id(), clinic.userIds().get(0), Role.DENTIST_ADMIN);
		try {
			return new TransactionTemplate(transactionManager).execute(status -> {
				entityManager.unwrap(Session.class).disableFilter(TenantAwareEntity.FILTER);
				return block.get();
			});
		}
		finally {
			TenantContext.clear();
		}
	}

	@Test
	void bothClinicsHaveRowsInEveryTableSoTheIsolationProofIsNotVacuous() throws Exception {
		World a = populate("Maria Souza", "52998224725");
		World b = populate("Pedro Reis", "52998224725"); // o mesmo CPF em outra clínica é aceito

		var admin = DatabaseContainer.admin();
		for (String table : TABLES) {
			for (World world : List.of(a, b)) {
				assertThat(admin.queryForObject("SELECT count(*) FROM core." + table + " WHERE clinic_id = ?::uuid", Long.class,
						world.clinic().id().toString())).as(table).isGreaterThan(0);
			}
		}
	}

	@Test
	void jpaQueriesWithTheFilterOffReturnOnlyOwnRowsInAllFiveTables() throws Exception {
		World a = populate("Maria Souza", null);
		World b = populate("Pedro Reis", null);

		for (Class<? extends TenantAwareEntity> entity : ENTITIES) {
			List<? extends TenantAwareEntity> seenByB = asDentistWithoutHibernateFilter(b.clinic(),
					() -> entityManager.createQuery("select e from " + entity.getSimpleName() + " e", entity).getResultList());

			assertThat(seenByB).as(entity.getSimpleName()).isNotEmpty()
					.allSatisfy(row -> assertThat(row.getClinicId()).isEqualTo(b.clinic().id()))
					.noneSatisfy(row -> assertThat(row.getClinicId()).isEqualTo(a.clinic().id()));
		}
	}

	@Test
	void nativeSqlCrossReadsAreEmptyInAllFiveTables() throws Exception {
		World a = populate("Maria Souza", null);
		World b = populate("Pedro Reis", null);
		var admin = DatabaseContainer.admin();

		for (String table : TABLES) {
			long ownRows = admin.queryForObject("SELECT count(*) FROM core." + table + " WHERE clinic_id = ?::uuid", Long.class,
					b.clinic().id().toString());
			long[] seen = asDentistWithoutHibernateFilter(b.clinic(), () -> new long[] {
					jdbc.queryForObject("SELECT count(*) FROM core." + table + " WHERE clinic_id = ?::uuid", Long.class, a.clinic().id().toString()),
					jdbc.queryForObject("SELECT count(*) FROM core." + table, Long.class) });

			assertThat(seen[0]).as(table + ": linhas da clínica A vistas pela B").isZero();
			assertThat(seen[1]).as(table + ": total visto pela B = só as suas").isEqualTo(ownRows);
		}
	}

	@Test
	void nativeSqlCannotWriteIntoAnotherClinic() throws Exception {
		World a = populate("Maria Souza", null);
		World b = populate("Pedro Reis", null);

		long updated = asDentistWithoutHibernateFilter(b.clinic(),
				() -> (long) jdbc.update("UPDATE core.patients SET profession = 'invasor' WHERE id = ?::uuid", a.patient()));

		assertThat(updated).isZero();
		assertThat(DatabaseContainer.admin().queryForObject("SELECT profession FROM core.patients WHERE id = ?::uuid", String.class, a.patient()))
				.isNull();
	}

	@Test
	void everyRouteThatTakesAnIdAnswers404ForAnotherClinicsIds() throws Exception {
		World a = populate("Maria Souza", null);
		World b = populate("Pedro Reis", null);
		String foreignAnonymizable = createPatient(a.clinic(), "Ana Lima", null);
		deactivate(a.clinic(), foreignAnonymizable);
		Ctx foreign = new Ctx(UUID.fromString(a.patient()), UUID.fromString(a.note()), UUID.fromString(a.file()),
				UUID.fromString(foreignAnonymizable), 0, 0);
		var dentistOfB = as(b.clinic(), Role.DENTIST_ADMIN);

		List<Route> idRoutes = List.of(Route.GET_PATIENT, Route.UPDATE_PATIENT, Route.SAVE_HISTORY, Route.GET_HISTORY, Route.CREATE_NOTE,
				Route.LIST_NOTES, Route.UPDATE_NOTE, Route.UPLOAD_FILE, Route.LIST_FILES, Route.DOWNLOAD_URL, Route.DELETE_FILE,
				Route.SET_ACTIVE, Route.ANONYMIZE);
		for (Route route : idRoutes) {
			mvc.perform(route.request(foreign, dentistOfB)).andExpect(status().isNotFound());
		}
		// a clínica A não foi tocada por nenhuma das tentativas
		var admin = DatabaseContainer.admin();
		assertThat(admin.queryForObject("SELECT active FROM core.patients WHERE id = ?::uuid", Boolean.class, a.patient())).isTrue();
		assertThat(admin.queryForObject("SELECT anonymized_at IS NULL FROM core.patients WHERE id = ?::uuid", Boolean.class, foreignAnonymizable))
				.isTrue();
		assertThat(admin.queryForObject("SELECT deleted_at IS NULL FROM core.patient_files WHERE id = ?::uuid", Boolean.class, a.file())).isTrue();
		assertThat(objectsOf(b.clinic())).as("nenhum objeto foi criado na clínica B para o paciente da A").isEqualTo(1);
	}

	@Test
	void theAuditTrailAndTheListingsOfAnotherClinicAreEmptyNotForbidden() throws Exception {
		World a = populate("Maria Souza", null);
		World b = populate("Pedro Reis", null);
		var dentistOfB = as(b.clinic(), Role.DENTIST_ADMIN);

		mvc.perform(authenticatedGet("/api/v1/audit-log", dentistOfB, "tableName", "patients", "recordId", a.patient()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
		mvc.perform(authenticatedGet("/api/v1/patients", dentistOfB, "status", "all", "q", "Maria"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
	}
}
