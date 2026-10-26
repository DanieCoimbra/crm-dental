package crm_data.catalog.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import crm_data.catalog.domain.ProcedureType;
import crm_data.catalog.service.ProcedureTypeService;
import crm_data.catalog.service.ProcedureTypeService.ActiveFilter;
import crm_data.shared.config.CorsConfig;
import crm_data.shared.config.JsonStrictnessConfig;
import crm_data.shared.config.JwtConfig;
import crm_data.shared.config.ProblemResponseWriter;
import crm_data.shared.config.SecurityConfig;
import crm_data.shared.config.SecurityHeadersConfig;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ProblemTypes;
import crm_data.shared.security.Role;
import crm_data.shared.web.RequestIdFilter;

/** Contrato HTTP do catálogo: papéis, validação (todos os erros juntos), formato do preço e repasse ao serviço. */
@WebMvcTest(ProcedureTypeController.class)
@Import({ SecurityConfig.class, CorsConfig.class, SecurityHeadersConfig.class, ProblemResponseWriter.class,
		RequestIdFilter.class, JwtConfig.class, JsonStrictnessConfig.class })
@TestPropertySource(properties = { "crm.cors-origins=https://app.test.local",
		"crm.jwt.secret=segredo-somente-de-teste-com-mais-de-32-bytes" })
class ProcedureTypeWebTest {

	private static final UUID ID = UUID.fromString("b3f1c7d2-0a54-4e8b-8c2f-61d9a4e7f003");

	@Autowired
	MockMvc mvc;
	@MockitoBean
	ProcedureTypeService service;

	private final UUID clinicId = UUID.randomUUID();
	private final UUID userId = UUID.randomUUID();

	private RequestPostProcessor as(Role role) {
		return jwt().jwt(j -> j.subject(userId.toString()).claim("clinic_id", clinicId.toString()).claim("role", role.name()))
				.authorities(new SimpleGrantedAuthority("ROLE_" + role.name()));
	}

