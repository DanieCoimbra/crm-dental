package crm_data.finance.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import crm_data.finance.domain.InstallmentStatus;
import crm_data.finance.domain.Payment;
import crm_data.finance.domain.PaymentMethod;
import crm_data.finance.repository.InstallmentRow;
import crm_data.finance.service.FinanceReportService;
import crm_data.finance.service.FinanceReportService.CashFlow;
import crm_data.finance.service.FinanceReportService.ReceivablePage;
import crm_data.finance.service.FinanceReportService.Revenue;
import crm_data.finance.service.PatientFinancialService;
import crm_data.finance.service.PatientFinancialService.PatientFinancial;
import crm_data.finance.service.PaymentService;
import crm_data.finance.service.PaymentService.PaymentResult;
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

/** Contrato HTTP de pagamentos, estornos, relatórios e ficha financeira: só o dentista, formato, validação e repasse. */
@WebMvcTest({ PaymentController.class, FinanceReportController.class })
@Import({ SecurityConfig.class, CorsConfig.class, SecurityHeadersConfig.class, ProblemResponseWriter.class,
		RequestIdFilter.class, JwtConfig.class, JsonStrictnessConfig.class })
@TestPropertySource(properties = { "crm.cors-origins=https://app.test.local",
		"crm.jwt.secret=segredo-somente-de-teste-com-mais-de-32-bytes" })
class PaymentWebTest {

	private static final UUID INSTALLMENT = UUID.fromString("a0a0a0a0-0000-4000-8000-000000000001");
	private static final UUID PAYMENT = UUID.fromString("a0a0a0a0-0000-4000-8000-000000000002");
	private static final UUID PATIENT = UUID.fromString("6f0a1b9e-1d3c-4a52-9c0e-3a7b5f2d8e11");

	@Autowired
	MockMvc mvc;
	@MockitoBean
	PaymentService payments;
	@MockitoBean
	FinanceReportService reports;
	@MockitoBean
	PatientFinancialService patientFinancial;

