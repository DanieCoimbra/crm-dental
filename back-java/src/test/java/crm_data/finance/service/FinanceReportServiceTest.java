package crm_data.finance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import crm_data.clinic.service.ClinicZones;
import crm_data.commercial.service.ApprovedBudget;
import crm_data.commercial.service.BudgetBrief;
import crm_data.commercial.service.BudgetLookup;
import crm_data.finance.domain.InstallmentStatus;
import crm_data.finance.domain.PaymentMethod;
import crm_data.finance.repository.InstallmentRepository;
import crm_data.finance.repository.InstallmentRow;
import crm_data.finance.repository.PaymentRepository;
import crm_data.finance.service.FinanceReportService.ReceivablePage;
import crm_data.finance.service.FinanceReportService.Revenue;
import crm_data.patient.service.PatientBrief;
import crm_data.patient.service.PatientLookup;
import crm_data.shared.error.FieldValidationException;

class FinanceReportServiceTest {

	private final InstallmentRepository installments = mock(InstallmentRepository.class);
	private final PaymentRepository payments = mock(PaymentRepository.class);
	private final BudgetLookup budgets = mock(BudgetLookup.class);
	private final PatientLookup patients = mock(PatientLookup.class);
	private final ClinicZones zones = mock(ClinicZones.class);
	// 2026-11-01 02:00 UTC = 2026-10-31 23:00 em São Paulo: "hoje" na clínica é 31/out
	private final Clock clock = Clock.fixed(Instant.parse("2026-11-01T02:00:00Z"), ZoneOffset.UTC);
	private final FinanceReportService service = new FinanceReportService(installments, payments, budgets, patients, zones, clock);

	record Net(UUID getInstallmentId, BigDecimal getNet) implements PaymentRepository.NetPaid {
	}

	record Totals(long getCount, BigDecimal getAmount) implements InstallmentRepository.Totals {
	}

	record Day(LocalDate getKey, BigDecimal getNet) implements PaymentRepository.DayNet {
	}

	record Method(PaymentMethod getKey, BigDecimal getNet) implements PaymentRepository.MethodNet {
	}

	private InstallmentRow row(LocalDate due, String amount) {
		return new InstallmentRow(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, InstallmentStatus.OPEN,
				new BigDecimal(amount), due, 0L);
	}

	// ---- janela ----

	@Test
	void theWindowIsValidatedBeforeAnyQuery() {
		LocalDate day = LocalDate.parse("2026-05-01");
		for (LocalDate[] bad : new LocalDate[][] { { null, null }, { day, null }, { null, day }, { day, day.minusDays(1) },
				{ day, day.plusDays(366) }, { LocalDate.parse("1999-12-31"), day } }) {
			assertThatThrownBy(() -> service.cashFlow(bad[0], bad[1])).isInstanceOf(FieldValidationException.class);
			assertThatThrownBy(() -> service.revenue(bad[0], bad[1])).isInstanceOf(FieldValidationException.class);
			assertThatThrownBy(() -> service.receivables(bad[0], bad[1], 0, 20)).isInstanceOf(FieldValidationException.class);
		}

		verify(payments, never()).netByDay(any(), any());
		verify(installments, never()).receivables(any(), any(), any());
	}

	@Test
	void aWindowOf366InclusiveDaysIsAcceptedAnd367IsNot() {
		LocalDate from = LocalDate.parse("2028-01-01"); // ano bissexto
		when(zones.zone()).thenReturn(ZoneId.of("America/Sao_Paulo"));
		when(payments.netByDay(any(), any())).thenReturn(List.of());
		when(payments.netByMethod(any(), any())).thenReturn(List.of());

		assertThat(service.cashFlow(from, from.plusDays(365)).byDay()).isEmpty();
		assertThatThrownBy(() -> service.cashFlow(from, from.plusDays(366))).isInstanceOf(FieldValidationException.class);
	}

