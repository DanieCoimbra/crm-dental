package crm_data.finance.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import crm_data.clinic.service.ClinicZones;
import crm_data.commercial.service.ApprovedBudget;
import crm_data.finance.repository.InstallmentRepository;
import crm_data.finance.repository.InstallmentRow;
import crm_data.finance.repository.PaymentRepository;
import crm_data.commercial.service.BudgetLookup;
import crm_data.patient.service.PatientLookup;
import lombok.RequiredArgsConstructor;

/**
 * Ficha financeira do paciente (só o dentista): orçamentos aprovados, todas as parcelas (até 1000) e totais por agregação
 * (nunca pela lista truncada). Paciente inativo ou anonimizado continua consultável; inexistente → 404.
 */
@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('DENTIST_ADMIN')")
public class PatientFinancialService {

	public record InstallmentLine(InstallmentRow installment, BigDecimal netPaid) {

		public BigDecimal balance() {
			return installment.amount().subtract(netPaid);
		}
	}

	public record PatientFinancial(UUID patientId, BigDecimal approvedTotal, BigDecimal activeInstallmentsTotal, BigDecimal paid,
			BigDecimal balance, BigDecimal overdue, List<ApprovedBudget> budgets, List<InstallmentLine> installments,
			boolean truncated) {
	}

	private final InstallmentRepository installments;
	private final PaymentRepository payments;
	private final BudgetLookup budgets;
	private final PatientLookup patients;
	private final ClinicZones zones;
	private final Clock clock;

	@Transactional(readOnly = true)
	public PatientFinancial get(UUID patientId) {
		patients.requireExists(patientId);
		LocalDate today = LocalDate.now(clock.withZone(zones.zone()));
		int limit = FinanceRules.PATIENT_INSTALLMENTS_LIMIT;
		List<ApprovedBudget> approvedRows = budgets.approvedOfPatient(patientId, limit + 1);
		boolean budgetsTruncated = approvedRows.size() > limit;
		List<ApprovedBudget> approved = budgetsTruncated ? approvedRows.subList(0, limit) : approvedRows;
		BigDecimal approvedTotal = budgets.approvedTotalOfPatient(patientId);

		List<InstallmentRow> rows = installments.ofPatient(patientId, PageRequest.of(0, limit + 1));
		boolean truncated = budgetsTruncated || rows.size() > limit;
		List<InstallmentRow> shown = rows.size() > limit ? rows.subList(0, limit) : rows;
		Map<UUID, BigDecimal> net = new HashMap<>();
		if (!shown.isEmpty()) {
			payments.netPaidByInstallmentIds(shown.stream().map(InstallmentRow::id).toList())
					.forEach(row -> net.put(row.getInstallmentId(), row.getNet()));
		}
		List<InstallmentLine> lines = shown.stream()
				.map(row -> new InstallmentLine(row, net.getOrDefault(row.id(), BigDecimal.ZERO.setScale(2)))).toList();

		BigDecimal active = installments.activeAmountOfPatient(patientId);
		BigDecimal paid = payments.netPaidOfPatient(patientId);
		BigDecimal overdue = installments.overdueAmountOfPatient(patientId, today)
				.subtract(payments.netPaidOfPatientOverdue(patientId, today));
		return new PatientFinancial(patientId, approvedTotal, active, paid, active.subtract(paid), overdue, approved, lines, truncated);
	}
}
