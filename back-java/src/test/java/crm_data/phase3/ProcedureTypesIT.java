package crm_data.phase3;

import static crm_data.phase2.AgendaDates.at;
import static crm_data.phase2.AgendaDates.monday;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import crm_data.shared.error.DatabaseErrors;
import crm_data.shared.security.Role;
import crm_data.support.DatabaseContainer;
import crm_data.support.SqlRecordingDataSourceConfig;
import crm_data.support.tenant.TenantFixtures.Clinic;
import tools.jackson.databind.JsonNode;

/**
 * F02 contra o PostgreSQL real (V001–V029): catálogo de procedimentos (papéis, unicidade do nome entre os ativos, versão,
 * ativar/inativar, isolamento) e o procedimento na agenda (substituição no {@code PUT}, inativo, resumo em lote).
 */
class ProcedureTypesIT extends Phase3ItSupport {

	// ---- auxiliares ----

	private JsonNode procedure(Clinic clinic, Role role, String id) throws Exception {
		return read(mvc.perform(get("/api/v1/procedure-types/" + id).with(as(clinic, role))).andExpect(status().isOk()).andReturn());
	}

	private void setActive(Clinic clinic, String id, boolean active, long version, int expected) throws Exception {
		mvc.perform(json(patch("/api/v1/procedure-types/" + id + "/active"), "{\"active\":%s,\"version\":%d}".formatted(active, version))
				.with(as(clinic, Role.DENTIST_ADMIN))).andExpect(status().is(expected));
	}

	private static String appointmentBody(String patient, String start, String end, String procedure) {
		return "{\"patientId\":\"%s\",\"startsAt\":\"%s\",\"endsAt\":\"%s\"%s}".formatted(patient, start, end,
				procedure == null ? "" : ",\"procedureTypeId\":\"" + procedure + "\"");
	}

	private JsonNode bookWith(Setup setup, String start, String end, String procedure) throws Exception {
		return read(mvc.perform(json(post("/api/v1/appointments"), appointmentBody(setup.patient(), start, end, procedure))
				.with(as(setup.clinic(), Role.RECEPTIONIST))).andExpect(status().isCreated()).andReturn());
	}

	// ---- catálogo ----

	@Test
	void theDentistCreatesAndBothRolesReadWhileTheReceptionistCannotWrite() throws Exception {
		Clinic clinic = newClinic();

		JsonNode created = read(mvc.perform(json(post("/api/v1/procedure-types"), procedureBody("Restauração", "250.5", 45, "#3B82F6"))
				.with(as(clinic, Role.DENTIST_ADMIN))).andExpect(status().isCreated()).andExpect(header().exists("Location"))
				.andExpect(header().string("Cache-Control", "no-store")).andReturn());

		assertThat(created.get("basePrice").asString()).isEqualTo("250.50");
		assertThat(created.get("active").asBoolean()).isTrue();
		assertThat(created.get("version").asLong()).isZero();
		assertThat(created.has("clinicId")).isFalse();
		String id = created.get("id").asString();
		for (Role role : Role.values()) {
			assertThat(procedure(clinic, role, id).get("name").asString()).isEqualTo("Restauração");
			mvc.perform(get("/api/v1/procedure-types").with(as(clinic, role))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
		}
		mvc.perform(json(post("/api/v1/procedure-types"), procedureBody("Canal", "900", 90, "#EF4444")).with(as(clinic, Role.RECEPTIONIST)))
				.andExpect(status().isForbidden());
		mvc.perform(json(put("/api/v1/procedure-types/" + id), procedureBody("X1", "1", 5, "#000000").replace("}", ",\"version\":0}"))
				.with(as(clinic, Role.RECEPTIONIST))).andExpect(status().isForbidden());
		setActive(clinic, id, false, 0, 200);
	}

	@Test
	void serverOwnedFieldsInTheBodyAreIgnored() throws Exception {
		Clinic clinic = newClinic();
		Clinic other = newClinic();
		String body = "{\"name\":\"Canal\",\"basePrice\":\"900\",\"defaultDurationMinutes\":90,\"color\":\"#EF4444\",\"active\":false,"
				+ "\"clinicId\":\"%s\",\"createdBy\":\"%s\",\"id\":\"%s\"}".formatted(other.id(), UUID.randomUUID(), UUID.randomUUID());

		JsonNode created = read(mvc.perform(json(post("/api/v1/procedure-types"), body).with(as(clinic, Role.DENTIST_ADMIN)))
				.andExpect(status().isCreated()).andReturn());

		assertThat(created.get("active").asBoolean()).as("nasce ativo, o cliente não decide").isTrue();
		assertThat(DatabaseContainer.admin().queryForObject("SELECT clinic_id FROM core.procedure_types WHERE id = ?::uuid", UUID.class, created.get("id").asString()))
				.isEqualTo(clinic.id());
		assertThat(DatabaseContainer.admin().queryForObject("SELECT created_by FROM core.procedure_types WHERE id = ?::uuid", UUID.class, created.get("id").asString()))
				.isEqualTo(userOf(clinic, Role.DENTIST_ADMIN));
	}

	@Test
	void aDuplicateActiveNameIs409ForAnyLetterCaseButAnotherClinicAndAnInactiveNameAreFine() throws Exception {
		Clinic clinic = newClinic();
		Clinic other = newClinic();
		String first = createProcedure(clinic, "Limpeza", "150", 30);

		for (String duplicate : new String[] { "Limpeza", "limpeza", "LIMPEZA", "LiMpEzA" }) {
			mvc.perform(json(post("/api/v1/procedure-types"), procedureBody(duplicate, "10", 30, "#3B82F6")).with(as(clinic, Role.DENTIST_ADMIN)))
					.andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("Já existe um procedimento ativo com este nome."));
		}
		createProcedure(other, "Limpeza", "99", 30); // outra clínica passa
		setActive(clinic, first, false, 0, 200);
		String reused = createProcedure(clinic, "Limpeza", "160", 30); // nome de inativo reaproveitado
		setActive(clinic, first, true, 1, 409); // reativar o antigo colide com o novo ativo
		assertThat(procedure(clinic, Role.DENTIST_ADMIN, first).get("active").asBoolean()).isFalse();
		assertThat(reused).isNotEqualTo(first);
	}

