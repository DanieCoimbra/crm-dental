package crm_data.phase2;

import static crm_data.phase2.AgendaDates.at;
import static crm_data.phase2.AgendaDates.monday;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import crm_data.shared.error.DatabaseErrors;
import crm_data.shared.security.Role;
import crm_data.support.tenant.TenantFixtures.Clinic;
import tools.jackson.databind.JsonNode;

/**
 * A RLS da agenda provada com SQL direto (nenhum filtro Hibernate no caminho): papel real, forjado, ausente e inválido;
 * o que a recepção pode ou não gravar; expediente e feriados; auditoria que exige usuário; e o isolamento entre clínicas.
 * O papel do banco só vale se o backend o informar; estes testes mostram o que o banco faz com cada valor.
 */
class SchedulingForgedRoleIT extends Phase2ItSupport {

	/** Uma clínica com um agendamento em cada estado e um horário/feriado da clínica. */
	private record World(Clinic clinic, String patient, UUID dentist, UUID receptionist, Map<String, UUID> byStatus) {
	}

	private static final String[] STATUSES = { "SCHEDULED", "CONFIRMED", "IN_PROGRESS", "COMPLETED", "CANCELED", "NO_SHOW" };

	private World world() throws Exception {
		Setup setup = openClinic();
		LocalDate monday = monday();
		Map<String, UUID> byStatus = new LinkedHashMap<>();
		int hour = 8;
		for (String status : STATUSES) {
			JsonNode created = read(mvc.perform(json(post("/api/v1/appointments"),
					"{\"patientId\":\"%s\",\"startsAt\":\"%s\",\"endsAt\":\"%s\"}".formatted(setup.patient(), at(monday, hour, 0), at(monday, hour, 30)))
					.with(as(setup.clinic(), Role.RECEPTIONIST))).andExpect(status().isCreated()).andReturn());
			UUID id = UUID.fromString(created.get("id").asString());
			if (!status.equals("SCHEDULED")) {
				adminSql(setup.clinic(), "UPDATE core.appointments SET status = '%s' WHERE id = '%s'".formatted(status, id));
			}
			byStatus.put(status, id);
			hour += hour == 11 ? 2 : 1; // pula o almoço
		}
		mvc.perform(json(post("/api/v1/clinic-holidays"), "{\"date\":\"2027-12-25\",\"name\":\"Natal da clínica\"}").with(as(setup.clinic(), Role.DENTIST_ADMIN)))
				.andExpect(status().isCreated());
		return new World(setup.clinic(), setup.patient(), userOf(setup.clinic(), Role.DENTIST_ADMIN), userOf(setup.clinic(), Role.RECEPTIONIST), byStatus);
	}

	private static String sqlState(Throwable error) {
		return DatabaseErrors.sqlState(error).orElse("?");
	}

	private int update(World w, UUID user, Role role, String sql, Object... args) {
		return asSession(w.clinic(), user, role, () -> jdbc.update(sql, args));
	}

	private long count(World w, UUID user, Role role, String table) {
		return asSession(w.clinic(), user, role, () -> jdbc.queryForObject("SELECT count(*) FROM core." + table, Long.class));
	}

