package crm_data.finance.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import crm_data.clinic.service.ClinicZones;
import crm_data.commercial.service.ApprovedBudget;
import crm_data.commercial.service.BudgetBrief;
import crm_data.commercial.service.BudgetLookup;
import crm_data.finance.domain.PaymentMethod;
import crm_data.finance.repository.InstallmentRepository;
import crm_data.finance.repository.InstallmentRow;
import crm_data.finance.repository.PaymentRepository;
import crm_data.patient.service.PatientBrief;
import crm_data.patient.service.PatientLookup;
import crm_data.shared.error.FieldValidationException;
import crm_data.shared.error.ProblemDetails.FieldError;
import lombok.RequiredArgsConstructor;

/**
 * Consultas do financeiro (só o dentista): a receber, vencidas, fluxo de caixa e faturamento. Só leitura, JPQL, sem SQL
 * nativo. "Hoje" e os limites do período são sempre da clínica ({@link ClinicZones}); o atraso é <strong>derivado</strong>
 * ({@code OPEN} com vencimento antes de hoje), nunca gravado. {@code netPaid} de uma página vem de uma consulta agregada;
 * pacientes e orçamentos, em lote (sem N+1).
 */
@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('DENTIST_ADMIN')")
public class FinanceReportService {

	/** Parcela de uma lista do financeiro; {@code daysOverdue} só na inadimplência. */
	public record Receivable(InstallmentRow installment, BigDecimal netPaid, PatientBrief patient, BudgetBrief budget,
			Long daysOverdue) {

		public BigDecimal balance() {
			return installment.amount().subtract(netPaid);
		}
	}

	public record ReceivablePage(Page<Receivable> page, long count, BigDecimal balanceTotal) {
	}

	public record DayNet(LocalDate date, BigDecimal net) {
	}

	public record MethodNet(PaymentMethod method, BigDecimal net) {
	}

	public record CashFlow(LocalDate from, LocalDate to, BigDecimal netTotal, List<DayNet> byDay, List<MethodNet> byMethod) {
	}

	public record RevenueItem(ApprovedBudget budget, PatientBrief patient) {
	}

	public record Revenue(LocalDate from, LocalDate to, long count, BigDecimal totalAmount, List<RevenueItem> budgets,
			boolean truncated) {
	}

	private final InstallmentRepository installments;
	private final PaymentRepository payments;
	private final BudgetLookup budgets;
	private final PatientLookup patients;
	private final ClinicZones zones;
	private final Clock clock;

	@Transactional(readOnly = true)
	public ReceivablePage receivables(LocalDate from, LocalDate to, int page, int size) {
		validateWindow(from, to);
		List<InstallmentRow> rows = installments.receivables(from, to, PageRequest.of(page, size));
		InstallmentRepository.Totals totals = installments.receivablesTotals(from, to);
		BigDecimal paid = payments.netPaidOnOpenDueBetween(from, to);
		return new ReceivablePage(paged(rows, page, size, totals.getCount(), null), totals.getCount(), totals.getAmount().subtract(paid));
	}

	@Transactional(readOnly = true)
	public ReceivablePage overdue(int page, int size) {
		LocalDate today = today();
		List<InstallmentRow> rows = installments.overdue(today, PageRequest.of(page, size));
		InstallmentRepository.Totals totals = installments.overdueTotals(today);
		BigDecimal paid = payments.netPaidOnOverdue(today);
		return new ReceivablePage(paged(rows, page, size, totals.getCount(), today), totals.getCount(), totals.getAmount().subtract(paid));
	}

	/** Entradas líquidas (pagamentos − estornos) por {@code paidOn} (regime de caixa). */
	@Transactional(readOnly = true)
	public CashFlow cashFlow(LocalDate from, LocalDate to) {
		validateWindow(from, to);
		List<DayNet> byDay = payments.netByDay(from, to).stream().map(row -> new DayNet(row.getKey(), row.getNet())).toList();
		List<MethodNet> byMethod = payments.netByMethod(from, to).stream().map(row -> new MethodNet(row.getKey(), row.getNet())).toList();
		BigDecimal total = byDay.stream().map(DayNet::net).reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
		return new CashFlow(from, to, total, byDay, byMethod);
	}

