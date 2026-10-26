package crm_data.finance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import crm_data.clinic.service.ClinicZones;
import crm_data.commercial.service.ApprovedBudget;
import crm_data.commercial.service.BudgetLookup;
import crm_data.finance.domain.InstallmentStatus;
import crm_data.finance.repository.InstallmentRepository;
import crm_data.finance.repository.InstallmentRow;
import crm_data.finance.repository.PaymentRepository;
import crm_data.finance.service.PatientFinancialService.PatientFinancial;
import crm_data.patient.service.PatientLookup;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ProblemTypes;

class PatientFinancialServiceTest {

	private final InstallmentRepository installments = mock(InstallmentRepository.class);
	private final PaymentRepository payments = mock(PaymentRepository.class);
	private final BudgetLookup budgets = mock(BudgetLookup.class);
	private final PatientLookup patients = mock(PatientLookup.class);
	private final ClinicZones zones = mock(ClinicZones.class);
	private final Clock clock = Clock.fixed(Instant.parse("2026-10-27T01:30:00Z"), ZoneOffset.UTC);
	private final PatientFinancialService service = new PatientFinancialService(installments, payments, budgets, patients, zones, clock);

	private final UUID patientId = UUID.randomUUID();

	private InstallmentRow row(int number) {
		return new InstallmentRow(UUID.randomUUID(), patientId, UUID.randomUUID(), number, InstallmentStatus.OPEN, new BigDecimal("10.00"),
				LocalDate.parse("2026-12-01"), 0L);
	}

	private void aggregates() {
		when(zones.zone()).thenReturn(ZoneId.of("America/Sao_Paulo"));
		when(installments.activeAmountOfPatient(patientId)).thenReturn(new BigDecimal("800.00"));
		when(payments.netPaidOfPatient(patientId)).thenReturn(new BigDecimal("500.00"));
		when(installments.overdueAmountOfPatient(eq(patientId), any())).thenReturn(new BigDecimal("400.00"));
		when(payments.netPaidOnOverdue(any())).thenReturn(BigDecimal.ZERO);
		when(payments.netPaidOfPatientOverdue(eq(patientId), any())).thenReturn(new BigDecimal("100.00"));
		when(budgets.approvedTotalOfPatient(patientId)).thenReturn(new BigDecimal("800.00"));
	}

	@Test
	void totalsComeFromAggregatesAndTheBalanceIsActiveMinusPaid() {
		aggregates();
		when(budgets.approvedOfPatient(eq(patientId), any(Integer.class))).thenReturn(
				List.of(new ApprovedBudget(UUID.randomUUID(), 3, patientId, new BigDecimal("800.00"), Instant.now())));
		when(installments.ofPatient(eq(patientId), any())).thenReturn(List.of(row(1), row(2)));
		when(payments.netPaidByInstallmentIds(any())).thenReturn(List.of());

		PatientFinancial sheet = service.get(patientId);

		assertThat(sheet.approvedTotal()).isEqualByComparingTo("800.00");
		assertThat(sheet.activeInstallmentsTotal()).isEqualByComparingTo("800.00");
		assertThat(sheet.paid()).isEqualByComparingTo("500.00");
		assertThat(sheet.balance()).isEqualByComparingTo("300.00");
		assertThat(sheet.overdue()).isEqualByComparingTo("300.00"); // 400 vencido − 100 já pago dele
		assertThat(sheet.installments()).hasSize(2);
		assertThat(sheet.truncated()).isFalse();
	}

	@Test
	void moreThanTheLimitOfInstallmentsTruncatesButTheTotalsStayWhole() {
		aggregates();
		when(budgets.approvedOfPatient(eq(patientId), any(Integer.class))).thenReturn(List.of());
		List<InstallmentRow> many = new ArrayList<>();
		for (int i = 0; i < FinanceRules.PATIENT_INSTALLMENTS_LIMIT + 1; i++) {
			many.add(row(i % 60 + 1));
		}
		when(installments.ofPatient(eq(patientId), any())).thenReturn(many);
		when(payments.netPaidByInstallmentIds(any())).thenReturn(List.of());

		PatientFinancial sheet = service.get(patientId);

		assertThat(sheet.truncated()).isTrue();
		assertThat(sheet.installments()).hasSize(FinanceRules.PATIENT_INSTALLMENTS_LIMIT);
		assertThat(sheet.activeInstallmentsTotal()).isEqualByComparingTo("800.00");
	}

	@Test
	void anUnknownPatientIs404AndNothingElseIsQueried() {
		doThrow(new BusinessException(ProblemTypes.NOT_FOUND, "Paciente não encontrado.")).when(patients).requireExists(patientId);

		assertThatThrownBy(() -> service.get(patientId)).isInstanceOf(BusinessException.class);

		verify(installments, never()).ofPatient(any(), any());
		verify(budgets, never()).approvedTotalOfPatient(any());
	}
}