	@Test
	void updateChangesTheFieldsAndAStaleVersionIs409() throws Exception {
		Clinic clinic = newClinic();
		String id = createProcedure(clinic, "Canal", "900", 90);

		JsonNode updated = read(mvc.perform(json(put("/api/v1/procedure-types/" + id), procedureBody("Canal molar", "1200.00", 120, "#10B981")
				.replace("}", ",\"version\":0}")).with(as(clinic, Role.DENTIST_ADMIN))).andExpect(status().isOk()).andReturn());

		assertThat(updated.get("name").asString()).isEqualTo("Canal molar");
		assertThat(updated.get("basePrice").asString()).isEqualTo("1200.00");
		assertThat(updated.get("defaultDurationMinutes").asInt()).isEqualTo(120);
		assertThat(updated.get("color").asString()).isEqualTo("#10B981");
		assertThat(updated.get("version").asLong()).isEqualTo(1);
		mvc.perform(json(put("/api/v1/procedure-types/" + id), procedureBody("Canal", "900", 90, "#3B82F6").replace("}", ",\"version\":0}"))
				.with(as(clinic, Role.DENTIST_ADMIN))).andExpect(status().isConflict());
		setActive(clinic, id, false, 0, 409);
	}

	@Test
	void theListFiltersByActiveAndIsOrderedByNameIgnoringCase() throws Exception {
		Clinic clinic = newClinic();
		createProcedure(clinic, "extração", "200", 40);
		String off = createProcedure(clinic, "Canal", "900", 90);
		createProcedure(clinic, "Aplicação de flúor", "80", 20);
		setActive(clinic, off, false, 0, 200);

		String active = mvc.perform(get("/api/v1/procedure-types").with(as(clinic, Role.RECEPTIONIST))).andExpect(status().isOk()).andReturn()
				.getResponse().getContentAsString();
		String inactive = mvc.perform(get("/api/v1/procedure-types").param("active", "false").with(as(clinic, Role.RECEPTIONIST)))
				.andReturn().getResponse().getContentAsString();
		JsonNode all = read(mvc.perform(get("/api/v1/procedure-types").param("active", "all").with(as(clinic, Role.DENTIST_ADMIN)))
				.andExpect(status().isOk()).andReturn());

		assertThat(active).contains("Aplicação de flúor", "extração").doesNotContain("Canal");
		assertThat(active.indexOf("Aplicação")).isLessThan(active.indexOf("extração"));
		assertThat(inactive).contains("Canal").doesNotContain("extração");
		assertThat(all).hasSize(3);
		assertThat(all.get(0).get("name").asString()).isEqualTo("Aplicação de flúor");
		assertThat(all.get(1).get("name").asString()).isEqualTo("Canal");
		assertThat(all.get(2).get("name").asString()).isEqualTo("extração");
	}

