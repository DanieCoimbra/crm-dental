package crm_data.phase2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import crm_data.scheduling.domain.Appointment;
import crm_data.scheduling.repository.AppointmentRepository;
import crm_data.shared.security.Role;
import crm_data.support.DatabaseContainer;
import crm_data.support.tenant.TenantFixtures.Clinic;
import tools.jackson.databind.JsonNode;

/**
 * F02 contra o PostgreSQL real (V001–V021): expediente, feriados, aviso de agendamentos afetados (inclusive
 * {@code dryRun}), papéis e isolamento entre clínicas.
 */
class BusinessHoursHolidaysIT extends Phase2ItSupport {

	private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");

	@Autowired
	AppointmentRepository appointments;

	// ---- auxiliares ----

	/** Segunda-feira daqui a pelo menos duas semanas: sempre no futuro, em qualquer dia em que o teste rode. */
	private static LocalDate futureMonday() {
		return LocalDate.now(SAO_PAULO).with(TemporalAdjusters.next(DayOfWeek.MONDAY)).plusWeeks(1);
	}

	private static Instant at(LocalDate date, int hour, int minute) {
		return LocalDateTime.of(date, LocalTime.of(hour, minute)).atZone(SAO_PAULO).toInstant();
	}

	private static String day(int weekday, String opens, String closes, String lunchStarts, String lunchEnds, Long version) {
		return "{\"weekday\":%d,\"isOpen\":true,\"opensAt\":\"%s\",\"closesAt\":\"%s\"%s%s}".formatted(weekday, opens, closes,
				lunchStarts == null ? "" : ",\"lunchStartsAt\":\"%s\",\"lunchEndsAt\":\"%s\"".formatted(lunchStarts, lunchEnds),
				version == null ? "" : ",\"version\":" + version);
	}

	private static String body(String... days) {
		return "{\"days\":[" + String.join(",", days) + "]}";
	}

	private JsonNode putHours(Clinic clinic, String body, boolean dryRun, int expectedStatus) throws Exception {
		MvcResult result = mvc.perform(json(put("/api/v1/business-hours").param("dryRun", Boolean.toString(dryRun)), body)
				.with(as(clinic, Role.DENTIST_ADMIN))).andExpect(status().is(expectedStatus)).andReturn();
		return read(result);
	}

