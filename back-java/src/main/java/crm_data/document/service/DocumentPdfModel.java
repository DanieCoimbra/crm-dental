package crm_data.document.service;

import java.time.LocalDate;

import crm_data.document.domain.DocumentType;

/**
 * Tudo que o PDF do documento mostra, já resolvido (texto decifrado, nomes). Imutável e sem {@code toString} com dado: o
 * conteúdo clínico e o nome do paciente nunca vão para log. Sem CPF e sem data de nascimento por construção.
 *
 * @param voidedOn data da anulação, ou {@code null} se o documento está ativo (liga a marca "ANULADO")
 * @param restDays só atestado
 * @param targetSpecialty só encaminhamento
 * @param content complemento do atestado/encaminhamento ou texto da receita; pode ser {@code null} no atestado
 * @param dentistCro e {@code dentistCroState} podem ser {@code null}: a linha do CRO é omitida
 */
public record DocumentPdfModel(String clinicName, String clinicAddress, String clinicPhone, DocumentType type, int number,
		LocalDate issuedOn, String patientName, Integer restDays, String targetSpecialty, String content, LocalDate voidedOn,
		String dentistName, String dentistCro, String dentistCroState) {

	@Override
	public String toString() {
		return "DocumentPdfModel[type=" + type + ", number=" + number + "]";
	}
}
