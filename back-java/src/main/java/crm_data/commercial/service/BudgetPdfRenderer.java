package crm_data.commercial.service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfTemplate;
import com.lowagie.text.pdf.PdfWriter;

/**
 * Gera em memória o PDF simples do orçamento (A4, 1 a 3 páginas) com OpenPDF. Regra pura: recebe um
 * {@link BudgetPdfModel} e devolve os bytes; não toca banco, Storage nem relógio e não depende de Spring. Usa a Helvetica
 * padrão do PDF com codificação {@code CP1252} (cobre acentos do português, {@code º} e {@code R$}) sem embutir fonte,
 * então não precisa de fonte instalada no contêiner. Caracteres fora da codificação são omitidos, nunca derrubam o envio.
 * Formata em pt-BR ({@code R$ 1.234,50}, {@code dd/MM/aaaa}).
 */
// OpenPDF 2.x marca o pacote com.lowagie.text como obsoleto (o pacote novo org.openpdf só existe na série 3.x); fixamos a 2.4.0
@SuppressWarnings("deprecation")
public class BudgetPdfRenderer {

	private static final Locale PT_BR = Locale.of("pt", "BR");
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
	private static final Color GRAY = new Color(90, 90, 90);
	private static final Color HEADER_BG = new Color(235, 235, 235);

	/** Autor genérico dos metadados: o PDF nunca grava nome de paciente em metadado. */
	private static final String PRODUCER = "CRM Clínica";

