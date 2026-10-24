package crm_data.phase2;

import static crm_data.phase2.AgendaDates.at;
import static crm_data.phase2.AgendaDates.monday;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import crm_data.shared.security.Role;
import crm_data.support.SqlRecordingDataSourceConfig;
import tools.jackson.databind.JsonNode;

/**
 * A matriz de papéis da agenda contra o contexto completo e o PostgreSQL: o 401 e o 403 da recepção são provados por
 * "nenhuma instrução JDBC executada", e a recusa não deixa nenhum rastro nos dados. A cobertura rota a rota com serviços
 * simulados está em {@link SchedulingRoleMatrixWebTest}.
 */
class SchedulingRoleMatrixIT extends Phase2ItSupport {

	private static MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder request, String json) {
		return request.contentType("application/json").content(json);
	}

	private Map<String, MockHttpServletRequestBuilder> dentistOnlyRoutes(JsonNode appointment, String holidayId) {
		String action = "{\"version\":" + appointment.get("version").asLong() + "}";
		String id = appointment.get("id").asString();
		Map<String, MockHttpServletRequestBuilder> routes = new LinkedHashMap<>();
		routes.put("start", body(post("/api/v1/appointments/" + id + "/start"), action));
		routes.put("finish", body(post("/api/v1/appointments/" + id + "/finish"), action));
		routes.put("reactivate", body(post("/api/v1/appointments/" + id + "/reactivate"), action));
		routes.put("PUT /business-hours", body(put("/api/v1/business-hours"), "{\"days\":[{\"weekday\":7,\"isOpen\":false}]}"));
		routes.put("POST /clinic-holidays", body(post("/api/v1/clinic-holidays"), "{\"date\":\"2027-12-24\",\"name\":\"Recesso\"}"));
		routes.put("PUT /clinic-holidays", body(put("/api/v1/clinic-holidays/" + holidayId), "{\"date\":\"2027-12-26\",\"name\":\"Recesso\",\"version\":0}"));
		routes.put("DELETE /clinic-holidays", delete("/api/v1/clinic-holidays/" + holidayId));
		routes.put("PUT /clinic", body(put("/api/v1/clinic"), "{\"name\":\"Clínica\",\"timezone\":\"UTC\",\"version\":0}"));
		return routes;
	}

	@Test
	void receptionistGets403OnTheDentistRoutesWithoutAnyStatementAndNothingChanges() throws Exception {
		Setup setup = openClinic();
		LocalDate monday = monday();
		JsonNode appointment = read(mvc.perform(body(post("/api/v1/appointments"),
				"{\"patientId\":\"%s\",\"startsAt\":\"%s\",\"endsAt\":\"%s\"}".formatted(setup.patient(), at(monday, 9, 0), at(monday, 10, 0)))
				.with(as(setup.clinic(), Role.RECEPTIONIST))).andExpect(status().isCreated()).andReturn());
		String holidayId = read(mvc.perform(body(post("/api/v1/clinic-holidays"), "{\"date\":\"2027-12-25\",\"name\":\"Natal da clínica\"}")
				.with(as(setup.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isCreated()).andReturn()).get("holiday").get("id").asString();

		for (Map.Entry<String, MockHttpServletRequestBuilder> route : dentistOnlyRoutes(appointment, holidayId).entrySet()) {
			SqlRecordingDataSourceConfig.reset();

			mvc.perform(route.getValue().with(as(setup.clinic(), Role.RECEPTIONIST))).andExpect(status().isForbidden())
					.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
					.andExpect(jsonPath("$.type").value("https://crm.local/problems/forbidden"));

			assertThat(SqlRecordingDataSourceConfig.prepared()).as("%s: nenhuma instrução SQL", route.getKey()).isEmpty();
		}
		JsonNode unchanged = read(mvc.perform(get("/api/v1/appointments/" + appointment.get("id").asString()).with(as(setup.clinic(), Role.DENTIST_ADMIN)))
				.andReturn());
		assertThat(unchanged.get("status").asString()).isEqualTo("SCHEDULED");
		assertThat(unchanged.get("version").asLong()).isEqualTo(appointment.get("version").asLong());
		assertThat(mapper.readTree(mvc.perform(get("/api/v1/holidays").param("from", "2027-12-01").param("to", "2027-12-31")
				.with(as(setup.clinic(), Role.RECEPTIONIST))).andReturn().getResponse().getContentAsString())).hasSize(2); // Natal nacional + o da clínica
	}

	@Test
	void withoutTokenTheDatabaseIsNeverReached() throws Exception {
		Setup setup = openClinic();
		LocalDate monday = monday();
		JsonNode appointment = read(mvc.perform(body(post("/api/v1/appointments"),
				"{\"patientId\":\"%s\",\"startsAt\":\"%s\",\"endsAt\":\"%s\"}".formatted(setup.patient(), at(monday, 9, 0), at(monday, 10, 0)))
				.with(as(setup.clinic(), Role.RECEPTIONIST))).andExpect(status().isCreated()).andReturn());
		SqlRecordingDataSourceConfig.reset();
		String id = appointment.get("id").asString();

		mvc.perform(get("/api/v1/appointments").param("from", monday.toString()).param("to", monday.toString())).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/appointments/" + id)).andExpect(status().isUnauthorized());
		mvc.perform(body(post("/api/v1/appointments"), "{}")).andExpect(status().isUnauthorized());
		mvc.perform(body(put("/api/v1/appointments/" + id), "{}")).andExpect(status().isUnauthorized());
		mvc.perform(body(post("/api/v1/appointments/" + id + "/confirm"), "{\"version\":0}")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/business-hours")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/holidays").param("from", "2026-01-01").param("to", "2026-12-31")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/patients/" + setup.patient() + "/appointments")).andExpect(status().isUnauthorized());

		assertThat(SqlRecordingDataSourceConfig.prepared()).as("nenhuma instrução SQL nas rotas sem token").isEmpty();
	}

	@Test
	void receptionistFittingIs403AndNothingIsWrittenEvenInTheRulesThatWouldBlockHer() throws Exception {
		Setup setup = openClinic();
		LocalDate sunday = monday().plusDays(6);

		mvc.perform(body(post("/api/v1/appointments"), "{\"patientId\":\"%s\",\"startsAt\":\"%s\",\"endsAt\":\"%s\",\"allowOutsideHours\":true}"
				.formatted(setup.patient(), at(sunday, 9, 0), at(sunday, 10, 0))).with(as(setup.clinic(), Role.RECEPTIONIST))).andExpect(status().isForbidden());

		assertThat(mapper.readTree(mvc.perform(get("/api/v1/appointments").param("from", sunday.toString()).param("to", sunday.toString())
				.with(as(setup.clinic(), Role.DENTIST_ADMIN))).andReturn().getResponse().getContentAsString())).isEmpty();
	}

	@Test
	void theReceptionistKeepsEveryReadAndTheOperationalWrites() throws Exception {
		Setup setup = openClinic();
		LocalDate monday = monday();
		JsonNode appointment = read(mvc.perform(body(post("/api/v1/appointments"),
				"{\"patientId\":\"%s\",\"startsAt\":\"%s\",\"endsAt\":\"%s\"}".formatted(setup.patient(), at(monday, 9, 0), at(monday, 10, 0)))
				.with(as(setup.clinic(), Role.RECEPTIONIST))).andExpect(status().isCreated()).andReturn());
		String id = appointment.get("id").asString();

		mvc.perform(get("/api/v1/clinic").with(as(setup.clinic(), Role.RECEPTIONIST))).andExpect(status().isOk());
		mvc.perform(get("/api/v1/business-hours").with(as(setup.clinic(), Role.RECEPTIONIST))).andExpect(status().isOk());
		mvc.perform(get("/api/v1/holidays").param("from", "2026-01-01").param("to", "2026-12-31").with(as(setup.clinic(), Role.RECEPTIONIST)))
				.andExpect(status().isOk());
		mvc.perform(get("/api/v1/appointments/" + id).with(as(setup.clinic(), Role.RECEPTIONIST))).andExpect(status().isOk());
		mvc.perform(get("/api/v1/patients/" + setup.patient() + "/appointments").with(as(setup.clinic(), Role.RECEPTIONIST))).andExpect(status().isOk());
		mvc.perform(body(post("/api/v1/appointments/" + id + "/confirm"), "{\"version\":0}").with(as(setup.clinic(), Role.RECEPTIONIST)))
				.andExpect(status().isOk());
		mvc.perform(body(post("/api/v1/appointments/" + id + "/cancel"), "{\"version\":1}").with(as(setup.clinic(), Role.RECEPTIONIST)))
				.andExpect(status().isOk());
		mvc.perform(get("/api/v1/audit-log").param("tableName", "appointments").with(as(setup.clinic(), Role.RECEPTIONIST))).andExpect(status().isForbidden());
		assertThat(UUID.fromString(id)).isNotNull();
	}
}