	@Test
	void theValidationErrorNamesTheOffendingField() {
		assertThatThrownBy(() -> service.cashFlow(null, LocalDate.parse("2026-01-01"))).isInstanceOfSatisfying(FieldValidationException.class,
				ex -> assertThat(ex.errors()).extracting(error -> error.field()).containsExactly("from"));
	}

	// ---- a receber e inadimplência ----

	@Test
	void receivablesEnrichTheRowsWithOneNetPaidQueryAndBatchLookups() {
		InstallmentRow a = row(LocalDate.parse("2026-12-01"), "400.00");
		InstallmentRow b = row(LocalDate.parse("2026-12-02"), "300.00");
		when(installments.receivables(any(), any(), any())).thenReturn(List.of(a, b));
		when(installments.receivablesTotals(any(), any())).thenReturn(new Totals(2, new BigDecimal("700.00")));
		when(payments.netPaidOnOpenDueBetween(any(), any())).thenReturn(new BigDecimal("100.00"));
		when(payments.netPaidByInstallmentIds(any())).thenReturn(List.of(new Net(a.id(), new BigDecimal("100.00"))));
		when(patients.briefs(any())).thenReturn(Map.of(a.patientId(), new PatientBrief(a.patientId(), "Maria Souza", "119")));
		when(budgets.brief(any())).thenReturn(Map.of(a.budgetId(), new BudgetBrief(a.budgetId(), 12, a.patientId())));

		ReceivablePage page = service.receivables(LocalDate.parse("2026-12-01"), LocalDate.parse("2026-12-31"), 0, 20);

		assertThat(page.count()).isEqualTo(2);
		assertThat(page.balanceTotal()).isEqualByComparingTo("600.00");
		assertThat(page.page().getTotalElements()).isEqualTo(2);
		assertThat(page.page().getContent().get(0).netPaid()).isEqualByComparingTo("100.00");
		assertThat(page.page().getContent().get(0).balance()).isEqualByComparingTo("300.00");
		assertThat(page.page().getContent().get(0).patient().name()).isEqualTo("Maria Souza");
		assertThat(page.page().getContent().get(0).budget().number()).isEqualTo(12);
		assertThat(page.page().getContent().get(1).netPaid()).isEqualByComparingTo("0.00");
		assertThat(page.page().getContent().get(1).patient()).isNull();
		assertThat(page.page().getContent().get(0).daysOverdue()).isNull();
		verify(payments).netPaidByInstallmentIds(any());
	}

	@Test
	void anEmptyPageSkipsTheLookups() {
		when(installments.receivables(any(), any(), any())).thenReturn(List.of());
		when(installments.receivablesTotals(any(), any())).thenReturn(new Totals(0, BigDecimal.ZERO));
		when(payments.netPaidOnOpenDueBetween(any(), any())).thenReturn(BigDecimal.ZERO);

		ReceivablePage page = service.receivables(LocalDate.parse("2026-12-01"), LocalDate.parse("2026-12-31"), 0, 20);

		assertThat(page.page().getContent()).isEmpty();
		verify(payments, never()).netPaidByInstallmentIds(any());
		verify(patients, never()).briefs(any());
		verify(budgets, never()).brief(any());
	}

	@Test
	void overdueUsesTodayInTheClinicZoneAndDerivesTheDaysLate() {
		when(zones.zone()).thenReturn(ZoneId.of("America/Sao_Paulo"));
		InstallmentRow late = row(LocalDate.parse("2026-10-21"), "400.00");
		when(installments.overdue(any(), any())).thenReturn(List.of(late));
		when(installments.overdueTotals(any())).thenReturn(new Totals(1, new BigDecimal("400.00")));
		when(payments.netPaidOnOverdue(any())).thenReturn(BigDecimal.ZERO);
		when(payments.netPaidByInstallmentIds(any())).thenReturn(List.of());
		when(patients.briefs(any())).thenReturn(Map.of());
		when(budgets.brief(any())).thenReturn(Map.of());

		ReceivablePage page = service.overdue(0, 20);

		ArgumentCaptor<LocalDate> today = ArgumentCaptor.forClass(LocalDate.class);
		verify(installments).overdue(today.capture(), any());
		assertThat(today.getValue()).isEqualTo(LocalDate.parse("2026-10-31")); // em UTC já seria 1º de novembro
		assertThat(page.page().getContent().get(0).daysOverdue()).isEqualTo(10);
		assertThat(page.balanceTotal()).isEqualByComparingTo("400.00");
	}

