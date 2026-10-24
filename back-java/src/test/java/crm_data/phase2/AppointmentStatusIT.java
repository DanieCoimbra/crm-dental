package crm_data.phase2;

import static crm_data.phase2.AgendaDates.SAO_PAULO;
import static crm_data.phase2.AgendaDates.at;
import static crm_data.phase2.AgendaDates.monday;
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

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import crm_data.shared.security.Role;
import crm_data.support.tenant.PatientFixtures;
import tools.jackson.databind.JsonNode;

/**
 * F04 contra o PostgreSQL real (V001–V021): as seis ações de status, a tabela de transições, as regras extras (dia,
 * horário, único atendimento em andamento), a reativação e a trilha de auditoria.
 */
class AppointmentStatusIT extends Phase2ItSupport {

	private static final String FIT = ",\"allowOutsideHours\":true";

	// ---- auxiliares ----

	private JsonNode book(Setup setup, Role role, String start, String end, String extra) throws Exception {
		return read(mvc.perform(json(post("/api/v1/appointments"),
				"{\"patientId\":\"%s\",\"startsAt\":\"%s\",\"endsAt\":\"%s\"%s}".formatted(setup.patient(), start, end, extra))
				.with(as(setup.clinic(), role))).andExpect(status().isCreated()).andReturn());
	}

	private JsonNode book(Setup setup, String start, String end) throws Exception {
		return book(setup, Role.RECEPTIONIST, start, end, "");
	}

	private MockHttpServletRequestBuilder act(Setup setup, Role role, JsonNode appointment, String route) {
		return act(setup, role, appointment.get("id").asString(), route, appointment.get("version").asLong());
	}

	private MockHttpServletRequestBuilder act(Setup setup, Role role, String id, String route, long version) {
		return json(post("/api/v1/appointments/" + id + "/" + route), "{\"version\":" + version + "}").with(as(setup.clinic(), role));
	}