	private JsonNode week(Clinic clinic, Role role) throws Exception {
		return read(mvc.perform(get("/api/v1/business-hours").with(as(clinic, role))).andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store")).andReturn());
	}

	private UUID patientOf(Clinic clinic) throws Exception {
		String patient = "{\"name\":\"Maria Souza\",\"birthDate\":\"1988-04-12\",\"phone\":\"11987654321\"}";
		return UUID.fromString(read(mvc.perform(json(post("/api/v1/patients"), patient).with(as(clinic, Role.RECEPTIONIST)))
				.andExpect(status().isCreated()).andReturn()).get("id").asString());
	}

	private Appointment book(Clinic clinic, UUID patient, Instant start, int minutes) {
		return asRole(clinic, Role.DENTIST_ADMIN, () -> appointments.saveAndFlush(
				new Appointment(UUID.randomUUID(), patient, start, start.plusSeconds(minutes * 60L), null, userOf(clinic, Role.DENTIST_ADMIN))));
	}

	// ---- expediente ----

	@Test
	void aNewClinicHasSevenUnconfiguredClosedDays() throws Exception {
		Clinic clinic = newClinic();

		JsonNode week = week(clinic, Role.RECEPTIONIST);

		assertThat(week).hasSize(7);
		for (int i = 0; i < 7; i++) {
			assertThat(week.get(i).get("weekday").asInt()).isEqualTo(i + 1);
			assertThat(week.get(i).get("configured").asBoolean()).isFalse();
			assertThat(week.get(i).get("isOpen").asBoolean()).isFalse();
			assertThat(week.get(i).get("version").isNull()).isTrue();
		}
	}

	@Test
	void createThenUpdateWithTheVersionAndTheOtherDaysStayUntouched() throws Exception {
		Clinic clinic = newClinic();

		JsonNode created = putHours(clinic, body(day(1, "08:00", "18:00", "12:00", "13:00", null), "{\"weekday\":7,\"isOpen\":false}"), false, 200);

		assertThat(created.get("saved").asBoolean()).isTrue();
		JsonNode monday = created.get("days").get(0);
		assertThat(monday.get("opensAt").asString()).isEqualTo("08:00");
		assertThat(monday.get("lunchEndsAt").asString()).isEqualTo("13:00");
		assertThat(monday.get("version").asLong()).isZero();
		assertThat(DatabaseContainer.admin().queryForList(
				"SELECT opens_at::text || '|' || closes_at::text || '|' || lunch_starts_at::text || '|' || lunch_ends_at::text FROM core.business_hours WHERE clinic_id = ? AND weekday = 1",
				String.class, clinic.id())).as("o banco guarda exatamente o que o dentista enviou, sem deslocamento de fuso")
						.containsExactly("08:00:00|18:00:00|12:00:00|13:00:00");
		assertThat(created.get("days").get(6).get("configured").asBoolean()).isTrue();
		assertThat(created.get("days").get(1).get("configured").asBoolean()).isFalse();

		JsonNode updated = putHours(clinic, body(day(1, "09:00", "17:00", null, null, 0L)), false, 200);
		assertThat(updated.get("days").get(0).get("version").asLong()).isEqualTo(1);
		JsonNode reread = week(clinic, Role.DENTIST_ADMIN);
		assertThat(reread.get(0).get("opensAt").asString()).isEqualTo("09:00");
		assertThat(reread.get(0).get("lunchStartsAt").isNull()).isTrue();
		assertThat(reread.get(6).get("configured").asBoolean()).as("domingo continua como foi gravado").isTrue();
	}

	@Test
	void staleMissingOrUnexpectedVersionsAreConflictsAndWriteNothing() throws Exception {
		Clinic clinic = newClinic();
		putHours(clinic, body(day(1, "08:00", "18:00", null, null, null)), false, 200);

		putHours(clinic, body(day(1, "09:00", "17:00", null, null, 5L)), false, 409);
		putHours(clinic, body(day(1, "09:00", "17:00", null, null, null)), false, 409);
		putHours(clinic, body(day(2, "09:00", "17:00", null, null, 0L)), false, 409);
		putHours(clinic, body(day(2, "09:00", "17:00", null, null, null), day(1, "09:00", "17:00", null, null, 9L)), false, 409);

		JsonNode reread = week(clinic, Role.DENTIST_ADMIN);
		assertThat(reread.get(0).get("opensAt").asString()).isEqualTo("08:00");
		assertThat(reread.get(1).get("configured").asBoolean()).as("a terça do lote que falhou não foi criada").isFalse();
	}

	@Test
	void closingADayClearsItsTimesAndFailsTheDatabaseChecksAreNeverReached() throws Exception {
		Clinic clinic = newClinic();
		putHours(clinic, body(day(2, "08:00", "18:00", "12:00", "13:00", null)), false, 200);

		putHours(clinic, body("{\"weekday\":2,\"isOpen\":false,\"version\":0}"), false, 200);

		JsonNode tuesday = week(clinic, Role.DENTIST_ADMIN).get(1);
		assertThat(tuesday.get("configured").asBoolean()).isTrue();
		assertThat(tuesday.get("isOpen").asBoolean()).isFalse();
		assertThat(tuesday.get("opensAt").isNull()).isTrue();
		assertThat(tuesday.get("lunchStartsAt").isNull()).isTrue();
	}

	@Test
	void receptionistCannotWriteAndNothingIsStored() throws Exception {
		Clinic clinic = newClinic();

		mvc.perform(json(put("/api/v1/business-hours"), body(day(1, "08:00", "18:00", null, null, null))).with(as(clinic, Role.RECEPTIONIST)))
				.andExpect(status().isForbidden());

		assertThat(week(clinic, Role.DENTIST_ADMIN).get(0).get("configured").asBoolean()).isFalse();
	}

	@Test
	void clinicsNeverSeeEachOthersHours() throws Exception {
		Clinic a = newClinic();
		Clinic b = newClinic();
		putHours(a, body(day(3, "07:00", "19:00", null, null, null)), false, 200);

		assertThat(week(b, Role.DENTIST_ADMIN).get(2).get("configured").asBoolean()).isFalse();
		JsonNode created = putHours(b, body(day(3, "10:00", "14:00", null, null, null)), false, 200);
		assertThat(created.get("days").get(2).get("opensAt").asString()).isEqualTo("10:00");
		assertThat(week(a, Role.DENTIST_ADMIN).get(2).get("opensAt").asString()).isEqualTo("07:00");
	}

	@Test
	void aClosingTimeOf2400WrittenOutsideTheApiIsReadAs2359() throws Exception {
		Clinic clinic = newClinic();
		adminSql(clinic, "INSERT INTO core.business_hours (id, clinic_id, weekday, is_open, opens_at, closes_at, created_by) VALUES ('%s', '%s', 4, true, '08:00', '24:00:00', '%s')"
				.formatted(UUID.randomUUID(), clinic.id(), userOf(clinic, Role.DENTIST_ADMIN)));

		JsonNode thursday = week(clinic, Role.DENTIST_ADMIN).get(3);

		assertThat(thursday.get("isOpen").asBoolean()).isTrue();
		assertThat(thursday.get("closesAt").asString()).isEqualTo("23:59");
	}

	// ---- aviso de agendamentos afetados ----

	@Test
	void changingTheHoursWarnsWhichBookingsAreAffectedAndDryRunNeverWrites() throws Exception {
		Clinic clinic = newClinic();
		UUID patient = patientOf(clinic);
		LocalDate monday = futureMonday();
		putHours(clinic, body(day(1, "08:00", "18:00", null, null, null)), false, 200);
		Appointment morning = book(clinic, patient, at(monday, 9, 0), 60);
		Appointment lunchtime = book(clinic, patient, at(monday, 12, 15), 30);
		Appointment canceled = book(clinic, patient, at(monday, 12, 45), 15);
		adminSql(clinic, "UPDATE core.appointments SET status = 'CANCELED' WHERE id = '" + canceled.getId() + "'");
		Appointment past = book(clinic, patient, at(LocalDate.of(2020, 1, 6), 12, 15), 30); // segunda-feira de 2020

		// o aviso sem gravar
		JsonNode dry = putHours(clinic, body(day(1, "08:00", "18:00", "12:00", "13:00", 0L)), true, 200);

		assertThat(dry.get("saved").asBoolean()).isFalse();
		assertThat(dry.get("affectedCount").asInt()).isEqualTo(1);
		assertThat(dry.get("truncated").asBoolean()).isFalse();
		JsonNode item = dry.get("affectedAppointments").get(0);
		assertThat(item.get("id").asString()).isEqualTo(lunchtime.getId().toString());
		assertThat(item.get("reason").asString()).isEqualTo("LUNCH");
		assertThat(item.get("status").asString()).isEqualTo("SCHEDULED");
		assertThat(item.get("patient").get("name").asString()).isEqualTo("Maria Souza");
		assertThat(item.get("patient").get("phone").asString()).isEqualTo("11987654321");
		JsonNode unchanged = week(clinic, Role.DENTIST_ADMIN).get(0);
		assertThat(unchanged.get("lunchStartsAt").isNull()).as("dryRun não gravou o almoço").isTrue();
		assertThat(unchanged.get("version").asLong()).isZero();

		// grava de verdade: o mesmo aviso, e a mudança não é bloqueada pelos agendamentos
		JsonNode saved = putHours(clinic, body(day(1, "08:00", "18:00", "12:00", "13:00", 0L)), false, 200);
		assertThat(saved.get("saved").asBoolean()).isTrue();
		assertThat(saved.get("affectedAppointments").get(0).get("id").asString()).isEqualTo(lunchtime.getId().toString());
		assertThat(week(clinic, Role.DENTIST_ADMIN).get(0).get("lunchStartsAt").asString()).isEqualTo("12:00");

		// fechar o dia afeta os dois agendamentos futuros em aberto; cancelado e passado nunca entram
		JsonNode closed = putHours(clinic, body("{\"weekday\":1,\"isOpen\":false,\"version\":1}"), false, 200);
		assertThat(closed.get("affectedCount").asInt()).isEqualTo(2);
		assertThat(closed.get("affectedAppointments")).extracting(n -> n.get("id").asString())
				.containsExactly(morning.getId().toString(), lunchtime.getId().toString());
		assertThat(closed.get("affectedAppointments")).extracting(n -> n.get("reason").asString()).containsOnly("CLOSED_DAY");

		// nada foi cancelado nem remarcado pelo aviso
		Appointment stillThere = asRole(clinic, Role.DENTIST_ADMIN, () -> appointments.findById(lunchtime.getId()).orElseThrow());
		assertThat(stillThere.getStatus().name()).isEqualTo("SCHEDULED");
		assertThat(stillThere.getVersion()).isEqualTo(lunchtime.getVersion());
		assertThat(past.getId()).isNotNull();
	}

	@Test
	void dryRunWithAStaleVersionIsStillConflictAndOtherClinicsBookingsNeverAppear() throws Exception {
		Clinic a = newClinic();
		Clinic b = newClinic();
		LocalDate monday = futureMonday();
		putHours(a, body(day(1, "08:00", "18:00", null, null, null)), false, 200);
		book(b, patientOf(b), at(monday, 9, 0), 60);

		putHours(a, body(day(1, "09:00", "17:00", null, null, 7L)), true, 409);
		JsonNode closed = putHours(a, body("{\"weekday\":1,\"isOpen\":false,\"version\":0}"), true, 200);

		assertThat(closed.get("affectedCount").asInt()).as("o agendamento é da clínica B").isZero();
		assertThat(closed.get("affectedAppointments")).isEmpty();
	}

	// ---- feriados ----

	@Test
	void holidaysListIsTheUnionOfNationalAndClinicHolidaysAndItsWindowIsLimited() throws Exception {
		Clinic clinic = newClinic();
		mvc.perform(json(post("/api/v1/clinic-holidays"), "{\"date\":\"2026-12-24\",\"name\":\"Recesso\"}").with(as(clinic, Role.DENTIST_ADMIN)))
				.andExpect(status().isCreated());

		mvc.perform(get("/api/v1/holidays").param("from", "2026-12-01").param("to", "2026-12-31").with(as(clinic, Role.RECEPTIONIST)))
				.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].date").value("2026-12-24")).andExpect(jsonPath("$[0].source").value("CLINIC"))
				.andExpect(jsonPath("$[0].id").exists()).andExpect(jsonPath("$[0].version").value(0))
				.andExpect(jsonPath("$[1].date").value("2026-12-25")).andExpect(jsonPath("$[1].name").value("Natal"))
				.andExpect(jsonPath("$[1].source").value("NATIONAL")).andExpect(jsonPath("$[1].id").doesNotExist());
		mvc.perform(get("/api/v1/holidays").param("from", "2026-01-01").param("to", "2027-01-02").with(as(clinic, Role.RECEPTIONIST)))
				.andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/holidays").param("from", "2026-12-31").param("to", "2026-12-01").with(as(clinic, Role.RECEPTIONIST)))
				.andExpect(status().isBadRequest());
	}