	// ---- caixa e faturamento ----

	@Test
	void cashFlowTotalsTheNetByDayAndKeepsTheMethodBreakdown() {
		when(payments.netByDay(any(), any())).thenReturn(List.of(new Day(LocalDate.parse("2026-10-01"), new BigDecimal("300.00")),
				new Day(LocalDate.parse("2026-10-02"), new BigDecimal("-50.00"))));
		when(payments.netByMethod(any(), any())).thenReturn(List.of(new Method(PaymentMethod.PIX, new BigDecimal("250.00"))));

		var flow = service.cashFlow(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"));

		assertThat(flow.netTotal()).isEqualByComparingTo("250.00");
		assertThat(flow.byDay()).hasSize(2);
		assertThat(flow.byMethod().get(0).method()).isEqualTo(PaymentMethod.PIX);
	}

	@Test
	void revenueConvertsThePeriodWithTheClinicZoneAndFlagsTruncation() {
		when(zones.zone()).thenReturn(ZoneId.of("America/Sao_Paulo"));
		UUID patient = UUID.randomUUID();
		ApprovedBudget approved = new ApprovedBudget(UUID.randomUUID(), 7, patient, new BigDecimal("800.00"), Instant.parse("2026-10-15T12:00:00Z"));
		when(budgets.approvedCount(any(), any())).thenReturn(201L);
		when(budgets.approvedTotal(any(), any())).thenReturn(new BigDecimal("160000.00"));
		when(budgets.approvedBetween(any(), any(), org.mockito.ArgumentMatchers.eq(FinanceRules.REVENUE_LIST_LIMIT))).thenReturn(List.of(approved));
		when(patients.briefs(any())).thenReturn(Map.of(patient, new PatientBrief(patient, "Maria Souza", "119")));

		Revenue revenue = service.revenue(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"));

		ArgumentCaptor<Instant> ini = ArgumentCaptor.forClass(Instant.class);
		ArgumentCaptor<Instant> fim = ArgumentCaptor.forClass(Instant.class);
		verify(budgets).approvedBetween(ini.capture(), fim.capture(), org.mockito.ArgumentMatchers.anyInt());
		assertThat(ini.getValue()).isEqualTo(Instant.parse("2026-10-01T03:00:00Z")); // meia-noite de SP (UTC−3)
		assertThat(fim.getValue()).isEqualTo(Instant.parse("2026-11-01T03:00:00Z")); // fim exclusivo: 1º de novembro em SP
		assertThat(revenue.truncated()).isTrue();
		assertThat(revenue.count()).isEqualTo(201);
		assertThat(revenue.totalAmount()).isEqualByComparingTo("160000.00");
		assertThat(revenue.budgets().get(0).patient().name()).isEqualTo("Maria Souza");
	}

	@Test
	void aRevenueListWithinTheLimitIsNotTruncated() {
		when(zones.zone()).thenReturn(ZoneId.of("UTC"));
		when(budgets.approvedCount(any(), any())).thenReturn(200L);
		when(budgets.approvedTotal(any(), any())).thenReturn(BigDecimal.ZERO);
		when(budgets.approvedBetween(any(), any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of());

		assertThat(service.revenue(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31")).truncated()).isFalse();
		verify(patients, never()).briefs(org.mockito.ArgumentMatchers.argThat(ids -> !ids.isEmpty()));
	}
}
