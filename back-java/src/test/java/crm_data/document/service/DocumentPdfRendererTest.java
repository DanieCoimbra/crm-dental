package crm_data.document.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;

import crm_data.document.domain.DocumentType;

@SuppressWarnings("deprecation") // OpenPDF 2.x: ver DocumentPdfRenderer
class DocumentPdfRendererTest {

	private final DocumentPdfRenderer renderer = new DocumentPdfRenderer();

	private static DocumentPdfModel model(DocumentType type, int number, Integer days, String specialty, String content,
			LocalDate voidedOn) {
		return new DocumentPdfModel("Clínica Sorriso", "Rua das Flores, 100 - Centro", "(11) 3333-4444", type, number,
				LocalDate.of(2026, 10, 27), "Maria Conceição Souza", days, specialty, content, voidedOn, "Dra. Ana Lima", "12345",
				"SP");
	}

	/** Extrai o texto de todas as páginas, normalizando espaços e quebras para comparar frases. */
	private static String text(byte[] pdf) throws IOException {
		PdfReader reader = new PdfReader(pdf);
		try {
			PdfTextExtractor extractor = new PdfTextExtractor(reader);
			StringBuilder all = new StringBuilder();
			for (int page = 1; page <= reader.getNumberOfPages(); page++) {
				all.append(extractor.getTextFromPage(page)).append('\n');
			}
			return all.toString().replaceAll("[\\u00a0\\s]+", " ");
		}
		finally {
			reader.close();
		}
	}

	private static int pages(byte[] pdf) throws IOException {
		PdfReader reader = new PdfReader(pdf);
		try {
			return reader.getNumberOfPages();
		}
		finally {
			reader.close();
		}
	}

	@Test
	void certificateHasTheStandardSentenceDaysSignatureAndFooter() throws Exception {
		byte[] pdf = renderer.render(model(DocumentType.CERTIFICATE, 12, 2, null, null, null));

		String text = text(pdf);
		assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
		assertThat(text).contains("Clínica Sorriso", "Rua das Flores, 100 - Centro", "(11) 3333-4444", "ATESTADO Nº 12",
				"Paciente: Maria Conceição Souza", "Emissão: 27/10/2026",
				"Atesto, para os devidos fins, que Maria Conceição Souza esteve em atendimento odontológico nesta data e necessita de 2 dias de afastamento de suas atividades.",
				"Dra. Ana Lima", "CRO 12345/SP", "Atestado nº 12 — página 1 de 1").doesNotContain("ANULADO", "Anulado em");
	}

	@Test
	void singleRestDayIsSingular() throws Exception {
		String text = text(renderer.render(model(DocumentType.CERTIFICATE, 1, 1, null, null, null)));

		assertThat(text).contains("necessita de 1 dia de afastamento");
	}

	@Test
	void certificateComplementComesAfterTheStandardSentence() throws Exception {
		String text = text(renderer.render(model(DocumentType.CERTIFICATE, 3, 3, null, "Repouso relativo após exodontia.", null)));

		assertThat(text).contains("de afastamento de suas atividades.", "Repouso relativo após exodontia.");
	}

	@Test
	void referralNamesTheSpecialtyAndPrescriptionKeepsTheTextAndLineBreaks() throws Exception {
		String referral = text(renderer.render(model(DocumentType.REFERRAL, 1, null, "Endodontia", "Dente 36 com necrose pulpar.", null)));
		String prescription = text(renderer.render(model(DocumentType.PRESCRIPTION, 3, null, null, "Amoxicilina 500 mg\n8/8 h por 7 dias", null)));

		assertThat(referral).contains("ENCAMINHAMENTO Nº 1",
				"Encaminho o(a) paciente Maria Conceição Souza para avaliação e conduta em Endodontia.", "Dente 36 com necrose pulpar.");
		assertThat(prescription).contains("RECEITUÁRIO Nº 3", "Prescrição:", "Amoxicilina 500 mg", "8/8 h por 7 dias",
				"Receituário nº 3 — página 1 de 1");
	}

	@Test
	void voidedDocumentCarriesTheWatermarkOnEveryPageAndTheVoidDate() throws Exception {
		byte[] longText = renderer.render(model(DocumentType.PRESCRIPTION, 4, null, null, "Linha de prescrição.\n".repeat(150),
				LocalDate.of(2026, 10, 28)));

		String text = text(longText);
		int pages = pages(longText);
		assertThat(pages).isGreaterThanOrEqualTo(2);
		assertThat(text).contains("Anulado em 28/10/2026");
		assertThat(text.split("ANULADO", -1).length - 1).isGreaterThanOrEqualTo(pages);
	}

	@Test
	void documentWithoutCroOmitsTheCroLineButKeepsTheName() throws Exception {
		DocumentPdfModel noCro = new DocumentPdfModel("Clínica", null, null, DocumentType.CERTIFICATE, 1, LocalDate.of(2026, 10, 27),
				"Maria", 1, null, null, null, "Dra. Ana Lima", null, null);

		String text = text(renderer.render(noCro));

		assertThat(text).contains("Dra. Ana Lima").doesNotContain("CRO ");
	}

	@Test
	void contentOf4000CharactersFitsInTwoPages() throws Exception {
		byte[] pdf = renderer.render(model(DocumentType.PRESCRIPTION, 5, null, null, "Tomar 1 comprimido a cada 8 horas. ".repeat(115).substring(0, 4000), null));

		assertThat(pages(pdf)).isBetween(1, 2);
		assertThat(pdf.length).isLessThan(DocumentRules.MAX_PDF_BYTES);
	}

	@Test
	void metadataNeverCarriesThePatientNameAndTheModelNeverPrintsData() throws Exception {
		DocumentPdfModel model = model(DocumentType.CERTIFICATE, 12, 2, null, null, null);
		PdfReader reader = new PdfReader(renderer.render(model));
		String info = reader.getInfo().toString();
		reader.close();

		assertThat(info).contains("Atestado nº 12").doesNotContain("Maria", "Conceição", "Souza");
		assertThat(model.toString()).doesNotContain("Maria", "Souza", "12345");
	}

	@Test
	void neverPrintsCpfOrBirthDateAndSurvivesCharactersOutsideTheEncoding() throws Exception {
		String text = text(renderer.render(model(DocumentType.PRESCRIPTION, 6, null, null, "Dipirona 500 mg 中文 emoji 😀 ok", null)));

		assertThat(text).contains("Dipirona 500 mg", "ok").doesNotContainPattern("\\d{3}\\.\\d{3}\\.\\d{3}-\\d{2}")
				.doesNotContain("Nascimento", "CPF");
	}
}
