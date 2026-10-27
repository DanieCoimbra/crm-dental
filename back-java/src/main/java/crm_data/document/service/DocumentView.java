package crm_data.document.service;

import crm_data.document.domain.ClinicalDocument;

/**
 * Documento com o texto já decifrado (nulo se anonimizado) e os nomes resolvidos, pronto para a resposta. O
 * {@code toString} nunca imprime o conteúdo clínico.
 */
public record DocumentView(ClinicalDocument document, String content, String createdByName, String voidedByName) {

	@Override
	public String toString() {
		return "DocumentView[id=" + document.getId() + "]";
	}
}
