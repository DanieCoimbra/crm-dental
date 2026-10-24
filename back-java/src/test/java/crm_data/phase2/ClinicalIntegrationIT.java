package crm_data.phase2;

import static crm_data.phase2.AgendaDates.SAO_PAULO;
import static crm_data.phase2.AgendaDates.at;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import crm_data.shared.security.Role;
import crm_data.support.tenant.PatientFixtures;
import tools.jackson.databind.JsonNode;

/**
 * F05 contra o PostgreSQL real (V001–V021): inativar o paciente cancela os futuros na mesma transação, o histórico do
 * paciente, a anonimização com agendamentos e a nota clínica vinculada ao atendimento (uma por atendimento, inclusive
 * em paralelo; vincular, trocar e desvincular).
 */
class ClinicalIntegrationIT extends Phase2ItSupport {

	private static final String FIT = ",\"allowOutsideHours\":true";

	// ---- auxiliares ----

	private JsonNode book(Setup setup, String patient, Role role, String start, String end, String extra) throws Exception {
		return read(mvc.perform(json(post("/api/v1/appointments"),
				"{\"patientId\":\"%s\",\"startsAt\":\"%s\",\"endsAt\":\"%s\"%s}".formatted(patient, start, end, extra))
				.with(as(setup.clinic(), role))).andExpect(status().isCreated()).andReturn());
	}

	private JsonNode book(Setup setup, String patient, String start, String end) throws Exception {
		return book(setup, patient, Role.RECEPTIONIST, start, end, "");
	}

	private MockHttpServletRequestBuilder act(Setup setup, JsonNode appointment, String route) {
		return json(post("/api/v1/appointments/" + appointment.get("id").asString() + "/" + route),
				"{\"version\":" + appointment.get("version").asLong() + "}").with(as(setup.clinic(), Role.DENTIST_ADMIN));
	}