	@Test
	void validationReportsEveryFieldAndRejectsJsonNumbersForThePriceAndDecimalsForTheDuration() throws Exception {
		Clinic clinic = newClinic();

		String errors = mvc.perform(json(post("/api/v1/procedure-types"), "{\"name\":\" \",\"basePrice\":\"-5\",\"defaultDurationMinutes\":7,\"color\":\"azul\"}")
				.with(as(clinic, Role.DENTIST_ADMIN))).andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
		assertThat(errors).contains("\"field\":\"name\"", "\"field\":\"basePrice\"", "\"field\":\"defaultDurationMinutes\"", "\"field\":\"color\"");
		mvc.perform(json(post("/api/v1/procedure-types"), "{\"name\":\"Canal\",\"basePrice\":900.5,\"defaultDurationMinutes\":30,\"color\":\"#3B82F6\"}")
				.with(as(clinic, Role.DENTIST_ADMIN))).andExpect(status().isBadRequest());
		mvc.perform(json(post("/api/v1/procedure-types"), "{\"name\":\"Canal\",\"basePrice\":\"900\",\"defaultDurationMinutes\":30.5,\"color\":\"#3B82F6\"}")
				.with(as(clinic, Role.DENTIST_ADMIN))).andExpect(status().isBadRequest());
		assertThat(DatabaseContainer.admin().queryForObject("SELECT count(*) FROM core.procedure_types WHERE clinic_id = ?", Long.class, clinic.id())).isZero();
	}

