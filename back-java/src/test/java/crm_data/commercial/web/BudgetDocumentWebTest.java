package crm_data.commercial.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import crm_data.commercial.domain.Budget;
import crm_data.commercial.domain.BudgetStatus;
import crm_data.commercial.service.BudgetDocumentService;
import crm_data.commercial.service.BudgetDocumentService.DownloadUrl;
import crm_data.commercial.service.BudgetService.BudgetView;
import crm_data.commercial.service.BudgetService.DocumentSummary;
import crm_data.commercial.service.DocumentRateLimiter;
import crm_data.patient.service.PatientBrief;
import crm_data.shared.config.CorsConfig;
import crm_data.shared.config.JsonStrictnessConfig;
import crm_data.shared.config.JwtConfig;
import crm_data.shared.config.ProblemResponseWriter;
import crm_data.shared.config.SecurityConfig;
import crm_data.shared.config.SecurityHeadersConfig;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.BusyException;
import crm_data.shared.error.ProblemTypes;
import crm_data.shared.error.RateLimitedException;
import crm_data.shared.security.Role;
import crm_data.shared.storage.StorageException;
import crm_data.shared.web.RequestIdFilter;

/** Contrato HTTP do envio e do PDF: papéis, formato das respostas, limites (429 e 503) e erros do serviço. */
@WebMvcTest(BudgetDocumentController.class)
@Import({ SecurityConfig.class, CorsConfig.class, SecurityHeadersConfig.class, ProblemResponseWriter.class,
		RequestIdFilter.class, JwtConfig.class, JsonStrictnessConfig.class, CommercialWebConfig.class })
@TestPropertySource(properties = { "crm.cors-origins=https://app.test.local",
		"crm.jwt.secret=segredo-somente-de-teste-com-mais-de-32-bytes" })
class BudgetDocumentWebTest {

	private static final UUID ID = UUID.fromString("b3f1c7d2-0a54-4e8b-8c2f-61d9a4e7f003");
	private static final UUID DOC = UUID.fromString("d0c0d0c0-0a54-4e8b-8c2f-61d9a4e7f003");
	private static final UUID PATIENT = UUID.fromString("6f0a1b9e-1d3c-4a52-9c0e-3a7b5f2d8e11");

	@Autowired
	MockMvc mvc;
	@MockitoBean
	BudgetDocumentService documents;
	@MockitoBean
	DocumentRateLimiter limiter;

	private final UUID clinicId = UUID.randomUUID();
	private final UUID userId = UUID.randomUUID();

	private RequestPostProcessor as(Role role) {
		return jwt().jwt(j -> j.subject(userId.toString()).claim("clinic_id", clinicId.toString()).claim("role", role.name()))
				.authorities(new SimpleGrantedAuthority("ROLE_" + role.name()));
	}