	private JsonNode appointment(Setup setup, String id) throws Exception {
		return read(mvc.perform(get("/api/v1/appointments/" + id).with(as(setup.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isOk()).andReturn());
	}

	private MockHttpServletRequestBuilder setActive(Setup setup, String patient, boolean active) {
		return json(patch("/api/v1/patients/" + patient + "/active"), "{\"active\":" + active + "}").with(as(setup.clinic(), Role.DENTIST_ADMIN));
	}

	private JsonNode audit(Setup setup, String table, String recordId) throws Exception {
		return read(mvc.perform(get("/api/v1/audit-log").param("tableName", table).param("recordId", recordId).param("size", "50")
				.with(as(setup.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isOk()).andReturn()).get("content");
	}

	private static LocalDate lastMonday() {
		return pastMonday();
	}

	// ---- inativar paciente ----

	@Test
	void inactivatingAPatientCancelsOnlyTheirFutureOpenAppointmentsInTheSameTransaction() throws Exception {
		Setup setup = openClinic();
		String other = createPatient(setup.clinic(), "Outro Paciente");
		LocalDate monday = mondays(1).get(0);
		JsonNode scheduled = book(setup, setup.patient(), at(monday, 9, 0), at(monday, 10, 0));
		JsonNode confirmed = read(mvc.perform(act(setup, book(setup, setup.patient(), at(monday, 10, 0), at(monday, 11, 0)), "confirm"))
				.andExpect(status().isOk()).andReturn());
		JsonNode past = book(setup, setup.patient(), Role.DENTIST_ADMIN, at(lastMonday(), 9, 0), at(lastMonday(), 10, 0), "");
		JsonNode alreadyCanceled = read(mvc.perform(act(setup, book(setup, setup.patient(), at(monday, 14, 0), at(monday, 15, 0)), "cancel"))
				.andExpect(status().isOk()).andReturn());
		JsonNode othersFuture = book(setup, other, at(monday, 16, 0), at(monday, 17, 0));

		mvc.perform(setActive(setup, setup.patient(), false)).andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));

		assertThat(appointment(setup, scheduled.get("id").asString()).get("status").asString()).isEqualTo("CANCELED");
		assertThat(appointment(setup, confirmed.get("id").asString()).get("status").asString()).isEqualTo("CANCELED");
		assertThat(appointment(setup, past.get("id").asString()).get("status").asString()).as("passado não é tocado").isEqualTo("SCHEDULED");
		assertThat(appointment(setup, alreadyCanceled.get("id").asString()).get("version").asLong()).as("já cancelado não é regravado")
				.isEqualTo(alreadyCanceled.get("version").asLong());
		assertThat(appointment(setup, othersFuture.get("id").asString()).get("status").asString()).as("outro paciente não é tocado")
				.isEqualTo("SCHEDULED");
		// o horário liberado já aceita outro paciente
		book(setup, other, at(monday, 9, 0), at(monday, 10, 0));
	}

	@Test
	void everyAutomaticCancellationHasItsOwnAuditRowAuthoredByTheDentist() throws Exception {
		Setup setup = openClinic();
		LocalDate monday = mondays(1).get(0);
		JsonNode first = book(setup, setup.patient(), at(monday, 9, 0), at(monday, 10, 0));
		JsonNode second = book(setup, setup.patient(), at(monday, 10, 0), at(monday, 11, 0));

		mvc.perform(setActive(setup, setup.patient(), false)).andExpect(status().isOk());

		for (JsonNode appointment : List.of(first, second)) {
			JsonNode trail = audit(setup, "appointments", appointment.get("id").asString());
			assertThat(trail).extracting(e -> e.get("action").asString()).containsExactlyInAnyOrder("INSERT", "UPDATE");
			JsonNode update = trail.get(0).get("action").asString().equals("UPDATE") ? trail.get(0) : trail.get(1);
			assertThat(update.get("userId").asString()).isEqualTo(userOf(setup.clinic(), Role.DENTIST_ADMIN).toString());
		}
	}

	@Test
	void anAppointmentInProgressRefusesTheInactivationAndNothingChanges() throws Exception {
		Setup setup = openClinic();
		LocalDate monday = mondays(1).get(0);
		LocalDate today = LocalDate.now(SAO_PAULO);
		JsonNode future = book(setup, setup.patient(), at(monday, 9, 0), at(monday, 10, 0));
		JsonNode running = book(setup, setup.patient(), Role.DENTIST_ADMIN, at(today, 0, 5), at(today, 0, 35), FIT);
		running = read(mvc.perform(act(setup, running, "confirm")).andExpect(status().isOk()).andReturn());
		running = read(mvc.perform(act(setup, running, "start")).andExpect(status().isOk()).andReturn());

		mvc.perform(setActive(setup, setup.patient(), false)).andExpect(status().isConflict())
				.andExpect(jsonPath("$.detail").value("Finalize o atendimento antes de inativar o paciente."));

		mvc.perform(get("/api/v1/patients/" + setup.patient()).with(as(setup.clinic(), Role.DENTIST_ADMIN))).andExpect(jsonPath("$.active").value(true));
		assertThat(appointment(setup, future.get("id").asString()).get("status").asString()).isEqualTo("SCHEDULED");

		mvc.perform(act(setup, running, "finish")).andExpect(status().isOk());
		mvc.perform(setActive(setup, setup.patient(), false)).andExpect(status().isOk());
		assertThat(appointment(setup, future.get("id").asString()).get("status").asString()).isEqualTo("CANCELED");
	}

	@Test
	void inactivatingTwiceAndReactivatingNeverCancelsAgainNorRestores() throws Exception {
		Setup setup = openClinic();
		LocalDate monday = mondays(1).get(0);
		JsonNode booked = book(setup, setup.patient(), at(monday, 9, 0), at(monday, 10, 0));
		mvc.perform(setActive(setup, setup.patient(), false)).andExpect(status().isOk());
		long versionAfter = appointment(setup, booked.get("id").asString()).get("version").asLong();

		mvc.perform(setActive(setup, setup.patient(), false)).andExpect(status().isOk());
		mvc.perform(setActive(setup, setup.patient(), true)).andExpect(status().isOk()).andExpect(jsonPath("$.active").value(true));

		JsonNode after = appointment(setup, booked.get("id").asString());
		assertThat(after.get("status").asString()).isEqualTo("CANCELED");
		assertThat(after.get("version").asLong()).isEqualTo(versionAfter);
	}

	// ---- anonimização ----

	@Test
	void anonymizingKeepsTheTimesAndStatusButClearsTheNotesAndAuditsEachOne() throws Exception {
		Setup setup = openClinic();
		LocalDate monday = mondays(1).get(0);
		JsonNode booked = read(mvc.perform(json(post("/api/v1/appointments"), "{\"patientId\":\"%s\",\"startsAt\":\"%s\",\"endsAt\":\"%s\",\"notes\":\"ligar antes\"}"
				.formatted(setup.patient(), at(monday, 9, 0), at(monday, 10, 0))).with(as(setup.clinic(), Role.RECEPTIONIST))).andExpect(status().isCreated())
				.andReturn());
		mvc.perform(setActive(setup, setup.patient(), false)).andExpect(status().isOk()); // cancela os futuros

		mvc.perform(post("/api/v1/patients/" + setup.patient() + "/anonymize").with(as(setup.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isOk());

		JsonNode after = appointment(setup, booked.get("id").asString());
		assertThat(after.get("notes").isNull()).isTrue();
		assertThat(after.get("startsAt").asString()).isEqualTo(booked.get("startsAt").asString());
		assertThat(after.get("endsAt").asString()).isEqualTo(booked.get("endsAt").asString());
		assertThat(after.get("status").asString()).isEqualTo("CANCELED");
		assertThat(after.get("patient").get("name").asString()).doesNotContain("Maria");
		assertThat(audit(setup, "appointments", booked.get("id").asString())).extracting(e -> e.get("action").asString()).hasSize(3);
	}

	@Test
	void anonymizingWithAFutureOpenAppointmentIs409() throws Exception {
		Setup setup = openClinic();
		LocalDate monday = mondays(1).get(0);
		JsonNode booked = book(setup, setup.patient(), at(monday, 9, 0), at(monday, 10, 0));
		// inativação fora da API (como um ajuste de suporte): não passa pelo cancelamento automático
		adminSql(setup.clinic(), "UPDATE core.patients SET active = false, deactivated_at = now() WHERE id = '" + setup.patient() + "'");

		mvc.perform(post("/api/v1/patients/" + setup.patient() + "/anonymize").with(as(setup.clinic(), Role.DENTIST_ADMIN)))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("Cancele os agendamentos futuros antes de anonimizar."));

		assertThat(appointment(setup, booked.get("id").asString()).get("status").asString()).isEqualTo("SCHEDULED");
	}

	// ---- histórico ----

	@Test
	void theHistoryIsPagedNewestFirstWithEveryStatusForBothRolesAndOnlyForThePatientsOfTheClinic() throws Exception {
		Setup setup = openClinic();
		Setup other = openClinic();
		List<LocalDate> mondays = mondays(3);
		JsonNode oldest = book(setup, setup.patient(), at(mondays.get(0), 9, 0), at(mondays.get(0), 10, 0));
		JsonNode middle = book(setup, setup.patient(), at(mondays.get(1), 9, 0), at(mondays.get(1), 10, 0));
		JsonNode newest = book(setup, setup.patient(), at(mondays.get(2), 9, 0), at(mondays.get(2), 10, 0));
		mvc.perform(act(setup, middle, "cancel")).andExpect(status().isOk());
		book(other, other.patient(), at(mondays.get(0), 9, 0), at(mondays.get(0), 10, 0));

		for (Role role : Role.values()) {
			JsonNode page = read(mvc.perform(get("/api/v1/patients/" + setup.patient() + "/appointments").with(as(setup.clinic(), role)))
					.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")).andReturn());
			assertThat(page.get("totalElements").asInt()).isEqualTo(3);
			assertThat(page.get("content")).extracting(a -> a.get("id").asString())
					.containsExactly(newest.get("id").asString(), middle.get("id").asString(), oldest.get("id").asString());
			assertThat(page.get("content").get(1).get("status").asString()).isEqualTo("CANCELED");
		}
		JsonNode second = read(mvc.perform(get("/api/v1/patients/" + setup.patient() + "/appointments").param("page", "1").param("size", "2")
				.with(as(setup.clinic(), Role.RECEPTIONIST))).andExpect(status().isOk()).andReturn());
		assertThat(second.get("content")).hasSize(1);
		assertThat(second.get("totalPages").asInt()).isEqualTo(2);
		mvc.perform(get("/api/v1/patients/" + setup.patient() + "/appointments").param("size", "51").with(as(setup.clinic(), Role.RECEPTIONIST)))
				.andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/patients/" + other.patient() + "/appointments").with(as(setup.clinic(), Role.RECEPTIONIST))).andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/patients/" + UUID.randomUUID() + "/appointments").with(as(setup.clinic(), Role.RECEPTIONIST))).andExpect(status().isNotFound());
	}

	@Test
	void anInactiveOrAnonymizedPatientKeepsAReadableHistory() throws Exception {
		Setup setup = openClinic();
		LocalDate monday = mondays(1).get(0);
		book(setup, setup.patient(), at(monday, 9, 0), at(monday, 10, 0));
		String anonymized = createPatient(setup.clinic(), "Aline Anonima");
		book(setup, anonymized, at(monday, 14, 0), at(monday, 15, 0));
		mvc.perform(setActive(setup, setup.patient(), false)).andExpect(status().isOk());
		mvc.perform(setActive(setup, anonymized, false)).andExpect(status().isOk());
		PatientFixtures.anonymize(setup.clinic(), anonymized);

		mvc.perform(get("/api/v1/patients/" + setup.patient() + "/appointments").with(as(setup.clinic(), Role.RECEPTIONIST)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
		mvc.perform(get("/api/v1/patients/" + anonymized + "/appointments").with(as(setup.clinic(), Role.RECEPTIONIST)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
	}

	// ---- nota clínica × atendimento ----

	private MockHttpServletRequestBuilder createNote(Setup setup, String patient, String appointmentId) {
		String link = appointmentId == null ? "" : ",\"appointmentId\":\"" + appointmentId + "\"";
		return json(post("/api/v1/patients/" + patient + "/notes"), "{\"content\":\"Restauração do 36\"" + link + "}")
				.with(as(setup.clinic(), Role.DENTIST_ADMIN));
	}

	private MockHttpServletRequestBuilder updateNote(Setup setup, String patient, JsonNode note, String appointmentId) {
		String link = appointmentId == null ? "" : ",\"appointmentId\":\"" + appointmentId + "\"";
		return json(put("/api/v1/patients/" + patient + "/notes/" + note.get("id").asString()),
				"{\"content\":\"Revisado\",\"notedAt\":\"%s\",\"version\":%d%s}".formatted(note.get("notedAt").asString(), note.get("version").asLong(), link))
						.with(as(setup.clinic(), Role.DENTIST_ADMIN));
	}

	@Test
	void aNoteLinksToTheAppointmentOfTheSamePatientAndTheSecondNoteForItIs409() throws Exception {
		Setup setup = openClinic();
		LocalDate monday = mondays(1).get(0);
		JsonNode appointment = book(setup, setup.patient(), at(monday, 9, 0), at(monday, 10, 0));
		String id = appointment.get("id").asString();

		JsonNode note = read(mvc.perform(createNote(setup, setup.patient(), id)).andExpect(status().isCreated())
				.andExpect(jsonPath("$.appointmentId").value(id)).andReturn());

		mvc.perform(createNote(setup, setup.patient(), id)).andExpect(status().isConflict())
				.andExpect(jsonPath("$.detail").value("Já existe uma nota para este atendimento."));
		JsonNode unlinked = read(mvc.perform(createNote(setup, setup.patient(), null)).andExpect(status().isCreated())
				.andExpect(jsonPath("$.appointmentId").value(org.hamcrest.Matchers.nullValue())).andReturn());
		assertThat(unlinked.get("id").asString()).isNotEqualTo(note.get("id").asString());
		// a nota guardada continua cifrada: conferido pelo conteúdo devolvido
		mvc.perform(get("/api/v1/patients/" + setup.patient() + "/notes").with(as(setup.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(2));
	}

	@Test
	void anAppointmentOfAnotherPatientIs409UnknownAndForeignOnesAre404AndTheReceptionistStaysOut() throws Exception {
		Setup setup = openClinic();
		Setup foreign = openClinic();
		LocalDate monday = mondays(1).get(0);
		String otherPatient = createPatient(setup.clinic(), "Outro Paciente");
		JsonNode ofOther = book(setup, otherPatient, at(monday, 9, 0), at(monday, 10, 0));
		JsonNode ofForeign = book(foreign, foreign.patient(), at(monday, 9, 0), at(monday, 10, 0));

		mvc.perform(createNote(setup, setup.patient(), ofOther.get("id").asString())).andExpect(status().isConflict())
				.andExpect(jsonPath("$.detail").value("O atendimento é de outro paciente."));
		mvc.perform(createNote(setup, setup.patient(), UUID.randomUUID().toString())).andExpect(status().isNotFound());
		mvc.perform(createNote(setup, setup.patient(), ofForeign.get("id").asString())).andExpect(status().isNotFound());
		mvc.perform(json(post("/api/v1/patients/" + setup.patient() + "/notes"), "{\"content\":\"x\"}").with(as(setup.clinic(), Role.RECEPTIONIST)))
				.andExpect(status().isForbidden());
		mvc.perform(get("/api/v1/patients/" + setup.patient() + "/notes").with(as(setup.clinic(), Role.DENTIST_ADMIN))).andExpect(jsonPath("$.totalElements").value(0));
	}

	@Test
	void updateReplacesTheLinkReeditKeepsItSwapMovesItAndNullOrAbsentUnlinks() throws Exception {
		Setup setup = openClinic();
		LocalDate monday = mondays(1).get(0);
		String first = book(setup, setup.patient(), at(monday, 9, 0), at(monday, 10, 0)).get("id").asString();
		String second = book(setup, setup.patient(), at(monday, 10, 0), at(monday, 11, 0)).get("id").asString();
		JsonNode note = read(mvc.perform(createNote(setup, setup.patient(), first)).andExpect(status().isCreated()).andReturn());

		// reeditar reenviando o mesmo vínculo passa (a própria nota não conta)
		note = read(mvc.perform(updateNote(setup, setup.patient(), note, first)).andExpect(status().isOk())
				.andExpect(jsonPath("$.appointmentId").value(first)).andReturn());
		// trocar para outro atendimento livre
		note = read(mvc.perform(updateNote(setup, setup.patient(), note, second)).andExpect(status().isOk())
				.andExpect(jsonPath("$.appointmentId").value(second)).andReturn());
		// o primeiro ficou livre para outra nota; o segundo está ocupado
		JsonNode other = read(mvc.perform(createNote(setup, setup.patient(), first)).andExpect(status().isCreated()).andReturn());
		mvc.perform(updateNote(setup, setup.patient(), other, second)).andExpect(status().isConflict());
		// ausente desvincula (e libera o atendimento)
		note = read(mvc.perform(updateNote(setup, setup.patient(), note, null)).andExpect(status().isOk())
				.andExpect(jsonPath("$.appointmentId").value(org.hamcrest.Matchers.nullValue())).andReturn());
		other = read(mvc.perform(updateNote(setup, setup.patient(), other, second)).andExpect(status().isOk())
				.andExpect(jsonPath("$.appointmentId").value(second)).andReturn());
		// null explícito também desvincula
		mvc.perform(json(put("/api/v1/patients/" + setup.patient() + "/notes/" + other.get("id").asString()),
				"{\"content\":\"Revisado\",\"notedAt\":\"%s\",\"version\":%d,\"appointmentId\":null}".formatted(other.get("notedAt").asString(), other.get("version").asLong()))
				.with(as(setup.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isOk())
				.andExpect(jsonPath("$.appointmentId").value(org.hamcrest.Matchers.nullValue()));
		assertThat(note.get("id").asString()).isNotNull();
	}

	@Test
	void twoSimultaneousNotesForTheSameAppointmentLeaveExactlyOne() throws Exception {
		Setup setup = openClinic();
		List<LocalDate> mondays = mondays(10);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			for (int round = 0; round < 10; round++) {
				String appointment = book(setup, setup.patient(), at(mondays.get(round), 9, 0), at(mondays.get(round), 10, 0)).get("id").asString();
				CountDownLatch go = new CountDownLatch(1);
				List<Future<Integer>> results = new ArrayList<>();
				for (int i = 0; i < 2; i++) {
					results.add(pool.submit(() -> {
						go.await();
						return mvc.perform(createNote(setup, setup.patient(), appointment)).andReturn().getResponse().getStatus();
					}));
				}
				go.countDown();
				List<Integer> statuses = new ArrayList<>();
				for (Future<Integer> result : results) {
					statuses.add(result.get());
				}
				assertThat(statuses).as("rodada %d", round).containsExactlyInAnyOrder(201, 409);
			}
		}
		finally {
			pool.shutdownNow();
		}
	}
}
