package crm_data.finance.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import crm_data.shared.error.FieldValidationException;
import crm_data.shared.error.ProblemDetails.FieldError;
import crm_data.shared.money.Money;

/**
 * Calcula as parcelas de um plano. Regra pura (sem Spring nem JPA): {@code Σ parcelas == total} sempre, parcelas de
 * pelo menos R$ 0,01, no máximo 60. {@code AUTO}: as {@code n − 1} primeiras iguais (arredondadas para baixo) e a última
 * absorve os centavos; vencimentos mensais a partir da primeira data (31/jan → 28/fev → 31/mar). Com entrada, ela é a
 * parcela 1 e vence em {@code today}; {@code count} a inclui. {@code MANUAL}: ordena por vencimento e depois pela ordem
 * recebida e numera de 1 a n.
 */
public final class InstallmentPlanner {

	private InstallmentPlanner() {
	}

	/** @throws FieldValidationException 400 com os campos inválidos (e a diferença, na soma manual) */
	public static List<PlannedInstallment> plan(BigDecimal total, PlanRequest request, LocalDate today) {
		if (request.mode() == null) {
			throw invalid("mode", "obrigatório");
		}
		return switch (request.mode()) {
			case AUTO -> auto(total, request, today);
			case MANUAL -> manual(total, request);
		};
	}

	private static List<PlannedInstallment> auto(BigDecimal total, PlanRequest request, LocalDate today) {
		List<FieldError> errors = new ArrayList<>();
		Integer count = request.count();
		if (count == null || count < 1 || count > FinanceRules.MAX_INSTALLMENTS) {
			errors.add(new FieldError("count", "deve estar entre 1 e " + FinanceRules.MAX_INSTALLMENTS));
		}
		LocalDate first = request.firstDueDate();
		if (first == null) {
			errors.add(new FieldError("firstDueDate", "obrigatório"));
		}
		else if (first.isBefore(FinanceRules.EARLIEST_DATE)) {
			errors.add(new FieldError("firstDueDate", "deve ser 2000-01-01 ou posterior"));
		}
		BigDecimal down = request.downPayment();
		boolean hasDown = down != null && down.signum() > 0;
		if (down != null && down.signum() < 0) {
			errors.add(new FieldError("downPayment", "não pode ser negativa"));
		}
		else if (hasDown && (down.compareTo(total) >= 0 || down.compareTo(Money.CENT) < 0)) {
			errors.add(new FieldError("downPayment", "deve ser maior que zero e menor que o total"));
		}
		else if (hasDown && count != null && count < 2) {
			errors.add(new FieldError("count", "com entrada, informe ao menos 2 parcelas"));
		}
		if (!errors.isEmpty()) {
			throw new FieldValidationException(errors);
		}

		List<PlannedInstallment> plan = new ArrayList<>(count);
		if (hasDown) {
			plan.add(new PlannedInstallment(1, down, today));
			List<BigDecimal> rest = Money.splitDown(total.subtract(down), count - 1);
			for (int k = 0; k < rest.size(); k++) {
				plan.add(new PlannedInstallment(k + 2, rest.get(k), first.plusMonths(k)));
			}
		}
		else {
			List<BigDecimal> parts = Money.splitDown(total, count);
			for (int k = 0; k < parts.size(); k++) {
				plan.add(new PlannedInstallment(k + 1, parts.get(k), first.plusMonths(k)));
			}
		}
		for (PlannedInstallment p : plan) {
			if (p.amount().compareTo(Money.CENT) < 0) {
				throw invalid("count", "o valor de cada parcela deve ser de pelo menos R$ 0,01");
			}
		}
		return List.copyOf(plan);
	}

	private static List<PlannedInstallment> manual(BigDecimal total, PlanRequest request) {
		List<PlanRequest.ManualItem> items = request.items();
		if (items == null || items.isEmpty() || items.size() > FinanceRules.MAX_INSTALLMENTS) {
			throw invalid("installments", "informe de 1 a " + FinanceRules.MAX_INSTALLMENTS + " parcelas");
		}
		List<FieldError> errors = new ArrayList<>();
		BigDecimal sum = BigDecimal.ZERO;
		for (int i = 0; i < items.size(); i++) {
			PlanRequest.ManualItem item = items.get(i);
			if (item == null || item.amount() == null) {
				errors.add(new FieldError("installments[" + i + "].amount", "obrigatório"));
				continue;
			}
			if (item.amount().compareTo(Money.CENT) < 0) {
				errors.add(new FieldError("installments[" + i + "].amount", "deve ser de pelo menos R$ 0,01"));
			}
			else {
				sum = sum.add(item.amount());
			}
			if (item.dueDate() == null) {
				errors.add(new FieldError("installments[" + i + "].dueDate", "obrigatório"));
			}
			else if (item.dueDate().isBefore(FinanceRules.EARLIEST_DATE)) {
				errors.add(new FieldError("installments[" + i + "].dueDate", "deve ser 2000-01-01 ou posterior"));
			}
		}
		if (!errors.isEmpty()) {
			throw new FieldValidationException(errors);
		}
		if (sum.compareTo(total) != 0) {
			BigDecimal diff = total.subtract(sum);
			throw invalid("installments", "a soma das parcelas difere do total em " + Money.format(diff.abs())
					+ (diff.signum() > 0 ? " (faltam)" : " (excedem)"));
		}
		List<PlanRequest.ManualItem> sorted = new ArrayList<>(items);
		sorted.sort(Comparator.comparing(PlanRequest.ManualItem::dueDate)); // estável: empate mantém a ordem recebida
		List<PlannedInstallment> plan = new ArrayList<>(sorted.size());
		for (int i = 0; i < sorted.size(); i++) {
			plan.add(new PlannedInstallment(i + 1, sorted.get(i).amount(), sorted.get(i).dueDate()));
		}
		return List.copyOf(plan);
	}

	private static FieldValidationException invalid(String field, String message) {
		return new FieldValidationException(List.of(new FieldError(field, message)));
	}
}