	public byte[] render(BudgetPdfModel model) {
		ByteArrayOutputStream out = new ByteArrayOutputStream(32 * 1024);
		Document document = new Document(PageSize.A4, 40, 40, 40, 56);
		try {
			PdfWriter writer = PdfWriter.getInstance(document, out);
			BaseFont base = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.CP1252, BaseFont.NOT_EMBEDDED);
			BaseFont bold = BaseFont.createFont(BaseFont.HELVETICA_BOLD, BaseFont.CP1252, BaseFont.NOT_EMBEDDED);
			writer.setPageEvent(new Footer(base));
			document.addTitle("Orçamento nº " + model.number());
			document.addAuthor(PRODUCER);
			document.addCreator(PRODUCER);
			document.open();

			Fonts fonts = new Fonts(new Font(base, 10), new Font(base, 9, Font.NORMAL, GRAY), new Font(bold, 10),
					new Font(bold, 14), new Font(bold, 16));
			addHeader(document, model, fonts);
			addTitle(document, model, fonts);
			addItems(document, model, fonts);
			addTotals(document, model, fonts);
			addNotes(document, model, fonts);
			document.close();
		}
		catch (DocumentException | IOException ex) {
			throw new IllegalStateException("Falha ao gerar o PDF do orçamento.", ex);
		}
		return out.toByteArray();
	}

	private record Fonts(Font normal, Font small, Font bold, Font heading, Font title) {
	}

	private static void addHeader(Document document, BudgetPdfModel model, Fonts fonts) throws DocumentException {
		document.add(new Paragraph(safe(model.clinicName()), fonts.heading()));
		if (hasText(model.clinicAddress())) {
			document.add(new Paragraph(model.clinicAddress(), fonts.small()));
		}
		if (hasText(model.clinicPhone())) {
			document.add(new Paragraph("Telefone: " + model.clinicPhone(), fonts.small()));
		}
		document.add(spacer(10));
	}

	private static void addTitle(Document document, BudgetPdfModel model, Fonts fonts) throws DocumentException {
		String title = "Orçamento nº " + model.number() + (model.revision() > 1 ? " — Revisão " + model.revision() : "");
		document.add(new Paragraph(title, fonts.title()));
		// orçamento nunca enviado (rascunho descartado) não pode dizer "Enviado em"
		String stamp = model.sentOn() != null ? "Enviado em " + DATE.format(model.sentOn())
				: "Emitido em " + DATE.format(model.issuedOn());
		document.add(new Paragraph(stamp, fonts.normal()));
		if (model.revision() > 1) {
			document.add(new Paragraph("Revisão gerada em " + DATE.format(model.issuedOn()), fonts.normal()));
		}
		if (model.validUntil() != null) {
			document.add(new Paragraph("Válido até " + DATE.format(model.validUntil()), fonts.normal()));
		}
		Paragraph patient = new Paragraph();
		patient.setSpacingBefore(8);
		patient.add(new Phrase("Paciente: ", fonts.bold()));
		patient.add(new Phrase(safe(model.patientName()), fonts.normal()));
		document.add(patient);
		document.add(spacer(8));
	}

	private static void addItems(Document document, BudgetPdfModel model, Fonts fonts) throws DocumentException {
		PdfPTable table = new PdfPTable(new float[] { 5f, 1.2f, 2.2f, 2.2f, 2.2f });
		table.setWidthPercentage(100);
		table.setHeaderRows(1);
		table.setSplitRows(false);
		for (String heading : new String[] { "Descrição", "Qtd", "Preço unit.", "Desconto", "Total" }) {
			PdfPCell cell = cell(heading, fonts.bold(), heading.equals("Descrição") ? Element.ALIGN_LEFT : Element.ALIGN_RIGHT);
			cell.setBackgroundColor(HEADER_BG);
			table.addCell(cell);
		}
		for (BudgetPdfModel.Line line : model.items()) {
			table.addCell(cell(safe(line.description()), fonts.normal(), Element.ALIGN_LEFT));
			table.addCell(cell(Integer.toString(line.quantity()), fonts.normal(), Element.ALIGN_RIGHT));
			table.addCell(cell(money(line.unitPrice()), fonts.normal(), Element.ALIGN_RIGHT));
			table.addCell(cell(money(line.discount()), fonts.normal(), Element.ALIGN_RIGHT));
			table.addCell(cell(money(line.lineTotal()), fonts.normal(), Element.ALIGN_RIGHT));
		}
		document.add(table);
	}

	private static void addTotals(Document document, BudgetPdfModel model, Fonts fonts) throws DocumentException {
		PdfPTable totals = new PdfPTable(new float[] { 7f, 3f });
		totals.setWidthPercentage(100);
		totals.setSpacingBefore(10);
		totals.setKeepTogether(true);
		totals.addCell(borderless("Subtotal", fonts.normal(), Element.ALIGN_RIGHT));
		totals.addCell(borderless(money(model.subtotal()), fonts.normal(), Element.ALIGN_RIGHT));
		totals.addCell(borderless("Desconto", fonts.normal(), Element.ALIGN_RIGHT));
		totals.addCell(borderless(money(model.discount()), fonts.normal(), Element.ALIGN_RIGHT));
		totals.addCell(borderless("TOTAL", fonts.bold(), Element.ALIGN_RIGHT));
		totals.addCell(borderless(money(model.total()), fonts.bold(), Element.ALIGN_RIGHT));
		document.add(totals);
	}

	private static void addNotes(Document document, BudgetPdfModel model, Fonts fonts) throws DocumentException {
		if (!hasText(model.notes())) {
			return;
		}
		Paragraph heading = new Paragraph("Observações", fonts.bold());
		heading.setSpacingBefore(14);
		document.add(heading);
		document.add(new Paragraph(model.notes(), fonts.normal()));
	}

	private static PdfPCell cell(String text, Font font, int align) {
		PdfPCell cell = new PdfPCell(new Phrase(text, font));
		cell.setHorizontalAlignment(align);
		cell.setPadding(5);
		cell.setBorderColor(new Color(200, 200, 200));
		return cell;
	}

	private static PdfPCell borderless(String text, Font font, int align) {
		PdfPCell cell = new PdfPCell(new Phrase(text, font));
		cell.setHorizontalAlignment(align);
		cell.setBorder(Rectangle.NO_BORDER);
		cell.setPadding(3);
		return cell;
	}

	private static Paragraph spacer(float height) {
		Paragraph spacer = new Paragraph(" ");
		spacer.setLeading(height);
		return spacer;
	}

	/** {@code R$ 1.234,50} (pt-BR). */
	private static String money(BigDecimal value) {
		return NumberFormat.getCurrencyInstance(PT_BR).format(value);
	}

	private static String safe(String value) {
		return value == null ? "" : value;
	}

	private static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	/** Rodapé "Página x de y": o total entra num molde preenchido ao fechar o documento. */
	private static final class Footer extends PdfPageEventHelper {

		private static final float SIZE = 9f;

		private final BaseFont base;
		private PdfTemplate total;

		Footer(BaseFont base) {
			this.base = base;
		}

		@Override
		public void onOpenDocument(PdfWriter writer, Document document) {
			total = writer.getDirectContent().createTemplate(30, 12);
		}

		@Override
		public void onEndPage(PdfWriter writer, Document document) {
			PdfContentByte canvas = writer.getDirectContent();
			String text = "Página " + writer.getPageNumber() + " de ";
			float width = base.getWidthPoint(text, SIZE);
			float x = (document.left() + document.right()) / 2 - (width + 12) / 2;
			canvas.beginText();
			canvas.setFontAndSize(base, SIZE);
			canvas.setTextMatrix(x, 30);
			canvas.showText(text);
			canvas.endText();
			canvas.addTemplate(total, x + width, 30);
		}

		@Override
		public void onCloseDocument(PdfWriter writer, Document document) {
			total.beginText();
			total.setFontAndSize(base, SIZE);
			total.setTextMatrix(0, 0);
			total.showText(Integer.toString(writer.getPageNumber() - 1));
			total.endText();
		}
	}
}
