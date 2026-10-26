package crm_data.phase2;

import static crm_data.phase2.AgendaDates.at;
import static crm_data.phase2.AgendaDates.monday;
import static crm_data.phase2.AgendaDates.mondays;
import static crm_data.phase2.AgendaDates.pastMonday;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import crm_data.shared.security.Role;
import crm_data.support.tenant.PatientFixtures;
import crm_data.support.tenant.TenantFixtures.Clinic;
import tools.jackson.databind.JsonNode;

/**
 * F03 contra o PostgreSQL real (V001–V021): criação com conflito decidido pela constraint (inclusive em paralelo),
 * regras de expediente/feriado/passado por papel, estados do paciente, agenda por janela local e remarcação.
 */
class AppointmentsIT extends Phase2ItSupport {

	// ---- auxiliares ----

	private static String body(String patient, String start, String end, String extra) {
		return "{\"patientId\":\"%s\",\"startsAt\":\"%s\",\"endsAt\":\"%s\"%s}".formatted(patient, start, end, extra);
	}

	private MockHttpServletRequestBuilder create(Setup setup, Role role, String start, String end, String extra) {
		return json(post("/api/v1/appointments"), body(setup.patient(), start, end, extra)).with(as(setup.clinic(), role));
	}

	private JsonNode created(Setup setup, Role role, String start, String end) throws Exception {
		return read(mvc.perform(create(setup, role, start, end, "")).andExpect(status().isCreated()).andReturn());
	}

	private void setStatus(Clinic clinic, String appointmentId, String status) {
		adminSql(clinic, "UPDATE core.appointments SET status = '%s' WHERE id = '%s'".formatted(status, appointmentId));
	}

