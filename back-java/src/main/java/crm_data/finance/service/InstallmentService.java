package crm_data.finance.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import crm_data.commercial.service.BudgetLookup;
import crm_data.finance.domain.Installment;
import crm_data.finance.domain.InstallmentStatus;
import crm_data.finance.repository.InstallmentRepository;
import crm_data.finance.repository.PaymentRepository;
import crm_data.patient.service.PatientLookup;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ConflictMessages;
import crm_data.shared.error.FieldValidationException;
import crm_data.shared.error.ProblemDetails.FieldError;
import crm_data.shared.error.ProblemTypes;
import crm_data.shared.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/**
 * Consulta do plano de parcelas e ajuste de vencimento. Só o dentista (as parcelas nunca são visíveis à recepção). O
 * {@code netPaid} de todas as parcelas vem de <strong>uma</strong> consulta agregada (sem N+1).
 */
@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('DENTIST_ADMIN')")
public class InstallmentService {

	/** Parcela com o líquido pago; {@code balance = amount − netPaid}. */
	public record InstallmentView(Installment installment, BigDecimal netPaid) {

		public BigDecimal balance() {
			return installment.getAmount().subtract(netPaid);
		}
	}

	/** Plano: todas as parcelas (inclusive canceladas); {@code activeSum} soma só as não canceladas. */
	public record PlanView(UUID budgetId, BigDecimal totalAmount, BigDecimal activeSum, List<InstallmentView> installments) {
	}

	private final InstallmentRepository installments;
	private final PaymentRepository payments;
	private final BudgetLookup budgets;
	private final PatientLookup patients;
	private final ApplicationEventPublisher events;
	private final EntityManager entityManager;

	/** @throws BusinessException 404 se o orçamento não existe na clínica */
	@Transactional(readOnly = true)
	public PlanView listByBudget(UUID budgetId) {
		BigDecimal total = budgets.totalOf(budgetId);
		return planView(budgetId, total);
	}

	/** Monta a visão do plano na transação corrente (usado também depois de criar ou refazer). */
	PlanView planView(UUID budgetId, BigDecimal total) {
		List<Installment> all = installments.findAllByBudgetIdOrderByNumberAscIdAsc(budgetId);
		Map<UUID, BigDecimal> net = netPaid(all.stream().map(Installment::getId).toList());
		List<InstallmentView> views = new ArrayList<>(all.size());
		BigDecimal activeSum = BigDecimal.ZERO.setScale(2);
		for (Installment i : all) {
			views.add(new InstallmentView(i, net.getOrDefault(i.getId(), BigDecimal.ZERO.setScale(2))));
			if (i.getStatus() != InstallmentStatus.CANCELED) {
				activeSum = activeSum.add(i.getAmount());
			}
		}
		return new PlanView(budgetId, total, activeSum, views);
	}

	/** Líquido pago por parcela (uma consulta); parcela sem pagamento fica de fora do mapa. */
	Map<UUID, BigDecimal> netPaid(Collection<UUID> installmentIds) {
		Map<UUID, BigDecimal> map = new HashMap<>();
		if (installmentIds.isEmpty()) {
			return map;
		}
		for (PaymentRepository.NetPaid row : payments.netPaidByInstallmentIds(installmentIds)) {
			map.put(row.getInstallmentId(), row.getNet());
		}
		return map;
	}

	/**
	 * Muda o vencimento de uma parcela {@code OPEN}.
	 *
	 * @throws BusinessException 404; 409 com {@code version} defasada, parcela paga/cancelada ou paciente anonimizado
	 */
	@Transactional
	public InstallmentView changeDueDate(UUID id, LocalDate dueDate, long expectedVersion) {
		if (dueDate == null) {
			throw new FieldValidationException(List.of(new FieldError("dueDate", "obrigatório")));
		}
		Installment installment = installments.findProjectionById(id)
				.orElseThrow(() -> new BusinessException(ProblemTypes.NOT_FOUND, "Parcela não encontrada."));
		if (!Objects.equals(installment.getVersion(), expectedVersion)) {
			throw new BusinessException(ProblemTypes.CONFLICT, ConflictMessages.STALE_VERSION);
		}
		if (installment.getStatus() != InstallmentStatus.OPEN) {
			throw new BusinessException(ProblemTypes.CONFLICT, ConflictMessages.INSTALLMENT_NOT_OPEN);
		}
		patients.requireWritable(installment.getPatientId());
		installment.setDueDate(dueDate);
		installments.saveAndFlush(installment);

		events.publishEvent(new FinanceEvents.DueDateChanged(TenantContext.clinicId(), id, TenantContext.userId()));
		entityManager.clear();
		Installment reloaded = installments.findProjectionById(id).orElseThrow();
		return new InstallmentView(reloaded, netPaid(List.of(id)).getOrDefault(id, BigDecimal.ZERO.setScale(2)));
	}
}
