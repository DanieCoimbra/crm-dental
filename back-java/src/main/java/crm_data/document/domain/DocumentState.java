package crm_data.document.domain;

/** Estado derivado do documento (não há coluna): ativo, anulado ou anonimizado junto com o paciente. */
public enum DocumentState {
	ACTIVE, VOIDED, ANONYMIZED
}