	/** Executa a ação, exige 200 e devolve o agendamento atualizado. */
	private JsonNode ok(Setup setup, Role role, JsonNode appointment, String route, String expectedStatus) throws Exception {
		return read(mvc.perform(act(setup, role, appointment, route)).andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.status").value(expectedStatus)).andReturn());
	}

	private static LocalDate today() {
		return LocalDate.now(SAO_PAULO);
	}

	// ---- recepção: confirmar e cancelar ----

	@Test
	void receptionistConfirmsAndCancelsAndTheCancelFreesTheSlotAtOnce() throws Exception {
		Setup setup = openClinic();
		LocalDate monday = monday();
		JsonNode created = book(setup, at(monday, 9, 0), at(monday, 10, 0));

		JsonNode confirmed = ok(setup, Role.RECEPTIONIST, created, "confirm", "CONFIRMED");
		assertThat(confirmed.get("version").asLong()).isEqualTo(created.get("version").asLong() + 1);
		assertThat(confirmed.get("patient").get("name").asString()).isEqualTo("Maria Souza");
		mvc.perform(json(post("/api/v1/appointments"), "{\"patientId\":\"%s\",\"startsAt\":\"%s\",\"endsAt\":\"%s\"}"
				.formatted(setup.patient(), at(monday, 9, 30), at(monday, 10, 30))).with(as(setup.clinic(), Role.RECEPTIONIST)))
				.andExpect(status().isConflict());

		ok(setup, Role.RECEPTIONIST, confirmed, "cancel", "CANCELED");

		book(setup, at(monday, 9, 30), at(monday, 10, 30));
	}

	@Test
	void aScheduledOneCanBeCanceledDirectlyByTheDentistToo() throws Exception {
		Setup setup = openClinic();
		LocalDate monday = monday();

		ok(setup, Role.DENTIST_ADMIN, book(setup, at(monday, 9, 0), at(monday, 10, 0)), "cancel", "CANCELED");
	}

	// ---- tabela de transições ----

	@Test
	void transitionsOutsideTheTableAre409WithTheCurrentStatus() throws Exception {
		Setup setup = openClinic();
		LocalDate monday = monday();
		JsonNode scheduled = book(setup, at(monday, 9, 0), at(monday, 10, 0));

		mvc.perform(act(setup, Role.DENTIST_ADMIN, scheduled, "start")).andExpect(status().isConflict())
				.andExpect(jsonPath("$.detail").value("O agendamento está SCHEDULED e não aceita esta ação."));
		mvc.perform(act(setup, Role.DENTIST_ADMIN, scheduled, "finish")).andExpect(status().isConflict());
		mvc.perform(act(setup, Role.DENTIST_ADMIN, scheduled, "reactivate")).andExpect(status().isConflict());

		JsonNode confirmed = ok(setup, Role.RECEPTIONIST, scheduled, "confirm", "CONFIRMED");
		mvc.perform(act(setup, Role.RECEPTIONIST, confirmed, "confirm")).andExpect(status().isConflict())
				.andExpect(jsonPath("$.detail").value("O agendamento está CONFIRMED e não aceita esta ação."));

		JsonNode canceled = ok(setup, Role.RECEPTIONIST, confirmed, "cancel", "CANCELED");
		for (String route : new String[] { "confirm", "cancel", "no-show", "start", "finish" }) {
			mvc.perform(act(setup, Role.DENTIST_ADMIN, canceled, route)).andExpect(status().isConflict());
		}
	}

	@Test
	void aStaleVersionIs409OnEveryActionAndAnUnknownIdIs404() throws Exception {
		Setup setup = openClinic();
		LocalDate monday = monday();
		JsonNode scheduled = book(setup, at(monday, 9, 0), at(monday, 10, 0));

		for (String route : new String[] { "confirm", "cancel", "no-show", "start", "finish", "reactivate" }) {
			mvc.perform(act(setup, Role.DENTIST_ADMIN, scheduled.get("id").asString(), route, 9)).andExpect(status().isConflict());
		}
		mvc.perform(act(setup, Role.DENTIST_ADMIN, java.util.UUID.randomUUID().toString(), "confirm", 0)).andExpect(status().isNotFound());
		ok(setup, Role.RECEPTIONIST, scheduled, "confirm", "CONFIRMED");
	}

	// ---- faltou ----

	@Test
	void noShowOnlyFromTheStartAndTheDentistCanReactivateIt() throws Exception {
		Setup setup = openClinic();
		LocalDate monday = monday();
		JsonNode future = book(setup, at(monday, 9, 0), at(monday, 10, 0));
		mvc.perform(act(setup, Role.RECEPTIONIST, future, "no-show")).andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.reason").value("NOT_STARTED"));

		LocalDate lastMonday = pastMonday();
		JsonNode past = book(setup, Role.DENTIST_ADMIN, at(lastMonday, 9, 0), at(lastMonday, 10, 0), "");
		JsonNode missed = ok(setup, Role.RECEPTIONIST, past, "no-show", "NO_SHOW");

		book(setup, Role.DENTIST_ADMIN, at(lastMonday, 9, 30), at(lastMonday, 10, 30), ""); // faltou libera o horário
		mvc.perform(act(setup, Role.DENTIST_ADMIN, missed, "reactivate")).andExpect(status().isConflict())
				.andExpect(jsonPath("$.detail").value("Horário já ocupado."));
	}

	@Test
	void aMissedAppointmentIsReactivatedWhenTheSlotIsStillFree() throws Exception {
		Setup setup = openClinic();
		LocalDate lastMonday = pastMonday();
		JsonNode past = book(setup, Role.DENTIST_ADMIN, at(lastMonday, 9, 0), at(lastMonday, 10, 0), "");
		JsonNode missed = ok(setup, Role.DENTIST_ADMIN, past, "no-show", "NO_SHOW");

		JsonNode reactivated = ok(setup, Role.DENTIST_ADMIN, missed, "reactivate", "SCHEDULED");

		assertThat(reactivated.get("startsAt").asString()).as("reativar não reaplica a regra do passado").isEqualTo(at(lastMonday, 9, 0));
	}

	// ---- iniciar e finalizar ----

	@Test
	void startAndFinishFollowTheDayAndTheSingleInProgressRule() throws Exception {
		Setup setup = openClinic();
		LocalDate today = today();
		JsonNode first = book(setup, Role.DENTIST_ADMIN, at(today, 0, 5), at(today, 0, 35), FIT);
		JsonNode second = book(setup, Role.DENTIST_ADMIN, at(today, 0, 40), at(today, 1, 10), FIT);
		first = ok(setup, Role.RECEPTIONIST, first, "confirm", "CONFIRMED");
		second = ok(setup, Role.RECEPTIONIST, second, "confirm", "CONFIRMED");

		JsonNode started = ok(setup, Role.DENTIST_ADMIN, first, "start", "IN_PROGRESS");
		mvc.perform(act(setup, Role.DENTIST_ADMIN, second, "start")).andExpect(status().isConflict())
				.andExpect(jsonPath("$.detail").value("Já existe um atendimento em andamento."));

		JsonNode finished = ok(setup, Role.DENTIST_ADMIN, started, "finish", "COMPLETED");
		JsonNode secondStarted = ok(setup, Role.DENTIST_ADMIN, second, "start", "IN_PROGRESS");
		ok(setup, Role.DENTIST_ADMIN, secondStarted, "finish", "COMPLETED");

		for (String route : new String[] { "confirm", "cancel", "no-show", "start", "finish", "reactivate" }) {
			mvc.perform(act(setup, Role.DENTIST_ADMIN, finished, route)).andExpect(status().isConflict());
		}
		mvc.perform(json(put("/api/v1/appointments/" + finished.get("id").asString()),
				"{\"startsAt\":\"%s\",\"endsAt\":\"%s\",\"version\":%d}".formatted(at(today, 2, 0), at(today, 3, 0), finished.get("version").asLong()))
				.with(as(setup.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isConflict());
	}

	@Test
	void startOutsideTheLocalDayOfTheAppointmentIs422() throws Exception {
		Setup setup = openClinic();
		LocalDate monday = monday();
		JsonNode confirmed = ok(setup, Role.RECEPTIONIST, book(setup, at(monday, 9, 0), at(monday, 10, 0)), "confirm", "CONFIRMED");

		mvc.perform(act(setup, Role.DENTIST_ADMIN, confirmed, "start")).andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.reason").value("NOT_TODAY"));
	}

	@Test
	void receptionistCanNeverStartFinishOrReactivate() throws Exception {
		Setup setup = openClinic();
		LocalDate monday = monday();
		JsonNode scheduled = book(setup, at(monday, 9, 0), at(monday, 10, 0));

		for (String route : new String[] { "start", "finish", "reactivate" }) {
			mvc.perform(act(setup, Role.RECEPTIONIST, scheduled, route)).andExpect(status().isForbidden());
		}
		JsonNode unchanged = read(mvc.perform(get("/api/v1/appointments/" + scheduled.get("id").asString()).with(as(setup.clinic(), Role.RECEPTIONIST)))
				.andReturn());
		assertThat(unchanged.get("status").asString()).isEqualTo("SCHEDULED");
		assertThat(unchanged.get("version").asLong()).isEqualTo(scheduled.get("version").asLong());
	}

	// ---- reativar ----

	@Test
	void reactivateReevaluatesTheSlotAndThePatient() throws Exception {
		Setup setup = openClinic();
		LocalDate monday = monday();
		JsonNode original = book(setup, at(monday, 9, 0), at(monday, 10, 0));
		JsonNode canceled = ok(setup, Role.RECEPTIONIST, original, "cancel", "CANCELED");

		JsonNode taker = book(setup, at(monday, 9, 30), at(monday, 10, 30));
		mvc.perform(act(setup, Role.DENTIST_ADMIN, canceled, "reactivate")).andExpect(status().isConflict())
				.andExpect(jsonPath("$.detail").value("Horário já ocupado."));
		ok(setup, Role.RECEPTIONIST, taker, "cancel", "CANCELED");
		JsonNode back = ok(setup, Role.DENTIST_ADMIN, canceled, "reactivate", "SCHEDULED");
		assertThat(back.get("version").asLong()).isGreaterThan(canceled.get("version").asLong());

		// paciente inativo e paciente anonimizado
		String inactive = createPatient(setup.clinic(), "Ivo Inativo");
		JsonNode ofInactive = read(mvc.perform(json(post("/api/v1/appointments"), "{\"patientId\":\"%s\",\"startsAt\":\"%s\",\"endsAt\":\"%s\"}"
				.formatted(inactive, at(monday, 14, 0), at(monday, 15, 0))).with(as(setup.clinic(), Role.RECEPTIONIST)))
				.andExpect(status().isCreated()).andReturn());
		JsonNode canceledOfInactive = ok(setup, Role.RECEPTIONIST, ofInactive, "cancel", "CANCELED");
		mvc.perform(json(patch("/api/v1/patients/" + inactive + "/active"), "{\"active\":false}").with(as(setup.clinic(), Role.DENTIST_ADMIN)))
				.andExpect(status().isOk());
		mvc.perform(act(setup, Role.DENTIST_ADMIN, canceledOfInactive, "reactivate")).andExpect(status().isConflict())
				.andExpect(jsonPath("$.detail").value("Paciente inativo."));

		String anonymized = createPatient(setup.clinic(), "Aline Anonima");
		JsonNode ofAnonymized = read(mvc.perform(json(post("/api/v1/appointments"), "{\"patientId\":\"%s\",\"startsAt\":\"%s\",\"endsAt\":\"%s\"}"
				.formatted(anonymized, at(monday, 16, 0), at(monday, 17, 0))).with(as(setup.clinic(), Role.RECEPTIONIST)))
				.andExpect(status().isCreated()).andReturn());
		JsonNode canceledOfAnonymized = ok(setup, Role.RECEPTIONIST, ofAnonymized, "cancel", "CANCELED");
		PatientFixtures.anonymize(setup.clinic(), anonymized);
		mvc.perform(act(setup, Role.DENTIST_ADMIN, canceledOfAnonymized, "reactivate")).andExpect(status().isConflict())
				.andExpect(jsonPath("$.detail").value("Paciente anonimizado."));
	}

	@Test
	void twoSimultaneousReactivationsOfTheSameSlotLeaveOneWinner() throws Exception {
		Setup setup = openClinic();
		LocalDate monday = monday();
		JsonNode first = ok(setup, Role.RECEPTIONIST, book(setup, at(monday, 9, 0), at(monday, 10, 0)), "cancel", "CANCELED");
		JsonNode second = ok(setup, Role.RECEPTIONIST, book(setup, at(monday, 9, 0), at(monday, 10, 0)), "cancel", "CANCELED");

		int statusFirst = mvc.perform(act(setup, Role.DENTIST_ADMIN, first, "reactivate")).andReturn().getResponse().getStatus();
		int statusSecond = mvc.perform(act(setup, Role.DENTIST_ADMIN, second, "reactivate")).andReturn().getResponse().getStatus();

		assertThat(java.util.List.of(statusFirst, statusSecond)).containsExactlyInAnyOrder(200, 409);
	}

	// ---- isolamento e auditoria ----

	@Test
	void anotherClinicsAppointmentIs404ForEveryAction() throws Exception {
		Setup a = openClinic();
		Setup b = openClinic();
		LocalDate monday = monday();
		JsonNode inA = book(a, at(monday, 9, 0), at(monday, 10, 0));

		for (String route : new String[] { "confirm", "cancel", "no-show", "start", "finish", "reactivate" }) {
			mvc.perform(act(b, Role.DENTIST_ADMIN, inA, route)).andExpect(status().isNotFound());
		}
		assertThat(read(mvc.perform(get("/api/v1/appointments/" + inA.get("id").asString()).with(as(a.clinic(), Role.DENTIST_ADMIN))).andReturn())
				.get("status").asString()).isEqualTo("SCHEDULED");
	}

	@Test
	void everyTransitionLeavesATrailInTheAuditLogWithoutValues() throws Exception {
		Setup setup = openClinic();
		LocalDate monday = monday();
		JsonNode created = book(setup, at(monday, 9, 0), at(monday, 10, 0));
		JsonNode confirmed = ok(setup, Role.RECEPTIONIST, created, "confirm", "CONFIRMED");
		ok(setup, Role.RECEPTIONIST, confirmed, "cancel", "CANCELED");

		String trail = mvc.perform(get("/api/v1/audit-log").param("tableName", "appointments").param("recordId", created.get("id").asString())
				.with(as(setup.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		JsonNode page = mapper.readTree(trail);

		assertThat(page.get("totalElements").asInt()).isEqualTo(3);
		assertThat(page.get("content")).extracting(entry -> entry.get("action").asString()).containsExactlyInAnyOrder("INSERT", "UPDATE", "UPDATE");
		assertThat(trail).doesNotContain("Maria Souza").doesNotContain("startsAt");
		assertThat(page.get("content").get(0).get("userId").asString()).isEqualTo(userOf(setup.clinic(), Role.RECEPTIONIST).toString());
	}
}