	@Test
	void theSeedHasTheTenNationalHolidaysOf2026() throws Exception {
		Clinic clinic = newClinic();

		mvc.perform(get("/api/v1/holidays").param("from", "2026-01-01").param("to", "2026-12-31").with(as(clinic, Role.DENTIST_ADMIN)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(10))
				.andExpect(jsonPath("$[?(@.date=='2026-04-03')].name").exists());
	}

	@Test
	void clinicHolidayCrudWithVersionDuplicatesAndIsolation() throws Exception {
		Clinic a = newClinic();
		Clinic b = newClinic();
		String create = "{\"date\":\"2026-11-20\",\"name\":\"Recesso\"}";

		JsonNode created = read(mvc.perform(json(post("/api/v1/clinic-holidays"), create).with(as(a, Role.DENTIST_ADMIN)))
				.andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store")).andReturn());
		String id = created.get("holiday").get("id").asString();
		mvc.perform(json(post("/api/v1/clinic-holidays"), create).with(as(a, Role.DENTIST_ADMIN))).andExpect(status().isConflict());
		mvc.perform(json(post("/api/v1/clinic-holidays"), create).with(as(b, Role.DENTIST_ADMIN))).andExpect(status().isCreated());

		mvc.perform(json(put("/api/v1/clinic-holidays/" + id), "{\"date\":\"2026-11-21\",\"name\":\"Recesso 2\",\"version\":0}")
				.with(as(a, Role.DENTIST_ADMIN))).andExpect(status().isOk()).andExpect(jsonPath("$.holiday.version").value(1))
				.andExpect(jsonPath("$.holiday.date").value("2026-11-21"));
		mvc.perform(json(put("/api/v1/clinic-holidays/" + id), "{\"date\":\"2026-11-22\",\"name\":\"Recesso 3\",\"version\":0}")
				.with(as(a, Role.DENTIST_ADMIN))).andExpect(status().isConflict());
		mvc.perform(json(put("/api/v1/clinic-holidays/" + id), "{\"date\":\"2026-11-22\",\"name\":\"Recesso 3\",\"version\":1}")
				.with(as(b, Role.DENTIST_ADMIN))).andExpect(status().isNotFound());
		mvc.perform(delete("/api/v1/clinic-holidays/" + id).with(as(b, Role.DENTIST_ADMIN))).andExpect(status().isNotFound());

		mvc.perform(delete("/api/v1/clinic-holidays/" + id).with(as(a, Role.DENTIST_ADMIN))).andExpect(status().isNoContent());
		mvc.perform(delete("/api/v1/clinic-holidays/" + id).with(as(a, Role.DENTIST_ADMIN))).andExpect(status().isNotFound());
		mvc.perform(json(post("/api/v1/clinic-holidays"), "{\"date\":\"2026-11-21\",\"name\":\"De novo\"}").with(as(a, Role.DENTIST_ADMIN)))
				.andExpect(status().isCreated());
	}

	@Test
	void movingAHolidayOntoAnExistingDateIs409() throws Exception {
		Clinic clinic = newClinic();
		mvc.perform(json(post("/api/v1/clinic-holidays"), "{\"date\":\"2026-11-20\",\"name\":\"Um\"}").with(as(clinic, Role.DENTIST_ADMIN)))
				.andExpect(status().isCreated());
		String other = read(mvc.perform(json(post("/api/v1/clinic-holidays"), "{\"date\":\"2026-11-21\",\"name\":\"Dois\"}")
				.with(as(clinic, Role.DENTIST_ADMIN))).andExpect(status().isCreated()).andReturn()).get("holiday").get("id").asString();

		mvc.perform(json(put("/api/v1/clinic-holidays/" + other), "{\"date\":\"2026-11-20\",\"name\":\"Dois\",\"version\":0}")
				.with(as(clinic, Role.DENTIST_ADMIN))).andExpect(status().isConflict())
				.andExpect(jsonPath("$.detail").value("Já existe feriado nesta data."));
	}

	@Test
	void receptionistCannotWriteHolidays() throws Exception {
		Clinic clinic = newClinic();
		String id = read(mvc.perform(json(post("/api/v1/clinic-holidays"), "{\"date\":\"2026-11-20\",\"name\":\"Recesso\"}")
				.with(as(clinic, Role.DENTIST_ADMIN))).andExpect(status().isCreated()).andReturn()).get("holiday").get("id").asString();

		mvc.perform(json(post("/api/v1/clinic-holidays"), "{\"date\":\"2026-11-25\",\"name\":\"Outro\"}").with(as(clinic, Role.RECEPTIONIST)))
				.andExpect(status().isForbidden());
		mvc.perform(json(put("/api/v1/clinic-holidays/" + id), "{\"date\":\"2026-11-26\",\"name\":\"Outro\",\"version\":0}")
				.with(as(clinic, Role.RECEPTIONIST))).andExpect(status().isForbidden());
		mvc.perform(delete("/api/v1/clinic-holidays/" + id).with(as(clinic, Role.RECEPTIONIST))).andExpect(status().isForbidden());

		mvc.perform(get("/api/v1/holidays").param("from", "2026-11-01").param("to", "2026-11-30").with(as(clinic, Role.RECEPTIONIST)))
				.andExpect(status().isOk()).andExpect(jsonPath("$[?(@.name=='Recesso')]").exists());
	}

	@Test
	void aHolidayWarnsTheOpenBookingsOnItsDateAndDeleteDoesNot() throws Exception {
		Clinic clinic = newClinic();
		UUID patient = patientOf(clinic);
		LocalDate monday = futureMonday();
		Appointment onDay = book(clinic, patient, at(monday, 9, 0), 60);
		book(clinic, patient, at(monday.plusDays(1), 9, 0), 60);
		Appointment canceled = book(clinic, patient, at(monday, 15, 0), 30);
		adminSql(clinic, "UPDATE core.appointments SET status = 'CANCELED' WHERE id = '" + canceled.getId() + "'");

		JsonNode created = read(mvc.perform(json(post("/api/v1/clinic-holidays"),
				"{\"date\":\"%s\",\"name\":\"Recesso\"}".formatted(monday)).with(as(clinic, Role.DENTIST_ADMIN)))
				.andExpect(status().isCreated()).andReturn());

		assertThat(created.get("affectedCount").asInt()).isEqualTo(1);
		assertThat(created.get("affectedAppointments").get(0).get("id").asString()).isEqualTo(onDay.getId().toString());
		assertThat(created.get("affectedAppointments").get(0).get("reason").asString()).isEqualTo("HOLIDAY");
		assertThat(created.get("affectedAppointments").get(0).get("patient").get("name").asString()).isEqualTo("Maria Souza");

		String id = created.get("holiday").get("id").asString();
		mvc.perform(json(put("/api/v1/clinic-holidays/" + id), "{\"date\":\"%s\",\"name\":\"Recesso\",\"version\":0}".formatted(monday.plusDays(2)))
				.with(as(clinic, Role.DENTIST_ADMIN))).andExpect(status().isOk()).andExpect(jsonPath("$.affectedCount").value(0));
		mvc.perform(delete("/api/v1/clinic-holidays/" + id).with(as(clinic, Role.DENTIST_ADMIN))).andExpect(status().isNoContent());
	}
}
