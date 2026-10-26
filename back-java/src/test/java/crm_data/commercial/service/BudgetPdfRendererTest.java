package crm_data.commercial.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;

import crm_data.commercial.service.BudgetPdfModel.Line;

@SuppressWarnings("deprecation") // OpenPDF 2.x: ver BudgetPdfRenderer
class BudgetPdfRendererTest {

	private final BudgetPdfRenderer renderer = new BudgetPdfRenderer();

	private static BudgetPdfModel model(int revision, List<Line> lines, String notes, LocalDate validUntil) {
		BigDecimal subtotal = lines.stream().map(Line::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
		return new BudgetPdfModel("Clínica Sorriso Ltda", "Rua das Flores, 120 — São Paulo/SP", "(11) 3333-4444", "Maria da Conceição Souza",
				12, revision, LocalDate.of(2026, 10, 27), LocalDate.of(2026, revision > 1 ? 11 : 10, 27), validUntil, lines, subtotal,
				new BigDecimal("50.00"), subtotal.subtract(new BigDecimal("50.00")), notes);
	}

	private static List<Line> twoLines() {
		return List.of(new Line("Restauração", 2, new BigDecimal("250.00"), new BigDecimal("0.00"), new BigDecimal("500.00")),
				new Line("Tratamento de canal", 1, new BigDecimal("900.00"), new BigDecimal("100.00"), new BigDecimal("800.00")));
	}

	/** Extrai o texto de todas as páginas, normalizando o espaço sem quebra que o Java usa em "R$ 1.234,50". */
	private static String text(byte[] pdf) throws IOException {
		PdfReader reader = new PdfReader(pdf);
		try {
			PdfTextExtractor extractor = new PdfTextExtractor(reader);
			StringBuilder all = new StringBuilder();
			for (int page = 1; page <= reader.getNumberOfPages(); page++) {
				all.append(extractor.getTextFromPage(page)).append('\n');
			}
			return all.toString().replaceAll("[\\u00a0 ]+", " ");
		}
		finally {
			reader.close();
		}
	}

	@Test
	void theFirstRevisionHasTheTitleItemsTotalsAndTheFooter() throws Exception {
		byte[] pdf = renderer.render(model(1, twoLines(), null, null));

		String text = text(pdf);
		assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
		assertThat(text).contains("Clínica Sorriso Ltda", "Rua das Flores, 120", "Telefone: (11) 3333-4444");
		assertThat(text).contains("Orçamento nº 12").doesNotContain("Revisão");
		assertThat(text).contains("Enviado em 27/10/2026");
		assertThat(text).contains("Paciente: Maria da Conceição Souza");
		assertThat(text).contains("Descrição", "Qtd", "Preço unit.", "Desconto", "Total");
		assertThat(text).contains("Restauração", "Tratamento de canal");
		assertThat(text).contains("R$ 250,00", "R$ 500,00", "R$ 900,00", "R$ 100,00", "R$ 800,00");
		assertThat(text).contains("Subtotal", "R$ 1.300,00", "R$ 50,00", "TOTAL", "R$ 1.250,00");
		assertThat(text).contains("Página 1 de 1");
	}

	@Test
	void aRevisionShowsTheRevisionNumberAndTheReissueDate() throws Exception {
		String text = text(renderer.render(model(3, twoLines(), null, null)));

		assertThat(text).contains("Orçamento nº 12 — Revisão 3", "Enviado em 27/10/2026", "Revisão gerada em 27/11/2026");
	}

	@Test
	void aBudgetThatWasNeverSentSaysIssuedNotSent() throws Exception {
		BudgetPdfModel model = new BudgetPdfModel("Clínica Sorriso", null, null, "Maria", 5, 1, null, LocalDate.of(2026, 11, 3), null,
				twoLines(), new BigDecimal("1300.00"), BigDecimal.ZERO.setScale(2), new BigDecimal("1300.00"), null);

		String text = text(renderer.render(model));

		assertThat(text).contains("Emitido em 03/11/2026").doesNotContain("Enviado em");
	}

	@Test
	void theValidityAndTheNotesAppearOnlyWhenPresent() throws Exception {
		String without = text(renderer.render(model(1, twoLines(), null, null)));
		String with = text(renderer.render(model(1, twoLines(), "Pagamento em até 6x sem juros", LocalDate.of(2026, 12, 15))));

		assertThat(without).doesNotContain("Válido até", "Observações");
		assertThat(with).contains("Válido até 15/12/2026", "Observações", "Pagamento em até 6x sem juros");
	}

	@Test
	void aMissingAddressAndPhoneSimplyDisappearFromTheHeader() throws Exception {
		BudgetPdfModel model = new BudgetPdfModel("Clínica Sorriso", null, "  ", "Maria", 1, 1, LocalDate.of(2026, 10, 27),
				LocalDate.of(2026, 10, 27), null, twoLines(), new BigDecimal("1300.00"), BigDecimal.ZERO.setScale(2),
				new BigDecimal("1300.00"), null);

		String text = text(renderer.render(model));

		assertThat(text).contains("Clínica Sorriso").doesNotContain("Telefone", "null");
	}

	@Test
	void fiftyItemsFlowOverSeveralPagesWithACorrectTotalPageCountAndStayWellUnderTheLimit() throws Exception {
		List<Line> lines = new ArrayList<>();
		for (int i = 1; i <= 50; i++) {
			lines.add(new Line("Procedimento " + i, 1, new BigDecimal("10.00"), BigDecimal.ZERO.setScale(2), new BigDecimal("10.00")));
		}

		byte[] pdf = renderer.render(model(1, lines, "obs", null));

		PdfReader reader = new PdfReader(pdf);
		int pages = reader.getNumberOfPages();
		reader.close();
		String text = text(pdf);
		assertThat(pages).isBetween(2, 4);
		assertThat(text).contains("Procedimento 1", "Procedimento 50", "Página " + pages + " de " + pages);
		assertThat(pdf.length).isLessThan(200 * 1024);
	}

	@Test
	void theMetadataNeverCarriesThePatientName() throws Exception {
		byte[] pdf = renderer.render(model(1, twoLines(), null, null));

		PdfReader reader = new PdfReader(pdf);
		String info = reader.getInfo().toString();
		reader.close();
		assertThat(info).contains("Orçamento nº 12").doesNotContain("Maria", "Conceição", "Souza");
	}

	@Test
	void charactersOutsideTheEncodingNeverBreakTheSend() throws Exception {
		BudgetPdfModel model = model(1, List.of(new Line("Clareamento 😀 日本語", 1, new BigDecimal("10.00"), BigDecimal.ZERO.setScale(2),
				new BigDecimal("10.00"))), "Observação com emoji 🎉", null);

		byte[] pdf = renderer.render(model);

		assertThat(text(pdf)).contains("Clareamento", "Observação com emoji");
	}

	@Test
	void theModelToStringNeverPrintsPatientNotesOrValues() {
		BudgetPdfModel model = model(2, twoLines(), "observação reservada", null);

		assertThat(model.toString()).doesNotContain("Maria", "Clínica", "reservada", "1250", "Restauração");
		assertThat(model.items().get(0).toString()).doesNotContain("Restauração", "250");
	}

	@Test
	void theSameModelAlwaysRendersTheSameText() throws Exception {
		BudgetPdfModel model = model(1, twoLines(), "obs", null);

		assertThat(text(renderer.render(model))).isEqualTo(text(renderer.render(model)));
	}
}