	private RequestPostProcessor as(Role role) {
		return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("clinic_id", UUID.randomUUID().toString()).claim("role", role.name()))
				.authorities(new SimpleGrantedAuthority("ROLE_" + role.name()));
	}

	private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
		return request.contentType(MediaType.APPLICATION_JSON).content(body);
	}

	private static PaymentResult result() {
		Payment payment = Payment.payment(INSTALLMENT, new BigDecimal("200.00"), PaymentMethod.PIX, LocalDate.parse("2026-10-27"), UUID.randomUUID());
		ReflectionTestUtils.setField(payment, "id", PAYMENT);
		ReflectionTestUtils.setField(payment, "createdAt", Instant.parse("2026-10-27T12:00:00Z"));
		InstallmentRow row = new InstallmentRow(INSTALLMENT, PATIENT, UUID.randomUUID(), 1, InstallmentStatus.PAID, new BigDecimal("200.00"),
				LocalDate.parse("2026-12-01"), 1L);
		return new PaymentResult(payment, row, new BigDecimal("200.00"));
	}

	private static final String BODY = "{\"amount\":\"200.00\",\"method\":\"PIX\"}";

	@Test
	void registerReturns201WithThePaymentTheInstallmentStateAndALocation() throws Exception {
		when(payments.register(eq(INSTALLMENT), any(), eq(PaymentMethod.PIX), eq(null))).thenReturn(result());

		mvc.perform(json(post("/api/v1/installments/" + INSTALLMENT + "/payments"), BODY).with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isCreated()).andExpect(header().string("Location", "/api/v1/payments/" + PAYMENT))
				.andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.payment.kind").value("PAYMENT"))
				.andExpect(jsonPath("$.payment.amount").value("200.00")).andExpect(jsonPath("$.payment.reversesPaymentId").doesNotExist())
				.andExpect(jsonPath("$.payment.clinicId").doesNotExist()).andExpect(jsonPath("$.installment.status").value("PAID"))
				.andExpect(jsonPath("$.installment.netPaid").value("200.00")).andExpect(jsonPath("$.installment.balance").value("0.00"))
				.andExpect(jsonPath("$.installment.version").value(1));
	}

	@Test
	void theBodyKeepsOnlyAmountMethodAndPaidOn() throws Exception {
		when(payments.register(eq(INSTALLMENT), any(), any(), any())).thenReturn(result());

		mvc.perform(json(post("/api/v1/installments/" + INSTALLMENT + "/payments"),
				"{\"amount\":\"200.00\",\"method\":\"CASH\",\"paidOn\":\"2026-10-20\",\"kind\":\"REVERSAL\",\"clinicId\":\"" + UUID.randomUUID()
						+ "\",\"status\":\"PAID\",\"createdBy\":\"" + UUID.randomUUID() + "\"}").with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isCreated());

		verify(payments).register(eq(INSTALLMENT), any(), eq(PaymentMethod.CASH), eq(LocalDate.parse("2026-10-20")));
	}

	@ParameterizedTest
	@ValueSource(strings = { "{}", "{\"method\":\"PIX\"}", "{\"amount\":\"200.00\"}", "{\"amount\":\"0.00\",\"method\":\"PIX\"}",
			"{\"amount\":\"-1.00\",\"method\":\"PIX\"}", "{\"amount\":\"10.001\",\"method\":\"PIX\"}", "{\"amount\":\"1e3\",\"method\":\"PIX\"}",
			"{\"amount\":200,\"method\":\"PIX\"}", "{\"amount\":\"200.00\",\"method\":\"BITCOIN\"}",
			"{\"amount\":\"200.00\",\"method\":\"PIX\",\"paidOn\":\"1999-12-31\"}", "{\"amount\":\"1000000.00\",\"method\":\"PIX\"}",
			"{\"amount\":\"200.00\",\"method\":\"PIX\",\"paidOn\":\"2026-13-40\"}" })
	void malformedBodiesAre400WithoutReachingTheService(String body) throws Exception {
		mvc.perform(json(post("/api/v1/installments/" + INSTALLMENT + "/payments"), body).with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(payments);
	}

	@Test
	void reverseHasNoBodyAndReturns201() throws Exception {
		when(payments.reverse(PAYMENT)).thenReturn(result());
		when(payments.reverse(UUID.fromString("a0a0a0a0-0000-4000-8000-0000000000ff")))
				.thenThrow(new BusinessException(ProblemTypes.CONFLICT, "Este pagamento já foi estornado."));

		mvc.perform(post("/api/v1/payments/" + PAYMENT + "/reversal").with(as(Role.DENTIST_ADMIN))).andExpect(status().isCreated());
		mvc.perform(post("/api/v1/payments/a0a0a0a0-0000-4000-8000-0000000000ff/reversal").with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isConflict());
	}

	@Test
	void thereIsNoPutPatchOrDeleteOfAPayment() throws Exception {
		mvc.perform(json(put("/api/v1/payments/" + PAYMENT), BODY).with(as(Role.DENTIST_ADMIN))).andExpect(status().is4xxClientError());
		mvc.perform(json(patch("/api/v1/payments/" + PAYMENT), BODY).with(as(Role.DENTIST_ADMIN))).andExpect(status().is4xxClientError());
		mvc.perform(delete("/api/v1/payments/" + PAYMENT).with(as(Role.DENTIST_ADMIN))).andExpect(status().is4xxClientError());

		verifyNoInteractions(payments);
	}

	@Test
	void listReturnsTheHistoryAsAnArray() throws Exception {
		when(payments.list(INSTALLMENT)).thenReturn(List.of(result().payment()));

		mvc.perform(get("/api/v1/installments/" + INSTALLMENT + "/payments").with(as(Role.DENTIST_ADMIN))).andExpect(status().isOk())
				.andExpect(jsonPath("$[0].id").value(PAYMENT.toString())).andExpect(jsonPath("$[0].amount").value("200.00"));
	}

	@Test
	void reportsPassTheWindowAndFormatMoneyAsText() throws Exception {
		when(reports.cashFlow(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"))).thenReturn(
				new CashFlow(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"), new BigDecimal("4300.00"), List.of(), List.of()));
		when(reports.revenue(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"))).thenReturn(
				new Revenue(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"), 0, BigDecimal.ZERO, List.of(), false));
		when(reports.receivables(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"), 0, 20)).thenReturn(
				new ReceivablePage(new org.springframework.data.domain.PageImpl<>(List.of()), 0, BigDecimal.ZERO));
		when(reports.overdue(0, 100)).thenReturn(new ReceivablePage(new org.springframework.data.domain.PageImpl<>(List.of()), 0, BigDecimal.ZERO));

		mvc.perform(get("/api/v1/finance/cash-flow?from=2026-10-01&to=2026-10-31").with(as(Role.DENTIST_ADMIN))).andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.netTotal").value("4300.00"));
		mvc.perform(get("/api/v1/finance/revenue?from=2026-10-01&to=2026-10-31").with(as(Role.DENTIST_ADMIN))).andExpect(status().isOk())
				.andExpect(jsonPath("$.totalAmount").value("0.00")).andExpect(jsonPath("$.truncated").value(false));
		mvc.perform(get("/api/v1/finance/receivables?from=2026-10-01&to=2026-10-31").with(as(Role.DENTIST_ADMIN))).andExpect(status().isOk())
				.andExpect(jsonPath("$.summary.count").value(0)).andExpect(jsonPath("$.summary.balanceTotal").value("0.00"));
		mvc.perform(get("/api/v1/finance/overdue?size=100").with(as(Role.DENTIST_ADMIN))).andExpect(status().isOk());
	}

	@ParameterizedTest
	@ValueSource(strings = { "/finance/receivables?from=2026-10-01&to=2026-10-31&size=101", "/finance/receivables?from=2026-10-01&to=2026-10-31&size=0",
			"/finance/receivables?from=2026-10-01&to=2026-10-31&page=-1", "/finance/receivables?from=2026-10-01&to=2026-10-31&page=100001",
			"/finance/overdue?size=101", "/finance/overdue?page=-1", "/finance/overdue?page=100001",
			"/finance/cash-flow?from=ontem&to=2026-10-31", "/finance/revenue?from=2026-10-01&to=2026-13-01" })
	void pagingAndDateFormatAreValidatedAtTheEdge(String path) throws Exception {
		mvc.perform(get("/api/v1" + path).with(as(Role.DENTIST_ADMIN))).andExpect(status().isBadRequest());

		verifyNoInteractions(reports);
	}

	@Test
	void thePatientSheetReturnsTheTotalsAsText() throws Exception {
		when(patientFinancial.get(PATIENT)).thenReturn(new PatientFinancial(PATIENT, new BigDecimal("800.00"), new BigDecimal("800.00"),
				new BigDecimal("500.00"), new BigDecimal("300.00"), BigDecimal.ZERO, List.of(), List.of(), false));

		mvc.perform(get("/api/v1/patients/" + PATIENT + "/financial").with(as(Role.DENTIST_ADMIN))).andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.paid").value("500.00"))
				.andExpect(jsonPath("$.balance").value("300.00")).andExpect(jsonPath("$.overdue").value("0.00"))
				.andExpect(jsonPath("$.truncated").value(false));
	}

	@Test
	void theReceptionistIs403OnEveryRouteWithoutTouchingTheServices() throws Exception {
		mvc.perform(get("/api/v1/installments/" + INSTALLMENT + "/payments").with(as(Role.RECEPTIONIST))).andExpect(status().isForbidden());
		mvc.perform(json(post("/api/v1/installments/" + INSTALLMENT + "/payments"), BODY).with(as(Role.RECEPTIONIST))).andExpect(status().isForbidden());
		mvc.perform(post("/api/v1/payments/" + PAYMENT + "/reversal").with(as(Role.RECEPTIONIST))).andExpect(status().isForbidden());
		for (String path : List.of("/finance/receivables?from=2026-10-01&to=2026-10-31", "/finance/overdue",
				"/finance/cash-flow?from=2026-10-01&to=2026-10-31", "/finance/revenue?from=2026-10-01&to=2026-10-31", "/patients/" + PATIENT + "/financial")) {
			mvc.perform(get("/api/v1" + path).with(as(Role.RECEPTIONIST))).andExpect(status().isForbidden());
			mvc.perform(get("/api/v1" + path)).andExpect(status().isUnauthorized());
		}

		verifyNoInteractions(payments, reports, patientFinancial);
	}
}
