package crm_data.phase4;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import crm_data.shared.security.Role;
import crm_data.support.tenant.TenantFixtures.Clinic;
import tools.jackson.databind.JsonNode;

/**
 * Matriz papel × endpoint da Fase 4: anônimo → 401; recepção → 403 nas rotas do dentista e 200 no dashboard e em
 * {@code GET /me}; dentista → sucesso. Cada linha usa dados reais da clínica de teste.
 */
class Phase4MatrixIT extends Phase4ItSupport {

	private record Row(String name, int anonymous, int reception, int dentist) {
	}

	private MockHttpServletRequestBuilder request(String name, Setup setup, String documentId, long version) {
		String patient = setup.patient();
		return switch (name) {
			case "PUT /me/professional-registry" -> json(put("/api/v1/me/professional-registry"),
					"{\"cro\":\"12345\",\"croState\":\"SP\",\"version\":%d}".formatted(version));
			case "POST /patients/{id}/clinical-documents" -> json(post("/api/v1/patients/" + patient + "/clinical-documents"),
					"{\"documentType\":\"PRESCRIPTION\",\"content\":\"x\"}");
			case "GET /patients/{id}/clinical-documents" -> get("/api/v1/patients/" + patient + "/clinical-documents");
			case "GET /clinical-documents/{id}" -> get("/api/v1/clinical-documents/" + documentId);
			case "GET /clinical-documents/{id}/pdf" -> get("/api/v1/clinical-documents/" + documentId + "/pdf");
			case "POST /clinical-documents/{id}/void" -> json(post("/api/v1/clinical-documents/" + documentId + "/void"), "{\"version\":0}");
			case "GET /dashboard" -> get("/api/v1/dashboard");
			case "GET /me" -> get("/api/v1/me");
			default -> throw new IllegalArgumentException(name);
		};
	}

	@Test
	void everyPhase4EndpointHonoursTheRoleMatrix() throws Exception {
		Setup setup = openDentistSetup();
		Clinic clinic = setup.clinic();
		JsonNode doc = read(mvc.perform(json(post("/api/v1/patients/" + setup.patient() + "/clinical-documents"),
				"{\"documentType\":\"PRESCRIPTION\",\"content\":\"Dipirona\"}").with(as(clinic, Role.DENTIST_ADMIN)))
				.andExpect(status().isCreated()).andReturn());
		String id = doc.get("id").asString();
		long version = versionOf(clinic, Role.DENTIST_ADMIN);

		// a anulação é a última do dentista (muda o estado); as demais não dependem da ordem
		List<Row> rows = List.of(new Row("PUT /me/professional-registry", 401, 403, 200),
				new Row("POST /patients/{id}/clinical-documents", 401, 403, 201), new Row("GET /patients/{id}/clinical-documents", 401, 403, 200),
				new Row("GET /clinical-documents/{id}", 401, 403, 200), new Row("GET /clinical-documents/{id}/pdf", 401, 403, 200),
				new Row("GET /dashboard", 401, 200, 200), new Row("GET /me", 401, 200, 200),
				new Row("POST /clinical-documents/{id}/void", 401, 403, 200));
		for (Row row : rows) {
			assertThat(mvc.perform(request(row.name(), setup, id, version)).andReturn().getResponse().getStatus())
					.as("%s anônimo", row.name()).isEqualTo(row.anonymous());
			assertThat(mvc.perform(request(row.name(), setup, id, version).with(as(clinic, Role.RECEPTIONIST))).andReturn().getResponse().getStatus())
					.as("%s recepção", row.name()).isEqualTo(row.reception());
			assertThat(mvc.perform(request(row.name(), setup, id, version).with(as(clinic, Role.DENTIST_ADMIN))).andReturn().getResponse().getStatus())
					.as("%s dentista", row.name()).isEqualTo(row.dentist());
		}
	}

	@Test
	void unauthenticatedAndForbiddenResponsesAreProblemJsonWithoutDetails() throws Exception {
		Setup setup = openDentistSetup();

		var anonymous = mvc.perform(get("/api/v1/patients/" + setup.patient() + "/clinical-documents")).andReturn().getResponse();
		var reception = mvc.perform(get("/api/v1/patients/" + setup.patient() + "/clinical-documents").with(as(setup.clinic(), Role.RECEPTIONIST)))
				.andReturn().getResponse();

		assertThat(anonymous.getContentType()).contains("problem+json");
		assertThat(reception.getContentType()).contains("problem+json");
		assertThat(reception.getContentAsString()).contains("/problems/forbidden").doesNotContain("Exception");
	}
}