	private static OffsetDateTime when(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

	private int insertAppointment(World w, UUID user, Role role, String status, int startHour) {
		Instant start = Instant.parse(at(monday().plusWeeks(5), startHour, 0));
		return update(w, user, role, """
				INSERT INTO core.appointments (id, clinic_id, patient_id, starts_at, ends_at, status, created_by)
				VALUES (?, ?, ?, ?, ?, ?, ?)""", UUID.randomUUID(), w.clinic().id(), UUID.fromString(w.patient()), when(start),
				when(start.plusSeconds(1800)), status, user);
	}

	// ---- leitura ----

	@Test
	void bothRealRolesReadTheAgendaAndTheConfigurationAndTheNationalHolidays() throws Exception {
		World w = world();

		for (Role role : Role.values()) {
			UUID user = role == Role.DENTIST_ADMIN ? w.dentist() : w.receptionist();
			assertThat(count(w, user, role, "appointments")).as("%s: agendamentos", role).isEqualTo(6);
			assertThat(count(w, user, role, "business_hours")).as("%s: expediente", role).isEqualTo(5);
			assertThat(count(w, user, role, "clinic_holidays")).as("%s: feriados da clínica", role).isEqualTo(1);
			assertThat(count(w, user, role, "holidays")).as("%s: feriados nacionais", role).isGreaterThanOrEqualTo(50);
		}
	}

	@Test
	void anAbsentOrInvalidRoleDeniesTheClinicDataButNeverTheNationalHolidays() throws Exception {
		World w = world();

		for (String table : new String[] { "appointments", "business_hours", "clinic_holidays" }) {
			assertThat(count(w, w.receptionist(), null, table)).as("papel ausente em %s", table).isZero();
			long invalid = asSession(w.clinic(), w.dentist(), Role.DENTIST_ADMIN, () -> {
				jdbc.queryForObject("SELECT set_config('app.role', 'ADMIN', true)", String.class);
				return jdbc.queryForObject("SELECT count(*) FROM core." + table, Long.class);
			});
			assertThat(invalid).as("papel inválido em %s", table).isZero();
		}
		assertThat(count(w, w.receptionist(), null, "holidays")).isGreaterThanOrEqualTo(50);
		assertThatThrownBy(() -> insertAppointment(w, w.dentist(), null, "SCHEDULED", 9)).satisfies(e -> assertThat(sqlState(e)).isEqualTo("42501"));
	}

	// ---- recepção forjada: agendamentos ----

	@Test
	void aForgedReceptionistCannotWriteTheDentistStatesNorChangeRowsInThem() throws Exception {
		World w = world();

		for (String status : new String[] { "IN_PROGRESS", "COMPLETED" }) {
			assertThatThrownBy(() -> insertAppointment(w, w.receptionist(), Role.RECEPTIONIST, status, 9)).as("INSERT %s", status)
					.satisfies(e -> assertThat(sqlState(e)).isEqualTo("42501"));
		}
		assertThatThrownBy(() -> update(w, w.receptionist(), Role.RECEPTIONIST, "UPDATE core.appointments SET status = 'IN_PROGRESS' WHERE id = ?",
				w.byStatus().get("CONFIRMED"))).satisfies(e -> assertThat(sqlState(e)).isEqualTo("42501"));
		assertThatThrownBy(() -> update(w, w.receptionist(), Role.RECEPTIONIST, "UPDATE core.appointments SET status = 'COMPLETED' WHERE id = ?",
				w.byStatus().get("SCHEDULED"))).satisfies(e -> assertThat(sqlState(e)).isEqualTo("42501"));

		for (String locked : new String[] { "IN_PROGRESS", "COMPLETED", "CANCELED", "NO_SHOW" }) {
			assertThat(update(w, w.receptionist(), Role.RECEPTIONIST, "UPDATE core.appointments SET status = 'SCHEDULED' WHERE id = ?",
					w.byStatus().get(locked))).as("UPDATE de linha %s", locked).isZero();
			assertThat(update(w, w.receptionist(), Role.RECEPTIONIST, "UPDATE core.appointments SET notes = 'x' WHERE id = ?",
					w.byStatus().get(locked))).as("edição de linha %s", locked).isZero();
		}
		assertThat(statusOf(w, "IN_PROGRESS")).isEqualTo("IN_PROGRESS");
		assertThat(statusOf(w, "COMPLETED")).isEqualTo("COMPLETED");
		assertThat(statusOf(w, "CANCELED")).as("recepção não reativa").isEqualTo("CANCELED");
	}

	@Test
	void aForgedReceptionistKeepsTheOperationalWrites() throws Exception {
		World w = world();

		assertThat(insertAppointment(w, w.receptionist(), Role.RECEPTIONIST, "SCHEDULED", 9)).isEqualTo(1);
		assertThat(update(w, w.receptionist(), Role.RECEPTIONIST, "UPDATE core.appointments SET status = 'CONFIRMED' WHERE id = ?",
				w.byStatus().get("SCHEDULED"))).isEqualTo(1);
		assertThat(update(w, w.receptionist(), Role.RECEPTIONIST, "UPDATE core.appointments SET status = 'CANCELED' WHERE id = ?",
				w.byStatus().get("CONFIRMED"))).isEqualTo(1);
		assertThat(update(w, w.receptionist(), Role.RECEPTIONIST, "UPDATE core.appointments SET status = 'NO_SHOW' WHERE id = ?",
				w.byStatus().get("SCHEDULED"))).as("a linha confirmada na etapa anterior ainda é editável (CONFIRMED -> NO_SHOW)").isEqualTo(1);
		assertThat(update(w, w.receptionist(), Role.RECEPTIONIST, "UPDATE core.appointments SET status = 'CONFIRMED' WHERE id = ?",
				w.byStatus().get("SCHEDULED"))).as("depois da falta a linha trava para a recepção").isZero();
	}

	@Test
	void theDentistDoesNotNeedTheStatesInOrderBecauseTheDatabaseDoesNotValidateTheSequence() throws Exception {
		World w = world();

		assertThat(update(w, w.dentist(), Role.DENTIST_ADMIN, "UPDATE core.appointments SET status = 'SCHEDULED' WHERE id = ?",
				w.byStatus().get("COMPLETED"))).as("COMPLETED -> SCHEDULED: a ordem é do backend").isEqualTo(1);
		assertThat(update(w, w.dentist(), Role.DENTIST_ADMIN, "UPDATE core.appointments SET status = 'IN_PROGRESS' WHERE id = ?",
				w.byStatus().get("CONFIRMED"))).isEqualTo(1);
	}

	private String statusOf(World w, String originalStatus) {
		return asSession(w.clinic(), w.dentist(), Role.DENTIST_ADMIN,
				() -> jdbc.queryForObject("SELECT status FROM core.appointments WHERE id = ?", String.class, w.byStatus().get(originalStatus)));
	}

	// ---- recepção forjada: expediente e feriados ----

	@Test
	void aForgedReceptionistCannotWriteTheScheduleNorTheHolidays() throws Exception {
		World w = world();

		assertThatThrownBy(() -> update(w, w.receptionist(), Role.RECEPTIONIST, """
				INSERT INTO core.business_hours (id, clinic_id, weekday, is_open, created_by) VALUES (?, ?, 7, false, ?)""", UUID.randomUUID(),
				w.clinic().id(), w.receptionist())).satisfies(e -> assertThat(sqlState(e)).isEqualTo("42501"));
		assertThat(update(w, w.receptionist(), Role.RECEPTIONIST, "UPDATE core.business_hours SET is_open = false, opens_at = NULL, closes_at = NULL, lunch_starts_at = NULL, lunch_ends_at = NULL WHERE clinic_id = ?",
				w.clinic().id())).isZero();
		assertThatThrownBy(() -> update(w, w.receptionist(), Role.RECEPTIONIST, """
				INSERT INTO core.clinic_holidays (id, clinic_id, holiday_date, name, created_by) VALUES (?, ?, DATE '2027-01-02', 'Recesso', ?)""",
				UUID.randomUUID(), w.clinic().id(), w.receptionist())).satisfies(e -> assertThat(sqlState(e)).isEqualTo("42501"));
		assertThat(update(w, w.receptionist(), Role.RECEPTIONIST, "UPDATE core.clinic_holidays SET name = 'Hackeado' WHERE clinic_id = ?", w.clinic().id())).isZero();
		assertThat(update(w, w.receptionist(), Role.RECEPTIONIST, "DELETE FROM core.clinic_holidays WHERE clinic_id = ?", w.clinic().id())).isZero();

		assertThat(count(w, w.dentist(), Role.DENTIST_ADMIN, "clinic_holidays")).isEqualTo(1);
		assertThat(count(w, w.dentist(), Role.DENTIST_ADMIN, "business_hours")).isEqualTo(5);
	}

	@Test
	void nobodyWritesTheNationalHolidaysAndNobodyDeletesAppointmentsOrHours() throws Exception {
		World w = world();

		for (Role role : Role.values()) {
			UUID user = role == Role.DENTIST_ADMIN ? w.dentist() : w.receptionist();
			assertThatThrownBy(() -> update(w, user, role, "INSERT INTO core.holidays (holiday_date, name) VALUES (DATE '2040-01-01', 'Falso')"))
					.as("%s insere feriado nacional", role).satisfies(e -> assertThat(sqlState(e)).isEqualTo("42501"));
			assertThatThrownBy(() -> update(w, user, role, "DELETE FROM core.appointments WHERE clinic_id = ?", w.clinic().id()))
					.as("%s apaga agendamentos", role).satisfies(e -> assertThat(sqlState(e)).isEqualTo("42501"));
			assertThatThrownBy(() -> update(w, user, role, "DELETE FROM core.business_hours WHERE clinic_id = ?", w.clinic().id()))
					.as("%s apaga expediente", role).satisfies(e -> assertThat(sqlState(e)).isEqualTo("42501"));
		}
		assertThat(update(w, w.dentist(), Role.DENTIST_ADMIN, "DELETE FROM core.clinic_holidays WHERE clinic_id = ?", w.clinic().id()))
				.as("só o dentista apaga feriado da clínica").isEqualTo(1);
	}

	// ---- auditoria exige usuário ----

	@Test
	void withoutAUserTheWriteIsRefusedAndNothingChanges() throws Exception {
		World w = world();

		assertThatThrownBy(() -> update(w, null, Role.DENTIST_ADMIN, "UPDATE core.appointments SET notes = 'sem usuário' WHERE id = ?",
				w.byStatus().get("SCHEDULED"))).satisfies(e -> assertThat(sqlState(e)).isEqualTo("28000"));
		assertThatThrownBy(() -> insertAppointment(w, null, Role.DENTIST_ADMIN, "SCHEDULED", 9)).satisfies(e -> assertThat(sqlState(e)).isEqualTo("42501"));

		assertThat(asSession(w.clinic(), w.dentist(), Role.DENTIST_ADMIN,
				() -> jdbc.queryForObject("SELECT notes FROM core.appointments WHERE id = ?", String.class, w.byStatus().get("SCHEDULED")))).isNull();
		assertThat(count(w, w.dentist(), Role.DENTIST_ADMIN, "appointments")).isEqualTo(6);
	}

	@Test
	void theScheduleAndTheHolidaysAreNotAuditedAndTheAppointmentsAre() throws Exception {
		World w = world();
		mvc.perform(json(put("/api/v1/business-hours"), "{\"days\":[{\"weekday\":7,\"isOpen\":false}]}").with(as(w.clinic(), Role.DENTIST_ADMIN)))
				.andExpect(status().isOk());

		var admin = crm_data.support.DatabaseContainer.admin();
		assertThat(admin.queryForObject("SELECT count(*) FROM core.audit_log WHERE clinic_id = ? AND table_name IN ('business_hours','clinic_holidays','holidays')",
				Long.class, w.clinic().id())).isZero();
		assertThat(admin.queryForObject("SELECT count(*) FROM core.audit_log WHERE clinic_id = ? AND table_name = 'appointments' AND action = 'INSERT'",
				Long.class, w.clinic().id())).isEqualTo(6);
	}

	// ---- isolamento entre clínicas, sem filtro Hibernate ----

	@Test
	void aSessionOfOneClinicNeverSeesOrWritesTheOtherEvenWithRawSql() throws Exception {
		World a = world();
		World b = world();
		UUID bAppointment = b.byStatus().get("SCHEDULED");

		for (Role role : Role.values()) {
			UUID user = role == Role.DENTIST_ADMIN ? a.dentist() : a.receptionist();
			assertThat(count(a, user, role, "appointments")).as("%s: só as 6 da própria clínica", role).isEqualTo(6);
			assertThat(asSession(a.clinic(), user, role, () -> jdbc.queryForObject("SELECT count(*) FROM core.appointments WHERE clinic_id = ?", Long.class,
					b.clinic().id()))).as("%s: nenhuma da outra", role).isZero();
			assertThat(asSession(a.clinic(), user, role, () -> jdbc.queryForObject("SELECT count(*) FROM core.appointments WHERE patient_id = ?", Long.class,
					UUID.fromString(b.patient())))).as("%s: histórico do paciente da outra clínica", role).isZero();
			assertThat(asSession(a.clinic(), user, role, () -> jdbc.queryForObject("SELECT count(*) FROM core.business_hours WHERE clinic_id = ?", Long.class,
					b.clinic().id()))).isZero();
			assertThat(asSession(a.clinic(), user, role, () -> jdbc.queryForObject("SELECT count(*) FROM core.clinic_holidays WHERE clinic_id = ?", Long.class,
					b.clinic().id()))).isZero();
		}
		assertThat(update(a, a.dentist(), Role.DENTIST_ADMIN, "UPDATE core.appointments SET notes = 'invasão' WHERE id = ?", bAppointment)).isZero();
		assertThat(update(a, a.dentist(), Role.DENTIST_ADMIN, "UPDATE core.business_hours SET is_open = false, opens_at = NULL, closes_at = NULL, lunch_starts_at = NULL, lunch_ends_at = NULL WHERE clinic_id = ?",
				b.clinic().id())).isZero();
		assertThat(update(a, a.dentist(), Role.DENTIST_ADMIN, "UPDATE core.clinic_holidays SET name = 'invasão' WHERE clinic_id = ?", b.clinic().id())).isZero();
		Instant start = Instant.parse(at(monday().plusWeeks(6), 9, 0));
		assertThatThrownBy(() -> update(a, a.dentist(), Role.DENTIST_ADMIN, """
				INSERT INTO core.appointments (id, clinic_id, patient_id, starts_at, ends_at, status, created_by)
				VALUES (?, ?, ?, ?, ?, 'SCHEDULED', ?)""", UUID.randomUUID(), b.clinic().id(), UUID.fromString(b.patient()), when(start), when(start.plusSeconds(1800)),
				a.dentist())).as("INSERT em nome da outra clínica").satisfies(e -> assertThat(sqlState(e)).isEqualTo("42501"));
		assertThatThrownBy(() -> insertAppointmentForForeignPatient(a, b)).as("paciente da outra clínica na FK composta")
				.satisfies(e -> assertThat(sqlState(e)).isEqualTo("23503"));

		assertThat(statusOf(b, "SCHEDULED")).isEqualTo("SCHEDULED");
	}

	private int insertAppointmentForForeignPatient(World a, World b) {
		Instant start = Instant.parse(at(monday().plusWeeks(7), 9, 0));
		return update(a, a.dentist(), Role.DENTIST_ADMIN, """
				INSERT INTO core.appointments (id, clinic_id, patient_id, starts_at, ends_at, status, created_by)
				VALUES (?, ?, ?, ?, ?, 'SCHEDULED', ?)""", UUID.randomUUID(), a.clinic().id(), UUID.fromString(b.patient()), when(start),
				when(start.plusSeconds(1800)), a.dentist());
	}
}
