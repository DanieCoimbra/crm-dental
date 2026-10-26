package crm_data.commercial.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import crm_data.commercial.domain.Budget;
import crm_data.commercial.domain.BudgetItem;
import crm_data.commercial.domain.BudgetStatus;
import crm_data.commercial.service.BudgetDecisionService;
import crm_data.commercial.service.BudgetService;
import crm_data.commercial.service.BudgetService.BudgetSummary;
import crm_data.commercial.service.BudgetService.BudgetView;
import crm_data.commercial.service.BudgetService.DraftInput;
import crm_data.patient.service.PatientBrief;
import crm_data.shared.config.CorsConfig;
import crm_data.shared.config.JsonStrictnessConfig;
import crm_data.shared.config.JwtConfig;
import crm_data.shared.config.ProblemResponseWriter;
import crm_data.shared.config.SecurityConfig;
import crm_data.shared.config.SecurityHeadersConfig;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.FieldValidationException;
import crm_data.shared.error.ProblemDetails.FieldError;
import crm_data.shared.error.ProblemTypes;
import crm_data.shared.security.Role;
import crm_data.shared.web.RequestIdFilter;

/** Contrato HTTP do orçamento em rascunho: papéis, validação, formato do dinheiro, resposta e repasse ao serviço. */
@WebMvcTest({ BudgetController.class, PatientBudgetController.class })
@Import({ SecurityConfig.class, CorsConfig.class, SecurityHeadersConfig.class, ProblemResponseWriter.class,
		RequestIdFilter.class, JwtConfig.class, JsonStrictnessConfig.class })
@TestPropertySource(properties = { "crm.cors-origins=https://app.test.local",
		"crm.jwt.secret=segredo-somente-de-teste-com-mais-de-32-bytes" })
class BudgetWebTest {

	private static final UUID ID = UUID.fromString("b3f1c7d2-0a54-4e8b-8c2f-61d9a4e7f003");
	private static final UUID PATIENT = UUID.fromString("6f0a1b9e-1d3c-4a52-9c0e-3a7b5f2d8e11");
	private static final UUID PROCEDURE = UUID.fromString("c1a2b3d4-0e5f-4a6b-8c7d-9e0f1a2b3c4d");

	@Autowired
	MockMvc mvc;
	@MockitoBean
	BudgetService budgets;
	@MockitoBean
	BudgetDecisionService decisions;

	private final UUID clinicId = UUID.randomUUID();
	private final UUID userId = UUID.randomUUID();

	private RequestPostProcessor as(Role role) {
		return jwt().jwt(j -> j.subject(userId.toString()).claim("clinic_id", clinicId.toString()).claim("role", role.name()))
				.authorities(new SimpleGrantedAuthority("ROLE_" + role.name()));
	}