	private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
		return request.contentType(MediaType.APPLICATION_JSON).content(body);
	}

	private BudgetView sentView() {
		Budget budget = new Budget(ID, PATIENT, null, null, null, userId);
		budget.setStatus(BudgetStatus.SENT);
		ReflectionTestUtils.setField(budget, "number", 12);
		ReflectionTestUtils.setField(budget, "totalAmount", new BigDecimal("800.00"));
		ReflectionTestUtils.setField(budget, "version", 5L);
		ReflectionTestUtils.setField(budget, "sentAt", Instant.parse("2026-10-27T14:03:11Z"));
		return new BudgetView(budget, List.of(), new PatientBrief(PATIENT, "Maria Souza", "119"),
				List.of(new DocumentSummary(DOC, 1, Instant.parse("2026-10-27T14:03:11Z"), 48211)));
	}

	// ---- enviar ----

	@Test
	void theDentistSendsAndTheResponseCarriesTheSentBudgetWithItsDocumentButNeverTheKey() throws Exception {
		when(documents.send(ID, 4L)).thenReturn(sentView());

		mvc.perform(json(post("/api/v1/budgets/" + ID + "/send"), "{\"version\":4}").with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.status").value("SENT"))
				.andExpect(jsonPath("$.sentAt").value("2026-10-27T14:03:11Z"))
				.andExpect(jsonPath("$.documents[0].id").value(DOC.toString()))
				.andExpect(jsonPath("$.documents[0].revision").value(1))
				.andExpect(jsonPath("$.documents[0].sizeBytes").value(48211))
				.andExpect(content().string(Matchers.not(Matchers.containsString("storageKey"))))
				.andExpect(content().string(Matchers.not(Matchers.containsString("sha256"))))
				.andExpect(content().string(Matchers.not(Matchers.containsString("clinicId"))));

		verify(limiter).check(userId);
	}

	@Test
	void theVersionIsRequiredToSend() throws Exception {
		mvc.perform(json(post("/api/v1/budgets/" + ID + "/send"), "{}").with(as(Role.DENTIST_ADMIN))).andExpect(status().isBadRequest());
		mvc.perform(json(post("/api/v1/budgets/" + ID + "/send"), "{\"version\":-1}").with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(documents);
	}

	@Test
	void theReceptionistIs403OnSendAndRegenerateBeforeAnyLimiterOrService() throws Exception {
		mvc.perform(json(post("/api/v1/budgets/" + ID + "/send"), "{\"version\":0}").with(as(Role.RECEPTIONIST))).andExpect(status().isForbidden());
		mvc.perform(post("/api/v1/budgets/" + ID + "/documents").with(as(Role.RECEPTIONIST))).andExpect(status().isForbidden());

		verifyNoInteractions(documents, limiter);
	}

	@Test
	void withoutATokenEveryRouteIs401() throws Exception {
		mvc.perform(json(post("/api/v1/budgets/" + ID + "/send"), "{\"version\":0}")).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/v1/budgets/" + ID + "/documents")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/budgets/" + ID + "/documents")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/budgets/" + ID + "/documents/" + DOC + "/download-url")).andExpect(status().isUnauthorized());
	}

	// ---- limites ----

	@Test
	void theUserLimitIs429WithRetryAfterAndStopsBeforeTheService() throws Exception {
		doThrow(new RateLimitedException("Muitos envios de orçamento. Tente novamente em instantes.", 42)).when(limiter).check(any());

		mvc.perform(json(post("/api/v1/budgets/" + ID + "/send"), "{\"version\":0}").with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isTooManyRequests())
				.andExpect(header().string("Retry-After", "42"))
				.andExpect(jsonPath("$.type").value("https://crm.local/problems/rate-limited"));
		mvc.perform(post("/api/v1/budgets/" + ID + "/documents").with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isTooManyRequests());

		verifyNoInteractions(documents);
	}

	@Test
	void theLimiterOnlyCountsThePostRoutesNeverTheReads() throws Exception {
		when(documents.list(ID)).thenReturn(List.of());
		when(documents.downloadUrl(ID, DOC)).thenReturn(new DownloadUrl(URI.create("https://storage.test/x"), 60));

		mvc.perform(get("/api/v1/budgets/" + ID + "/documents").with(as(Role.RECEPTIONIST))).andExpect(status().isOk());
		mvc.perform(get("/api/v1/budgets/" + ID + "/documents/" + DOC + "/download-url").with(as(Role.RECEPTIONIST))).andExpect(status().isOk());

		verify(limiter, never()).check(any());
	}

	@Test
	void withoutAFreeSlotTheAnswerIs503WithRetryAfterFive() throws Exception {
		when(documents.send(any(), anyLong())).thenThrow(new BusyException("Muitos envios em andamento. Tente novamente em instantes.", 5));

		mvc.perform(json(post("/api/v1/budgets/" + ID + "/send"), "{\"version\":0}").with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isServiceUnavailable())
				.andExpect(header().string("Retry-After", "5"))
				.andExpect(jsonPath("$.type").value("https://crm.local/problems/unavailable"));
	}

	@Test
	void aStorageFailureIs503WithoutDetailsAndNoRetryAfter() throws Exception {
		when(documents.send(any(), anyLong())).thenThrow(new StorageException("Falha ao enviar o PDF ao Storage."));

		var result = mvc.perform(json(post("/api/v1/budgets/" + ID + "/send"), "{\"version\":0}").with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isServiceUnavailable()).andReturn();

		assertThat(result.getResponse().getContentAsString()).doesNotContain("Storage", "PDF");
	}

	@Test
	void ruleErrorsKeepTheirStatusAndReason() throws Exception {
		when(documents.send(any(), anyLong()))
				.thenThrow(new BusinessException(ProblemTypes.CONFLICT, "Inclua ao menos um item com valor maior que zero."))
				.thenThrow(new BusinessException(ProblemTypes.BUSINESS_RULE, "O PDF do orçamento passou do tamanho permitido.", "PDF_TOO_LARGE"));

		mvc.perform(json(post("/api/v1/budgets/" + ID + "/send"), "{\"version\":0}").with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isConflict());
		mvc.perform(json(post("/api/v1/budgets/" + ID + "/send"), "{\"version\":0}").with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.reason").value("PDF_TOO_LARGE"));
	}

	// ---- reemitir, listar e baixar ----

	@Test
	void regenerateReturns201WithTheRevisionAndALocationToItsDownloadRoute() throws Exception {
		when(documents.regenerate(ID)).thenReturn(new DocumentSummary(DOC, 2, Instant.parse("2026-11-27T10:00:00Z"), 50000));

		mvc.perform(post("/api/v1/budgets/" + ID + "/documents").with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", "/api/v1/budgets/" + ID + "/documents/" + DOC + "/download-url"))
				.andExpect(jsonPath("$.id").value(DOC.toString()))
				.andExpect(jsonPath("$.revision").value(2))
				.andExpect(jsonPath("$.generatedAt").value("2026-11-27T10:00:00Z"))
				.andExpect(jsonPath("$.sizeBytes").value(50000))
				.andExpect(content().string(Matchers.not(Matchers.containsString("storageKey"))));

		verify(limiter).check(userId);
	}

	@ParameterizedTest
	@EnumSource(Role.class)
	void bothRolesListTheRevisionsAndGetTheDownloadUrl(Role role) throws Exception {
		when(documents.list(ID)).thenReturn(List.of(new DocumentSummary(DOC, 2, Instant.parse("2026-11-27T10:00:00Z"), 50000),
				new DocumentSummary(UUID.randomUUID(), 1, Instant.parse("2026-10-27T14:03:11Z"), 48211)));
		when(documents.downloadUrl(ID, DOC)).thenReturn(new DownloadUrl(URI.create("https://storage.test/sign?token=abc"), 60));

		mvc.perform(get("/api/v1/budgets/" + ID + "/documents").with(as(role)))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].revision").value(2))
				.andExpect(content().string(Matchers.not(Matchers.containsString("storageKey"))));
		mvc.perform(get("/api/v1/budgets/" + ID + "/documents/" + DOC + "/download-url").with(as(role)))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.url").value("https://storage.test/sign?token=abc"))
				.andExpect(jsonPath("$.expiresInSeconds").value(60));
	}

	@Test
	void anUnknownBudgetOrDocumentIs404() throws Exception {
		when(documents.list(any())).thenThrow(new BusinessException(ProblemTypes.NOT_FOUND, "Orçamento não encontrado."));
		when(documents.downloadUrl(any(), eq(DOC))).thenThrow(new BusinessException(ProblemTypes.NOT_FOUND, "Documento não encontrado."));

		mvc.perform(get("/api/v1/budgets/" + UUID.randomUUID() + "/documents").with(as(Role.RECEPTIONIST))).andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/budgets/" + ID + "/documents/" + DOC + "/download-url").with(as(Role.RECEPTIONIST)))
				.andExpect(status().isNotFound());
	}
}