	private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
		return request.contentType(MediaType.APPLICATION_JSON).content(body);
	}

	private ProcedureType procedure(boolean active) {
		ProcedureType procedure = new ProcedureType(ID, "Restauração", new BigDecimal("250.5"), (short) 45, "#3B82F6", userId);
		procedure.setActive(active);
		ReflectionTestUtils.setField(procedure, "version", 2L);
		return procedure;
	}

	private static String body(String name, String price, String minutes, String color) {
		return "{\"name\":%s,\"basePrice\":%s,\"defaultDurationMinutes\":%s,\"color\":%s}".formatted(name, price, minutes, color);
	}

	private static final String VALID = body("\"Restauração\"", "\"250.50\"", "45", "\"#3B82F6\"");

	// ---- leitura: os dois papéis ----

	@ParameterizedTest
	@EnumSource(Role.class)
	void bothRolesListWithActiveOnlyByDefaultAndTheResponseHasTheContractShape(Role role) throws Exception {
		when(service.list(ActiveFilter.ACTIVE)).thenReturn(List.of(procedure(true)));

		mvc.perform(get("/api/v1/procedure-types").with(as(role)))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$[0].id").value(ID.toString()))
				.andExpect(jsonPath("$[0].name").value("Restauração"))
				.andExpect(jsonPath("$[0].basePrice").value("250.50"))
				.andExpect(jsonPath("$[0].defaultDurationMinutes").value(45))
				.andExpect(jsonPath("$[0].color").value("#3B82F6"))
				.andExpect(jsonPath("$[0].active").value(true))
				.andExpect(jsonPath("$[0].version").value(2))
				.andExpect(content().string(Matchers.not(Matchers.containsString("clinicId"))))
				.andExpect(content().string(Matchers.not(Matchers.containsString("createdBy"))));
	}

	@ParameterizedTest
	@ValueSource(strings = { "true", "TRUE", "false", "all", "ALL" })
	void theActiveFilterAcceptsTrueFalseAndAllInAnyCase(String value) throws Exception {
		when(service.list(any())).thenReturn(List.of());

		mvc.perform(get("/api/v1/procedure-types").param("active", value).with(as(Role.RECEPTIONIST))).andExpect(status().isOk());

		verify(service).list(switch (value.toLowerCase()) {
			case "true" -> ActiveFilter.ACTIVE;
			case "false" -> ActiveFilter.INACTIVE;
			default -> ActiveFilter.ALL;
		});
	}

	@ParameterizedTest
	@ValueSource(strings = { "yes", "1", "tudo", "truee" })
	void anInvalidActiveFilterIs400(String value) throws Exception {
		mvc.perform(get("/api/v1/procedure-types").param("active", value).with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(service);
	}

	@Test
	void getByIdReturnsTheProcedureAndAnUnknownIdIs404() throws Exception {
		UUID unknown = UUID.randomUUID();
		when(service.get(ID)).thenReturn(procedure(false));
		when(service.get(unknown)).thenThrow(new BusinessException(ProblemTypes.NOT_FOUND, "Procedimento não encontrado."));

		mvc.perform(get("/api/v1/procedure-types/" + ID).with(as(Role.RECEPTIONIST)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
		mvc.perform(get("/api/v1/procedure-types/" + unknown).with(as(Role.RECEPTIONIST))).andExpect(status().isNotFound());
	}

	// ---- escrita: só o dentista ----

	@Test
	void theDentistCreatesWith201LocationAndTheServiceReceivesTheParsedValues() throws Exception {
		when(service.create(any(), any(), anyShort(), any())).thenReturn(procedure(true));

		mvc.perform(json(post("/api/v1/procedure-types"), VALID).with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", "/api/v1/procedure-types/" + ID))
				.andExpect(jsonPath("$.id").value(ID.toString()));

		verify(service).create("Restauração", new BigDecimal("250.50"), (short) 45, "#3B82F6");
	}

	@Test
	void theNameIsStrippedAndExtraFieldsAreIgnored() throws Exception {
		when(service.create(any(), any(), anyShort(), any())).thenReturn(procedure(true));
		String extra = "{\"name\":\"  Canal  \",\"basePrice\":\"900\",\"defaultDurationMinutes\":90,\"color\":\"#ef4444\","
				+ "\"id\":\"%s\",\"clinicId\":\"%s\",\"createdBy\":\"%s\",\"active\":false}".formatted(UUID.randomUUID(), UUID.randomUUID(),
						UUID.randomUUID());

		mvc.perform(json(post("/api/v1/procedure-types"), extra).with(as(Role.DENTIST_ADMIN))).andExpect(status().isCreated());

		verify(service).create("Canal", new BigDecimal("900.00"), (short) 90, "#ef4444");
	}

	@Test
	void theReceptionistIs403OnEveryWriteWithoutTouchingTheService() throws Exception {
		mvc.perform(json(post("/api/v1/procedure-types"), VALID).with(as(Role.RECEPTIONIST))).andExpect(status().isForbidden());
		mvc.perform(json(put("/api/v1/procedure-types/" + ID), VALID.replace("}", ",\"version\":0}")).with(as(Role.RECEPTIONIST)))
				.andExpect(status().isForbidden());
		mvc.perform(json(patch("/api/v1/procedure-types/" + ID + "/active"), "{\"active\":false,\"version\":0}").with(as(Role.RECEPTIONIST)))
				.andExpect(status().isForbidden());

		verifyNoInteractions(service);
	}

	@Test
	void withoutATokenEveryRouteIs401() throws Exception {
		mvc.perform(get("/api/v1/procedure-types")).andExpect(status().isUnauthorized());
		mvc.perform(json(post("/api/v1/procedure-types"), VALID)).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/procedure-types/" + ID)).andExpect(status().isUnauthorized());
	}

	// ---- validação ----

	@Test
	void everyInvalidFieldIsReportedAtOnce() throws Exception {
		MvcResult result = mvc.perform(json(post("/api/v1/procedure-types"), body("\" \"", "\"-5\"", "7", "\"azul\""))
				.with(as(Role.DENTIST_ADMIN))).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.type").value("https://crm.local/problems/validation")).andReturn();

		String response = result.getResponse().getContentAsString();
		assertThat(response).contains("\"field\":\"name\"", "\"field\":\"basePrice\"", "\"field\":\"defaultDurationMinutes\"",
				"\"field\":\"color\"");
		verifyNoInteractions(service);
	}

	@ParameterizedTest
	@ValueSource(strings = { "\"-1\"", "\"1e3\"", "\"1,5\"", "\"1.234\"", "\"1000000.00\"", "\"\"", "\" 10\"", "1234.5", "1234",
			"null" })
	void thePriceMustBeADecimalTextWithinTheCeilingNeverAJsonNumber(String price) throws Exception {
		mvc.perform(json(post("/api/v1/procedure-types"), body("\"Canal\"", price, "30", "\"#3B82F6\"")).with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(service);
	}

	@ParameterizedTest
	@ValueSource(strings = { "\"0\"", "\"0.01\"", "\"999999.99\"", "\"10\"", "\"10.5\"" })
	void validPricesAreAccepted(String price) throws Exception {
		when(service.create(any(), any(), anyShort(), any())).thenReturn(procedure(true));

		mvc.perform(json(post("/api/v1/procedure-types"), body("\"Canal\"", price, "30", "\"#3B82F6\"")).with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isCreated());
	}

	@ParameterizedTest
	@ValueSource(strings = { "0", "4", "7", "725", "721", "-5", "30.5", "5.0" })
	void theDurationMustBeAMultipleOfFiveFromFiveTo720(String minutes) throws Exception {
		mvc.perform(json(post("/api/v1/procedure-types"), body("\"Canal\"", "\"10\"", minutes, "\"#3B82F6\"")).with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isBadRequest());
	}

	@ParameterizedTest
	@ValueSource(strings = { "5", "15", "720", "45" })
	void durationLimitsAreAccepted(String minutes) throws Exception {
		when(service.create(any(), any(), anyShort(), any())).thenReturn(procedure(true));

		mvc.perform(json(post("/api/v1/procedure-types"), body("\"Canal\"", "\"10\"", minutes, "\"#3B82F6\"")).with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isCreated());
	}

	@ParameterizedTest
	@ValueSource(strings = { "\"3B82F6\"", "\"#3B82F\"", "\"#3B82F6A\"", "\"#GGGGGG\"", "\"red\"", "\"\"" })
	void theColorMustBeHashPlusSixHexDigits(String color) throws Exception {
		mvc.perform(json(post("/api/v1/procedure-types"), body("\"Canal\"", "\"10\"", "30", color)).with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isBadRequest());
	}

	@Test
	void theNameMustHaveBetween2And80CharactersAfterStripping() throws Exception {
		mvc.perform(json(post("/api/v1/procedure-types"), body("\"A\"", "\"10\"", "30", "\"#3B82F6\"")).with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isBadRequest());
		mvc.perform(json(post("/api/v1/procedure-types"), body("\"" + "a".repeat(81) + "\"", "\"10\"", "30", "\"#3B82F6\""))
				.with(as(Role.DENTIST_ADMIN))).andExpect(status().isBadRequest());
		when(service.create(any(), any(), anyShort(), any())).thenReturn(procedure(true));
		mvc.perform(json(post("/api/v1/procedure-types"), body("\"" + "a".repeat(80) + "\"", "\"10\"", "30", "\"#3B82F6\""))
				.with(as(Role.DENTIST_ADMIN))).andExpect(status().isCreated());
	}

	// ---- editar e ativar ----

	@Test
	void updatePassesTheFieldsAndTheVersionAndStaleVersionIs409() throws Exception {
		when(service.update(any(), any(), any(), anyShort(), any(), eq(2L))).thenReturn(procedure(true));
		when(service.update(any(), any(), any(), anyShort(), any(), eq(1L)))
				.thenThrow(new BusinessException(ProblemTypes.CONFLICT, "O recurso foi alterado."));

		mvc.perform(json(put("/api/v1/procedure-types/" + ID), VALID.replace("}", ",\"version\":2}")).with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isOk());
		mvc.perform(json(put("/api/v1/procedure-types/" + ID), VALID.replace("}", ",\"version\":1}")).with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isConflict());

		verify(service).update(ID, "Restauração", new BigDecimal("250.50"), (short) 45, "#3B82F6", 2L);
	}

	@Test
	void updateRequiresTheVersion() throws Exception {
		mvc.perform(json(put("/api/v1/procedure-types/" + ID), VALID).with(as(Role.DENTIST_ADMIN))).andExpect(status().isBadRequest());

		verifyNoInteractions(service);
	}

	@Test
	void setActivePassesTheFlagAndTheVersion() throws Exception {
		when(service.setActive(any(), eq(false), anyLong())).thenReturn(procedure(false));

		mvc.perform(json(patch("/api/v1/procedure-types/" + ID + "/active"), "{\"active\":false,\"version\":2}").with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));

		verify(service).setActive(ID, false, 2L);
	}

	@Test
	void setActiveRequiresBothFields() throws Exception {
		mvc.perform(json(patch("/api/v1/procedure-types/" + ID + "/active"), "{\"version\":2}").with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isBadRequest());
		mvc.perform(json(patch("/api/v1/procedure-types/" + ID + "/active"), "{\"active\":true}").with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(service);
	}

	@Test
	void thereIsNoDeleteRoute() throws Exception {
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/procedure-types/" + ID)
				.with(as(Role.DENTIST_ADMIN))).andExpect(status().isMethodNotAllowed());
	}
}
