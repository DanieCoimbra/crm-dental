package crm_data.finance.domain;

/** Situação da parcela. {@code OPEN}/{@code PAID} são mantidos pelo banco (trigger de pagamento); o backend só cancela. */
public enum InstallmentStatus {
	OPEN, PAID, CANCELED
}
