package crm_data.finance.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
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

import crm_data.finance.domain.Installment;
import crm_data.finance.domain.InstallmentStatus;
import crm_data.finance.service.InstallmentPlanService;
import crm_data.finance.service.InstallmentService;
import crm_data.finance.service.InstallmentService.InstallmentView;
import crm_data.finance.service.InstallmentService.PlanView;
import crm_data.finance.service.PlanRequest;
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

/** Contrato HTTP do plano de parcelas: só o dentista, formato, validação do corpo e repasse aos serviços. */
@WebMvcTest(InstallmentController.class)
@Import({ SecurityConfig.class, CorsConfig.class, SecurityHeadersConfig.class, ProblemResponseWriter.class,
		RequestIdFilter.class, JwtConfig.class, JsonStrictnessConfig.class })
@TestPropertySource(properties = { "crm.cors-origins=https://app.test.local",
		"crm.jwt.secret=segredo-somente-de-teste-com-mais-de-32-bytes" })
class InstallmentWebTest {

	private static final UUID BUDGET = UUID.fromString("b3f1c7d2-0a54-4e8b-8c2f-61d9a4e7f003");
	private static final UUID PATIENT = UUID.fromString("6f0a1b9e-1d3c-4a52-9c0e-3a7b5f2d8e11");
	private static final UUID INSTALLMENT = UUID.fromString("a0a0a0a0-0000-4000-8000-000000000001");

	@Autowired
	MockMvc mvc;
	@MockitoBean
	InstallmentPlanService plans;
	@MockitoBean
	InstallmentService installments;

