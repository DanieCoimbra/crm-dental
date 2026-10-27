package crm_data.document.service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.format.DateTimeFormatter;

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

import crm_data.document.domain.DocumentType;

/**
 * Gera em memória o PDF do documento clínico (A4) com OpenPDF. Regra pura: recebe um {@link DocumentPdfModel} e devolve os
 * bytes; não toca banco, Storage nem relógio e não depende de Spring. Helvetica padrão com codificação {@code CP1252} (acentos
 * do português e {@code º}) sem embutir fonte; caracteres fora da codificação são omitidos. Datas em pt-BR. Os metadados
 * nunca levam nome de paciente.
 */
// OpenPDF 2.x marca o pacote com.lowagie.text como obsoleto (o pacote novo org.openpdf só existe na série 3.x); fixamos a 2.4.0
@SuppressWarnings("deprecation")
public class DocumentPdfRenderer {

	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
	private static final Color GRAY = new Color(90, 90, 90);
	private static final Color WATERMARK = new Color(215, 215, 215);

	/** Autor genérico dos metadados. */
	private static final String PRODUCER = "CRM Clínica";

	public byte[] render(DocumentPdfModel model) {
		ByteArrayOutputStream out = new ByteArrayOutputStream(16 * 1024);
		Document document = new Document(PageSize.A4, 40, 40, 40, 56);
		try {
			PdfWriter writer = PdfWriter.getInstance(document, out);
			BaseFont base = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.CP1252, BaseFont.NOT_EMBEDDED);
			BaseFont bold = BaseFont.createFont(BaseFont.HELVETICA_BOLD, BaseFont.CP1252, BaseFont.NOT_EMBEDDED);
			writer.setPageEvent(new PageDecorations(base, bold, model));
			document.addTitle(typeName(model.type()) + " nº " + model.number());
			document.addAuthor(PRODUCER);
			document.addCreator(PRODUCER);
			document.open();

			Fonts fonts = new Fonts(new Font(base, 11), new Font(base, 9, Font.NORMAL, GRAY), new Font(bold, 11),
					new Font(bold, 14), new Font(bold, 16));
			addHeader(document, model, fonts);
			addTitle(document, model, fonts);
			addIdentification(document, model, fonts);
			addBody(document, model, fonts);
			addSignature(document, model, fonts);
			document.close();
		}
		catch (DocumentException | IOException ex) {
			throw new IllegalStateException("Falha ao gerar o PDF do documento clínico.", ex);
		}
		return out.toByteArray();
	}

	private record Fonts(Font normal, Font small, Font bold, Font heading, Font title) {
	}

	/** Nome do tipo no singular, como aparece no título do arquivo e no rodapé. */
	static String typeName(DocumentType type) {
		return switch (type) {
			case CERTIFICATE -> "Atestado";
			case PRESCRIPTION -> "Receituário";
			case REFERRAL -> "Encaminhamento";
		};
	}

	private static void addHeader(Document document, DocumentPdfModel model, Fonts fonts) throws DocumentException {
		document.add(new Paragraph(safe(model.clinicName()), fonts.heading()));
		StringBuilder contact = new StringBuilder();
		if (hasText(model.clinicAddress())) {
			contact.append(model.clinicAddress());
		}
		if (hasText(model.clinicPhone())) {
			contact.append(contact.isEmpty() ? "" : " · ").append(model.clinicPhone());
		}
		if (!contact.isEmpty()) {
			document.add(new Paragraph(contact.toString(), fonts.small()));
		}
		PdfPTable rule = new PdfPTable(1);
		rule.setWidthPercentage(100);
		rule.setSpacingBefore(6);
		PdfPCell line = new PdfPCell(new Phrase(" ", fonts.small()));
		line.setBorder(Rectangle.BOTTOM);
		line.setBorderColor(GRAY);
		line.setFixedHeight(4);
		rule.addCell(line);
		document.add(rule);
	}

	private static void addTitle(Document document, DocumentPdfModel model, Fonts fonts) throws DocumentException {
		Paragraph title = new Paragraph(typeName(model.type()).toUpperCase(java.util.Locale.ROOT) + " Nº " + model.number(),
				fonts.title());
		title.setAlignment(Element.ALIGN_CENTER);
		title.setSpacingBefore(18);
		document.add(title);
		if (model.voidedOn() != null) {
			Paragraph voided = new Paragraph("Anulado em " + DATE.format(model.voidedOn()), fonts.bold());
			voided.setAlignment(Element.ALIGN_CENTER);
			document.add(voided);
		}
	}

	private static void addIdentification(Document document, DocumentPdfModel model, Fonts fonts) throws DocumentException {
		PdfPTable table = new PdfPTable(new float[] { 7f, 3f });
		table.setWidthPercentage(100);
		table.setSpacingBefore(18);
		Phrase patient = new Phrase();
		patient.add(new com.lowagie.text.Chunk("Paciente: ", fonts.bold()));
		patient.add(new com.lowagie.text.Chunk(safe(model.patientName()), fonts.normal()));
		PdfPCell left = new PdfPCell(patient);
		left.setBorder(Rectangle.NO_BORDER);
		left.setPaddingLeft(0);
		Phrase date = new Phrase();
		date.add(new com.lowagie.text.Chunk("Emissão: ", fonts.bold()));
		date.add(new com.lowagie.text.Chunk(DATE.format(model.issuedOn()), fonts.normal()));
		PdfPCell right = new PdfPCell(date);
		right.setBorder(Rectangle.NO_BORDER);
		right.setHorizontalAlignment(Element.ALIGN_RIGHT);
		table.addCell(left);
		table.addCell(right);
		document.add(table);
	}

	private static void addBody(Document document, DocumentPdfModel model, Fonts fonts) throws DocumentException {
		switch (model.type()) {
			case CERTIFICATE -> {
				int days = model.restDays() == null ? 0 : model.restDays();
				addParagraph(document, "Atesto, para os devidos fins, que " + safe(model.patientName())
						+ " esteve em atendimento odontológico nesta data e necessita de " + days + (days == 1 ? " dia" : " dias")
						+ " de afastamento de suas atividades.", fonts.normal());
				addComplement(document, model, fonts);
			}
			case REFERRAL -> {
				addParagraph(document, "Encaminho o(a) paciente " + safe(model.patientName()) + " para avaliação e conduta em "
						+ safe(model.targetSpecialty()) + ".", fonts.normal());
				addComplement(document, model, fonts);
			}
			case PRESCRIPTION -> {
				Paragraph heading = new Paragraph("Prescrição:", fonts.bold());
				heading.setSpacingBefore(24);
				document.add(heading);
				if (hasText(model.content())) {
					Paragraph text = new Paragraph(model.content(), fonts.normal());
					text.setSpacingBefore(6);
					text.setLeading(15);
					document.add(text);
				}
			}
		}
	}

	private static void addParagraph(Document document, String text, Font font) throws DocumentException {
		Paragraph paragraph = new Paragraph(text, font);
		paragraph.setSpacingBefore(24);
		paragraph.setLeading(18);
		paragraph.setAlignment(Element.ALIGN_JUSTIFIED);
		document.add(paragraph);
	}

	private static void addComplement(Document document, DocumentPdfModel model, Fonts fonts) throws DocumentException {
		if (hasText(model.content())) {
			Paragraph complement = new Paragraph(model.content(), fonts.normal());
			complement.setSpacingBefore(12);
			complement.setLeading(15);
			document.add(complement);
		}
	}

	private static void addSignature(Document document, DocumentPdfModel model, Fonts fonts) throws DocumentException {
		PdfPTable signature = new PdfPTable(1);
		signature.setWidthPercentage(60);
		signature.setSpacingBefore(70);
		signature.setKeepTogether(true);
		signature.addCell(signatureCell("___________________________", fonts.normal()));
		signature.addCell(signatureCell(safe(model.dentistName()), fonts.bold()));
		if (hasText(model.dentistCro()) && hasText(model.dentistCroState())) {
			signature.addCell(signatureCell("CRO " + model.dentistCro() + "/" + model.dentistCroState(), fonts.normal()));
		}
		document.add(signature);
	}

	private static PdfPCell signatureCell(String text, Font font) {
		PdfPCell cell = new PdfPCell(new Phrase(text, font));
		cell.setBorder(Rectangle.NO_BORDER);
		cell.setHorizontalAlignment(Element.ALIGN_CENTER);
		cell.setPadding(1);
		return cell;
	}

	private static String safe(String value) {
		return value == null ? "" : value;
	}

	private static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	/** Marca "ANULADO" sob o texto (todas as páginas) e rodapé "{Tipo} nº n — página x de y". */
	private static final class PageDecorations extends PdfPageEventHelper {

		private static final float SIZE = 9f;

		private final BaseFont base;
		private final BaseFont bold;
		private final DocumentPdfModel model;
		private PdfTemplate total;

		PageDecorations(BaseFont base, BaseFont bold, DocumentPdfModel model) {
			this.base = base;
			this.bold = bold;
			this.model = model;
		}

		@Override
		public void onOpenDocument(PdfWriter writer, Document document) {
			total = writer.getDirectContent().createTemplate(30, 12);
		}

		@Override
		public void onStartPage(PdfWriter writer, Document document) {
			if (model.voidedOn() == null) {
				return;
			}
			PdfContentByte under = writer.getDirectContentUnder();
			under.saveState();
			under.beginText();
			under.setFontAndSize(bold, 80);
			under.setColorFill(WATERMARK);
			under.showTextAligned(Element.ALIGN_CENTER, "ANULADO", (document.left() + document.right()) / 2,
					(document.top() + document.bottom()) / 2, 45);
			under.endText();
			under.restoreState();
		}

		@Override
		public void onEndPage(PdfWriter writer, Document document) {
			PdfContentByte canvas = writer.getDirectContent();
			String text = typeName(model.type()) + " nº " + model.number() + " — página " + writer.getPageNumber() + " de ";
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
