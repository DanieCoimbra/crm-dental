package crm_data.commercial.service;

/** Limites do orçamento (iguais às {@code CHECK} do banco; a validação do backend vem antes e devolve o campo). */
public final class CommercialRules {

	/** Itens por orçamento ({@code position BETWEEN 1 AND 50}). */
	public static final int MAX_ITEMS = 50;

	public static final int MIN_QUANTITY = 1;
	public static final int MAX_QUANTITY = 999;

	public static final int NOTES_MAX = 500;

	/** Tamanho de página: padrão e teto (lista de orçamentos e histórico do paciente). */
	public static final int DEFAULT_PAGE_SIZE = 20;
	public static final int MAX_PAGE_SIZE = 50;

	private CommercialRules() {
	}
}