	private RequestPostProcessor as(Role role) {
		return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("clinic_id", UUID.randomUUID().toString()).claim("role", role.name()))
				.authorities(new SimpleGrantedAuthority("ROLE_" + role.name()));
	}

	private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
		return request.contentType(MediaType.APPLICATION_JSON).content(body);
	}

	private static PlanView plan() {
		Installment installment = new Installment(INSTALLMENT, BUDGET, PATIENT, 1, new BigDecimal("800.00"), LocalDate.parse("2026-11-10"),
				UUID.randomUUID());
		ReflectionTestUtils.setField(installment, "version", 0L);
		return new PlanView(BUDGET, new BigDecimal("800.00"), new BigDecimal("800.00"),
				List.of(new InstallmentView(installment, new BigDecimal("0.00"))));
	}

	private static final String AUTO = "{\"mode\":\"AUTO\",\"count\":3,\"firstDueDate\":\"2026-11-10\",\"downPayment\":\"100.00\"}";
	private static final String MANUAL = "{\"mode\":\"MANUAL\",\"installments\":[{\"amount\":\"800.00\",\"dueDate\":\"2026-11-10\"}]}";

	@Test
	void getReturnsThePlanWithTextMoneyAndNoStore() throws Exception {
		when(installments.listByBudget(BUDGET)).thenReturn(plan());

		mvc.perform(get("/api/v1/budgets/" + BUDGET + "/installments").with(as(Role.DENTIST_ADMIN))).andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.totalAmount").value("800.00"))
				.andExpect(jsonPath("$.activeSum").value("800.00")).andExpect(jsonPath("$.installments[0].amount").value("800.00"))
				.andExpect(jsonPath("$.installments[0].netPaid").value("0.00")).andExpect(jsonPath("$.installments[0].balance").value("800.00"))
				.andExpect(jsonPath("$.installments[0].status").value("OPEN")).andExpect(jsonPath("$.installments[0].version").value(0))
				.andExpect(jsonPath("$.installments[0].clinicId").doesNotExist()).andExpect(jsonPath("$.installments[0].patientId").doesNotExist());
	}

	@Test
	void postCreatesWith201AndPassesTheAutoRequest() throws Exception {
		when(plans.create(eq(BUDGET), any())).thenReturn(plan());

		mvc.perform(json(post("/api/v1/budgets/" + BUDGET + "/installments"), AUTO).with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isCreated()).andExpect(header().exists("Location"));

		ArgumentCaptor<PlanRequest> captor = ArgumentCaptor.forClass(PlanRequest.class);
		verify(plans).create(eq(BUDGET), captor.capture());
		org.assertj.core.api.Assertions.assertThat(captor.getValue().mode()).isEqualTo(PlanRequest.Mode.AUTO);
		org.assertj.core.api.Assertions.assertThat(captor.getValue().count()).isEqualTo(3);
		org.assertj.core.api.Assertions.assertThat(captor.getValue().downPayment()).isEqualByComparingTo("100.00");
	}

	@Test
	void putRedoesWith200AndPassesTheManualRequest() throws Exception {
		when(plans.redo(eq(BUDGET), any())).thenReturn(plan());

		mvc.perform(json(put("/api/v1/budgets/" + BUDGET + "/installments"), MANUAL).with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isOk());

		ArgumentCaptor<PlanRequest> captor = ArgumentCaptor.forClass(PlanRequest.class);
		verify(plans).redo(eq(BUDGET), captor.capture());
		org.assertj.core.api.Assertions.assertThat(captor.getValue().items()).hasSize(1);
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"{}",
			"{\"mode\":\"OUTRO\",\"count\":3}",
			"{\"mode\":\"AUTO\",\"count\":3,\"firstDueDate\":\"1999-12-31\"}",
			"{\"mode\":\"AUTO\",\"count\":3,\"firstDueDate\":\"2026-11-10\",\"downPayment\":\"1e3\"}",
			"{\"mode\":\"AUTO\",\"count\":3,\"firstDueDate\":\"2026-11-10\",\"downPayment\":\"-5.00\"}",
			"{\"mode\":\"AUTO\",\"count\":3,\"firstDueDate\":\"2026-11-10\",\"downPayment\":100}",
			"{\"mode\":\"AUTO\",\"count\":3.5,\"firstDueDate\":\"2026-11-10\"}",
			"{\"mode\":\"MANUAL\",\"installments\":[{\"amount\":\"0.001\",\"dueDate\":\"2026-11-10\"}]}",
			"{\"mode\":\"MANUAL\",\"installments\":[{\"amount\":\"10.00\"}]}",
			"{\"mode\":\"MANUAL\",\"installments\":[null]}",
			"{\"mode\":\"MANUAL\",\"installments\":[{\"amount\":\"1000000.00\",\"dueDate\":\"2026-11-10\"}]}" })
	void malformedBodiesAre400WithoutReachingTheService(String body) throws Exception {
		mvc.perform(json(post("/api/v1/budgets/" + BUDGET + "/installments"), body).with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(plans);
	}

	@Test
	void serverOwnedFieldsInTheBodyAreIgnored() throws Exception {
		when(plans.create(eq(BUDGET), any())).thenReturn(plan());

		mvc.perform(json(post("/api/v1/budgets/" + BUDGET + "/installments"),
				"{\"mode\":\"AUTO\",\"count\":2,\"firstDueDate\":\"2026-11-10\",\"clinicId\":\"" + UUID.randomUUID()
						+ "\",\"status\":\"PAID\",\"number\":9,\"netPaid\":\"800.00\",\"balance\":\"0.00\",\"createdBy\":\"" + UUID.randomUUID() + "\"}")
				.with(as(Role.DENTIST_ADMIN))).andExpect(status().isCreated());
	}

	@Test
	void patchDueDatePassesTheDateAndVersion() throws Exception {
		when(installments.changeDueDate(INSTALLMENT, LocalDate.parse("2027-01-15"), 4L)).thenReturn(plan().installments().get(0));
		when(installments.changeDueDate(INSTALLMENT, LocalDate.parse("2027-01-15"), 3L))
				.thenThrow(new BusinessException(ProblemTypes.CONFLICT, "O recurso foi alterado."));

		mvc.perform(json(patch("/api/v1/installments/" + INSTALLMENT + "/due-date"), "{\"dueDate\":\"2027-01-15\",\"version\":4}")
				.with(as(Role.DENTIST_ADMIN))).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(INSTALLMENT.toString()))
				.andExpect(jsonPath("$.status").value(InstallmentStatus.OPEN.name()));
		mvc.perform(json(patch("/api/v1/installments/" + INSTALLMENT + "/due-date"), "{\"dueDate\":\"2027-01-15\",\"version\":3}")
				.with(as(Role.DENTIST_ADMIN))).andExpect(status().isConflict());
		mvc.perform(json(patch("/api/v1/installments/" + INSTALLMENT + "/due-date"), "{\"version\":3}").with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isBadRequest());
		mvc.perform(json(patch("/api/v1/installments/" + INSTALLMENT + "/due-date"), "{\"dueDate\":\"2027-01-15\"}").with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isBadRequest());
		mvc.perform(json(patch("/api/v1/installments/" + INSTALLMENT + "/due-date"), "{\"dueDate\":\"1999-01-01\",\"version\":1}")
				.with(as(Role.DENTIST_ADMIN))).andExpect(status().isBadRequest());
	}

	@Test
	void theReceptionistIs403OnEveryRouteWithoutTouchingTheServices() throws Exception {
		mvc.perform(get("/api/v1/budgets/" + BUDGET + "/installments").with(as(Role.RECEPTIONIST))).andExpect(status().isForbidden());
		mvc.perform(json(post("/api/v1/budgets/" + BUDGET + "/installments"), AUTO).with(as(Role.RECEPTIONIST))).andExpect(status().isForbidden());
		mvc.perform(json(put("/api/v1/budgets/" + BUDGET + "/installments"), AUTO).with(as(Role.RECEPTIONIST))).andExpect(status().isForbidden());
		mvc.perform(json(patch("/api/v1/installments/" + INSTALLMENT + "/due-date"), "{\"dueDate\":\"2027-01-15\",\"version\":1}")
				.with(as(Role.RECEPTIONIST))).andExpect(status().isForbidden());

		verifyNoInteractions(plans, installments);
	}

	@Test
	void withoutATokenEveryRouteIs401() throws Exception {
		mvc.perform(get("/api/v1/budgets/" + BUDGET + "/installments")).andExpect(status().isUnauthorized());
		mvc.perform(json(post("/api/v1/budgets/" + BUDGET + "/installments"), AUTO)).andExpect(status().isUnauthorized());
		mvc.perform(json(patch("/api/v1/installments/" + INSTALLMENT + "/due-date"), "{}")).andExpect(status().isUnauthorized());
	}

	@Test
	void aMalformedIdIs400() throws Exception {
		mvc.perform(get("/api/v1/budgets/nao-e-uuid/installments").with(as(Role.DENTIST_ADMIN))).andExpect(status().isBadRequest());
	}
}
