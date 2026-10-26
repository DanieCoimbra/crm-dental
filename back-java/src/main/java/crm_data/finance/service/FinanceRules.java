package crm_data.finance.service;

import java.time.LocalDate;

/** Limites do financeiro (iguais às {@code CHECK} do banco; a validação do backend vem antes e devolve o campo). */
public final class FinanceRules {

	public static final int MAX_INSTALLMENTS = 60;

	public static final LocalDate EARLIEST_DATE = LocalDate.of(2000, 1, 1);

	/** Janela máxima dos relatórios, em dias corridos contando as duas pontas. */
	public static final int MAX_WINDOW_DAYS = 366;

	public static final int DEFAULT_PAGE_SIZE = 20;
	public static final int MAX_PAGE_SIZE = 100;

	/** Lista de orçamentos do faturamento e parcelas da ficha do paciente (acima disso, {@code truncated}). */
	public static final int REVENUE_LIST_LIMIT = 200;
	public static final int PATIENT_INSTALLMENTS_LIMIT = 1000;

	private FinanceRules() {
	}
}
