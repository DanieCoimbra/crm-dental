package crm_data.clinic.service;

/**
 * Interface pública do módulo {@code clinic} para o PDF do orçamento: nome, endereço e telefone da clínica do token,
 * sem expor a entidade nem o repositório. Só o necessário para o cabeçalho do documento (nada de CNPJ, e-mail ou fuso).
 */
public interface ClinicIdentity {

	/** Dados de cabeçalho; {@code address} e {@code phone} podem ser {@code null}. */
	record ClinicInfo(String name, String address, String phone) {

		/** Nunca imprime os dados da clínica. */
		@Override
		public String toString() {
			return "ClinicInfo[]";
		}
	}

	/**
	 * @return os dados da clínica da transação
	 * @throws crm_data.shared.error.BusinessException {@code not-found} (404) se a clínica não existe
	 */
	ClinicInfo current();
}