	@Test
	void aProcedureOfAnotherClinicIs404AndTheListNeverShowsIt() throws Exception {
		Clinic clinic = newClinic();
		Clinic other = newClinic();
		String foreign = createProcedure(other, "Canal", "900", 90);

		mvc.perform(get("/api/v1/procedure-types/" + foreign).with(as(clinic, Role.DENTIST_ADMIN))).andExpect(status().isNotFound());
		mvc.perform(json(put("/api/v1/procedure-types/" + foreign), procedureBody("Canal", "1", 5, "#000000").replace("}", ",\"version\":0}"))
				.with(as(clinic, Role.DENTIST_ADMIN))).andExpect(status().isNotFound());
		setActive(clinic, foreign, false, 0, 404);
		mvc.perform(get("/api/v1/procedure-types").param("active", "all").with(as(clinic, Role.DENTIST_ADMIN)))
				.andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void theDatabaseItselfHidesOtherClinicsRowsAndRefusesTheReceptionistsWrites() throws Exception {
		Clinic clinic = newClinic();
		Clinic other = newClinic();
		createProcedure(other, "Canal", "900", 90);
		createProcedure(clinic, "Limpeza", "150", 30);

		// sem filtro Hibernate: SQL cru sob a sessão da clínica; a RLS é a única barreira
		assertThat(asRole(clinic, Role.DENTIST_ADMIN, () -> jdbc.queryForList("SELECT name FROM core.procedure_types", String.class)))
				.containsExactly("Limpeza");
		assertThat(asRole(clinic, Role.RECEPTIONIST, () -> jdbc.queryForList("SELECT name FROM core.procedure_types", String.class)))
				.containsExactly("Limpeza"); // a recepção lê o catálogo
		assertThatThrownBy(() -> asRole(clinic, Role.RECEPTIONIST, () -> jdbc.update("""
				INSERT INTO core.procedure_types (id, clinic_id, name, base_price, default_duration_minutes, color, created_by)
				VALUES (?, core.current_clinic_id(), 'Forjado', 10, 30, '#000000', core.current_user_id())""", UUID.randomUUID())))
				.satisfies(e -> assertThat(DatabaseErrors.sqlState(e)).contains("42501"));
		assertThat(asRole(clinic, Role.RECEPTIONIST, () -> jdbc.update("UPDATE core.procedure_types SET base_price = 1"))).isZero();
		assertThatThrownBy(() -> asRole(clinic, Role.DENTIST_ADMIN, () -> jdbc.update("DELETE FROM core.procedure_types")))
				.as("crm_app não apaga procedimento").satisfies(e -> assertThat(DatabaseErrors.sqlState(e)).contains("42501"));
	}

	// ---- procedimento na agenda ----

	@Test
	void anAppointmentCarriesTheProcedureSummaryOnCreateGetListAndHistory() throws Exception {
		Setup setup = openClinic();
		String procedure = createProcedure(setup.clinic(), "Restauração", "250", 45);
		LocalDate monday = monday();

		JsonNode created = bookWith(setup, at(monday, 9, 0), at(monday, 9, 45), procedure);
		String id = created.get("id").asString();

		assertThat(created.get("procedure").get("id").asString()).isEqualTo(procedure);
		assertThat(created.get("procedure").get("name").asString()).isEqualTo("Restauração");
		assertThat(created.get("procedure").get("color").asString()).isEqualTo("#3B82F6");
		assertThat(created.has("procedureTypeId")).isFalse();
		for (Role role : Role.values()) {
			JsonNode fetched = read(mvc.perform(get("/api/v1/appointments/" + id).with(as(setup.clinic(), role))).andExpect(status().isOk()).andReturn());
			assertThat(fetched.get("procedure").get("name").asString()).isEqualTo("Restauração");
		}
		JsonNode agenda = read(mvc.perform(get("/api/v1/appointments").param("from", monday.toString()).param("to", monday.toString())
				.with(as(setup.clinic(), Role.RECEPTIONIST))).andExpect(status().isOk()).andReturn());
		assertThat(agenda.get(0).get("procedure").get("name").asString()).isEqualTo("Restauração");
		JsonNode history = read(mvc.perform(get("/api/v1/patients/" + setup.patient() + "/appointments").with(as(setup.clinic(), Role.RECEPTIONIST)))
				.andExpect(status().isOk()).andReturn());
		assertThat(history.get("content").get(0).get("procedure").get("name").asString()).isEqualTo("Restauração");
		JsonNode confirmed = read(mvc.perform(json(post("/api/v1/appointments/" + id + "/confirm"), "{\"version\":0}")
				.with(as(setup.clinic(), Role.RECEPTIONIST))).andExpect(status().isOk()).andReturn());
		assertThat(confirmed.get("procedure").get("name").asString()).as("as ações de status também devolvem o procedimento").isEqualTo("Restauração");
	}

	@Test
	void anAppointmentWithoutProcedureKeepsWorkingAndHasANullProcedure() throws Exception {
		Setup setup = openClinic();
		LocalDate monday = monday();

		JsonNode created = bookWith(setup, at(monday, 9, 0), at(monday, 9, 45), null);

		assertThat(created.get("procedure").isNull()).isTrue();
	}

	@Test
	void aProcedureThatDoesNotExistOrBelongsToAnotherClinicIs404AndAnInactiveOneIs422() throws Exception {
		Setup setup = openClinic();
		Clinic other = newClinic();
		String foreign = createProcedure(other, "Canal", "900", 90);
		String inactive = createProcedure(setup.clinic(), "Antigo", "10", 30);
		setActive(setup.clinic(), inactive, false, 0, 200);
		LocalDate monday = monday();

		for (String bad : new String[] { foreign, UUID.randomUUID().toString() }) {
			mvc.perform(json(post("/api/v1/appointments"), appointmentBody(setup.patient(), at(monday, 9, 0), at(monday, 9, 45), bad))
					.with(as(setup.clinic(), Role.RECEPTIONIST))).andExpect(status().isNotFound());
		}
		mvc.perform(json(post("/api/v1/appointments"), appointmentBody(setup.patient(), at(monday, 9, 0), at(monday, 9, 45), inactive))
				.with(as(setup.clinic(), Role.RECEPTIONIST))).andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.reason").value("PROCEDURE_INACTIVE"));
		assertThat(DatabaseContainer.admin().queryForObject("SELECT count(*) FROM core.appointments WHERE clinic_id = ?", Long.class, setup.clinic().id())).isZero();
	}

	@Test
	void thePutReplacesTheProcedureAndAbsentOrNullRemovesIt() throws Exception {
		Setup setup = openClinic();
		String first = createProcedure(setup.clinic(), "Restauração", "250", 45);
		String second = createProcedure(setup.clinic(), "Canal", "900", 90);
		LocalDate monday = monday();
		JsonNode created = bookWith(setup, at(monday, 9, 0), at(monday, 9, 45), first);
		String id = created.get("id").asString();
		String window = "\"startsAt\":\"%s\",\"endsAt\":\"%s\"".formatted(at(monday, 9, 0), at(monday, 9, 45));

		MockHttpServletRequestBuilder swap = json(put("/api/v1/appointments/" + id), "{" + window + ",\"procedureTypeId\":\"" + second + "\",\"version\":0}")
				.with(as(setup.clinic(), Role.RECEPTIONIST));
		JsonNode swapped = read(mvc.perform(swap).andExpect(status().isOk()).andReturn());
		assertThat(swapped.get("procedure").get("name").asString()).isEqualTo("Canal");

		JsonNode cleared = read(mvc.perform(json(put("/api/v1/appointments/" + id), "{" + window + ",\"version\":%d}".formatted(swapped.get("version").asLong()))
				.with(as(setup.clinic(), Role.RECEPTIONIST))).andExpect(status().isOk()).andReturn());
		assertThat(cleared.get("procedure").isNull()).isTrue();
		assertThat(DatabaseContainer.admin().queryForObject("SELECT procedure_type_id FROM core.appointments WHERE id = ?::uuid", UUID.class, id)).isNull();

		JsonNode viaNull = read(mvc.perform(json(put("/api/v1/appointments/" + id),
				"{" + window + ",\"procedureTypeId\":\"" + first + "\",\"version\":%d}".formatted(cleared.get("version").asLong()))
				.with(as(setup.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isOk()).andReturn());
		JsonNode explicitNull = read(mvc.perform(json(put("/api/v1/appointments/" + id),
				"{" + window + ",\"procedureTypeId\":null,\"version\":%d}".formatted(viaNull.get("version").asLong()))
				.with(as(setup.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isOk()).andReturn());
		assertThat(explicitNull.get("procedure").isNull()).isTrue();
	}

	@Test
	void anAppointmentKeepsItsInactivatedProcedureInTheHistoryButCannotBeEditedWithIt() throws Exception {
		Setup setup = openClinic();
		String procedure = createProcedure(setup.clinic(), "Restauração", "250", 45);
		LocalDate monday = monday();
		JsonNode created = bookWith(setup, at(monday, 9, 0), at(monday, 9, 45), procedure);
		String id = created.get("id").asString();
		setActive(setup.clinic(), procedure, false, 0, 200);
		String window = "\"startsAt\":\"%s\",\"endsAt\":\"%s\"".formatted(at(monday, 9, 0), at(monday, 9, 45));

		JsonNode fetched = read(mvc.perform(get("/api/v1/appointments/" + id).with(as(setup.clinic(), Role.RECEPTIONIST))).andExpect(status().isOk()).andReturn());
		assertThat(fetched.get("procedure").get("name").asString()).as("o histórico não muda").isEqualTo("Restauração");
		mvc.perform(json(put("/api/v1/appointments/" + id), "{" + window + ",\"procedureTypeId\":\"" + procedure + "\",\"version\":0}")
				.with(as(setup.clinic(), Role.RECEPTIONIST))).andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.reason").value("PROCEDURE_INACTIVE"));
	}

	@Test
	void theAgendaResolvesTheProceduresOfManyAppointmentsInASingleQuery() throws Exception {
		Setup setup = openClinic();
		String procedure = createProcedure(setup.clinic(), "Restauração", "250", 45);
		LocalDate monday = monday();
		for (int hour = 8; hour < 12; hour++) {
			bookWith(setup, at(monday, hour, 0), at(monday, hour, 30), procedure);
		}
		SqlRecordingDataSourceConfig.reset();

		JsonNode agenda = read(mvc.perform(get("/api/v1/appointments").param("from", monday.toString()).param("to", monday.toString())
				.with(as(setup.clinic(), Role.RECEPTIONIST))).andExpect(status().isOk()).andReturn());

		assertThat(agenda).hasSize(4);
		assertThat(SqlRecordingDataSourceConfig.prepared()).filteredOn(sql -> sql.toLowerCase().contains("from core.procedure_types"))
				.as("uma consulta de procedimentos para a lista inteira").hasSize(1);
	}

	@Test
	void theDatabaseForeignKeyRefusesAProcedureOfAnotherClinicEvenWithoutTheBackendChecks() throws Exception {
		Setup setup = openClinic();
		Clinic other = newClinic();
		String foreign = createProcedure(other, "Canal", "900", 90);
		LocalDate monday = monday();
		JsonNode created = bookWith(setup, at(monday, 9, 0), at(monday, 9, 45), null);

		assertThatThrownBy(() -> asRole(setup.clinic(), Role.DENTIST_ADMIN, () -> jdbc.update(
				"UPDATE core.appointments SET procedure_type_id = ?::uuid WHERE id = ?::uuid", foreign, created.get("id").asString())))
				.satisfies(e -> assertThat(DatabaseErrors.sqlState(e)).contains("23503"));
	}
}