	/** Faturamento (competência): orçamentos aprovados por {@code decidedAt}, com o período convertido pelo fuso da clínica. */
	@Transactional(readOnly = true)
	public Revenue revenue(LocalDate from, LocalDate to) {
		validateWindow(from, to);
		ZoneId zone = zones.zone();
		Instant ini = from.atStartOfDay(zone).toInstant();
		Instant fim = to.plusDays(1).atStartOfDay(zone).toInstant();
		long count = budgets.approvedCount(ini, fim);
		BigDecimal total = budgets.approvedTotal(ini, fim);
		List<ApprovedBudget> listed = budgets.approvedBetween(ini, fim, FinanceRules.REVENUE_LIST_LIMIT);
		Map<UUID, PatientBrief> names = patients.briefs(listed.stream().map(ApprovedBudget::patientId).distinct().toList());
		List<RevenueItem> items = listed.stream().map(b -> new RevenueItem(b, names.get(b.patientId()))).toList();
		return new Revenue(from, to, count, total, items, count > FinanceRules.REVENUE_LIST_LIMIT);
	}

	/** Completa as linhas com o líquido pago (uma consulta), pacientes e números de orçamento (em lote). */
	private Page<Receivable> paged(List<InstallmentRow> rows, int page, int size, long total, LocalDate overdueToday) {
		List<UUID> ids = rows.stream().map(InstallmentRow::id).toList();
		Map<UUID, BigDecimal> net = netPaid(ids);
		Map<UUID, PatientBrief> names = ids.isEmpty() ? Map.of()
				: patients.briefs(rows.stream().map(InstallmentRow::patientId).distinct().toList());
		Map<UUID, BudgetBrief> numbers = ids.isEmpty() ? Map.of()
				: budgets.brief(rows.stream().map(InstallmentRow::budgetId).distinct().toList());
		List<Receivable> content = rows.stream().map(row -> new Receivable(row, net.getOrDefault(row.id(), BigDecimal.ZERO.setScale(2)),
				names.get(row.patientId()), numbers.get(row.budgetId()),
				overdueToday == null ? null : ChronoUnit.DAYS.between(row.dueDate(), overdueToday))).toList();
		return new PageImpl<>(content, PageRequest.of(page, size), total);
	}

	private Map<UUID, BigDecimal> netPaid(Collection<UUID> ids) {
		Map<UUID, BigDecimal> map = new HashMap<>();
		if (!ids.isEmpty()) {
			payments.netPaidByInstallmentIds(ids).forEach(row -> map.put(row.getInstallmentId(), row.getNet()));
		}
		return map;
	}

	/** {@code from} e {@code to} obrigatórios, {@code to >= from} e a janela de no máximo 366 dias (as duas pontas contam). */
	static void validateWindow(LocalDate from, LocalDate to) {
		List<FieldError> errors = new ArrayList<>();
		if (from == null) {
			errors.add(new FieldError("from", "obrigatório"));
		}
		else if (from.isBefore(FinanceRules.EARLIEST_DATE)) {
			errors.add(new FieldError("from", "deve ser 2000-01-01 ou posterior"));
		}
		if (to == null) {
			errors.add(new FieldError("to", "obrigatório"));
		}
		if (errors.isEmpty()) {
			if (to.isBefore(from)) {
				errors.add(new FieldError("to", "deve ser igual ou posterior a from"));
			}
			else if (ChronoUnit.DAYS.between(from, to) + 1 > FinanceRules.MAX_WINDOW_DAYS) {
				errors.add(new FieldError("to", "a janela deve ter no máximo " + FinanceRules.MAX_WINDOW_DAYS + " dias"));
			}
		}
		if (!errors.isEmpty()) {
			throw new FieldValidationException(errors);
		}
	}

	private LocalDate today() {
		return LocalDate.now(clock.withZone(zones.zone()));
	}
}