	private String agenda(Setup setup, Role role, String from, String to, String... statuses) throws Exception {
		MockHttpServletRequestBuilder request = get("/api/v1/appointments").param("from", from).param("to", to);
		for (String status : statuses) {
			request = request.param("status", status);
		}
		return mvc.perform(request.with(as(setup.clinic(), role))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
	}

	// ---- criar ----

	@Test
	void bothRolesCreateAndTheResponseCarriesTheDatabaseGeneratedTimestamps() throws Exception {
		Setup setup = openClinic();
		LocalDate monday = monday();

		for (Role role : Role.values()) {
			int hour = role == Role.DENTIST_ADMIN ? 9 : 10;
			MvcResult result = mvc.perform(create(setup, role, at(monday, hour, 0), at(monday, hour, 45), ",\"notes\":\"Retorno\""))
					.andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store"))
					.andExpect(header().exists("Location")).andReturn();
			JsonNode created = read(result);

			assertThat(created.get("status").asString()).isEqualTo("SCHEDULED");
			assertThat(created.get("createdBy").asString()).isEqualTo(userOf(setup.clinic(), role).toString());
			assertThat(created.get("version").asLong()).isZero();
			assertThat(created.get("createdAt").isNull()).isFalse();
			assertThat(created.get("updatedAt").isNull()).isFalse();
			assertThat(created.get("patient").get("name").asString()).isEqualTo("Maria Souza");
			assertThat(created.has("clinicId")).isFalse();
			assertThat(created.has("procedureTypeId")).isFalse();
			mvc.perform(get(result.getResponse().getHeader("Location")).with(as(setup.clinic(), Role.RECEPTIONIST)))
					.andExpect(status().isOk()).andExpect(jsonPath("$.id").value(created.get("id").asString()))
					.andExpect(jsonPath("$.notes").value("Retorno"));
		}
	}

	@Test
	void serverOwnedFieldsInTheBodyAreIgnored() throws Exception {
		Setup setup = openClinic();
		// procedureTypeId deixou de ser ignorado na Fase 3 (F02): é campo da API e é validado (ver ProcedureTypesIT)
		String extra = ",\"status\":\"COMPLETED\",\"createdBy\":\"%s\",\"clinicId\":\"%s\",\"id\":\"%s\""
				.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
		LocalDate monday = monday();

		JsonNode created = read(mvc.perform(create(setup, Role.RECEPTIONIST, at(monday, 9, 0), at(monday, 10, 0), extra))
				.andExpect(status().isCreated()).andReturn());

		assertThat(created.get("status").asString()).isEqualTo("SCHEDULED");
		assertThat(created.get("createdBy").asString()).isEqualTo(userOf(setup.clinic(), Role.RECEPTIONIST).toString());
		assertThat(created.get("id").asString()).isNotEqualTo(extra);
	}

	@Test
	void overlapsAreConflictsAndConsecutiveSlotsOtherClinicsAndCanceledOnesAreNot() throws Exception {
		Setup setup = openClinic();
		Setup other = openClinic();
		LocalDate monday = monday();
		JsonNode base = created(setup, Role.RECEPTIONIST, at(monday, 9, 0), at(monday, 10, 0));

		for (String[] overlap : new String[][] { { at(monday, 9, 30), at(monday, 10, 30) }, { at(monday, 8, 30), at(monday, 9, 30) },
				{ at(monday, 9, 0), at(monday, 10, 0) }, { at(monday, 9, 15), at(monday, 9, 45) }, { at(monday, 8, 0), at(monday, 11, 0) } }) {
			mvc.perform(create(setup, Role.DENTIST_ADMIN, overlap[0], overlap[1], "")).andExpect(status().isConflict())
					.andExpect(jsonPath("$.detail").value("Horário já ocupado."));
		}
		created(setup, Role.RECEPTIONIST, at(monday, 10, 0), at(monday, 11, 0)); // consecutivo depois
		created(setup, Role.RECEPTIONIST, at(monday, 8, 0), at(monday, 9, 0)); // consecutivo antes
		created(other, Role.RECEPTIONIST, at(monday, 9, 0), at(monday, 10, 0)); // outra clínica, mesmo horário

		setStatus(setup.clinic(), base.get("id").asString(), "CANCELED");
		created(setup, Role.RECEPTIONIST, at(monday, 9, 0), at(monday, 10, 0)); // cancelado libera o horário
	}

	@Test
	void noShowFreesTheSlotToo() throws Exception {
		Setup setup = openClinic();
		LocalDate monday = monday();
		JsonNode base = created(setup, Role.RECEPTIONIST, at(monday, 14, 0), at(monday, 15, 0));
		mvc.perform(create(setup, Role.RECEPTIONIST, at(monday, 14, 30), at(monday, 15, 30), "")).andExpect(status().isConflict());

		setStatus(setup.clinic(), base.get("id").asString(), "NO_SHOW");

		created(setup, Role.RECEPTIONIST, at(monday, 14, 30), at(monday, 15, 30));
	}

	@Test
	void twoSimultaneousBookingsOfTheSameSlotYieldOneWinnerAndNeverA500() throws Exception {
		Setup setup = openClinic();
		Setup other = openClinic();
		List<LocalDate> days = mondays(20);
		ExecutorService pool = Executors.newFixedThreadPool(4);
		try {
			for (int round = 0; round < 20; round++) {
				LocalDate day = days.get(round); // um horário novo a cada rodada
				CountDownLatch go = new CountDownLatch(1);
				List<Future<Integer>> sameClinic = new ArrayList<>();
				for (Role role : new Role[] { Role.RECEPTIONIST, Role.DENTIST_ADMIN }) {
					sameClinic.add(pool.submit(() -> {
						go.await();
						return mvc.perform(create(setup, role, at(day, 9, 0), at(day, 10, 0), "")).andReturn().getResponse().getStatus();
					}));
				}
				List<Future<Integer>> otherClinics = new ArrayList<>();
				for (Setup target : List.of(setup, other)) {
					otherClinics.add(pool.submit(() -> {
						go.await();
						return mvc.perform(create(target, Role.DENTIST_ADMIN, at(day, 14, 0), at(day, 15, 0), "")).andReturn().getResponse()
								.getStatus();
					}));
				}
				go.countDown();

				List<Integer> statuses = new ArrayList<>();
				for (Future<Integer> future : sameClinic) {
					statuses.add(future.get());
				}
				assertThat(statuses).as("rodada %d", round).containsExactlyInAnyOrder(201, 409);
				List<Integer> second = new ArrayList<>();
				for (Future<Integer> future : otherClinics) {
					second.add(future.get());
				}
				// o mesmo horário em clínicas diferentes nunca disputa: os dois passam
				assertThat(second).as("rodada %d, 14:00", round).containsExactlyInAnyOrder(201, 201);
			}
		}
		finally {
			pool.shutdownNow();
		}
	}

	// ---- regras por papel ----

	@Test
	void receptionistIsBlockedWithEachReasonAndTheDentistMayFit() throws Exception {
		Setup setup = openClinic();
		LocalDate monday = monday();
		LocalDate sunday = monday.plusDays(6);
		mvc.perform(json(post("/api/v1/clinic-holidays"), "{\"date\":\"%s\",\"name\":\"Recesso\"}".formatted(monday.plusDays(1)))
				.with(as(setup.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isCreated());

		record Case(LocalDate day, int fromH, int fromM, int toH, int toM, String reason) {
		}
		for (Case c : List.of(new Case(monday, 7, 0, 8, 0, "OUTSIDE_HOURS"), new Case(monday, 17, 30, 18, 30, "OUTSIDE_HOURS"),
				new Case(monday, 11, 30, 12, 30, "LUNCH"), new Case(sunday, 9, 0, 10, 0, "CLOSED_DAY"),
				new Case(monday.plusDays(1), 9, 0, 10, 0, "HOLIDAY"))) {
			mvc.perform(create(setup, Role.RECEPTIONIST, at(c.day(), c.fromH(), c.fromM()), at(c.day(), c.toH(), c.toM()), ""))
					.andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.reason").value(c.reason()))
					.andExpect(jsonPath("$.type").value("https://crm.local/problems/business-rule"));
			mvc.perform(create(setup, Role.DENTIST_ADMIN, at(c.day(), c.fromH(), c.fromM()), at(c.day(), c.toH(), c.toM()), ""))
					.andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.reason").value(c.reason()));
		}
		// o dentista encaixa; a recepção que pede o encaixe recebe 403, com a regra que fosse
		mvc.perform(create(setup, Role.DENTIST_ADMIN, at(sunday, 9, 0), at(sunday, 10, 0), ",\"allowOutsideHours\":true"))
				.andExpect(status().isCreated());
		mvc.perform(create(setup, Role.RECEPTIONIST, at(monday, 9, 0), at(monday, 10, 0), ",\"allowOutsideHours\":true"))
				.andExpect(status().isForbidden());
		mvc.perform(get("/api/v1/appointments").param("from", sunday.toString()).param("to", sunday.toString())
				.with(as(setup.clinic(), Role.RECEPTIONIST))).andExpect(jsonPath("$.length()").value(1));
	}

	@Test
	void thePastIsBlockedForTheReceptionistWithTheToleranceAndAllowedForTheDentist() throws Exception {
		Setup setup = openClinic();
		LocalDate lastMonday = pastMonday();
		mvc.perform(create(setup, Role.RECEPTIONIST, at(lastMonday, 9, 0), at(lastMonday, 10, 0), "")).andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.reason").value("PAST"));

		created(setup, Role.DENTIST_ADMIN, at(lastMonday, 9, 0), at(lastMonday, 10, 0));
	}

	@Test
	void anUnconfiguredClinicIsClosedForTheReceptionistButNotForTheDentistFitting() throws Exception {
		Clinic clinic = newClinic();
		Setup setup = new Setup(clinic, createPatient(clinic, "Maria Souza"));
		LocalDate monday = monday();

		mvc.perform(create(setup, Role.RECEPTIONIST, at(monday, 9, 0), at(monday, 10, 0), "")).andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.reason").value("CLOSED_DAY"));
		mvc.perform(create(setup, Role.DENTIST_ADMIN, at(monday, 9, 0), at(monday, 10, 0), ",\"allowOutsideHours\":true"))
				.andExpect(status().isCreated());
	}

	// ---- paciente ----

	@Test
	void patientStatesAreEnforced() throws Exception {
		Setup setup = openClinic();
		Setup other = openClinic();
		LocalDate monday = monday();
		String inactive = createPatient(setup.clinic(), "Ivo Inativo");
		mvc.perform(json(patch("/api/v1/patients/" + inactive + "/active"), "{\"active\":false}").with(as(setup.clinic(), Role.DENTIST_ADMIN)))
				.andExpect(status().isOk());
		String anonymized = createPatient(setup.clinic(), "Aline Anonima");
		PatientFixtures.anonymize(setup.clinic(), anonymized);

		mvc.perform(json(post("/api/v1/appointments"), body(inactive, at(monday, 9, 0), at(monday, 10, 0), "")).with(as(setup.clinic(), Role.RECEPTIONIST)))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("Paciente inativo."));
		mvc.perform(json(post("/api/v1/appointments"), body(anonymized, at(monday, 9, 0), at(monday, 10, 0), "")).with(as(setup.clinic(), Role.RECEPTIONIST)))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("Paciente anonimizado."));
		mvc.perform(json(post("/api/v1/appointments"), body(other.patient(), at(monday, 9, 0), at(monday, 10, 0), "")).with(as(setup.clinic(), Role.RECEPTIONIST)))
				.andExpect(status().isNotFound());
		mvc.perform(json(post("/api/v1/appointments"), body(UUID.randomUUID().toString(), at(monday, 9, 0), at(monday, 10, 0), ""))
				.with(as(setup.clinic(), Role.RECEPTIONIST))).andExpect(status().isNotFound());
	}

	// ---- agenda ----

	@Test
	void theAgendaIsTheLocalWindowOrderedAndIncludesWhatStartedBeforeIt() throws Exception {
		Setup setup = openClinic();
		LocalDate monday = monday();
		LocalDate tuesday = monday.plusDays(1);
		String fit = ",\"allowOutsideHours\":true";
		mvc.perform(create(setup, Role.DENTIST_ADMIN, at(monday, 23, 30), at(tuesday, 0, 30), fit)).andExpect(status().isCreated()); // cruza a meia-noite
		JsonNode later = created(setup, Role.RECEPTIONIST, at(tuesday, 10, 0), at(tuesday, 11, 0));
		JsonNode earlier = created(setup, Role.RECEPTIONIST, at(tuesday, 9, 0), at(tuesday, 10, 0));
		created(setup, Role.RECEPTIONIST, at(tuesday.plusDays(1), 9, 0), at(tuesday.plusDays(1), 10, 0));

		JsonNode tuesdayOnly = mapper.readTree(agenda(setup, Role.RECEPTIONIST, tuesday.toString(), tuesday.toString()));

		assertThat(tuesdayOnly).hasSize(3);
		assertThat(tuesdayOnly.get(0).get("startsAt").asString()).as("o que começou na segunda e entra na terça").isEqualTo(at(monday, 23, 30));
		assertThat(tuesdayOnly.get(1).get("id").asString()).isEqualTo(earlier.get("id").asString());
		assertThat(tuesdayOnly.get(2).get("id").asString()).isEqualTo(later.get("id").asString());
		assertThat(mapper.readTree(agenda(setup, Role.DENTIST_ADMIN, monday.toString(), monday.toString()))).hasSize(1);
		assertThat(mapper.readTree(agenda(setup, Role.DENTIST_ADMIN, monday.toString(), tuesday.plusDays(1).toString()))).hasSize(4);
	}

	@Test
	void theWindowFollowsTheClinicTimezone() throws Exception {
		Setup setup = openClinic();
		LocalDate monday = monday();
		String fit = ",\"allowOutsideHours\":true";
		mvc.perform(create(setup, Role.DENTIST_ADMIN, at(monday, 22, 0), at(monday, 22, 45), fit)).andExpect(status().isCreated()); // 01:00Z do dia seguinte
		assertThat(mapper.readTree(agenda(setup, Role.RECEPTIONIST, monday.toString(), monday.toString()))).hasSize(1);

		JsonNode clinic = read(mvc.perform(get("/api/v1/clinic").with(as(setup.clinic(), Role.DENTIST_ADMIN))).andReturn());
		mvc.perform(json(put("/api/v1/clinic"), "{\"name\":\"Clínica\",\"timezone\":\"UTC\",\"version\":%d}".formatted(clinic.get("version").asLong()))
				.with(as(setup.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isOk());

		assertThat(mapper.readTree(agenda(setup, Role.RECEPTIONIST, monday.toString(), monday.toString()))).as("em UTC já é o dia seguinte").isEmpty();
		assertThat(mapper.readTree(agenda(setup, Role.RECEPTIONIST, monday.plusDays(1).toString(), monday.plusDays(1).toString()))).hasSize(1);
	}

	@Test
	void theStatusFilterAndTheWindowLimitsAreEnforced() throws Exception {
		Setup setup = openClinic();
		LocalDate monday = monday();
		JsonNode canceled = created(setup, Role.RECEPTIONIST, at(monday, 9, 0), at(monday, 10, 0));
		created(setup, Role.RECEPTIONIST, at(monday, 10, 0), at(monday, 11, 0));
		setStatus(setup.clinic(), canceled.get("id").asString(), "CANCELED");

		assertThat(mapper.readTree(agenda(setup, Role.RECEPTIONIST, monday.toString(), monday.toString()))).as("todos os status").hasSize(2);
		assertThat(mapper.readTree(agenda(setup, Role.RECEPTIONIST, monday.toString(), monday.toString(), "CANCELED"))).hasSize(1);
		assertThat(mapper.readTree(agenda(setup, Role.RECEPTIONIST, monday.toString(), monday.toString(), "SCHEDULED", "CANCELED"))).hasSize(2);
		assertThat(mapper.readTree(agenda(setup, Role.RECEPTIONIST, monday.toString(), monday.toString(), "COMPLETED"))).isEmpty();
		mvc.perform(get("/api/v1/appointments").param("from", monday.toString()).param("to", monday.plusDays(42).toString())
				.with(as(setup.clinic(), Role.RECEPTIONIST))).andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/appointments").param("from", monday.toString()).param("to", monday.plusDays(41).toString())
				.with(as(setup.clinic(), Role.RECEPTIONIST))).andExpect(status().isOk());
	}

	@Test
	void clinicsNeverSeeEachOthersAppointments() throws Exception {
		Setup a = openClinic();
		Setup b = openClinic();
		LocalDate monday = monday();
		JsonNode inA = created(a, Role.RECEPTIONIST, at(monday, 9, 0), at(monday, 10, 0));

		assertThat(mapper.readTree(agenda(b, Role.DENTIST_ADMIN, monday.toString(), monday.toString()))).isEmpty();
		mvc.perform(get("/api/v1/appointments/" + inA.get("id").asString()).with(as(b.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isNotFound());
		mvc.perform(json(put("/api/v1/appointments/" + inA.get("id").asString()),
				"{\"startsAt\":\"%s\",\"endsAt\":\"%s\",\"version\":0}".formatted(at(monday, 11, 0), at(monday, 12, 0))).with(as(b.clinic(), Role.DENTIST_ADMIN)))
				.andExpect(status().isNotFound());
	}

	// ---- remarcar ----

	private MockHttpServletRequestBuilder reschedule(Setup setup, Role role, JsonNode appointment, String start, String end, String extra) {
		return json(put("/api/v1/appointments/" + appointment.get("id").asString()),
				"{\"startsAt\":\"%s\",\"endsAt\":\"%s\",\"version\":%d%s}".formatted(start, end, appointment.get("version").asLong(), extra))
						.with(as(setup.clinic(), role));
	}

	@Test
	void rescheduleMovesTheAppointmentAndBumpsTheVersion() throws Exception {
		Setup setup = openClinic();
		LocalDate monday = monday();
		JsonNode appointment = created(setup, Role.RECEPTIONIST, at(monday, 9, 0), at(monday, 10, 0));

		mvc.perform(reschedule(setup, Role.RECEPTIONIST, appointment, at(monday, 14, 0), at(monday, 15, 0), ",\"notes\":\"Remarcado\""))
				.andExpect(status().isOk()).andExpect(jsonPath("$.startsAt").value(at(monday, 14, 0))).andExpect(jsonPath("$.version").value(1))
				.andExpect(jsonPath("$.notes").value("Remarcado")).andExpect(jsonPath("$.status").value("SCHEDULED"));

		assertThat(mapper.readTree(agenda(setup, Role.RECEPTIONIST, monday.toString(), monday.toString())).get(0).get("startsAt").asString())
				.isEqualTo(at(monday, 14, 0));
		created(setup, Role.RECEPTIONIST, at(monday, 9, 0), at(monday, 10, 0)); // o horário antigo ficou livre
	}

	@Test
	void rescheduleConflictsStaleVersionAndWrongStatusAreRejected() throws Exception {
		Setup setup = openClinic();
		LocalDate monday = monday();
		JsonNode first = created(setup, Role.RECEPTIONIST, at(monday, 9, 0), at(monday, 10, 0));
		JsonNode second = created(setup, Role.RECEPTIONIST, at(monday, 10, 0), at(monday, 11, 0));

		mvc.perform(reschedule(setup, Role.RECEPTIONIST, second, at(monday, 9, 30), at(monday, 10, 30), "")).andExpect(status().isConflict())
				.andExpect(jsonPath("$.detail").value("Horário já ocupado."));
		mvc.perform(json(put("/api/v1/appointments/" + first.get("id").asString()),
				"{\"startsAt\":\"%s\",\"endsAt\":\"%s\",\"version\":7}".formatted(at(monday, 15, 0), at(monday, 16, 0))).with(as(setup.clinic(), Role.RECEPTIONIST)))
				.andExpect(status().isConflict());
		for (String blocked : List.of("IN_PROGRESS", "COMPLETED", "CANCELED", "NO_SHOW")) {
			JsonNode appointment = created(setup, Role.RECEPTIONIST, at(monday.plusDays(1), 9, 0), at(monday.plusDays(1), 10, 0));
			setStatus(setup.clinic(), appointment.get("id").asString(), blocked);
			mvc.perform(reschedule(setup, Role.RECEPTIONIST, appointment, at(monday.plusDays(1), 14, 0), at(monday.plusDays(1), 15, 0), ""))
					.andExpect(status().isConflict());
			setStatus(setup.clinic(), appointment.get("id").asString(), "CANCELED");
		}
	}

	@Test
	void aConfirmedAppointmentGoesBackToScheduledOnlyWhenTheTimeChanges() throws Exception {
		Setup setup = openClinic();
		LocalDate monday = monday();
		JsonNode appointment = created(setup, Role.RECEPTIONIST, at(monday, 9, 0), at(monday, 10, 0));
		setStatus(setup.clinic(), appointment.get("id").asString(), "CONFIRMED");

		mvc.perform(reschedule(setup, Role.RECEPTIONIST, appointment, at(monday, 9, 0), at(monday, 10, 0), ",\"notes\":\"só a observação\""))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED")).andExpect(jsonPath("$.notes").value("só a observação"));
		JsonNode refreshed = read(mvc.perform(get("/api/v1/appointments/" + appointment.get("id").asString()).with(as(setup.clinic(), Role.RECEPTIONIST)))
				.andReturn());
		mvc.perform(reschedule(setup, Role.RECEPTIONIST, refreshed, at(monday, 9, 30), at(monday, 10, 30), ""))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SCHEDULED")).andExpect(jsonPath("$.notes").doesNotExist());
	}

	@Test
	void editingOnlyTheNotesOfAnEncaixeDoesNotReapplyTheHourRulesToTheReceptionist() throws Exception {
		Setup setup = openClinic();
		LocalDate sunday = monday().plusDays(6);
		JsonNode encaixe = read(mvc.perform(create(setup, Role.DENTIST_ADMIN, at(sunday, 9, 0), at(sunday, 10, 0), ",\"allowOutsideHours\":true"))
				.andExpect(status().isCreated()).andReturn());

		mvc.perform(reschedule(setup, Role.RECEPTIONIST, encaixe, at(sunday, 9, 0), at(sunday, 10, 0), ",\"notes\":\"confirmar por telefone\""))
				.andExpect(status().isOk()).andExpect(jsonPath("$.notes").value("confirmar por telefone"));
		JsonNode refreshed = read(mvc.perform(get("/api/v1/appointments/" + encaixe.get("id").asString()).with(as(setup.clinic(), Role.RECEPTIONIST)))
				.andReturn());
		mvc.perform(reschedule(setup, Role.RECEPTIONIST, refreshed, at(sunday, 10, 0), at(sunday, 11, 0), "")).andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.reason").value("CLOSED_DAY"));
		mvc.perform(reschedule(setup, Role.RECEPTIONIST, refreshed, at(sunday, 10, 0), at(sunday, 11, 0), ",\"allowOutsideHours\":true"))
				.andExpect(status().isForbidden());
		mvc.perform(reschedule(setup, Role.DENTIST_ADMIN, refreshed, at(sunday, 10, 0), at(sunday, 11, 0), ",\"allowOutsideHours\":true"))
				.andExpect(status().isOk());
	}

	@Test
	void notesLimitsAreEnforced() throws Exception {
		Setup setup = openClinic();
		LocalDate monday = monday();

		mvc.perform(create(setup, Role.RECEPTIONIST, at(monday, 9, 0), at(monday, 10, 0), ",\"notes\":\"" + "n".repeat(500) + "\"")).andExpect(status().isCreated());
		mvc.perform(create(setup, Role.RECEPTIONIST, at(monday, 10, 0), at(monday, 11, 0), ",\"notes\":\"" + "n".repeat(501) + "\"")).andExpect(status().isBadRequest());
		mvc.perform(create(setup, Role.RECEPTIONIST, at(monday, 10, 0), at(monday, 11, 0), ",\"notes\":\"   \"")).andExpect(status().isBadRequest());
	}
}
