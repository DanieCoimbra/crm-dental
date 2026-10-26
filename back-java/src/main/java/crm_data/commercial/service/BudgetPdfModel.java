package crm_data.commercial.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Tudo o que o PDF do orçamento mostra, já resolvido: o renderizador não acessa banco, Storage nem relógio. Traz o nome
 * do paciente (o PDF o exibe) e <strong>nunca</strong> CPF, telefone ou e-mail. Nada daqui vai para log: o
 * {@code toString} não imprime dado algum.
 *
 * @param revision 1 no primeiro envio; maior que 1 nas reemissões (aparece no título)
 * @param sentOn data do envio (fuso da clínica); {@code null} se o orçamento nunca foi enviado (rascunho descartado), e o PDF diz "Emitido em"
 * @param issuedOn data em que este PDF foi gerado (igual a {@code sentOn} na revisão 1)
 * @param validUntil opcional; informativo
 * @param notes opcional; texto claro, não leva dado clínico
 */
public record BudgetPdfModel(String clinicName, String clinicAddress, String clinicPhone, String patientName, int number,
		int revision, LocalDate sentOn, LocalDate issuedOn, LocalDate validUntil, List<Line> items, BigDecimal subtotal,
		BigDecimal discount, BigDecimal total, String notes) {

	/** Uma linha de item, com o preço copiado do catálogo no momento da criação. */
	public record Line(String description, int quantity, BigDecimal unitPrice, BigDecimal discount, BigDecimal lineTotal) {

		@Override
		public String toString() {
			return "Line[]";
		}
	}

	@Override
	public String toString() {
		return "BudgetPdfModel[number=" + number + ", revision=" + revision + "]";
	}
}