	private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
		return request.contentType(MediaType.APPLICATION_JSON).content(body);
	}

	private BudgetView view(BudgetStatus status) {
		Budget budget = new Budget(ID, PATIENT, null, LocalDate.of(2026, 12, 15), "Em 6x", userId);
		budget.setStatus(status);
		budget.setDiscountAmount(new BigDecimal("50.00"));
		ReflectionTestUtils.setField(budget, "number", 12);
		ReflectionTestUtils.setField(budget, "totalAmount", new BigDecimal("800.00"));
		ReflectionTestUtils.setField(budget, "version", 3L);
		ReflectionTestUtils.setField(budget, "createdAt", Instant.parse("2026-10-24T12:00:00Z"));
		ReflectionTestUtils.setField(budget, "updatedAt", Instant.parse("2026-10-24T12:30:00Z"));
		BudgetItem item = new BudgetItem(UUID.fromString("a0a0a0a0-0000-4000-8000-000000000001"), ID, PROCEDURE, (short) 2,
				new BigDecimal("0.00"), (short) 1);
		ReflectionTestUtils.setField(item, "description", "Restauração");
		ReflectionTestUtils.setField(item, "unitPrice", new BigDecimal("250.00"));
		ReflectionTestUtils.setField(item, "lineTotal", new BigDecimal("500.00"));
		BudgetItem second = new BudgetItem(UUID.fromString("a0a0a0a0-0000-4000-8000-000000000002"), ID, PROCEDURE, (short) 1,
				new BigDecimal("100.00"), (short) 2);
		ReflectionTestUtils.setField(second, "description", "Canal");
		ReflectionTestUtils.setField(second, "unitPrice", new BigDecimal("350.00"));
		ReflectionTestUtils.setField(second, "lineTotal", new BigDecimal("250.00"));
		return new BudgetView(budget, List.of(item, second), new PatientBrief(PATIENT, "Maria Souza", "11987654321"), List.of());
	}

	private static String create(String extra) {
		return "{\"patientId\":\"%s\",\"items\":[{\"procedureTypeId\":\"%s\",\"quantity\":2}]%s}".formatted(PATIENT, PROCEDURE, extra);
	}

	// ---- criar ----

	@Test
	void theDentistCreatesWith201LocationAndTheResponseHasTheContractShape() throws Exception {
		when(budgets.create(any(), any())).thenReturn(view(BudgetStatus.DRAFT));

		mvc.perform(json(post("/api/v1/budgets"), create(",\"discountAmount\":\"50.00\",\"notes\":\"  Em 6x  \",\"validUntil\":\"2026-12-15\""))
				.with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", "/api/v1/budgets/" + ID))
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.id").value(ID.toString()))
				.andExpect(jsonPath("$.number").value(12))
				.andExpect(jsonPath("$.patient.name").value("Maria Souza"))
				.andExpect(jsonPath("$.patient.phone").doesNotExist())
				.andExpect(jsonPath("$.status").value("DRAFT"))
				.andExpect(jsonPath("$.subtotalAmount").value("750.00"))
				.andExpect(jsonPath("$.discountAmount").value("50.00"))
				.andExpect(jsonPath("$.totalAmount").value("800.00"))
				.andExpect(jsonPath("$.validUntil").value("2026-12-15"))
				.andExpect(jsonPath("$.notes").value("Em 6x"))
				.andExpect(jsonPath("$.version").value(3))
				.andExpect(jsonPath("$.items.length()").value(2))
				.andExpect(jsonPath("$.items[0].description").value("Restauração"))
				.andExpect(jsonPath("$.items[0].unitPrice").value("250.00"))
				.andExpect(jsonPath("$.items[0].lineTotal").value("500.00"))
				.andExpect(jsonPath("$.items[1].discountAmount").value("100.00"))
				.andExpect(jsonPath("$.documents.length()").value(0))
				.andExpect(content().string(Matchers.not(Matchers.containsString("clinicId"))))
				.andExpect(content().string(Matchers.not(Matchers.containsString("storageKey"))));

		ArgumentCaptor<DraftInput> draft = ArgumentCaptor.forClass(DraftInput.class);
		verify(budgets).create(eq(PATIENT), draft.capture());
		assertThat(draft.getValue().discountAmount()).isEqualByComparingTo("50.00");
		assertThat(draft.getValue().notes()).isEqualTo("Em 6x");
		assertThat(draft.getValue().validUntil()).isEqualTo(LocalDate.of(2026, 12, 15));
		assertThat(draft.getValue().items()).hasSize(1);
		assertThat(draft.getValue().items().get(0).quantity()).isEqualTo(2);
		assertThat(draft.getValue().items().get(0).discountAmount()).isEqualByComparingTo("0.00");
	}

	@Test
	void serverOwnedFieldsInTheBodyAreIgnored() throws Exception {
		when(budgets.create(any(), any())).thenReturn(view(BudgetStatus.DRAFT));
		String extra = ",\"id\":\"%s\",\"clinicId\":\"%s\",\"createdBy\":\"%s\",\"status\":\"APPROVED\",\"number\":99,\"totalAmount\":\"1.00\",\"sentAt\":\"2026-01-01T00:00:00Z\",\"decidedAt\":\"2026-01-01T00:00:00Z\""
				.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
		String itemExtra = "{\"procedureTypeId\":\"%s\",\"quantity\":1,\"unitPrice\":\"1.00\",\"description\":\"x\",\"lineTotal\":\"1.00\"}"
				.formatted(PROCEDURE);

		mvc.perform(json(post("/api/v1/budgets"), "{\"patientId\":\"%s\",\"items\":[%s]%s}".formatted(PATIENT, itemExtra, extra))
				.with(as(Role.DENTIST_ADMIN))).andExpect(status().isCreated());

		verify(budgets).create(eq(PATIENT), any());
	}

	@Test
	void theReceptionistIs403OnEveryWriteWithoutTouchingTheService() throws Exception {
		mvc.perform(json(post("/api/v1/budgets"), create("")).with(as(Role.RECEPTIONIST))).andExpect(status().isForbidden());
		mvc.perform(json(put("/api/v1/budgets/" + ID), create("").replace("{\"patientId\":\"" + PATIENT + "\",", "{")
				.replace("}", ",\"version\":0}")).with(as(Role.RECEPTIONIST))).andExpect(status().isForbidden());
		mvc.perform(json(post("/api/v1/budgets/" + ID + "/discard"), "{\"version\":0}").with(as(Role.RECEPTIONIST)))
				.andExpect(status().isForbidden());
		mvc.perform(json(post("/api/v1/budgets/" + ID + "/approve"), "{\"version\":0}").with(as(Role.RECEPTIONIST)))
				.andExpect(status().isForbidden());
		mvc.perform(json(post("/api/v1/budgets/" + ID + "/reject"), "{\"version\":0}").with(as(Role.RECEPTIONIST)))
				.andExpect(status().isForbidden());

		verifyNoInteractions(budgets, decisions);
	}

	@Test
	void withoutATokenEveryRouteIs401() throws Exception {
		mvc.perform(get("/api/v1/budgets")).andExpect(status().isUnauthorized());
		mvc.perform(json(post("/api/v1/budgets"), create(""))).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/budgets/" + ID)).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/patients/" + PATIENT + "/budgets")).andExpect(status().isUnauthorized());
	}

	// ---- validação ----

	@Test
	void itemsAreRequiredAndLimitedTo50() throws Exception {
		mvc.perform(json(post("/api/v1/budgets"), "{\"patientId\":\"%s\"}".formatted(PATIENT)).with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isBadRequest());
		mvc.perform(json(post("/api/v1/budgets"), "{\"patientId\":\"%s\",\"items\":[]}".formatted(PATIENT)).with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isBadRequest());
		String one = "{\"procedureTypeId\":\"%s\",\"quantity\":1}".formatted(PROCEDURE);
		String fiftyOne = String.join(",", java.util.Collections.nCopies(51, one));
		mvc.perform(json(post("/api/v1/budgets"), "{\"patientId\":\"%s\",\"items\":[%s]}".formatted(PATIENT, fiftyOne))
				.with(as(Role.DENTIST_ADMIN))).andExpect(status().isBadRequest());
		verifyNoInteractions(budgets);
	}

	@Test
	void theFiftyItemsLimitIsAccepted() throws Exception {
		when(budgets.create(any(), any())).thenReturn(view(BudgetStatus.DRAFT));
		String one = "{\"procedureTypeId\":\"%s\",\"quantity\":1}".formatted(PROCEDURE);
		String fifty = String.join(",", java.util.Collections.nCopies(50, one));

		mvc.perform(json(post("/api/v1/budgets"), "{\"patientId\":\"%s\",\"items\":[%s]}".formatted(PATIENT, fifty))
				.with(as(Role.DENTIST_ADMIN))).andExpect(status().isCreated());
	}

	@ParameterizedTest
	@ValueSource(strings = { "0", "1000", "-1", "1.5", "null" })
	void theQuantityMustBeBetween1And999(String quantity) throws Exception {
		mvc.perform(json(post("/api/v1/budgets"),
				"{\"patientId\":\"%s\",\"items\":[{\"procedureTypeId\":\"%s\",\"quantity\":%s}]}".formatted(PATIENT, PROCEDURE, quantity))
				.with(as(Role.DENTIST_ADMIN))).andExpect(status().isBadRequest());
		verifyNoInteractions(budgets);
	}

	@ParameterizedTest
	@ValueSource(strings = { "\"-1\"", "\"1e3\"", "\"1,5\"", "\"1.234\"", "\"1000000.00\"", "10.5", "10" })
	void everyMoneyFieldRejectsMalformedTextAndJsonNumbers(String money) throws Exception {
		mvc.perform(json(post("/api/v1/budgets"), create(",\"discountAmount\":" + money)).with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isBadRequest());
		mvc.perform(json(post("/api/v1/budgets"),
				"{\"patientId\":\"%s\",\"items\":[{\"procedureTypeId\":\"%s\",\"quantity\":1,\"discountAmount\":%s}]}".formatted(PATIENT,
						PROCEDURE, money))
				.with(as(Role.DENTIST_ADMIN))).andExpect(status().isBadRequest());
		verifyNoInteractions(budgets);
	}

	@Test
	void notesAboveFiveHundredAndDatesBefore2000AreRejectedAndBlankNotesBecomeNull() throws Exception {
		when(budgets.create(any(), any())).thenReturn(view(BudgetStatus.DRAFT));

		mvc.perform(json(post("/api/v1/budgets"), create(",\"notes\":\"" + "a".repeat(501) + "\"")).with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isBadRequest());
		mvc.perform(json(post("/api/v1/budgets"), create(",\"validUntil\":\"1999-12-31\"")).with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isBadRequest());
		mvc.perform(json(post("/api/v1/budgets"), create(",\"notes\":\"   \"")).with(as(Role.DENTIST_ADMIN))).andExpect(status().isCreated());

		ArgumentCaptor<DraftInput> draft = ArgumentCaptor.forClass(DraftInput.class);
		verify(budgets).create(eq(PATIENT), draft.capture());
		assertThat(draft.getValue().notes()).isNull();
	}

	@Test
	void everyInvalidFieldIsReportedAtOnce() throws Exception {
		String response = mvc.perform(json(post("/api/v1/budgets"),
				"{\"items\":[{\"quantity\":0,\"discountAmount\":\"-1\"}],\"notes\":\"%s\",\"discountAmount\":\"abc\"}".formatted("a".repeat(501)))
				.with(as(Role.DENTIST_ADMIN))).andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();

		assertThat(response).contains("\"field\":\"patientId\"", "\"field\":\"notes\"", "\"field\":\"discountAmount\"")
				.contains("items[0].procedureTypeId", "items[0].quantity", "items[0].discountAmount");
	}

	@Test
	void theServiceFieldErrorsKeepTheValidationFormat() throws Exception {
		when(budgets.create(any(), any())).thenThrow(new FieldValidationException(
				List.of(new FieldError("items[0].discountAmount", "maior que o valor da linha"), new FieldError("discountAmount", "maior que a soma dos itens"))));

		mvc.perform(json(post("/api/v1/budgets"), create("")).with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.type").value("https://crm.local/problems/validation"))
				.andExpect(jsonPath("$.errors.length()").value(2))
				.andExpect(jsonPath("$.errors[0].field").value("items[0].discountAmount"))
				.andExpect(jsonPath("$.errors[1].message").value("maior que a soma dos itens"));
	}

	@Test
	void serviceRuleErrorsKeepTheirStatusAndReason() throws Exception {
		when(budgets.create(any(), any()))
				.thenThrow(new BusinessException(ProblemTypes.BUSINESS_RULE, "O procedimento está inativo.", "PROCEDURE_INACTIVE"))
				.thenThrow(new BusinessException(ProblemTypes.CONFLICT, "Paciente inativo."))
				.thenThrow(new BusinessException(ProblemTypes.NOT_FOUND, "Paciente não encontrado."));

		mvc.perform(json(post("/api/v1/budgets"), create("")).with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.reason").value("PROCEDURE_INACTIVE"));
		mvc.perform(json(post("/api/v1/budgets"), create("")).with(as(Role.DENTIST_ADMIN))).andExpect(status().isConflict());
		mvc.perform(json(post("/api/v1/budgets"), create("")).with(as(Role.DENTIST_ADMIN))).andExpect(status().isNotFound());
	}

	// ---- editar e descartar ----

	@Test
	void updatePassesTheDraftAndTheVersionAndNeverAPatient() throws Exception {
		when(budgets.update(any(), any(), anyLong())).thenReturn(view(BudgetStatus.DRAFT));
		UUID appointmentId = UUID.randomUUID();
		String body = "{\"patientId\":\"%s\",\"appointmentId\":\"%s\",\"items\":[{\"procedureTypeId\":\"%s\",\"quantity\":3}],\"version\":3}"
				.formatted(UUID.randomUUID(), appointmentId, PROCEDURE);

		mvc.perform(json(put("/api/v1/budgets/" + ID), body).with(as(Role.DENTIST_ADMIN))).andExpect(status().isOk());

		ArgumentCaptor<DraftInput> draft = ArgumentCaptor.forClass(DraftInput.class);
		verify(budgets).update(eq(ID), draft.capture(), eq(3L));
		assertThat(draft.getValue().appointmentId()).isEqualTo(appointmentId);
		assertThat(draft.getValue().discountAmount()).isEqualByComparingTo("0.00");
		assertThat(draft.getValue().items().get(0).quantity()).isEqualTo(3);
	}

	@Test
	void updateAndDiscardRequireTheVersion() throws Exception {
		mvc.perform(json(put("/api/v1/budgets/" + ID), "{\"items\":[{\"procedureTypeId\":\"%s\",\"quantity\":1}]}".formatted(PROCEDURE))
				.with(as(Role.DENTIST_ADMIN))).andExpect(status().isBadRequest());
		mvc.perform(json(post("/api/v1/budgets/" + ID + "/discard"), "{}").with(as(Role.DENTIST_ADMIN))).andExpect(status().isBadRequest());
		mvc.perform(json(post("/api/v1/budgets/" + ID + "/discard"), "{\"version\":-1}").with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(budgets);
	}

	@Test
	void discardPassesTheVersionAndStaleOrWrongStatusIs409() throws Exception {
		when(budgets.discard(ID, 3L)).thenReturn(view(BudgetStatus.REJECTED));
		when(budgets.discard(ID, 2L)).thenThrow(new BusinessException(ProblemTypes.CONFLICT, "O orçamento está SENT e não aceita esta ação."));

		mvc.perform(json(post("/api/v1/budgets/" + ID + "/discard"), "{\"version\":3}").with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
		mvc.perform(json(post("/api/v1/budgets/" + ID + "/discard"), "{\"version\":2}").with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isConflict());
	}

	@Test
	void approveAndRejectPassTheVersionAndReturnTheBudget() throws Exception {
		when(decisions.approve(ID, 3L)).thenReturn(view(BudgetStatus.APPROVED));
		when(decisions.reject(ID, 3L)).thenReturn(view(BudgetStatus.REJECTED));
		when(decisions.approve(ID, 2L)).thenThrow(new BusinessException(ProblemTypes.CONFLICT, "O orçamento está DRAFT e não aceita esta ação."));

		mvc.perform(json(post("/api/v1/budgets/" + ID + "/approve"), "{\"version\":3}").with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
		mvc.perform(json(post("/api/v1/budgets/" + ID + "/reject"), "{\"version\":3}").with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
		mvc.perform(json(post("/api/v1/budgets/" + ID + "/approve"), "{\"version\":2}").with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isConflict());
		mvc.perform(json(post("/api/v1/budgets/" + ID + "/approve"), "{}").with(as(Role.DENTIST_ADMIN))).andExpect(status().isBadRequest());
	}

	// ---- leitura: os dois papéis ----

	@ParameterizedTest
	@EnumSource(Role.class)
	void bothRolesReadABudget(Role role) throws Exception {
		when(budgets.get(ID)).thenReturn(view(BudgetStatus.SENT));

		mvc.perform(get("/api/v1/budgets/" + ID).with(as(role)))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.status").value("SENT"))
				.andExpect(jsonPath("$.totalAmount").value("800.00"))
				.andExpect(jsonPath("$.installments").doesNotExist());
	}

	@Test
	void anUnknownBudgetIs404() throws Exception {
		when(budgets.get(any())).thenThrow(new BusinessException(ProblemTypes.NOT_FOUND, "Orçamento não encontrado."));

		mvc.perform(get("/api/v1/budgets/" + UUID.randomUUID()).with(as(Role.RECEPTIONIST))).andExpect(status().isNotFound());
	}

	@Test
	@SuppressWarnings("unchecked")
	void theListPassesTheFiltersAndReturnsAPage() throws Exception {
		BudgetView v = view(BudgetStatus.SENT);
		when(budgets.list(any(), any(), any(), anyInt(), anyInt())).thenReturn(new PageImpl<>(List.of(new BudgetSummary(v.budget(), v.patient()))));

		mvc.perform(get("/api/v1/budgets").param("status", "DRAFT", "SENT").param("patientId", PATIENT.toString())
				.param("number", "12").param("page", "1").param("size", "10").with(as(Role.RECEPTIONIST)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[0].number").value(12))
				.andExpect(jsonPath("$.content[0].patient.name").value("Maria Souza"))
				.andExpect(jsonPath("$.content[0].totalAmount").value("800.00"))
				.andExpect(jsonPath("$.content[0].items").doesNotExist())
				.andExpect(jsonPath("$.content[0].notes").doesNotExist())
				.andExpect(content().string(Matchers.not(Matchers.containsString("clinicId"))));

		ArgumentCaptor<java.util.Collection<BudgetStatus>> statuses = ArgumentCaptor.forClass(java.util.Collection.class);
		verify(budgets).list(statuses.capture(), eq(PATIENT), eq(12), eq(1), eq(10));
		assertThat(statuses.getValue()).containsExactlyInAnyOrder(BudgetStatus.DRAFT, BudgetStatus.SENT);
	}

	@Test
	void theListUsesDefaultsAndRejectsInvalidParameters() throws Exception {
		when(budgets.list(any(), any(), any(), anyInt(), anyInt())).thenReturn(new PageImpl<>(List.of()));

		mvc.perform(get("/api/v1/budgets").with(as(Role.DENTIST_ADMIN))).andExpect(status().isOk());
		verify(budgets).list(isNull(), isNull(), isNull(), eq(0), eq(20));

		for (String[] bad : new String[][] { { "size", "51" }, { "size", "0" }, { "page", "-1" }, { "number", "0" }, { "number", "abc" },
				{ "status", "FOO" }, { "patientId", "xyz" } }) {
			mvc.perform(get("/api/v1/budgets").param(bad[0], bad[1]).with(as(Role.DENTIST_ADMIN))).andExpect(status().isBadRequest());
		}
	}

	@Test
	void thePatientHistoryIsAvailableToBothRolesAndHonoursTheSizeLimit() throws Exception {
		BudgetView v = view(BudgetStatus.APPROVED);
		when(budgets.historyOf(eq(PATIENT), anyInt(), anyInt())).thenReturn(new PageImpl<>(List.of(new BudgetSummary(v.budget(), v.patient()))));

		for (Role role : Role.values()) {
			mvc.perform(get("/api/v1/patients/" + PATIENT + "/budgets").with(as(role))).andExpect(status().isOk())
					.andExpect(jsonPath("$.content[0].status").value("APPROVED"));
		}
		mvc.perform(get("/api/v1/patients/" + PATIENT + "/budgets").param("size", "51").with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isBadRequest());
		verify(budgets, org.mockito.Mockito.times(2)).historyOf(PATIENT, 0, 20);
	}
}
